package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.progress.ProgressBestEffort
import app.overdrive.core.progress.ProgressDirection
import app.overdrive.core.progress.ProgressHeartRateBasis
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.progress.ProgressTrend
import app.overdrive.core.remote.ProgressRange
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.BandLayer
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.DotLayer
import app.overdrive.uikit.chart.LineLayer
import app.overdrive.uikit.chart.NiceAxis
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.ReadoutKey
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Progress screen (design/companion.md §4.16, §6.13, §7.9, ADR 0043) as pure functions: the entry row's headline, the states of the
 * screen, the six cards with their verdicts, numbers lines, readouts, plots, tables and TalkBack descriptions. The service computes every
 * figure, trend and verdict sentence; the phone words the numbers around them and draws. It computes no trend.
 */

/** The words of the screen that are not a service's sentence (design/companion.md §7.9). */
object ProgressWords {
    const val TITLE = "Progress"
    const val NEUTRAL_ROW = "How your power and heart rate change over the months"
    const val LOADING = "Loading your progress…"
    const val NOT_LOADED = "Not loaded yet. It loads when the service can be reached."
    const val EMPTY_HEADING = "No progress to show yet"
    const val EMPTY_TEXT =
        "Progress fills in from rides on the tablet and from the watch. Give it a few weeks of riding."
    const val POWER = "Power"
    const val HEART_RATE = "Heart rate"
    const val CAUTION =
        "Heart rate also moves with heat, sleep and caffeine, so trust a trend over several weeks, not one ride."

    const val BEST_POWER = "Best power"
    const val WEIGHT = "Weight and watts per kilogram"
    const val AEROBIC_EFFICIENCY = "Aerobic efficiency"
    const val EFFICIENCY_FACTOR = "Efficiency factor"
    const val DRIFT = "Aerobic drift"
    const val RESTING = "Resting heart rate"

    const val NO_BEST_POWER = "No rides with power in this range."
    const val NO_EFFICIENCY = "No steady stretch of 5 minutes or more with heart rate in this range."
    const val NO_EFFICIENCY_FACTOR = "No steady ride with heart rate in this range."
    const val NO_DRIFT = "No steady rides yet. Drift is measured on steady rides of 40 minutes or more with heart rate."
    const val NO_RESTING = "No resting heart rate yet. It comes from the watch through Health Connect."
    const val NO_WEIGHT = "No weight yet. Weigh yourself with a scale that writes to Health Connect."

    const val NOTE_BEST_POWER = "Your best of each month."
    const val NOTE_EFFICIENCY = "From steady stretches of 5 minutes or more in every ride, the median of each week."
    const val NOTE_EFFICIENCY_FACTOR =
        "Normalised power divided by average heart rate on steady rides. Higher is better."
    const val NOTE_DRIFT =
        "How much heart rate rose against power on steady rides of 40 minutes or more. Lower is better."
    const val NOTE_RESTING = "From the watch, the mean of each week's nights."

    /** The range selector's segments, in the order of [ProgressRange.entries]. */
    val labels: List<String> = listOf("6 months", "1 year", "All time")

    fun label(range: ProgressRange): String = labels[range.ordinal]

    /** The range as TalkBack says it: "last 6 months", "last year", "all time". */
    fun spoken(range: ProgressRange): String =
        when (range) {
            ProgressRange.SIX_MONTHS -> "last 6 months"
            ProgressRange.ONE_YEAR -> "last year"
            ProgressRange.ALL_TIME -> "all time"
        }
}

/** The row at the end of Fitness and of Power (design/companion.md §4.16): "Progress" over the service's headline. */
@Immutable
data class ProgressEntryUi(
    /** The service's headline sentence, or the neutral one while no measure has a verdict. */
    val text: String,
    /** What TalkBack says: "Progress. Heart rate at the same power is trending down: fitter. Opens the progress page." */
    val description: String,
)

/** One plot of a card: its caption (stacked pairs only) and what it draws. */
@Immutable
class ProgressPlotUi(
    val caption: String?,
    val spec: PlotSpec,
)

/** A card of the screen. */
@Immutable
sealed interface ProgressCardUi {
    val key: String
    val name: String
}

/** A measure with nothing in the range: its name and one sentence, no frame, no verdict (design/companion.md §7.9). */
@Immutable
data class ProgressSentenceUi(
    override val key: String,
    override val name: String,
    val text: String,
) : ProgressCardUi

/**
 * A card with a chart: the service's [verdict], the [numbers] line (now against the start of the range, or why there is no verdict
 * yet), the readout at any selected x, the [plots] (one, or two stacked on one x), the Values [table] and the TalkBack [summary].
 * The verdict and the numbers are about the range and never follow the selection.
 */
@Immutable
class ProgressChartUi(
    override val key: String,
    override val name: String,
    val verdict: String,
    val numbers: String,
    val plots: List<ProgressPlotUi>,
    /** The readout at [x], or at rest for null. */
    val readoutAt: (Double?) -> Readout,
    /** The ride the selected x is, or null (the ride measures only): the readout opens it. */
    val rideAt: (Double?) -> String?,
    /** Where the selection rests: the latest point. */
    val restX: Double,
    val table: ValuesTable,
    val summary: String,
    val note: String?,
) : ProgressCardUi

@Immutable
class ProgressGroupUi(
    val heading: String,
    /** The one plain caution under the heading, or null. */
    val caution: String?,
    val cards: List<ProgressCardUi>,
)

/** What the screen shows when it has an answer, or [notice] alone when the range was never loaded. */
@Immutable
class ProgressContent(
    val groups: List<ProgressGroupUi>,
    val notice: String? = null,
)

