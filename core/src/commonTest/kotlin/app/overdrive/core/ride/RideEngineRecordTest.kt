package app.overdrive.core.ride

import app.overdrive.core.device.SimulatedHeartRateDevice
import app.overdrive.core.device.SimulatedTrainerDevice
import app.overdrive.core.physics.FlatPhysicsSpeedModel
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RideSummary
import app.overdrive.core.record.RrInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the engine records (spec §8.2): one sample per second, laps per step, RR intervals, the summary. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineRecordTest {
    @Test
    fun `nothing is recorded until the ride starts`() =
        runTest {
            val rig = EngineRig(this)
            advanceSeconds(10)
            assertNull(rig.recorder.record)
            assertFalse(rig.recorder.isFinished)
        }

    @Test
    fun `the header describes the ride`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout, settings = RideSettings(ftp = 230))
            advanceSeconds(7)
            rig.start()
            assertEquals(
                RideHeader(
                    id = "test-ride",
                    startEpochMillis = ENGINE_TEST_START_MILLIS + 7_000,
                    endEpochMillis = null,
                    workoutId = "abc",
                    ftp = 230,
                    devices = listOf("Simulated trainer", "Simulated heart-rate strap"),
                ),
                rig.record.ride,
            )
            rig.followTrainer(20)
            rig.engine.finish()
            rig.settle()
            assertEquals(ENGINE_TEST_START_MILLIS + 27_000, rig.record.ride.endEpochMillis)
        }

    @Test
    fun `without a strap or a workout the header says so`() =
        runTest {
            val rig = EngineRig(this, withStrap = false)
            rig.start()
            assertNull(rig.record.ride.workoutId)
            assertEquals(listOf("Simulated trainer"), rig.record.ride.devices)
        }

    @Test
    fun `the id comes from the start time unless given`() =
        runTest {
            val trainer = SimulatedTrainerDevice().also { it.connect() }
            val recorder = InMemoryRideRecorder()
            val engine = RideEngine(backgroundScope, trainer, recorder = recorder, clock = { 1_234_567L })
            settle()
            engine.start()
            settle()
            assertEquals("ride-1234567", recorder.record!!.ride.id)
        }

    @Test
    fun `a full workout gives one sample per second and one lap per step`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(180)
            assertEquals(RideState.FINISHED, rig.now.state)

            val record = rig.record
            assertEquals(180, record.samples.size)
            assertEquals((1..180).toList(), record.samples.map { it.timeOffsetSeconds })
            assertEquals(List(60) { 0 } + List(60) { 1 } + List(60) { 2 }, record.samples.map { it.stepIndex })

            assertEquals(3, record.laps.size, "abc has three steps")
            assertEquals(listOf(0, 1, 2), record.laps.map { it.stepIndex })
            assertEquals(listOf(0 to 60, 60 to 120, 120 to 180), record.laps.map { it.startSeconds to it.endSeconds })
            assertEquals(listOf(100.0, 160.0, 110.0), record.laps.map { it.averagePower })
            assertEquals(listOf(140.0, 140.0, 140.0), record.laps.map { it.averageHeartRate })

            assertEquals(180, record.summary.durationSeconds)
            assertEquals(180, record.summary.movingSeconds)
            assertEquals(true, record.ride.endEpochMillis != null)
        }

    @Test
    fun `each sample carries the target gear and step that were in force during its second`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(125) // the two ERG steps and five seconds of the free ride
            rig.engine.gearUp()
            rig.settle()
            rig.followTrainer(55)

            val samples = rig.record.samples
            // ERG steps: a target and no gear. The sample of second 60 belongs to step A even though step B starts then.
            assertEquals(listOf(100, 100, 160, 160), listOf(1, 60, 61, 120).map { samples[it - 1].targetPower })
            assertTrue(samples.take(120).all { it.gear == null })
            // The free ride: a gear and no target.
            assertTrue(samples.drop(120).all { it.targetPower == null })
            assertEquals(12, samples[124].gear)
            assertEquals(13, samples[125].gear, "the gear was raised after second 125 and is in force from the next")
            assertEquals(13, samples.last().gear)
            assertTrue(samples.all { !it.paused })
        }

    @Test
    fun `a ride without a workout is a single lap with no step`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(30, power = 150)
            val record = rig.record
            assertEquals(30, record.samples.size)
            assertTrue(record.samples.all { it.stepIndex == null && it.targetPower == null && it.gear == 12 })
            assertEquals(1, record.laps.size)
            assertNull(record.laps.single().stepIndex)
            assertEquals(0, record.laps.single().startSeconds)
            assertEquals(30, record.laps.single().endSeconds)
            assertEquals(150.0, record.laps.single().averagePower)
        }

    @Test
    fun `skipping back and forth makes a lap for each stretch in one step`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(5) // A
            rig.engine.nextInterval()
            rig.settle()
            rig.followTrainer(6) // B
            rig.engine.previousInterval() // more than 3 s in: restarts B, the lap goes on
            rig.settle()
            rig.followTrainer(2) // B again
            rig.engine.previousInterval() // within 3 s: back to A
            rig.settle()
            rig.followTrainer(4) // A again

            val record = rig.record
            assertEquals(17, record.samples.size)
            assertEquals(listOf(0, 1, 0), record.laps.map { it.stepIndex })
            assertEquals(listOf(5, 8, 4), record.laps.map { it.durationSeconds })
            assertEquals(17, record.laps.sumOf { it.durationSeconds })
        }

    @Test
    fun `gaps are nulls and the sample is still there`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5)
            rig.strap.disconnect()
            rig.settle()
            rig.second(bpm = null)
            rig.second(bpm = null, power = null, cadence = null) // the trainer says nothing
            rig.second(bpm = null, trainerSilent = true)

            val samples = rig.record.samples
            assertEquals(8, samples.size)
            assertEquals(List(5) { 140 } + List(3) { null }, samples.map { it.heartRate })
            // Power: the 6th second had a reading, the 7th a reading with no power in it, and the 8th none at all,
            // so the 7th's is reused.
            assertEquals(listOf(200, 200, 200, 200, 200, 200, null, null), samples.map { it.power })
            assertTrue(samples.none { it.paused })
        }

    @Test
    fun `the sample shows the workout's target during a pause flagged as paused`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(10)
            rig.engine.pause()
            rig.settle()
            rig.seconds(3, power = 0, cadence = 0)
            rig.engine.resume()
            rig.settle()
            rig.followTrainer(3)

            val samples = rig.record.samples
            assertEquals(List(10) { false } + List(3) { true } + List(3) { false }, samples.map { it.paused })
            assertTrue(samples.all { it.targetPower == 100 }, "the target is the workout's, not the commanded ramp")
            assertEquals(List(10) { 100 } + List(3) { 0 } + listOf(0, 33, 67), samples.map { it.power }.take(16))
        }

    @Test
    fun `speed and distance come from the physics model not from the trainer`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            val expectedSpeed = FlatPhysicsSpeedModel().speedKmh(200.0)
            repeat(10) {
                rig.trainer.emit(power = 200, cadence = 90, speedKmh = 999.0)
                advanceSeconds(1)
            }
            assertEquals(expectedSpeed, rig.now.speedKmh, 1e-9)
            assertEquals(expectedSpeed / 3.6 * 10, rig.now.distanceMeters, 1e-9)
            assertTrue(expectedSpeed in 33.0..34.0)

            val samples = rig.record.samples
            assertEquals(expectedSpeed, samples.first().speedKmh, 1e-9)
            assertEquals(expectedSpeed / 3.6, samples.first().distanceMeters, 1e-9)
            assertEquals(rig.now.distanceMeters, samples.last().distanceMeters, 1e-9)
            assertEquals(List(10) { it + 1 }, samples.map { it.timeOffsetSeconds })
        }

    @Test
    fun `no speed and no distance while paused or without power`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(5, power = 200)
            val distance = rig.now.distanceMeters
            rig.engine.pause()
            rig.settle()
            rig.seconds(5, power = 200)
            assertEquals(0.0, rig.now.speedKmh)
            assertEquals(distance, rig.now.distanceMeters)
            rig.engine.resume()
            rig.settle()
            rig.second(power = null)
            assertEquals(0.0, rig.now.speedKmh, "no power reading, no movement")
            assertEquals(distance, rig.now.distanceMeters)
            rig.second(power = 200)
            assertTrue(rig.now.distanceMeters > distance)
            assertEquals(0.0, rig.record.samples[10].speedKmh, "the second without power")
            assertTrue(rig.record.samples[11].speedKmh > 0.0)
        }

    @Test
    fun `RR intervals are recorded with the second they arrived in`() =
        runTest {
            val rig = EngineRig(this)
            rig.strap.emit(80, listOf(700, 710)) // before the start: not part of the ride
            rig.settle()
            rig.start()
            rig.second(rr = listOf(800, 810))
            rig.second()
            rig.second(rr = listOf(795))
            rig.engine.pause()
            rig.settle()
            rig.second(rr = listOf(790, 805)) // paused: still recorded
            assertEquals(
                listOf(
                    RrInterval(1, 800),
                    RrInterval(1, 810),
                    RrInterval(3, 795),
                    RrInterval(4, 790),
                    RrInterval(4, 805),
                ),
                rig.record.rrIntervals,
            )
        }

    @Test
    fun `a strap without RR intervals leaves the series empty`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(10)
            assertTrue(rig.record.rrIntervals.isEmpty())
        }

    @Test
    fun `the summary is that of the samples`() =
        runTest {
            val rig = EngineRig(this, settings = RideSettings(ftp = 200))
            rig.start()
            rig.seconds(60, power = 200, bpm = 140)
            val record = rig.record
            assertEquals(RideSummary.of(record.samples, 200), record.summary)
            with(record.summary) {
                assertEquals(60, durationSeconds)
                assertEquals(60, movingSeconds)
                assertEquals(200.0, averagePower!!, 1e-9)
                assertEquals(200.0, normalizedPower!!, 1e-9)
                assertEquals(1.0, intensityFactor!!, 1e-9)
                assertEquals(60 * 200 * 1.0 / (200 * 3600) * 100, trainingStressScore!!, 1e-9)
                assertEquals(12.0, workKilojoules, 1e-9)
                assertEquals(140.0, averageHeartRate!!, 1e-9)
                assertEquals(140, maxHeartRate)
            }
        }

    @Test
    fun `moving time leaves out the pauses`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(30)
            rig.engine.pause()
            rig.settle()
            rig.seconds(10, power = 0, cadence = 0)
            rig.engine.resume()
            rig.settle()
            rig.seconds(20)
            with(rig.record.summary) {
                assertEquals(60, durationSeconds)
                assertEquals(50, movingSeconds)
                assertEquals(200.0, averagePower!!, 1e-9, "the paused zeros do not pull the average down")
            }
            assertEquals(50, rig.now.elapsedSeconds)
        }

    private class SpyRecorder : RideRecorder {
        val calls = mutableListOf<String>()

        override fun onRideStarted(header: RideHeader) {
            calls += "start"
        }

        override fun onSample(sample: RideSample) {
            calls += "sample ${sample.timeOffsetSeconds}"
        }

        override fun onRrIntervals(intervals: List<RrInterval>) {
            calls += "rr ${intervals.size}"
        }

        override fun onRideFinished(endEpochMillis: Long) {
            calls += "finish $endEpochMillis"
        }
    }

    @Test
    fun `the recorder is called in order once a second and not after the end`() =
        runTest {
            val trainer = SimulatedTrainerDevice().also { it.connect() }
            val strap = SimulatedHeartRateDevice().also { it.connect() }
            val spy = SpyRecorder()
            val engine =
                RideEngine(backgroundScope, trainer, strap, recorder = spy, clock = { testScheduler.currentTime })
            settle()
            assertTrue(spy.calls.isEmpty())
            engine.start()
            settle()
            repeat(3) {
                trainer.emit(power = 150, cadence = 80)
                strap.emit(120, listOf(500, 500))
                advanceSeconds(1)
            }
            engine.finish()
            settle()
            advanceSeconds(10)
            assertEquals(
                listOf("start", "sample 1", "rr 2", "sample 2", "rr 2", "sample 3", "rr 2", "finish 3000"),
                spy.calls,
            )
        }

    @Test
    fun `a recorder that is not wanted costs nothing`() =
        runTest {
            val trainer = SimulatedTrainerDevice().also { it.connect() }
            val engine = RideEngine(backgroundScope, trainer)
            settle()
            engine.start()
            settle()
            repeat(5) {
                trainer.emit(power = 100, cadence = 80)
                advanceSeconds(1)
            }
            assertEquals(5, engine.snapshots.value.elapsedSeconds)
        }
}
