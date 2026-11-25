package app.overdrive.core.ride

import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.record.RideRecord
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.HeartRateStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE_2 =
    HeartRateStep(duration = 1800, targetBpm = 130, band = 5, powerMin = 80, powerMax = 220, label = "Zone 2")

private fun hrWorkout(vararg more: app.overdrive.core.workout.LeafStep) =
    Workout(1, "hr", "Heart rate", steps = listOf(ZONE_2) + more)

/** The heart-rate step in the engine (spec §11.2, docs/adr/0033): ERG whose power a controller chooses from the heart rate. */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateStepEngineTest {
    private fun ride(
        scope: TestScope,
        workout: Workout = hrWorkout(),
        settings: RideSettings = RideSettings(ftp = 200),
    ) = EngineRig(scope, workout = workout, settings = settings).also { it.start() }

    /** The rider follows the trainer's target with a lagging heart rate; [silent] leaves the strap quiet. */
    private fun EngineRig.follow(
        rider: LagRider,
        count: Int,
        cadence: Int? = 90,
        silent: Boolean = false,
    ) = repeat(count) {
        val watts = trainer.targetPowerWatts ?: 0
        rider.pedal(watts)
        second(power = watts, cadence = cadence, bpm = if (silent) null else rider.reading())
    }

    private fun targetPowers(rig: EngineRig) =
        rig.commands.filterIsInstance<TrainerCommand.TargetPower>().map { it.watts }

    @Test
    fun `a heart-rate step is ERG and starts at power_min with the start ramp running`() =
        runTest {
            val rig = ride(this)
            assertEquals(TrainerMode.ERG, rig.now.mode)
            assertEquals(80, rig.trainer.targetPowerWatts)
            assertEquals(80, rig.now.targetPower, "the controller's power is the target")
            val hold = assertNotNull(rig.now.heartRateHold)
            assertEquals(130, hold.targetBpm)
            assertEquals(5, hold.band)
            assertTrue(hold.startRamp)
            assertNull(hold.inBand, "no heart rate yet")
            assertEquals(80 to 220, hold.powerMin to hold.powerMax)
            assertEquals(100, rig.now.intensityPercent)
        }

    @Test
    fun `the start ramp rises for 3 minutes and then the controller is in charge`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 90)
            assertEquals(101, rig.trainer.targetPowerWatts, "halfway up the 80 to 122 W ramp")
            assertTrue(assertNotNull(rig.now.heartRateHold).startRamp)
            rig.follow(rider, 90)
            assertEquals(122, rig.trainer.targetPowerWatts)
            assertFalse(assertNotNull(rig.now.heartRateHold).startRamp)

            // The record: the heart-rate target is there only from the second the controller took over.
            val samples = rig.record.samples
            assertTrue(samples.take(180).all { it.targetHeartRate == null })
            rig.follow(rider, 5)
            assertEquals(130, rig.record.samples[180].targetHeartRate)
            assertEquals(listOf(0), rig.record.laps.map { it.stepIndex })
        }

    @Test
    fun `it settles inside the band within the limits and the record has the judged seconds`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 2)
            rig.follow(rider, 1500)

            assertTrue(targetPowers(rig).all { it in 80..220 || it == 0 })
            val hold = assertNotNull(rig.now.heartRateHold)
            assertTrue(abs(rider.heartRate - 130) <= 5, "heart rate ${rider.heartRate}")
            assertTrue(hold.inBand == true || hold.inBand == false)

            val judged = rig.record.samples.filter { it.targetHeartRate != null && !it.paused && it.heartRate != null }
            assertEquals(1500 - 180, judged.size, "the start ramp is not judged")
            val inBand = judged.count { abs(it.heartRate!! - it.targetHeartRate!!) <= 5 }
            assertTrue(inBand > judged.size * 0.7, "in the band for $inBand of ${judged.size} judged seconds")
        }

    // region the gear buttons

    @Test
    fun `the two gear buttons move the target by 2 bpm instead of the intensity`() =
        runTest {
            val rig = ride(this)
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(132, rig.now.heartRateHold?.targetBpm)
            assertEquals(100, rig.now.intensityPercent, "the intensity is not touched")
            assertEquals(80, rig.now.targetPower, "and the power is the controller's")
            rig.engine.intensityDown()
            rig.engine.intensityDown()
            rig.settle()
            assertEquals(128, rig.now.heartRateHold?.targetBpm)
        }

    @Test
    fun `the controller chases the target the rider set`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 900)
            repeat(5) { rig.engine.intensityDown() } // 120 bpm
            rig.settle()
            assertEquals(120, rig.now.heartRateHold?.targetBpm)
            rig.follow(rider, 900)
            assertTrue(abs(rider.heartRate - 120) <= 5, "heart rate ${rider.heartRate}")
            assertEquals(120, rig.record.samples.last().targetHeartRate)
        }

    @Test
    fun `the adjustment stays for the next heart-rate step and a power step has the intensity again`() =
        runTest {
            val short = ZONE_2.copy(duration = 20)
            val workout = Workout(1, "w", "W", steps = listOf(short, SteadyStep(30, 0.5), short))
            val rig = ride(this, workout)
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(132, rig.now.heartRateHold?.targetBpm)
            rig.seconds(22, power = 100, cadence = 90, bpm = 120)
            assertNull(rig.now.heartRateHold, "a power step has no heart-rate state")
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(105, rig.now.intensityPercent)
            rig.seconds(30, power = 100, cadence = 90, bpm = 120)
            assertEquals(132, rig.now.heartRateHold?.targetBpm, "the second heart-rate step starts at the rider's 132")
        }

    // endregion

    // region strap loss

    @Test
    fun `a lost strap holds the last power for 15 s and after 60 s drops to power_min and says so`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 600)
            val held = assertNotNull(rig.trainer.targetPowerWatts)
            assertTrue(held > 100, "a raised power: $held")

            // The engine reuses a reading for 4 more seconds (heartRateTimeoutSeconds is 5); the controller counts from there.
            rig.follow(rider, 4 + 14, silent = true)
            assertFalse(rig.now.heartRateHold!!.signalLost)
            rig.follow(rider, 1, silent = true)
            assertTrue(rig.now.heartRateHold!!.signalLost, "15 s without a heart rate")
            assertFalse(rig.now.heartRateHold!!.strapLost)
            assertEquals(held, rig.trainer.targetPowerWatts, "the last power is held")
            assertNull(rig.now.heartRate)

            rig.follow(rider, 44, silent = true)
            assertFalse(rig.now.heartRateHold!!.strapLost)
            assertEquals(held, rig.trainer.targetPowerWatts)
            rig.follow(rider, 1, silent = true)
            assertTrue(rig.now.heartRateHold!!.strapLost, "60 s")
            assertEquals(80, rig.trainer.targetPowerWatts, "power_min")
            assertEquals(80, rig.now.targetPower)

            rig.follow(rider, 3)
            assertFalse(rig.now.heartRateHold!!.strapLost, "the strap is back")
        }

    // endregion

    // region pause, skipping, the guard

    @Test
    fun `a pause holds the controller's power and after it the target ramps in to it`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 400)
            val before = assertNotNull(rig.trainer.targetPowerWatts)
            rig.engine.pause()
            rig.settle()
            assertEquals(0, rig.trainer.targetPowerWatts)
            rig.follow(rider, 30)
            rig.engine.resume()
            rig.settle()
            assertEquals(0, rig.trainer.targetPowerWatts)
            rig.follow(rider, 3)
            assertEquals(before, rig.trainer.targetPowerWatts, "the same power as before the pause, in three steps")
            // No decision for a while: the heart rate fell during the pause and is not judged until its window is full.
            rig.follow(rider, 20)
            assertEquals(before, rig.trainer.targetPowerWatts)
        }

    @Test
    fun `skipping back restarts the step with its start ramp and skipping on leaves it`() =
        runTest {
            val rig = ride(this, hrWorkout(FreeStep(600)))
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 400)
            assertTrue(assertNotNull(rig.trainer.targetPowerWatts) > 122)
            rig.engine.previousInterval() // more than 3 s in: restarts the step
            rig.settle()
            assertEquals(80, rig.now.targetPower)
            assertTrue(rig.now.heartRateHold!!.startRamp)
            rig.engine.nextInterval()
            rig.settle()
            assertEquals(TrainerMode.SIMULATION, rig.now.mode)
            assertNull(rig.now.heartRateHold)
            assertNull(rig.now.targetPower)
        }

    @Test
    fun `the stall guard caps the controller's power and the controller does not wind up against it`() =
        runTest {
            val rig = ride(this)
            val rider = LagRider(gain = 0.45, tau = 50.0, noiseBpm = 0)
            rig.follow(rider, 400)
            val controller = assertNotNull(rig.now.targetPower)
            // A stall: the cadence falls, and the rider's heart rate with the power that follows it down.
            rig.follow(rider, 3, cadence = 40)
            assertTrue(rig.now.stallGuardActive)
            assertEquals((controller * 0.7).roundToInt(), rig.trainer.targetPowerWatts, "70% of the controller's power")
            assertEquals(controller, rig.now.targetPower, "the controller's power is unchanged by the guard")
            rig.follow(rider, 60, cadence = 40)
            assertEquals(controller, rig.now.targetPower, "frozen: no decision while the guard holds")
            assertEquals(1, rig.record.events.size)
            assertEquals(0, rig.record.events.single().stepIndex)
        }

    // endregion

    @Test
    fun `flattening gives the step its heart rate and an unresolved threshold fraction is a free step`() {
        val byBpm = hrWorkout().flatSteps().single()
        assertEquals(HeartRateTarget(130, 5, 80, 220), byBpm.heartRate)
        assertEquals(TrainerMode.ERG, byBpm.mode)
        assertNull(byBpm.targetFrom)
        assertEquals("Zone 2", byBpm.label)

        val fraction = HeartRateStep(duration = 60, targetLthr = 0.9, powerMin = 80, powerMax = 200)
        val byThreshold = Workout(1, "w", "W", steps = listOf(fraction))
        assertEquals(TrainerMode.SIMULATION, byThreshold.flatSteps().single().mode, "no threshold: nothing to hold")
        assertEquals(162, byThreshold.flatSteps(thresholdHeartRate = 180).single().heartRate?.targetBpm)
        assertEquals("Heart rate", byThreshold.flatSteps(180).single().label)
    }

    @Test
    fun `a record with a heart-rate step reads back through the wire format`() =
        runTest {
            val rig = ride(this)
            rig.follow(LagRider(0.45, 50.0), 240)
            val record: RideRecord = rig.record
            val text =
                app.overdrive.core.record.RideRecordJson
                    .encode(record)
            assertTrue("targetHeartRate" in text)
            assertEquals(
                record,
                app.overdrive.core.record.RideRecordJson
                    .decode(text),
            )
        }
}
