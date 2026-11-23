package app.overdrive.companion

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.progress.ProgressAerobicEfficiency
import app.overdrive.core.progress.ProgressBestEffort
import app.overdrive.core.progress.ProgressBestPower
import app.overdrive.core.progress.ProgressBestPowerTrends
import app.overdrive.core.progress.ProgressBetterWhen
import app.overdrive.core.progress.ProgressDecoupling
import app.overdrive.core.progress.ProgressDirection
import app.overdrive.core.progress.ProgressDriftRide
import app.overdrive.core.progress.ProgressEfficiencyFactor
import app.overdrive.core.progress.ProgressEfficiencyRide
import app.overdrive.core.progress.ProgressEfficiencyWeek
import app.overdrive.core.progress.ProgressHeadline
import app.overdrive.core.progress.ProgressHeadlineDirection
import app.overdrive.core.progress.ProgressHeartRateBasis
import app.overdrive.core.progress.ProgressMeasure
import app.overdrive.core.progress.ProgressMonth
import app.overdrive.core.progress.ProgressOverallTrend
import app.overdrive.core.progress.ProgressRangeDays
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.progress.ProgressRestingHeartRate
import app.overdrive.core.progress.ProgressRestingWeek
import app.overdrive.core.progress.ProgressRolling
import app.overdrive.core.progress.ProgressStretchCount
import app.overdrive.core.progress.ProgressTrend
import app.overdrive.core.progress.ProgressUnit
import app.overdrive.core.progress.ProgressWeight
import app.overdrive.core.progress.ProgressWeightChange
import app.overdrive.core.progress.ProgressWeightWeek
import kotlin.math.abs

/**
 * A full synthetic Progress answer in the numbers of the design's mock-up (`design/mockups/companion/progress.html`): a range of weeks,
 * the months of best power, heart rate falling at the reference power, power rising at the reference heart rate, an efficiency factor
 * and a drift per ride, a resting heart rate and a weight with watts per kilogram. Every figure is invented. The trends are worked out
 * here by the service's rule (a least-squares line, a steady band, a minimum) only so that the answer is valid by the contract's own
 * checks; the phone itself computes none.
 */
internal object ProgressScreenSamples {
    const val TODAY = "2026-11-18"
    const val FROM = "2026-05-18"
    const val NOTE = ProgressSamples.NOTE
    const val GENERATED = ProgressSamples.GENERATED

    /** What a variant of the full answer leaves out. */
    class Gaps(
        val bestPower: Boolean = true,
        val weight: Boolean = true,
        val resting: Boolean = true,
        val efficiencyWeeks: Int = Int.MAX_VALUE,
        val factorRides: Boolean = true,
        val driftRides: Boolean = true,
        val bestPowerWeightKnown: Boolean = true,
    )

    private fun wobble(i: Int): Double = ((i * 7919 + 13) % 100) / 100.0 - 0.5

    private fun mondayOf(date: String) = PlanCalendar.addDays(date, -(PlanCalendar.dayOfWeek(date) - 1))

    private fun monthOf(date: String) = date.substring(0, 7)

    private fun nextMonth(month: String): String {
        val year = month.substring(0, 4).toInt()
        val m = month.substring(5, 7).toInt()
        return if (m == 12) "${year + 1}-01" else "$year-${(m + 1).toString().padStart(2, '0')}"
    }

    private fun round(
        value: Double,
        places: Int,
    ): Double {
        val factor = Math.pow(10.0, places.toDouble())
        return Math.round(value * factor) / factor
    }

