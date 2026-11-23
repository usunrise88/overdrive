package app.overdrive.companion.model

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.ProgressRange
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.NiceAxis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/*
 * The axes and the gaps of the Progress charts (design/companion.md §6.13) as pure functions: the month ticks of the three ranges, the
 * y axis at the steps a measure reads in, the label of a tick, and which points of a line stand alone (and so need a dot to be seen).
 * The design describes the tick rules in words ("6 months: every month; 1 year: every second month; all time: every quarter, or every
 * year beyond two years"); the exact rules are here and tested.
 */
object ProgressAxis {
    /** A tick this close to either end of the axis is left out (design/companion.md §6.1). */
    const val END_MARGIN_DAYS = 3

    /** A month's own point is on its 15th: the second week of the month, in the middle of it. */
    const val MID_MONTH_OFFSET = 14L

    private const val SIX_MONTHS_SPAN = 200L
    private const val YEAR_SPAN = 365L
    private const val TWO_YEARS_SPAN = 730L
    private const val EVERY_MONTH = 1
    private const val EVERY_SECOND_MONTH = 2
    private const val EVERY_QUARTER = 3
    private const val EVERY_YEAR = 12
    private const val MONTHS_PER_YEAR = 12
    private const val EPSILON = 1e-9

    /** How far apart the ticks are, in months, and whether a January is written as its year. */
    data class TickRule(
        val everyMonths: Int,
        val januaryAsYear: Boolean,
    )

    /**
     * The tick rule of [range] for an axis that spans [spanDays] days. The two shorter ranges have the design's own rule (6 months: every
     * month, 1 year: every second month). **All time** is five years back from today whatever the history is, so the axis starts at the
     * first point and the rule follows what is shown: up to about 6 months every month, up to a year every second month, up to two
     * years every quarter (a January is then its year, so that two of them cannot be mixed up) and beyond that every year.
     */
    fun rule(
        range: ProgressRange,
        spanDays: Long,
    ): TickRule =
        when (range) {
            ProgressRange.SIX_MONTHS -> {
                TickRule(EVERY_MONTH, false)
            }

            ProgressRange.ONE_YEAR -> {
                TickRule(EVERY_SECOND_MONTH, false)
            }

            ProgressRange.ALL_TIME -> {
                when {
                    spanDays <= SIX_MONTHS_SPAN -> TickRule(EVERY_MONTH, false)
                    spanDays <= YEAR_SPAN -> TickRule(EVERY_SECOND_MONTH, false)
                    spanDays <= TWO_YEARS_SPAN -> TickRule(EVERY_QUARTER, true)
                    else -> TickRule(EVERY_YEAR, true)
                }
            }
        }

    /**
     * The ticks of a time axis from day [first] to day [last] (epoch days): the first of the months the rule names, in the calendar
     * (every second month is January, March, ...; a quarter starts in January, April, ...), at [offsetDays] after the month's start
     * (0 for weeks, [MID_MONTH_OFFSET] where the points are the months' own, so a label sits under its point). With [margin] a tick within
     * [END_MARGIN_DAYS] days of either end is left out.
     */
    fun xTicks(
        range: ProgressRange,
        first: Long,
        last: Long,
        words: Words,
        offsetDays: Long = 0,
        margin: Boolean = true,
    ): List<AxisTick> {
        val rule = rule(range, last - first)
        val out = ArrayList<AxisTick>()
        val start = PlanCalendar.ofEpochDay(first)
        var year = start.take(YEAR_CHARS).toInt()
        var month = start.substring(MONTH_FROM, MONTH_TO).toInt()
        while (true) {
            val monthStart = "%04d-%02d-01".format(year, month)
            val day = checkNotNull(PlanDates.epochDay(monthStart))
            val at = day + offsetDays
            if (at > last) return out
            val inside = !margin || (at - first >= END_MARGIN_DAYS && last - at >= END_MARGIN_DAYS)
            val onRule = (month - 1) % rule.everyMonths == 0
            if (at >= first && onRule && inside) {
                val label = if (rule.januaryAsYear && month == 1) year.toString() else words.monthShort(monthStart)
                out += AxisTick(at.toDouble(), label)
            }
            month += 1
            if (month > MONTHS_PER_YEAR) {
                month = 1
                year += 1
            }
        }
    }

