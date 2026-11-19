package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.repeats.RepeatAttempt
import app.overdrive.core.repeats.RepeatChange
import app.overdrive.core.repeats.RepeatDetailResponse
import app.overdrive.core.repeats.RepeatDirection
import app.overdrive.core.repeats.RepeatSummary
import app.overdrive.core.repeats.RepeatTrend
import app.overdrive.core.repeats.RepeatsResponse
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.DotLayer
import app.overdrive.uikit.chart.LineLayer
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.ReadoutKey
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The repeated sessions of the Rides tab and their page (spec §11.7, ADR 0028, design/companion.md §4.12). Every figure on these screens
 * is the service's (heart rate at the same power, drift, compliance, the trends and the direction of each): the models here only choose
 * words and draw what they are given. A trend is called "trending down" only when the service says so, and the service says so only with
 * at least three attempts that have the figure.
 */

/** One repeated session in the Rides tab's list (design/companion.md §4.12): the card's rows follow [position]. */
@Immutable
data class RepeatRowUi(
    val hash: String,
    val title: String,
    /** "4 attempts · last Wed 18 Nov". */
    val subtitle: String,
    /** The latest attempt against the previous in one plain line. */
    val line: String,
    val position: CardPosition,
)

@Immutable
class RepeatsContent(
    val rows: List<RepeatRowUi>,
)

/** The list of repeated sessions made from `GET /v1/repeats`. Pure, like the other screens' models. */
object RepeatsUi {
    const val EMPTY_HEADING = "No repeated sessions yet"
    const val EMPTY_TEXT =
        "Ride the same workout twice on the tablet and it appears here, with every attempt side by side."

    fun of(
        state: ResourceState<RepeatsResponse>,
        words: Words,
        host: String,
    ): ScreenUi<RepeatsContent> =
        screenOf(
            state,
            words,
            host,
            loading = "Loading your repeated sessions…",
            empty = EMPTY_HEADING to EMPTY_TEXT,
            isEmpty = { it.repeats.isEmpty() },
        ) { content(it, words) }

    fun content(
        response: RepeatsResponse,
        words: Words,
    ): RepeatsContent {
        val all = response.repeats
        return RepeatsContent(
            all.mapIndexed { index, summary ->
                val position =
                    when {
                        all.size == 1 -> CardPosition.Only
                        index == 0 -> CardPosition.First
                        index == all.lastIndex -> CardPosition.Last
                        else -> CardPosition.Middle
                    }
                RepeatRowUi(
                    hash = summary.hash,
                    title = summary.name,
                    subtitle = subtitle(summary, words),
                    line = line(summary.comparison.heartRateAtPower),
                    position = position,
                )
            },
        )
    }

    /** "4 attempts · last Wed 18 Nov". */
    fun subtitle(
        summary: RepeatSummary,
        words: Words,
    ): String = "${summary.attempts} attempts · last ${words.day(summary.lastDate)}"

    /**
     * The latest attempt against the one before it, in one plain line: "Heart rate at the same power 144 bpm, 3 lower than last time".
     * It does not say "fitter": one comparison proves little (spec §11.7), and the page's trend is where that word may appear. Without
     * both figures it says the service's own reason.
     */
    fun line(change: RepeatChange): String {
        val latest = change.latest
        val difference = change.difference
        if (latest != null && change.previous != null && difference != null) {
            val size = abs(difference).roundToInt()
            val how =
                when {
                    size == 0 -> "the same as last time"
                    difference < 0 -> "$size lower than last time"
                    else -> "$size higher than last time"
                }
            return "Heart rate at the same power ${latest.roundToInt()} bpm, $how"
        }
        return "Heart rate at the same power: ${change.reason.orEmpty()}"
    }
}

// region the page of one session

/** The trend of one measure in words, next to its chart (design/companion.md §4.12). */
@Immutable
data class TrendUi(
    /** "Heart rate at the same power is trending down: fitter" or "Not enough attempts yet". */
    val text: String,
    /** "Down 4 bpm over 5 attempts", or "2 of 3 attempts have a figure". */
    val detail: String,
)

