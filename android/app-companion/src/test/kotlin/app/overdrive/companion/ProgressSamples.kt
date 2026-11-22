package app.overdrive.companion

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.progress.ProgressAerobicEfficiency
import app.overdrive.core.progress.ProgressApiJson
import app.overdrive.core.progress.ProgressBestPower
import app.overdrive.core.progress.ProgressBestPowerTrends
import app.overdrive.core.progress.ProgressBetterWhen
import app.overdrive.core.progress.ProgressDecoupling
import app.overdrive.core.progress.ProgressDirection
import app.overdrive.core.progress.ProgressEfficiencyFactor
import app.overdrive.core.progress.ProgressEfficiencyWeek
import app.overdrive.core.progress.ProgressHeadline
import app.overdrive.core.progress.ProgressHeadlineDirection
import app.overdrive.core.progress.ProgressHeartRateBasis
import app.overdrive.core.progress.ProgressMeasure
import app.overdrive.core.progress.ProgressOverallTrend
import app.overdrive.core.progress.ProgressRangeDays
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.progress.ProgressRestingHeartRate
import app.overdrive.core.progress.ProgressStretchCount
import app.overdrive.core.progress.ProgressTrend
import app.overdrive.core.progress.ProgressUnit
import app.overdrive.core.progress.ProgressWeight

/** Synthetic Progress answers, valid by the contract's own rules (the models check them when they are built). Every figure is invented. */
internal object ProgressSamples {
    const val GENERATED = "2026-11-18T07:40:00Z"
    const val NOTE =
        "Heart rate also moves with heat, sleep and caffeine, so trust a trend over several weeks, not one ride."

    fun unknown(
        unit: ProgressUnit,
        betterWhen: ProgressBetterWhen,
        minPoints: Int,
        reason: String,
        note: String? = null,
    ) = ProgressTrend(
        direction = ProgressDirection.UNKNOWN,
        betterWhen = betterWhen,
        unit = unit,
        points = 0,
        minPoints = minPoints,
        change = null,
        difference = null,
        from = null,
        latest = null,
        since = null,
        steadyBand = 1.0,
        verdictText = "Not enough data yet",
        reason = reason,
        note = note,
    )

    private fun noLine(minutes: Int) =
        unknown(ProgressUnit.WATTS, ProgressBetterWhen.HIGHER, 3, "0 of 3 months have a $minutes-minute ride.")

    /** An answer with nothing recorded: every trend unknown, no headline. */
    fun empty(
        from: String = "2026-05-18",
        to: String = "2026-11-18",
    ) = ProgressResponse(
        generatedAt = GENERATED,
        stale = false,
        range = ProgressRangeDays(from, to),
        headline = null,
        bestPower =
            ProgressBestPower(
                months = emptyList(),
                trend =
                    ProgressOverallTrend(
                        ProgressDirection.UNKNOWN,
                        ProgressBetterWhen.HIGHER,
                        0,
                        "Not enough data yet",
                        "No line has a verdict yet.",
                        null,
                    ),
                trends = ProgressBestPowerTrends(noLine(5), noLine(20), noLine(60)),
            ),
        aerobicEfficiency =
            ProgressAerobicEfficiency(
                ftpAtStart = null,
                referencePower = null,
                referenceHeartRate = null,
                referenceHeartRateBasis = null,
                weeks = emptyList(),
                trend =
                    unknown(
                        ProgressUnit.BEATS_PER_MINUTE,
                        ProgressBetterWhen.LOWER,
                        4,
                        "There is no FTP to set a reference power from.",
                        NOTE,
                    ),
                powerTrend =
                    unknown(
                        ProgressUnit.WATTS,
                        ProgressBetterWhen.HIGHER,
                        4,
                        "There is no threshold or maximum heart rate to set a reference heart rate from.",
                        NOTE,
                    ),
            ),
        efficiencyFactor =
            ProgressEfficiencyFactor(
                emptyList(),
                emptyList(),
                unknown(ProgressUnit.WATTS_PER_BEAT, ProgressBetterWhen.HIGHER, 5, "0 of 5 steady rides so far.", NOTE),
            ),
        decoupling =
            ProgressDecoupling(
                emptyList(),
                emptyList(),
                unknown(
                    ProgressUnit.PERCENT,
                    ProgressBetterWhen.LOWER,
                    5,
                    "0 of 5 rides of 40 minutes or more so far.",
                    NOTE,
                ),
            ),
        restingHeartRate =
            ProgressRestingHeartRate(
                emptyList(),
                unknown(
                    ProgressUnit.BEATS_PER_MINUTE,
                    ProgressBetterWhen.LOWER,
                    4,
                    "0 of 4 weeks have 3 nights with the watch.",
                    NOTE,
                ),
            ),
        weight =
            ProgressWeight(
                emptyList(),
                unknown(
                    ProgressUnit.WATTS_PER_KILOGRAM,
                    ProgressBetterWhen.HIGHER,
                    4,
                    "0 of 4 weeks have 2 weighings and an FTP.",
                ),
                null,
            ),
    )

    /**
     * An answer where heart rate at the reference power fell from [first] to [last] bpm over six weeks from 28 Sep 2026: a verdict
     * ("fitter") and the headline that carries it.
     */
    fun heartRateFalling(
        first: Double = 146.0,
        last: Double = 141.0,
    ): ProgressResponse {
        val weeks =
            (0 until 6).map { i ->
                ProgressEfficiencyWeek(
                    weekStart = PlanCalendar.addDays("2026-09-28", 7 * i),
                    heartRateAtPower = first + (last - first) * i / 5,
                    powerAtHeartRate = null,
                    atPower = ProgressStretchCount(1, 600),
                    atHeartRate = ProgressStretchCount(0, 0),
                )
            }
        val change = last - first
        val trend =
            ProgressTrend(
                direction = ProgressDirection.IMPROVING,
                betterWhen = ProgressBetterWhen.LOWER,
                unit = ProgressUnit.BEATS_PER_MINUTE,
                points = 6,
                minPoints = 4,
                change = change,
                difference = change,
                from = first,
                latest = last,
                since = "2026-09-28",
                steadyBand = 2.0,
                verdictText = "Heart rate at the same power is trending down: fitter",
                reason = null,
                note = NOTE,
            )
        val base = empty(from = "2026-09-28", to = "2026-11-18")
        return base.copy(
            headline =
                ProgressHeadline(
                    ProgressMeasure.AEROBIC_EFFICIENCY,
                    ProgressHeadlineDirection.IMPROVING,
                    trend.verdictText,
                ),
            aerobicEfficiency =
                base.aerobicEfficiency.copy(
                    ftpAtStart = 257,
                    referencePower = 180,
                    referenceHeartRate = 135,
                    referenceHeartRateBasis = ProgressHeartRateBasis.THRESHOLD,
                    weeks = weeks,
                    trend = trend,
                ),
        )
    }

    fun json(response: ProgressResponse): String = ProgressApiJson.encode(response)
}
