package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.metrics.FormWord
import app.overdrive.core.metrics.MetricsDay
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.MetricsRange
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.BandLayer
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.ColumnSpec
import app.overdrive.uikit.chart.LineLayer
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.ReadoutKey
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The words of the range selector (design/companion.md §3.4). */
object RangeWords {
    fun label(range: MetricsRange): String =
        when (range) {
            MetricsRange.SIX_WEEKS -> "6 weeks"
            MetricsRange.SIX_MONTHS -> "6 months"
            MetricsRange.ONE_YEAR -> "1 year"
        }

    val labels: List<String> = MetricsRange.entries.map(::label)
}

/** A tile of the Fitness group: a label, a value and one line under it. */
@Immutable
data class TileUi(
    val label: String,
    val value: String?,
    val caption: String?,
    val unit: String? = null,
)

/** What the Fitness screen shows when there is load (design/companion.md §4.3). */
@Immutable
class FitnessContent(
    val tiles: List<TileUi>,
    val fitness: LineChartUi,
    val form: LineChartUi,
    val weekly: ColumnChartUi,
    /** The latest day: where the selection rests. */
    val lastDay: Double,
)

/**
 * The Fitness screen made from one `GET /v1/metrics` answer (design/companion.md §4.3, §6.1 to §6.3): the three tiles of today,
 * the fitness and fatigue lines, the form line over its "Training hard" band, and the weekly columns. Pure: the screen only draws
 * it, and every word is tested here.
 */
object FitnessUi {
    private const val CHART_TICKS = 5
    private const val WEEKS_PER_TILE_CHANGE = 28
    private const val WEEK_DAYS = 7

    fun of(
        state: app.overdrive.core.remote.ResourceState<MetricsResponse>,
        range: MetricsRange,
        words: Words,
        host: String,
    ): ScreenUi<FitnessContent> =
        screenOf(
            state,
            words,
            host,
            loading = "Loading your fitness…",
            empty =
                "No load yet" to
                    "Fitness and fatigue start with your first ride on the tablet or your first activity on the watch.",
            isEmpty = { it.firstLoadDate == null },
        ) { content(it, range, words) }

    fun content(
        response: MetricsResponse,
        range: MetricsRange,
        words: Words,
    ): FitnessContent {
        val daily = response.daily
        val today = daily.last()
        val days = DoubleArray(daily.size) { checkNotNull(PlanDates.epochDay(daily[it].date)).toDouble() }
        val rangeWords = RangeWords.label(range)
        val ctlThen = daily.getOrNull(daily.size - 1 - WEEKS_PER_TILE_CHANGE)
        val atlThen = daily.getOrNull(daily.size - 1 - WEEK_DAYS)
        val tiles =
            listOf(
                TileUi(
                    "Fitness",
                    today.ctl.roundToInt().toString(),
                    ctlThen?.let { "${words.change(today.ctl.roundToInt() - it.ctl.roundToInt())} in 4 weeks" },
                ),
                TileUi(
                    "Fatigue",
                    today.atl.roundToInt().toString(),
                    atlThen?.let { "${words.change(today.atl.roundToInt() - it.atl.roundToInt())} in 7 days" },
                ),
                TileUi("Form", UiFormat.signed(today.tsb.roundToInt()), FormWord.of(today.tsb).label),
            )
        val ticks = DateAxis.ticks(range, response.from, response.to, words)
        return FitnessContent(
            tiles = tiles,
            fitness = fitnessChart(daily, days, ticks, rangeWords, words),
            form = formChart(daily, days, ticks, rangeWords, words),
            weekly = weeklyChart(response, range, words),
            lastDay = days.last(),
        )
    }

