package app.overdrive.core.remote

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
import app.overdrive.core.repeats.RepeatsApiJson
import app.overdrive.core.repeats.RepeatsResponse
import kotlin.math.abs
import kotlin.math.roundToInt

/** Synthetic repeated sessions, valid by the contract's own rules (the models check them when they are built). No real ride in here. */
internal object RepeatSamples {
    const val HASH = "b1add004e12d9617"
    const val OTHER_HASH = "0123456789abcdef"
    const val NAME = "Sweet spot 2x10"

    private const val FIRST_DAY = "2026-10-08"

    private fun rideId(index: Int) = "ride-${1_791_500_000_000L + index * 604_800_000L}"

    private fun date(index: Int) = PlanCalendar.addDays(FIRST_DAY, index * 7)

    private fun round(
        value: Double,
        decimals: Int,
    ): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        return (value * factor).roundToInt() / factor
    }

    /** The service's least-squares trend over the attempts that have a figure (the rules of ADR 0026). */
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
                "Only $n attempts have a figure; ${RepeatDefinition.MIN_TREND_ATTEMPTS} are needed.",
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
        return RepeatTrend(
            unit,
            betterWhen,
            n,
            RepeatDefinition.MIN_TREND_ATTEMPTS,
            direction,
            slope,
            change,
            steadyBelow,
            null,
        )
    }

    /**
     * One session of [overall].size attempts, oldest first. [overall] is each attempt's heart rate at the same power (null: none, with a
     * reason); the two steps' figures follow from it. [drift] and [compliance] default to flat figures.
     */
    fun detail(
        overall: List<Double?>,
        drift: List<Double?> = overall.map { 3.5 },
        compliance: List<Double?> = overall.map { 100.0 },
        name: String = NAME,
        hash: String = HASH,
        generatedAt: String = "2026-11-18T18:00:00Z",
    ): RepeatDetailResponse {
        val steps =
            listOf(
                RepeatSessionStep(2, "Sweet spot", 600, 0.88),
                RepeatSessionStep(3, "Recover", 300, 0.5),
            )
        val attempts =
            overall.indices.map { i ->
                val hr = overall[i]
                val day = date(i)
                RepeatAttempt(
                    rideId = rideId(i),
                    number = i + 1,
                    date = day,
                    startTime = "${day}T18:00:00Z",
                    durationSeconds = if (compliance[i] == null) 1500 else 3600,
                    ftp = 250,
                    averagePower = 181.0 + i,
                    averageHeartRate = hr?.let { it - 3.0 } ?: 136.0,
                    effortRating = null,
                    heartRateAtPower =
                        if (hr == null) {
                            RepeatOverallHeartRate(null, 0, "no heart rate at the target power in step 2")
                        } else {
                            RepeatOverallHeartRate(hr, 720, null)
                        },
                    stepHeartRates =
                        listOf(
                            if (hr == null) {
                                RepeatStepHeartRate(2, 220.0, null, null, 0, "no heart rate at the target power")
                            } else {
                                RepeatStepHeartRate(2, 220.0, 220.4, round(hr + 4.0, 1), 480, null)
                            },
                            if (hr == null) {
                                RepeatStepHeartRate(3, 125.0, null, null, 0, "stopped before this step")
                            } else {
                                RepeatStepHeartRate(3, 125.0, 125.4, round(hr - 20.0, 1), 240, null)
                            },
                        ),
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
            steps = steps,
            comparedSteps = listOf(2, 3),
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

    /** The list: one summary per [sessions] entry (name, hash, the last two heart-rate figures, attempts), most recent first. */
    fun list(vararg sessions: Triple<String, String, List<Double?>>): RepeatsResponse =
        RepeatsResponse(
            "2026-11-18T18:00:00Z",
            sessions
                .mapIndexed { s, (name, hash, overall) ->
                    val last = overall.lastIndex
                    val shift = 20 - s * 7
                    RepeatSummary(
                        hash = hash,
                        name = name,
                        routeKey = null,
                        attempts = overall.size,
                        firstDate = date(0),
                        lastDate = date(last).let { PlanCalendar.addDays(it, shift) },
                        latest =
                            RepeatAttemptRef(
                                rideId(last) + "-$s",
                                PlanCalendar.addDays(date(last), shift),
                                "${PlanCalendar.addDays(date(last), shift)}T18:00:00Z",
                            ),
                        previous =
                            RepeatAttemptRef(
                                rideId(last - 1) + "-$s",
                                date(last - 1),
                                "${date(last - 1)}T18:00:00Z",
                            ),
                        comparison =
                            RepeatComparison(
                                change(
                                    overall[last],
                                    overall[last - 1],
                                    "no heart rate at the target power in the latest attempt",
                                ),
                                change(3.2, 3.9, "no drift figure"),
                                change(100.0, 100.0, "no compliance figure"),
                            ),
                    )
                }.sortedWith(compareByDescending<RepeatSummary> { it.lastDate }.thenBy { it.hash }),
        )

    fun json(response: RepeatDetailResponse): String = RepeatsApiJson.encodeDetail(response)

    fun json(response: RepeatsResponse): String = RepeatsApiJson.encodeRepeats(response)
}
