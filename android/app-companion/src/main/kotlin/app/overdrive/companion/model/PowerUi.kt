package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.metrics.FtpSuggestion
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.DotLayer
import app.overdrive.uikit.chart.LineLayer
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.ReadoutKey
import app.overdrive.uikit.chart.RefLine
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.max
import kotlin.math.roundToInt

/** What the FTP card or notice says (design/companion.md §4.1, §4.4). */
@Immutable
sealed interface FtpUi {
    /** A suggestion is open: Use [watts] / Keep [current]. [waiting] says an answer is queued ("Will be sent when ..."). */
    data class Suggestion(
        val id: Int,
        val watts: Int,
        val current: Int?,
        val windowWeeks: Int,
        val basis: String,
        val waiting: Boolean,
    ) : FtpUi

    /** The rider just said Use: "FTP is now 256 W. The tablet uses it from its next sync." until the next visit. */
    data class Used(
        val watts: Int,
        val waiting: Boolean,
    ) : FtpUi

    /** The rider kept the FTP and the service has not been told yet. */
    data object Queued : FtpUi
}

/** The FTP as the screens know it: the figure, the day it was set, the latest estimate, and the open suggestion or the line after Use. */
@Immutable
data class FtpFacts(
    val current: Int?,
    val setLine: String?,
    val estimate: Int?,
    val answer: FtpUi?,
)

object FtpWords {
    /**
     * @param metrics the service's FTP block.
     * @param profile the profile in force: its FTP wins over the metrics' (an edit is on top of it at once), and a suggestion the
     * profile already has is not one any more.
     * @param justUsed the watts the rider just accepted, until the next visit to Today.
     */
    fun of(
        metrics: MetricsResponse,
        profile: ProfileState,
        answers: FtpAnswers,
        justUsed: Int?,
        words: Words,
    ): FtpFacts {
        val ftp = metrics.ftp
        val inForce = profile.profile?.ftp
        val current = inForce ?: ftp.current
        val waiting = answers.waiting > 0 || profile.pending != null
        val open: FtpSuggestion? =
            ftp.suggestion?.takeIf { it.id !in answers.dismissed && it.watts != inForce && it.watts != current }
        val answer =
            when {
                justUsed != null -> {
                    FtpUi.Used(justUsed, waiting)
                }

                open != null -> {
                    FtpUi.Suggestion(open.id, open.watts, current, weeksOf(open), basis(open), waiting)
                }

                answers.waiting > 0 -> {
                    FtpUi.Queued
                }

                else -> {
                    null
                }
            }
        return FtpFacts(current, ftp.setAt?.let { "Set ${words.dayShort(it)}" }, ftp.latestEstimate, answer)
    }

    /** The window the suggestion looked at, in whole weeks. */
    fun weeksOf(suggestion: FtpSuggestion): Int = max(1, (suggestion.windowDays + 3) / 7)

    /** "from 3 rides in 6 weeks". */
    fun basis(suggestion: FtpSuggestion): String {
        val weeks = weeksOf(suggestion)
        val rides = if (suggestion.basedOnRides == 1) "ride" else "rides"
        val weekWord = if (weeks == 1) "week" else "weeks"
        return "from ${suggestion.basedOnRides} $rides in $weeks $weekWord"
    }
}

/** One row of the best-efforts table. */
@Immutable
data class BestEffortUi(
    val duration: String,
    val watts: Int,
    val date: String,
    val rideId: String,
)

@Immutable
class PowerContent(
    val ftp: FtpFacts,
    val curve: LineChartUi?,
    val bestEfforts: List<BestEffortUi>,
    val ftpHistory: LineChartUi,
    val rangeWords: String,
)

/** The Power screen made from one `GET /v1/metrics` answer (design/companion.md §4.4, §6.4, §6.5; the aerobic drift moved to Progress). Pure, like [FitnessUi]. */
object PowerUi {
    /** The durations the best-efforts table lists, in seconds (design/companion.md §4.4). */
    val BEST_EFFORT_SECONDS: List<Int> = listOf(5, 60, 300, 1200, 3600)

    private val CURVE_TICKS = listOf(5, 60, 300, 1200, 3600)
    private const val CURVE_X_MIN = 1.0
    private const val CURVE_X_MAX = 7200.0
    private const val REST_DURATION = 1200

