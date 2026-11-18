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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
 * Weekly columns (design/companion.md §6.3): one column per week, width `min(20, slot - 2)` dp and at least 3 dp, a 4 dp
 * radius on top (less when thinner) and square at the baseline, a zero week draws nothing. Text-3 columns, the selected week in
 * text 1, a value label only on the selected column and on the highest.
 *
 * Selecting a day on a line chart that shares [selection] selects its week here; a touch on a column selects the week's last
 * day in the range, which the line charts above show. Built and drawn like [PlotChart]: paths once per size, the selection in the
 * draw phase.
 */
@Composable
fun ColumnChart(
    spec: ColumnSpec,
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
                val layout = ColumnLayout(spec, size, this, measurer)
                onDrawWithContent {
                    with(layout) {
                        drawStatic(selectedColumn(selection.x))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalViewConfiguration provides ScrubViewConfiguration(LocalViewConfiguration.current)) {
        Box(
            modifier
                .fillMaxWidth()
                .height(spec.heightDp.dp)
                .chartSemantics(description, spec.selectXs, spec.restX, selection)
                .chartGestures(spec, haptics, snap = { px, size -> with(density) { snapColumn(spec, px, size) } }) {
                    selection.select(it)
                }.drawWithCache(build),
        )
    }
}

private fun Density.frameOf(
    spec: ColumnSpec,
    size: IntSize,
) = PlotFrame.of(size, this, PlotSpec.DEFAULT_GUTTER_DP, xAxis = spec.xTicks.isNotEmpty())

private fun Density.snapColumn(
    spec: ColumnSpec,
    px: Float,
    size: IntSize,
): Double {
    val frame = frameOf(spec, size)
    val slot = frame.width / spec.values.size
    val index = ((px - frame.left) / slot).toInt().coerceIn(0, spec.values.size - 1)
    return spec.selectXs[index]
}

private class ColumnLayout(
    private val spec: ColumnSpec,
    private val size: Size,
    density: Density,
    measurer: TextMeasurer,
) {
    private val frame =
        PlotFrame.of(
            IntSize(size.width.toInt(), size.height.toInt()),
            density,
            PlotSpec.DEFAULT_GUTTER_DP,
            spec.xTicks.isNotEmpty(),
        )
    private val yScale = AxisScale(0.0, spec.yMax, frame.bottom, frame.top)
    private val slot = frame.width / spec.values.size
    private val px1 = with(density) { 1.dp.toPx() }
    private val gap8 = with(density) { 8.dp.toPx() }
    private val gap4 = with(density) { 4.dp.toPx() }
    private val columnWidth =
        ChartMath.columnWidth(
            slot,
            with(density) {
                20.dp.toPx()
            },
            with(density) { 2.dp.toPx() },
            with(density) { 3.dp.toPx() },
        )
    private val topRadius = minOf(with(density) { 4.dp.toPx() }, columnWidth / 2f)

    private val yLabels: List<Pair<Float, TextLayoutResult>> =
        spec.yTicks.map { yScale.toPx(it.value) to measurer.tick(it.label) }
    private val xLabels: List<Pair<Float, TextLayoutResult>> =
        spec.xTicks.map { (frame.left + (it.value + 0.5) * slot).toFloat() to measurer.tick(it.label) }
    private val highest: Int = spec.values.indices.maxByOrNull { spec.values[it] } ?: 0
    private val valueLabels: Map<Int, TextLayoutResult> =
        spec.values.indices.associateWith { measurer.tick(spec.valueLabel(spec.values[it])) }

    /** The paths of the columns that are not selected, made once. */
    private val columns: List<Path?> =
        spec.values.indices.map { i ->
            val value = spec.values[i]
            if (value <= 0.0) {
                null
            } else {
                val left = frame.left + i * slot + (slot - columnWidth) / 2f
                val top = yScale.toPx(value.coerceAtMost(spec.yMax)).coerceAtMost(frame.bottom - px1)
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(left, top, left + columnWidth, frame.bottom),
                            topLeft = CornerRadius(topRadius),
                            topRight = CornerRadius(topRadius),
                            bottomRight = CornerRadius.Zero,
                            bottomLeft = CornerRadius.Zero,
                        ),
                    )
                }
            }
        }

    fun selectedColumn(x: Double?): Int = spec.columnOf(x ?: spec.restX)

    fun DrawScope.drawStatic(selected: Int) {
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
        for ((i, path) in columns.withIndex()) {
            if (path != null && i != selected) drawPath(path, OverdriveColors.textTertiary)
        }
        columns.getOrNull(selected)?.let { drawPath(it, OverdriveColors.textPrimary) }
        for (i in setOfNotNull(selected, highest.takeIf { spec.values[it] > 0.0 })) {
            val label = valueLabels.getValue(i)
            val centre = frame.left + (i + 0.5f) * slot
            val top = yScale.toPx(spec.values[i].coerceAtMost(spec.yMax)) - gap4 - label.size.height
            val left = (centre - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawLabel(
                label,
                if (i ==
                    selected
                ) {
                    OverdriveColors.textSecondary
                } else {
                    OverdriveColors.textTertiary
                },
                Offset(left, top),
            )
        }
        for ((x, label) in xLabels) {
            val left = (x - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
            drawLabel(label, OverdriveColors.textTertiary, Offset(left, frame.bottom + gap4))
        }
    }
}
