package app.overdrive.core.remote

import app.overdrive.core.metrics.Confidence
import app.overdrive.core.metrics.CurvePoint
import app.overdrive.core.metrics.FtpBasis
import app.overdrive.core.metrics.FtpInfo
import app.overdrive.core.metrics.FtpSuggestion
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.MetricsDay
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.metrics.MetricsWeek
import app.overdrive.core.metrics.PowerCurves
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.metrics.RidesResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport

internal const val TOKEN = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"
internal const val ADDRESS = "https://overdrive.example.org"

/** A transport that answers from a script (an [HttpAnswer] to return, a [Throwable] to throw), repeating the last entry. */
internal class ScriptedTransport(
    vararg script: Any,
) : HttpGetTransport,
    HttpPostTransport {
    private val script = script.toMutableList()
    private var last: Any? = null
    val gets = mutableListOf<HttpGetRequest>()
    val posts = mutableListOf<HttpPostRequest>()

    /** What to do before answering: a test uses it to hold a request open. */
    var beforeAnswer: suspend () -> Unit = {}

    fun then(vararg more: Any) {
        script.addAll(more)
    }

    private fun next(): Any = (if (script.isNotEmpty()) script.removeAt(0).also { last = it } else checkNotNull(last))

    override suspend fun get(request: HttpGetRequest): HttpAnswer {
        gets += request
        beforeAnswer()
        return answer(next())
    }

    override suspend fun post(request: HttpPostRequest): HttpAnswer {
        posts += request
        beforeAnswer()
        return answer(next())
    }

    private fun answer(entry: Any): HttpAnswer =
        when (entry) {
            is HttpAnswer -> entry
            is Throwable -> throw entry
            else -> error("bad script entry")
        }
}

internal class MemoryTextStore(
    var text: String? = null,
) : TextStore {
    var failWrites = false
    var reads = 0
    var writes = 0

    override suspend fun read(): String? {
        reads++
        return text
    }

    override suspend fun write(text: String) {
        writes++
        if (failWrites) throw IllegalStateException("The disk refuses")
        this.text = text
    }
}

/** Synthetic answers of the service, valid by the contract's own rules. Nothing here is a real rider's. */
internal object RemoteSamples {
    fun instant(date: String) = "${date}T07:40:00Z"

    fun metrics(
        from: String,
        to: String,
        suggestion: FtpSuggestion? = null,
        generatedAt: String = "${to}T07:40:00Z",
    ): MetricsResponse {
        val first = checkNotNull(PlanDates.epochDay(from))
        val last = checkNotNull(PlanDates.epochDay(to))
        val daily =
            (first..last).map { day ->
                val date = PlanCalendar.ofEpochDay(day)
                val ridden = day % 3 != 0L
                val load = if (ridden) 60.0 else 0.0
                MetricsDay(date, load, load, 0.0, ctl = 50.0, atl = 43.5, tsb = 6.5)
            }
        val firstMonday = first - (first + 3).mod(7L)
        val lastMonday = last - (last + 3).mod(7L)
        val weekly =
            (firstMonday..lastMonday step 7).map { monday ->
                MetricsWeek(PlanCalendar.ofEpochDay(monday), 240.0, 240.0, 0.0, 4, 0)
            }
        val point = CurvePoint(1200, 257.0, to, "ride-1")
        return MetricsResponse(
            from = from,
            to = to,
            generatedAt = generatedAt,
            stale = false,
            firstLoadDate = from,
            daily = daily,
            weekly = weekly,
            powerCurve = PowerCurves(listOf(point), listOf(point.copy(power = 268.0))),
            ftp = FtpInfo(250, from, suggestion, 256, emptyList(), emptyList()),
            drift = emptyList(),
        )
    }

    fun suggestion(
        id: Int = 7,
        watts: Int = 256,
    ) = FtpSuggestion(
        id = id,
        watts = watts,
        basedOnRides = 3,
        windowDays = 42,
        createdAt = "2026-11-17T20:00:00Z",
        confidence = Confidence.MEDIUM,
        basis = FtpBasis(1200, 270.0, "ride-1", "2026-11-16"),
    )

    fun ride(
        id: String,
        start: String,
        name: String = "Sweet spot 3×10",
    ) = RideItem(
        id = id,
        kind = RideKind.RIDE,
        name = name,
        start = start,
        date = start.take(10),
        durationSeconds = 3900,
        load = 74.0,
        loadApproximate = false,
        sport = null,
        averagePower = 196.0,
        averageHeartRate = null,
    )

    /** [count] rides, one a day from [lastDay] backwards, newest first, in a page that says whether older ones exist. */
    fun ridesPage(
        lastDay: String,
        count: Int,
        hasMore: Boolean,
        before: String? = null,
        limit: Int = 50,
        idPrefix: String = "ride",
    ): RidesResponse {
        val items =
            (0 until count).map { i ->
                val day = PlanCalendar.addDays(lastDay, -i)
                ride("$idPrefix-$day", "${day}T18:00:00Z")
            }
        return RidesResponse(
            generatedAt = "2026-11-18T21:10:00Z",
            stale = false,
            before = before,
            limit = limit,
            hasMore = hasMore,
            nextBefore = if (hasMore) items.last().start else null,
            items = items,
        )
    }

    fun json(response: MetricsResponse): String = MetricsApiJson.encodeMetrics(response)

    fun json(response: RidesResponse): String = MetricsApiJson.encodeRides(response)

    fun ok(body: String) = HttpAnswer(200, body)

    fun error(
        status: Int,
        code: String = "internal_error",
    ) = HttpAnswer(status, """{"error":{"code":"$code","message":"Not today"}}""")
}
