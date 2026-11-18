package app.overdrive.ride.overlay

import app.overdrive.core.ride.RideState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the overlay window exists and when its service stops (ADR 0006), in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayPolicyTest {
    @Test
    fun theWindowIsWantedOnlyWhileARideIsLiveAndTheAppIsNotOnScreen() {
        for (state in RideState.entries) {
            for (visible in listOf(true, false)) {
                val live = state == RideState.RIDING || state == RideState.PAUSED
                assertEquals("$state visible=$visible", live && !visible, OverlayPolicy.windowWanted(state, visible))
            }
        }
    }

    @Test
    fun theWindowAppearsAfterTheDelayAndDisappearsAtOnce() =
        runTest {
            val wanted = MutableStateFlow(false)
            val seen = mutableListOf<Pair<Long, Boolean>>()
            val job =
                launch {
                    OverlayPolicy.windowShown(wanted, showDelayMs = 300).collect {
                        seen +=
                            testScheduler.currentTime to it
                    }
                }
            runCurrent()
            assertEquals(listOf(0L to false), seen)

            wanted.value = true
            advanceTimeBy(299)
            runCurrent()
            assertEquals(1, seen.size)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(0L to false, 300L to true), seen)

            wanted.value = false
            runCurrent()
            // No wait to hide: it goes at the instant of the change.
            assertEquals(listOf(0L to false, 300L to true, 301L to false), seen)
            job.cancel()
        }

    @Test
    fun leavingAndReturningWithinTheDelayNeverShowsTheWindow() =
        runTest {
            // An activity that is recreated (rotation) stops and starts within a few frames.
            val wanted = MutableStateFlow(false)
            val seen = mutableListOf<Boolean>()
            val job = launch { OverlayPolicy.windowShown(wanted, showDelayMs = 300).collect { seen += it } }
            runCurrent()
            wanted.value = true
            advanceTimeBy(120)
            wanted.value = false
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(false), seen)
            job.cancel()
        }

    @Test
    fun theServiceStopsWhenTheRideIsDismissedAfterItsSummary() {
        val rule = ServiceStopRule()
        assertFalse(rule.onUpdate(RideState.READY, appVisible = true)) // the start press, before the ride shows up
        assertFalse(rule.onUpdate(RideState.RIDING, appVisible = false))
        assertFalse(rule.onUpdate(RideState.PAUSED, appVisible = false))
        // The summary has not been seen yet: the notification stays.
        assertFalse(rule.onUpdate(RideState.FINISHED, appVisible = false))
        assertTrue(rule.onUpdate(RideState.READY, appVisible = true)) // Done: the host is ready again
    }

    @Test
    fun theServiceStopsOnceTheSummaryIsOnScreen() {
        val rule = ServiceStopRule()
        rule.onUpdate(RideState.RIDING, appVisible = false)
        assertTrue(rule.onUpdate(RideState.FINISHED, appVisible = true))
    }

    @Test
    fun aServiceThatNeverSawARideStopsAfterTheGracePeriod() {
        val rule = ServiceStopRule()
        rule.onUpdate(RideState.IDLE, appVisible = true)
        assertTrue(rule.onGraceElapsed())

        val busy = ServiceStopRule()
        busy.onUpdate(RideState.RIDING, appVisible = false)
        assertFalse(busy.onGraceElapsed())
    }

    @Test
    fun aServiceThatNeverSawARideDoesNotStopOnReadyBeforeTheGraceEnds() {
        val rule = ServiceStopRule()
        assertFalse(rule.onUpdate(RideState.IDLE, appVisible = true))
        assertFalse(rule.onUpdate(RideState.READY, appVisible = true))
    }
}
