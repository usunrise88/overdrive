package app.overdrive.uikit.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The seven days of one week (design/companion.md §6.12): Monday to Sunday in [labels], their loads in [values]. [today] is the index of
 * today in this week, or -1 when the week is not the one in progress; the days after it are drawn at half strength.
 */
@Immutable
class DayStripSpec(
    val labels: List<String>,
    val values: DoubleArray,
    val today: Int,
) {
    init {
        require(labels.size == DAYS && values.size == DAYS) { "A week has seven days" }
    }

    companion object {
        const val DAYS = 7
    }
}

/**
 * Seven small columns under the Effort chart: 64 dp tall (46 plot and 18 of labels), 20 dp wide columns in text 2 scaled to the week's
 * largest day, no y axis and no numbers (the rows carry them), a `borderStrong` baseline and the day names in caption text 3, today's in
 * text 1 at weight 600. One node for TalkBack, read from [description]. Built once per spec: no animation.
 */
@Composable
fun DayStrip(
    spec: DayStripSpec,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .fillMaxWidth()
            .height(HEIGHT_DP.dp)
            .semantics { contentDescription = description }
            .drawWithCache {
                val slot = size.width / DayStripSpec.DAYS
                val plotHeight = PLOT_DP.dp.toPx()
                val width = COLUMN_DP.dp.toPx()
                val radius = minOf(4.dp.toPx(), width / 2f)
                val largest = spec.values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
                val dimmed: (Int) -> Boolean = { spec.today >= 0 && it > spec.today }
                val labels =
                    spec.labels.mapIndexed { i, text ->
                        val today = i == spec.today
                        val base = TickStyle
                        measurer.measure(
                            text,
                            style =
                                base.copy(
                                    color = if (today) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
                                    fontWeight = if (today) FontWeight.SemiBold else base.fontWeight,
                                ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                onDrawWithContent {
                    drawLine(
                        OverdriveColors.borderStrong,
                        Offset(0f, plotHeight),
                        Offset(size.width, plotHeight),
                        strokeWidth = 1.dp.toPx(),
                    )
                    for (i in 0 until DayStripSpec.DAYS) {
                        val centre = slot * (i + 0.5f)
                        val alpha = if (dimmed(i)) DIM else 1f
                        if (spec.values[i] > 0.0) {
                            val top = plotHeight - (spec.values[i] / largest).toFloat() * (plotHeight - 2.dp.toPx())
                            val path =
                                Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            Rect(
                                                centre - width / 2f,
                                                top.coerceAtMost(plotHeight - 1.dp.toPx()),
                                                centre + width / 2f,
                                                plotHeight,
                                            ),
                                            topLeft = CornerRadius(radius),
                                            topRight = CornerRadius(radius),
                                            bottomRight = CornerRadius.Zero,
                                            bottomLeft = CornerRadius.Zero,
                                        ),
                                    )
                                }
                            drawPath(path, OverdriveColors.textSecondary.copy(alpha = alpha))
                        }
                        val label = labels[i]
                        drawText(
                            label,
                            Color.Unspecified,
                            Offset(
                                centre - label.size.width / 2f,
                                plotHeight + 3.dp.toPx(),
                            ),
                            alpha,
                        )
                    }
                }
            },
    )
}

private const val PLOT_DP = 46
private const val HEIGHT_DP = 64
private const val COLUMN_DP = 20
private const val DIM = 0.5f
