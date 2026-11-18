package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
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
            .plainClickable(source, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(title, style = OdType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OdText(
                subtitle,
                style = OdType.label,
                color = OverdriveColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) OdIcon(OverdriveIcons.Check)
    }
}

/**
 * A selection row with a value on the right (design/system.md §7.5): the title in body-strong over a one-line subtitle in
 * the label style, and, when there is a [value], that value in the tabular numeral style over a [caption] in tertiary text,
 * right-aligned ("65 min" over "IF 0.76"). The selected row is surface 2 with radius 8 and a check, like [SelectableRow].
 *
 * - [subtitleIcon] is a 16 dp icon before the subtitle: the check of "Done 18:04", the alert of a row that cannot be ridden.
 * - A row that is not [enabled] has a tertiary title and an alert in primary text, takes no press and is skipped by keyboard
 *   focus; its subtitle, still secondary text so that it can be read, says why.
 * - **Focus** (a keyboard or any controller that moves focus): a 2 dp primary ring inside the row, as design/system.md §7.1
 *   says for buttons. The key that activates a focused control (Enter, the d-pad centre) selects the row.
 */
@Composable
fun ValueRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    caption: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    subtitleIcon: ImageVector? = null,
) {
    val source = rememberInteractionSource()
    val pressed = source.pressed()
    val focused by source.collectIsFocusedAsState()
    val fill =
        when {
            pressed && enabled -> OverdriveColors.surface3
            selected -> OverdriveColors.surface2
            else -> Color.Transparent
        }
    val titleColour = if (enabled) OverdriveColors.textPrimary else OverdriveColors.textTertiary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
            .background(fill, md)
            .then(
                if (focused) {
                    Modifier.border(
                        OverdriveDimens.strokeFocus,
                        OverdriveColors.textPrimary,
                        md,
                    )
                } else {
                    Modifier
                },
            ).semantics { this.selected = selected }
            .plainClickable(source, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(
                title,
                style = OdType.bodyStrong,
                color = titleColour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (subtitleIcon != null) {
                    OdIcon(
                        subtitleIcon,
                        size = InlineIconSize,
                        tint = if (enabled) OverdriveColors.textSecondary else OverdriveColors.textPrimary,
                    )
                }
                OdText(
                    subtitle,
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Column(horizontalAlignment = Alignment.End) {
                OdText(
                    value,
                    style = OdType.numXS,
                    color = titleColour,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                )
                if (caption != null) {
                    OdText(
                        caption,
                        style = OdType.captionNum,
                        color = OverdriveColors.textTertiary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (selected) OdIcon(OverdriveIcons.Check)
    }
}

/**
 * A row that opens and closes the list under it (design/system.md §7.5): a single-line, 56 dp row with the [title] in
 * body-strong and, at the right, a [detail] in the label style ("3 workouts") and a chevron that points down when
 * [expanded] and right when not. Its state is also said to accessibility ("Expanded", "Collapsed"). The same press, focus
 * ring and key behaviour as [ValueRow].
 */
@Composable
fun DisclosureRow(
    title: String,
    detail: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteractionSource()
    val pressed = source.pressed()
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = OverdriveDimens.sizeListRow)
            .background(if (pressed) OverdriveColors.surface3 else Color.Transparent, md)
            .then(
                if (focused) {
                    Modifier.border(
                        OverdriveDimens.strokeFocus,
                        OverdriveColors.textPrimary,
                        md,
                    )
                } else {
                    Modifier
                },
            ).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .plainClickable(source, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdText(title, Modifier.weight(1f), style = OdType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            OdText(detail, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1, softWrap = false)
        }
        OdIcon(
            if (expanded) OverdriveIcons.ChevronDown else OverdriveIcons.ChevronRight,
            tint = OverdriveColors.textSecondary,
        )
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

    /**
     * Paired, but nothing can be tried until something outside the device is fixed (Bluetooth is off, the permission is
     * missing): an alert in the subtitle and no button, because the notice above the list holds the one action.
     */
    Blocked,
}

/**
 * A device row (design/system.md §7.7): a 40 dp icon well, the role as title and the model and detail as the
 * subtitle. The trailing part follows [status]. A [Lost] or [Blocked] row puts an alert icon before the subtitle in
 * primary text; [NotPaired] dims the icon. [notice] is shown below the row (the Click unlock notice). [onForget], for a
 * row that has a device, adds a ghost **Forget** button at the far right.
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
    divider: Boolean = false,
    notice: (@Composable () -> Unit)? = null,
    onForget: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalRule()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = OverdriveDimens.sizeDeviceRow,
                ).padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(OverdriveColors.surface2, md), contentAlignment = Alignment.Center) {
                OdIcon(
                    icon,
                    tint =
                        if (status ==
                            DeviceStatus.NotPaired
                        ) {
                            OverdriveColors.textTertiary
                        } else {
                            OverdriveColors.textPrimary
                        },
                )
            }
            Column(Modifier.weight(1f)) {
                OdText(title, style = OdType.bodyStrong, maxLines = 1)
                if (status == DeviceStatus.Lost || status == DeviceStatus.Blocked) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize)
                        OdText(subtitle, style = OdType.label, color = OverdriveColors.textPrimary, maxLines = 1)
                    }
                } else {
                    OdText(subtitle, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                }
            }
            when (status) {
                DeviceStatus.NotPaired -> {
                    OdButton("Pair", onPair)
                }

                DeviceStatus.Searching -> {
                    Spinner()
                }

                DeviceStatus.Connected -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OdIcon(OverdriveIcons.Check, size = InlineIconSize, tint = OverdriveColors.textSecondary)
                        OdText("Connected", style = OdType.label, color = OverdriveColors.textSecondary)
                    }
                }

                DeviceStatus.Lost -> {
                    OdButton("Retry now", onRetry)
                }

                DeviceStatus.Locked -> {
                    OdText("No presses", style = OdType.label)
                }

                DeviceStatus.Blocked -> {
                    // No button: the notice above the rows holds the one action.
                }
            }
            if (onForget != null) OdButton("Forget", onForget, variant = ButtonVariant.Ghost, height = 40.dp)
        }
        if (notice != null) Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) { notice() }
    }
}

/**
 * A notice for a recoverable problem (design/system.md §7.6): surface 2, radius 8, a 20 dp icon, a title over
 * a short text and up to two 40 dp buttons (the second one, [secondaryLabel], follows the first). No colour and no border.
 */
@Composable
fun Notice(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
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
        if (actionLabel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OdButton(actionLabel, onAction, height = 40.dp)
                if (secondaryLabel != null) OdButton(secondaryLabel, onSecondary, height = 40.dp)
            }
        }
    }
}
