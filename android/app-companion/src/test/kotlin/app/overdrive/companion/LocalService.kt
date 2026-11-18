package app.overdrive.companion

import app.overdrive.core.metrics.DismissedStatus
import app.overdrive.core.metrics.FtpSuggestion
import app.overdrive.core.metrics.FtpSuggestionDismissResponse
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileApiJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A stand-in for the service's phone-facing routes on the loopback address (ADR 0024), for the tests that run the real container:
 * bearer authentication, `GET /v1/metrics`, `GET /v1/rides` by cursor, `GET /v1/rides/{id}`, `GET /v1/workouts`, `GET` and
 * `PATCH /v1/profile` and `POST /v1/ftp-suggestion/dismiss`, answered from the synthetic [Samples] and as the real service does
 * (the profile's patch is read strictly and applied). [down] makes every request answer 503; [rejectToken] makes them 401.
 */
class LocalService(
    val token: String = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7",
) : AutoCloseable {
    class Received(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    val received = CopyOnWriteArrayList<Received>()

    @Volatile
    var down = false

    @Volatile
    var rejectToken = false

    /** The open FTP suggestion in the metrics, or null. */
    @Volatile
    var suggestion: FtpSuggestion? = Samples.suggestion()

    @Volatile
    var rides: List<RideItem> = Samples.rideItems()

    @Volatile
    var planConfigured = true

    @Volatile
    var profile: Profile? = Samples.profile()

    @Volatile
    var dismissed = emptyList<Int>()

    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun requests(
        method: String,
        path: String,
    ): List<Received> = received.filter { it.method == method && it.path == path }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val body = exchange.requestBody.readBytes().decodeToString()
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path
            received += Received(exchange.requestMethod, path, exchange.requestURI.rawQuery, auth, body)
            val (status, text) =
                answer(
                    exchange.requestMethod,
                    path,
                    exchange.requestURI.rawQuery.orEmpty(),
                    auth,
                    body,
                )
            val bytes = text.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun query(raw: String): Map<String, String> =
        raw.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun answer(
        method: String,
        path: String,
        rawQuery: String,
        auth: String?,
        body: String,
    ): Pair<Int, String> {
        if (down) return 503 to error("internal_error", "Down for the test")
        if (rejectToken ||
            auth != "Bearer $token"
        ) {
            return 401 to error("unauthorized", "Missing or invalid bearer token")
        }
        val q = query(rawQuery)
        return when {
            method == "GET" && path == "/v1/metrics" -> {
                200 to MetricsApiJson.encodeMetrics(Samples.metrics(q.getValue("from"), q.getValue("to"), suggestion))
            }

            method == "GET" && path == "/v1/rides" -> {
                200 to ridesPage(q)
            }

            method == "GET" && path.startsWith("/v1/rides/") -> {
                rideDetail(path.removePrefix("/v1/rides/"))
            }

            method == "GET" && path == "/v1/workouts" -> {
                200 to PlanApiJson.encodeWorkoutsResponse(Samples.workouts(q.getValue("from"), planConfigured))
            }

            path == "/v1/profile" -> {
                profileAnswer(method, body)
            }

            method == "POST" && path == "/v1/ftp-suggestion/dismiss" -> {
                dismiss(body)
            }

            else -> {
                404 to error("not_found", "No such route")
            }
        }
    }

    private fun ridesPage(q: Map<String, String>): String {
        val limit = q["limit"]?.toInt() ?: 50
        val before = q["before"]
        val older = rides.filter { before == null || it.start.take(19) < before.take(19) }
        val items = older.take(limit)
        val hasMore = older.size > items.size
        return MetricsApiJson.encodeRides(Samples.ridesPage(items, hasMore, before, limit))
    }

    private fun rideDetail(id: String): Pair<Int, String> =
        if (rides.none { it.id == id && it.kind == app.overdrive.core.metrics.RideKind.RIDE }) {
            404 to error("ride_not_found", "No such ride")
        } else {
            200 to MetricsApiJson.encodeRideDetail(Samples.rideDetail(id))
        }

    private fun profileAnswer(
        method: String,
        body: String,
    ): Pair<Int, String> {
        val held = profile ?: return 404 to error("not_found", "No profile")
        return when (method) {
            "GET" -> {
                200 to ProfileApiJson.encodeProfile(held)
            }

            "PATCH" -> {
                try {
                    val patched = ProfileApiJson.decodePatch(body).applyTo(held)
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

    private fun dismiss(body: String): Pair<Int, String> {
        val id =
            try {
                MetricsApiJson.decodeDismissRequest(body).suggestionId
            } catch (e: IllegalArgumentException) {
                return 400 to error("invalid_json", "Not a dismissal")
            }
        dismissed = dismissed + id
        if (suggestion?.id == id) suggestion = null
        return 200 to MetricsApiJson.encodeDismissResponse(FtpSuggestionDismissResponse(id, DismissedStatus.DISMISSED))
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun error(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message"}}"""
}
