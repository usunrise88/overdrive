package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.WellnessData
import app.overdrive.core.remote.WellnessRange
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.Reading
import app.overdrive.core.wellness.WellnessDay
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.DotLayer
import app.overdrive.uikit.chart.LineLayer
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.RangeBandLayer
import app.overdrive.uikit.chart.SleepSpec
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The words of Wellness' range selector (design/companion.md §3.4). */
object WellnessRangeWords {
    fun label(range: WellnessRange): String =
        when (range) {
            WellnessRange.FOUR_WEEKS -> "4 weeks"
            WellnessRange.THREE_MONTHS -> "3 months"
            WellnessRange.ONE_YEAR -> "1 year"
        }

    val labels: List<String> = WellnessRange.entries.map(::label)
}

/** Whether Health Connect lets the app read anything: the Wellness screen says so when there is no figure to draw. */
enum class WatchAccess { Unknown, None, Some }

/** What a Wellness card says at one selected day: the usual range at the right of the title, the figure and the verdict. */
@Immutable
class CardHead(
    val usual: String,
    /** The selected day's figure, or null for none (the card shows the dash). */
    val value: String?,
    val unit: String?,
    /** "In your usual range", "Above usual · day 3", "No reading"; null while there is no usual range to compare with. */
    val verdict: String?,
    /** Outside the usual range: a `triangle-alert` and text 1. */
    val alert: Boolean,
)

/** One card of Wellness (design/companion.md §4.5): its [head] at any selected x, its chart, or a sentence when there is no reading. */
@Immutable
class WellnessCardUi(
    val key: String,
    val title: String,
    val headAt: (Double?) -> CardHead,
    /** A line chart in its personal band, or null (then [sleep] or [empty] is set). */
    val plot: PlotSpec?,
    /** The sleep timing chart. */
    val sleep: SleepSpec?,
    /** The sentence that replaces the chart when the whole range has no reading. */
    val empty: String?,
    val table: ValuesTable,
    val summary: String,
    /** Cards that show a figure and nothing about a usual range (weight): no verdict, no band. */
    val readoutAt: ((Double?) -> String)? = null,
)

@Immutable
class WellnessContent(
    val cards: List<WellnessCardUi>,
    /** The first and the last day, as day numbers: the x axis of every card. */
    val firstDay: Double,
    val lastDay: Double,
    /** "Sun 15 Nov" for a day number: the "Showing" line. */
    val dayAt: (Double) -> String,
)

/**
 * The Wellness screen made from `GET /v1/wellness` (design/companion.md §4.5, §6.7, §7.4, §7.6, ADR 0023): one small multiple per
 * metric in the design's order, each a line in the personal band the service computed (the usual range of that day), with the days
 * outside it as dots and every figure in the words the design uses. **The phone computes no baseline and no verdict**: it draws what
 * the service says. Pure, so the words and the numbers behind every chart are unit tests.
 */
object WellnessUi {
    private const val CHART_HEIGHT = 112f
    private const val SLEEP_HEIGHT = 168f
    private const val GUTTER = 40f
    private const val SECONDS_PER_HOUR = 3600.0
    private const val NIGHT_TOP = -180.0
    private const val NIGHT_BOTTOM = 540.0
    private const val TWO_HOURS = 120.0
    private const val HOUR = 60.0

    private class Metric(
        val key: String,
        val title: String,
        val unit: String?,
        val pick: (WellnessDay) -> Reading,
        val scale: Double,
        val format: (Double?) -> String,
        val yMin: Double,
        val yMax: Double,
        val step: Double,
        val tick: (Double) -> String,
        val noReading: String,
    )

    private val whole: (Double) -> String = { UiFormat.count(it.roundToInt()) }

