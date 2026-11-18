package app.overdrive.ride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.ride.overlay.OverlayAccess
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.ClickKeepAlive
import app.overdrive.ride.settings.ClickMtu
import app.overdrive.ride.settings.ClickPucks
import app.overdrive.ride.settings.ClickReplyCheck
import app.overdrive.ride.settings.ClickRules
import app.overdrive.ride.settings.ClickSettings
import app.overdrive.ride.settings.ClickSilence
import app.overdrive.ride.settings.DemoSpeed
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.ride.settings.SecretText
import app.overdrive.ride.settings.SettingsRules
import app.overdrive.ride.settings.SimulatedClick
import app.overdrive.ride.settings.SyncRules
import app.overdrive.ride.settings.UrlCheck
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdSlider
import app.overdrive.uikit.OdSwitch
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTextField
import app.overdrive.uikit.OdType
import app.overdrive.uikit.SegmentedControl
import app.overdrive.uikit.SettingRow
import app.overdrive.uikit.Stepper
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.overlay.OverlayCompact
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.preview.PreviewSamples
import app.overdrive.uikit.tokens.OverdriveColors

/** What the rider can do on the Settings screen. Every change goes through [onChange] and is validated and stored there. */
class SettingsActions(
    val onBack: () -> Unit,
    val onChange: ((AppSettings) -> AppSettings) -> Unit,
    val onResetOverlayPosition: () -> Unit,
    /** Opens the system screen for "Display over other apps". */
    val onOpenOverlaySettings: () -> Unit = {},
    /** Opens the app's notification settings. */
    val onOpenNotificationSettings: () -> Unit = {},
)

/**
 * Settings (design/system.md §6.6): the overlay with a live preview, the rider and gears, the physics for speed and
 * distance, and a developer group (where the devices come from, the Bluetooth logs, the simulated ride). The rules (ranges, steps) are [SettingsRules]; the
 * steppers cannot leave them, and the repository sanitizes whatever arrives anyway.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    overlayAccess: OverlayAccess = OverlayAccess.Granted,
    bleLogs: BleLogFiles = BleLogFiles.None,
    waitingToUpload: Int = 0,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Settings", actions.onBack)
        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                OverlayGroup(settings, overlayAccess, actions)
                DeveloperGroup(settings, actions, bleLogs)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                RiderGroup(settings, actions)
                PhysicsGroup(settings, actions)
                SyncGroup(settings, actions, waitingToUpload)
                ClickDeveloperGroup(settings, actions)
            }
        }
    }
}

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OdText(title, Modifier.padding(horizontal = 4.dp), style = OdType.label, color = OverdriveColors.textTertiary)
        OdCard(padding = PaddingValues(0.dp)) { content() }
    }
}

// ---- Overlay -----------------------------------------------------------------------------------

@Composable
private fun OverlayGroup(
    settings: AppSettings,
    access: OverlayAccess,
    actions: SettingsActions,
) {
    Group("Overlay") {
        OverlayPreview(
            settings.overlayOpacityPercent / PERCENT,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        )
        SettingRow(
            "Display over other apps",
            description =
                if (access.canDrawOverlays) {
                    "Allowed. The overlay shows over other apps during a ride"
                } else {
                    "Needed for the overlay over other apps. The ride works without it"
                },
        ) {
            OdButton(
                if (access.canDrawOverlays) "Manage" else "Allow",
                actions.onOpenOverlaySettings,
                variant = if (access.canDrawOverlays) ButtonVariant.Ghost else ButtonVariant.Secondary,
            )
        }
        SettingRow(
            "Ride notification",
            description = "Shown while a ride is on. The overlay works without it",
        ) {
            if (access.notificationsAllowed) {
                OdText("On", style = OdType.label, color = OverdriveColors.textSecondary)
            } else {
                OdButton("Allow", actions.onOpenNotificationSettings)
            }
        }
        SettingRow("Mode", description = "Tap the overlay to switch") {
            SegmentedControl(
                options = listOf("Compact", "Detailed"),
                selectedIndex = settings.overlayMode.ordinal,
                onSelect = { index -> actions.onChange { it.copy(overlayMode = OverlayStartMode.entries[index]) } },
            )
        }
        SettingRow("Opacity", description = "85–100%", titleWidth = 200.dp) {
            OdSlider(
                value = settings.overlayOpacityPercent,
                onValueChange = { value -> actions.onChange { it.copy(overlayOpacityPercent = value) } },
                range = SettingsRules.opacity.min..SettingsRules.opacity.max,
                step = SettingsRules.opacity.step,
                modifier = Modifier.weight(1f),
                contentDescription = "Overlay opacity",
            )
            OdText(
                "${settings.overlayOpacityPercent}%",
                Modifier.width(48.dp),
                style = OdType.numXS,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
        // Hidden where the system does not blur behind windows (design/system.md §5.5): the switch would do nothing.
        if (access.blurAvailable) {
            SettingRow("Blur the video behind", description = "Easier to read over busy video. Uses more battery.") {
                OdSwitch(settings.overlayBlur, { value ->
                    actions.onChange {
                        it.copy(overlayBlur = value)
                    }
                }, contentDescription = "Blur the video behind")
            }
        }
        SettingRow("Position", description = "Drag the overlay; it snaps to the nearest edge") {
            OdButton(
                "Reset",
                actions.onResetOverlayPosition,
                variant = ButtonVariant.Ghost,
                enabled =
                    settings.overlayX != null || settings.overlayY != null,
            )
        }
    }
}

/** The compact overlay on a bright frame at the chosen opacity, flat colours only. */
@Composable
private fun OverlayPreview(
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp))) {
        Box(Modifier.fillMaxSize().background(Color(0xFFE9EEF4)))
        Box(Modifier.fillMaxWidth().height(100.dp).offset(y = 100.dp).background(Color(0xFFF1F2EE)))
        OverlayCompact(PreviewSamples.overlayErg, OverlaySettings(opacity = opacity), Modifier.offset(16.dp, 16.dp))
    }
}

