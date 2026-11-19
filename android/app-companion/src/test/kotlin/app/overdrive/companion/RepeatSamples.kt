package app.overdrive.companion

import app.overdrive.core.metrics.RideItem
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.outdoor.ActivityImportJson
import app.overdrive.core.outdoor.ActivityImportKind
import app.overdrive.core.outdoor.ActivityImportReason
import app.overdrive.core.outdoor.ActivityImportResponse
import app.overdrive.core.outdoor.ActivityImportStatus
import app.overdrive.core.outdoor.ActivityImportSummary
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.repeats.RepeatAttempt
import app.overdrive.core.repeats.RepeatAttemptRef
import app.overdrive.core.repeats.RepeatBetterWhen
import app.overdrive.core.repeats.RepeatChange
import app.overdrive.core.repeats.RepeatComparison
import app.overdrive.core.repeats.RepeatCompliance
import app.overdrive.core.repeats.RepeatDefinition
import app.overdrive.core.repeats.RepeatDetailResponse
import app.overdrive.core.repeats.RepeatDirection
import app.overdrive.core.repeats.RepeatFigure
import app.overdrive.core.repeats.RepeatOverallHeartRate
import app.overdrive.core.repeats.RepeatSessionStep
import app.overdrive.core.repeats.RepeatStepHeartRate
import app.overdrive.core.repeats.RepeatSummary
import app.overdrive.core.repeats.RepeatTrend
import app.overdrive.core.repeats.RepeatTrends
import app.overdrive.core.repeats.RepeatsResponse
import kotlin.math.abs
import kotlin.math.roundToInt

/** Synthetic repeated sessions and outdoor rides, valid by the contract's own rules (the models check them). No real ride or reading. */
object RepeatSamples {
    const val HASH = "b1add004e12d9617"
    const val OTHER_HASH = "0123456789abcdef"
    const val NAME = "Sweet spot 2×10"

    /** The ride id of attempt [index] (0-based) of the session [hash]: distinct across sessions, a valid ride id. */
    fun rideId(
        index: Int,
        hash: String = HASH,
    ) = "ride-${hash.take(4)}-$index"

    private const val FIRST_DAY = "2026-10-08"

    fun date(index: Int) = PlanCalendar.addDays(FIRST_DAY, index * 7)

