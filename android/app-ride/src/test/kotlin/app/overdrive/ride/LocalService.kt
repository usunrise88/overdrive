package app.overdrive.ride

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A stand-in for the ride intake of `server/` on the loopback address (ADR 0014 section 3), for the tests that run the real
 * container: bearer authentication, `PUT /v1/rides/{id}` and `PUT /v1/rides/{id}/fit`, answered as the real service does.
 * [down] makes every request answer 503.
 */
class LocalService(
    val token: String = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7",
) : AutoCloseable {
    class Received(
        val path: String,
        val authorization: String?,
        val body: ByteArray,
    )

    val received = CopyOnWriteArrayList<Received>()

    @Volatile
    var down = false

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
            val body = exchange.requestBody.readBytes()
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path
            received += Received(path, auth, body)
            val (status, text) = answer(path, auth, body)
            val bytes = text.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (status == 401) exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"overdrive\"")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun answer(
        path: String,
        auth: String?,
        body: ByteArray,
    ): Pair<Int, String> {
        if (down) return 503 to error("internal_error", "Down for the test")
        if (auth != "Bearer $token") return 401 to error("unauthorized", "Missing or invalid bearer token")
        val match = ROUTE.matchEntire(path) ?: return 404 to error("not_found", "No such route")
        val id = match.groupValues[1]
        val store = if (match.groupValues[2].isEmpty()) records else fits
        if (store === fits && !records.containsKey(id)) return 404 to error("ride_not_found", "Not stored yet")
        val before = store.putIfAbsent(id, body) ?: return 201 to ok(id, "stored")
        return if (before.contentEquals(body)) {
            200 to ok(id, "already-stored")
        } else {
            409 to
                error("ride_conflict", "Other")
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun ok(
        id: String,
        what: String,
    ) = """{"rideId":"$id","status":"$what"}"""

    private fun error(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message"}}"""

    private companion object {
        val ROUTE = Regex("/v1/rides/([A-Za-z0-9][A-Za-z0-9._-]{0,127})(/fit)?")
    }
}