// ---- Rider and physics -------------------------------------------------------------------------

@Composable
private fun RiderGroup(
    settings: AppSettings,
    actions: SettingsActions,
) {
    Group("Rider and gears") {
        SettingRow("FTP", description = "Sets power zones and workout targets", first = true) {
            Stepper(
                "${settings.ftp} W",
                onDecrement = { actions.onChange { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, -1)) } },
                onIncrement = { actions.onChange { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, +1)) } },
                canDecrement = SettingsRules.ftp.canStep(settings.ftp, -1),
                canIncrement = SettingsRules.ftp.canStep(settings.ftp, +1),
                label = "FTP",
            )
        }
        SettingRow("Gears", description = "Gear ${settings.neutralGear} rides the flat") {
            Stepper(
                settings.gearCount.toString(),
                onDecrement = {
                    actions.onChange {
                        it.copy(
                            gearCount = SettingsRules.gears.stepped(it.gearCount, -1),
                        )
                    }
                },
                onIncrement = {
                    actions.onChange {
                        it.copy(
                            gearCount = SettingsRules.gears.stepped(it.gearCount, +1),
                        )
                    }
                },
                canDecrement = SettingsRules.gears.canStep(settings.gearCount, -1),
                canIncrement = SettingsRules.gears.canStep(settings.gearCount, +1),
                label = "gears",
            )
        }
        SettingRow("Step per gear", description = "Grade added by each gear; total stays within 15%") {
            Stepper(
                "${UiFormat.decimal(settings.gradePerGear, 1)}%",
                onDecrement = {
                    actions.onChange {
                        it.copy(
                            gradePerGear = SettingsRules.gradePerGear.stepped(it.gradePerGear, -1),
                        )
                    }
                },
                onIncrement = {
                    actions.onChange {
                        it.copy(
                            gradePerGear = SettingsRules.gradePerGear.stepped(it.gradePerGear, +1),
                        )
                    }
                },
                canDecrement = SettingsRules.gradePerGear.canStep(settings.gradePerGear, -1),
                canIncrement = SettingsRules.gradePerGear.canStep(settings.gradePerGear, +1),
                label = "step per gear",
            )
        }
    }
}