object ProgressUi {
    private val wattSteps = listOf(5.0, 10.0, 20.0, 50.0)
    private val bestPowerSteps = listOf(50.0, 100.0, 250.0, 500.0)
    private val bpmSteps = listOf(1.0, 2.0, 5.0, 10.0)
    private val restingSteps = listOf(2.0, 4.0, 5.0, 10.0)
    private val wattsPerKgSteps = listOf(0.05, 0.1, 0.2, 0.5, 1.0)
    private val kgSteps = listOf(0.5, 1.0, 2.0, 5.0, 10.0)
    private val factorSteps = listOf(0.05, 0.1, 0.2, 0.5)

    private const val BEST_POWER_HEIGHT = 132f
    private const val STACKED_HEIGHT = 96f
    private const val SINGLE_HEIGHT = 112f
    private const val LINE_WIDTH = 2f
    private const val DRIFT_STEADY_PERCENT = 5.0
    private const val DRIFT_MIN_TOP = 10.0
    private const val DRIFT_TICK = 5.0
    private const val FIVE_RIDES = "Last 5 rides"
    private const val SECONDS_PER_MINUTE = 60

    // region entry row and states

    /** The row at the end of Fitness and Power from the default range's state. */
    fun entry(state: ResourceState<ProgressResponse>): ProgressEntryUi {
        val text = state.value?.headline?.verdictText ?: ProgressWords.NEUTRAL_ROW
        return ProgressEntryUi(text, "${ProgressWords.TITLE}. $text. Opens the progress page.")
    }

    /**
     * The screen for [state] (design/companion.md §4.16, States). [othersCached] says another range has an answer: a range that was never
     * fetched then says so in its body and the status line gives the cause, where with nothing at all it is the error block.
     */
    fun of(
        state: ResourceState<ProgressResponse>,
        range: ProgressRange,
        words: Words,
        host: String,
        othersCached: Boolean = false,
    ): ScreenUi<ProgressContent> {
        val ui =
            screenOf(
                state,
                words,
                host,
                loading = ProgressWords.LOADING,
                empty = ProgressWords.EMPTY_HEADING to ProgressWords.EMPTY_TEXT,
                isEmpty = ::nothingAtAll,
            ) { content(it, range, words) }
        val body = ui.body
        return if (body is Body.Failed && othersCached) {
            ScreenUi(
                null,
                ui.refreshing,
                body.problem,
                Body.Content(ProgressContent(emptyList(), ProgressWords.NOT_LOADED)),
            )
        } else {
            ui
        }
    }

    /** Nothing at all: no ride with power, no steady stretch and no watch figure in the range. */
    fun nothingAtAll(response: ProgressResponse): Boolean =
        response.bestPower.months.all { it.d300 == null && it.d1200 == null && it.d3600 == null } &&
            response.aerobicEfficiency.weeks.all { it.heartRateAtPower == null && it.powerAtHeartRate == null } &&
            response.efficiencyFactor.rides.isEmpty() &&
            response.decoupling.rides.isEmpty() &&
            response.restingHeartRate.weeks.all { it.mean == null } &&
            response.weight.weeks.all { it.kg == null && it.wattsPerKg == null }

