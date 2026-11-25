package app.overdrive.core.ride

import app.overdrive.core.device.BikeEvent
import app.overdrive.core.device.DeviceDisconnect
import app.overdrive.core.device.DeviceSilence
import app.overdrive.core.device.RiderStop
import app.overdrive.core.device.SimulatedBike
import app.overdrive.core.device.SimulatedDevice
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The engine over a [SimulatedBike]: a whole ride with a simulated rider, trainer and strap, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineSimulatedBikeTest {
    private class BikeRide(
        scope: TestScope,
        workout: Workout? = null,
        scenario: SimulationScenario = SimulationScenario(noise = 0.0),
        events: List<BikeEvent> = emptyList(),
        seed: Long = 1L,
        settings: RideSettings = RideSettings(ftp = 200),
    ) {
        val bike =
            SimulatedBike(scope.backgroundScope, ftp = settings.ftp, seed = seed, scenario = scenario, events = events)
        val recorder = InMemoryRideRecorder()
        val engine =
            RideEngine(
                scope = scope.backgroundScope,
                trainer = bike.trainer,
                heartRate = bike.heartRate,
                workout = workout,
                settings = settings,
                recorder = recorder,
                clock = { ENGINE_TEST_START_MILLIS + scope.testScheduler.currentTime },
            )
        val snapshots = scope.record(engine)

        init {
            bike.start()
            scope.settle()
            engine.start()
            scope.settle()
        }

        val now get() = engine.snapshots.value
        val record: RideRecord get() = checkNotNull(recorder.record)
    }

    @Test
    fun `an ERG workout is held by the simulated rider step by step`() =
        runTest {
            val ride = BikeRide(this, workout = abcWorkout)
            advanceSeconds(180)
            assertEquals(RideState.FINISHED, ride.now.state)

            val samples = ride.record.samples
            assertEquals(180, samples.size)
            // The rider is on target before each step ends: A is 100 W, B is 160 W; the free ride floats at 55% of 200 W.
            assertEquals(100, samples[59].power)
            assertEquals(160, samples[119].power)
            assertEquals(110, samples[179].power)
            assertEquals(listOf(100, 160), listOf(59, 119).map { samples[it].targetPower })
            assertTrue(samples.drop(120).all { it.targetPower == null && it.gear == 12 })

            val laps = ride.record.laps
            assertEquals(3, laps.size)
            assertTrue(laps[0].averagePower!! in 90.0..100.0, "the first seconds lag behind the target")
            assertTrue(laps[1].averagePower!! in 150.0..160.0)
            assertTrue(laps[2].averagePower!! in 110.0..113.0, "the rider eases down from 160 W to 110 W")
        }

    @Test
    fun `shifting gears in a free ride makes the simulated rider work harder`() =
        runTest {
            val ride = BikeRide(this)
            advanceSeconds(30)
            assertEquals(110, ride.now.power)
            repeat(5) { ride.engine.gearUp() }
            settle()
            advanceSeconds(30)
            assertEquals(17, ride.now.gear)
            assertEquals(132, ride.now.power, "five gears of grade: 5 * 4% more than 110 W")
            assertEquals(5.0, ride.bike.trainer.simulation?.gradePercent)
        }

    @Test
    fun `the heart rate follows the power the engine asks for`() =
        runTest {
            val workout = Workout(1, "ftp", "At FTP", steps = listOf(SteadyStep(duration = 200, target = 1.0)))
            val ride = BikeRide(this, workout = workout)
            advanceSeconds(199)
            assertEquals(200, ride.now.power)
            assertEquals(170, ride.now.heartRate)
            assertEquals(PowerZone.Z4, ride.now.zone, "FTP is in Z4")
        }

    @Test
    fun `pulling the trainer's plug pauses the ride and the reconnect resumes it with nothing lost`() =
        runTest {
            val ride =
                BikeRide(
                    this,
                    events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 20, durationSeconds = 10)),
                )
            advanceSeconds(19)
            assertEquals(RideState.RIDING, ride.now.state)
            advanceSeconds(1)
            assertEquals(RideState.PAUSED, ride.now.state)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, ride.now.pauseReason)
            advanceSeconds(10)
            assertEquals(RideState.RIDING, ride.now.state)
            advanceSeconds(30)

            val samples = ride.record.samples
            assertEquals((1..60).toList(), samples.map { it.timeOffsetSeconds })
            assertEquals((20..29).toList(), samples.filter { it.paused }.map { it.timeOffsetSeconds })
            assertTrue(
                samples.filter { it.paused }.all { it.power == null && it.cadence == null && it.heartRate != null },
            )
            assertTrue(samples.filter { !it.paused }.all { it.power != null && it.cadence != null })
            assertEquals(50, ride.now.elapsedSeconds)
            assertEquals(50, ride.record.summary.movingSeconds)
            assertEquals(60, ride.record.summary.durationSeconds)
        }

    @Test
    fun `a power-off ends the ride's progress but not the record`() =
        runTest {
            val ride = BikeRide(this, events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 10)))
            advanceSeconds(100)
            assertEquals(RideState.PAUSED, ride.now.state)
            assertEquals(9, ride.now.elapsedSeconds)
            assertEquals(100, ride.record.samples.size)
            ride.engine.finish()
            settle()
            assertEquals(RideState.FINISHED, ride.now.state)
            assertTrue(ride.recorder.isFinished)
        }

    @Test
    fun `heart-rate dropouts of every kind leave gaps and never pause the ride`() =
        runTest {
            val scenario =
                SimulationScenario(
                    noise = 0.0,
                    dropouts = listOf(SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 30, durationSeconds = 4)),
                )
            val events =
                listOf(
                    DeviceDisconnect(SimulatedDevice.HEART_RATE, startSecond = 10, durationSeconds = 5),
                    DeviceSilence(SimulatedDevice.HEART_RATE, startSecond = 40, durationSeconds = 8),
                )
            val ride = BikeRide(this, scenario = scenario, events = events)
            advanceSeconds(60)

            val samples = ride.record.samples
            val gaps = samples.filter { it.heartRate == null }.map { it.timeOffsetSeconds }
            // 10..14: the strap's link was down. 30..33: it reported 0 bpm. 44..47: the last reading is reused for
            // 5 seconds, so only the 5th second of silence onwards is a gap.
            assertEquals((10..14).toList() + (30..33).toList() + (44..47).toList(), gaps)
            assertTrue(samples.none { it.paused })
            assertEquals(RideState.RIDING, ride.now.state)
            assertEquals(60, ride.now.elapsedSeconds)
            assertTrue(samples.all { it.power != null })
        }

    @Test
    fun `a rider who stops for a while is auto-paused and resumes pedalling`() =
        runTest {
            val ride = BikeRide(this, events = listOf(RiderStop(startSecond = 30, durationSeconds = 10)))
            advanceSeconds(31)
            assertEquals(RideState.RIDING, ride.now.state)
            advanceSeconds(1)
            assertEquals(RideState.PAUSED, ride.now.state)
            assertEquals(PauseReason.AUTO, ride.now.pauseReason)
            advanceSeconds(28)

            val samples = ride.record.samples
            // Pedalling is noticed on the notification that carries it, which the bike delivers just before the engine's
            // tick of second 40: that second is already riding (docs/adr/0032).
            assertEquals((33..39).toList(), samples.filter { it.paused }.map { it.timeOffsetSeconds })
            assertEquals(RideState.RIDING, ride.now.state)
            assertEquals(53, ride.record.summary.movingSeconds)
            assertTrue(samples.drop(29).take(10).all { it.cadence == 0 })
        }

    @Test
    fun `a free ride on the flat keeps a plausible speed and distance`() =
        runTest {
            val ride = BikeRide(this)
            advanceSeconds(600)
            // 110 W on the flat is 26.6 km/h with the default rider; 10 minutes cover about 4.4 km.
            assertTrue(ride.now.speedKmh in 26.5..26.8, "got ${ride.now.speedKmh}")
            assertTrue(ride.now.distanceMeters in 4_400.0..4_470.0, "got ${ride.now.distanceMeters}")
        }

    private fun run(
        seed: Long,
        workout: Workout?,
    ): Pair<RideRecord, List<RideSnapshot>> {
        var result: Pair<RideRecord, List<RideSnapshot>>? = null
        runTest {
            val ride =
                BikeRide(
                    this,
                    workout = workout,
                    scenario = SimulationScenario(),
                    seed = seed,
                    events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 50, durationSeconds = 15)),
                )
            advanceSeconds(100)
            ride.engine.gearUp()
            ride.engine.intensityUp()
            advanceSeconds(80)
            result = ride.record to ride.snapshots.toList()
        }
        return checkNotNull(result)
    }

    @Test
    fun `the same seed gives the same ride down to every snapshot`() {
        val first = run(seed = 42, workout = abcWorkout)
        val second = run(seed = 42, workout = abcWorkout)
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
        assertTrue(first.first.samples.size >= 180)
        assertTrue(first.first.samples.any { it.power != null }, "readings were recorded")
        assertNotEquals(first.first, run(seed = 43, workout = abcWorkout).first)
    }
}