    /** A trend by the service's rule: the least-squares change over [points] (x, y), the band, the minimum, the words. */
    fun trend(
        points: List<Pair<Double, Double>>,
        unit: ProgressUnit,
        betterWhen: ProgressBetterWhen,
        minPoints: Int,
        band: Double,
        subject: String,
        since: String?,
        fitter: Boolean,
        heartRate: Boolean,
        reason: (Int) -> String,
        verb: String = "is",
        ends: Pair<Double, Double>? = null,
    ): ProgressTrend {
        val note = if (heartRate) NOTE else null
        if (points.size < minPoints) {
            return ProgressTrend(
                ProgressDirection.UNKNOWN,
                betterWhen,
                unit,
                points.size,
                minPoints,
                null,
                null,
                points.firstOrNull()?.second,
                points.lastOrNull()?.second,
                if (points.isEmpty()) null else since,
                band,
                "Not enough data yet",
                reason(points.size),
                note,
            )
        }
        val meanX = points.map { it.first }.average()
        val meanY = points.map { it.second }.average()
        val slope =
            points.sumOf { (it.first - meanX) * (it.second - meanY) } /
                points.sumOf { (it.first - meanX) * (it.first - meanX) }
        val change = round(slope * (points.last().first - points.first().first), 3)
        val direction =
            when {
                abs(change) < band -> ProgressDirection.STEADY
                (change < 0.0) == (betterWhen == ProgressBetterWhen.LOWER) -> ProgressDirection.IMPROVING
                else -> ProgressDirection.WORSENING
            }
        val from = round(ends?.first ?: points.first().second, 3)
        val latest = round(ends?.second ?: points.last().second, 3)
        val way = if (change < 0) "down" else "up"
        val text =
            when (direction) {
                ProgressDirection.STEADY -> "$subject $verb steady"
                ProgressDirection.IMPROVING -> "$subject $verb trending $way: ${if (fitter) "fitter" else "better"}"
                else -> "$subject $verb trending $way"
            }
        return ProgressTrend(
            direction,
            betterWhen,
            unit,
            points.size,
            minPoints,
            change,
            round(latest - from, 3),
            from,
            latest,
            since,
            band,
            text,
            null,
            note,
        )
    }

    private fun weeksFrom(
        from: String,
        to: String,
    ): List<String> {
        val first = mondayOf(from)
        val last = mondayOf(to)
        return generateSequence(first) { PlanCalendar.addDays(it, 7) }.takeWhile { it <= last }.toList()
    }

    private fun monthsFrom(
        from: String,
        to: String,
    ): List<String> {
        val last = monthOf(to)
        return generateSequence(monthOf(from)) { nextMonth(it) }.takeWhile { it <= last }.toList()
    }