    /** The metrics in the design's order, with the fixed y range each is drawn on (design/companion.md §6.7). */
    private fun metrics(): List<Metric> =
        listOf(
            Metric("restingHr", "Resting heart rate", "bpm", {
                it.restingHr
            }, 1.0, WatchWords::whole, 45.0, 65.0, 5.0, whole, WORN),
            Metric("sleepSeconds", "Sleep", null, {
                it.sleepSeconds
            }, SECONDS_PER_HOUR, WatchWords::hoursMinutes, 5.0, 9.0, 1.0, { "${it.roundToInt()} h" }, SLEEP_ON),
            Metric("respiratoryRate", "Respiratory rate", "/min", {
                it.respiratoryRate
            }, 1.0, WatchWords::oneDecimal, 12.5, 15.5, 1.0, whole, WORN),
            Metric(
                "spo2",
                "Oxygen saturation",
                "%",
                { it.spo2 },
                1.0,
                WatchWords::whole,
                92.0,
                100.0,
                2.0,
                whole,
                OXYGEN_ON,
            ),
            Metric("sleepingHr", "Heart rate in sleep", "bpm", {
                it.sleepingHr
            }, 1.0, WatchWords::whole, 40.0, 70.0, 5.0, whole, WORN),
        )

    fun of(
        state: ResourceState<WellnessData>,
        range: WellnessRange,
        access: WatchAccess,
        words: Words,
        host: String,
    ): ScreenUi<WellnessContent> {
        val screen =
            screenOf(
                state,
                words,
                host,
                loading = "Loading your wellness…",
                empty =
                    "No readings yet" to
                        "Your watch's readings appear here after Overdrive has read Health Connect and sent them to your service.",
                isEmpty = { data -> data.days.none(::hasAnyReading) },
            ) { content(it, range, words) }
        val body = screen.body
        return if (body is Body.Empty && access == WatchAccess.None) {
            screen.copy(body = Body.Empty("Overdrive cannot read Health Connect yet.", NO_ACCESS, action = "Allow"))
        } else {
            screen
        }
    }

    private fun hasAnyReading(day: WellnessDay): Boolean =
        listOf(day.restingHr, day.sleepSeconds, day.respiratoryRate, day.spo2, day.sleepingHr, day.bedtimeMinutes)
            .any { it.value != null } || day.weightKg != null

    fun content(
        data: WellnessData,
        range: WellnessRange,
        words: Words,
    ): WellnessContent {
        val days = DoubleArray(data.days.size) { checkNotNull(PlanDates.epochDay(data.days[it].date)).toDouble() }
        val rangeWords = WellnessRangeWords.label(range)
        val ticks = axisTicks(days, data.days, words)
        val cards = ArrayList<WellnessCardUi>()
        for (metric in metrics()) {
            val readings = data.days.map(metric.pick)
            val empty = readings.none { it.value != null }
            // Heart rate in sleep is drawn only when the service has it; the others say so in a sentence when they have no reading.
            if (metric.key == "sleepingHr" && empty) continue
            cards += metricCard(metric, data, days, readings, ticks, rangeWords, words)
            if (metric.key == "sleepSeconds") cards += timingCard(data, days, ticks, rangeWords, words)
        }
        weightCard(data, days, ticks, rangeWords, words)?.let { cards += it }
        return WellnessContent(
            cards = cards,
            firstDay = days.first(),
            lastDay = days.last(),
            dayAt = { words.day(PlanCalendar.ofEpochDay(it.toLong())) },
        )
    }

    private fun axisTicks(
        days: DoubleArray,
        data: List<WellnessDay>,
        words: Words,
    ): List<AxisTick> {
        if (days.size < 3) return emptyList()
        val middle = days.size / 2
        return listOf(
            AxisTick(days.first(), words.dayShort(data.first().date)),
            AxisTick(days[middle], words.dayShort(data[middle].date)),
            AxisTick(days.last(), "Today"),
        )
    }

    private fun usualText(
        reading: Reading,
        metric: Metric,
        needed: Int,
    ): String =
        WatchWords.range(reading.baselineLow, reading.baselineHigh, metric.format)?.let { "usual $it" }
            ?: "Your usual range appears after $needed nights."