/** One chart card of the page: the chart, or the sentence that replaces it when no attempt has the figure, and the trend in words. */
@Immutable
class RepeatChartUi(
    val heading: String,
    val chart: LineChartUi?,
    /** What a card says instead of a chart when no attempt has the figure (design/companion.md §5, "Partial"). */
    val emptyText: String?,
    val trend: TrendUi?,
)

/** One attempt as a row of the attempts card; tapping it opens the ride. */
@Immutable
data class AttemptRowUi(
    val rideId: String,
    /** "Attempt 4 · Wed 18 Nov". */
    val title: String,
    /** "1:05:00 · 196 W · compliance 100%". */
    val subtitle: String,
    /** The heart rate at the same power, or the dash. */
    val value: String,
    /** "bpm at power", or "no figure". */
    val caption: String,
    /** Why there is no heart rate figure, in the service's words, or null. */
    val reason: String?,
    val description: String,
)

/** One fixed-power step of the table. */
@Immutable
data class StepRowUi(
    val label: String,
    /** "220 W · 10:00". */
    val caption: String,
    val cells: List<String>,
    val description: String,
)

/** The average heart rate of each compared step in each attempt, side by side: the table twin of itself (no Values button). */
@Immutable
class StepTableUi(
    val heading: String,
    /** "#1", "#2", ...: one per attempt, oldest first; the last is the latest attempt. */
    val headers: List<String>,
    val rows: List<StepRowUi>,
    /** What a dash means, in the service's numbers; null when every cell has a figure. */
    val note: String?,
)

@Immutable
class RepeatDetailContent(
    val name: String,
    /** "6 attempts · 8 Oct to 18 Nov". */
    val whenLine: String,
    val heartRate: RepeatChartUi,
    val attempts: List<AttemptRowUi>,
    val steps: StepTableUi?,
    val drift: RepeatChartUi,
    val compliance: RepeatChartUi,
    val power: RepeatChartUi,
    val duration: RepeatChartUi,
)

/** The page of one repeated session made from `GET /v1/repeats/{hash}` (design/companion.md §4.12, §6.10). Pure, like the other models. */
object RepeatDetailUi {
    fun of(
        state: ResourceState<RepeatDetailResponse>,
        words: Words,
        host: String,
    ): ScreenUi<RepeatDetailContent> =
        screenOf(state, words, host, loading = "Loading the session…", empty = null) { content(it, words) }