    private fun round(
        value: Double,
        decimals: Int,
    ): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return (value * factor).roundToInt() / factor
    }

    /** The service's least-squares trend over the attempts that have a figure (ADR 0026). */
    fun trend(
        values: List<Double?>,
        betterWhen: RepeatBetterWhen,
        unit: String,
        steadyBelow: Double,
    ): RepeatTrend {
        val figures = values.filterNotNull()
        val n = figures.size
        if (n < RepeatDefinition.MIN_TREND_ATTEMPTS) {
            return RepeatTrend(
                unit,
                betterWhen,
                n,
                RepeatDefinition.MIN_TREND_ATTEMPTS,
                RepeatDirection.UNKNOWN,
                null,
                null,
                steadyBelow,
                "a trend needs at least 3 attempts with a figure; there ${if (n == 1) "is" else "are"} $n",
            )
        }
        val meanX = (n - 1) / 2.0
        val meanY = figures.average()
        var num = 0.0
        var den = 0.0
        for ((i, y) in figures.withIndex()) {
            num += (i - meanX) * (y - meanY)
            den += (i - meanX) * (i - meanX)
        }
        val slope = round(num / den, 3)
        val change = round(slope * (n - 1), 2)
        val direction =
            when {
                abs(change) < steadyBelow -> RepeatDirection.STEADY
                (change < 0.0) == (betterWhen == RepeatBetterWhen.LOWER) -> RepeatDirection.IMPROVING
                else -> RepeatDirection.WORSENING
            }
        return RepeatTrend(unit, betterWhen, n, 3, direction, slope, change, steadyBelow, null)
    }

    /**
     * One session of [overall].size attempts, oldest first. [overall] is each attempt's heart rate at the same power (null: none, with a
     * reason). The step figures follow from it. [drift] and [compliance] default to flat figures; [power] is the average power.
     */
    fun detail(
        overall: List<Double?>,
        drift: List<Double?> = overall.map { 3.5 },
        compliance: List<Double?> = overall.map { 100.0 },
        name: String = NAME,
        hash: String = HASH,
        generatedAt: String = "2026-11-18T18:00:00Z",
        powers: List<Double?> = overall.indices.map { 181.0 + it },
        steps: Int = 2,
    ): RepeatDetailResponse {
        val session =
            (0 until steps).map {
                val hard = it % 2 == 0
                RepeatSessionStep(
                    it + 2,
                    if (hard) "Sweet spot" else "Recover",
                    if (hard) 600 else 300,
                    if (hard) 0.88 else 0.5,
                )
            }
        val attempts =
            overall.indices.map { i ->
                val hr = overall[i]
                val day = date(i)
                RepeatAttempt(
                    rideId = rideId(i, hash),
                    number = i + 1,
                    date = day,
                    startTime = "${day}T18:00:00Z",
                    durationSeconds = if (compliance[i] == null) 1500 else 3600 + i * 12,
                    ftp = 250,
                    averagePower = powers[i],
                    averageHeartRate = hr?.let { it - 3.0 } ?: 136.0,
                    effortRating = null,
                    heartRateAtPower =
                        if (hr == null) {
                            RepeatOverallHeartRate(
                                null,
                                0,
                                "step 2: only 90 s were within 3% of the target power after the first 60 s; 120 s are needed",
                            )
                        } else {
                            RepeatOverallHeartRate(hr, 720, null)
                        },
                    stepHeartRates =
                        session.mapIndexed { n, step ->
                            val target = if (n % 2 == 0) 220.0 else 125.0
                            if (hr == null) {
                                RepeatStepHeartRate(
                                    step.step,
                                    target,
                                    null,
                                    null,
                                    0,
                                    "only 90 s were within 3% of the target power",
                                )
                            } else {
                                RepeatStepHeartRate(
                                    step.step,
                                    target,
                                    target + 0.4,
                                    round(hr + if (n % 2 == 0) 4.0 else -20.0, 1),
                                    480,
                                    null,
                                )
                            }
                        },
                    driftPercent =
                        drift[i]?.let { RepeatFigure(it, null) }
                            ?: RepeatFigure(null, "the steady part was shorter than 40 minutes"),
                    compliance = RepeatCompliance(compliance[i], 3, 3, 0.4),
                )
            }
        return RepeatDetailResponse(
            generatedAt = generatedAt,
            hash = hash,
            name = name,
            routeKey = null,
            note = RepeatDefinition.NOTE,
            definition = RepeatDefinition(3, 60, 120, 3),
            steps = session,
            comparedSteps = session.map { it.step },
            attempts = attempts,
            trends =
                RepeatTrends(
                    trend(overall, RepeatBetterWhen.LOWER, "bpm", 2.0),
                    trend(drift, RepeatBetterWhen.LOWER, "%", 1.0),
                    trend(compliance, RepeatBetterWhen.HIGHER, "%", 5.0),
                ),
        )
    }

    private fun change(
        latest: Double?,
        previous: Double?,
        reason: String,
    ) = if (latest != null && previous != null) {
        RepeatChange(latest, previous, round(latest - previous, 1), null)
    } else {
        RepeatChange(latest, previous, null, reason)
    }

    /** A session of the list: [overall] is its attempts' heart rate at the same power; [lastDay] the day of the latest. */
    class Listed(
        val name: String,
        val hash: String,
        val overall: List<Double?>,
        val lastDay: String,
    )

    fun list(vararg sessions: Listed): RepeatsResponse =
        RepeatsResponse(
            "2026-11-18T18:00:00Z",
            sessions
                .map { s ->
                    val last = s.overall.lastIndex
                    RepeatSummary(
                        hash = s.hash,
                        name = s.name,
                        routeKey = null,
                        attempts = s.overall.size,
                        firstDate = date(0),
                        lastDate = s.lastDay,
                        latest = RepeatAttemptRef(rideId(last, s.hash), s.lastDay, "${s.lastDay}T18:00:00Z"),
                        previous =
                            RepeatAttemptRef(
                                rideId(last - 1, s.hash),
                                date(last - 1),
                                "${date(last - 1)}T18:00:00Z",
                            ),
                        comparison =
                            RepeatComparison(
                                change(
                                    s.overall[last],
                                    s.overall[last - 1],
                                    "the latest attempt has none: step 2: only 90 s were counted",
                                ),
                                change(3.2, 3.9, "no drift figure"),
                                change(100.0, 100.0, "no compliance figure"),
                            ),
                    )
                }.sortedWith(compareByDescending<RepeatSummary> { it.lastDate }.thenBy { it.hash }),
        )

    /** The design's two sessions: one with a falling trend, one with only two attempts. */
    fun twoSessions(): RepeatsResponse =
        list(
            Listed(NAME, HASH, listOf(150.0, 148.0, 149.0, 146.0, 144.0), "2026-11-18"),
            Listed("Threshold 4×8", OTHER_HASH, listOf(161.0, 158.0), "2026-11-12"),
        )

    // region outdoor rides

    const val FIT_ID = "fit-0123456789abcdef"

    /** What the service read from a file: a ride of 1:12:04 on 15 Nov, 31.4 km, heart rate 142, load 74. */
    fun summary(
        load: Double? = 74.0,
        approximate: Boolean = true,
    ) = ActivityImportSummary(
        name = "Outdoor ride",
        start = "2026-11-15T10:00:00Z",
        end = "2026-11-15T11:12:04Z",
        startDate = "2026-11-15",
        durationSeconds = 4324,
        distanceMeters = 31_400.0,
        elevationGainMeters = 212.0,
        averageHeartRate = 142.4,
        maxHeartRate = 171,
        load = load,
        loadApproximate = load != null && approximate,
    )

    fun imported(id: String = FIT_ID) =
        ActivityImportResponse(ActivityImportStatus.IMPORTED, id, ActivityImportKind.ACTIVITY, null, summary())

    fun duplicate(
        reason: ActivityImportReason = ActivityImportReason.SAME_FILE,
        id: String = FIT_ID,
    ) = ActivityImportResponse(
        ActivityImportStatus.DUPLICATE,
        id,
        ActivityImportKind.ACTIVITY,
        reason,
        summary(load = null),
    )

    fun importedJson(id: String = FIT_ID): String = ActivityImportJson.encode(imported(id))

    fun duplicateJson(reason: ActivityImportReason = ActivityImportReason.SAME_FILE): String =
        ActivityImportJson.encode(duplicate(reason))

    /** The row the service lists for an imported ride: an activity of the sport `cycling-outdoor`. */
    fun outdoorItem(
        id: String = FIT_ID,
        date: String = "2026-11-15",
    ) = RideItem(
        id,
        RideKind.ACTIVITY,
        "Outdoor ride",
        "${date}T10:00:00Z",
        date,
        4324,
        74.0,
        true,
        "cycling-outdoor",
        null,
        142.4,
    )
}
