package app.overdrive.ride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.DeviceRow
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/** What the rider can do on the Devices screen. All of it is simulated until the BLE layer (M3) and the Click (M4). */
class DevicesActions(
    val onBack: () -> Unit,
    val onRetryTrainer: () -> Unit,
    val onRetryHeartRate: () -> Unit,
    val onPairClick: () -> Unit,
    val onCheckClick: () -> Unit,
)

/**
 * Devices (design/system.md §6.5): the trainer, heart-rate strap and Click with their states on the left, the Click's
 * button map on the right. The rows show the *simulated* devices of this build; the screen is driven by link states
 * (`core` `ConnectionState`) so the real adapters of M3 fill it unchanged.
 */
@Composable
fun DevicesScreen(
    devices: DevicesUi,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Devices", actions.onBack)
        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OdText("Paired devices connect on their own when the app starts.", style = OdType.label, color = OverdriveColors.textTertiary)
                OdCard(padding = PaddingValues(0.dp)) {
                    DeviceRow(
                        OverdriveIcons.Bike,
                        "Trainer",
                        devices.trainer.subtitle,
                        devices.trainer.status,
                        onRetry = actions.onRetryTrainer,
                    )
                    DeviceRow(
                        OverdriveIcons.Heart,
                        "Heart rate",
                        devices.heartRate.subtitle,
                        devices.heartRate.status,
                        onRetry = actions.onRetryHeartRate,
                        divider = true,
                    )
                    ClickRow(devices.click, actions)
                }
                OdText(
                    "These are simulated devices. Real Bluetooth arrives in a later build.",
                    style = OdType.label,
                    color = OverdriveColors.textTertiary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OdText("Click buttons", style = OdType.label, color = OverdriveColors.textTertiary)
                ClickMap()
                OdButton("Change button mapping", {}, enabled = false)
            }
        }
    }
}

@Composable
private fun ClickRow(
    row: DeviceRowUi,
    actions: DevicesActions,
) {
    DeviceRow(
        OverdriveIcons.Gamepad2,
        "Click",
        row.subtitle,
        row.status,
        onPair = actions.onPairClick,
        divider = true,
        notice =
            if (row.status == DeviceStatus.Locked) {
                {
                    Notice(
                        icon = OverdriveIcons.Info,
                        title = "Click is locked",
                        text = "It is connected but sends no button presses. Unlock it once in the Zwift app, then check again. You can ride without it; every action is on screen.",
                        actionLabel = "Check again",
                        onAction = actions.onCheckClick,
                    )
                }
            } else {
                null
            },
    )
}

private class ClickMapping(
    val button: String,
    val press: String,
    val hold: String,
    val holdRepeats: Boolean = false,
)

private val clickMap =
    listOf(
        ClickMapping("Plus", "Gear up", "Next interval"),
        ClickMapping("Button 2", "Gear down", "Previous interval"),
        ClickMapping("Button 3", "Play or pause media", "Pause or resume ride"),
        ClickMapping("Button 4", "Volume up", "Repeats", holdRepeats = true),
        ClickMapping("Button 5", "Volume down", "Repeats", holdRepeats = true),
    )

/** The press and hold map of the five buttons (spec §4.7), for reference. */
@Composable
private fun ClickMap() {
    OdCard(padding = PaddingValues(0.dp)) {
        MapLine("Button", "Press", "Hold", header = true)
        for (mapping in clickMap) {
            HorizontalRule()
            MapLine(mapping.button, mapping.press, mapping.hold, header = false, holdDim = mapping.holdRepeats)
        }
        HorizontalRule()
        OdText(
            "In ERG steps the gear buttons change intensity by 5%.",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            style = OdType.label,
            color = OverdriveColors.textSecondary,
        )
    }
}

@Composable
private fun MapLine(
    button: String,
    press: String,
    hold: String,
    header: Boolean,
    holdDim: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(if (header) 40.dp else 48.dp).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (header) {
            OdText(button, Modifier.width(112.dp), style = OdType.caption, color = OverdriveColors.textTertiary)
            OdText(press, Modifier.weight(1f), style = OdType.caption, color = OverdriveColors.textTertiary)
            OdText(hold, Modifier.weight(1f), style = OdType.caption, color = OverdriveColors.textTertiary)
        } else {
            OdText(button, Modifier.width(112.dp), style = OdType.bodyStrong)
            OdText(press, Modifier.weight(1f), style = OdType.label)
            OdText(hold, Modifier.weight(1f), style = OdType.label, color = if (holdDim) OverdriveColors.textSecondary else OverdriveColors.textPrimary)
        }
    }
}