    fun content(
        detail: RepeatDetailResponse,
        words: Words,
    ): RepeatDetailContent {
        val attempts = detail.attempts
        val definition = detail.definition

        fun figures(of: (RepeatAttempt) -> Double?) = DoubleArray(attempts.size) { of(attempts[it]) ?: Double.NaN }
        val heartRates = figures { it.heartRateAtPower.value }
        val drifts = figures { it.driftPercent.value }
        val compliances = figures { it.compliance.percent }
        val powers = figures { it.averagePower }
        val minutes = figures { it.durationSeconds / SECONDS_PER_MINUTE }
        return RepeatDetailContent(
            name = detail.name,
            whenLine =
                "${attempts.size} attempts · ${words.dayShort(attempts.first().date)} to " +
                    words.dayShort(attempts.last().date),
            heartRate =
                card(
                    detail,
                    words,
                    Series(
                        heading = "Heart rate at the same power, bpm",
                        name = "Heart rate",
                        values = heartRates,
                        axis = valueAxis(heartRates, pad = 1.0),
                        format = { "${it.roundToInt()} bpm" },
                        cell = { "${it.roundToInt()}" },
                        column = "bpm",
                        note = detail.note,
                        emptyText =
                            "No attempt has a heart rate at the same power yet. " +
                                "A step needs ${definition.minStepSeconds} s within " +
                                "${definition.powerTolerancePercent}% of its target power, " +
                                "after its first ${definition.skipSeconds} s.",
                        trend =
                            trendOf(
                                detail.trends.heartRateAtPower,
                                "Heart rate at the same power",
                                Measure.HeartRate,
                            ),
                    ),
                ),
            attempts = attempts.reversed().map { attemptRow(it, words) },
            steps = stepTable(detail, words),
            drift =
                card(
                    detail,
                    words,
                    Series(
                        heading = "Aerobic drift, %",
                        name = "Drift",
                        values = drifts,
                        axis = zeroBasedAxis(drifts, step = DRIFT_STEP, minTop = DRIFT_MIN_TOP),
                        format = { "${UiFormat.decimal(it, 1)}%" },
                        cell = { "${UiFormat.decimal(it, 1)}%" },
                        column = "Drift",
                        note = DRIFT_NOTE,
                        emptyText =
                            "No attempt has an aerobic drift figure yet. " +
                                "Drift is measured on steady stretches of 40 minutes or more with heart rate.",
                        trend = trendOf(detail.trends.driftPercent, "Aerobic drift", Measure.Drift),
                    ),
                ),
            compliance =
                card(
                    detail,
                    words,
                    Series(
                        heading = "Compliance, %",
                        name = "Compliance",
                        values = compliances,
                        axis = percentAxis(),
                        format = { "${it.roundToInt()}%" },
                        cell = { "${it.roundToInt()}%" },
                        column = "Compliance",
                        note = "The share of the planned time you rode.",
                        emptyText = "No attempt has a compliance figure.",
                        trend = trendOf(detail.trends.compliance, "Compliance", Measure.Compliance),
                    ),
                ),
            power =
                card(
                    detail,
                    words,
                    Series(
                        heading = "Average power, W",
                        name = "Power",
                        values = powers,
                        axis = valueAxis(powers, pad = 5.0),
                        format = { "${it.roundToInt()} W" },
                        cell = { "${it.roundToInt()} W" },
                        column = "Power",
                        note = null,
                        emptyText = "No power was recorded in these attempts.",
                        trend = null,
                    ),
                ),
            duration =
                card(
                    detail,
                    words,
                    Series(
                        heading = "Duration, min",
                        name = "Duration",
                        values = minutes,
                        axis = valueAxis(minutes, pad = 2.0),
                        format = { words.duration((it * SECONDS_PER_MINUTE).roundToInt()) },
                        cell = { words.duration((it * SECONDS_PER_MINUTE).roundToInt()) },
                        column = "Duration",
                        note = null,
                        emptyText = "These attempts have no duration.",
                        trend = null,
                    ),
                ),
        )
    }

    // region the trend in words

    private enum class Measure(
        val decimals: Int,
        val unit: String,
        /** Lower is better and a fall means fitter: only the heart rate at the same power may say so. */
        val fitterWhenDown: Boolean,
    ) {
        HeartRate(0, "bpm", true),
        Drift(1, "%", false),
        Compliance(0, "%", false),
    }

    /**
     * The service's trend in words. "Is trending down: fitter" is said only when the service says the direction is improving for the
     * heart rate at the same power, and only with at least [RepeatTrend.minAttempts] attempts that have the figure; below that the
     * answer is "Not enough attempts yet" and how many attempts have a figure.
     */
    private fun trendOf(
        trend: RepeatTrend,
        subject: String,
        measure: Measure,
    ): TrendUi {
        if (trend.direction == RepeatDirection.UNKNOWN || trend.attempts < trend.minAttempts) {
            return TrendUi(
                "Not enough attempts yet",
                "${trend.attempts} of ${trend.minAttempts} attempts have a figure",
            )
        }
        val over = "over ${trend.attempts} attempts"

        // "4 bpm", "3.0%": a percent is written close to its number.
        fun amount(value: Double): String {
            val number =
                if (measure.decimals ==
                    0
                ) {
                    value.roundToInt().toString()
                } else {
                    UiFormat.decimal(value, measure.decimals)
                }
            return if (measure.unit == "%") "$number%" else "$number ${measure.unit}"
        }
        if (trend.direction == RepeatDirection.STEADY) {
            return TrendUi("$subject is steady", "Within ${amount(trend.steadyBelow)} $over")
        }
        val change = trend.change ?: 0.0
        val way = if (change > 0.0) "up" else "down"
        val fitter = if (measure.fitterWhenDown && trend.direction == RepeatDirection.IMPROVING) ": fitter" else ""
        return TrendUi(
            "$subject is trending $way$fitter",
            "${way.replaceFirstChar { it.uppercase() }} ${amount(abs(change))} $over",
        )
    }

