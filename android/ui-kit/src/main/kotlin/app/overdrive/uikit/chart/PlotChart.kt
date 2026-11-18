package app.overdrive.uikit.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.abs

/**
 * A line chart (design/companion.md §6.1, §6.2, §6.4, §6.5, §6.6): lines, dots, flat bands and reference lines on hairline
 * gridlines, a log or a linear x axis, and the shared selection.
 *
 * **Performance (design §9).** The paths, tick labels and band rectangles are built once per (spec, size) in `drawWithCache`.
 * The selection (the crosshair and the dots) is drawn in the draw phase from [selection], which is read there and nowhere in
 * composition, so scrubbing redraws and never recomposes or rebuilds a path.
 *
 * @param description what TalkBack says: the readout and a summary (design §8).
 */
@Composable
fun PlotChart(
    spec: PlotSpec,
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
                val layout = PlotLayout(spec, size, this, measurer)
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
                .height(spec.heightDp.dp)
                .chartSemantics(description, spec.snapXs, spec.restX, selection)
                .chartGestures(spec, haptics, snap = {
                    px,
                    size,
                    ->
                    with(density) { snapX(spec, px, size) }
                }) { selection.select(it) }
                .drawWithCache(build),
        )
    }
}

private fun Density.scaleOf(
    spec: PlotSpec,
    frame: PlotFrame,
) = AxisScale(spec.xMin, spec.xMax, frame.left, frame.right, spec.xLogarithmic)

private fun Density.snapX(
    spec: PlotSpec,
    px: Float,
    size: IntSize,
): Double? {
    val frame = PlotFrame.of(size, this, spec.gutterDp, spec.xTicks.isNotEmpty())
    val scale = scaleOf(spec, frame)
    val x = scale.fromPx(px.coerceIn(frame.left, frame.right))
    val index = ChartMath.nearestIndex(spec.snapXs, x)
    return if (index < 0) null else spec.snapXs[index]
}

/** What [PlotChart] draws at one size: the scales, the paths and the text, made once. */
private class PlotLayout(
    private val spec: PlotSpec,
    size: Size,
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
    private val xScale = density.scaleOf(spec, frame)
    private val yScale = AxisScale(spec.yMin, spec.yMax, frame.bottom, frame.top)
    private val px1 = with(density) { 1.dp.toPx() }
    private val gap8 = with(density) { 8.dp.toPx() }
    private val gap5 = with(density) { 5.dp.toPx() }
    private val gap4 = with(density) { 4.dp.toPx() }

    private val yLabels: List<Pair<Float, TextLayoutResult>> =
        spec.yTicks.map { yScale.toPx(it.value) to measurer.tick(it.label) }
    private val xLabels: List<Pair<Float, TextLayoutResult>> =
        spec.xTicks.map { xScale.toPx(it.value) to measurer.tick(it.label) }
    private val bandLabels: List<TextLayoutResult?> = spec.bands.map { band -> band.label?.let { measurer.tick(it) } }
    private val refLabels: List<TextLayoutResult> = spec.refLines.map { measurer.tick(it.label) }

    private val paths: List<Path> =
        spec.lines.map { line ->
            val indices = ChartMath.decimate(line.ys, buckets = maxOf(1, frame.width.toInt()))
            linePath(line.xs, line.ys, indices, xScale::toPx, yScale::toPx, line.stepped)
        }

    private val strokePx = spec.lines.map { with(density) { it.widthDp.dp.toPx() } }
    private val smallDot = with(density) { 6.dp.toPx() / 2f }
    private val ring = with(density) { 1.5.dp.toPx() }
    private val dotDiameter = 8f

    fun DrawScope.drawStatic() {
        for ((index, band) in spec.bands.withIndex()) {
            val top = yScale.toPx(maxOf(band.from, band.to).coerceIn(spec.yMin, spec.yMax))
            val bottom = yScale.toPx(minOf(band.from, band.to).coerceIn(spec.yMin, spec.yMax))
            drawRect(band.color, Offset(frame.left, top), Size(frame.width, bottom - top))
            val label = bandLabels[index]
            if (label != null) {
                drawLabel(
                    label,
                    OverdriveColors.textTertiary,
                    Offset(frame.left + gap8, bottom - label.size.height - gap4 / 2f),
                )
            }
        }
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
        if (spec.yMin < 0.0 && spec.yMax > 0.0) {
            val zero = yScale.toPx(0.0)
            hairline(Offset(frame.left, zero), Offset(frame.right, zero), OverdriveColors.borderStrong, px1)
        }
        for ((index, line) in spec.refLines.withIndex()) {
            val y = yScale.toPx(line.y)
            hairline(Offset(frame.left, y), Offset(frame.right, y), OverdriveColors.borderStrong, px1)
            val label = refLabels[index]
            drawLabel(
                label,
                OverdriveColors.textTertiary,
                Offset(
                    frame.right - label.size.width,
                    y - gap5 - label.size.height,
                ),
            )
        }
        // The second series first, so the main one is on top.
        for (index in spec.lines.indices.reversed()) strokeLine(paths[index], spec.lines[index].color, strokePx[index])
        for (layer in spec.dots) {
            for (i in layer.xs.indices) {
                val y = layer.ys[i]
                if (y.isNaN()) continue
                val centre = Offset(xScale.toPx(layer.xs[i]), yScale.toPx(y))
                drawCircle(OverdriveColors.surface1, smallDot + ring, centre)
                drawCircle(layer.color, smallDot, centre)
            }
        }
        for ((x, label) in xLabels) {
            val left = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawLabel(label, OverdriveColors.textTertiary, Offset(left, frame.bottom + gap4))
        }
    }

    /** The crosshair (only away from rest) and the 8 dp dots of the marked series at the selected x, or at rest the latest. */
    fun DrawScope.drawSelection(selected: Double?) {
        val x = selected ?: spec.restX
        val px = xScale.toPx(x)
        if (selected != null && selected != spec.restX) {
            hairline(Offset(px, frame.top), Offset(px, frame.bottom), OverdriveColors.textSecondary, px1)
        }
        for (line in spec.lines) {
            if (!line.marked) continue
            val i = indexOf(line.xs, x)
            if (i < 0 || line.ys[i].isNaN()) continue
            ringedDot(Offset(px, yScale.toPx(line.ys[i])), line.color, dotDiameter, 2f)
        }
        for (layer in spec.dots) {
            if (!layer.marked) continue
            val i = indexOf(layer.xs, x)
            if (i < 0 || layer.ys[i].isNaN()) continue
            ringedDot(Offset(px, yScale.toPx(layer.ys[i])), layer.color, dotDiameter, 2f)
        }
    }

    private fun indexOf(
        xs: DoubleArray,
        x: Double,
    ): Int {
        val i = ChartMath.nearestIndex(xs, x)
        return if (i >= 0 && xs[i] == x) i else -1
    }
}