    fun of(
        state: ResourceState<MetricsResponse>,
        profile: ProfileState,
        answers: FtpAnswers,
        range: MetricsRange,
        words: Words,
        host: String,
    ): ScreenUi<PowerContent> =
        screenOf(
            state,
            words,
            host,
            loading = "Loading your power…",
            empty =
                "No rides yet" to
                    "The power curve and FTP fill in from rides on the tablet. They appear here after the tablet uploads its first ride.",
            isEmpty = { it.firstLoadDate == null && it.powerCurve.allTime.isEmpty() && it.ftp.current == null },
        ) { content(it, profile, answers, range, words) }

    fun content(
        response: MetricsResponse,
        profile: ProfileState,
        answers: FtpAnswers,
        range: MetricsRange,
        words: Words,
    ): PowerContent {
        val rangeWords = RangeWords.label(range)
        val ftp = FtpWords.of(response, profile, answers, justUsed = null, words)
        val rangePoints = response.powerCurve.range
        return PowerContent(
            ftp = ftp,
            curve = curveChart(response, ftp.current, rangeWords, words),
            bestEfforts =
                BEST_EFFORT_SECONDS.mapNotNull { seconds ->
                    rangePoints.firstOrNull { it.durationSec == seconds }?.let {
                        BestEffortUi(words.curveDuration(seconds), it.power.roundToInt(), words.day(it.date), it.rideId)
                    }
                },
            ftpHistory = ftpHistoryChart(response, ftp.current, rangeWords, words),
            rangeWords = rangeWords,
        )
    }

