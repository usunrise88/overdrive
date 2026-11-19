package app.overdrive.ride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.overdrive.ride.host.DevicesUi
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.DeviceIconState
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.DeviceStatusCluster
import app.overdrive.uikit.DeviceStatusIcon
import app.overdrive.uikit.OdChip
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTopBar
import app.overdrive.uikit.OdType
import app.overdrive.uikit.OverdriveMark
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/** The state chip next to the title (design/system.md §7.2). Chips are read-only. */
@Immutable
sealed interface TopBarChip {
    /** "Ready", "Riding": a dot and a word in surface 2. */
    data class Calm(
        val text: String,
    ) : TopBarChip

    /** "Paused": the inverted chip that needs attention. */
    data class Attention(
        val text: String,
    ) : TopBarChip

    data object None : TopBarChip
}

/** The buttons of the ride top bar: devices, overlay, settings. */
@Immutable
class TopBarActions(
    val onDevices: () -> Unit,
    val onOverlay: () -> Unit,
    val onSettings: () -> Unit,
    val overlayShown: Boolean = false,
)

/**
 * The top bar of the ride screens (design/system.md §6): the title and a state chip, then right-aligned the device
 * status icons, the overlay button and the settings button. [mark] puts the Overdrive mark before the title (the Ready screen,
 * where the title is the app's name).
 */
@Composable
fun RideTopBar(
    title: String,
    chip: TopBarChip,
    devices: DevicesUi,
    actions: TopBarActions,
    modifier: Modifier = Modifier,
    mark: Boolean = false,
) {
    OdTopBar(modifier) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mark) OverdriveMark(size = 26.dp)
            OdText(title, Modifier.weight(1f, fill = false), style = OdType.heading, maxLines = 1)
            when (chip) {
                is TopBarChip.Calm -> OdChip(chip.text, dot = true)
                is TopBarChip.Attention -> OdChip(chip.text, icon = OverdriveIcons.Pause, on = true)
                TopBarChip.None -> Spacer(Modifier)
            }
        }
        DeviceStatusCluster(deviceIcons(devices), actions.onDevices)
        OdIconButton(
            OverdriveIcons.PictureInPicture2,
            actions.onOverlay,
            "Overlay",
            tint = if (actions.overlayShown) OverdriveColors.textPrimary else Color.Unspecified,
            variant = ButtonVariant.Ghost,
        )
        OdIconButton(OverdriveIcons.Settings, actions.onSettings, "Settings")
    }
}

/** The three status icons in the order trainer, strap, Click (design/system.md §7.7). */
fun deviceIcons(devices: DevicesUi): List<DeviceStatusIcon> =
    listOf(
        DeviceStatusIcon(OverdriveIcons.Bike, devices.trainer.status.iconState(), "Trainer"),
        DeviceStatusIcon(OverdriveIcons.Heart, devices.heartRate.status.iconState(), "Heart rate"),
        DeviceStatusIcon(OverdriveIcons.Gamepad2, devices.click.status.iconState(), "Click"),
    )

private fun DeviceStatus.iconState(): DeviceIconState =
    when (this) {
        DeviceStatus.Connected -> DeviceIconState.Ok
        DeviceStatus.NotPaired, DeviceStatus.Searching -> DeviceIconState.NotPaired
        DeviceStatus.Lost, DeviceStatus.Locked, DeviceStatus.Blocked -> DeviceIconState.Attention
    }

/** A section label above a card or a column (label style, tertiary). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    OdText(text, modifier.fillMaxWidth(), style = OdType.label, color = OverdriveColors.textTertiary)
}

/** A title followed by a lighter secondary part: "Sweet spot" and "2 of 3". */
@Composable
fun rememberTitleWithNote(
    title: String,
    note: String?,
): AnnotatedString =
    remember(title, note) {
        buildAnnotatedString {
            append(title)
            if (note != null) {
                withStyle(
                    SpanStyle(fontWeight = FontWeight.Normal, color = OverdriveColors.textSecondary),
                ) { append(" $note") }
            }
        }
    }