@Composable
private fun PhysicsGroup(
    settings: AppSettings,
    actions: SettingsActions,
) {
    Group("Physics for speed and distance") {
        SettingRow("Rider and bike mass", first = true) {
            Stepper(
                "${UiFormat.decimal(settings.massKg, 0)} kg",
                onDecrement = { actions.onChange { it.copy(massKg = SettingsRules.mass.stepped(it.massKg, -1)) } },
                onIncrement = { actions.onChange { it.copy(massKg = SettingsRules.mass.stepped(it.massKg, +1)) } },
                canDecrement = SettingsRules.mass.canStep(settings.massKg, -1),
                canIncrement = SettingsRules.mass.canStep(settings.massKg, +1),
                label = "mass",
            )
        }
        SettingRow("Drag area (CdA)") {
            Stepper(
                "${UiFormat.decimal(settings.cdA, 2)} m²",
                onDecrement = { actions.onChange { it.copy(cdA = SettingsRules.cdA.stepped(it.cdA, -1)) } },
                onIncrement = { actions.onChange { it.copy(cdA = SettingsRules.cdA.stepped(it.cdA, +1)) } },
                canDecrement = SettingsRules.cdA.canStep(settings.cdA, -1),
                canIncrement = SettingsRules.cdA.canStep(settings.cdA, +1),
                label = "drag area",
            )
        }
        SettingRow("Rolling resistance (Crr)") {
            Stepper(
                UiFormat.decimal(settings.crr, 4),
                onDecrement = { actions.onChange { it.copy(crr = SettingsRules.crr.stepped(it.crr, -1)) } },
                onIncrement = { actions.onChange { it.copy(crr = SettingsRules.crr.stepped(it.crr, +1)) } },
                canDecrement = SettingsRules.crr.canStep(settings.crr, -1),
                canIncrement = SettingsRules.crr.canStep(settings.crr, +1),
                label = "rolling resistance",
            )
        }
    }
}

// ---- Developer ---------------------------------------------------------------------------------

@Composable
private fun DeveloperGroup(
    settings: AppSettings,
    actions: SettingsActions,
    bleLogs: BleLogFiles,
) {
    Group("Developer") {
        SettingRow(
            "Devices",
            description = "Bluetooth connects your trainer and strap. Simulated rides without them.",
            first = true,
        ) {
            SegmentedControl(
                options = DeviceSource.entries.map { it.label },
                selectedIndex = settings.deviceSource.ordinal,
                onSelect = { index -> actions.onChange { it.copy(deviceSource = DeviceSource.entries[index]) } },
            )
        }
        SettingRow(
            "Record Bluetooth sessions",
            description = "Writes each connection to a file on the tablet, to fix the app without a bench",
        ) {
            OdSwitch(
                settings.recordBleSessions,
                { value -> actions.onChange { it.copy(recordBleSessions = value) } },
                contentDescription = "Record Bluetooth sessions",
            )
        }
        SettingRow("Share Bluetooth logs", description = bleLogs.message ?: bleLogs.summary.describe()) {
            OdButton(
                "Share",
                bleLogs.actions::share,
                enabled = bleLogs.summary.files > 0,
                leadingIcon = OverdriveIcons.Share2,
                height = 40.dp,
            )
        }
        SettingRow("Demo speed", description = "Runs the next simulated ride faster than real time") {
            SegmentedControl(
                options = DemoSpeed.entries.map { it.label },
                selectedIndex = settings.demoSpeed.ordinal,
                onSelect = { index -> actions.onChange { it.copy(demoSpeed = DemoSpeed.entries[index]) } },
            )
        }
        SettingRow(
            "Scripted dropouts",
            description = "Heart rate, cadence, power and the trainer drop out during the ride",
        ) {
            OdSwitch(settings.scriptedDropouts, { value ->
                actions.onChange {
                    it.copy(scriptedDropouts = value)
                }
            }, contentDescription = "Scripted dropouts")
        }
        SettingRow(
            "Simulated Click",
            description = "What the Devices screen shows for the Click when devices are simulated",
        ) {
            SegmentedControl(
                options = SimulatedClick.entries.map { it.label },
                selectedIndex = settings.simulatedClick.ordinal,
                onSelect = { index -> actions.onChange { it.copy(simulatedClick = SimulatedClick.entries[index]) } },
            )
        }
    }
}

