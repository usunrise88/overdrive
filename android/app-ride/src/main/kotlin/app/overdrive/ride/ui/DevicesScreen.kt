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
import app.overdrive.core.ble.ScanFailure
import app.overdrive.ride.devices.FoundDevice
import app.overdrive.ride.devices.PairingUi
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.DeviceRow
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.SelectableRow
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * What the rider can do on the Devices screen. The first five are the ones the simulated build had; the Click's two stay
 * placeholders until M4.
 */
class DevicesActions(
    val onBack: () -> Unit,
    val onRetryTrainer: () -> Unit,
    val onRetryHeartRate: () -> Unit,
    val onPairClick: () -> Unit,
    val onCheckClick: () -> Unit,
    /** Starts a scan for trainers and straps (the **Scan** button, and **Pair** on a row that has no device). */
    val onScan: () -> Unit = {},
    /** Ends the scan. */
    val onStopScan: () -> Unit = {},
    /** The rider chose a device from the list. */
    val onPick: (FoundDevice) -> Unit = {},
    val onForgetTrainer: () -> Unit = {},
    val onForgetHeartRate: () -> Unit = {},
)

/**
 * Devices (design/system.md §6.5): the trainer, heart-rate strap and Click with their real link states on the left, a
 * **Scan** that lists the trainers and straps nearby, and the Click's button map on the right.
 *
 * Under the rows there is, in this order, **one** of: the Bluetooth notice with its one action (Bluetooth off, permission
 * missing), the scan panel, or a line saying devices cannot change during a ride. Bluetooth that cannot be used also
 * turns the rows into "Bluetooth is off" and the like, so the screen never claims a state that is not true.
 *
 * @param devices the rows; [DevicesUi.simulated] says they are the developer setting's simulated devices.
 * @param pairing the scan panel's state.
 * @param bluetooth the action of the Bluetooth notice.
 */
@Composable
fun DevicesScreen(
    devices: DevicesUi,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
    pairing: PairingUi = PairingUi(),
    bluetooth: BluetoothFix = BluetoothFix.None,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Devices", actions.onBack)
        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OdText(
                    if (devices.simulated) {
                        "These are simulated devices. Switch to Bluetooth under Settings, Developer, Devices."
                    } else {
                        "Paired devices connect on their own when the app starts."
                    },
                    style = OdType.label,
                    color = OverdriveColors.textTertiary,
                )
                val radio = devices.radio.takeIf { !devices.simulated }
                if (radio != null) BluetoothNotice(radio, bluetooth)
                // With Bluetooth unusable **Pair** does the notice's one action: a scan would find nothing. Where
                // nothing can be paired (simulated devices, a live ride) there is no scan panel to show one, so it does nothing.
                val pair: () -> Unit =
                    when {
                        radio != null -> bluetooth.run
                        devices.simulated || !devices.canChange -> ({})
                        else -> actions.onScan
                    }
                OdCard(padding = PaddingValues(0.dp)) {
                    DeviceRow(
                        OverdriveIcons.Bike,
                        "Trainer",
                        devices.trainer.subtitle,
                        devices.trainer.status,
                        onPair = pair,
                        onRetry = actions.onRetryTrainer,
                        onForget = actions.onForgetTrainer.takeIf { devices.canForget(devices.trainer) },
                    )
                    DeviceRow(
                        OverdriveIcons.Heart,
                        "Heart rate",
                        devices.heartRate.subtitle,
                        devices.heartRate.status,
                        onPair = pair,
                        onRetry = actions.onRetryHeartRate,
                        divider = true,
                        onForget = actions.onForgetHeartRate.takeIf { devices.canForget(devices.heartRate) },
                    )
                    ClickRow(devices.click, actions)
                }
                if (!devices.simulated) {
                    if (!devices.canChange) {
                        OdText(
                            "Devices cannot be changed during a ride.",
                            style = OdType.label,
                            color = OverdriveColors.textTertiary,
                        )
                    } else if (radio == null) {
                        ScanPanel(pairing, actions)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OdText("Click buttons", style = OdType.label, color = OverdriveColors.textTertiary)
                ClickMap()
                OdButton("Change button mapping", {}, enabled = false)
            }
        }
    }
}

private fun DevicesUi.canForget(row: DeviceRowUi): Boolean = !simulated && canChange && row.canForget

/** The scan button, what it found, and what to do when it found nothing. */
@Composable
private fun ScanPanel(
    pairing: PairingUi,
    actions: DevicesActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pairing.scanning) {
                OdButton("Stop", actions.onStopScan)
                Spinner()
                OdText("Looking for devices…", style = OdType.label, color = OverdriveColors.textSecondary)
            } else {
                OdButton(
                    if (pairing.scanned) "Scan again" else "Scan for devices",
                    actions.onScan,
                    variant = if (pairing.scanned) ButtonVariant.Secondary else ButtonVariant.Primary,
                )
            }
        }
        if (!pairing.isEmpty) {
            OdCard(padding = PaddingValues(0.dp)) {
                if (pairing.trainers.isNotEmpty()) FoundSection("Trainers", pairing.trainers, actions, divider = false)
                if (pairing.straps.isNotEmpty()) {
                    FoundSection("Heart-rate straps", pairing.straps, actions, divider = pairing.trainers.isNotEmpty())
                }
            }
        } else if (!pairing.scanning && pairing.scanned) {
            Notice(
                icon = OverdriveIcons.Info,
                title = if (pairing.failure == ScanFailure.REFUSED) "Scanning was refused" else "Nothing found",
                text = noResultText(pairing),
            )
        }
    }
}

private fun noResultText(pairing: PairingUi): String =
    if (pairing.failure == ScanFailure.REFUSED) {
        "Android allows only a few scans in a short time. Wait half a minute and scan again."
    } else {
        "Switch the trainer or strap on and wake it. Close any other app that is connected to it, then scan again."
    }

@Composable
private fun FoundSection(
    title: String,
    devices: List<FoundDevice>,
    actions: DevicesActions,
    divider: Boolean,
) {
    if (divider) HorizontalRule()
    OdText(
        title,
        Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        style = OdType.label,
        color = OverdriveColors.textTertiary,
    )
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (device in devices) {
            SelectableRow(
                title = device.name,
                subtitle = if (device.paired) "Paired · ${signalText(device.rssi)}" else signalText(device.rssi),
                selected = device.paired,
                onClick = { actions.onPick(device) },
            )
        }
    }
}

/** "Strong · −58 dBm": the signal in a word a rider can use, and the number for anyone who cares. */
fun signalText(rssi: Int): String {
    val word =
        when {
            rssi >= STRONG_DBM -> "Strong"
            rssi >= GOOD_DBM -> "Good"
            else -> "Weak"
        }
    return "$word · −${-rssi} dBm"
}

private const val STRONG_DBM = -60
private const val GOOD_DBM = -75

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
                        text =
                            "It is connected but sends no button presses. Unlock it once in the Zwift app, " +
                                "then check again. You can ride without it; every action is on screen.",
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
            OdText(
                hold,
                Modifier.weight(1f),
                style = OdType.label,
                color = if (holdDim) OverdriveColors.textSecondary else OverdriveColors.textPrimary,
            )
        }
    }
}
