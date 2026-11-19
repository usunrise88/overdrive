package app.overdrive.uikit.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.Zones
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.max
import kotlin.math.min

/**
 * What the ride chart draws (design/companion.md §6.8): the ride's power, heart rate and W′ balance at one time axis, and the
 * planned steps behind the power. Numbers only, built once per ride by [RideChartSpec.of]; compared by identity.
 *
 * Missing readings are NaN: a gap in the line, never a zero.
 */
@Immutable
class RideChartSpec private constructor(
    val seconds: DoubleArray,
    val power: DoubleArray,
    val heartRate: DoubleArray,
    val wBalance: DoubleArray,
    val plan: List<ProfileStep>?,
    val ftp: Int,
    val thresholdHr: Int?,
    val wPrimeKj: Double,
    val durationSeconds: Int,
    val powerMax: Double,
    val powerTicks: List<AxisTick>,
    val hrMin: Double,
    val hrMax: Double,
    val hrTicks: List<AxisTick>,
    val wMin: Double,
    val wTicks: List<AxisTick>,
    val xTicks: List<AxisTick>,
    /** The index of the lowest W′ balance, where the dot rests, or -1 without a W′ series. */
    val lowestIndex: Int,
) {
    val hasPower: Boolean get() = power.any { !it.isNaN() }
    val hasHeartRate: Boolean get() = heartRate.any { !it.isNaN() }
    val hasWBalance: Boolean get() = wBalance.any { !it.isNaN() }

    /** Where the selection rests: the lowest W′ balance when there is one, else the middle of the ride. */
    val restX: Double get() = if (lowestIndex >= 0) seconds[lowestIndex] else seconds[seconds.size / 2]

    companion object {
        const val POWER_HEIGHT_DP = 150f
        const val HEART_HEIGHT_DP = 88f
        const val W_HEIGHT_DP = 72f
        const val CAPTION_GAP_DP = 22f
        const val TOTAL_HEIGHT_DP =
            PlotFrame.TOP_DP + POWER_HEIGHT_DP + CAPTION_GAP_DP + HEART_HEIGHT_DP + CAPTION_GAP_DP + W_HEIGHT_DP +
                PlotFrame.AXIS_BAND_DP

        /**
         * @param seconds the time of each point, ascending, in seconds from the start (10 s steps).
         * @param power watts, NaN where there is none; [heartRate] the same in bpm; [wBalance] kJ, NaN where unknown.
         * @param plan the steps of the workout the ride followed, drawn behind the power, or null.
         */
        fun of(
            seconds: DoubleArray,
            power: DoubleArray,
            heartRate: DoubleArray,
            wBalance: DoubleArray,
            plan: List<ProfileStep>?,
            ftp: Int,
            thresholdHr: Int?,
            wPrimeKj: Double,
            durationSeconds: Int,
        ): RideChartSpec {
            require(seconds.isNotEmpty()) { "A ride chart has points" }
            require(seconds.size == power.size && seconds.size == heartRate.size && seconds.size == wBalance.size)
            val peak = power.filter { !it.isNaN() }.maxOrNull() ?: 0.0
            val powerMax = ChartMath.ceilTo(max(1.5 * ftp, peak), 50.0)
            val powerTicks =
                generateSequence(0.0) {
                    it + 100.0
                }.takeWhile { it <= powerMax }.map { AxisTick(it, UiFormat.count(it.toInt())) }.toList()
            val hrValues = heartRate.filter { !it.isNaN() }
            val hrMin = min(80.0, ChartMath.floorTo(hrValues.minOrNull() ?: 80.0, 10.0))
            val hrMax = max(190.0, ChartMath.ceilTo(hrValues.maxOrNull() ?: 190.0, 10.0))
            val hrTicks =
                listOf(
                    100.0,
                    140.0,
                    180.0,
                ).filter { it in hrMin..hrMax }.map { AxisTick(it, UiFormat.count(it.toInt())) }
            val lowest = wBalance.indices.filter { !wBalance[it].isNaN() }.minByOrNull { wBalance[it] } ?: -1
            val wMin = min(0.0, if (lowest >= 0) wBalance[lowest] else 0.0)
            val wTicks = listOf(0.0, wPrimeKj / 2, wPrimeKj).map { AxisTick(it, kilojoules(it)) }
            val minutes = (durationSeconds / SECONDS_PER_MINUTE).coerceAtLeast(1)
            val step =
                if (minutes <= 40) {
                    10
                } else if (minutes <= 100) {
                    20
                } else {
                    30
                }
            val xTicks =
                generateSequence(
                    0,
                ) { it + step }.takeWhile { it * SECONDS_PER_MINUTE <= durationSeconds }.toList().let { marks ->
                    marks.mapIndexed { i, m ->
                        AxisTick(
                            m * SECONDS_PER_MINUTE.toDouble(),
                            if (i == marks.lastIndex &&
                                marks.size > 1
                            ) {
                                "$m min"
                            } else {
                                "$m"
                            },
                        )
                    }
                }
            return RideChartSpec(
                seconds,
                power,
                heartRate,
                wBalance,
                plan,
                ftp,
                thresholdHr,
                wPrimeKj,
                durationSeconds,
                powerMax,
                powerTicks,
                hrMin,
                hrMax,
                hrTicks,
                wMin,
                wTicks,
                xTicks,
                lowest,
            )
        }

        private fun kilojoules(value: Double): String =
            if (value == Math.rint(value)) value.toInt().toString() else UiFormat.decimal(value, 1)

        private const val SECONDS_PER_MINUTE = 60
    }
}

