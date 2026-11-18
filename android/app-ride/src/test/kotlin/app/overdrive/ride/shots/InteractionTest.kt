package app.overdrive.ride.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.center
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.host.MediaController
import app.overdrive.ride.host.NoMedia
import app.overdrive.ride.host.RideActions
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.ui.RidingScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdSlider
import app.overdrive.uikit.OdSwitch
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.SegmentedControl
import app.overdrive.uikit.Stepper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the commands a screen sends. */
private class RecordingSession(
    initial: RideSnapshot,
) : RideSession {
    val calls = mutableListOf<String>()
    override val snapshots: StateFlow<RideSnapshot> = MutableStateFlow(initial)

    override fun start() {
        calls += "start"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun finish() {
        calls += "finish"
    }

    override fun nextInterval() {
        calls += "next"
    }

    override fun previousInterval() {
        calls += "previous"
    }

    override fun gearUp() {
        calls += "gearUp"
    }

    override fun gearDown() {
        calls += "gearDown"
    }

    override fun intensityUp() {
        calls += "intensityUp"
    }

    override fun intensityDown() {
        calls += "intensityDown"
    }
}

private class RecordingMedia : MediaController {
    val calls = mutableListOf<String>()

    override fun playPause() {
        calls += "playPause"
    }

    override fun volumeUp() {
        calls += "volumeUp"
    }

    override fun volumeDown() {
        calls += "volumeDown"
    }
}

/** Every control reaches the right command, and the ui-kit inputs behave under touch (Robolectric, no emulator). */
class InteractionTest : ShotTest() {
    private val topBar = TopBarActions({}, {}, {})

    private fun ride(
        snapshot: RideSnapshot,
        media: MediaController = NoMedia,
        profile: Boolean = true,
    ): RecordingSession {
        val session = RecordingSession(snapshot)
        val actions = RideActions({ session }, { snapshot })
        rule.setContent {
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(
                        snapshot,
                        if (profile) RideChoice.Planned(TestData.sweetSpot) else RideChoice.Free,
                        profile,
                    ),
                    TestData.devicesOk,
                    topBar,
                    actions,
                    media,
                )
            }
        }
        return session
    }

    @Test
    fun ergStepButtonsDriveIntensityIntervalsAndPause() {
        val session = ride(TestData.riding())
        rule.onNodeWithContentDescription("Intensity up").performClick()
        rule.onNodeWithContentDescription("Intensity down").performClick()
        rule.onNode(hasText("Next") and hasClickAction()).performClick()
        rule.onNode(hasText("Previous") and hasClickAction()).performClick()
        rule.onNodeWithText("Pause").performClick()
        // The first click of a repeating button fires on press and none follows on release.
        assertEquals(listOf("intensityUp", "intensityDown", "next", "previous", "pause"), session.calls)
    }

    @Test
    fun freeRideButtonsDriveTheGearAndHaveNoIntervalButtons() {
        val session = ride(TestData.freeRide(), profile = false)
        rule.onNodeWithContentDescription("Gear up").performClick()
        rule.onNodeWithContentDescription("Gear down").performClick()
        assertEquals(listOf("gearUp", "gearDown"), session.calls)
        rule.onNode(hasText("Next") and hasClickAction()).assertDoesNotExist()
        rule.onNode(hasText("Previous") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun theMediaButtonsCallTheMediaController() {
        val media = RecordingMedia()
        ride(TestData.riding(), media)
        rule.onNodeWithContentDescription("Play or pause media").performClick()
        rule.onNodeWithContentDescription("Volume up").performClick()
        rule.onNodeWithContentDescription("Volume down").performClick()
        assertEquals(listOf("playPause", "volumeUp", "volumeDown"), media.calls)
    }

    @Test
    fun holdingVolumeUpRepeatsEvery150Milliseconds() {
        val media = RecordingMedia()
        ride(TestData.riding(), media)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Volume up").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(400 + 150 * 4L + 10)
        rule.onNodeWithContentDescription("Volume up").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)
        // One on press, one after the 400 ms initial delay, then one per 150 ms.
        assertTrue("expected a repeat, got ${media.calls.size}", media.calls.size in 5..7)
        assertTrue(media.calls.all { it == "volumeUp" })
    }

    @Test
    fun pausedScreenOffersResumeAndAConfirmedFinish() {
        val session = ride(TestData.riding(RideState.PAUSED, PauseReason.MANUAL))
        rule.onNodeWithText("Resume").performClick()
        rule.onNodeWithText("Finish ride").performClick()
        rule.onNodeWithText("Finish this ride?").assertExists()
        rule.onNodeWithText("Keep riding").performClick()
        assertEquals(listOf("resume"), session.calls)
        rule.onNodeWithText("Finish ride").performClick()
        rule.onNodeWithText("Finish", substring = false).performClick()
        assertEquals(listOf("resume", "finish"), session.calls)
    }

    @Test
    fun theClicksLongPressRoutesToPauseOrResumeAndGearButtonsFollowTheMode() {
        val ergSession = RecordingSession(TestData.riding())
        val erg = RideActions({ ergSession }, { TestData.riding() })
        erg.levelUp()
        erg.levelDown()
        erg.togglePause()
        assertEquals(listOf("intensityUp", "intensityDown", "pause"), ergSession.calls)

        val freeSession = RecordingSession(TestData.freeRide())
        val free = RideActions({ freeSession }, { TestData.freeRide().copy(mode = TrainerMode.SIMULATION) })
        free.levelUp()
        free.levelDown()
        assertEquals(listOf("gearUp", "gearDown"), freeSession.calls)

        val pausedSession = RecordingSession(TestData.riding(RideState.PAUSED, PauseReason.AUTO))
        val paused = RideActions({ pausedSession }, { TestData.riding(RideState.PAUSED, PauseReason.AUTO) })
        paused.togglePause()
        assertEquals(listOf("resume"), pausedSession.calls)
    }

    @Test
    fun disabledButtonsDoNotFire() {
        var clicks = 0
        rule.setContent { OverdriveTheme { OdButton("Start", { clicks++ }, enabled = false) } }
        rule.onNodeWithText("Start").assertIsNotEnabled()
        rule.onNodeWithText("Start").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun stepperSegmentedSwitchAndSliderReportTheirChanges() {
        var ftp by mutableIntStateOf(250)
        var segment by mutableIntStateOf(0)
        var on by mutableStateOf(false)
        var opacity by mutableIntStateOf(95)
        rule.setContent {
            OverdriveTheme {
                Column {
                    Stepper("$ftp W", { ftp -= 5 }, { ftp += 5 }, label = "FTP")
                    SegmentedControl(listOf("Compact", "Detailed"), segment, { segment = it })
                    OdSwitch(on, { on = it }, contentDescription = "Blur")
                    OdSlider(opacity, { opacity = it }, 85..100, 5, Modifier.width(200.dp), "Opacity")
                }
            }
        }
        rule.onNodeWithContentDescription("Increase FTP").performClick()
        assertEquals(255, ftp)
        rule.onNodeWithContentDescription("Decrease FTP").performClick()
        rule.onNodeWithContentDescription("Decrease FTP").performClick()
        assertEquals(245, ftp)

        rule.onNodeWithText("Detailed").performClick()
        assertEquals(1, segment)
        rule.onNodeWithText("Detailed").assertIsSelected()

        rule.onNodeWithContentDescription("Blur").performClick()
        assertTrue(on)

        // The far left of the slider is the minimum, the far right the maximum, the middle the middle step (92.5 rounds up).
        rule.onNodeWithContentDescription("Opacity").performTouchInput { click(Offset(1f, centerY)) }
        assertEquals(85, opacity)
        rule.onNodeWithContentDescription("Opacity").performTouchInput { click(Offset(width - 1f, centerY)) }
        assertEquals(100, opacity)
        rule.onNodeWithContentDescription("Opacity").performTouchInput { click(center) }
        assertEquals(95, opacity)
    }

    @Test
    fun aStepperAtItsEndIsDisabledOnThatSide() {
        rule.setContent { OverdriveTheme { Stepper("600 W", {}, {}, canIncrement = false, label = "FTP") } }
        rule.onNodeWithContentDescription("Increase FTP").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease FTP").assertIsEnabled()
    }
}
