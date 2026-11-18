package app.overdrive.core.ride

import app.overdrive.core.device.DeviceDisconnect
import app.overdrive.core.device.SimulatedBike
import app.overdrive.core.device.SimulatedDevice
import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.record.InMemoryRideRecorder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ride engine playing `contracts/fixtures/workouts/valid/sweet-spot-3x10.json` through ERG in virtual time
 * (acceptance criteria 2 and 4, spec §4.10): which target the trainer is told when, and what happens when the
 * trainer drops in the middle of an effort.
 *
 * The workout, at FTP 250 W: warm-up ramp 0 to 600 s (45% to 70%), three times 600 s at 88% then 300 s at 50%
 * (600 to 3300 s), a free ride 3300 to 3600 s, a cool-down ramp 3600 to 3900 s (55% to 40%).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SweetSpotEngineTest {
    private val workout = workoutFixture("sweet-spot-3x10.json")
    private val ftp = 250
    private val settings = RideSettings(ftp = ftp)
    private val physics = PhysicsSettings()

    private fun target(watts: Int) = TrainerCommand.TargetPower(watts)

    private val flat =
        TrainerCommand.Simulation(
            SimulationParameters(0.0, physics.crr, physics.windResistanceCoefficient),
        )

    /** Rounds `numerator / denominator` half up, in integers, so the expectation has no floating-point in it. */
    private fun roundedDiv(
        numerator: Long,
        denominator: Long,
    ): Int = ((2 * numerator + denominator) / (2 * denominator)).toInt()

    /** The ERG target in watts at second [k] of the workout, from the class comment's table; null in the free ride. */
    private fun expectedWatts(k: Int): Int? =
        when {
            // Warm-up: (45% + 25% * k / 600) of 250 W.
            k < 600 -> roundedDiv(ftp * (45L * 600 + 25L * k), 100L * 600)

            // Three sweet-spot efforts of 600 s at 88%, each followed by 300 s at 50%.
            k < 3300 -> if ((k - 600) % 900 < 600) roundedDiv(ftp * 88L, 100) else roundedDiv(ftp * 50L, 100)

            k < 3600 -> null

            // Cool-down: (55% - 15% * t / 300) of 250 W.
            else -> roundedDiv(ftp * (55L * 300 - 15L * (k - 3600)), 100L * 300)
        }

    private fun expectedCommand(k: Int): TrainerCommand = expectedWatts(k)?.let(::target) ?: flat

    @Test
    fun `the trainer is told the right target at the right second`() =
        runTest {
            val rig = EngineRig(this, workout = workout, settings = settings)
            advanceSeconds(3) // some time in READY: nothing is held yet
            val start = testScheduler.currentTime
            rig.start()
            rig.followTrainer(3900)
            assertEquals(RideState.FINISHED, rig.now.state)

            val log = rig.trainer.received
            assertEquals(TrainerCommand.RequestControl, log.first().command, "control is requested once, first")
            assertEquals(1, log.count { it.command == TrainerCommand.RequestControl })
            val commands = log.filter { it.command != TrainerCommand.RequestControl }
            assertEquals(target(0), commands.first().command, "before the start the trainer is not held")

            fun commandAt(second: Int) = commands.last { it.timeMillis <= start + second * 1_000L }.command
            for (k in 0 until 3900) assertEquals(expectedCommand(k), commandAt(k), "second $k of the workout")

            // Spot checks that do not depend on the formula above.
            assertEquals(target(113), commandAt(0)) // 45% of 250 W is 112.5 W
            assertEquals(target(175), commandAt(599)) // 69.9%
            assertEquals(target(220), commandAt(600)) // 88%
            assertEquals(target(220), commandAt(1199))
            assertEquals(target(125), commandAt(1200)) // 50%
            assertEquals(target(220), commandAt(1500))
            assertEquals(target(220), commandAt(2400))
            assertEquals(target(125), commandAt(3000))
            assertEquals(flat, commandAt(3300))
            assertEquals(flat, commandAt(3599))
            assertEquals(target(138), commandAt(3600)) // 55% is 137.5 W
            assertEquals(target(100), commandAt(3899)) // 40.05% is 100.1 W

            // No command repeats the one before it, and the ride ends with the trainer on the flat.
            assertTrue(commands.zipWithNext().none { (a, b) -> a.command == b.command })
            assertEquals(flat, commands.last().command)
            assertEquals(start + 3900_000L, commands.last().timeMillis)
            assertEquals(1, commands.count { it.command == target(220) && it.timeMillis == start + 600_000L })
            assertEquals(3, commands.count { it.command == target(220) }, "one command for each effort")
        }

    @Test
    fun `the record, the laps and the snapshots follow the same table`() =
        runTest {
            val rig = EngineRig(this, workout = workout, settings = settings)
            rig.start()
            rig.followTrainer(3900)

            val record = rig.record
            assertEquals(3900, record.samples.size)
            for ((index, sample) in record.samples.withIndex()) {
                // The sample of second index + 1 describes the second that began at workout second index.
                assertEquals(expectedWatts(index), sample.targetPower, "sample ${index + 1}")
                assertEquals(if (index in 3300 until 3600) 12 else null, sample.gear, "gear in sample ${index + 1}")
            }
            for (snapshot in rig.snapshots.filter { it.state == RideState.RIDING }) {
                assertEquals(
                    expectedWatts(snapshot.elapsedSeconds),
                    snapshot.targetPower,
                    "target at ${snapshot.elapsedSeconds}",
                )
            }

            assertEquals(9, record.laps.size)
            assertEquals(listOf(600, 600, 300, 600, 300, 600, 300, 300, 300), record.laps.map { it.durationSeconds })
            assertEquals((0..8).toList(), record.laps.map { it.stepIndex })
            assertEquals(3900, record.laps.last().endSeconds)
            assertEquals(220.0, record.laps[1].averagePower!!, 1.0, "the rider follows the target")
            assertEquals(110.0, record.laps[7].averagePower!!, 1e-9, "and floats at 110 W in the free ride")

            val summary = record.summary
            assertEquals(3900, summary.durationSeconds)
            assertEquals(3900, summary.movingSeconds)
            assertTrue(summary.normalizedPower!! > summary.averagePower!!, "the efforts make it variable")
            assertTrue(summary.intensityFactor!! in 0.6..0.9, "got ${summary.intensityFactor}")
            assertTrue(summary.trainingStressScore!! in 40.0..90.0, "got ${summary.trainingStressScore}")
        }

    @Test
    fun `unplugging the trainer mid-effort pauses the workout, which then continues from the same second`() =
        runTest {
            val bike =
                SimulatedBike(
                    backgroundScope,
                    ftp = ftp,
                    scenario = SimulationScenario(noise = 0.0),
                    events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 800, durationSeconds = 30)),
                    clock = { testScheduler.currentTime },
                )
            val recorder = InMemoryRideRecorder()
            val engine =
                RideEngine(backgroundScope, bike.trainer, bike.heartRate, workout, settings, recorder = recorder)
            bike.start()
            settle()
            engine.start()
            settle()

            advanceSeconds(799)
            val before = engine.snapshots.value
            assertEquals(RideState.RIDING, before.state)
            assertEquals(1, before.workout!!.stepIndex, "200 s into the first sweet-spot effort")
            assertEquals(799, before.workout.workoutElapsedSeconds)
            assertEquals(220, before.targetPower)

            advanceSeconds(1)
            val during = engine.snapshots.value
            assertEquals(RideState.PAUSED, during.state)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, during.pauseReason)
            advanceSeconds(29)
            assertEquals(799, engine.snapshots.value.workout!!.workoutElapsedSeconds, "the workout stands still")
            assertEquals(PauseReason.TRAINER_DISCONNECTED, engine.snapshots.value.pauseReason)

            advanceSeconds(1) // second 830: the trainer is back
            assertEquals(RideState.RIDING, engine.snapshots.value.state)
            assertEquals(800, engine.snapshots.value.workout!!.workoutElapsedSeconds, "it continues from where it was")
            assertEquals(null, engine.snapshots.value.pauseReason)

            // The trainer was commanded again: control first, then the effort's target ramped in over 3 s. The first
            // step of the ramp is already due at the second of the reconnect, so the 0 W of the pause is skipped.
            fun sentSince(millis: Long) = bike.trainer.received.filter { it.timeMillis >= millis }.map { it.command }
            assertEquals(listOf(TrainerCommand.RequestControl, target(73)), sentSince(830_000L))
            advanceSeconds(2)
            assertEquals(
                listOf(TrainerCommand.RequestControl, target(73), target(147), target(220)),
                sentSince(830_000L),
            )
            assertEquals(220, bike.trainer.targetPowerWatts)

            // Finish the workout: the 30 s outage are 30 extra seconds in the record, as gaps, inside the same lap.
            advanceSeconds(3930 - 832)
            assertEquals(RideState.FINISHED, engine.snapshots.value.state)
            val record = recorder.record!!
            assertEquals(3930, record.samples.size)
            assertEquals((800..829).toList(), record.samples.filter { it.paused }.map { it.timeOffsetSeconds })
            assertTrue(record.samples.filter { it.paused }.all { it.power == null && it.cadence == null })
            assertEquals(9, record.laps.size, "the pause does not split a lap")
            assertEquals(630, record.laps[1].durationSeconds)
            assertEquals(3900, record.summary.movingSeconds)
            assertEquals(3930, record.summary.durationSeconds)
        }

    @Test
    fun `a sixty-minute free ride runs to the end without losing state`() =
        runTest {
            val bike = SimulatedBike(backgroundScope, scenario = SimulationScenario(), seed = 7)
            val recorder = InMemoryRideRecorder()
            val engine =
                RideEngine(
                    backgroundScope,
                    bike.trainer,
                    bike.heartRate,
                    settings = RideSettings(ftp = 200),
                    recorder = recorder,
                )
            val seen = record(engine)
            bike.start()
            settle()
            engine.start()
            settle()
            advanceSeconds(3600)

            assertEquals(RideState.RIDING, engine.snapshots.value.state)
            assertEquals(3600, engine.snapshots.value.elapsedSeconds)
            assertTrue(
                seen.filter { it.state != RideState.IDLE && it.state != RideState.READY }.all {
                    it.state ==
                        RideState.RIDING
                },
            )
            engine.finish()
            settle()
            val record = recorder.record!!
            assertEquals(RideState.FINISHED, engine.snapshots.value.state)
            assertEquals(3600, record.samples.size)
            assertTrue(record.samples.none { it.paused })
            assertTrue(record.samples.all { it.power != null && it.heartRate != null && it.cadence != null })
            assertEquals(1, record.laps.size)
            assertTrue(record.summary.distanceMeters in 15_000.0..30_000.0, "got ${record.summary.distanceMeters}")
            assertTrue(record.rrIntervals.size > 3000)
        }
}
