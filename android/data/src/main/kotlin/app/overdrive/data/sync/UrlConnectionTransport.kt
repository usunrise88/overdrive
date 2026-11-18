package app.overdrive.data.sync

import app.overdrive.core.sync.ChunkSink
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchRequest
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.HttpRequest
import app.overdrive.core.sync.HttpTransport
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.sync.UploadBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
 * companion's FTP answer (ADR 0024): one `POST` with a small JSON body, the same stack and rules again.
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
) : HttpTransport,
    HttpGetTransport,
    HttpPatchTransport,
    HttpPostTransport {
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

    /** One request with a small JSON body of known length ([PATCH][patch] and [POST][post]), the answer read up to [maxBytes]. */
    private suspend fun sendJson(
        url: String,
        bearerToken: String,
        text: String,
        maxBytes: Int,
        setMethod: (HttpURLConnection) -> Unit,
    ): HttpAnswer =
        withContext(dispatcher) {
            val connection =
                try {
                    URL(url).openConnection() as HttpURLConnection
                } catch (e: IOException) {
                    throw TransportException(TransportFailure.Other, e)
                } catch (e: ClassCastException) {
                    throw TransportException(TransportFailure.Other, e)
                }
            try {
                val body = text.encodeToByteArray()
                setMethod(connection)
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
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
        private const val MAX_ANSWER_BYTES = 4_096
        private const val READ_CHUNK_BYTES = 8_192
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