    private fun curveChart(
        response: MetricsResponse,
        ftp: Int?,
        rangeWords: String,
        words: Words,
    ): LineChartUi? {
        val best = response.powerCurve.allTime
        if (best.isEmpty()) return null
        val durations = DoubleArray(best.size) { best[it].durationSec.toDouble() }
        val inRange = response.powerCurve.range.associateBy { it.durationSec }
        val rangeWatts = DoubleArray(best.size) { inRange[best[it].durationSec]?.power ?: Double.NaN }
        val bestWatts = DoubleArray(best.size) { best[it].power }
        val top = bestWatts.max()
        val step = if (top < 600.0) 100.0 else 250.0
        val yMax = ChartMath.ceilTo(max(top, ftp?.toDouble() ?: 0.0), step)
        val yTicks =
            generateSequence(0.0) {
                it + step
            }.takeWhile { it <= yMax }.map { AxisTick(it, it.roundToInt().toString()) }.toList()
        val rest = durations.indices.minByOrNull { kotlin.math.abs(durations[it] - REST_DURATION) } ?: 0
        val spec =
            PlotSpec(
                xMin = CURVE_X_MIN,
                xMax = CURVE_X_MAX,
                xTicks = CURVE_TICKS.map { AxisTick(it.toDouble(), words.curveDuration(it)) },
                yMin = 0.0,
                yMax = yMax,
                yTicks = yTicks,
                lines =
                    listOf(
                        LineLayer(durations, rangeWatts, OverdriveColors.textPrimary, 2f),
                        LineLayer(durations, bestWatts, OverdriveColors.textTertiary, 1.5f),
                    ),
                snapXs = durations,
                restX = durations[rest],
                heightDp = 220f,
                xLogarithmic = true,
                refLines = listOfNotNull(ftp?.let { RefLine(it.toDouble(), "FTP $it") }),
                gutterDp = if (yMax >= 1000.0) 40f else 32f,
            )
        val readoutAt = { x: Double? ->
            val i = ChartMath.nearestIndex(durations, x ?: durations[rest])
            val inRangeWatts = rangeWatts[i]
            Readout(
                words.curveDuration(best[i].durationSec),
                listOf(
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        rangeWords,
                        if (inRangeWatts.isNaN()) UiFormat.EMPTY else "${inRangeWatts.roundToInt()} W",
                    ),
                    ReadoutEntry(
                        ReadoutKey.ThinLine,
                        OverdriveColors.textTertiary,
                        "Best ever",
                        "${bestWatts[i].roundToInt()} W",
                    ),
                ),
            )
        }
        return LineChartUi(
            heading = "Power curve",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "Power curve",
                    "Duration",
                    listOf(rangeWords, "Best ever"),
                    best.indices.map { i ->
                        ValuesRow(
                            "d${best[i].durationSec}",
                            words.curveDuration(best[i].durationSec),
                            listOf(
                                if (rangeWatts[i].isNaN()) UiFormat.EMPTY else "${rangeWatts[i].roundToInt()} W",
                                "${bestWatts[i].roundToInt()} W",
                            ),
                        )
                    },
                ),
            summary = "Power curve, $rangeWords",
        )
    }

    private fun ftpHistoryChart(
        response: MetricsResponse,
        ftp: Int?,
        rangeWords: String,
        words: Words,
    ): LineChartUi {
        val first = checkNotNull(PlanDates.epochDay(response.from))
        val last = checkNotNull(PlanDates.epochDay(response.to))
        val days = DoubleArray((last - first + 1).toInt()) { (first + it).toDouble() }
        // The value in force on each day: the last change on or before it; NaN before the first and where the FTP was cleared.
        val changes = response.ftp.history.map { checkNotNull(PlanDates.epochDay(it.date)) to it.ftp }
        val inForce =
            DoubleArray(days.size) { i ->
                val day = first + i
                val change = changes.lastOrNull { it.first <= day }
                change?.second?.toDouble() ?: Double.NaN
            }
        // Today's FTP is the profile's even where the history has not caught up yet.
        if (ftp != null && inForce.isNotEmpty() && inForce.last().isNaN()) inForce[inForce.size - 1] = ftp.toDouble()
        val estimates = response.ftp.estimates
        val estimateXs = DoubleArray(estimates.size) { checkNotNull(PlanDates.epochDay(estimates[it].date)).toDouble() }
        val estimateYs = DoubleArray(estimates.size) { estimates[it].eftp.toDouble() }
        val values = inForce.filter { !it.isNaN() } + estimateYs.toList()
        val axis =
            if (values.isEmpty()) {
                ChartMath.niceAxis(150.0, 250.0, 5)
            } else {
                ChartMath.niceAxis(values.min(), values.max(), 6)
            }
        val spec =
            PlotSpec(
                xMin = days.first(),
                xMax = max(days.last(), days.first() + 1),
                xTicks = DateAxis.ticks(rangeOf(response), response.from, response.to, words),
                yMin = axis.min,
                yMax = axis.max,
                yTicks = axis.ticks.map { AxisTick(it, it.roundToInt().toString()) },
                lines = listOf(LineLayer(days, inForce, OverdriveColors.textPrimary, 2f, stepped = true)),
                dots = listOf(DotLayer(estimateXs, estimateYs, OverdriveColors.textSecondary)),
                snapXs = days,
                restX = days.last(),
                heightDp = 150f,
            )
        val readoutAt = { x: Double? ->
            val day = (x ?: days.last()).toLong()
            val i = ChartMath.nearestIndex(days, day.toDouble())
            val estimate = estimates.lastOrNull { checkNotNull(PlanDates.epochDay(it.date)) <= day }
            Readout(
                words.day(PlanCalendar.ofEpochDay(day)),
                listOfNotNull(
                    ReadoutEntry(
                        ReadoutKey.Line,
                        OverdriveColors.textPrimary,
                        "FTP",
                        if (inForce[i].isNaN()) UiFormat.EMPTY else "${inForce[i].roundToInt()} W",
                    ),
                    estimate?.let {
                        ReadoutEntry(
                            ReadoutKey.Dot,
                            OverdriveColors.textSecondary,
                            "Estimated",
                            "${it.eftp} W",
                        )
                    },
                ),
            )
        }
        return LineChartUi(
            heading = "FTP history",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "FTP history",
                    "Day",
                    listOf("FTP", "Estimated"),
                    days.indices.reversed().map { i ->
                        val date = PlanCalendar.ofEpochDay(days[i].toLong())
                        val estimate = estimates.lastOrNull { it.date <= date }
                        ValuesRow(
                            date,
                            words.day(date),
                            listOf(
                                if (inForce[i].isNaN()) UiFormat.EMPTY else "${inForce[i].roundToInt()} W",
                                estimate?.let { "${it.eftp} W" } ?: UiFormat.EMPTY,
                            ),
                        )
                    },
                ),
            summary = "FTP history, $rangeWords",
        )
    }

    private fun rangeOf(response: MetricsResponse): MetricsRange {
        val days = checkNotNull(PlanDates.epochDay(response.to)) - checkNotNull(PlanDates.epochDay(response.from)) + 1
        return when {
            days <= MetricsRange.SIX_WEEKS.weeks * 7 + 6 -> MetricsRange.SIX_WEEKS
            days <= MetricsRange.SIX_MONTHS.weeks * 7 + 6 -> MetricsRange.SIX_MONTHS
            else -> MetricsRange.ONE_YEAR
        }
    }
}
