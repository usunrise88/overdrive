package app.overdrive.ride.overlay

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideUi
import app.overdrive.uikit.overlay.OverlayControl
import app.overdrive.uikit.overlay.OverlayUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay's input: distinct, rate limited, with the rider's own actions shown at once (spec §4.6, §7.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayUpdatesTest {
    private fun snapshot(
        power: Int? = 200,
        elapsed: Int = 100,
        gear: Int? = 12,
        state: RideState = RideState.RIDING,
    ) = RideSnapshot(
        state = state,
        ftp = 250,
        elapsedSeconds = elapsed,
        power = power,
        heartRate = 140,
        cadence = 90,
        mode = TrainerMode.SIMULATION,
        gear = gear,
    )

    private fun ride(
        snapshot: RideSnapshot,
        averagePower: Int? = null,
        number: Int = 1,
    ) = RideUi(snapshot, RideChoice.Free, null, averagePower, null, emptyList(), null, number)

    private fun TestScope.collectStates(rides: MutableStateFlow<RideUi>): MutableList<Pair<Long, OverlayUiState>> {
        val seen = mutableListOf<Pair<Long, OverlayUiState>>()
        backgroundScope.launch {
            overlayStates(
                rides,
                now = { testScheduler.currentTime },
            ).collect { seen += testScheduler.currentTime to it }
        }
        runCurrent()
        return seen
    }

    @Test
    fun manySnapshotsThatShowTheSameNumbersProduceOneUpdate() =
        runTest {
            val rides = MutableStateFlow(ride(snapshot()))
            val seen = collectStates(rides)
            assertEquals(1, seen.size)

            // The engine publishes a snapshot for every sensor reading; the averages and the ride number are not on
            // the overlay, so none of these changes anything it shows.
            repeat(50) { i ->
                rides.value = ride(snapshot(), averagePower = 100 + i, number = 2 + i)
                advanceTimeBy(100)
                runCurrent()
            }
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, seen.size)
        }

    @Test
    fun theFirstValueGoesThroughAtOnce() =
        runTest {
            val seen = collectStates(MutableStateFlow(ride(snapshot(power = 150))))
            assertEquals(1, seen.size)
            assertEquals(0L, seen.single().first)
            assertEquals(150, seen.single().second.power)
        }

    @Test
    fun valuesThatChangeContinuouslyAreSpacedAtLeastASecondApart() =
        runTest {
            val rides = MutableStateFlow(ride(snapshot(power = 200)))
            val seen = collectStates(rides)

            // A power reading every 250 ms for five seconds, like a trainer: never more than one update a second.
            repeat(20) { i ->
                advanceTimeBy(250)
                rides.value = ride(snapshot(power = 201 + i))
                runCurrent()
            }
            advanceTimeBy(2_000)
            runCurrent()

            val times = seen.map { it.first }
            for (gap in times.zipWithNext { a, b -> b - a }) {
                assertTrue("updates $gap ms apart: $times", gap >= OverlayPolicy.MIN_UPDATE_INTERVAL_MS)
            }
            // ...and nothing is lost: the last reading arrives.
            assertEquals(220, seen.last().second.power)
        }

    @Test
    fun aLaterValueReplacesOneThatWasStillWaiting() =
        runTest {
            val rides = MutableStateFlow(ride(snapshot(power = 200)))
            val seen = collectStates(rides)
            advanceTimeBy(100)
            rides.value = ride(snapshot(power = 210))
            runCurrent()
            advanceTimeBy(100)
            rides.value = ride(snapshot(power = 220))
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(200, 220), seen.map { it.second.power })
        }

    @Test
    fun theRidersOwnActionsAreShownAtOnce() =
        runTest {
            val rides = MutableStateFlow(ride(snapshot(gear = 12)))
            val seen = collectStates(rides)
            advanceTimeBy(100)
            rides.value = ride(snapshot(gear = 13)) // a gear press 100 ms after the last update
            runCurrent()
            assertEquals(2, seen.size)
            assertEquals(100L, seen.last().first)
            assertEquals(OverlayControl.Gear(13), seen.last().second.control)

            advanceTimeBy(50)
            rides.value = ride(snapshot(gear = 13, state = RideState.PAUSED)) // pause
            runCurrent()
            assertEquals(3, seen.size)
            assertEquals(true, seen.last().second.paused)
        }

    @Test
    fun anUnchangedStateAfterASlowChangeDoesNotRepeat() =
        runTest {
            val rides = MutableStateFlow(ride(snapshot(power = 200)))
            val seen = collectStates(rides)
            advanceTimeBy(100)
            rides.value = ride(snapshot(power = 210))
            runCurrent()
            advanceTimeBy(100)
            rides.value = ride(snapshot(power = 200)) // back to what was last shown, before the 210 got its turn
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(listOf(200), seen.map { it.second.power })
        }
}
