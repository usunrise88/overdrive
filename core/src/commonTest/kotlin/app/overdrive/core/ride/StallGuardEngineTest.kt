package app.overdrive.core.ride

import app.overdrive.core.record.RideEvent
import app.overdrive.core.record.RideEventType
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun steady(
    vararg targets: Pair<Int, Double>,
    free: Int? = null,
) = Workout(
    1,
    "guard",
    "Guard",
    steps =
        targets.map { (seconds, fraction) -> SteadyStep(duration = seconds, target = fraction) } +
            listOfNotNull(free?.let { FreeStep(duration = it) }),
)

private const val GOOD = 90
private const val STALLING = 40

/** The stall guard in the engine (spec §11.1, docs/adr/0033): ERG at FTP 200, a step of 160 W unless a test says otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class StallGuardEngineTest {
    private fun ride(
        scope: kotlinx.coroutines.test.TestScope,
        workout: Workout? = steady(600 to 0.8),
        settings: RideSettings = RideSettings(ftp = 200),
    ) = EngineRig(scope, workout = workout, settings = settings, withStrap = false).also { it.start() }

    private fun EngineRig.pedal(
        count: Int,
        cadence: Int?,
    ) = repeat(count) { second(power = 150, cadence = cadence, bpm = null) }

    @Test
    fun `three seconds below the floor lower the target to 70 percent and record an event`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            assertEquals(160, rig.trainer.targetPowerWatts)
            assertFalse(rig.now.stallGuardActive)

            rig.pedal(2, STALLING)
            assertEquals(160, rig.trainer.targetPowerWatts, "two seconds are not a stall")
            rig.pedal(1, STALLING)

            assertEquals(112, rig.trainer.targetPowerWatts, "70% of 160 W")
            assertTrue(rig.now.stallGuardActive)
            assertEquals(112, rig.now.stallGuardWatts)
            assertEquals(160, rig.now.targetPower, "the step still asks for 160 W; the snapshot shows both")
            assertEquals(
                listOf(RideEvent(RideEventType.STALL_GUARD, timeOffsetSeconds = 13, stepIndex = 0)),
                rig.record.events,
            )
        }

    @Test
    fun `it holds while the cadence stays low and the plan in the record is not the guard's`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(30, STALLING)
            assertEquals(112, rig.trainer.targetPowerWatts)
            assertEquals(1, rig.record.events.size, "one activation, not one a second")
            assertTrue(rig.record.samples.all { it.targetPower == 160 }, "the record keeps the workout's target")
        }

    @Test
    fun `it lets go after 5 s above the floor plus 10 then the target climbs back over 5 s`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(3, STALLING)
            rig.pedal(4, 80)
            assertEquals(112, rig.trainer.targetPowerWatts, "4 s above 65 rpm is not enough")
            rig.pedal(1, 80)
            assertTrue(rig.now.stallGuardActive, "released, climbing back")
            val climb = mutableListOf<Int>()
            repeat(5) {
                rig.pedal(1, 80)
                climb.add(checkNotNull(rig.trainer.targetPowerWatts))
            }
            assertEquals(listOf(122, 131, 141, 150, 160), climb)
            assertFalse(rig.now.stallGuardActive)
            assertNull(rig.now.stallGuardWatts)
            assertEquals(1, rig.record.events.size)
        }

    @Test
    fun `the guard scales the target with the intensity setting`() =
        runTest {
            val rig = ride(this)
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(168, rig.trainer.targetPowerWatts, "105%")
            rig.pedal(10, GOOD)
            rig.pedal(3, STALLING)
            assertEquals(118, rig.trainer.targetPowerWatts, "70% of 168 W, rounded")
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(
                123,
                rig.trainer.targetPowerWatts,
                "70% of 176 W: the guard follows the intensity while it holds",
            )
        }

    @Test
    fun `a step change while it holds is limited by the guard too`() =
        runTest {
            val rig = ride(this, steady(15 to 0.8, 600 to 1.0))
            rig.pedal(10, GOOD)
            rig.pedal(3, STALLING)
            assertEquals(112, rig.trainer.targetPowerWatts)
            rig.pedal(3, STALLING)
            assertEquals(140, rig.trainer.targetPowerWatts, "70% of the next step's 200 W")
            assertEquals(1, rig.record.events.size)
        }

    @Test
    fun `an event is recorded in the step it happened in`() =
        runTest {
            val rig = ride(this, steady(20 to 0.6, 600 to 0.8))
            rig.pedal(22, GOOD)
            rig.pedal(3, STALLING)
            assertEquals(1, rig.record.events.single().stepIndex)
        }

    // region when it must not fire

    @Test
    fun `a missing cadence is not a low cadence`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(30, null)
            assertEquals(160, rig.trainer.targetPowerWatts)
            assertTrue(rig.record.events.isEmpty())
        }

    @Test
    fun `at 0 rpm the auto-pause wins and the guard stays out of it`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(3, 0)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            assertFalse(rig.now.stallGuardActive)
            assertTrue(rig.record.events.isEmpty(), "a stop is not a stall")
        }

    @Test
    fun `with auto-pause off a stop is a stall like any low cadence`() =
        runTest {
            val rig = ride(this, settings = RideSettings(ftp = 200, autoPause = false))
            rig.pedal(10, GOOD)
            rig.pedal(3, 0)
            assertTrue(rig.now.stallGuardActive)
            assertEquals(1, rig.record.events.size)
        }

    @Test
    fun `a rider who restarts after an auto-pause is not judged`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(3, 0)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            // The first strokes are slow, and stay slow for 20 s: nothing to guard against yet, the rider is getting going.
            rig.pedal(20, 30)
            assertEquals(RideState.RIDING, rig.now.state)
            assertFalse(rig.now.stallGuardActive)
            assertTrue(rig.record.events.isEmpty())
        }

    @Test
    fun `after a manual resume the first seconds are not judged even for a rider who was up to speed`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.engine.pause()
            rig.settle()
            rig.pedal(5, GOOD)
            rig.engine.resume()
            rig.settle()
            assertEquals(0, rig.now.secondsSinceResume)
            // Pedalling hard enough to arm the guard, then dropping straight away: inside the grace nothing counts.
            rig.pedal(1, GOOD)
            rig.pedal(3, STALLING)
            assertFalse(rig.now.stallGuardActive, "4 s after the resume")
            rig.pedal(3, STALLING)
            assertTrue(rig.now.stallGuardActive, "the grace is over and 3 s in a row were low")
        }

    @Test
    fun `a pause forgets a guard that had hold and the ramp-in goes to the full target`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(3, STALLING)
            assertEquals(112, rig.trainer.targetPowerWatts)
            rig.engine.pause()
            rig.settle()
            assertFalse(rig.now.stallGuardActive)
            assertEquals(0, rig.trainer.targetPowerWatts, "paused: 0 W as always")
            rig.engine.resume()
            rig.settle()
            assertEquals(0, rig.trainer.targetPowerWatts, "the ramp-in starts at the hold")
            rig.pedal(3, GOOD)
            assertEquals(160, rig.trainer.targetPowerWatts, "not 112: the guard starts again unarmed")
        }

    @Test
    fun `turned off it never lowers a target`() =
        runTest {
            val rig =
                ride(this, settings = RideSettings(ftp = 200, stallGuard = StallGuardSettings(enabled = false)))
            rig.pedal(10, GOOD)
            rig.pedal(40, STALLING)
            assertEquals(160, rig.trainer.targetPowerWatts)
            assertFalse(rig.now.stallGuardActive)
            assertTrue(rig.record.events.isEmpty())
        }

    @Test
    fun `a free-ride step has no guard`() =
        runTest {
            val rig = ride(this, steady(20 to 0.8, free = 600))
            rig.pedal(15, GOOD)
            // The guard takes hold in the ERG step (at 18 s), and lets go of everything when the free step begins at 20 s.
            rig.pedal(10, STALLING)
            assertEquals(TrainerMode.SIMULATION, rig.now.mode)
            assertFalse(rig.now.stallGuardActive)
            rig.pedal(30, STALLING)
            assertFalse(rig.now.stallGuardActive)
            assertEquals(null, rig.now.stallGuardWatts)
            assertTrue(rig.record.events.none { it.stepIndex == 1 }, "nothing is recorded in the free step")
        }

    @Test
    fun `a ride without a workout has no guard`() =
        runTest {
            val rig = ride(this, workout = null)
            rig.pedal(10, GOOD)
            rig.pedal(30, STALLING)
            assertFalse(rig.now.stallGuardActive)
            assertTrue(rig.record.events.isEmpty())
        }

    // endregion

    @Test
    fun `a stall that comes again after the guard let go is a second event`() =
        runTest {
            val rig = ride(this)
            rig.pedal(10, GOOD)
            rig.pedal(3, STALLING)
            rig.pedal(10, 80)
            assertFalse(rig.now.stallGuardActive)
            rig.pedal(3, STALLING)
            assertTrue(rig.now.stallGuardActive)
            assertEquals(listOf(13, 26), rig.record.events.map { it.timeOffsetSeconds })
        }
}