    // region the charts

    private class YAxis(
        val min: Double,
        val max: Double,
        val ticks: List<AxisTick>,
    )

    /** What one chart card is made from: the figure of each attempt in the axis' unit (NaN where there is none) and the words around it. */
    private class Series(
        val heading: String,
        val name: String,
        val values: DoubleArray,
        val axis: YAxis,
        /** The figure in a readout, and in a Values row. */
        val format: (Double) -> String,
        val cell: (Double) -> String,
        val column: String,
        val note: String?,
        val emptyText: String?,
        val trend: TrendUi?,
    )

    /** A clean axis around the attempts that have a figure, [pad] above and below so no dot sits on the border. */
    private fun valueAxis(
        values: DoubleArray,
        pad: Double,
    ): YAxis {
        val known = values.filter { !it.isNaN() }
        if (known.isEmpty()) return YAxis(0.0, 1.0, listOf(AxisTick(0.0, "0"), AxisTick(1.0, "1")))
        val axis = ChartMath.niceAxis(known.min() - pad, known.max() + pad, AXIS_TICKS)
        return YAxis(axis.min, axis.max, axis.ticks.map { AxisTick(it, UiFormat.decimal(it, 0)) })
    }

    /** 0 to 100 with a tick at 0, 50 and 100. */
    private fun percentAxis() =
        YAxis(0.0, 100.0, listOf(0.0, 50.0, 100.0).map { AxisTick(it, it.roundToInt().toString()) })

    /** 0 up to [minTop] or the highest figure rounded up to [step], a tick every [step]. */
    private fun zeroBasedAxis(
        values: DoubleArray,
        step: Double,
        minTop: Double,
    ): YAxis {
        val top = max(minTop, ChartMath.ceilTo(values.filter { !it.isNaN() }.maxOrNull() ?: 0.0, step))
        val ticks = generateSequence(0.0) { it + step }.takeWhile { it <= top }.toList()
        return YAxis(0.0, top, ticks.map { AxisTick(it, it.roundToInt().toString()) })
    }

