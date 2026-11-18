package app.overdrive.core.ride

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A whole simulated ride of `contracts/fixtures/workouts/valid/sweet-spot-3x10.json` in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SweetSpotRideTest {
    private val workout = workoutFixture("sweet-spot-3x10.json")
    private val flat = workout.flatSteps()

    @Test
    fun `the ride finishes after exactly the workout duration`() =
        runTest {
            val session = newSession(workout = workout, scenario = SimulationScenario(), ftp = 250, seed = 11)
            val seen = record(session)

            ride(session, 3899)
            assertEquals(RideState.RIDING, session.now.state)
            assertEquals(3899, session.now.elapsedSeconds)
            assertEquals(1, session.now.workout!!.workoutRemainingSeconds)

            advanceSeconds(1)
            with(session.now) {
                assertEquals(RideState.FINISHED, state)
                assertEquals(3900, elapsedSeconds)
                assertEquals(0, workout!!.workoutRemainingSeconds)
            }

            // Nothing is published after the end, however long we wait.
            val countAtEnd = seen.size
            advanceSeconds(600)
            assertEquals(countAtEnd, seen.size)
            assertEquals(RideState.FINISHED, seen.last().state)
            assertEquals(1, seen.count { it.state == RideState.FINISHED })
        }

    @Test
    fun `the steps are passed in order and each lasts its duration`() =
        runTest {
            val session = newSession(workout = workout, scenario = SimulationScenario(), seed = 3)
            val seen = record(session)
            ride(session, 3900)

            val riding = seen.filter { it.state == RideState.RIDING }
            // One snapshot per second of ride time, from 0 to 3899, and then the finished one.
            assertEquals((0..3899).toList(), riding.map { it.elapsedSeconds })
            assertEquals(RideState.FINISHED, seen.last().state)

            val indexes = riding.map { it.workout!!.stepIndex }
            assertEquals(indexes.sorted(), indexes, "steps never go backwards")
            assertEquals(flat.indices.toList(), indexes.distinct(), "every step is visited, in order")
            for ((index, step) in flat.withIndex()) {
                assertEquals(step.durationSeconds, indexes.count { it == index }, "snapshots in ${step.label} ($index)")
            }

            for (snapshot in riding) {
                val progress = snapshot.workout!!
                val step = flat[progress.stepIndex]
                assertEquals(step.label, progress.stepLabel)
                assertEquals(step.mode, snapshot.mode)
                assertEquals(snapshot.elapsedSeconds, progress.workoutElapsedSeconds, "no skips, so the clocks agree")
                assertEquals(3900 - snapshot.elapsedSeconds, progress.workoutRemainingSeconds)
                assertEquals(snapshot.elapsedSeconds - step.startSeconds, progress.stepElapsedSeconds)
                assertEquals(step.durationSeconds - progress.stepElapsedSeconds, progress.stepRemainingSeconds)
                assertEquals(flat.getOrNull(progress.stepIndex + 1)?.label, progress.nextStepLabel)
                assertEquals(step.mode == TrainerMode.ERG, snapshot.targetPower != null)
                assertEquals(step.mode == TrainerMode.SIMULATION, snapshot.gear != null)
            }
        }

    @Test
    fun `the targets follow the workout`() =
        runTest {
            val session = newSession(workout = workout, ftp = 240)
            val seen = record(session)
            ride(session, 3899)
            val riding = seen.filter { it.state == RideState.RIDING }

            fun at(second: Int) = riding.first { it.elapsedSeconds == second }

            assertEquals(108, at(0).targetPower) // warm-up starts at 45% of 240 W
            assertEquals(168, at(599).targetPower) // and ends just under 70%
            assertEquals(211, at(600).targetPower) // sweet spot, 88%
            assertEquals(120, at(1200).targetPower) // recovery, 50%
            assertEquals(null, at(3300).targetPower) // the free ride
            assertEquals(132, at(3600).targetPower) // cool-down starts at 55%
            // After a few minutes at 88% the quiet rider is on target.
            assertEquals(211, at(1100).power)
            assertEquals(PowerZone.Z3, at(1100).zone) // 88% of FTP
        }

    @Test
    fun `the ride visibly exercises the whole signal range`() =
        runTest {
            val session = newSession(workout = workout, scenario = SimulationScenario(), ftp = 250, seed = 5)
            val seen = record(session)
            ride(session, 3899)
            val riding = seen.filter { it.state == RideState.RIDING && it.elapsedSeconds > 0 }
            assertTrue(riding.all { it.power != null && it.heartRate != null && it.cadence != null })
            assertTrue(riding.map { it.zone }.toSet().size >= 3)
            assertTrue(riding.last().distanceMeters > 20_000, "an hour of riding covers a plausible distance")
            assertTrue(riding.maxOf { it.heartRate!! } in 140..185)
        }
}
