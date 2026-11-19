package app.overdrive.ride

import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileApiJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A stand-in for the ride intake of `server/` on the loopback address (ADR 0014 section 3), for the tests that run the real
 * container: bearer authentication, `PUT /v1/rides/{id}` and `PUT /v1/rides/{id}/fit`, answered as the real service does, and
 * `GET /v1/workouts` (ADR 0017), which answers [plan] with [planStatus], and `GET`/`PATCH /v1/profile` (ADR 0021), which hold
 * [profile]. `GET /v1/routes` answers [routes] and `GET /v1/routes/{id}` the body in [routePoints] (ADR 0037), and `GET /v1/recovery`
 * answers [recovery]; each is a 404 until a test sets it, and each is counted apart from the ride upload's [received]. [down] makes
 * every request answer 503.
 */
class LocalService(
    val token: String = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7",
) : AutoCloseable {
    class Received(
        val path: String,
        val authorization: String?,
        val body: ByteArray,
        val method: String = "PUT",
        val query: String? = null,
    )

    val received = CopyOnWriteArrayList<Received>()

    @Volatile
    var down = false

    /** What `GET /v1/workouts` answers with (the planned workouts' JSON), and with which status. */
    @Volatile
    var plan: String = """{"error":{"code":"not_found","message":"No plan"}}"""

    @Volatile
    var planStatus = 404

    /** The GETs of the plan, in order. They are kept apart from [received], which is the ride upload's. */
    val planRequests = CopyOnWriteArrayList<Received>()

    /**
     * The rider's profile as the service holds it (ADR 0021): `GET /v1/profile` answers it, `PATCH /v1/profile` applies a patch to
     * it **as the real service does** (read strictly, applied, validated as the profile it makes) and answers the result. Null is
     * a service without the route: 404. A test changes it, as an AI client's `update_profile` would.
     */
    @Volatile
    var profile: Profile? = null

    /** The GETs and PATCHes of the profile, in order, apart from [received] and [planRequests]. */
    val profileRequests = CopyOnWriteArrayList<Received>()

    /** What `GET /v1/routes` answers with (the list's JSON), or null for a service without the route (404). */
    @Volatile
    var routes: String? = null

    /** What `GET /v1/routes/{id}` answers with, by id; an id that is not here is a 404. */
    val routePoints = ConcurrentHashMap<String, String>()

    /** What `GET /v1/recovery` answers with, or null for a 404. */
    @Volatile
    var recovery: String? = null

    /** The GETs of the routes and of the recovery state, in order, apart from [received], [planRequests] and [profileRequests]. */
    val routeRequests = CopyOnWriteArrayList<Received>()

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
            val request = Received(path, auth, body, exchange.requestMethod, exchange.requestURI.rawQuery)
            when {
                request.method == "GET" && path == "/v1/workouts" -> {
                    planRequests += request
                }

                path == "/v1/profile" -> {
                    profileRequests += request
                }

                path == "/v1/routes" || path.startsWith("/v1/routes/") || path == "/v1/recovery" -> {
                    routeRequests +=
                        request
                }

                else -> {
                    received += request
                }
            }
            val (status, text) = answer(exchange.requestMethod, path, auth, body)
            val bytes = text.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (status == 401) exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"overdrive\"")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun answer(
        method: String,
        path: String,
        auth: String?,
        body: ByteArray,
    ): Pair<Int, String> {
        if (down) return 503 to error("internal_error", "Down for the test")
        if (auth != "Bearer $token") return 401 to error("unauthorized", "Missing or invalid bearer token")
        if (method == "GET" && path == "/v1/workouts") return planStatus to plan
        if (path == "/v1/profile") return profileAnswer(method, body)
        if (method == "GET" &&
            path == "/v1/routes"
        ) {
            return routes?.let { 200 to it } ?: (404 to error("not_found", "No routes"))
        }
        if (method == "GET" && path.startsWith("/v1/routes/")) {
            val body = routePoints[path.removePrefix("/v1/routes/")]
            return if (body != null) 200 to body else 404 to error("not_found", "No such route")
        }
        if (method == "GET" &&
            path == "/v1/recovery"
        ) {
            return recovery?.let { 200 to it } ?: (404 to error("not_found", "No recovery"))
        }
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

    private fun profileAnswer(
        method: String,
        body: ByteArray,
    ): Pair<Int, String> {
        val held = profile ?: return 404 to error("not_found", "No profile")
        return when (method) {
            "GET" -> {
                200 to ProfileApiJson.encodeProfile(held)
            }

            "PATCH" -> {
                try {
                    val patched = ProfileApiJson.decodePatch(body.decodeToString()).applyTo(held)
                    profile = patched.copy(updatedAt = "2026-11-18T10:00:00Z")
                    200 to ProfileApiJson.encodeProfile(checkNotNull(profile))
                } catch (e: IllegalArgumentException) {
                    422 to error("invalid_profile", "Not a valid change")
                }
            }

            else -> {
                405 to error("not_found", "No such route")
            }
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
