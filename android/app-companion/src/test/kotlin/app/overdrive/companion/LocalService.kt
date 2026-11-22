package app.overdrive.companion

import app.overdrive.core.health.kindName
import app.overdrive.core.insights.InsightsApiJson
import app.overdrive.core.insights.InsightsResponse
import app.overdrive.core.metrics.DismissedStatus
import app.overdrive.core.metrics.FtpSuggestion
import app.overdrive.core.metrics.FtpSuggestionDismissResponse
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileApiJson
import app.overdrive.core.progress.ProgressApiJson
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.repeats.RepeatDetailResponse
import app.overdrive.core.repeats.RepeatsApiJson
import app.overdrive.core.repeats.RepeatsResponse
import app.overdrive.core.wellness.RecordCounts
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryState
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessRecord
import app.overdrive.core.wellness.WellnessUploadResponse
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
        val contentType: String? = null,
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

    /** What `GET /v1/workouts` answers instead of the sample plan when it is set (a plan with a route or an easier version, ADR 0037). */
    @Volatile
    var workoutsBody: String? = null

    @Volatile
    var profile: Profile? = Samples.profile()

    @Volatile
    var dismissed = emptyList<Int>()

    /** What `GET /v1/recovery` answers (null is a 404, as a service with no rating yet), and what `GET /v1/insights` does. */
    @Volatile
    var recovery: RecoveryResponse? = WatchSamples.recovery(RecoveryState.READY)

    @Volatile
    var insights: InsightsResponse = WatchSamples.insights()

    /** What `GET /v1/repeats` answers (null is a 404, as a service that has not got the route yet), and `GET /v1/repeats/{hash}` by hash. */
    @Volatile
    var repeats: RepeatsResponse? = null

    val repeatDetails = java.util.concurrent.ConcurrentHashMap<String, RepeatDetailResponse>()

    /** What `GET /v1/progress` answers, the same for every range (null is a 404, as a service that has not got the route yet). */
    @Volatile
    var progress: ProgressResponse? = null

    /** The FIT files `POST /v1/activities/fit` holds, by the id it gave them: idempotent by the file's bytes, as the real one is. */
    val fitFiles = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Every body `POST /v1/activities/fit` received, in order (a file that was refused is in it too). */
    val fitBodies = CopyOnWriteArrayList<ByteArray>()

    /** Answers for the next files instead of storing them: a status and a body, used once each. */
    val fitScript = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String>>()

    /** Stores the next files but cuts the connection before the answer: the answer is lost, the file is not. */
    @Volatile
    var fitLoseAnswers = 0

    /** Every record the service holds from `POST /v1/wellness`, by id: idempotent, as the real one is. */
    val wellnessRecords = java.util.concurrent.ConcurrentHashMap<String, WellnessRecord>()

    /** Every body `POST /v1/wellness` received, in order (an upload that was refused is in it too). */
    val wellnessBodies = CopyOnWriteArrayList<String>()

    /** Answers for the next uploads instead of storing: a status and a body, used once each. */
    val wellnessScript = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, String>>()

    /** Stores the next uploads but cuts the connection before the answer: the answer is lost, the data is not. */
    @Volatile
    var wellnessLoseAnswers = 0

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
            val raw = exchange.requestBody.readBytes()
            val body = raw.decodeToString()
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val path = exchange.requestURI.path
            val type = exchange.requestHeaders.getFirst("Content-Type")
            received += Received(exchange.requestMethod, path, exchange.requestURI.rawQuery, auth, body, type)
            val (status, text) =
                answer(
                    exchange.requestMethod,
                    path,
                    exchange.requestURI.rawQuery.orEmpty(),
                    auth,
                    body,
                    raw,
                    type,
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
        raw: ByteArray,
        contentType: String?,
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
                200 to
                    (
                        workoutsBody
                            ?: PlanApiJson.encodeWorkoutsResponse(Samples.workouts(q.getValue("from"), planConfigured))
                    )
            }

            path == "/v1/profile" -> {
                profileAnswer(method, body)
            }

            method == "POST" && path == "/v1/ftp-suggestion/dismiss" -> {
                dismiss(body)
            }

            method == "POST" && path == "/v1/wellness" -> {
                upload(body)
            }

            method == "GET" && path == "/v1/wellness" -> {
                val from = q.getValue("from")
                val to = q.getValue("to")
                200 to WellnessApiJson.encodeWellnessResponse(WatchSamples.wellnessResponse(from, to))
            }

            method == "GET" && path == "/v1/progress" -> {
                progress?.let { 200 to ProgressApiJson.encode(it) } ?: (404 to error("not_found", "No such route"))
            }

            method == "GET" && path == "/v1/repeats" -> {
                repeats?.let { 200 to RepeatsApiJson.encodeRepeats(it) } ?: (404 to error("not_found", "No such route"))
            }

            method == "GET" && path.startsWith("/v1/repeats/") -> {
                repeatDetails[path.removePrefix("/v1/repeats/")]?.let { 200 to RepeatsApiJson.encodeDetail(it) }
                    ?: (404 to error("not_found", "No such session"))
            }

            method == "POST" && path == "/v1/activities/fit" -> {
                importFit(raw, contentType)
            }

            method == "GET" && path == "/v1/recovery" -> {
                recovery?.let { 200 to WellnessApiJson.encodeRecoveryResponse(it) }
                    ?: (404 to error("not_found", "No rating"))
            }

            method == "GET" && path == "/v1/insights" -> {
                val from = q.getValue("from")
                val to = q.getValue("to")
                val shown = insights.insights.filter { it.createdAt.take(10) in from..to }
                200 to InsightsApiJson.encodeResponse(insights.copy(from = from, to = to, insights = shown))
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

    private fun upload(body: String): Pair<Int, String> {
        wellnessBodies += body
        wellnessScript.poll()?.let { return it }
        val upload =
            try {
                WellnessApiJson.decodeUpload(body)
            } catch (e: IllegalArgumentException) {
                return 422 to error("invalid_record", "Not an upload")
            }
        var accepted = 0
        var duplicates = 0
        val byType = LinkedHashMap<String, IntArray>()
        for (record in upload.records) {
            val kind = record.kindName()
            val counts = byType.getOrPut(kind) { IntArray(2) }
            val known = wellnessRecords[record.id]
            if (known == null || (record.lastModified != null && record.lastModified!! > (known.lastModified ?: ""))) {
                wellnessRecords[record.id] = record
                accepted++
                counts[0]++
            } else {
                duplicates++
                counts[1]++
            }
        }
        if (wellnessLoseAnswers > 0) {
            wellnessLoseAnswers--
            throw java.io.IOException("The answer is lost")
        }
        val response =
            WellnessUploadResponse(
                accepted,
                duplicates,
                0,
                byType.mapValues { RecordCounts(it.value[0], it.value[1], 0) },
                "2026-11-18T07:40:00Z",
            )
        return 200 to WellnessApiJson.encodeUploadResponse(response)
    }

    private fun importFit(
        raw: ByteArray,
        contentType: String?,
    ): Pair<Int, String> {
        fitBodies += raw
        fitScript.poll()?.let { return it }
        if (contentType != "application/vnd.ant.fit") return 415 to error("unsupported_media_type", "Not a FIT body")
        val isFit = raw.size >= 14 && raw[0].toInt() == 12 && String(raw, 8, 4, Charsets.US_ASCII) == ".FIT"
        if (!isFit) return 422 to error("invalid_fit", "Not a FIT file")
        val digest =
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(raw)
                .joinToString("") { "%02x".format(it) }
        val id = "fit-${digest.take(16)}"
        val known = fitFiles.putIfAbsent(id, raw) != null
        if (!known) {
            rides = (rides + RepeatSamples.outdoorItem(id)).sortedByDescending { it.start }
        }
        if (fitLoseAnswers > 0) {
            fitLoseAnswers--
            throw java.io.IOException("The answer is lost")
        }
        return if (known) {
            200 to RepeatSamples.duplicateJson()
        } else {
            201 to RepeatSamples.importedJson(id)
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
