package app.overdrive.uikit.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.ConfirmSheet
import app.overdrive.uikit.DeviceIconState
import app.overdrive.uikit.DeviceRow
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.DeviceStatusCluster
import app.overdrive.uikit.DeviceStatusIcon
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdChip
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdSlider
import app.overdrive.uikit.OdSwitch
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTopBar
import app.overdrive.uikit.OdType
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.SegmentedControl
import app.overdrive.uikit.SelectableRow
import app.overdrive.uikit.SettingRow
import app.overdrive.uikit.StatTile
import app.overdrive.uikit.Stepper
import app.overdrive.uikit.TileGroup
import app.overdrive.uikit.ZoneChip
import app.overdrive.uikit.Zones
import app.overdrive.uikit.chart.ProfileLook
import app.overdrive.uikit.chart.WorkoutProfile
import app.overdrive.uikit.chart.ZoneTimeBars
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

// Previews of every component and state (design/system.md §7, §8). The sheets are public so the screenshot tests
// in `app-ride` render exactly what Android Studio shows.

@Composable
private fun SheetTitle(text: String) {
    OdText(text, Modifier.padding(bottom = 12.dp), style = OdType.label, color = OverdriveColors.textSecondary)
}

/** All device-row states of design/system.md §7.7, like the mock-up `devices--components`. */
@Composable
fun DeviceRowStates(modifier: Modifier = Modifier) {
    OdCard(modifier.width(640.dp), padding = PaddingValues(0.dp)) {
        DeviceRow(OverdriveIcons.Heart, "Heart rate", "Not paired", DeviceStatus.NotPaired)
        DeviceRow(
            OverdriveIcons.Heart,
            "Heart rate",
            "Searching for heart-rate straps…",
            DeviceStatus.Searching,
            divider = true,
        )
        DeviceRow(
            OverdriveIcons.Heart,
            "Heart rate",
            "Coospo HR7 · battery 80%",
            DeviceStatus.Connected,
            divider = true,
        )
        DeviceRow(
            OverdriveIcons.Bike,
            "Trainer",
            "Connection lost · retrying in 8 s",
            DeviceStatus.Lost,
            divider = true,
        )
        DeviceRow(
            OverdriveIcons.Gamepad2,
            "Click",
            "Zwift Click v2 · connected",
            DeviceStatus.Locked,
            divider = true,
            notice = { ClickLockedNotice() },
        )
    }
}

/** The Click "locked" notice of design/system.md §7.6, with its fixed copy. */
@Composable
fun ClickLockedNotice(onCheckAgain: () -> Unit = {}) {
    Notice(
        icon = OverdriveIcons.Info,
        title = "Click is locked",
        text =
            "It is connected but sends no button presses. Unlock it once in the Zwift app, " +
                "then check again. You can ride without it; every action is on screen.",
        actionLabel = "Check again",
        onAction = onCheckAgain,
    )
}

/** Buttons in every variant and state, chips, zone chips, tiles and inputs: the component sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentSheet() {
    OverdriveTheme {
        Column(
            Modifier.size(1280.dp, 1000.dp).background(OverdriveColors.bg).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                SheetTitle(
                    "Device row: not paired · searching · connected · connection lost · Click connected but locked",
                )
                DeviceRowStates()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f)) {
                    SheetTitle("Buttons: primary · secondary · ghost · pressed · disabled · ride size (64 dp)")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OdButton("Start", {}, variant = ButtonVariant.Primary)
                        OdButton("Pair", {})
                        OdButton("Cancel", {}, variant = ButtonVariant.Ghost)
                        OdButton("Pair", {}, forcePressed = true)
                        OdButton("Pair", {}, enabled = false)
                        OdButton("Next", {}, trailingIcon = OverdriveIcons.SkipForward, height = 64.dp)
                        OdIconButton(OverdriveIcons.Play, {}, "Play", size = 56.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    SheetTitle("Chips · zone chips · state chips")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OdChip("Riding", dot = true)
                        OdChip("Paused", icon = OverdriveIcons.Pause, on = true)
                        OdChip("ERG")
                        for (zone in 1..7) ZoneChip(zone)
                        ZoneChip(null)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f)) {
                    SheetTitle("Stat tiles: value · no signal · zone-coloured power")
                    val pad = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
                    TileGroup(
                        columns = 3,
                        tiles =
                            listOf(
                                {
                                    StatTile(
                                        "Heart rate",
                                        "148",
                                        "bpm",
                                        valueStyle = OdType.numS,
                                        spaceBetween = false,
                                        padding = pad,
                                    )
                                },
                                {
                                    StatTile(
                                        "Heart rate",
                                        null,
                                        null,
                                        valueStyle = OdType.numS,
                                        labelEnd = "No signal",
                                        spaceBetween = false,
                                        padding = pad,
                                    )
                                },
                                {
                                    StatTile(
                                        "Power",
                                        "251",
                                        "W",
                                        valueStyle = OdType.numS,
                                        valueColor = Zones.color(4),
                                        labelEnd = "Z4",
                                        spaceBetween = false,
                                        padding = pad,
                                    )
                                },
                            ),
                    )
                }
                Column(Modifier.weight(1f)) {
                    SheetTitle("Controls: segmented · stepper · switch · slider")
                    InputsSample()
                }
            }
        }
    }
}

@Composable
private fun InputsSample() {
    var segment by remember { mutableIntStateOf(0) }
    var ftp by remember { mutableIntStateOf(250) }
    var opacity by remember { mutableIntStateOf(95) }
    var on by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        SegmentedControl(listOf("Compact", "Detailed"), segment, { segment = it })
        Stepper("$ftp W", { ftp -= 5 }, { ftp += 5 })
        OdSwitch(on, { on = it })
        OdSwitch(off, { off = it })
        OdSlider(opacity, { opacity = it }, 85..100, 5, Modifier.width(200.dp))
    }
}

/** Every icon of the subset, 24 dp, with its Lucide name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconSheet() {
    OverdriveTheme {
        FlowRow(
            Modifier.size(1280.dp, 420.dp).background(OverdriveColors.bg).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((name, icon) in OverdriveIcons.all) {
                Column(
                    Modifier.width(112.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OdIcon(icon, size = 24.dp)
                    OdText(name, style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1)
                }
            }
        }
    }
}

/** The workout profile as on the ride screen: FTP line, minute ticks, blended elapsed steps, power trace, now marker. */
@Composable
fun ProfileSample() {
    OverdriveTheme {
        OdCard(Modifier.size(1000.dp, 260.dp)) {
            WorkoutProfile(
                PreviewSamples.sweetSpot,
                ProfileLook.Ride,
                Modifier.fillMaxSize(),
                nowSeconds = PreviewSamples.NOW_SECONDS,
                trace = PreviewSamples.trace.map { it.copy(fraction = it.fraction) },
            )
        }
    }
}