    private fun fitnessChart(
        daily: List<MetricsDay>,
        days: DoubleArray,
        ticks: List<AxisTick>,
        rangeWords: String,
        words: Words,
    ): LineChartUi {
        val ctl = DoubleArray(daily.size) { daily[it].ctl }
        val atl = DoubleArray(daily.size) { daily[it].atl }
        val axis = ChartMath.niceAxis(min(ctl.min(), atl.min()), max(ctl.max(), atl.max()), CHART_TICKS)
        val spec =
            PlotSpec(
                xMin = days.first(),
                xMax = days.last(),
                xTicks = ticks,
                yMin = axis.min,
                yMax = axis.max,
                yTicks = axis.ticks.map { AxisTick(it, UiFormat.signed(it.roundToInt())) },
                lines =
                    listOf(
                        LineLayer(days, ctl, OverdriveColors.textPrimary, 2f),
                        LineLayer(days, atl, OverdriveColors.textTertiary, 1.5f),
                    ),
                snapXs = days,
                restX = days.last(),
                heightDp = 196f,
            )

        fun at(x: Double?): Int = ChartMath.nearestIndex(days, x ?: days.last())
        val readoutAt = { x: Double? ->
            val d = daily[at(x)]
            Readout(
                words.day(d.date),
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        "Fitness",
                        d.ctl.roundToInt().toString(),
                    ),
                    ReadoutEntry(
                        ReadoutKey.ThinLine,
                        OverdriveColors.textTertiary,
                        "Fatigue",
                        d.atl.roundToInt().toString(),
                    ),
                ),
            )
        }
        return LineChartUi(
            heading = "Fitness and fatigue",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "Fitness and fatigue",
                    "Day",
                    listOf("Fitness", "Fatigue", "Form"),
                    daily.reversed().map {
                        ValuesRow(
                            it.date,
                            words.day(it.date),
                            listOf(
                                it.ctl.roundToInt().toString(),
                                it.atl.roundToInt().toString(),
                                UiFormat.signed(it.tsb.roundToInt()),
                            ),
                        )
                    },
                ),
            summary = "Fitness and fatigue, $rangeWords",
        )
    }

    private fun formChart(
        daily: List<MetricsDay>,
        days: DoubleArray,
        ticks: List<AxisTick>,
        rangeWords: String,
        words: Words,
    ): LineChartUi {
        val tsb = DoubleArray(daily.size) { daily[it].tsb }
        val axis = ChartMath.niceAxis(min(-FORM_FLOOR, tsb.min()), max(FORM_CEILING, tsb.max()), CHART_TICKS)
        val spec =
            PlotSpec(
                xMin = days.first(),
                xMax = days.last(),
                xTicks = ticks,
                yMin = axis.min,
                yMax = axis.max,
                yTicks = axis.ticks.map { AxisTick(it, UiFormat.signed(it.roundToInt())) },
                lines = listOf(LineLayer(days, tsb, OverdriveColors.textPrimary, 2f)),
                snapXs = days,
                restX = days.last(),
                heightDp = 148f,
                bands = listOf(BandLayer(-FORM_FLOOR, -FORM_BAND_TOP, OverdriveColors.surface2, "Training hard")),
            )
        val readoutAt = { x: Double? ->
            val d = daily[ChartMath.nearestIndex(days, x ?: days.last())]
            Readout(
                words.day(d.date),
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        "Form",
                        "${UiFormat.signed(d.tsb.roundToInt())} · ${FormWord.of(d.tsb).label}",
                    ),
                ),
            )
        }
        return LineChartUi(
            heading = "Form",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "Form",
                    "Day",
                    listOf("Form", "Word"),
                    daily.reversed().map {
                        ValuesRow(
                            it.date,
                            words.day(it.date),
                            listOf(UiFormat.signed(it.tsb.roundToInt()), FormWord.of(it.tsb).label),
                        )
                    },
                ),
            summary = "Form, $rangeWords",
            note = "Fitness minus fatigue. Below ${UiFormat.signed(-10)} you are training hard; above 5 you are fresh.",
        )
    }

    private fun weeklyChart(
        response: MetricsResponse,
        range: MetricsRange,
        words: Words,
    ): ColumnChartUi {
        val weeks = response.weekly
        val lastDay = checkNotNull(PlanDates.epochDay(response.to)).toDouble()
        val starts = DoubleArray(weeks.size) { checkNotNull(PlanDates.epochDay(weeks[it].weekStart)).toDouble() }
        val values = DoubleArray(weeks.size) { weeks[it].load }
        val selectXs = DoubleArray(weeks.size) { min(starts[it] + WEEK_DAYS - 1, lastDay) }
        val axis = ChartMath.niceAxis(0.0, max(values.max(), 1.0), 4, includeZero = true)
        val spec =
            ColumnSpec(
                starts = starts,
                values = values,
                selectXs = selectXs,
                xTicks = DateAxis.weekTicks(range, weeks.map { it.weekStart }, words),
                yMax = axis.max,
                yTicks = axis.ticks.map { AxisTick(it, UiFormat.signed(it.roundToInt())) },
                restX = lastDay,
                heightDp = 150f,
                valueLabel = { UiFormat.signed(it.roundToInt()) },
            )
        val readoutAt = { x: Double? ->
            val week = weeks[spec.columnOf(x ?: lastDay)]
            val partial = checkNotNull(PlanDates.epochDay(week.weekStart)) + WEEK_DAYS - 1 > lastDay
            val entries =
                buildList {
                    add(
                        ReadoutEntry(
                            ReadoutKey.Band,
                            OverdriveColors.textPrimary,
                            "Load",
                            week.load.roundToInt().toString(),
                        ),
                    )
                    add(ReadoutEntry(ReadoutKey.None, OverdriveColors.textPrimary, "Rides", week.rides.toString()))
                    if (week.otherLoad > 0.0) {
                        add(
                            ReadoutEntry(
                                ReadoutKey.None,
                                OverdriveColors.textPrimary,
                                "Watch",
                                week.otherLoad.roundToInt().toString(),
                            ),
                        )
                    }
                }
            Readout("Week of ${words.dayShort(week.weekStart)}${if (partial) " · so far" else ""}", entries)
        }
        return ColumnChartUi(
            heading = "Weekly load",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "Weekly load",
                    "Week of",
                    listOf("Load", "Rides", "Watch"),
                    weeks.reversed().map {
                        ValuesRow(
                            it.weekStart,
                            words.dayShort(it.weekStart),
                            listOf(
                                it.load.roundToInt().toString(),
                                it.rides.toString(),
                                it.otherLoad.roundToInt().toString(),
                            ),
                        )
                    },
                ),
            summary = "Weekly load, ${RangeWords.label(range)}",
        )
    }

    private const val FORM_FLOOR = 30.0
    private const val FORM_BAND_TOP = 10.0
    private const val FORM_CEILING = 10.0
}

