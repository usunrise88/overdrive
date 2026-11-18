package app.overdrive.uikit.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.Zones
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import kotlin.math.max

/**
 * Time in power zones (design/system.md §8.3): seven 28 dp rows with "Z1", the zone name, a bar and the time.
 * Bars are 12 dp thick, zone-coloured, start at one shared left edge, are square at the start and 4 dp rounded at
 * the end, and are proportional to time with the longest filling the track; a zone with no time keeps a 1 dp stub.
 *
 * @param secondsPerZone seconds in Z1 to Z7, exactly seven values.
 */
@Composable
fun ZoneTimeBars(
    secondsPerZone: List<Int>,
    modifier: Modifier = Modifier,
) {
    require(secondsPerZone.size == Zones.ZONE_COUNT) { "Seven zones expected, got ${secondsPerZone.size}" }
    val longest = max(secondsPerZone.max(), 1)
    Column(modifier.fillMaxWidth()) {
        secondsPerZone.forEachIndexed { index, seconds ->
            val zone = index + 1
            Row(
                Modifier.fillMaxWidth().height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdText(
                    "Z$zone",
                    Modifier.width(24.dp),
                    style = OdType.labelNum,
                    color = OverdriveColors.textSecondary,
                    maxLines = 1,
                )
                OdText(
                    Zones.name(zone),
                    Modifier.width(112.dp),
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                    maxLines = 1,
                )
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    val width = max(size.width * seconds / longest, 1.dp.toPx())
                    val radius = CornerRadius(OverdriveDimens.radiusSm.toPx())
                    val path =
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    Rect(0f, 0f, width, size.height),
                                    topLeft = CornerRadius.Zero,
                                    topRight = radius,
                                    bottomRight = radius,
                                    bottomLeft = CornerRadius.Zero,
                                ),
                            )
                        }
                    drawPath(path, Zones.color(zone))
                }
                OdText(
                    UiFormat.elapsed(seconds),
                    Modifier.width(56.dp),
                    style = OdType.labelNum,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