    private fun metricCard(
        metric: Metric,
        data: WellnessData,
        days: DoubleArray,
        readings: List<Reading>,
        ticks: List<AxisTick>,
        rangeWords: String,
        words: Words,
    ): WellnessCardUi {
        val title = metric.title
        val table =
            ValuesTable(
                title,
                "Day",
                listOf(title, "Usual"),
                data.days.indices.reversed().map { i ->
                    val reading = readings[i]
                    ValuesRow(
                        data.days[i].date,
                        words.day(data.days[i].date),
                        listOf(
                            reading.value?.let(metric.format) ?: UiFormat.EMPTY,
                            WatchWords.range(
                                reading.baselineLow,
                                reading.baselineHigh,
                                metric.format,
                            ) ?: UiFormat.EMPTY,
                        ),
                    )
                },
            )
        if (readings.none { it.value != null }) {
            return WellnessCardUi(
                metric.key,
                title,
                headAt = { CardHead("", null, null, null, false) },
                plot = null,
                sleep = null,
                empty = "No readings in the last $rangeWords. ${metric.noReading}",
                table = table,
                summary = "$title, $rangeWords. No readings.",
            )
        }
        val ys = DoubleArray(readings.size) { readings[it].value?.div(metric.scale) ?: Double.NaN }
        val lows = DoubleArray(readings.size) { readings[it].baselineLow?.div(metric.scale) ?: Double.NaN }
        val highs = DoubleArray(readings.size) { readings[it].baselineHigh?.div(metric.scale) ?: Double.NaN }
        val outside =
            readings.indices.filter {
                readings[it].deviation == Deviation.ABOVE ||
                    readings[it].deviation == Deviation.BELOW
            }
        val all = ys.indices.filter { !ys[it].isNaN() }
        val axis = axisFor(metric, ys, lows, highs)
        val spec =
            PlotSpec(
                xMin = days.first(),
                xMax = days.last(),
                xTicks = ticks,
                yMin = axis.first,
                yMax = axis.second,
                yTicks = axisTicksFor(metric, axis.first, axis.second),
                lines = listOf(LineLayer(days, ys, OverdriveColors.textSecondary, 1.5f, marked = false)),
                snapXs = days,
                restX = days.last(),
                heightDp = CHART_HEIGHT,
                dots =
                    listOf(
                        DotLayer(
                            outside
                                .map {
                                    days[it]
                                }.toDoubleArray(),
                            outside
                                .map {
                                    ys[it]
                                }.toDoubleArray(),
                            OverdriveColors.textPrimary,
                            marked = true,
                        ),
                        DotLayer(
                            all
                                .map {
                                    days[it]
                                }.toDoubleArray(),
                            all
                                .map {
                                    ys[it]
                                }.toDoubleArray(),
                            OverdriveColors.textPrimary,
                            marked = true,
                            drawn = false,
                        ),
                    ),
                gutterDp = GUTTER,
                rangeBands = listOf(RangeBandLayer(days, lows, highs, OverdriveColors.surface3)),
            )
        val headAt = { x: Double? ->
            val reading = readings[ChartMath.nearestIndex(days, x ?: days.last())]
            val verdict =
                if (reading.value == null) "No reading" else WatchWords.verdict(reading.deviation, reading.streak)
            CardHead(
                usual = usualText(reading, metric, data.nightsNeeded),
                value = reading.value?.let(metric.format),
                unit = metric.unit,
                verdict = verdict,
                alert = reading.deviation == Deviation.ABOVE || reading.deviation == Deviation.BELOW,
            )
        }
        return WellnessCardUi(
            metric.key,
            title,
            headAt,
            spec,
            null,
            null,
            table,
            "$title, $rangeWords",
        )
    }