    fun full(
        from: String = FROM,
        to: String = TODAY,
        gaps: Gaps = Gaps(),
    ): ProgressResponse {
        val weeks = weeksFrom(from, to)
        val n = weeks.size
        val frac = { i: Int -> if (n <= 1) 0.0 else i.toDouble() / (n - 1) }
        val ftpAt = { i: Int ->
            if (frac(i) >= 0.73) {
                250
            } else if (frac(i) >= 0.35) {
                244
            } else {
                238
            }
        }

        // aerobic efficiency
        val hr = weeks.indices.map { i -> if (i % 9 == 4) null else round(146.0 - 7.0 * frac(i) + wobble(i) * 3.0, 0) }
        val power =
            weeks.indices.map { i ->
                if (hr[i] ==
                    null
                ) {
                    null
                } else {
                    round(172.0 + 13.0 * frac(i) + wobble(i + 3) * 4.0, 0)
                }
            }
        val keep = gaps.efficiencyWeeks
        val hrKept = hr.mapIndexed { i, v -> if (i >= n - keep) v else null }
        val powerKept = power.mapIndexed { i, v -> if (i >= n - keep) v else null }
        val efWeeks =
            weeks.indices.map { i ->
                ProgressEfficiencyWeek(
                    weeks[i],
                    hrKept[i],
                    powerKept[i],
                    ProgressStretchCount(if (hrKept[i] == null) 0 else 2, if (hrKept[i] == null) 0 else 900),
                    ProgressStretchCount(if (powerKept[i] == null) 0 else 1, if (powerKept[i] == null) 0 else 600),
                )
            }
        val efReason = { count: Int ->
            "$count of 4 weeks have a figure. Ride steady for 5 minutes or more to add one."
        }
        val hrPoints = weeks.indices.filter { hrKept[it] != null }.map { it.toDouble() to hrKept[it]!! }
        val pwPoints = weeks.indices.filter { powerKept[it] != null }.map { it.toDouble() to powerKept[it]!! }
        val firstWeek = weeks.indices.firstOrNull { hrKept[it] != null }?.let { weeks[it] }
        val aerobic =
            ProgressAerobicEfficiency(
                ftpAtStart = 257,
                referencePower = 180,
                referenceHeartRate = 140,
                referenceHeartRateBasis = ProgressHeartRateBasis.THRESHOLD,
                weeks = efWeeks,
                trend =
                    trend(
                        hrPoints,
                        ProgressUnit.BEATS_PER_MINUTE,
                        ProgressBetterWhen.LOWER,
                        4,
                        2.0,
                        "Heart rate at the same power",
                        firstWeek,
                        true,
                        true,
                        efReason,
                    ),
                powerTrend =
                    trend(
                        pwPoints,
                        ProgressUnit.WATTS,
                        ProgressBetterWhen.HIGHER,
                        4,
                        4.0,
                        "Power at the same heart rate",
                        firstWeek,
                        true,
                        true,
                        efReason,
                    ),
            )

        // efficiency factor: a ride every other week
        val factorWeeks = weeks.indices.filter { it % 2 == 0 && gaps.factorRides }
        val factorRides =
            factorWeeks.mapIndexed { k, i ->
                val date = PlanCalendar.addDays(weeks[i], 0)
                val value = round(1.32 + 0.09 * frac(i) + wobble(i) * 0.02, 3)
                ProgressEfficiencyRide(
                    date,
                    "ride-ef-$k",
                    if (k % 3 ==
                        0
                    ) {
                        "Threshold 2×20"
                    } else {
                        "Endurance 90"
                    },
                    value,
                    200.0 + k,
                    round(200.0 / value, 1),
                    1500,
                )
            }
        val factorRolling =
            factorRides.indices.drop(4).map { k ->
                ProgressRolling(
                    factorRides[k].date,
                    factorRides[k].rideId,
                    round(
                        factorRides
                            .subList(
                                k - 4,
                                k + 1,
                            ).map {
                                it.value
                            }.average(),
                        3,
                    ),
                )
            }
        val factorTrend =
            trend(
                factorRides.indices.map { it.toDouble() to factorRides[it].value },
                ProgressUnit.WATTS_PER_BEAT,
                ProgressBetterWhen.HIGHER,
                5,
                0.02,
                "Efficiency factor",
                factorRides.firstOrNull()?.date,
                true,
                true,
                { "$it of 5 steady rides so far." },
                ends =
                    if (factorRides.size >= 5) {
                        factorRides.take(5).map { it.value }.average() to
                            factorRides.takeLast(5).map { it.value }.average()
                    } else {
                        null
                    },
            )

        // drift: a long ride every third week
        val driftWeeks = weeks.indices.filter { it % 3 == 1 && gaps.driftRides }
        val driftRides =
            driftWeeks.mapIndexed { k, i ->
                ProgressDriftRide(
                    PlanCalendar.addDays(weeks[i], 5),
                    "ride-dr-$k",
                    if (k % 2 ==
                        0
                    ) {
                        "Endurance 90"
                    } else {
                        "Long steady"
                    },
                    round(7.4 - 3.4 * frac(i) + wobble(i) * 1.6, 1),
                )
            }
        val driftRolling =
            driftRides.indices.drop(4).map { k ->
                ProgressRolling(
                    driftRides[k].date,
                    driftRides[k].rideId,
                    round(
                        driftRides
                            .subList(k - 4, k + 1)
                            .map {
                                it.driftPercent
                            }.average(),
                        2,
                    ),
                )
            }
        val driftTrend =
            trend(
                driftRides.indices.map { it.toDouble() to driftRides[it].driftPercent },
                ProgressUnit.PERCENT,
                ProgressBetterWhen.LOWER,
                5,
                1.0,
                "Aerobic drift",
                driftRides.firstOrNull()?.date,
                false,
                true,
                { "$it of 5 rides of 40 minutes or more so far." },
                ends =
                    if (driftRides.size >= 5) {
                        driftRides.take(5).map { it.driftPercent }.average() to
                            driftRides.takeLast(5).map { it.driftPercent }.average()
                    } else {
                        null
                    },
            )

        // resting heart rate and weight
        val restingWeeks =
            weeks.indices.map { i ->
                val nights = if (!gaps.resting || i % 11 == 9 || i % 11 == 10) 1 else 6
                ProgressRestingWeek(
                    weeks[i],
                    if (nights <
                        3
                    ) {
                        null
                    } else {
                        round(54.5 - 3.4 * frac(i) + wobble(i) * 1.2, 1)
                    },
                    nights,
                )
            }
        val restingTrend =
            trend(
                restingWeeks.indices.filter { restingWeeks[it].mean != null }.map {
                    it.toDouble() to
                        restingWeeks[it].mean!!
                },
                ProgressUnit.BEATS_PER_MINUTE,
                ProgressBetterWhen.LOWER,
                4,
                1.0,
                "Resting heart rate",
                restingWeeks.firstOrNull { it.mean != null }?.weekStart,
                false,
                true,
                { "$it of 4 weeks have 3 nights with the watch." },
            )
        val weightWeeks =
            weeks.indices.map { i ->
                val weighings = if (!gaps.weight || i % 13 == 7) 1 else 5
                val kg = if (weighings < 2) null else round(73.4 - 1.4 * frac(i) + wobble(i + 5) * 0.4, 1)
                ProgressWeightWeek(weeks[i], kg, weighings, ftpAt(i), kg?.let { round(ftpAt(i) / it, 2) })
            }
        val kgPoints = weightWeeks.filter { it.kg != null }
        val weightTrend =
            trend(
                weightWeeks.indices.filter { weightWeeks[it].wattsPerKg != null }.map {
                    it.toDouble() to
                        weightWeeks[it].wattsPerKg!!
                },
                ProgressUnit.WATTS_PER_KILOGRAM,
                ProgressBetterWhen.HIGHER,
                4,
                0.05,
                "Watts per kilogram",
                weightWeeks.firstOrNull { it.wattsPerKg != null }?.weekStart,
                false,
                false,
                { "$it of 4 weeks have 2 weighings and an FTP." },
                verb = "are",
            )

        // best power: the months of the range, the month in progress last
        val months = monthsFrom(from, to)
        val p5 = listOf(305.0, 312.0, 318.0, 322.0, 330.0, 326.0)
        val p20 = listOf(262.0, 266.0, 270.0, 276.0, 284.0, 281.0)
        val p60 = listOf(236.0, 238.0, null, 244.0, 250.0, 247.0)
        val first = months.size - 6
        val kg = 72.0
        val effort = { value: Double?, month: String, name: String, id: String ->
            if (value == null || !gaps.bestPower) {
                null
            } else {
                ProgressBestEffort(
                    value,
                    if (gaps.bestPowerWeightKnown) round(value / kg, 2) else null,
                    if (gaps.bestPowerWeightKnown) kg else null,
                    "$month-12",
                    "ride-$month-$id",
                    name,
                )
            }
        }
        val monthRows =
            months.mapIndexed { index, month ->
                val k = index - first
                ProgressMonth(
                    month,
                    month == monthOf(to),
                    effort(p5.getOrNull(k), month, "VO2 5×3", "a"),
                    effort(p20.getOrNull(k), month, "Threshold 2×20", "b"),
                    effort(p60.getOrNull(k), month, "Endurance 90", "c"),
                )
            }
        val bestTrend = { pick: (ProgressMonth) -> ProgressBestEffort?, subject: String, minutes: Int ->
            val points =
                monthRows.withIndex().filter { !it.value.partial && pick(it.value) != null }.map {
                    it.index.toDouble() to
                        pick(it.value)!!.watts
                }
            trend(
                points,
                ProgressUnit.WATTS,
                ProgressBetterWhen.HIGHER,
                3,
                if (points.isEmpty()) 5.0 else round(0.02 * points.first().second, 2),
                subject,
                monthRows.firstOrNull { !it.partial && pick(it) != null }?.let { pick(it)!!.date },
                false,
                false,
                { "$it of 3 months have a $minutes-minute ride." },
            )
        }
        val trends =
            ProgressBestPowerTrends(
                bestTrend({ it.d300 }, "Best 5-minute power", 5),
                bestTrend({ it.d1200 }, "Best 20-minute power", 20),
                bestTrend({ it.d3600 }, "Best 60-minute power", 60),
            )
        val known = listOf(trends.d300, trends.d1200, trends.d3600).filter { it.direction != ProgressDirection.UNKNOWN }
        val improving = known.count { it.direction == ProgressDirection.IMPROVING }
        val worsening = known.count { it.direction == ProgressDirection.WORSENING }
        val overall =
            when {
                known.size < 2 -> ProgressDirection.UNKNOWN
                improving >= 2 && worsening == 0 -> ProgressDirection.IMPROVING
                worsening >= 2 && improving == 0 -> ProgressDirection.WORSENING
                else -> ProgressDirection.STEADY
            }
        val bestPower =
            ProgressBestPower(
                monthRows,
                ProgressOverallTrend(
                    overall,
                    ProgressBetterWhen.HIGHER,
                    known.size,
                    when (overall) {
                        ProgressDirection.UNKNOWN -> "Not enough data yet"
                        ProgressDirection.IMPROVING -> "Best power is trending up: better"
                        ProgressDirection.WORSENING -> "Best power is trending down"
                        ProgressDirection.STEADY -> "Best power is steady"
                    },
                    if (overall == ProgressDirection.UNKNOWN) "Fewer than two durations have a verdict yet." else null,
                    null,
                ),
                trends,
            )

        val weightChange =
            if (kgPoints.size >= 2) {
                ProgressWeightChange(
                    kgPoints.size,
                    kgPoints.first().kg!!,
                    kgPoints.last().kg!!,
                    round(kgPoints.last().kg!! - kgPoints.first().kg!!, 3),
                    kgPoints.first().weekStart,
                )
            } else {
                null
            }
        val headline =
            when (aerobic.trend.direction) {
                ProgressDirection.IMPROVING -> ProgressHeadlineDirection.IMPROVING
                ProgressDirection.WORSENING -> ProgressHeadlineDirection.WORSENING
                ProgressDirection.STEADY -> ProgressHeadlineDirection.STEADY
                ProgressDirection.UNKNOWN -> null
            }?.let { ProgressHeadline(ProgressMeasure.AEROBIC_EFFICIENCY, it, aerobic.trend.verdictText) }
        return ProgressResponse(
            generatedAt = GENERATED,
            stale = false,
            range = ProgressRangeDays(from, to),
            headline = headline,
            bestPower = bestPower,
            aerobicEfficiency = aerobic,
            efficiencyFactor = ProgressEfficiencyFactor(factorRides, factorRolling, factorTrend),
            decoupling = ProgressDecoupling(driftRides, driftRolling, driftTrend),
            restingHeartRate = ProgressRestingHeartRate(restingWeeks, restingTrend),
            weight = ProgressWeight(weightWeeks, weightTrend, weightChange),
        )
    }
}
