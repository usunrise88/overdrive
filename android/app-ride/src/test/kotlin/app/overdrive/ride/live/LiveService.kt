package app.overdrive.ride.live

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the live coach routes of `server/` (ADR 0038) on the loopback address, with real sockets: `POST /v1/live/sessions`, the snapshots, the end and the
 * `played` reports, and the cue stream as `text/event-stream` that is flushed event by event and **closed properly** (the exchange is closed when the test ends the
 * stream or the client goes away). Every request is recorded with its bearer token; what the service answers is the test's to change. All the data is invented.
 */
class LiveService(
    val token: String = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7",
) : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
        val lastEventId: String? = null,
    )

    val requests = CopyOnWriteArrayList<Received>()

    /** What `POST /v1/live/sessions` answers: the status and the body; a `{id}` in the body is replaced by the next session's id. */
    @Volatile
    var startStatus = 201

    @Volatile
    var startBody =
        """{"sessionId":"{id}","coach":{"on":true,"reason":null},"serverTime":1795000000456,"snapshotEverySeconds":5}"""

    /** Statuses for the next starts, one each, before [startStatus] applies again: a service that is down for two starts and then up. */
    val startQueue = ConcurrentLinkedQueue<Int>()

    /** The status of a snapshot: 204 while the session lives, 404 when the service has forgotten it. */
    @Volatile
    var snapshotStatus = 204

    /** The status of the cue stream's `GET`; anything but 200 is the answer and no stream. */
    @Volatile
    var streamStatus = 200

    private val sessions = AtomicInteger()

    /** How many cue streams have been opened. */
    val streamsOpened = AtomicInteger()
    private val streams = LinkedBlockingQueue<Stream>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** One open cue stream: [send] writes text to it, [end] closes it. */
    inner class Stream(
        private val exchange: HttpExchange,
    ) {
        private val done = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var clientGone = false

        fun send(text: String) {
            try {
                exchange.responseBody.write(text.encodeToByteArray())
                exchange.responseBody.flush()
            } catch (e: IOException) {
                clientGone = true
                done.countDown()
            }
        }

        fun end() {
            done.countDown()
        }

        internal fun hold() {
            // Like the service: a comment now and then, as long as the client listens, so that a client that went away is noticed.
            while (!done.await(25, TimeUnit.MILLISECONDS)) send(": hb\n\n")
        }
    }

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    /** The next cue stream a client opens (waits a few seconds for it), or null. */
    fun awaitStream(seconds: Long = 5): Stream? = streams.poll(seconds, TimeUnit.SECONDS)

    fun requestsTo(suffix: String): List<Received> = requests.filter { it.path.endsWith(suffix) }

    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.readBytes().decodeToString()
            val path = exchange.requestURI.path
            val auth = exchange.requestHeaders.getFirst("Authorization")
            requests +=
                Received(exchange.requestMethod, path, auth, body, exchange.requestHeaders.getFirst("Last-Event-ID"))
            if (auth != "Bearer $token") {
                reply(exchange, 401, """{"error":{"code":"unauthorized","message":"No"}}""")
                return
            }
            when {
                path == "/v1/live/sessions" && exchange.requestMethod == "POST" -> {
                    val status = startQueue.poll() ?: startStatus
                    reply(
                        exchange,
                        status,
                        if (status in
                            200..299
                        ) {
                            startBody.replace("{id}", "ls-test-${sessions.incrementAndGet()}")
                        } else {
                            UNAVAILABLE
                        },
                    )
                }

                path.endsWith("/snapshots") -> {
                    reply(exchange, snapshotStatus, if (snapshotStatus == 204) "" else NOT_FOUND)
                }

                path.endsWith("/cues") && exchange.requestMethod == "GET" -> {
                    stream(exchange)
                }

                path.endsWith("/end") -> {
                    reply(exchange, 200, """{"sessionId":"${path.split('/')[4]}","status":"ended","cues":1}""")
                }

                path.contains("/cues/") && path.endsWith("/played") -> {
                    reply(exchange, 204, "")
                }

                else -> {
                    reply(exchange, 404, NOT_FOUND)
                }
            }
        } catch (e: IOException) {
            // The client went away.
        } finally {
            exchange.close()
        }
    }

    private fun reply(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    private fun stream(exchange: HttpExchange) {
        if (streamStatus != 200) {
            reply(exchange, streamStatus, NOT_FOUND)
            return
        }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache, no-transform")
        exchange.sendResponseHeaders(200, 0)
        val stream = Stream(exchange)
        stream.send("retry: 3000\n\n: open\n\n")
        streams.add(stream)
        streamsOpened.incrementAndGet()
        stream.hold()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        const val NOT_FOUND = """{"error":{"code":"not_found","message":"No such session"}}"""
        const val UNAVAILABLE = """{"error":{"code":"unavailable","message":"Not now"}}"""
    }
}