/** The ticks of the date axes (design/companion.md §6.1, §6.3): one rule per range, never a label within three days of an end. */
object DateAxis {
    private const val END_MARGIN_DAYS = 3
    private const val FORTNIGHT = 14

    /** Ticks for a daily axis from [from] to [to]; the value is the day number. */
    fun ticks(
        range: MetricsRange,
        from: String,
        to: String,
        words: Words,
    ): List<AxisTick> {
        val first = checkNotNull(PlanDates.epochDay(from))
        val last = checkNotNull(PlanDates.epochDay(to))
        val inside = { day: Long -> day - first >= END_MARGIN_DAYS && last - day >= END_MARGIN_DAYS }
        return when (range) {
            MetricsRange.SIX_WEEKS -> {
                generateSequence(first + FORTNIGHT) { it + FORTNIGHT }
                    .takeWhile { it <= last }
                    .filter(inside)
                    .map { AxisTick(it.toDouble(), words.dayShort(PlanCalendar.ofEpochDay(it))) }
                    .toList()
            }

            MetricsRange.SIX_MONTHS -> {
                monthStarts(first, last, 1).filter(inside).map { tick(it, words) }
            }

            MetricsRange.ONE_YEAR -> {
                monthStarts(first, last, 3).filter(inside).map { tick(it, words) }
            }
        }
    }

    private fun tick(
        day: Long,
        words: Words,
    ) = AxisTick(day.toDouble(), words.monthShort(PlanCalendar.ofEpochDay(day)))

    /** The first of each month from [first] to [last] that is January, or every [every] months from January. */
    private fun monthStarts(
        first: Long,
        last: Long,
        every: Int,
    ): List<Long> {
        val start = PlanCalendar.ofEpochDay(first)
        var year = start.take(4).toInt()
        var month = start.substring(5, 7).toInt()
        val out = ArrayList<Long>()
        while (true) {
            val day = checkNotNull(PlanDates.epochDay("%04d-%02d-01".format(year, month)))
            if (day > last) return out
            if (day >= first && (month - 1) % every == 0) out += day
            month += 1
            if (month > 12) {
                month = 1
                year += 1
            }
        }
    }

    /**
     * Ticks of the weekly columns: the value is the column's index. Six weeks label every week start ("9 Nov"); longer ranges
     * label the week starts that open a month ("Jun").
     */
    fun weekTicks(
        range: MetricsRange,
        weekStarts: List<String>,
        words: Words,
    ): List<AxisTick> =
        weekStarts.mapIndexedNotNull { index, start ->
            when {
                range == MetricsRange.SIX_WEEKS -> AxisTick(index.toDouble(), words.dayShort(start))
                start.substring(8, 10).toInt() <= 7 && index > 0 -> AxisTick(index.toDouble(), words.monthShort(start))
                else -> null
            }
        }
}
