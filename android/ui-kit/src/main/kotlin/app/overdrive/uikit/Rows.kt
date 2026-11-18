package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

private val md = RoundedCornerShape(OverdriveDimens.radiusMd)

/**
 * A selection row (design/system.md §7.5): title in body-strong over a one-line subtitle in the label style,
 * secondary. The selected row is surface 2 with radius 8 and a check; selection lists have no dividers.
 */
@Composable
fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteractionSource()
    val pressed = source.pressed()
    val fill =
        when {
            pressed -> OverdriveColors.surface3
            selected -> OverdriveColors.surface2
            else -> Color.Transparent
        }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
            .background(fill, md)
            .plainClickable(source, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(title, style = OdType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OdText(subtitle, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) OdIcon(OverdriveIcons.Check)
    }
}

/** What a device row is doing (design/system.md §7.7). */
enum class DeviceStatus {
    /** Nothing paired: shows **Pair**. */
    NotPaired,

    /** Looking for the device: a spinner. */
    Searching,

    /** Linked and working: a check and "Connected". */
    Connected,

    /** The link dropped and is being retried: an alert in the subtitle and **Retry now**. */
    Lost,

    /** Linked, but the Click sends no presses: "No presses" and a notice below the row. */
    Locked,
}

/**
 * A device row (design/system.md §7.7): a 40 dp icon well, the role as title and the model and detail as the
 * subtitle. The trailing part follows [status]. A [Lost] row puts an alert icon before the subtitle in primary
 * text; [NotPaired] dims the icon. [notice] is shown below the row (the Click unlock notice).
 */
@Composable
fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    onPair: () -> Unit = {},
    onRetry: () -> Unit = {},
    notice: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = OverdriveDimens.sizeDeviceRow).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(OverdriveColors.surface2, md), contentAlignment = Alignment.Center) {
                OdIcon(icon, tint = if (status == DeviceStatus.NotPaired) OverdriveColors.textTertiary else OverdriveColors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                OdText(title, style = OdType.bodyStrong, maxLines = 1)
                if (status == DeviceStatus.Lost) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize)
                        OdText(subtitle, style = OdType.label, color = OverdriveColors.textPrimary, maxLines = 1)
                    }
                } else {
                    OdText(subtitle, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                }
            }
            when (status) {
                DeviceStatus.NotPaired -> OdButton("Pair", onPair)
                DeviceStatus.Searching -> Spinner()
                DeviceStatus.Connected ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OdIcon(OverdriveIcons.Check, size = InlineIconSize, tint = OverdriveColors.textSecondary)
                        OdText("Connected", style = OdType.label, color = OverdriveColors.textSecondary)
                    }
                DeviceStatus.Lost -> OdButton("Retry now", onRetry)
                DeviceStatus.Locked -> OdText("No presses", style = OdType.label)
            }
        }
        if (notice != null) Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) { notice() }
    }
}

/**
 * A notice for a recoverable problem (design/system.md §7.6): surface 2, radius 8, a 20 dp icon, a title over
 * a short text and an optional secondary action. No colour and no border.
 */
@Composable
fun Notice(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().background(OverdriveColors.surface2, md).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OdIcon(icon, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(text, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        if (actionLabel != null) OdButton(actionLabel, onAction, height = 40.dp)
    }
}
