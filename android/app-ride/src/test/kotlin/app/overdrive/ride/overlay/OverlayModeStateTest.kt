package app.overdrive.ride.overlay

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay's shown mode around a step change (spec §11.3, docs/adr/0034): the window from the ride, the look from the window and the
 * rider's mode, and what a tap does meanwhile. The rules are `core`'s `StepChangeWindowTest`; this is the wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayModeStateTest {
    private val steps =
        listOf(
            FlatStep("A", 60, 0.5, 0.5, TrainerMode.ERG, startSeconds = 0),
            FlatStep("B", 60, 0.9, 0.9, TrainerMode.ERG, startSeconds = 60),
            FlatStep("C", 60, 0.5, 0.5, TrainerMode.ERG, startSeconds = 120),
        )

    /** The ride [elapsed] seconds into step [index], riding. */
    private fun ride(
        index: Int,
        elapsed: Int,
        state: RideState = RideState.RIDING,
    ): RideUi {
        val progress =
            WorkoutProgress(
                "Test",
                steps,
                index,
                elapsed,
                steps[index].durationSeconds - elapsed,
                steps[index].startSeconds + elapsed,
                180 - steps[index].startSeconds - elapsed,
            )
        val snapshot = RideSnapshot(state, ftp = 250, workout = progress)
        return RideUi(snapshot, RideChoice.Free, null, null, null, emptyList(), null, rideNumber = 1)
    }

    private val rides = MutableStateFlow(ride(0, 10))
    private val settings = MutableStateFlow(AppSettings())
    private val mode = OverlayModeState()

    private fun TestScope.collectLooks(): List<OverlaySettings> {
        val seen = mutableListOf<OverlaySettings>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mode.looks(mode.windowOpen(rides, settings), settings).collect { seen += it }
        }
        // The window closing clears a dismissal, as the service does.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mode.windowOpen(rides, settings).collect(mode::onWindow)
        }
        return seen
    }

    private fun shown(seen: List<OverlaySettings>) = seen.last().mode

    @Test
    fun theWindowOpensAndClosesAroundAChangeAndEmitsOnlyChanges() =
        runTest {
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                mode.windowOpen(rides, settings).collect { seen += it }
            }
            rides.value = ride(0, 30)
            rides.value = ride(0, 49)
            rides.value = ride(0, 50) // 10 s left
            rides.value = ride(0, 55)
            rides.value = ride(1, 0)
            rides.value = ride(1, 9)
            rides.value = ride(1, 10) // 10 s after
            rides.value = ride(1, 20)
            assertEquals(listOf(false, true, false), seen)
        }

    @Test
    fun aCompactOverlayExpandsInTheWindowAndReturnsAfterIt() =
        runTest {
            val seen = collectLooks()
            assertEquals(OverlayMode.Compact, shown(seen))
            rides.value = ride(0, 50)
            assertEquals(OverlayMode.Detailed, shown(seen))
            rides.value = ride(1, 10)
            assertEquals("back to the mode the rider chose", OverlayMode.Compact, shown(seen))
        }

    @Test
    fun theSettingTurnsItOff() =
        runTest {
            settings.value = AppSettings(overlayExpandsAtSteps = false)
            val seen = collectLooks()
            rides.value = ride(0, 55)
            assertEquals(OverlayMode.Compact, shown(seen))
            settings.value = AppSettings(overlayExpandsAtSteps = true)
            assertEquals("and on again at once, in the window", OverlayMode.Detailed, shown(seen))
        }

    @Test
    fun aDetailedChoiceStaysDetailedThroughtAndAfterTheWindow() =
        runTest {
            settings.value = AppSettings(overlayMode = OverlayStartMode.Detailed)
            val seen = collectLooks()
            assertEquals(OverlayMode.Detailed, shown(seen))
            rides.value = ride(0, 55)
            rides.value = ride(1, 20)
            assertTrue(seen.all { it.mode == OverlayMode.Detailed })
        }

    @Test
    fun aTapOnAnExpandedOverlayCollapsesItAndTheReturnDoesNothing() =
        runTest {
            val seen = collectLooks()
            rides.value = ride(0, 55)
            assertEquals(OverlayMode.Detailed, shown(seen))
            val chosen = mode.tap(rides.value.snapshot, settings.value)
            assertEquals("the rider's mode is untouched", OverlayStartMode.Compact, chosen)
            assertEquals(OverlayMode.Compact, shown(seen))
            // The window runs on and ends: still compact, nothing returns or expands.
            rides.value = ride(1, 5)
            assertEquals(OverlayMode.Compact, shown(seen))
            rides.value = ride(1, 12)
            assertEquals(OverlayMode.Compact, shown(seen))
        }

    @Test
    fun theNextStepChangeExpandsAgainAfterADismissal() =
        runTest {
            val seen = collectLooks()
            rides.value = ride(0, 55)
            mode.tap(rides.value.snapshot, settings.value)
            rides.value = ride(1, 12) // the window closed: the dismissal is forgotten
            rides.value = ride(1, 50) // 10 s before the next change
            assertEquals(OverlayMode.Detailed, shown(seen))
        }

    @Test
    fun aSecondTapInTheWindowChoosesDetailedForGood() =
        runTest {
            val seen = collectLooks()
            rides.value = ride(0, 55)
            val collapsed = mode.tap(rides.value.snapshot, settings.value)
            settings.value = settings.value.copy(overlayMode = collapsed)
            val chosen = mode.tap(rides.value.snapshot, settings.value) // the rider wants the detail
            assertEquals(OverlayStartMode.Detailed, chosen)
            settings.value = settings.value.copy(overlayMode = chosen)
            assertEquals(OverlayMode.Detailed, shown(seen))
            rides.value = ride(1, 15) // the window is over: the rider's choice stands
            assertEquals(OverlayMode.Detailed, shown(seen))
        }

    @Test
    fun aTapOutsideTheWindowIsTheOldToggle() {
        val calm = ride(0, 20).snapshot
        assertEquals(OverlayStartMode.Detailed, mode.tap(calm, AppSettings(overlayMode = OverlayStartMode.Compact)))
        assertEquals(OverlayStartMode.Compact, mode.tap(calm, AppSettings(overlayMode = OverlayStartMode.Detailed)))
    }

    @Test
    fun theFirstFrameIsRightInsideAWindow() {
        val inside = ride(0, 55).snapshot
        assertEquals(OverlayMode.Detailed, mode.look(inside, AppSettings()).mode)
        assertEquals(OverlayMode.Compact, mode.look(inside, AppSettings(overlayExpandsAtSteps = false)).mode)
        assertEquals(OverlayMode.Compact, mode.look(ride(0, 20).snapshot, AppSettings()).mode)
        assertEquals(0.85f, mode.look(inside, AppSettings(overlayOpacityPercent = 85)).opacity, 1e-6f)
    }

    @Test
    fun aPauseInTheLastSecondsKeepsTheWindowOpen() =
        runTest {
            val seen = collectLooks()
            rides.value = ride(0, 55)
            rides.value = ride(0, 55, RideState.PAUSED)
            assertEquals(OverlayMode.Detailed, shown(seen))
            assertFalse(seen.isEmpty())
        }
}
