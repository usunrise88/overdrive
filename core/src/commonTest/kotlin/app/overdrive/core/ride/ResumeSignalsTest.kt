package app.overdrive.core.ride

import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the engine takes for a rider who pedals again after an auto-pause (docs/adr/0032), reading by reading: each test
 * hand-feeds the trainer's notifications to an auto-paused ride and settles, with no time passing, so that anything
 * that ends the pause has done so on the notification itself and not at a tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeSignalsTest {
    private val workout = Workout(1, "steady", "Steady", steps = listOf(SteadyStep(duration = 600, target = 0.8)))

    /** A ride at FTP 200 that has been riding with the flywheel at 24 km/h and has just auto-paused, flywheel falling. */
    private fun TestScope.pausedRig(settings: RideSettings = RideSettings(ftp = 200)): EngineRig {
        val rig = EngineRig(this, workout = workout, settings = settings, withStrap = false)
        rig.start()
        repeat(3) { rig.tick(power = 160, cadence = 90, speed = 24.0) }
        repeat(3) { rig.tick(power = 0, cadence = 0, speed = 23.5 - it * 0.5) }
        check(rig.now.pauseReason == PauseReason.AUTO) { "expected an auto-pause, got ${rig.now.state}" }
        return rig
    }

    /** One second of the ride with a speed in the notification. */
    private fun EngineRig.tick(
        power: Int?,
        cadence: Int?,
        speed: Double?,
    ) {
        trainer.emit(power = power, cadence = cadence, speedKmh = speed)
        second(trainerSilent = true, bpm = null)
    }

    /** A notification that arrives now, between ticks. */
    private fun EngineRig.notify(
        power: Int? = 0,
        cadence: Int? = 0,
        speed: Double? = null,
    ) {
        trainer.emit(power = power, cadence = cadence, speedKmh = speed)
        settle()
    }

    private val EngineRig.paused get() = now.state == RideState.PAUSED

    @Test
    fun `a cadence above 0 ends the pause on the notification and the trainer is told in the same instant`() =
        runTest {
            val rig = pausedRig()
            assertEquals(TrainerCommand.TargetPower(25), rig.commands.last())
            rig.notify(cadence = 0, power = 0)
            assertTrue(rig.paused, "no cadence, no power: nothing yet")

            rig.notify(cadence = 1)
            assertEquals(RideState.RIDING, rig.now.state)
            val told = rig.trainer.received.last()
            assertEquals(testScheduler.currentTime, told.timeMillis)
            assertEquals(TrainerCommand.TargetPower(70), told.command)
        }

    @Test
    fun `zero and missing cadence show nothing and neither does a little power`() =
        runTest {
            val rig = pausedRig()
            rig.notify(cadence = 0, power = 0)
            rig.notify(cadence = null, power = null)
            rig.notify(cadence = 0, power = 9)
            rig.notify(cadence = null, power = 9)
            rig.notify(cadence = 0, power = null)
            assertTrue(rig.paused)
        }

    @Test
    fun `power at the threshold ends the pause one watt less does not`() =
        runTest {
            val rig = pausedRig()
            rig.notify(power = 9)
            assertTrue(rig.paused)
            rig.notify(power = 10)
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `the power threshold is a setting and 0 turns the rule off`() =
        runTest {
            val high = pausedRig(RideSettings(ftp = 200, autoResumePowerWatts = 50))
            high.notify(power = 49)
            assertTrue(high.paused)
            high.notify(power = 50)
            assertEquals(RideState.RIDING, high.now.state)

            val off = pausedRig(RideSettings(ftp = 200, autoResumePowerWatts = 0))
            off.notify(power = 500)
            assertTrue(off.paused, "cadence and the flywheel are all it looks at")
            off.notify(cadence = 70)
            assertEquals(RideState.RIDING, off.now.state)
        }

    @Test
    fun `the flywheel has to rise by the step between two consecutive readings`() =
        runTest {
            val rig = pausedRig()
            rig.notify(speed = 4.0)
            rig.notify(speed = 4.25)
            rig.notify(speed = 4.5)
            rig.notify(speed = 4.75)
            assertTrue(rig.paused, "a quarter of a km/h each is below the step of 0.5")
            rig.notify(speed = 5.25)
            assertEquals(RideState.RIDING, rig.now.state, "0.5 km/h")
        }

    @Test
    fun `a flywheel that falls or the first reading with a speed is no sign`() =
        runTest {
            val rig = pausedRig()
            rig.notify(speed = 10.0)
            assertTrue(rig.paused, "a fall from 22.5 km/h is no acceleration")
            rig.notify(speed = 5.0)
            rig.notify(speed = 0.0)
            rig.notify(speed = null)
            rig.notify(speed = 8.0)
            assertTrue(rig.paused, "a reading without speed starts the comparison over")
            rig.notify(speed = 9.0)
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `the speed step is a setting and 0 turns the rule off`() =
        runTest {
            val big = pausedRig(RideSettings(ftp = 200, autoResumeFlywheelStepKmh = 3.0))
            big.notify(speed = 5.0)
            big.notify(speed = 7.5)
            assertTrue(big.paused)
            big.notify(speed = 10.5)
            assertEquals(RideState.RIDING, big.now.state)

            val off = pausedRig(RideSettings(ftp = 200, autoResumeFlywheelStepKmh = 0.0))
            off.notify(speed = 5.0)
            off.notify(speed = 50.0)
            assertTrue(off.paused)
        }

    @Test
    fun `power does not end the pause while the flywheel is winding down and does once it is not`() =
        runTest {
            val rig = pausedRig() // the flywheel fell from 24 to 22.5 km/h on the way in
            rig.notify(power = 25, speed = 22.0)
            rig.notify(power = 25, speed = 21.75)
            rig.notify(power = 25, speed = 21.5)
            rig.notify(power = 25, speed = 21.5)
            assertTrue(rig.paused, "25 W, but the flywheel is coasting")
            rig.notify(power = 25, speed = 21.75)
            assertEquals(RideState.RIDING, rig.now.state, "it went up: someone is pushing, though not by a whole step")
        }

    @Test
    fun `nothing is compared across a reconnect`() =
        runTest {
            val rig = pausedRig()
            rig.notify(speed = 1.0)
            rig.trainer.disconnect()
            rig.settle()
            rig.trainer.connect()
            rig.settle()
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            rig.notify(speed = 5.0)
            assertTrue(rig.paused, "the first reading of a new link")
            rig.notify(speed = 6.0)
            assertEquals(RideState.RIDING, rig.now.state)
        }

    @Test
    fun `a manual pause ignores all of it`() =
        runTest {
            val rig = EngineRig(this, workout = workout, withStrap = false)
            rig.start()
            rig.seconds(3)
            rig.engine.pause()
            rig.settle()
            for (speed in listOf(0.0, 5.0, 10.0, 15.0)) rig.notify(power = 300, cadence = 90, speed = speed)
            assertEquals(PauseReason.MANUAL, rig.now.pauseReason)
            assertNull(rig.now.secondsSinceResume)
        }

    @Test
    fun `a ride that is not paused takes no notice either`() =
        runTest {
            val rig = EngineRig(this, workout = workout, withStrap = false)
            rig.start()
            val before = rig.commands.size
            for (speed in listOf(0.0, 5.0, 10.0)) rig.notify(power = 300, cadence = 90, speed = speed)
            assertEquals(RideState.RIDING, rig.now.state)
            assertNull(rig.now.secondsSinceResume)
            assertEquals(before, rig.commands.size)
        }
}