/** Time in power zones with the values of the mock-up `ride--finished`. */
@Composable
fun ZoneBarsSample() {
    OverdriveTheme {
        OdCard(Modifier.width(600.dp)) {
            ZoneTimeBars(listOf(812, 545, 1900, 370, 125, 70, 30))
        }
    }
}

/** A top bar and a selectable list, for the Ready screen's left column. */
@Composable
fun ListSample() {
    OverdriveTheme {
        Column(Modifier.width(360.dp).background(OverdriveColors.bg).padding(16.dp)) {
            SelectableRow("Free ride", "Simulation on the flat · gears", false, {})
            SelectableRow("Sweet spot 3×10", "65 min · steady, ramp, intervals", true, {})
            SelectableRow("VO2 5×3", "56 min · intervals", false, {})
            Box(Modifier.height(8.dp))
            SettingRow("FTP", description = "Sets power zones and workout targets", first = true) {
                Stepper("250 W", {}, {})
            }
        }
    }
}

/** The status cluster of the top bar and the confirm sheet, both open. */
@Composable
fun ChromeSample() {
    OverdriveTheme {
        Box(Modifier.size(1280.dp, 400.dp).background(OverdriveColors.bg)) {
            Column {
                OdTopBar {
                    OdText("Sweet spot 3×10", style = OdType.heading)
                    OdChip("Paused", icon = OverdriveIcons.Pause, on = true)
                    Box(Modifier.weight(1f))
                    DeviceStatusCluster(
                        listOf(
                            DeviceStatusIcon(OverdriveIcons.Bike, DeviceIconState.Ok, "Trainer"),
                            DeviceStatusIcon(OverdriveIcons.Heart, DeviceIconState.NotPaired, "Heart rate"),
                            DeviceStatusIcon(OverdriveIcons.Gamepad2, DeviceIconState.Attention, "Click"),
                        ),
                        onClick = {},
                    )
                    OdIconButton(OverdriveIcons.PictureInPicture2, {}, "Overlay")
                    OdIconButton(OverdriveIcons.Settings, {}, "Settings")
                }
                BackTopBar("Settings", {})
            }
            ConfirmSheet(true, "Finish this ride?", "Finish", "Keep riding", {}, {}, Modifier.fillMaxWidth())
        }
    }
}

@Preview(widthDp = 1280, heightDp = 1000)
@Composable
private fun ComponentSheetPreview() = ComponentSheet()

@Preview(widthDp = 1280, heightDp = 420)
@Composable
private fun IconSheetPreview() = IconSheet()

@Preview(widthDp = 1000, heightDp = 260)
@Composable
private fun ProfilePreview() = ProfileSample()

@Preview(widthDp = 600, heightDp = 260)
@Composable
private fun ZoneBarsPreview() = ZoneBarsSample()

@Preview(widthDp = 360, heightDp = 400)
@Composable
private fun ListPreview() = ListSample()

@Preview(widthDp = 1280, heightDp = 400)
@Composable
private fun ChromePreview() = ChromeSample()

@Preview(widthDp = 640, heightDp = 480)
@Composable
private fun DeviceRowsPreview() = OverdriveTheme { DeviceRowStates() }