    /** The y range of a metric: the fixed one, widened (to whole steps) when a figure or a band edge falls outside it. */
    private fun axisFor(
        metric: Metric,
        ys: DoubleArray,
        lows: DoubleArray,
        highs: DoubleArray,
    ): Pair<Double, Double> {
        val seen = (ys.toList() + lows.toList() + highs.toList()).filter { !it.isNaN() }
        var lo = metric.yMin
        var hi = metric.yMax
        if (seen.isNotEmpty()) {
            lo = min(lo, floor(seen.min() / metric.step) * metric.step)
            hi = max(hi, ceil(seen.max() / metric.step) * metric.step)
        }
        return lo to hi
    }

    private fun axisTicksFor(
        metric: Metric,
        lo: Double,
        hi: Double,
    ): List<AxisTick> {
        val first = ceil(lo / metric.step - 1e-9) * metric.step
        return generateSequence(first) {
            it + metric.step
        }.takeWhile { it <= hi + 1e-9 }.map { AxisTick(it, metric.tick(it)) }.toList()
    }

    private fun timingCard(
        data: WellnessData,
        days: DoubleArray,
        ticks: List<AxisTick>,
        rangeWords: String,
        words: Words,
    ): WellnessCardUi {
        val bed = data.days.map { it.bedtimeMinutes }
        val wake = data.days.map { it.wakeMinutes }
        val nights = data.days.indices.filter { bed[it].value != null && wake[it].value != null }
        val title = "Sleep timing"
        val table =
            ValuesTable(
                title,
                "Night ending",
                listOf("Bed", "Up"),
                data.days.indices.reversed().map { i ->
                    ValuesRow(
                        data.days[i].date,
                        words.day(data.days[i].date),
                        listOf(WatchWords.clock(bed[i].value), WatchWords.clock(wake[i].value)),
                    )
                },
            )
        if (nights.isEmpty()) {
            return WellnessCardUi(
                "bedtimeMinutes",
                title,
                headAt = { CardHead("", null, null, null, false) },
                plot = null,
                sleep = null,
                empty = "No readings in the last $rangeWords. $SLEEP_ON",
                table = table,
                summary = "$title, $rangeWords. No readings.",
            )
        }
        val bedMinutes = DoubleArray(days.size) { bed[it].value ?: Double.NaN }
        val wakeMinutes = DoubleArray(days.size) { wake[it].value ?: Double.NaN }
        val earliest = nights.minOf { bedMinutes[it] }
        val latest = nights.maxOf { wakeMinutes[it] }
        val top = min(NIGHT_TOP, floor(earliest / HOUR) * HOUR)
        val bottom = max(NIGHT_BOTTOM, ceil(latest / HOUR) * HOUR)
        val yTicks =
            generateSequence(ceil(top / TWO_HOURS) * TWO_HOURS) { it + TWO_HOURS }
                .takeWhile { it < bottom }
                .map { AxisTick(it, WatchWords.clock(it)) }
                .toList()
        val usualBed = data.usual.bedtime
        val usualWake = data.usual.wake
        val spec =
            SleepSpec(
                days = days,
                bedMinutes = bedMinutes,
                wakeMinutes = wakeMinutes,
                xMin = days.first(),
                xMax = days.last(),
                xTicks = ticks,
                topMinutes = top,
                bottomMinutes = bottom,
                yTicks = yTicks,
                restX = days.last(),
                heightDp = SLEEP_HEIGHT,
                bedBand = usualBed?.let { it.low to it.high },
                wakeBand = usualWake?.let { it.low to it.high },
                gutterDp = GUTTER,
            )
        val usual =
            if (usualBed != null && usualWake != null) {
                "usual ${WatchWords.range(
                    usualBed.low,
                    usualBed.high,
                    WatchWords::clock,
                )} to ${WatchWords.range(usualWake.low, usualWake.high, WatchWords::clock)}"
            } else {
                "Your usual times appear after ${data.nightsNeeded} nights."
            }
        val headAt = { x: Double? ->
            val i = ChartMath.nearestIndex(days, x ?: days.last())
            val b = bed[i]
            val w = wake[i]
            val has = b.value != null && w.value != null
            CardHead(
                usual = usual,
                value = if (has) "${WatchWords.clock(b.value)}–${WatchWords.clock(w.value)}" else null,
                unit = null,
                verdict = if (has) WatchWords.timingVerdict(b.deviation, b.streak) else "No reading",
                alert = b.deviation == Deviation.ABOVE || b.deviation == Deviation.BELOW,
            )
        }
        return WellnessCardUi("bedtimeMinutes", title, headAt, null, spec, null, table, "$title, $rangeWords")
    }

    /** Body weight with its 7-day average as a trend (spec §12.5): drawn only when the range has a weighing. */
    private fun weightCard(
        data: WellnessData,
        days: DoubleArray,
        ticks: List<AxisTick>,
        rangeWords: String,
        words: Words,
    ): WellnessCardUi? {
        val daily = data.days.map { it.weightKg }
        val average = data.days.map { it.weightKg7d }
        if (daily.all { it == null }) return null
        val title = "Weight"
        val table =
            ValuesTable(
                title,
                "Day",
                listOf("Weight", "7-day average"),
                data.days.indices.reversed().map { i ->
                    ValuesRow(data.days[i].date, words.day(data.days[i].date), listOf(kg(daily[i]), kg(average[i])))
                },
            )
        val ys = DoubleArray(days.size) { daily[it] ?: Double.NaN }
        val means = DoubleArray(days.size) { average[it] ?: Double.NaN }
        val seen = (ys.toList() + means.toList()).filter { !it.isNaN() }
        val axis = ChartMath.niceAxis(seen.min() - 1.0, seen.max() + 1.0, 4)
        val withValue = ys.indices.filter { !ys[it].isNaN() }
        val spec =
            PlotSpec(
                xMin = days.first(),
                xMax = days.last(),
                xTicks = ticks,
                yMin = axis.min,
                yMax = axis.max,
                yTicks = axis.ticks.map { AxisTick(it, UiFormat.count(it.roundToInt())) },
                lines =
                    listOf(
                        LineLayer(days, means, OverdriveColors.textPrimary, 2f),
                        LineLayer(days, ys, OverdriveColors.textTertiary, 1.5f, marked = false),
                    ),
                snapXs = days,
                restX = days.last(),
                heightDp = CHART_HEIGHT,
                dots =
                    listOf(
                        DotLayer(
                            withValue
                                .map {
                                    days[it]
                                }.toDoubleArray(),
                            withValue.map { ys[it] }.toDoubleArray(),
                            OverdriveColors.textTertiary,
                        ),
                    ),
                gutterDp = GUTTER,
            )
        val headAt = { x: Double? ->
            val i = ChartMath.nearestIndex(days, x ?: days.last())
            CardHead(
                usual = "7-day average",
                value = daily[i]?.let { UiFormat.decimal(it, 1) },
                unit = "kg",
                verdict = average[i]?.let { "average ${UiFormat.decimal(it, 1)} kg" } ?: "No reading",
                alert = false,
            )
        }
        return WellnessCardUi("weightKg", title, headAt, spec, null, null, table, "$title, $rangeWords")
    }

    private fun kg(value: Double?): String = value?.let { UiFormat.decimal(it, 1) } ?: UiFormat.EMPTY

    private const val WORN = "The watch writes them during sleep; check that the watch is worn at night."
    private const val SLEEP_ON =
        "The watch writes sleep when it is worn at night; check that sleep tracking is on in OHealth."
    private const val OXYGEN_ON =
        "The watch writes them during sleep; check that blood-oxygen tracking is on in OHealth."
    const val NO_ACCESS =
        "Allow Overdrive to read your watch data from Health Connect to see resting heart rate, sleep and the other signals here."
}
