package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/**
 * A read-only state chip (design/system.md §7.2): 28 dp high, full radius, surface 2 with secondary text.
 * [on] is the inverted chip for state that needs attention ("Paused"): near-white fill, dark text.
 * Chips are not buttons.
 */
@Composable
fun OdChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dot: Boolean = false,
    on: Boolean = false,
) {
    val fill = if (on) OverdriveColors.textPrimary else OverdriveColors.surface2
    val content = if (on) OverdriveColors.textOnPrimary else OverdriveColors.textSecondary
    Row(
        modifier
            .height(OverdriveDimens.sizeChipHeight)
            .background(fill, CircleShape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) OdIcon(icon, size = InlineIconSize, tint = content)
        if (dot) Box(Modifier.size(6.dp).background(content, CircleShape))
        OdText(text, style = OdType.label, color = content, maxLines = 1, softWrap = false)
    }
}

/**
 * The zone chip (design/system.md §7.2): 20 dp high, at least 28 dp wide, radius 4, zone fill, "Z4" in the
 * caption style with tabular figures and the on-primary colour. A null [zone] is the neutral "—" used for a
 * free-ride step in a table.
 */
@Composable
fun ZoneChip(
    zone: Int?,
    modifier: Modifier = Modifier,
) {
    val fill = if (zone == null) OverdriveColors.surface3 else Zones.color(zone)
    val content = if (zone == null) OverdriveColors.textSecondary else OverdriveColors.textOnPrimary
    Box(
        modifier
            .height(20.dp)
            .defaultMinSize(minWidth = 28.dp)
            .background(fill, RoundedCornerShape(OverdriveDimens.radiusSm))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        OdText(
            if (zone == null) UiFormat.EMPTY else "Z$zone",
            style = OdType.zoneTag,
            color = content,
            maxLines = 1,
            softWrap = false,
        )
    }
}
