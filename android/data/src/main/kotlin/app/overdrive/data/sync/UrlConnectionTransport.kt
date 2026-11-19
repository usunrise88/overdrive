package app.overdrive.data.sync

import app.overdrive.core.sync.ChunkSink
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchRequest
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpPostBytesRequest
import app.overdrive.core.sync.HttpPostBytesTransport
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.HttpRequest
import app.overdrive.core.sync.HttpStreamRequest
import app.overdrive.core.sync.HttpStreamTransport
import app.overdrive.core.sync.HttpTransport
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.sync.UploadBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * [HttpTransport] over `HttpURLConnection` (ADR 0014 section 6): no new dependency, no Play Services. One `PUT` per call
 * with the body **streamed** from its source (a fixed length, so nothing is buffered in memory), `Authorization: Bearer`, and
 * timeouts on connecting and on waiting for the answer. Redirects are not followed. The blocking socket work runs on
 * [dispatcher], never on the main thread.
 *
 * It is also the [HttpGetTransport] of the planned workouts (ADR 0017): one `GET` with the same stack, the same timeouts, the
 * same bearer header and the same rules (no redirects, nothing logged), whose answer is read up to the request's `maxBytes`.
 * And it is the [HttpPatchTransport] of the rider's profile (ADR 0021): one `PATCH` with a small JSON body
 * (`application/json`), the same stack and rules. Android's `HttpURLConnection` (OkHttp inside) knows the verb PATCH; the JDK's
 * does not, which only matters to a test on a plain JVM (see [setPatchMethod]). And it is the [HttpPostTransport] of the
 * companion's FTP answer (ADR 0024): one `POST` with a small JSON body, the same stack and rules again. And it is the
 * [HttpPostBytesTransport] of the outdoor ride's FIT file (ADR 0028): one `POST` of up to 16 MiB of bytes with a fixed length, sent in
 * pieces so a cancelled request stops, with `Content-Type` as the request says (`application/vnd.ant.fit`); the body is the rider's ride,
 * so it is kept out of every failure and every printed request, like the token. And it is the [HttpStreamTransport] of the live coach's cue stream
 * (docs/adr/0039): a `GET` read **as it arrives** (`text/event-stream`), with a read timeout of its own, longer than the service's 15 s heartbeat,
 * so a connection that goes quiet is broken and not waited on for ever; cancelling the request closes the connection.
 *
 * **It logs nothing and keeps neither the token nor the body anywhere but the request being sent.** What went wrong without an
 * answer is a [TransportException] whose message names only the kind of failure; its hint for the owner is a fixed vocabulary
 * (the exception's class and the system's error name, never an address), see [NetworkFailures].
 *
 * @param connectTimeoutMillis how long to wait for the connection (and, for TLS, the handshake).
 * @param readTimeoutMillis how long to wait for the server's answer once the body is sent, and between two of its bytes.
 * @param setPatchMethod makes a connection a `PATCH` one. The default is the plain `requestMethod = "PATCH"`, which is what
 * Android runs; a test on a JDK, whose `HttpURLConnection` refuses that verb, passes a way round it.
 */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val setPatchMethod: (HttpURLConnection) -> Unit = { it.requestMethod = "PATCH" },
    private val streamReadTimeoutMillis: Int = STREAM_READ_TIMEOUT_MILLIS,
) : HttpTransport,
    HttpGetTransport,
    HttpPatchTransport,
    HttpPostTransport,
    HttpPostBytesTransport,
    HttpStreamTransport {
    /** Where the blocking reads of event streams run, **detached from the caller**: see [stream]. */
    private val streamScope = CoroutineScope(SupervisorJob() + dispatcher)

    override suspend fun get(request: HttpGetRequest): HttpAnswer =
        withContext(dispatcher) {
            val connection =
                try {
                    URL(request.url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw TransportException(TransportFailure.Other, e)
                } catch (e: ClassCastException) {
                    throw TransportException(TransportFailure.Other, e)
                }
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                HttpAnswer(status, stream?.use { readAtMost(it, request.maxBytes) }.orEmpty())
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw NetworkFailures.exceptionOf(e)
            } finally {
                connection.disconnect()
            }
        }

    /**
     * `GET` as an event stream: the text of a `200` is given to [onChunk] as it is read, until the server ends the response (the answer is then `200`
     * with no body), the connection breaks or goes quiet for [streamReadTimeoutMillis] (a [TransportException]: the service writes a heartbeat every
     * 15 s, so a healthy stream never does), or the coroutine is cancelled. Any other status is returned with the start of its body and `onChunk` is
     * not called. Text is decoded as UTF-8 by a reader that holds half a character until its other half arrives.
     *
     * **A read that is waiting cannot be cancelled**, and whether closing the connection wakes it depends on the stack (Android's `HttpURLConnection` does:
     * the call is cancelled; the JDK's waits for the next byte). So the blocking read runs in a coroutine of its own ([streamScope]) that hands the
     * text to this one through a channel: **cancelling returns at once**, closes the connection from yet another coroutine so that nothing here waits for
     * it, and a reader that is still blocked ends with its read timeout and gives up its thread then. Nothing the reader reads after the cancel is used.
     */
    override suspend fun stream(
        request: HttpStreamRequest,
        onChunk: suspend (String) -> Unit,
    ): HttpAnswer {
        val items = Channel<Any>(Channel.UNLIMITED)
        val open = AtomicReference<HttpURLConnection?>(null)
        val reader =
            streamScope.launch {
                try {
                    items.trySend(readStream(request, open) { items.trySend(it) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    items.trySend(e)
                }
            }
        try {
            while (true) {
                when (val item = items.receive()) {
                    is String -> onChunk(item)
                    is HttpAnswer -> return item
                    is Exception -> throw item
                }
            }
        } finally {
            reader.cancel()
            open.get()?.let { connection -> streamScope.launch { connection.disconnect() } }
        }
    }

    /** The blocking half of [stream]: opens the connection (leaving it in [open] for the caller to close) and gives the text to [give] as it is read. */
    private suspend fun readStream(
        request: HttpStreamRequest,
        open: AtomicReference<HttpURLConnection?>,
        give: (String) -> Unit,
    ): HttpAnswer {
        val connection =
            try {
                URL(request.url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw TransportException(TransportFailure.Other, e)
            } catch (e: ClassCastException) {
                throw TransportException(TransportFailure.Other, e)
            }
        open.set(connection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = streamReadTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Cache-Control", "no-cache")
            request.lastEventId?.let { connection.setRequestProperty("Last-Event-ID", it) }
            val status = connection.responseCode
            if (status != HTTP_OK) {
                val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                return HttpAnswer(status, stream?.use(::readStart).orEmpty())
            }
            InputStreamReader(connection.inputStream, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(STREAM_CHUNK_CHARS)
                while (true) {
                    val read = reader.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (read < 0) break
                    if (read > 0) give(String(buffer, 0, read))
                }
            }
            return HttpAnswer(HTTP_OK)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw NetworkFailures.exceptionOf(e)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun put(request: HttpRequest): HttpAnswer =
        withContext(dispatcher) {
            val job = currentCoroutineContext().job
            val connection =
                try {
                    URL(request.url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw TransportException(TransportFailure.Other, e)
                } catch (e: ClassCastException) {
                    throw TransportException(TransportFailure.Other, e)
                }
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${request.bearerToken}")
                connection.setRequestProperty("Content-Type", request.contentType)
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(request.body.length)
                connection.outputStream.use { out ->
                    request.body.writeTo(
                        ChunkSink { bytes, offset, length ->
                            job.ensureActive()
                            out.write(bytes, offset, length)
                        },
                    )
                }
                val status = connection.responseCode
                val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                HttpAnswer(status, stream?.use(::readStart).orEmpty())
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw NetworkFailures.exceptionOf(e)
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun patch(request: HttpPatchRequest): HttpAnswer =
        sendJson(request.url, request.bearerToken, request.body, request.maxBytes, setPatchMethod)

    override suspend fun post(request: HttpPostRequest): HttpAnswer =
        sendJson(request.url, request.bearerToken, request.body, request.maxBytes) { it.requestMethod = "POST" }

    override suspend fun postBytes(request: HttpPostBytesRequest): HttpAnswer =
        sendBody(request.url, request.bearerToken, request.body, request.contentType, request.maxBytes) {
            it.requestMethod = "POST"
        }

    /** One request with a small JSON body of known length ([PATCH][patch] and [POST][post]), the answer read up to [maxBytes]. */
    private suspend fun sendJson(
        url: String,
        bearerToken: String,
        text: String,
        maxBytes: Int,
        setMethod: (HttpURLConnection) -> Unit,
    ): HttpAnswer = sendBody(url, bearerToken, text.encodeToByteArray(), "application/json", maxBytes, setMethod)

    /**
     * One request with a body of known length, in pieces (a cancelled request stops between two of them), the answer read up to
     * [maxBytes]. The body is only ever written to the connection: it is in no exception and no message.
     */
    private suspend fun sendBody(
        url: String,
        bearerToken: String,
        body: ByteArray,
        contentType: String,
        maxBytes: Int,
        setMethod: (HttpURLConnection) -> Unit,
    ): HttpAnswer =
        withContext(dispatcher) {
            val job = currentCoroutineContext().job
            val connection =
                try {
                    URL(url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw TransportException(TransportFailure.Other, e)
                } catch (e: ClassCastException) {
                    throw TransportException(TransportFailure.Other, e)
                }
            try {
                setMethod(connection)
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { out ->
                    var at = 0
                    while (at < body.size) {
                        job.ensureActive()
                        val count = minOf(WRITE_CHUNK_BYTES, body.size - at)
                        out.write(body, at, count)
                        at += count
                    }
                }
                val status = connection.responseCode
                val stream = if (status >= HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                HttpAnswer(status, stream?.use { readAtMost(it, maxBytes) }.orEmpty())
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw NetworkFailures.exceptionOf(e)
            } finally {
                connection.disconnect()
            }
        }

    /** The first [MAX_ANSWER_BYTES] of an answer: an error message or the upload response is far shorter. */
    private fun readStart(stream: InputStream): String {
        val buffer = ByteArray(MAX_ANSWER_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = stream.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return String(buffer, 0, filled, Charsets.UTF_8)
    }

    /**
     * All of [stream], which must not be longer than [maxBytes]: an answer that is longer is not read on and fails the request
     * (`Other`), so a server that never stops costs one failed refresh and not the tablet's memory.
     */
    private fun readAtMost(
        stream: InputStream,
        maxBytes: Int,
    ): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > maxBytes) throw TransportException(TransportFailure.Other)
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 30_000

        /** The cue stream's read timeout: longer than the service's 15 s heartbeat, shorter than the 30 s every other request waits (docs/adr/0038). */
        const val STREAM_READ_TIMEOUT_MILLIS = 25_000
        private const val STREAM_CHUNK_CHARS = 8_192
        private const val HTTP_OK = 200
        private const val MAX_ANSWER_BYTES = 4_096
        private const val READ_CHUNK_BYTES = 8_192
        private const val WRITE_CHUNK_BYTES = 64 * 1024
        private const val HTTP_BAD_REQUEST = 400
    }
}

/**
 * An [UploadBody] that streams a file in pieces. Its length is the file's at the moment it is asked; a file that changes size
 * while it is sent fails the upload (the bytes written must match the length the request promised) instead of sending a
 * damaged body, and the ride is tried again later.
 */
class FileBody(
    private val file: File,
) : UploadBody {
    override val length: Long get() = file.length()

    override fun writeTo(sink: ChunkSink) {
        val expected = length
        var sent = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sent += read
                if (sent > expected) throw IOException("The file grew while it was sent")
                sink.write(buffer, 0, read)
            }
        }
        if (sent != expected) throw IOException("The file shrank while it was sent")
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
    }
}
