package app.overdrive.uikit.chart

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OdType
import app.overdrive.uikit.tokens.OverdriveColors

/** The plot rectangle inside a chart canvas, in pixels (design/companion.md §6: gutter, 6 dp right, 10 dp top, 20 dp axis band). */
internal class PlotFrame(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        const val TOP_DP = 10f
        const val RIGHT_DP = 6f
        const val AXIS_BAND_DP = 20f

        fun of(
            size: IntSize,
            density: Density,
            gutterDp: Float,
            xAxis: Boolean,
        ): PlotFrame =
            with(density) {
                PlotFrame(
                    left = gutterDp.dp.toPx(),
                    right = size.width - RIGHT_DP.dp.toPx(),
                    top = TOP_DP.dp.toPx(),
                    bottom = size.height - if (xAxis) AXIS_BAND_DP.dp.toPx() else 0f,
                )
            }
    }
}

/** The long press of a scrub is 250 ms (design/companion.md §3.5), a little shorter than the system's. */
internal class ScrubViewConfiguration(
    private val base: ViewConfiguration,
) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = SCRUB_HOLD_MILLIS

    private companion object {
        const val SCRUB_HOLD_MILLIS = 250L
    }
}

/**
 * Touch for a chart: a tap selects the nearest x, a hold of 250 ms and a slide scrubs, snapping to [snap]'s answer for the
 * finger's x. A vertical scroll that starts without the hold is the page's and never the chart's. A haptic tick on every snap
 * while scrubbing, nothing else vibrates.
 *
 * @param snap the x to select for a finger at [Float] pixels, or null for none.
 */
@Composable
internal fun Modifier.chartGestures(
    key: Any,
    haptics: HapticFeedback,
    snap: (Float, IntSize) -> Double?,
    onSelect: (Double) -> Unit,
): Modifier {
    val select by rememberUpdatedState(onSelect)
    val snapper by rememberUpdatedState(snap)
    return this
        .pointerInput(key) {
            detectTapGestures { offset -> snapper(offset.x, size)?.let { select(it) } }
        }.pointerInput(key) {
            var last: Double? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    last = snapper(offset.x, size)
                    last?.let {
                        select(it)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onDragEnd = { last = null },
                onDragCancel = { last = null },
                onDrag = { change, _ ->
                    change.consume()
                    val next = snapper(change.position.x, size)
                    if (next != null && next != last) {
                        last = next
                        select(next)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
            )
        }
}

/**
 * What TalkBack says and does for a chart (design/companion.md §8): one focusable node whose description is the readout plus
 * a summary, and the two actions that move the selection along [snapXs]. Selection follows the focused action.
 */
internal fun Modifier.chartSemantics(
    description: String,
    snapXs: DoubleArray,
    restX: Double,
    selection: ChartSelection,
): Modifier =
    this.semantics {
        contentDescription = description
        customActions =
            listOf(
                CustomAccessibilityAction("Previous point") { move(snapXs, restX, selection, -1) },
                CustomAccessibilityAction("Next point") { move(snapXs, restX, selection, 1) },
            )
    }

private fun move(
    snapXs: DoubleArray,
    restX: Double,
    selection: ChartSelection,
    by: Int,
): Boolean {
    val current = ChartMath.nearestIndex(snapXs, selection.x ?: restX)
    val next = (current + by).coerceIn(0, snapXs.size - 1)
    if (next == current) return false
    selection.select(snapXs[next])
    return true
}

// region drawing

internal val TickStyle = OdType.captionNum

/** Measures [text] once, for a chart's cache. */
internal fun TextMeasurer.tick(text: String): TextLayoutResult =
    measure(text, style = TickStyle, maxLines = 1, softWrap = false)

internal fun DrawScope.hairline(
    from: Offset,
    to: Offset,
    color: Color,
    widthPx: Float,
) = drawLine(color, from, to, strokeWidth = widthPx)

/** A polyline through ([xs], [ys]) in pixels, one sub-path per run between NaN gaps; null when nothing is finite. */
internal fun linePath(
    xs: DoubleArray,
    ys: DoubleArray,
    indices: IntArray,
    xOf: (Double) -> Float,
    yOf: (Double) -> Float,
    stepped: Boolean,
): Path {
    val path = Path()
    var open = false
    var previousY = 0f
    for (i in indices) {
        val y = ys[i]
        if (y.isNaN()) {
            open = false
            continue
        }
        val px = xOf(xs[i])
        val py = yOf(y)
        if (!open) {
            path.moveTo(px, py)
            open = true
        } else if (stepped) {
            path.lineTo(px, previousY)
            path.lineTo(px, py)
        } else {
            path.lineTo(px, py)
        }
        previousY = py
    }
    return path
}

internal fun DrawScope.strokeLine(
    path: Path,
    color: Color,
    widthPx: Float,
) = drawPath(path, color, style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** A dot with its ring: [diameterDp] across, [ringDp] of the surface colour around it. */
internal fun DrawScope.ringedDot(
    centre: Offset,
    color: Color,
    diameterDp: Float,
    ringDp: Float,
    surface: Color = OverdriveColors.surface1,
) {
    val radius = diameterDp.dp.toPx() / 2f
    drawCircle(surface, radius + ringDp.dp.toPx(), centre)
    drawCircle(color, radius, centre)
}

internal fun DrawScope.drawLabel(
    layout: TextLayoutResult,
    color: Color,
    topLeft: Offset,
) = drawText(layout, color, topLeft)