/**
 * Three plots stacked with one time axis (design/companion.md §6.8): power over the planned steps, heart rate with its threshold,
 * W′ balance with its 10% wash. One card's worth of chart, one selection across all three, drawn in one canvas.
 *
 * The planned steps are drawn in their zone colours **pre-blended with surface 1 at 35%** (the ride screen's "past" look,
 * design/system.md §2), 1 dp gaps; free-ride steps in surface 3. A ride with no plan draws no background; a ride with no heart rate
 * or no W′ draws the plot's frame and one sentence in its place (the caller says why in the card; the plot stays empty here).
 */
@Composable
fun RideChart(
    spec: RideChartSpec,
    selection: ChartSelection,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // One lambda for as long as the data is the same: a new one at every recomposition would rebuild the paths at every snap.
    val build: CacheDrawScope.() -> DrawResult =
        remember(spec, selection, measurer) {
            {
                val layout = RideLayout(spec, size, this, measurer)
                onDrawWithContent {
                    with(layout) {
                        drawStatic()
                        drawSelection(selection.x)
                    }
                }
            }
        }
    CompositionLocalProvider(LocalViewConfiguration provides ScrubViewConfiguration(LocalViewConfiguration.current)) {
        Box(
            modifier
                .fillMaxWidth()
                .height(RideChartSpec.TOTAL_HEIGHT_DP.dp)
                .chartSemantics(description, spec.seconds, spec.restX, selection)
                .chartGestures(spec, haptics, snap = { px, size -> with(density) { snapSeconds(spec, px, size) } }) {
                    selection.select(it)
                }.drawWithCache(build),
        )
    }
}

private fun Density.snapSeconds(
    spec: RideChartSpec,
    px: Float,
    size: IntSize,
): Double? {
    val frame = PlotFrame.of(size, this, PlotSpec.DEFAULT_GUTTER_DP, xAxis = true)
    val scale = AxisScale(0.0, spec.durationSeconds.toDouble().coerceAtLeast(1.0), frame.left, frame.right)
    val index = ChartMath.nearestIndex(spec.seconds, scale.fromPx(px.coerceIn(frame.left, frame.right)))
    return if (index < 0) null else spec.seconds[index]
}