// ---- Sync --------------------------------------------------------------------------------------

/**
 * Sync (spec §4.9, ADR 0012): the web service's address and token, **stored and not used yet**: the app sends nothing in
 * Phase 1, and the rides it finishes wait on this tablet for Phase 2. The address is checked as it is typed and stored only
 * when it is a good one; the token is hidden, never shown in a log and not part of a ride.
 */
@Composable
private fun SyncGroup(
    settings: AppSettings,
    actions: SettingsActions,
    waitingToUpload: Int,
) {
    Group("Sync") {
        var address by remember { mutableStateOf(settings.syncUrl) }
        val check = SyncRules.checkUrl(address)
        FieldRow(
            "Service address",
            "Not used yet",
            first = true,
            message = (check as? UrlCheck.Invalid)?.reason,
        ) {
            OdTextField(
                address,
                { text ->
                    address = text
                    when (val result = SyncRules.checkUrl(text)) {
                        UrlCheck.Empty -> actions.onChange { it.copy(syncUrl = "") }
                        is UrlCheck.Valid -> actions.onChange { it.copy(syncUrl = result.url) }
                        is UrlCheck.Invalid -> Unit
                    }
                },
                contentDescription = "Service address",
                modifier = Modifier.weight(1f),
                placeholder = "https://example.com",
                keyboardType = KeyboardType.Uri,
            )
        }
        var showToken by remember { mutableStateOf(false) }
        // Typed text is kept here, like the address: the stored value comes back a moment later and would eat letters.
        var token by remember { mutableStateOf(settings.syncToken.value) }
        FieldRow("Token", "Not used yet. It stays on this tablet") {
            OdTextField(
                token,
                { text ->
                    token = text
                    actions.onChange { it.copy(syncToken = SecretText(text)) }
                },
                contentDescription = "Token",
                modifier = Modifier.weight(1f),
                placeholder = "Paste the token",
                secret = !showToken,
            )
            OdButton(
                if (showToken) "Hide" else "Show",
                { showToken = !showToken },
                variant = ButtonVariant.Ghost,
                height = 48.dp,
            )
        }
        SettingRow(
            "Waiting to upload",
            description = "Nothing is sent yet. Finished rides wait here for the next version",
        ) {
            OdText(
                if (waitingToUpload == 1) "1 ride" else "$waitingToUpload rides",
                style = OdType.numXS,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/** A setting whose control is a field wide enough to type in: title and note on top, the field under them, a message below. */
@Composable
private fun FieldRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    message: String? = null,
    field: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!first) HorizontalRule()
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                OdText(title, style = OdType.bodyStrong)
                OdText(description, style = OdType.label, color = OverdriveColors.textSecondary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = field,
            )
            if (message != null) OdText(message, style = OdType.label, color = OverdriveColors.textPrimary)
        }
    }
}

// ---- Click (developer) -------------------------------------------------------------------------

/**
 * The Click's developer settings (ADR 0012): what the owner flips at the bench without a rebuild, the answers to the open
 * questions of docs/protocols/click-v2.md §9, and the two gesture timings (spec §4.7). Every row is a default that the notes
 * recommend; **Reset Click settings** puts them all back.
 */
