package app.overdrive.ride.shots

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.overdrive.ble.log.BleLogSummary
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.ScanFailure
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.FoundDevice
import app.overdrive.ride.devices.PairingUi
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.ride.ui.BleLogActions
import app.overdrive.ride.ui.BleLogFiles
import app.overdrive.ride.ui.BluetoothFix
import app.overdrive.ride.ui.DevicesActions
import app.overdrive.ride.ui.DevicesScreen
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.ride.ui.signalText
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The Bluetooth screens of stage M3-2 (ADR 0010, design/system.md §6.5): every state of the Devices screen as a picture to
 * look at (`build/shots/`, never committed) and as taps that reach the right action; the Ready screen without a trainer or
 * with Bluetooth unusable; the Developer group with the log sharing. No radio, no real names or addresses.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class BluetoothUiTest : ShotTest() {
    private val topBar = TopBarActions({}, {}, {})
    private val settings = AppSettings(ftp = 250, massKg = 82.0, cdA = 0.32)
    private val workouts = TestData.library.workouts.value

    private val trainerOk = DeviceRowUi(DeviceStatus.Connected, "D500", canForget = true)
    private val strapOk = DeviceRowUi(DeviceStatus.Connected, "HR7", canForget = true)
    private val click = DeviceRowUi(DeviceStatus.Connected, "Zwift Click (right)", canForget = true)
    private val none = DeviceRowUi(DeviceStatus.NotPaired, "Not paired")

    private val connected = DevicesUi(trainerOk, strapOk, click)
    private val unpaired = DevicesUi(none, none, click)

    private fun found(
        name: String,
        role: DeviceRole,
        rssi: Int,
        paired: Boolean = false,
    ) = FoundDevice(DeviceId("test-$name"), role, name, rssi, paired)

    private val scanResults =
        PairingUi(
            scanning = true,
            scanned = true,
            trainers =
                listOf(
                    found("D500", DeviceRole.Trainer, -55, paired = true),
                    found("Studio bike", DeviceRole.Trainer, -78),
                ),
            straps =
                listOf(
                    found("HR7", DeviceRole.Strap, -65, paired = true),
                    found("Chest strap", DeviceRole.Strap, -90),
                ),
        )

    private fun devices(
        ui: DevicesUi,
        pairing: PairingUi = PairingUi(),
        bluetooth: BluetoothFix = BluetoothFix.None,
        actions: DevicesActions = DevicesActions({}, {}, {}, {}, {}),
    ) = rule.setContent { OverdriveTheme { DevicesScreen(ui, actions, pairing = pairing, bluetooth = bluetooth) } }

    // ---- Pictures ----------------------------------------------------------------------------

    @Test
    fun devicesConnected() =
        shot("devices--bluetooth-connected") {
            OverdriveTheme { DevicesScreen(connected, DevicesActions({}, {}, {}, {}, {})) }
        }

    @Test
    fun devicesScanning() =
        shot("devices--bluetooth-scanning") {
            OverdriveTheme { DevicesScreen(connected, DevicesActions({}, {}, {}, {}, {}), pairing = scanResults) }
        }

    @Test
    fun devicesNotPaired() =
        shot("devices--bluetooth-not-paired") {
            OverdriveTheme { DevicesScreen(unpaired, DevicesActions({}, {}, {}, {}, {})) }
        }

    @Test
    fun devicesNothingFound() =
        shot("devices--bluetooth-nothing-found") {
            OverdriveTheme {
                DevicesScreen(
                    unpaired,
                    DevicesActions({}, {}, {}, {}, {}),
                    pairing = PairingUi(scanned = true, timedOut = true),
                )
            }
        }

    @Test
    fun devicesRetrying() =
        shot("devices--bluetooth-retrying") {
            OverdriveTheme {
                DevicesScreen(
                    DevicesUi(
                        DeviceRowUi(DeviceStatus.Lost, "Not found · retrying in 4 s", canForget = true),
                        DeviceRowUi(DeviceStatus.Searching, "Connecting to HR7…", canForget = true),
                        click,
                    ),
                    DevicesActions({}, {}, {}, {}, {}),
                )
            }
        }

    @Test
    fun devicesBluetoothOff() =
        shot("devices--bluetooth-off") {
            OverdriveTheme {
                DevicesScreen(
                    DevicesUi(
                        DeviceRowUi(DeviceStatus.Blocked, "D500 · Bluetooth is off", canForget = true),
                        DeviceRowUi(DeviceStatus.Blocked, "HR7 · Bluetooth is off", canForget = true),
                        click,
                        radio = RadioIssue.BluetoothOff,
                    ),
                    DevicesActions({}, {}, {}, {}, {}),
                )
            }
        }

    @Test
    fun devicesPermissionNeeded() =
        shot("devices--bluetooth-permission") {
            OverdriveTheme {
                DevicesScreen(
                    DevicesUi(unpaired.trainer, unpaired.heartRate, click, radio = RadioIssue.PermissionNeeded),
                    DevicesActions({}, {}, {}, {}, {}),
                )
            }
        }

    @Test
    fun devicesDuringARide() =
        shot("devices--bluetooth-during-ride") {
            OverdriveTheme { DevicesScreen(connected.copy(canChange = false), DevicesActions({}, {}, {}, {}, {})) }
        }

    @Test
    fun devicesSimulated() =
        shot("devices--simulated") {
            OverdriveTheme {
                DevicesScreen(
                    TestData.devicesOk.copy(simulated = true),
                    DevicesActions({}, {}, {}, {}, {}),
                )
            }
        }

    @Test
    fun readyWithoutATrainer() =
        shot("ride--ready-no-trainer") {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices = unpaired,
                    startEnabled = false,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }

    @Test
    fun readyWithBluetoothOff() =
        shot("ride--ready-bluetooth-off") {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices =
                        DevicesUi(
                            DeviceRowUi(DeviceStatus.Blocked, "D500 · Bluetooth is off", canForget = true),
                            strapOk,
                            click,
                            radio = RadioIssue.BluetoothOff,
                        ),
                    startEnabled = false,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }

    // Tall, so the Developer group at the end of the left column is in the picture.
    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h1500dp-xhdpi")
    fun settingsDeveloperGroup() =
        shot("settings--developer-bluetooth") {
            OverdriveTheme {
                SettingsScreen(
                    settings,
                    SettingsActions({}, {}, {}),
                    bleLogs = BleLogFiles(BleLogActions.None, BleLogSummary(4, 120 * 1024L), null),
                )
            }
        }

    // ---- Taps --------------------------------------------------------------------------------

    @Test
    fun pairOnAnEmptyRowStartsTheScan() {
        var scans = 0
        devices(unpaired, actions = DevicesActions({}, {}, {}, {}, {}, onScan = { scans++ }))
        rule.onAllNodesWithText("Pair")[0].performClick()
        assertEquals(1, scans)
        rule.onNodeWithText("Scan for devices").performClick()
        assertEquals(2, scans)
    }

    @Test
    fun theScanListsEachRoleAndTheSignalInWords() {
        devices(connected, pairing = scanResults)
        rule.onNodeWithText("Trainers").assertIsDisplayed()
        rule.onNodeWithText("Heart-rate straps").assertIsDisplayed()
        rule.onNodeWithText("Paired · Strong · −55 dBm").assertIsDisplayed()
        rule.onNodeWithText("Weak · −78 dBm").assertIsDisplayed()
        rule.onNodeWithText("Studio bike").assertIsDisplayed()
        rule.onNodeWithText("Looking for devices…").assertIsDisplayed()
        rule.onNodeWithText("Stop").assertIsEnabled()
    }

    @Test
    fun theSignalWordsFollowTheThresholds() {
        assertEquals("Strong · −50 dBm", signalText(-50))
        assertEquals("Strong · −60 dBm", signalText(-60))
        assertEquals("Good · −65 dBm", signalText(-65))
        assertEquals("Good · −75 dBm", signalText(-75))
        assertEquals("Weak · −80 dBm", signalText(-80))
    }

    @Test
    fun tappingAFoundDevicePicksItAndStopStopsTheScan() {
        val picked = mutableListOf<FoundDevice>()
        var stops = 0
        devices(
            connected,
            pairing = scanResults,
            actions =
                DevicesActions({}, {}, {}, {}, {}, onStopScan = { stops++ }, onPick = {
                    picked +=
                        it
                }),
        )
        rule.onNodeWithText("Studio bike").performClick()
        assertEquals(listOf("Studio bike"), picked.map { it.name })
        assertEquals(DeviceRole.Trainer, picked.single().role)
        rule.onNodeWithText("Stop").performClick()
        assertEquals(1, stops)
    }

    @Test
    fun forgetAndRetryReachTheirRows() {
        val calls = mutableListOf<String>()
        devices(
            DevicesUi(DeviceRowUi(DeviceStatus.Lost, "Not found · retrying in 4 s", canForget = true), strapOk, click),
            actions =
                DevicesActions(
                    {},
                    onRetryTrainer = { calls += "retry trainer" },
                    onRetryHeartRate = { calls += "retry strap" },
                    onPairClick = {},
                    onCheckClick = {},
                    onForgetTrainer = { calls += "forget trainer" },
                    onForgetHeartRate = { calls += "forget strap" },
                ),
        )
        rule.onNodeWithText("Retry now").performClick()
        rule.onAllNodesWithText("Forget")[0].performClick()
        rule.onAllNodesWithText("Forget")[1].performClick()
        assertEquals(listOf("retry trainer", "forget trainer", "forget strap"), calls)
    }

    @Test
    fun nothingFoundSaysWhatToCheckAndARefusedScanSaysWait() {
        devices(unpaired, pairing = PairingUi(scanned = true, timedOut = true))
        rule.onNodeWithText("Nothing found").assertIsDisplayed()
        rule.onNodeWithText("Scan again").assertIsDisplayed()
    }

    @Test
    fun aRefusedScanSaysToWaitAndOffersToTryAgain() {
        devices(unpaired, pairing = PairingUi(scanned = true, failure = ScanFailure.REFUSED))
        rule.onNodeWithText("Scanning was refused").assertIsDisplayed()
        rule.onNodeWithText("Scan again").assertIsDisplayed()
    }

    @Test
    fun bluetoothOffShowsOneNoticeWithOneActionAndNoScanPanel() {
        var fixes = 0
        devices(
            DevicesUi(unpaired.trainer, unpaired.heartRate, click, radio = RadioIssue.BluetoothOff),
            bluetooth = BluetoothFix(permissionBlocked = false) { fixes++ },
        )
        rule.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        rule.onNodeWithText("Turn on").performClick()
        assertEquals(1, fixes)
        rule.onAllNodesWithText("Scan for devices").assertCountEquals(0)
        // Pair on a row does the notice's action: a scan would find nothing.
        rule.onAllNodesWithText("Pair")[0].performClick()
        assertEquals(2, fixes)
    }

    @Test
    fun theMissingPermissionAsksFirstAndSendsToTheAppSettingsOnceItWasRefused() {
        val ui = DevicesUi(unpaired.trainer, unpaired.heartRate, click, radio = RadioIssue.PermissionNeeded)
        devices(ui, bluetooth = BluetoothFix(permissionBlocked = false) {})
        rule.onNodeWithText("Allow nearby devices").assertIsDisplayed()
        rule.onNodeWithText("Allow").assertIsDisplayed()
        rule.onAllNodesWithText("Open settings").assertCountEquals(0)
    }

    @Test
    fun aRefusedPermissionOffersTheAppSettings() {
        var opened = 0
        val ui = DevicesUi(unpaired.trainer, unpaired.heartRate, click, radio = RadioIssue.PermissionNeeded)
        devices(ui, bluetooth = BluetoothFix(permissionBlocked = true) { opened++ })
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(1, opened)
        rule.onAllNodesWithText("Allow").assertCountEquals(0)
    }

    @Test
    fun aTabletWithoutBluetoothPointsAtTheSimulatedDevices() {
        devices(DevicesUi(none, none, click, radio = RadioIssue.Unsupported))
        rule.onNodeWithText("No Bluetooth on this tablet").assertIsDisplayed()
        rule.onAllNodesWithText("Turn on").assertCountEquals(0)
    }

    @Test
    fun duringARideNothingCanBeChanged() {
        devices(connected.copy(canChange = false))
        rule.onNodeWithText("Devices cannot be changed during a ride.").assertIsDisplayed()
        rule.onAllNodesWithText("Forget").assertCountEquals(0)
        rule.onAllNodesWithText("Scan for devices").assertCountEquals(0)
    }

    @Test
    fun pairOnARowDoesNotStartAScanWhereNothingCanBePaired() {
        var scans = 0
        val actions = DevicesActions({}, {}, {}, {}, {}, onScan = { scans++ })
        // A live ride has no strap paired, and the row still shows Pair: it must not start a scan nobody can see.
        devices(DevicesUi(trainerOk, none, click, canChange = false), actions = actions)
        rule.onNodeWithText("Pair").performClick()
        assertEquals(0, scans)
    }

    @Test
    fun theSimulatedDevicesCannotBePairedAndTheScreenSaysWhereToSwitch() {
        devices(TestData.devicesOk.copy(simulated = true))
        rule
            .onNodeWithText(
                "These are simulated devices. Switch to Bluetooth under Settings, Developer, Devices.",
            ).assertIsDisplayed()
        rule.onAllNodesWithText("Scan for devices").assertCountEquals(0)
        rule.onAllNodesWithText("Forget").assertCountEquals(0)
    }

    @Test
    fun theClickRowShowsItsNameAndLearningIsOfferedWithAConnectedClick() {
        devices(connected)
        rule.onNodeWithText("Zwift Click (right)").assertIsDisplayed()
        rule.onNodeWithText("Learn buttons").assertIsEnabled()
        // The default mapping is on: there is nothing to reset.
        rule.onNodeWithText("Reset to default").assertIsNotEnabled()
    }

    @Test
    fun readyWithoutATrainerSaysSoOffersTheDevicesScreenAndDoesNotOfferStart() {
        var opened = 0
        rule.setContent {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices = unpaired,
                    startEnabled = false,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}, onOpenDevices = { opened++ }),
                )
            }
        }
        rule.onNodeWithText("No trainer is paired.").assertIsDisplayed()
        rule.onNodeWithText("Start").assertIsNotEnabled()
        rule.onNodeWithText("No trainer").assertIsDisplayed()
        rule.onNodeWithText("Pair a trainer").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun readyWithATrainerStillConnectingOnlyWaits() {
        rule.setContent {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Free,
                    workouts = workouts,
                    settings = settings,
                    devices =
                        DevicesUi(
                            DeviceRowUi(DeviceStatus.Searching, "Connecting to D500…", canForget = true),
                            strapOk,
                            click,
                        ),
                    startEnabled = false,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }
        rule.onNodeWithText("Waiting for the trainer to connect.").assertIsDisplayed()
        rule.onAllNodesWithText("Pair a trainer").assertCountEquals(0)
        rule.onNodeWithText("Start").assertIsNotEnabled()
    }

    @Test
    fun readyWithBluetoothOffShowsTheNoticeAndTheActionRuns() {
        var fixes = 0
        rule.setContent {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Free,
                    workouts = workouts,
                    settings = settings,
                    devices =
                        DevicesUi(
                            DeviceRowUi(DeviceStatus.Blocked, "D500 · Bluetooth is off", canForget = true),
                            strapOk,
                            click,
                            radio = RadioIssue.BluetoothOff,
                        ),
                    startEnabled = false,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                    bluetooth = BluetoothFix(permissionBlocked = false) { fixes++ },
                )
            }
        }
        rule.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        rule.onNodeWithText("Waiting for Bluetooth.").assertIsDisplayed()
        rule.onNodeWithText("Turn on").performClick()
        assertEquals(1, fixes)
    }

    @Test
    fun readyWithSimulatedDevicesNeverShowsTheBluetoothNotice() {
        rule.setContent {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Free,
                    workouts = workouts,
                    settings = settings,
                    devices = TestData.devicesOk.copy(simulated = true, radio = RadioIssue.BluetoothOff),
                    startEnabled = true,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }
        rule.onAllNodesWithText("Bluetooth is off").assertCountEquals(0)
        rule.onNodeWithText("Start").assertIsEnabled()
    }

    // ---- Settings ----------------------------------------------------------------------------

    @Test
    fun theDeveloperSwitchesChangeTheSettings() {
        var current = settings
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    current,
                    SettingsActions({}, { change -> current = change(current) }, {}),
                )
            }
        }
        assertEquals(DeviceSource.Bluetooth, current.deviceSource)
        rule.onNodeWithText("Simulated").performScrollTo().performClick()
        assertEquals(DeviceSource.Simulated, current.deviceSource)
        assertTrue(current.recordBleSessions)
        rule.onNodeWithContentDescription("Record Bluetooth sessions").performScrollTo().performClick()
        assertFalse(current.recordBleSessions)
    }

    @Test
    fun shareBluetoothLogsCallsTheActionAndShowsWhatIsOnDisk() {
        var shared = 0
        val actions =
            object : BleLogActions {
                override fun share() {
                    shared++
                }
            }
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    settings,
                    SettingsActions({}, {}, {}),
                    bleLogs =
                        BleLogFiles(
                            actions,
                            BleLogSummary(
                                4,
                                120 * 1024L,
                            ),
                            null,
                        ),
                )
            }
        }
        rule.onNodeWithText("4 files · 120 KB").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals(1, shared)
    }

    @Test
    fun withNoLogsTheShareButtonIsOffAndTheRowSaysSo() {
        rule.setContent { OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {})) } }
        rule.onNodeWithText("No logs yet").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aShareThatFailedSaysWhyInPlaceOfTheSummary() {
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    settings,
                    SettingsActions({}, {}, {}),
                    bleLogs = BleLogFiles(BleLogActions.None, BleLogSummary(2, 900), "No app can take the files."),
                )
            }
        }
        rule.onNodeWithText("No app can take the files.").performScrollTo().assertIsDisplayed()
    }
}
