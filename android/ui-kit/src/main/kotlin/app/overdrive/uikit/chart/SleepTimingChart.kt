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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.abs

/**
 * What the sleep timing chart draws (design/companion.md §6.7): one floating bar per night from going to bed to waking, on a clock
 * axis. Times are **minutes from local midnight**, a bedtime before midnight negative (23:05 is −55), the way the service writes
 * them, so a range of bedtimes is a range and not two clusters either side of midnight.
 *
 * @property days the day number of each night (the morning it ends), ascending; the x the selection shares with the other charts.
 * @property bedMinutes the bedtime of each night, or NaN for a night with no reading (nothing is drawn for it).
 * @property wakeMinutes the waking time of each night, or NaN.
 * @property topMinutes the clock time at the top of the axis (21:00 is −180) and [bottomMinutes] the one at the bottom (09:00 is 540).
 * @property bedBand the usual bedtime range and [wakeBand] the usual waking range, as two horizontal bands, or null.
 */
@Immutable
class SleepSpec(
    val days: DoubleArray,
    val bedMinutes: DoubleArray,
    val wakeMinutes: DoubleArray,
    val xMin: Double,
    val xMax: Double,
    val xTicks: List<AxisTick>,
    val topMinutes: Double,
    val bottomMinutes: Double,
    val yTicks: List<AxisTick>,
    val restX: Double,
    val heightDp: Float,
    val bedBand: Pair<Double, Double>? = null,
    val wakeBand: Pair<Double, Double>? = null,
    val gutterDp: Float = 40f,
) {
    init {
        require(
            days.size == bedMinutes.size && days.size == wakeMinutes.size,
        ) { "A night has a bedtime and a waking time" }
        require(xMax > xMin && bottomMinutes > topMinutes) { "A chart has a range on both axes" }
    }

    /** The x a touch can select: the nights that have a reading. */
    val snapXs: DoubleArray =
        days
            .filterIndexed {
                i,
                _,
                ->
                !bedMinutes[i].isNaN() && !wakeMinutes[i].isNaN()
            }.toDoubleArray()
}

/**
 * The sleep timing chart (design/companion.md §6.7): bars from bedtime to waking on a clock axis (21:00 at the top), two surface-3
 * bands for the usual bedtime and the usual waking time, bars `min(8, slot − 3)` dp wide and fully rounded in text 3, the selected
 * night in text 1. It shares [selection] with the other Wellness charts and is built and drawn like [PlotChart]: bars and text once
 * per size, the selection in the draw phase, so scrubbing redraws and never recomposes.
 */
@Composable
fun SleepTimingChart(
    spec: SleepSpec,
    selection: ChartSelection,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val build: CacheDrawScope.() -> DrawResult =
        remember(spec, selection, measurer) {
            {
                val layout = SleepLayout(spec, size, this, measurer)
                onDrawWithContent { with(layout) { drawAll(selection.x) } }
            }
        }
    CompositionLocalProvider(LocalViewConfiguration provides ScrubViewConfiguration(LocalViewConfiguration.current)) {
        Box(
            modifier
                .fillMaxWidth()
                .height(spec.heightDp.dp)
                .chartSemantics(description, spec.snapXs, spec.restX, selection)
                .chartGestures(spec, haptics, snap = { px, size -> with(density) { snapNight(spec, px, size) } }) {
                    selection.select(it)
                }.drawWithCache(build),
        )
    }
}

private fun Density.snapNight(
    spec: SleepSpec,
    px: Float,
    size: IntSize,
): Double? {
    if (spec.snapXs.isEmpty()) return null
    val frame = PlotFrame.of(size, this, spec.gutterDp, spec.xTicks.isNotEmpty())
    val scale = AxisScale(spec.xMin, spec.xMax, frame.left, frame.right)
    val index = ChartMath.nearestIndex(spec.snapXs, scale.fromPx(px.coerceIn(frame.left, frame.right)))
    return if (index < 0) null else spec.snapXs[index]
}

private class SleepLayout(
    private val spec: SleepSpec,
    private val size: Size,
    density: Density,
    measurer: TextMeasurer,
) {
    private val frame =
        PlotFrame.of(
            IntSize(size.width.toInt(), size.height.toInt()),
            density,
            spec.gutterDp,
            spec.xTicks.isNotEmpty(),
        )
    private val px1 = with(density) { 1.dp.toPx() }
    private val gap8 = with(density) { 8.dp.toPx() }
    private val gap4 = with(density) { 4.dp.toPx() }

    // Bars are inset by half a bar so the first and the last night sit inside the plot, like the other charts' dots.
    private val slot = frame.width / (spec.xMax - spec.xMin + 1.0).toFloat()
    private val barWidth =
        ChartMath.columnWidth(
            slot,
            with(density) {
                8.dp.toPx()
            },
            with(density) { 3.dp.toPx() },
            with(density) { 1.5.dp.toPx() },
        )
    private val xScale = AxisScale(spec.xMin, spec.xMax, frame.left + barWidth / 2f, frame.right - barWidth / 2f)
    private val yScale = AxisScale(spec.topMinutes, spec.bottomMinutes, frame.top, frame.bottom)

    private val yLabels: List<Pair<Float, TextLayoutResult>> =
        spec.yTicks.map {
            yScale.toPx(it.value) to
                measurer.tick(it.label)
        }
    private val xLabels: List<Pair<Float, TextLayoutResult>> =
        spec.xTicks.map {
            xScale.toPx(it.value) to
                measurer.tick(it.label)
        }

    fun DrawScope.drawAll(selected: Double?) {
        spec.bedBand?.let { band(it, OverdriveColors.surface3) }
        spec.wakeBand?.let { band(it, OverdriveColors.surface3) }
        for ((y, label) in yLabels) {
            val isBase = abs(y - frame.bottom) < 0.5f
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
        val at = selected ?: spec.restX
        for (i in spec.days.indices) {
            if (spec.days[i] == at) continue
            bar(i, OverdriveColors.textTertiary)
        }
        spec.days.indexOfFirst { it == at }.takeIf { it >= 0 }?.let { bar(it, OverdriveColors.textPrimary) }
        for ((x, label) in xLabels) {
            val left = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawLabel(label, OverdriveColors.textTertiary, Offset(left, frame.bottom + gap4))
        }
    }

    private fun DrawScope.band(
        range: Pair<Double, Double>,
        color: Color,
    ) {
        val top = yScale.toPx(range.first.coerceIn(spec.topMinutes, spec.bottomMinutes))
        val bottom = yScale.toPx(range.second.coerceIn(spec.topMinutes, spec.bottomMinutes))
        drawRect(color, Offset(frame.left, minOf(top, bottom)), Size(frame.width, abs(bottom - top)))
    }

    private fun DrawScope.bar(
        index: Int,
        color: Color,
    ) {
        val bed = spec.bedMinutes[index]
        val wake = spec.wakeMinutes[index]
        if (bed.isNaN() || wake.isNaN()) return
        val x = xScale.toPx(spec.days[index])
        val top = yScale.toPx(bed.coerceIn(spec.topMinutes, spec.bottomMinutes))
        val bottom = yScale.toPx(wake.coerceIn(spec.topMinutes, spec.bottomMinutes))
        if (bottom <= top) return
        drawRoundRect(
            color,
            Offset(x - barWidth / 2f, top),
            Size(barWidth, bottom - top),
            CornerRadius(barWidth / 2f),
        )
    }
}