@Composable
private fun ClickDeveloperGroup(
    settings: AppSettings,
    actions: SettingsActions,
) {
    val click = settings.click

    fun change(update: (ClickSettings) -> ClickSettings) = actions.onChange { it.copy(click = update(it.click)) }
    Group("Developer · Click") {
        SettingRow("Setup message", description = "Writes FF 04 00 after the handshake", first = true) {
            OdSwitch(
                click.sendPostHandshake,
                { value -> change { it.copy(sendPostHandshake = value) } },
                contentDescription = "Setup message",
            )
        }
        SettingRow("Handshake with response", description = "Asks the Click to confirm the handshake write") {
            OdSwitch(
                click.writeWithResponse,
                { value -> change { it.copy(writeWithResponse = value) } },
                contentDescription = "Handshake with response",
            )
        }
        SettingRow("Reply check", description = "How the handshake reply is checked") {
            SegmentedControl(
                ClickReplyCheck.entries.map { it.label },
                click.replyCheck.ordinal,
                { index -> change { it.copy(replyCheck = ClickReplyCheck.entries[index]) } },
            )
        }
        SettingRow("Keep-alive", description = "Writes a neutral frame to the Click now and then") {
            SegmentedControl(
                ClickKeepAlive.entries.map { it.label },
                click.keepAlive.ordinal,
                { index -> change { it.copy(keepAlive = ClickKeepAlive.entries[index]) } },
            )
        }
        SettingRow(
            "Silence means locked",
            description = "Answered, then sent nothing: counts as locked. A healthy Click waits for a press",
        ) {
            OdSwitch(
                click.silentMeansLocked,
                { value -> change { it.copy(silentMeansLocked = value) } },
                contentDescription = "Silence means locked",
            )
        }
        SettingRow(
            "Silence limit",
            description = "With the switch above: how long without a message counts as locked",
        ) {
            SegmentedControl(
                ClickSilence.entries.map { it.label },
                click.noEventTimeout.ordinal,
                { index -> change { it.copy(noEventTimeout = ClickSilence.entries[index]) } },
            )
        }
        SettingRow("Message size", description = "The size asked for when connecting") {
            SegmentedControl(
                ClickMtu.entries.map { it.label },
                click.mtu.ordinal,
                { index -> change { it.copy(mtu = ClickMtu.entries[index]) } },
            )
        }
        SettingRow("Needs its service listed", description = "A scan result must advertise the Zwift service") {
            OdSwitch(
                click.requireAdvertisedService,
                { value -> change { it.copy(requireAdvertisedService = value) } },
                contentDescription = "Needs its service listed",
            )
        }
        SettingRow("Pucks found by Scan", description = "The right one rides; the left is for checking") {
            SegmentedControl(
                ClickPucks.entries.map { it.label },
                click.pucks.ordinal,
                { index -> change { it.copy(pucks = ClickPucks.entries[index]) } },
            )
        }
        SettingRow("Hold threshold", description = "Press this long for a hold") {
            Stepper(
                "${click.holdThresholdMillis} ms",
                onDecrement = {
                    change {
                        it.copy(
                            holdThresholdMillis = ClickRules.holdThreshold.stepped(it.holdThresholdMillis, -1),
                        )
                    }
                },
                onIncrement = {
                    change {
                        it.copy(
                            holdThresholdMillis = ClickRules.holdThreshold.stepped(it.holdThresholdMillis, +1),
                        )
                    }
                },
                canDecrement = ClickRules.holdThreshold.canStep(click.holdThresholdMillis, -1),
                canIncrement = ClickRules.holdThreshold.canStep(click.holdThresholdMillis, +1),
                label = "hold threshold",
            )
        }
        SettingRow("Volume repeat", description = "One volume step this often while held") {
            Stepper(
                "${click.volumeRepeatMillis} ms",
                onDecrement = {
                    change { it.copy(volumeRepeatMillis = ClickRules.volumeRepeat.stepped(it.volumeRepeatMillis, -1)) }
                },
                onIncrement = {
                    change { it.copy(volumeRepeatMillis = ClickRules.volumeRepeat.stepped(it.volumeRepeatMillis, +1)) }
                },
                canDecrement = ClickRules.volumeRepeat.canStep(click.volumeRepeatMillis, -1),
                canIncrement = ClickRules.volumeRepeat.canStep(click.volumeRepeatMillis, +1),
                label = "volume repeat",
            )
        }
        SettingRow("Reset Click settings", description = "Back to the defaults. The learned buttons stay") {
            OdButton(
                "Reset",
                { actions.onChange { it.copy(click = ClickSettings()) } },
                enabled = click != ClickSettings(),
                height = 40.dp,
            )
        }
    }
}

private const val PERCENT = 100f
