package app.overdrive.ride.shots

import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.ride.host.FinishedRide
import app.overdrive.ride.host.NoMedia
import app.overdrive.ride.host.RideActions
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.DevicesActions
import app.overdrive.ride.ui.DevicesScreen
import app.overdrive.ride.ui.FinishedScreen
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.RidingScreen
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.ride.workouts.WorkoutFileError
import app.overdrive.uikit.OverdriveTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.annotation.Config

/** The app's screens rendered with the numbers of the design mock-ups, at their 1280 x 800 dp size. */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class ScreenShotsTest : ShotTest() {
    private val topBar = TopBarActions({}, {}, {})
    private val settings = AppSettings(ftp = 250, massKg = 82.0, cdA = 0.32)

    private val workouts = TestData.library.workouts.value

    private fun actions(snapshot: RideSnapshot) = RideActions({ null }, { snapshot })

    @Test
    fun ready() =
        shot("ride--ready") {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices = TestData.devicesOk,
                    startEnabled = true,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }

    @Test
    fun readyFreeRideWithError() =
        shot("ride--ready-free-error") {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Free,
                    workouts = workouts,
                    settings = settings,
                    devices = TestData.devicesOk,
                    startEnabled = false,
                    openError =
                        WorkoutFileError(
                            "notes.txt is not a workout",
                            "Not a valid workout file: Unexpected JSON token. Fix the file or pick another one.",
                        ),
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                )
            }
        }

    @Test
    fun riding() =
        shot("ride--riding") {
            val snapshot = TestData.riding()
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(snapshot, RideChoice.Planned(TestData.sweetSpot)),
                    TestData.devicesOk,
                    topBar,
                    actions(snapshot),
                    NoMedia,
                )
            }
        }

    @Test
    fun paused() =
        shot("ride--paused") {
            val snapshot = TestData.riding(RideState.PAUSED, PauseReason.MANUAL)
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(snapshot, RideChoice.Planned(TestData.sweetSpot)),
                    TestData.devicesOk,
                    topBar,
                    actions(snapshot),
                    NoMedia,
                )
            }
        }

    @Test
    fun pausedConfirm() =
        shot("ride--paused-confirm") {
            val snapshot = TestData.riding(RideState.PAUSED, PauseReason.AUTO)
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(snapshot, RideChoice.Planned(TestData.sweetSpot)),
                    TestData.devicesOk,
                    topBar,
                    actions(snapshot),
                    NoMedia,
                    forceConfirm = true,
                )
            }
        }

    @Test
    fun freeRide() =
        shot("ride--free") {
            val snapshot = TestData.freeRide()
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(snapshot, RideChoice.Free, profile = false),
                    TestData.devicesOk,
                    topBar,
                    actions(snapshot),
                    NoMedia,
                )
            }
        }

    /** A whole simulated Sweet spot 3x10 ridden by the real engine in virtual time, then summarised by the host. */
    private fun finishedRide(): FinishedRide {
        var result: FinishedRide? = null
        runTest {
            val host =
                RideHost(
                    SimulatedRideSessionFactory(),
                    flowOf(AppSettings(ftp = 250)),
                    RideChoice.Planned(TestData.sweetSpot),
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            host.actions.start()
            advanceTimeBy(3_905_000)
            runCurrent()
            result = host.ui.value.finished
            host.close()
        }
        return checkNotNull(result)
    }

    @Test
    fun finished() {
        val ride = finishedRide()
        shot("ride--finished") {
            OverdriveTheme { FinishedScreen(ride, TestData.devicesOk, topBar, onDone = {}) }
        }
    }

    @Test
    fun devices() =
        shot("devices--pairing") {
            OverdriveTheme { DevicesScreen(TestData.devicesPairing, DevicesActions({}, {}, {}, {}, {})) }
        }

    @Test
    fun settingsScreen() =
        shot("settings--settings") {
            OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {})) }
        }
}
