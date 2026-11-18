package app.overdrive.data.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A stand-in for the ride intake of `server/` (ADR 0014 section 3), on the loopback address with a port of its own: bearer
 * authentication, `PUT /v1/rides/{id}` (201 stored, 200 identical repeat, 409 another record under the id) and
 * `PUT /v1/rides/{id}/fit` (404 before its record, 201, 200, 409), the same bodies and error envelope. A test queues
 * [Reply]s in [script] to make the next requests answer something else (a 503, a 401, a slow answer).
 */
class FakeService(
    val token: String = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7",
) : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: ByteArray,
    )

    class Reply(
        val status: Int,
        val body: String = "",
        val delayMillis: Long = 0,
        val headers: Map<String, String> = emptyMap(),
    )

    val received = CopyOnWriteArrayList<Received>()
    val script = ConcurrentLinkedQueue<Reply>()

    private val records = ConcurrentHashMap<String, ByteArray>()
    private val fits = ConcurrentHashMap<String, ByteArray>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun storedRecord(id: String): ByteArray? = records[id]

    fun storedFit(id: String): ByteArray? = fits[id]

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            // Like Fastify behind Caddy: the body is always read, so a client that is still writing is never cut off.
            val body = exchange.requestBody.readBytes()
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path
            received +=
                Received(exchange.requestMethod, path, auth, exchange.requestHeaders.getFirst("Content-Type"), body)
            val scripted = script.poll()
            val reply =
                if (scripted == null ||
                    scripted === PASS
                ) {
                    answer(exchange.requestMethod, path, auth, body)
                } else {
                    scripted
                }
            if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
            val bytes = reply.body.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        }
    }

    private fun answer(
        method: String,
        path: String,
        auth: String?,
        body: ByteArray,
    ): Reply {
        if (auth != "Bearer $token") {
            val denied = error(401, "unauthorized", "Missing or invalid bearer token")
            return Reply(
                denied.status,
                denied.body,
                headers = mapOf("WWW-Authenticate" to "Bearer realm=\"overdrive\""),
            )
        }
        val match = ROUTE.matchEntire(path)
        if (method != "PUT" || match == null) return error(404, "not_found", "No such route")
        val id = match.groupValues[1]
        return if (match.groupValues[2].isEmpty()) putRecord(id, body) else putFit(id, body)
    }

    private fun putRecord(
        id: String,
        body: ByteArray,
    ): Reply {
        val stored = records.putIfAbsent(id, body) ?: return ok(201, id, "stored")
        return if (stored.contentEquals(body)) {
            ok(200, id, "already-stored")
        } else {
            error(409, "ride_conflict", "Ride $id is already stored with different content")
        }
    }

    private fun putFit(
        id: String,
        body: ByteArray,
    ): Reply {
        if (!records.containsKey(
                id,
            )
        ) {
            return error(404, "ride_not_found", "Ride $id is not stored yet; upload its record first")
        }
        val stored = fits.putIfAbsent(id, body) ?: return ok(201, id, "stored")
        return if (stored.contentEquals(body)) {
            ok(200, id, "already-stored")
        } else {
            error(409, "fit_conflict", "Ride $id already has a different FIT file")
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        /** In [script]: this request is answered the normal way. */
        val PASS = Reply(-1)

        private val ROUTE = Regex("/v1/rides/([A-Za-z0-9][A-Za-z0-9._-]{0,127})(/fit)?")

        fun ok(
            status: Int,
            id: String,
            what: String,
        ) = Reply(status, """{"rideId":"$id","status":"$what"}""")

        fun error(
            status: Int,
            code: String,
            message: String,
        ) = Reply(status, """{"error":{"code":"$code","message":"$message"}}""")
    }
}