    /**
     * A y axis around [lo] to [hi] whose ticks are multiples of the first of [steps] (ascending) that needs at most [maxTicks] of them,
     * from a multiple at or below [lo] to one at or above [hi]. A single value, or values closer than one step, get the room of one step
     * on each side, so a chart of one point has a range (design/companion.md §5). [atLeast] keeps the axis from going below it (never
     * below zero for watts).
     */
    fun fit(
        lo: Double,
        hi: Double,
        steps: List<Double>,
        maxTicks: Int,
        atLeast: Double? = null,
    ): NiceAxis {
        require(lo <= hi && steps.isNotEmpty() && maxTicks >= 2) { "an ordered range, steps and at least two ticks" }
        var low = lo
        var high = hi
        if (high - low < steps.first() - EPSILON) {
            low -= steps.first()
            high += steps.first()
        }
        var chosen = steps.last()
        for (step in steps) {
            val ticks =
                round((ceil(high / step - EPSILON) * step - floor(low / step + EPSILON) * step) / step).toInt() + 1
            if (ticks <= maxTicks) {
                chosen = step
                break
            }
        }
        var min = floor(low / chosen + EPSILON) * chosen
        var max = ceil(high / chosen - EPSILON) * chosen
        if (atLeast != null && min < atLeast) min = ceil(atLeast / chosen - EPSILON) * chosen
        // At least three ticks, so a few close figures are not drawn on a two-line axis.
        if (round((max - min) / chosen) < 2) {
            if (atLeast == null || min - chosen >= atLeast - EPSILON) min -= chosen else max += chosen
            if (round((max - min) / chosen) < 2) max += chosen
        }
        return NiceAxis(min, max, chosen)
    }

    /** The tick label of [value] on an axis whose ticks are [step] apart: as many decimals as the step needs (0, 1 or 2). */
    fun tickLabel(
        value: Double,
        step: Double,
    ): String {
        val places =
            when {
                abs(step - round(step)) < EPSILON -> 0
                abs(step * TEN - round(step * TEN)) < EPSILON -> 1
                else -> 2
            }
        return UiFormat.decimal(if (abs(value) < EPSILON) 0.0 else value, places)
    }

    /** The ticks of [axis] as labelled marks. */
    fun yTicks(
        axis: NiceAxis,
        suffix: String = "",
    ): List<AxisTick> = axis.ticks.map { AxisTick(it, tickLabel(it, axis.step) + suffix) }

    /**
     * The figures of a line that stand alone: a point whose neighbours (both) have no figure draws no line, so it is drawn as a dot. The
     * result has the figure at those places and NaN everywhere else. A figure at the end of the series next to a gap stands alone too.
     */
    fun isolated(ys: DoubleArray): DoubleArray =
        DoubleArray(ys.size) { i ->
            val here = !ys[i].isNaN()
            val before = i > 0 && !ys[i - 1].isNaN()
            val after = i < ys.size - 1 && !ys[i + 1].isNaN()
            if (here && !before && !after) ys[i] else Double.NaN
        }

    /** How many places without a figure lie between the first and the last figure: the gaps a line is broken at. */
    fun gaps(ys: DoubleArray): Int {
        val first = ys.indexOfFirst { !it.isNaN() }
        val last = ys.indexOfLast { !it.isNaN() }
        if (first < 0) return 0
        return (first..last).count { ys[it].isNaN() }
    }

    private const val YEAR_CHARS = 4
    private const val MONTH_FROM = 5
    private const val MONTH_TO = 7
    private const val TEN = 10.0
}