private class RideLayout(
    private val spec: RideChartSpec,
    private val size: Size,
    density: Density,
    measurer: TextMeasurer,
) {
    private val frame =
        PlotFrame.of(
            IntSize(size.width.toInt(), size.height.toInt()),
            density,
            PlotSpec.DEFAULT_GUTTER_DP,
            xAxis = true,
        )
    private val dp = { value: Float -> with(density) { value.dp.toPx() } }
    private val px1 = dp(1f)
    private val gap8 = dp(8f)
    private val gap4 = dp(4f)
    private val gap5 = dp(5f)

    private val powerTop = dp(PlotFrame.TOP_DP)
    private val powerBottom = powerTop + dp(RideChartSpec.POWER_HEIGHT_DP)
    private val heartTop = powerBottom + dp(RideChartSpec.CAPTION_GAP_DP)
    private val heartBottom = heartTop + dp(RideChartSpec.HEART_HEIGHT_DP)
    private val wTop = heartBottom + dp(RideChartSpec.CAPTION_GAP_DP)
    private val wBottom = wTop + dp(RideChartSpec.W_HEIGHT_DP)

    private val xScale = AxisScale(0.0, spec.durationSeconds.toDouble().coerceAtLeast(1.0), frame.left, frame.right)
    private val powerScale = AxisScale(0.0, spec.powerMax, powerBottom, powerTop)
    private val heartScale = AxisScale(spec.hrMin, spec.hrMax, heartBottom, heartTop)
    private val wScale = AxisScale(spec.wMin, spec.wPrimeKj, wBottom, wTop)

    private val powerLabels = spec.powerTicks.map { powerScale.toPx(it.value) to measurer.tick(it.label) }
    private val heartLabels = spec.hrTicks.map { heartScale.toPx(it.value) to measurer.tick(it.label) }
    private val wLabels = spec.wTicks.map { wScale.toPx(it.value) to measurer.tick(it.label) }
    private val xLabels = spec.xTicks.map { xScale.toPx(it.value) to measurer.tick(it.label) }
    private val ftpLabel = measurer.tick("FTP")
    private val thresholdLabel = measurer.tick("Threshold")
    private val heartCaption = measurer.tick("Heart rate, bpm")
    private val wCaption = measurer.tick("W′ balance, kJ")

    private val buckets = max(1, frame.width.toInt())
    private val powerPath = path(spec.power, powerScale)
    private val heartPath = path(spec.heartRate, heartScale)
    private val wPath = path(spec.wBalance, wScale)
    private val wWash: Path =
        Path().apply {
            addPath(wPath)
            val last = spec.seconds.indices.lastOrNull { !spec.wBalance[it].isNaN() }
            val first = spec.seconds.indices.firstOrNull { !spec.wBalance[it].isNaN() }
            if (first != null && last != null) {
                lineTo(xScale.toPx(spec.seconds[last]), wScale.toPx(0.0))
                lineTo(xScale.toPx(spec.seconds[first]), wScale.toPx(0.0))
                close()
            }
        }
    private val steps: List<Pair<Path, Color>> = if (spec.plan == null) emptyList() else planPieces(spec.plan, dp(1f))

    private fun path(
        ys: DoubleArray,
        scale: AxisScale,
    ): Path = linePath(spec.seconds, ys, ChartMath.decimate(ys, buckets), xScale::toPx, scale::toPx, stepped = false)

    /** The planned steps as filled pieces in one zone colour each (a ramp split at the zone bounds), blended with surface 1. */
    private fun planPieces(
        plan: List<ProfileStep>,
        gap: Float,
    ): List<Pair<Path, Color>> {
        val pieces = ArrayList<Pair<Path, Color>>()
        val ftp = spec.ftp.toDouble()
        for (step in plan) {
            val x0 = xScale.toPx(step.startSeconds.toDouble())
            val x1 = max(x0, xScale.toPx((step.startSeconds + step.durationSeconds).toDouble()) - gap)
            val from = step.from
            val to = step.to
            if (step.heartRate) {
                // A heart-rate step: a floating band, as in the workout profile.
                val top = powerScale.toPx(HEART_RATE_BAND_HIGH * ftp)
                val bottom = powerScale.toPx(HEART_RATE_BAND_LOW * ftp)
                pieces +=
                    band(x0, x1, top, bottom) to Zones.blend(OverdriveColors.surface1, OverdriveColors.borderStrong)
            } else if (from == null || to == null) {
                val top = powerScale.toPx(0.5 * ftp)
                pieces += rect(x0, x1, top) to Zones.blend(OverdriveColors.surface1, OverdriveColors.surface3, 1f)
            } else if (from == to) {
                val top = powerScale.toPx(from * ftp)
                pieces +=
                    rect(x0, x1, top) to
                    Zones.blend(OverdriveColors.surface1, Zones.color(Zones.ofFraction(from.toDouble())))
            } else {
                pieces += rampPieces(step, x0, x1, ftp)
            }
        }
        return pieces
    }

    private fun band(
        x0: Float,
        x1: Float,
        top: Float,
        bottom: Float,
    ): Path =
        Path().apply {
            moveTo(x0, bottom)
            lineTo(x0, top)
            lineTo(x1, top)
            lineTo(x1, bottom)
            close()
        }

    private fun rect(
        x0: Float,
        x1: Float,
        top: Float,
    ): Path =
        Path().apply {
            moveTo(x0, powerBottom)
            lineTo(x0, top)
            lineTo(x1, top)
            lineTo(x1, powerBottom)
            close()
        }

    private fun rampPieces(
        step: ProfileStep,
        x0: Float,
        x1: Float,
        ftp: Double,
    ): List<Pair<Path, Color>> {
        val from = step.from!!.toDouble()
        val to = step.to!!.toDouble()
        val lo = min(from, to)
        val hi = max(from, to)
        val cuts =
            (
                listOf(lo) + Zones.upperBoundFractions.map { it + 0.0005 }.filter { it > lo && it < hi } +
                    listOf(hi)
            ).distinct()

        fun xAt(fraction: Double): Float = x0 + ((fraction - from) / (to - from)).toFloat() * (x1 - x0)
        return cuts.zipWithNext().map { (a, b) ->
            val path =
                Path().apply {
                    moveTo(xAt(a), powerBottom)
                    lineTo(xAt(a), powerScale.toPx(a * ftp))
                    lineTo(xAt(b), powerScale.toPx(b * ftp))
                    lineTo(xAt(b), powerBottom)
                    close()
                }
            path to Zones.blend(OverdriveColors.surface1, Zones.color(Zones.ofFraction((a + b) / 2)))
        }
    }

    private fun DrawScope.grid(
        top: Float,
        bottom: Float,
        labels: List<Pair<Float, TextLayoutResult>>,
    ) {
        for ((y, label) in labels) {
            val isBase = kotlin.math.abs(y - bottom) < 0.5f
            hairline(
                Offset(frame.left, y),
                Offset(frame.right, y),
                if (isBase) OverdriveColors.borderStrong else OverdriveColors.border,
                px1,
            )
            drawLabel(
                label,
                OverdriveColors.textTertiary,
                Offset(
                    frame.left - gap8 - label.size.width,
                    y - label.size.height / 2f,
                ),
            )
        }
        if (labels.none { kotlin.math.abs(it.first - bottom) < 0.5f }) {
            hairline(Offset(frame.left, bottom), Offset(frame.right, bottom), OverdriveColors.borderStrong, px1)
        }
    }

    fun DrawScope.drawStatic() {
        for ((piece, colour) in steps) drawPath(piece, colour)
        grid(powerTop, powerBottom, powerLabels)
        val ftpY = powerScale.toPx(spec.ftp.toDouble())
        hairline(Offset(frame.left, ftpY), Offset(frame.right, ftpY), OverdriveColors.borderStrong, px1)
        drawLabel(
            ftpLabel,
            OverdriveColors.textTertiary,
            Offset(
                frame.right - ftpLabel.size.width,
                ftpY - gap5 - ftpLabel.size.height,
            ),
        )
        if (spec.hasPower) strokeLine(powerPath, OverdriveColors.textPrimary, dp(1.5f))

        drawLabel(
            heartCaption,
            OverdriveColors.textTertiary,
            Offset(
                frame.left,
                heartTop - dp(RideChartSpec.CAPTION_GAP_DP) + gap4,
            ),
        )
        grid(heartTop, heartBottom, heartLabels)
        spec.thresholdHr?.let {
            val y = heartScale.toPx(it.toDouble())
            hairline(Offset(frame.left, y), Offset(frame.right, y), OverdriveColors.borderStrong, px1)
            drawLabel(
                thresholdLabel,
                OverdriveColors.textTertiary,
                Offset(
                    frame.right - thresholdLabel.size.width,
                    y - gap5 - thresholdLabel.size.height,
                ),
            )
        }
        if (spec.hasHeartRate) strokeLine(heartPath, OverdriveColors.textSecondary, dp(1.5f))

        drawLabel(
            wCaption,
            OverdriveColors.textTertiary,
            Offset(
                frame.left,
                wTop - dp(RideChartSpec.CAPTION_GAP_DP) + gap4,
            ),
        )
        grid(wTop, wBottom, wLabels)
        if (spec.hasWBalance) {
            drawPath(wWash, OverdriveColors.textPrimary.copy(alpha = WASH_ALPHA))
            strokeLine(wPath, OverdriveColors.textPrimary, dp(2f))
        }

        for ((x, label) in xLabels) {
            val left = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawLabel(label, OverdriveColors.textTertiary, Offset(left, wBottom + gap4))
        }
    }

    /** At rest the lowest W′ point carries its dot; with a selection a crosshair runs through the three plots and each carries its dot. */
    fun DrawScope.drawSelection(selected: Double?) {
        val away = selected != null && selected != spec.restX
        val x = selected ?: spec.restX
        val px = xScale.toPx(x)
        if (away) {
            for ((top, bottom) in listOf(powerTop to powerBottom, heartTop to heartBottom, wTop to wBottom)) {
                hairline(Offset(px, top), Offset(px, bottom), OverdriveColors.textSecondary, px1)
            }
        }
        val i = ChartMath.nearestIndex(spec.seconds, x)
        if (i < 0) return
        if (away) {
            if (!spec.power[i].isNaN()) {
                ringedDot(
                    Offset(px, powerScale.toPx(spec.power[i])),
                    OverdriveColors.textPrimary,
                    8f,
                    2f,
                )
            }
            if (!spec.heartRate[i].isNaN()) {
                ringedDot(
                    Offset(px, heartScale.toPx(spec.heartRate[i])),
                    OverdriveColors.textSecondary,
                    8f,
                    2f,
                )
            }
        }
        if (!spec.wBalance[i].isNaN() && (away || i == spec.lowestIndex)) {
            ringedDot(Offset(px, wScale.toPx(spec.wBalance[i])), OverdriveColors.textPrimary, 8f, 2f)
        }
    }

    private companion object {
        const val WASH_ALPHA = 0.10f
    }
}