    /**
     * One chart card over the attempts (design/companion.md §6.10): x is the attempt (1, 2, 3, ...), equally spaced, a 6 dp dot per attempt
     * and a thin line between neighbours, with a gap where an attempt has no figure. When no attempt has the figure the card says so in
     * one sentence and draws no frame.
     */
    private fun card(
        detail: RepeatDetailResponse,
        words: Words,
        series: Series,
    ): RepeatChartUi {
        if (series.values.all { it.isNaN() }) {
            return RepeatChartUi(series.heading, null, series.emptyText, series.trend)
        }
        val count = detail.attempts.size
        val xs = DoubleArray(count) { (it + 1).toDouble() }
        val values = series.values
        val spec =
            PlotSpec(
                xMin = 0.5,
                xMax = count + 0.5,
                xTicks = xTicks(count),
                yMin = series.axis.min,
                yMax = series.axis.max,
                yTicks = series.axis.ticks,
                lines = listOf(LineLayer(xs, values, OverdriveColors.textTertiary, 1.5f, marked = false)),
                dots = listOf(DotLayer(xs, values, OverdriveColors.textPrimary, marked = true)),
                snapXs = xs,
                restX = xs.last(),
                heightDp = CHART_HEIGHT_DP,
            )
        val readoutAt = { x: Double? ->
            val index = ((x ?: xs.last()).roundToInt() - 1).coerceIn(0, count - 1)
            val attempt = detail.attempts[index]
            Readout(
                "Attempt ${attempt.number} · ${words.day(attempt.date)}",
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Dot,
                        OverdriveColors.textPrimary,
                        series.name,
                        if (values[index].isNaN()) UiFormat.EMPTY else series.format(values[index]),
                    ),
                ),
            )
        }
        val title = series.heading.substringBefore(",")
        val chart =
            LineChartUi(
                heading = series.heading,
                spec = spec,
                readoutAt = readoutAt,
                table =
                    ValuesTable(
                        title,
                        "Attempt",
                        listOf(series.column),
                        detail.attempts.indices.reversed().map { i ->
                            val attempt = detail.attempts[i]
                            ValuesRow(
                                attempt.rideId,
                                "#${attempt.number} · ${words.dayShort(attempt.date)}",
                                listOf(if (values[i].isNaN()) UiFormat.EMPTY else series.cell(values[i])),
                            )
                        },
                    ),
                summary = "$title over $count attempts",
                note = series.note,
            )
        return RepeatChartUi(series.heading, chart, null, series.trend)
    }

    private fun xTicks(count: Int): List<AxisTick> {
        val every = max(1, ceil(count / MAX_X_LABELS.toDouble()).toInt())
        return (1..count).filter { (it - 1) % every == 0 || it == count }.map { AxisTick(it.toDouble(), it.toString()) }
    }

    // region the attempts

    private fun attemptRow(
        attempt: RepeatAttempt,
        words: Words,
    ): AttemptRowUi {
        val overall = attempt.heartRateAtPower
        val parts =
            listOfNotNull(
                words.duration(attempt.durationSeconds),
                attempt.averagePower?.let { "${it.roundToInt()} W" },
                attempt.compliance.percent?.let { "compliance ${it.roundToInt()}%" },
            )
        val title = "Attempt ${attempt.number} · ${words.day(attempt.date)}"
        val reason = overall.reason?.replaceFirstChar { it.uppercase() }
        return AttemptRowUi(
            rideId = attempt.rideId,
            title = title,
            subtitle = parts.joinToString(" · "),
            value = overall.value?.let { it.roundToInt().toString() } ?: UiFormat.EMPTY,
            caption = if (overall.value != null) "bpm at power" else "no figure",
            reason = reason,
            description =
                listOfNotNull(
                    title,
                    parts.joinToString(", "),
                    overall.value?.let { "heart rate at the same power ${it.roundToInt()} bpm" },
                    reason,
                ).joinToString(". "),
        )
    }

    // region the step table

    private fun stepTable(
        detail: RepeatDetailResponse,
        words: Words,
    ): StepTableUi? {
        if (detail.comparedSteps.isEmpty() || detail.attempts.none { it.heartRateAtPower.value != null }) return null
        val definition = detail.definition
        val latest = detail.attempts.last()
        var anyDash = false
        val rows =
            detail.comparedSteps.mapNotNull { number ->
                val step = detail.steps.firstOrNull { it.step == number } ?: return@mapNotNull null
                val at = detail.steps.indexOf(step)
                val cells =
                    detail.attempts.map { attempt ->
                        val figure = attempt.stepHeartRates.getOrNull(at)?.averageHeartRate
                        if (figure == null) anyDash = true
                        figure?.let { it.roundToInt().toString() } ?: UiFormat.EMPTY
                    }
                val watts = latest.stepHeartRates.getOrNull(at)?.targetWatts
                val caption =
                    listOfNotNull(watts?.let { "${it.roundToInt()} W" }, words.duration(step.durationSeconds))
                        .joinToString(" · ")
                StepRowUi(
                    label = step.label,
                    caption = caption,
                    cells = cells,
                    description =
                        "${step.label}, $caption. " +
                            cells.indices.joinToString(", ") { "attempt ${it + 1} ${spoken(cells[it])}" },
                )
            }
        if (rows.isEmpty()) return null
        return StepTableUi(
            heading = "Heart rate by step, bpm",
            headers = detail.attempts.map { "#${it.number}" },
            rows = rows,
            note =
                if (anyDash) {
                    "A dash means fewer than ${definition.minStepSeconds} s within " +
                        "${definition.powerTolerancePercent}% of the step's power, after its first ${definition.skipSeconds} s."
                } else {
                    null
                },
        )
    }

    private fun spoken(cell: String) = if (cell == UiFormat.EMPTY) "no figure" else cell

    private const val DRIFT_STEP = 5.0
    private const val DRIFT_MIN_TOP = 10.0
    private const val DRIFT_NOTE =
        "How much heart rate rose against power on steady rides of 40 minutes or more. Lower is better."
    private const val SECONDS_PER_MINUTE = 60.0
    private const val AXIS_TICKS = 4
    private const val MAX_X_LABELS = 8
    private const val CHART_HEIGHT_DP = 148f
}
