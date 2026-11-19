package app.overdrive.core.ride

import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The state machine of [RideEngine]: states, commands, auto-pause, manual pause and the trainer's link. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineStateTest {
    private fun TestScope.rigIn(state: RideState): EngineRig =
        when (state) {
            RideState.IDLE -> {
                EngineRig(this, connectTrainer = false)
            }

            RideState.READY -> {
                EngineRig(this)
            }

            RideState.RIDING -> {
                EngineRig(this).also {
                    it.start()
                    it.seconds(5)
                }
            }

            RideState.PAUSED -> {
                EngineRig(this).also {
                    it.start()
                    it.seconds(5)
                    it.engine.pause()
                    it.settle()
                }
            }

            RideState.FINISHED -> {
                EngineRig(this).also {
                    it.start()
                    it.seconds(5)
                    it.engine.finish()
                    it.settle()
                }
            }
        }

    private fun RideSession.commands(): Map<String, () -> Unit> =
        mapOf(
            "start" to ::start,
            "pause" to ::pause,
            "resume" to ::resume,
            "finish" to ::finish,
            "nextInterval" to ::nextInterval,
            "previousInterval" to ::previousInterval,
            "gearUp" to ::gearUp,
            "gearDown" to ::gearDown,
            "intensityUp" to ::intensityUp,
            "intensityDown" to ::intensityDown,
        )

    @Test
    fun `the engine is idle until the trainer connects, then ready with no sensor data`() =
        runTest {
            val rig = EngineRig(this, connectTrainer = false)
            assertEquals(RideState.IDLE, rig.now.state)

            rig.trainer.connect()
            rig.settle()
            with(rig.now) {
                assertEquals(RideState.READY, state)
                assertEquals(0, elapsedSeconds)
                assertNull(power)
                assertNull(heartRate)
                assertNull(cadence)
                assertNull(zone)
                assertNull(pauseReason)
                assertNull(workout)
                assertNull(targetPower)
                assertEquals(0.0, speedKmh)
                assertEquals(0.0, distanceMeters)
                assertEquals(100, intensityPercent)
                assertEquals(200, ftp)
                assertEquals(TrainerMode.SIMULATION, mode)
                assertEquals(12, gear)
            }

            rig.trainer.emit(power = 150, cadence = 80)
            rig.strap.emit(120)
            rig.settle()
            assertNull(rig.now.power, "sensor values stay null until the ride starts")
            assertNull(rig.now.heartRate)

            rig.trainer.disconnect()
            rig.settle()
            assertEquals(RideState.IDLE, rig.now.state)
            rig.trainer.connect()
            rig.settle()
            assertEquals(RideState.READY, rig.now.state)
        }

    @Test
    fun `the strap is not needed to ride`() =
        runTest {
            val rig = EngineRig(this, withStrap = false)
            rig.start()
            rig.seconds(3, bpm = null)
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.heartRate)
            assertEquals(3, rig.now.elapsedSeconds)
        }

    @Test
    fun `lifecycle commands move through the states`() {
        val expected =
            mapOf(
                RideState.IDLE to listOf(RideState.IDLE, RideState.IDLE, RideState.IDLE, RideState.IDLE),
                RideState.READY to listOf(RideState.RIDING, RideState.READY, RideState.READY, RideState.READY),
                RideState.RIDING to listOf(RideState.RIDING, RideState.PAUSED, RideState.RIDING, RideState.FINISHED),
                RideState.PAUSED to listOf(RideState.PAUSED, RideState.PAUSED, RideState.RIDING, RideState.FINISHED),
                RideState.FINISHED to
                    listOf(RideState.FINISHED, RideState.FINISHED, RideState.FINISHED, RideState.FINISHED),
            )
        for ((from, results) in expected) {
            for ((index, name) in listOf("start", "pause", "resume", "finish").withIndex()) {
                runTest {
                    val rig = rigIn(from)
                    assertEquals(from, rig.now.state, "setup for $from")
                    rig.engine.commands().getValue(name)()
                    rig.settle()
                    assertEquals(results[index], rig.now.state, "$name in $from")
                }
            }
        }
    }

    @Test
    fun `every command is a no-op while idle`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout, connectTrainer = false)
            val before = rig.now
            rig.engine.commands().values.forEach { it() }
            rig.settle()
            advanceSeconds(10)
            assertEquals(before, rig.now)
            assertTrue(rig.trainer.received.isEmpty(), "a trainer that is not connected is not commanded")
        }

    @Test
    fun `every command is a no-op after the ride is finished`() =
        runTest {
            val rig = rigIn(RideState.FINISHED)
            val before = rig.now
            rig.engine.commands().values.forEach { it() }
            rig.settle()
            advanceSeconds(10)
            assertEquals(before, rig.now)
        }

    @Test
    fun `the ride clock ticks once a second and every second is published`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            assertEquals(0, rig.now.elapsedSeconds)
            rig.seconds(10)
            assertEquals(10, rig.now.elapsedSeconds)
            assertEquals(
                (0..10).toList(),
                rig.snapshots.filter { it.state == RideState.RIDING }.map { it.elapsedSeconds },
            )
        }

    @Test
    fun `a manual pause stops the clock but the seconds are still recorded`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5)
            rig.engine.pause()
            rig.settle()
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(PauseReason.MANUAL, rig.now.pauseReason)

            rig.seconds(3)
            assertEquals(5, rig.now.elapsedSeconds)
            assertEquals(8, rig.record.samples.size)
            assertEquals(
                listOf(false, false, false, false, false, true, true, true),
                rig.record.samples.map { it.paused },
            )

            rig.engine.resume()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.pauseReason)
            rig.second()
            assertEquals(6, rig.now.elapsedSeconds)
            assertEquals(9, rig.record.samples.size)
        }

    @Test
    fun `three seconds of zero cadence pause the ride and pedalling resumes it`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5)
            rig.second(cadence = 0, power = 0)
            rig.second(cadence = 0, power = 0)
            assertEquals(RideState.RIDING, rig.now.state, "two seconds are not enough")
            rig.second(cadence = 0, power = 0)
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            assertEquals(8, rig.now.elapsedSeconds, "the three seconds of stopping were still riding time")

            rig.second(cadence = 0, power = 0)
            rig.second(cadence = 0, power = 0)
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(8, rig.now.elapsedSeconds)

            rig.second(cadence = 60) // pedalling again, noticed on a notification, not at a tick
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.pauseReason)
            assertEquals(9, rig.now.elapsedSeconds, "the second in which pedalling restarted is riding time")
            rig.second()
            assertEquals(10, rig.now.elapsedSeconds)

            assertEquals(
                List(8) { false } + List(2) { true } + false + false,
                rig.record.samples.map { it.paused },
                "seconds 9 and 10 were paused; a second is paused or riding by the state at the moment of its tick",
            )
        }

    @Test
    fun `auto-pause can be turned off`() =
        runTest {
            val rig = EngineRig(this, settings = RideSettings(ftp = 200, autoPause = false))
            rig.start()
            rig.seconds(20, power = 0, cadence = 0)
            assertEquals(RideState.RIDING, rig.now.state)
            assertEquals(20, rig.now.elapsedSeconds)
        }

    @Test
    fun `the auto-pause delay is a setting`() =
        runTest {
            val rig = EngineRig(this, settings = RideSettings(ftp = 200, autoPauseAfterSeconds = 5))
            rig.start()
            rig.seconds(4, cadence = 0, power = 0)
            assertEquals(RideState.RIDING, rig.now.state)
            rig.second(cadence = 0, power = 0)
            assertEquals(RideState.PAUSED, rig.now.state)
        }

    @Test
    fun `a pedal stroke in between restarts the count`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            for (cadence in listOf(0, 0, 80, 0, 0, 0)) rig.second(cadence = cadence)
            assertEquals(RideState.PAUSED, rig.now.state, "three zeros in a row after the stroke")

            val other = EngineRig(this)
            other.start()
            for (cadence in listOf(0, 0, 80, 0, 0, 80, 0, 0)) other.second(cadence = cadence)
            assertEquals(RideState.RIDING, other.now.state)
        }

    @Test
    fun `a missing cadence reading is not zero cadence`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(10, power = 150, cadence = null)
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.cadence)
        }

    @Test
    fun `a manual pause overrides auto-resume, only resume ends it`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3)
            rig.engine.pause()
            rig.settle()
            rig.seconds(5, cadence = 90)
            assertEquals(RideState.PAUSED, rig.now.state, "pedalling does not lift a manual pause")
            assertEquals(PauseReason.MANUAL, rig.now.pauseReason)
            rig.seconds(5, cadence = 0, power = 0)
            assertEquals(PauseReason.MANUAL, rig.now.pauseReason, "and stopping does not turn it into an auto-pause")
            rig.engine.resume()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `a manual pause while auto-paused changes nothing, pedalling still resumes`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3, cadence = 0, power = 0)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            rig.engine.pause()
            rig.settle()
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            rig.second(cadence = 80)
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `resume while auto-paused resumes at once and the count starts over`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3, cadence = 0, power = 0)
            assertEquals(RideState.PAUSED, rig.now.state)
            rig.engine.resume()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
            rig.seconds(2, cadence = 0, power = 0)
            assertEquals(RideState.RIDING, rig.now.state)
            rig.second(cadence = 0, power = 0)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
        }

    @Test
    fun `pulling the trainer's plug pauses the ride and plugging it back resumes it`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(10)
            assertEquals(10, rig.now.elapsedSeconds)
            assertEquals(200, rig.now.power)

            rig.trainer.disconnect()
            rig.settle()
            assertEquals(RideState.PAUSED, rig.now.state, "at once, not at the next tick")
            assertEquals(PauseReason.TRAINER_DISCONNECTED, rig.now.pauseReason)
            assertNull(rig.now.power)
            assertNull(rig.now.cadence)
            assertEquals(0.0, rig.now.speedKmh)

            repeat(5) { rig.second(trainerSilent = true, bpm = 135) }
            assertEquals(10, rig.now.elapsedSeconds)
            assertEquals(135, rig.now.heartRate, "the strap carries on")

            rig.trainer.connect()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.pauseReason)
            rig.second()
            assertEquals(11, rig.now.elapsedSeconds, "the clock carries on where it stopped")
            assertEquals(200, rig.now.power)

            // Nothing is lost: one sample for each of the 16 seconds, the outage as paused gaps.
            val samples = rig.record.samples
            assertEquals((1..16).toList(), samples.map { it.timeOffsetSeconds })
            assertEquals(List(10) { false } + List(5) { true } + false, samples.map { it.paused })
            assertEquals(List(10) { 200 } + List(5) { null } + 200, samples.map { it.power })
            assertEquals(List(10) { 90 } + List(5) { null } + 90, samples.map { it.cadence })
            assertEquals(List(10) { 140 } + List(5) { 135 } + 140, samples.map { it.heartRate })
        }

    @Test
    fun `a trainer that never comes back leaves the ride paused until it is finished`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5)
            rig.trainer.powerOff()
            rig.settle()
            rig.seconds(60, power = null, cadence = null, bpm = 120)
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(5, rig.now.elapsedSeconds)
            assertEquals(65, rig.record.samples.size)

            rig.engine.finish()
            rig.settle()
            assertEquals(RideState.FINISHED, rig.now.state)
            assertTrue(rig.recorder.isFinished)
        }

    @Test
    fun `a trainer that drops while manually paused stays paused after it is back`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3)
            rig.engine.pause()
            rig.settle()
            rig.trainer.disconnect()
            rig.settle()
            assertEquals(PauseReason.TRAINER_DISCONNECTED, rig.now.pauseReason, "the missing trainer is what to fix")
            rig.trainer.connect()
            rig.settle()
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(PauseReason.MANUAL, rig.now.pauseReason)
            rig.engine.resume()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `resume cannot bring the ride back while the trainer is gone`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3)
            rig.trainer.disconnect()
            rig.settle()
            rig.engine.resume()
            rig.settle()
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, rig.now.pauseReason)
        }

    @Test
    fun `a link that is only connecting counts as down`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3)
            rig.trainer.startConnecting()
            rig.settle()
            assertEquals(PauseReason.TRAINER_DISCONNECTED, rig.now.pauseReason)
            rig.trainer.connect()
            rig.settle()
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `a trainer that goes quiet while connected leaves a gap but does not pause the ride`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5)
            repeat(5) { rig.second(trainerSilent = true) }
            assertEquals(RideState.RIDING, rig.now.state)
            // The last reading is reused for 3 seconds, counting its own, and then the signal is lost.
            assertEquals(List(5) { 200 } + listOf(200, 200, null, null, null), rig.record.samples.map { it.power })
            assertNull(rig.now.power)
            assertNull(rig.now.zone)
            rig.second()
            assertEquals(200, rig.now.power)
        }

    @Test
    fun `a heart-rate dropout never stops the ride`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5, bpm = 150)

            rig.strap.disconnect()
            rig.settle()
            assertNull(rig.now.heartRate, "a lost link is a gap at once")
            rig.seconds(5, bpm = null)
            assertEquals(RideState.RIDING, rig.now.state)
            assertEquals(10, rig.now.elapsedSeconds)
            assertEquals(200, rig.now.power)

            rig.strap.connect()
            rig.settle()
            rig.second(bpm = 148)
            assertEquals(148, rig.now.heartRate)
            assertEquals(List(5) { 150 } + List(5) { null } + 148, rig.record.samples.map { it.heartRate })
        }

    @Test
    fun `a strap reporting 0 bpm or going silent is a gap`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.second(bpm = 150)
            rig.second(bpm = 0)
            assertNull(rig.now.heartRate, "no contact reads 0 bpm")
            rig.second(bpm = 151)
            repeat(6) { rig.second(strapSilent = true) }
            // 151 is reused for 5 seconds, counting its own, then the signal is lost.
            assertEquals(
                listOf(150, null, 151, 151, 151, 151, 151, null, null),
                rig.record.samples.map { it.heartRate },
            )
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `a workout ride finishes by itself after the last second`() =
        runTest {
            val tenSeconds = Workout(1, "w", "W", steps = listOf(SteadyStep(duration = 10, target = 0.5)))
            val rig = EngineRig(this, workout = tenSeconds)
            rig.start()
            rig.followTrainer(9)
            assertEquals(RideState.RIDING, rig.now.state)
            assertEquals(1, rig.now.workout!!.workoutRemainingSeconds)
            rig.followTrainer(1)
            with(rig.now) {
                assertEquals(RideState.FINISHED, state)
                assertEquals(10, elapsedSeconds)
                assertEquals(0, workout!!.workoutRemainingSeconds)
                assertNull(power)
                assertNull(heartRate)
                assertNull(targetPower)
                assertNull(gear)
            }
            assertEquals(10, rig.record.samples.size)
            assertTrue(rig.recorder.isFinished)

            val count = rig.snapshots.size
            advanceSeconds(100)
            assertEquals(count, rig.snapshots.size, "nothing is published after the end")
            assertEquals(1, rig.snapshots.count { it.state == RideState.FINISHED })
        }

    @Test
    fun `a free ride goes on until it is finished`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(600)
            assertEquals(RideState.RIDING, rig.now.state)
            rig.engine.finish()
            rig.settle()
            assertEquals(RideState.FINISHED, rig.now.state)
            assertEquals(600, rig.now.elapsedSeconds)
            assertFalse(rig.record.samples.isEmpty())
        }

    @Test
    fun `next and previous interval move the workout position, not the ride clock`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(5)
            rig.engine.nextInterval()
            rig.settle()
            with(rig.now.workout!!) {
                assertEquals(1, stepIndex)
                assertEquals(60, workoutElapsedSeconds)
            }
            assertEquals(5, rig.now.elapsedSeconds)

            rig.followTrainer(10) // 10 s into step B
            rig.engine.previousInterval()
            rig.settle()
            assertEquals(1, rig.now.workout!!.stepIndex, "more than 3 s in: restart the step")
            assertEquals(60, rig.now.workout!!.workoutElapsedSeconds)

            rig.followTrainer(2)
            rig.engine.previousInterval()
            rig.settle()
            assertEquals(0, rig.now.workout!!.stepIndex, "within 3 s of the start: the step before")

            rig.engine.nextInterval()
            rig.engine.nextInterval()
            rig.settle()
            assertEquals(2, rig.now.workout!!.stepIndex)
            rig.engine.nextInterval()
            rig.settle()
            assertEquals(2, rig.now.workout!!.stepIndex, "no skipping past the last step")
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `skipping works while paused and needs a workout`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(3)
            rig.engine.pause()
            rig.engine.nextInterval()
            rig.settle()
            assertEquals(1, rig.now.workout!!.stepIndex)
            assertEquals(RideState.PAUSED, rig.now.state)

            val free = EngineRig(this)
            free.start()
            free.seconds(2)
            val before = free.now
            free.engine.nextInterval()
            free.engine.previousInterval()
            free.settle()
            assertEquals(before, free.now)
        }

    @Test
    fun `the workout progress is the same instance of steps in every snapshot`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(3)
            val steps = rig.now.workout!!.steps
            assertEquals(abcWorkout.name, rig.now.workout!!.workoutName)
            assertTrue(rig.snapshots.mapNotNull { it.workout }.all { it.steps === steps })
        }
}