    /** The two groups and their cards. */
    fun content(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressContent =
        ProgressContent(
            listOf(
                ProgressGroupUi(
                    ProgressWords.POWER,
                    null,
                    listOf(bestPower(response, range, words), weight(response, range, words)),
                ),
                ProgressGroupUi(
                    ProgressWords.HEART_RATE,
                    ProgressWords.CAUTION,
                    listOf(
                        aerobicEfficiency(response, range, words),
                        efficiencyFactor(response, range, words),
                        drift(response, range, words),
                        resting(response, range, words),
                    ),
                ),
            ),
        )

    // endregion

    // region the frame every card of a range shares

    /** The x extent and the month ticks of one card: the range, widened to the points, or from the first point for All time. */
    private class Frame(
        val xMin: Double,
        val xMax: Double,
        val ticks: List<AxisTick>,
    )

    private fun epoch(date: String): Double = checkNotNull(PlanDates.epochDay(date)).toDouble()

    private fun frame(
        response: ProgressResponse,
        range: ProgressRange,
        dataXs: DoubleArray,
        words: Words,
        monthly: Boolean = false,
    ): Frame {
        val from = epoch(response.range.from)
        val to = epoch(response.range.to)
        val first = dataXs.minOrNull()
        val last = dataXs.maxOrNull()
        val lo =
            if (range == ProgressRange.ALL_TIME && first != null) first else min(from, first ?: from)
        val hi = maxOf(to, last ?: to).let { if (it <= lo) lo + 1 else it }
        val ticks =
            ProgressAxis.xTicks(
                range,
                lo.toLong(),
                hi.toLong(),
                words,
                offsetDays = if (monthly) ProgressAxis.MID_MONTH_OFFSET else 0,
                margin = !monthly,
            )
        return Frame(lo, hi, ticks)
    }

    private fun axisOf(
        values: List<Double>,
        steps: List<Double>,
        maxTicks: Int,
        atLeast: Double? = null,
    ): NiceAxis = ProgressAxis.fit(values.min(), values.max(), steps, maxTicks, atLeast)

    private fun plot(
        caption: String?,
        frame: Frame,
        axis: NiceAxis,
        suffix: String,
        lines: List<LineLayer>,
        dots: List<DotLayer>,
        snapXs: DoubleArray,
        restX: Double,
        heightDp: Float,
        bands: List<BandLayer> = emptyList(),
    ) = ProgressPlotUi(
        caption,
        PlotSpec(
            xMin = frame.xMin,
            xMax = frame.xMax,
            xTicks = frame.ticks,
            yMin = axis.min,
            yMax = axis.max,
            yTicks = ProgressAxis.yTicks(axis, suffix),
            lines = lines,
            snapXs = snapXs,
            restX = restX,
            heightDp = heightDp,
            dots = dots,
            bands = bands,
        ),
    )

    /** A weekly line and, for the weeks that stand alone, a dot each: a point with a gap on both sides draws no line. */
    private fun weekly(
        xs: DoubleArray,
        ys: DoubleArray,
        color: androidx.compose.ui.graphics.Color,
        width: Float,
    ): Pair<LineLayer, DotLayer> =
        LineLayer(xs, ys, color, width, marked = true) to
            DotLayer(xs, ProgressAxis.isolated(ys), color)

    private fun snapOf(
        xs: DoubleArray,
        present: (Int) -> Boolean,
    ): DoubleArray = xs.indices.filter(present).map { xs[it] }.toDoubleArray()

    private fun indexAt(
        snapXs: DoubleArray,
        xs: DoubleArray,
        x: Double?,
        restX: Double,
    ): Int {
        val near = snapXs[ChartMath.nearestIndex(snapXs, x ?: restX)]
        return xs.indices.last { xs[it] == near }
    }

    // endregion

    // region words

    private fun figure(
        value: Double?,
        places: Int,
        unit: String,
    ): String =
        value?.let { "${UiFormat.decimal(it, places)}${if (unit.isEmpty()) "" else " $unit"}" } ?: UiFormat.EMPTY

    private fun whole(value: Double?): String = value?.let { UiFormat.count(it.roundToInt()) } ?: UiFormat.EMPTY

    /** "7 lower than in June" / "7 higher than in June" / "the same as in June" for a heart rate. */
    private fun comparedWith(
        difference: Double,
        since: String,
        lower: String,
        higher: String,
        words: Words,
    ): String {
        val size = abs(difference).roundToInt()
        val month = words.month(since)
        return when {
            size == 0 -> "the same as in $month"
            difference < 0 -> "$size $lower than in $month"
            else -> "$size $higher than in $month"
        }
    }

    private fun moreOrLess(difference: Double): String {
        val size = abs(difference).roundToInt()
        return when {
            size == 0 -> "no change"
            difference < 0 -> "$size less"
            else -> "$size more"
        }
    }

    /** The service's sentence for a trend that has no verdict yet; "" for none. */
    private fun reasonOf(trend: ProgressTrend): String = trend.reason.orEmpty()

    private fun hasVerdict(trend: ProgressTrend) = trend.direction != ProgressDirection.UNKNOWN

    /** "Week of 16 Nov". */
    private fun weekOf(
        date: String,
        words: Words,
    ) = "Week of ${words.dayShort(date)}"

    /**
     * The text alternative of a weekly or monthly series (design/companion.md §8): "Heart rate at 180 watts, last 6 months, weekly. 146 in
     * the week of 1 June, 140 in the week of 16 November; 3 weeks without a figure."
     */
    private fun spokenSeries(
        subject: String,
        range: ProgressRange,
        cadence: String,
        dates: List<String>,
        ys: DoubleArray,
        format: (Double) -> String,
        unit: String,
        words: Words,
    ): String {
        val first = ys.indexOfFirst { !it.isNaN() }
        val last = ys.indexOfLast { !it.isNaN() }
        val head = "$subject, ${ProgressWords.spoken(range)}, $cadence."
        if (first < 0) return "$head No figure."
        val where = { i: Int -> "in the $unit of ${words.dayMonthLong(dates[i])}" }
        val ends =
            if (first == last) {
                "${format(ys[first])} ${where(first)}"
            } else {
                "${format(ys[first])} ${where(first)}, ${format(ys[last])} ${where(last)}"
            }
        val gaps = ProgressAxis.gaps(ys)
        val gapText =
            when (gaps) {
                0 -> ""
                1 -> "; 1 $unit without a figure"
                else -> "; $gaps ${unit}s without a figure"
            }
        return "$head $ends$gapText."
    }

    // endregion

    // region best power

    private class Duration(
        val seconds: Int,
        val label: String,
        val color: androidx.compose.ui.graphics.Color,
        val pick: (app.overdrive.core.progress.ProgressMonth) -> ProgressBestEffort?,
    )

    private val durations =
        listOf(
            Duration(300, "5 min", OverdriveColors.z5) { it.d300 },
            Duration(1200, "20 min", OverdriveColors.z4) { it.d1200 },
            Duration(3600, "60 min", OverdriveColors.z3) { it.d3600 },
        )

    fun bestPower(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val power = response.bestPower
        val months = power.months
        if (months.all { m -> durations.all { it.pick(m) == null } }) {
            return ProgressSentenceUi("best", ProgressWords.BEST_POWER, ProgressWords.NO_BEST_POWER)
        }
        val to = epoch(response.range.to)
        // A month's point is its 15th; the month in progress is at most today, so nothing is drawn in the future.
        val xs =
            DoubleArray(months.size) { i ->
                val mid = epoch("${months[i].month}-01") + ProgressAxis.MID_MONTH_OFFSET
                if (months[i].partial) min(mid, to) else mid
            }
        val ysOf = durations.map { d -> DoubleArray(months.size) { i -> d.pick(months[i])?.watts ?: Double.NaN } }
        val snapXs = snapOf(xs) { i -> ysOf.any { !it[i].isNaN() } }
        val restX = snapXs.last()
        val frame = frame(response, range, snapXs, words, monthly = true)
        val all = ysOf.flatMap { it.filter { v -> !v.isNaN() } }
        val axis = axisOf(all, bestPowerSteps, MAX_TICKS_BEST, atLeast = 0.0)
        val spec =
            plot(
                null,
                frame,
                axis,
                "",
                lines =
                    durations.indices.map {
                        LineLayer(
                            xs,
                            ysOf[it],
                            durations[it].color,
                            LINE_WIDTH,
                            marked = true,
                        )
                    },
                dots = durations.indices.map { DotLayer(xs, ysOf[it], durations[it].color) },
                snapXs = snapXs,
                restX = restX,
                heightDp = BEST_POWER_HEIGHT,
            )
        val monthName = { i: Int ->
            val name = words.monthShortYear("${months[i].month}-01")
            if (months[i].partial) "$name · so far" else name
        }
        val readoutAt = { x: Double? ->
            val i = indexAt(snapXs, xs, x, restX)
            Readout(
                monthName(i),
                durations.indices.map {
                    ReadoutEntry(
                        ReadoutKey.Line,
                        durations[it].color,
                        durations[it].label,
                        figure(ysOf[it][i].takeUnless(Double::isNaN), 0, "W"),
                    )
                },
            )
        }
        val table =
            ValuesTable(
                ProgressWords.BEST_POWER,
                "Month",
                durations.map { it.label },
                months.indices.reversed().map { i ->
                    ValuesRow(
                        months[i].month,
                        monthName(i),
                        durations.indices.map {
                            figure(ysOf[it][i].takeUnless(Double::isNaN), 0, "W")
                        },
                    )
                },
            )
        return ProgressChartUi(
            key = "best",
            name = ProgressWords.BEST_POWER,
            verdict = power.trend.verdictText,
            numbers = bestPowerNumbers(response, words),
            plots = listOf(spec),
            readoutAt = readoutAt,
            rideAt = { null },
            restX = restX,
            table = table,
            summary = bestPowerSummary(response, range, ysOf, words),
            note = bestPowerNote(response),
        )
    }

    /** "5 min +21 W · 20 min +19 W · 60 min +11 W since June"; the service's reason while the verdict is unknown. */
    fun bestPowerNumbers(
        response: ProgressResponse,
        words: Words,
    ): String {
        val power = response.bestPower
        if (power.trend.direction == ProgressDirection.UNKNOWN) return power.trend.reason.orEmpty()
        val trends = listOf(power.trends.d300, power.trends.d1200, power.trends.d3600)
        val lines = durations.indices.filter { trends[it].difference != null && trends[it].since != null }
        val months = lines.map { words.month(trends[it].since!!) }.distinct()
        val parts =
            lines.map { i ->
                val d = trends[i].difference!!
                val tail = if (months.size > 1) " since ${words.month(trends[i].since!!)}" else ""
                "${durations[i].label} ${words.change(d.roundToInt())} W$tail"
            }
        return parts.joinToString(" · ") + if (months.size == 1) " since ${months[0]}" else ""
    }

    /** "Your best of each month." and, with a weight, "3.9 W/kg at 20 min with 72.0 kg.": the latest month that has one. */
    fun bestPowerNote(response: ProgressResponse): String {
        val preferred = listOf(durations[1], durations[0], durations[2])
        for (month in response.bestPower.months.asReversed()) {
            for (d in preferred) {
                val effort = d.pick(month) ?: continue
                val perKg = effort.wattsPerKg ?: continue
                val kg = effort.weightKg ?: continue
                val perKgText = "${UiFormat.decimal(perKg, 1)} W/kg"
                return "${ProgressWords.NOTE_BEST_POWER} $perKgText at ${d.label} with ${UiFormat.decimal(kg, 1)} kg."
            }
        }
        return ProgressWords.NOTE_BEST_POWER
    }

    private fun bestPowerSummary(
        response: ProgressResponse,
        range: ProgressRange,
        ysOf: List<DoubleArray>,
        words: Words,
    ): String {
        val months = response.bestPower.months
        val lines =
            durations.indices.map { d ->
                val ys = ysOf[d]
                val first = ys.indexOfFirst { !it.isNaN() }
                val last = ys.indexOfLast { !it.isNaN() }
                val minutes = durations[d].seconds / SECONDS_PER_MINUTE
                when {
                    first < 0 -> "no $minutes-minute ride"
                    first == last -> "$minutes minutes ${ys[first].roundToInt()} watts"
                    else -> "$minutes minutes from ${ys[first].roundToInt()} to ${ys[last].roundToInt()} watts"
                }
            }
        val missing =
            durations.indices.flatMap { d ->
                val ys = ysOf[d]
                val first = ys.indexOfFirst { !it.isNaN() }
                val last = ys.indexOfLast { !it.isNaN() }
                if (first < 0) {
                    emptyList()
                } else {
                    (first..last).filter { ys[it].isNaN() }.map { i ->
                        val minutes = durations[d].seconds / SECONDS_PER_MINUTE
                        "no $minutes-minute ride in ${words.month("${months[i].month}-01")}"
                    }
                }
            }
        val what = (lines + missing).joinToString(", ")
        return "Best power by month, ${ProgressWords.spoken(range)}. $what. ${response.bestPower.trend.verdictText}."
    }

    // endregion

    // region weight and watts per kilogram

    fun weight(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val weeks = response.weight.weeks
        val kgs = DoubleArray(weeks.size) { weeks[it].kg ?: Double.NaN }
        val perKg = DoubleArray(weeks.size) { weeks[it].wattsPerKg ?: Double.NaN }
        val hasKg = kgs.any { !it.isNaN() }
        val hasPerKg = perKg.any { !it.isNaN() }
        if (!hasKg && !hasPerKg) {
            return ProgressSentenceUi("weight", ProgressWords.WEIGHT, ProgressWords.NO_WEIGHT)
        }
        val xs = DoubleArray(weeks.size) { epoch(weeks[it].weekStart) }
        val snapXs = snapOf(xs) { !kgs[it].isNaN() || !perKg[it].isNaN() }
        val restX = snapXs.last()
        val frame = frame(response, range, snapXs, words)
        val plots = ArrayList<ProgressPlotUi>()
        if (hasPerKg) {
            val axis = axisOf(perKg.filter { !it.isNaN() }, wattsPerKgSteps, MAX_TICKS_STACKED)
            val (line, dots) = weekly(xs, perKg, OverdriveColors.textPrimary, LINE_WIDTH)
            plots +=
                plot(
                    "Watts per kilogram (FTP)",
                    frame,
                    axis,
                    "",
                    listOf(line),
                    listOf(dots),
                    snapXs,
                    restX,
                    STACKED_HEIGHT,
                )
        }
        if (hasKg) {
            val axis = axisOf(kgs.filter { !it.isNaN() }, kgSteps, MAX_TICKS_STACKED)
            val (line, dots) = weekly(xs, kgs, OverdriveColors.textTertiary, LINE_WIDTH)
            plots += plot("Weight, kg", frame, axis, "", listOf(line), listOf(dots), snapXs, restX, STACKED_HEIGHT)
        }
        val readoutAt = { x: Double? ->
            val i = indexAt(snapXs, xs, x, restX)
            Readout(
                weekOf(weeks[i].weekStart, words),
                listOfNotNull(
                    if (hasPerKg) {
                        ReadoutEntry(
                            ReadoutKey.Line,
                            OverdriveColors.textPrimary,
                            "FTP per kg",
                            figure(perKg[i].takeUnless(Double::isNaN), 2, "W/kg"),
                        )
                    } else {
                        null
                    },
                    if (hasKg) {
                        ReadoutEntry(
                            ReadoutKey.Line,
                            OverdriveColors.textTertiary,
                            "Weight",
                            figure(kgs[i].takeUnless(Double::isNaN), 1, "kg"),
                        )
                    } else {
                        null
                    },
                ),
            )
        }
        val table =
            ValuesTable(
                ProgressWords.WEIGHT,
                "Week",
                listOf("W/kg", "Weight"),
                weeks.indices.reversed().map { i ->
                    ValuesRow(
                        weeks[i].weekStart,
                        words.dayShortYear(weeks[i].weekStart),
                        listOf(
                            figure(perKg[i].takeUnless(Double::isNaN), 2, ""),
                            figure(kgs[i].takeUnless(Double::isNaN), 1, "kg"),
                        ),
                    )
                },
            )
        val summary =
            buildList {
                if (hasPerKg) {
                    add(
                        spokenSeries(
                            "Watts per kilogram",
                            range,
                            "weekly",
                            weeks.map {
                                it.weekStart
                            },
                            perKg,
                            { UiFormat.decimal(it, 2) },
                            "week",
                            words,
                        ),
                    )
                }
                if (hasKg) {
                    add(
                        spokenSeries(
                            "Weight in kilograms",
                            range,
                            "weekly",
                            weeks.map {
                                it.weekStart
                            },
                            kgs,
                            { UiFormat.decimal(it, 1) },
                            "week",
                            words,
                        ),
                    )
                }
                add("${response.weight.trend.verdictText}.")
            }.joinToString(" ")
        return ProgressChartUi(
            key = "weight",
            name = ProgressWords.WEIGHT,
            verdict = response.weight.trend.verdictText,
            numbers = weightNumbers(response, words),
            plots = plots,
            readoutAt = readoutAt,
            rideAt = { null },
            restX = restX,
            table = table,
            summary = summary,
            note = null,
        )
    }

    /** "3.47 W/kg now, 3.25 in June · weight 72.1 kg, 1.2 kg less"; the weight is reported and never judged. */
    fun weightNumbers(
        response: ProgressResponse,
        words: Words,
    ): String {
        val weight = response.weight
        val trend = weight.trend
        val weeks = weight.weeks
        val first =
            if (!hasVerdict(trend)) {
                reasonOf(trend)
            } else {
                val latestWeek = weeks.lastOrNull { it.wattsPerKg != null }
                val now =
                    if (latestWeek ==
                        weeks.lastOrNull()
                    ) {
                        "now"
                    } else {
                        "in the week of ${latestWeek?.let { words.dayShort(it.weekStart) }}"
                    }
                val from = UiFormat.decimal(trend.from ?: 0.0, 2)
                "${figure(trend.latest, 2, "W/kg")} $now, $from in ${words.month(trend.since.orEmpty())}"
            }
        val change = weight.weightChange ?: return first
        val size = abs(change.difference)
        val how =
            when {
                UiFormat.decimal(size, 1) == "0.0" -> "unchanged"
                change.difference < 0 -> "${UiFormat.decimal(size, 1)} kg less"
                else -> "${UiFormat.decimal(size, 1)} kg more"
            }
        return "$first · weight ${UiFormat.decimal(change.latest, 1)} kg, $how"
    }

    // endregion

    // region aerobic efficiency

    fun aerobicEfficiency(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val ae = response.aerobicEfficiency
        val weeks = ae.weeks
        val hr = DoubleArray(weeks.size) { weeks[it].heartRateAtPower ?: Double.NaN }
        val pw = DoubleArray(weeks.size) { weeks[it].powerAtHeartRate ?: Double.NaN }
        val hasHr = hr.any { !it.isNaN() }
        val hasPw = pw.any { !it.isNaN() }
        if (!hasHr && !hasPw) {
            val text =
                if (ae.referencePower ==
                    null
                ) {
                    ae.trend.reason ?: ProgressWords.NO_EFFICIENCY
                } else {
                    ProgressWords.NO_EFFICIENCY
                }
            return ProgressSentenceUi("efficiency", ProgressWords.AEROBIC_EFFICIENCY, text)
        }
        val xs = DoubleArray(weeks.size) { epoch(weeks[it].weekStart) }
        val snapXs = snapOf(xs) { !hr[it].isNaN() || !pw[it].isNaN() }
        val restX = snapXs.last()
        val frame = frame(response, range, snapXs, words)
        val atPower = ae.referencePower?.let { "$it W" } ?: "the reference power"
        val atHeart = ae.referenceHeartRate?.let { "$it bpm" } ?: "the reference heart rate"
        val plots = ArrayList<ProgressPlotUi>()
        if (hasHr) {
            val axis = axisOf(hr.filter { !it.isNaN() }, bpmSteps, MAX_TICKS_STACKED)
            val (line, dots) = weekly(xs, hr, OverdriveColors.textPrimary, LINE_WIDTH)
            plots +=
                plot(
                    "Heart rate at $atPower, bpm",
                    frame,
                    axis,
                    "",
                    listOf(line),
                    listOf(dots),
                    snapXs,
                    restX,
                    STACKED_HEIGHT,
                )
        }
        if (hasPw) {
            val axis = axisOf(pw.filter { !it.isNaN() }, wattSteps, MAX_TICKS_STACKED)
            val (line, dots) = weekly(xs, pw, OverdriveColors.textSecondary, LINE_WIDTH)
            plots +=
                plot("Power at $atHeart, W", frame, axis, "", listOf(line), listOf(dots), snapXs, restX, STACKED_HEIGHT)
        }
        val readoutAt = { x: Double? ->
            val i = indexAt(snapXs, xs, x, restX)
            Readout(
                weekOf(weeks[i].weekStart, words),
                listOfNotNull(
                    if (hasHr) {
                        ReadoutEntry(
                            ReadoutKey.Line,
                            OverdriveColors.textPrimary,
                            "At $atPower",
                            figure(hr[i].takeUnless(Double::isNaN), 0, "bpm"),
                        )
                    } else {
                        null
                    },
                    if (hasPw) {
                        ReadoutEntry(
                            ReadoutKey.Line,
                            OverdriveColors.textSecondary,
                            "At $atHeart",
                            figure(pw[i].takeUnless(Double::isNaN), 0, "W"),
                        )
                    } else {
                        null
                    },
                ),
            )
        }
        val table =
            ValuesTable(
                ProgressWords.AEROBIC_EFFICIENCY,
                "Week",
                listOf("At $atPower", "At $atHeart"),
                weeks.indices.reversed().map { i ->
                    ValuesRow(
                        weeks[i].weekStart,
                        words.dayShortYear(weeks[i].weekStart),
                        listOf(
                            figure(hr[i].takeUnless(Double::isNaN), 0, "bpm"),
                            figure(pw[i].takeUnless(Double::isNaN), 0, "W"),
                        ),
                    )
                },
            )
        val dates = weeks.map { it.weekStart }
        val summary =
            buildList {
                if (hasHr) {
                    add(
                        spokenSeries("Heart rate at ${atPower.replace(" W", " watts")}", range, "weekly", dates, hr, {
                            whole(it)
                        }, "week", words),
                    )
                }
                if (hasPw) {
                    add(
                        spokenSeries(
                            "Power at ${atHeart.replace(
                                " bpm",
                                " beats per minute",
                            )}",
                            range,
                            "weekly",
                            dates,
                            pw,
                            {
                                whole(it)
                            },
                            "week",
                            words,
                        ),
                    )
                }
                add("${ae.trend.verdictText}.")
            }.joinToString(" ")
        return ProgressChartUi(
            key = "efficiency",
            name = ProgressWords.AEROBIC_EFFICIENCY,
            verdict = ae.trend.verdictText,
            numbers = efficiencyNumbers(response, words),
            plots = plots,
            readoutAt = readoutAt,
            rideAt = { null },
            restX = restX,
            table = table,
            summary = summary,
            note = efficiencyNote(response),
        )
    }

    /** "139 bpm at 180 W, 7 lower than in June · 185 W at 140 bpm, 13 more"; the service's reason while the verdict is unknown. */
    fun efficiencyNumbers(
        response: ProgressResponse,
        words: Words,
    ): String {
        val ae = response.aerobicEfficiency
        if (!hasVerdict(ae.trend)) return reasonOf(ae.trend)
        val trend = ae.trend
        val atPower = ae.referencePower?.let { " at $it W" }.orEmpty()
        val first =
            "${whole(trend.latest)} bpm$atPower, " +
                comparedWith(trend.difference ?: 0.0, trend.since.orEmpty(), "lower", "higher", words)
        val power = ae.powerTrend
        if (!hasVerdict(power) || power.latest == null) return first
        val atHeart = ae.referenceHeartRate?.let { " at $it bpm" }.orEmpty()
        return "$first · ${whole(power.latest)} W$atHeart, ${moreOrLess(power.difference ?: 0.0)}"
    }

    /** The note under the pair, and where the two references come from. */
    fun efficiencyNote(response: ProgressResponse): String {
        val ae = response.aerobicEfficiency
        val power = ae.referencePower
        val heart = ae.referenceHeartRate
        val basis = if (ae.referenceHeartRateBasis == ProgressHeartRateBasis.MAXIMUM) "maximum" else "threshold"
        val reference =
            when {
                power != null && heart != null -> {
                    " $power W is about 70% of your FTP at the start of the range; " +
                        "$heart bpm about 80% of your $basis heart rate."
                }

                power != null -> {
                    " $power W is about 70% of your FTP at the start of the range."
                }

                heart != null -> {
                    " $heart bpm is about 80% of your $basis heart rate."
                }

                else -> {
                    ""
                }
            }
        return ProgressWords.NOTE_EFFICIENCY + reference
    }

    // endregion

    // region efficiency factor and drift (a dot per ride and the mean of the last five)

    private class RideSeries(
        val key: String,
        val name: String,
        val dates: List<String>,
        val rideIds: List<String>,
        val names: List<String>,
        val values: DoubleArray,
        val rolling: DoubleArray,
        val verdict: String,
        val numbers: String,
        val note: String,
        val tickSteps: List<Double>,
        val places: Int,
        val suffix: String,
        val band: BandLayer?,
        val axisTop: Double?,
        val subject: String,
    )

    fun efficiencyFactor(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val factor = response.efficiencyFactor
        if (factor.rides.isEmpty()) {
            return ProgressSentenceUi("factor", ProgressWords.EFFICIENCY_FACTOR, ProgressWords.NO_EFFICIENCY_FACTOR)
        }
        val byRide = factor.rolling5.associate { it.rideId to it.value }
        val trend = factor.trend
        val numbers =
            if (!hasVerdict(trend)) {
                reasonOf(trend)
            } else {
                val month = words.month(trend.since.orEmpty())
                "${figure(trend.latest, 2, "")} over the last 5 steady rides, ${figure(trend.from, 2, "")} in $month"
            }
        return rideCard(
            RideSeries(
                "factor",
                ProgressWords.EFFICIENCY_FACTOR,
                factor.rides.map { it.date },
                factor.rides.map { it.rideId },
                factor.rides.map { it.name },
                DoubleArray(factor.rides.size) { factor.rides[it].value },
                DoubleArray(factor.rides.size) { byRide[factor.rides[it].rideId] ?: Double.NaN },
                trend.verdictText,
                numbers,
                ProgressWords.NOTE_EFFICIENCY_FACTOR,
                factorSteps,
                2,
                "",
                null,
                null,
                "Efficiency factor",
            ),
            response,
            range,
            words,
        )
    }

    fun drift(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val decoupling = response.decoupling
        if (decoupling.rides.isEmpty()) {
            return ProgressSentenceUi("drift", ProgressWords.DRIFT, ProgressWords.NO_DRIFT)
        }
        val byRide = decoupling.rolling5.associate { it.rideId to it.value }
        val trend = decoupling.trend
        val numbers =
            if (!hasVerdict(trend)) {
                reasonOf(trend)
            } else {
                val month = words.month(trend.since.orEmpty())
                "${figure(trend.latest, 1, "")}% over the last 5 long rides, ${figure(trend.from, 1, "")}% in $month"
            }
        val values = DoubleArray(decoupling.rides.size) { decoupling.rides[it].driftPercent }
        val top = maxOf(DRIFT_MIN_TOP, ChartMath.ceilTo(values.max(), DRIFT_TICK))
        return rideCard(
            RideSeries(
                "drift",
                ProgressWords.DRIFT,
                decoupling.rides.map { it.date },
                decoupling.rides.map { it.rideId },
                decoupling.rides.map { it.name },
                values,
                DoubleArray(decoupling.rides.size) { byRide[decoupling.rides[it].rideId] ?: Double.NaN },
                trend.verdictText,
                numbers,
                ProgressWords.NOTE_DRIFT,
                listOf(DRIFT_TICK),
                1,
                "%",
                BandLayer(0.0, DRIFT_STEADY_PERCENT, OverdriveColors.surface2, "Steady"),
                top,
                "Aerobic drift",
            ),
            response,
            range,
            words,
        )
    }

    private fun rideCard(
        s: RideSeries,
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressChartUi {
        // One x per day: two steady rides on a day are separate dots, the selection snaps to the later one.
        val xs = DoubleArray(s.dates.size) { epoch(s.dates[it]) }
        val snapXs = xs.distinct().toDoubleArray()
        val restX = snapXs.last()
        val frame = frame(response, range, snapXs, words)
        val axis =
            if (s.axisTop != null) {
                NiceAxis(0.0, s.axisTop, DRIFT_TICK)
            } else {
                axisOf(s.values.toList() + s.rolling.filter { !it.isNaN() }, s.tickSteps, MAX_TICKS_SINGLE)
            }
        val spec =
            plot(
                null,
                frame,
                axis,
                s.suffix,
                lines = listOf(LineLayer(xs, s.rolling, OverdriveColors.textPrimary, LINE_WIDTH, marked = true)),
                dots = listOf(DotLayer(xs, s.values, OverdriveColors.textSecondary, marked = true)),
                snapXs = snapXs,
                restX = restX,
                heightDp = SINGLE_HEIGHT,
                bands = listOfNotNull(s.band),
            )
        val rideIndex = { x: Double? -> indexAt(snapXs, xs, x, restX) }
        val readoutAt = { x: Double? ->
            val i = rideIndex(x)
            Readout(
                "${words.day(s.dates[i])} · ${s.names[i]}",
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Dot,
                        OverdriveColors.textSecondary,
                        "Ride",
                        figure(s.values[i], s.places, "").plus(s.suffix),
                    ),
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        FIVE_RIDES,
                        s.rolling[i].takeUnless(Double::isNaN)?.let { figure(it, s.places, "") + s.suffix }
                            ?: UiFormat.EMPTY,
                    ),
                ),
            )
        }
        val table =
            ValuesTable(
                s.name,
                "Ride",
                listOf("Ride", "Last 5"),
                s.dates.indices.reversed().map { i ->
                    ValuesRow(
                        s.rideIds[i],
                        "${words.day(s.dates[i])} · ${s.names[i]}",
                        listOf(
                            figure(s.values[i], s.places, "") + s.suffix,
                            s.rolling[i].takeUnless(Double::isNaN)?.let { figure(it, s.places, "") + s.suffix }
                                ?: UiFormat.EMPTY,
                        ),
                    )
                },
            )
        val last = s.dates.lastIndex
        val rolling = s.rolling.lastOrNull { !it.isNaN() }
        val summary =
            "${s.subject} by ride, ${ProgressWords.spoken(range)}. ${s.dates.size} " +
                "${if (s.dates.size == 1) "ride" else "rides"}, " +
                "${figure(s.values.first(), s.places, "")}${s.suffix} on ${words.dayMonthLong(s.dates.first())}" +
                (
                    if (last >
                        0
                    ) {
                        ", ${figure(s.values[last], s.places, "")}${s.suffix} on ${words.dayMonthLong(s.dates[last])}"
                    } else {
                        ""
                    }
                ) +
                (rolling?.let { "; the mean of the last 5 rides ${figure(it, s.places, "")}${s.suffix}" } ?: "") +
                ". ${s.verdict}."
        return ProgressChartUi(
            key = s.key,
            name = s.name,
            verdict = s.verdict,
            numbers = s.numbers,
            plots = listOf(spec),
            readoutAt = readoutAt,
            rideAt = { x -> s.rideIds[rideIndex(x)] },
            restX = restX,
            table = table,
            summary = summary,
            note = s.note,
        )
    }

    // endregion

    // region resting heart rate

    fun resting(
        response: ProgressResponse,
        range: ProgressRange,
        words: Words,
    ): ProgressCardUi {
        val weeks = response.restingHeartRate.weeks
        val means = DoubleArray(weeks.size) { weeks[it].mean ?: Double.NaN }
        if (means.all { it.isNaN() }) {
            return ProgressSentenceUi("resting", ProgressWords.RESTING, ProgressWords.NO_RESTING)
        }
        val xs = DoubleArray(weeks.size) { epoch(weeks[it].weekStart) }
        val snapXs = snapOf(xs) { !means[it].isNaN() }
        val restX = snapXs.last()
        val frame = frame(response, range, snapXs, words)
        val axis = axisOf(means.filter { !it.isNaN() }, restingSteps, MAX_TICKS_SINGLE)
        val (line, dots) = weekly(xs, means, OverdriveColors.textPrimary, LINE_WIDTH)
        val spec = plot(null, frame, axis, "", listOf(line), listOf(dots), snapXs, restX, SINGLE_HEIGHT)
        val readoutAt = { x: Double? ->
            val i = indexAt(snapXs, xs, x, restX)
            Readout(
                weekOf(weeks[i].weekStart, words),
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        "Weekly mean",
                        figure(means[i], 0, "bpm"),
                    ),
                ),
            )
        }
        val table =
            ValuesTable(
                ProgressWords.RESTING,
                "Week",
                listOf("Mean", "Nights"),
                weeks.indices.reversed().map { i ->
                    ValuesRow(
                        weeks[i].weekStart,
                        words.dayShortYear(weeks[i].weekStart),
                        listOf(figure(means[i].takeUnless(Double::isNaN), 0, "bpm"), weeks[i].nights.toString()),
                    )
                },
            )
        val trend = response.restingHeartRate.trend
        return ProgressChartUi(
            key = "resting",
            name = ProgressWords.RESTING,
            verdict = trend.verdictText,
            numbers = restingNumbers(response, words),
            plots = listOf(spec),
            readoutAt = readoutAt,
            rideAt = { null },
            restX = restX,
            table = table,
            summary =
                spokenSeries(
                    "Resting heart rate",
                    range,
                    "weekly",
                    weeks.map { it.weekStart },
                    means,
                    { whole(it) },
                    "week",
                    words,
                ) +
                    " ${trend.verdictText}.",
            note = ProgressWords.NOTE_RESTING,
        )
    }

    /** "51 bpm this week, 54 in June"; the service's reason while the verdict is unknown. */
    fun restingNumbers(
        response: ProgressResponse,
        words: Words,
    ): String {
        val trend = response.restingHeartRate.trend
        if (!hasVerdict(trend)) return reasonOf(trend)
        val weeks = response.restingHeartRate.weeks
        val latestWeek = weeks.lastOrNull { it.mean != null }
        val timeWord =
            if (latestWeek ==
                weeks.lastOrNull()
            ) {
                "this week"
            } else {
                "in the week of ${latestWeek?.let { words.dayShort(it.weekStart) }}"
            }
        return "${whole(trend.latest)} bpm $timeWord, ${whole(trend.from)} in ${words.month(trend.since.orEmpty())}"
    }

    // endregion

    private const val MAX_TICKS_BEST = 5
    private const val MAX_TICKS_STACKED = 4
    private const val MAX_TICKS_SINGLE = 4
}
