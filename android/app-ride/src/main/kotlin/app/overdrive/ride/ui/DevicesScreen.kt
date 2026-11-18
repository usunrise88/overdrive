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
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.UnlockSignal
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.HandlebarButton
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
 * What the rider can do on the Devices screen. The first five are the ones the simulated build had; the Click's **Pair**
 * and **Check again** are the real Click's (a scan, a retry) with Bluetooth devices and the simulated one's with simulated
 * ones (ADR 0012).
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
    /** **Retry now** on a Click that is not found or dropped. */
    val onRetryClick: () -> Unit = {},
    val onForgetClick: () -> Unit = {},
    /** **Learn buttons**: opens the guided flow that asks for the five roles in turn. */
    val onLearnButtons: () -> Unit = {},
    /** **Reset to default**: the default mapping again. */
    val onResetButtons: () -> Unit = {},
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
 * @param mapping which of the Click's buttons is which role, shown beside what each does.
 */
@Composable
fun DevicesScreen(
    devices: DevicesUi,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
    pairing: PairingUi = PairingUi(),
    bluetooth: BluetoothFix = BluetoothFix.None,
    mapping: ButtonMapping = ButtonMapping.DEFAULT,
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
                    ClickRow(
                        devices.click,
                        actions,
                        // **Pair** is a scan like the others; with simulated devices it is the simulated Click's.
                        onPair = if (devices.simulated) actions.onPairClick else pair,
                        onForget = actions.onForgetClick.takeIf { devices.canForget(devices.click) },
                    )
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
                ClickMap(mapping)
                LearnButtons(devices, mapping, actions)
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
                var before = false
                if (pairing.trainers.isNotEmpty()) {
                    FoundSection("Trainers", pairing.trainers, actions, divider = before)
                    before = true
                }
                if (pairing.straps.isNotEmpty()) {
                    FoundSection("Heart-rate straps", pairing.straps, actions, divider = before)
                    before = true
                }
                if (pairing.clicks.isNotEmpty()) {
                    FoundSection("Handlebar controllers", pairing.clicks, actions, divider = before)
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
        "Switch the trainer, strap or Click on and wake it. Close any other app that is connected to it, then scan again."
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

/**
 * The Click's row: its real states in plain words, a **Retry now** while it waits, and below a locked one the notice of
 * spec §4.7. The two signs of a lock have two texts (the service is hidden; it answers and says nothing) and one promise:
 * unlock it through Zwift once, and the ride goes on without it.
 */
@Composable
private fun ClickRow(
    row: DeviceRowUi,
    actions: DevicesActions,
    onPair: () -> Unit,
    onForget: (() -> Unit)?,
) {
    DeviceRow(
        OverdriveIcons.Gamepad2,
        "Click",
        row.subtitle,
        row.status,
        onPair = onPair,
        onRetry = actions.onRetryClick,
        divider = true,
        onForget = onForget,
        notice =
            row.locked?.let { signal ->
                {
                    Notice(
                        icon = OverdriveIcons.Info,
                        title = unlockTitle(signal),
                        text = unlockText(signal),
                        actionLabel = "Check again",
                        onAction = actions.onCheckClick,
                    )
                }
            },
    )
}

/** The unlock notice's title: what the rider sees first. */
fun unlockTitle(signal: UnlockSignal): String =
    when (signal) {
        UnlockSignal.SERVICE_HIDDEN -> "Click is locked"
        UnlockSignal.NO_EVENTS -> "Click sends no presses"
    }

/**
 * The unlock notice's text (spec §4.7: "tells the user to unlock it through Zwift once and continues without the Click").
 * One short sentence each, plain, ending in what the rider is promised.
 */
fun unlockText(signal: UnlockSignal): String =
    when (signal) {
        UnlockSignal.SERVICE_HIDDEN -> {
            "Zwift has it locked. Unlock it through Zwift once; the ride continues without it."
        }

        UnlockSignal.NO_EVENTS -> {
            "Press any button on it. If that does nothing, unlock it through Zwift once; the ride continues without it."
        }
    }

private class ClickMapping(
    val role: HandlebarButton,
    val press: String,
    val hold: String,
    val holdRepeats: Boolean = false,
)

private val clickMap =
    listOf(
        ClickMapping(HandlebarButton.PLUS, "Gear up", "Next interval"),
        ClickMapping(HandlebarButton.BUTTON_2, "Gear down", "Previous interval"),
        ClickMapping(HandlebarButton.BUTTON_3, "Play or pause media", "Pause or resume ride"),
        ClickMapping(HandlebarButton.BUTTON_4, "Volume up", "Repeats", holdRepeats = true),
        ClickMapping(HandlebarButton.BUTTON_5, "Volume down", "Repeats", holdRepeats = true),
    )

/** What the Click's own names for the bits of [mask] read as: `Y`, `Plus`, `A + Z`. */
fun buttonNames(mask: Int): String = buttonNames(ClickBits.namesOf(mask))

/** The Click's names as the rider reads them: a single letter stays, `PLUS` is `Plus`, `NAV_LEFT` is `Nav left`. */
fun buttonNames(names: List<String>): String = names.joinToString(" + ") { readable(it) }

private fun readable(name: String): String =
    if (name.length > 1 && name.all { it.isUpperCase() || it == '_' }) {
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    } else {
        name
    }

/** The press and hold map of the five buttons (spec §4.7), each with the Click button it is on now. */
@Composable
private fun ClickMap(mapping: ButtonMapping) {
    OdCard(padding = PaddingValues(0.dp)) {
        MapLine("Button", "", "Press", "Hold", header = true)
        for (line in clickMap) {
            HorizontalRule()
            MapLine(
                line.role.label,
                buttonNames(mapping.maskOf(line.role)),
                line.press,
                line.hold,
                header = false,
                holdDim = line.holdRepeats,
            )
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
    on: String,
    press: String,
    hold: String,
    header: Boolean,
    holdDim: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(if (header) 40.dp else 56.dp).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (header) {
            OdText(button, Modifier.width(112.dp), style = OdType.caption, color = OverdriveColors.textTertiary)
            OdText(press, Modifier.weight(1f), style = OdType.caption, color = OverdriveColors.textTertiary)
            OdText(hold, Modifier.weight(1f), style = OdType.caption, color = OverdriveColors.textTertiary)
        } else {
            Column(Modifier.width(112.dp)) {
                OdText(button, style = OdType.bodyStrong, maxLines = 1)
                OdText(on, style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1)
            }
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

/**
 * **Learn buttons** and **Reset to default** under the map. Learning is offered with a Click that is connected and no ride
 * live, because the handlebar says nothing while it runs; when it is not, one line says why.
 */
@Composable
private fun LearnButtons(
    devices: DevicesUi,
    mapping: ButtonMapping,
    actions: DevicesActions,
) {
    val reason =
        when {
            devices.simulated -> "Learning needs the real Click: switch to Bluetooth under Settings, Developer."
            !devices.canChange -> "Not during a ride."
            devices.click.status != DeviceStatus.Connected -> "Connect the Click to learn its buttons."
            else -> null
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OdButton("Learn buttons", actions.onLearnButtons, variant = ButtonVariant.Primary, enabled = reason == null)
            OdButton(
                "Reset to default",
                actions.onResetButtons,
                variant = ButtonVariant.Ghost,
                enabled = mapping != ButtonMapping.DEFAULT,
            )
        }
        if (reason != null) OdText(reason, style = OdType.label, color = OverdriveColors.textTertiary)
    }
}
