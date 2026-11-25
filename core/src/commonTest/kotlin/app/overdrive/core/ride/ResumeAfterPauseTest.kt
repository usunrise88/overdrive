package app.overdrive.core.ride

import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun steady(
    seconds: Int = 600,
    fraction: Double = 0.8,
) = Workout(1, "steady", "Steady", steps = listOf(SteadyStep(duration = seconds, target = fraction)))

private fun target(watts: Int) = TrainerCommand.TargetPower(watts)

/** A ride at FTP 200 on a [FlywheelTrainerModel]: 160 W in the steady step, the rider pedalling since the start. */
@OptIn(ExperimentalCoroutinesApi::class)
private class FlywheelRide(
    private val scope: TestScope,
    workout: Workout? = steady(),
    settings: RideSettings = RideSettings(ftp = 200),
    val options: FlywheelTrainerOptions = FlywheelTrainerOptions(),
) {
    val rig = EngineRig(scope, workout = workout, settings = settings, withStrap = false)
    val trainer = FlywheelTrainerModel(scope, rig.trainer, options)
    val now: RideSnapshot get() = rig.now

    init {
        rig.start()
        trainer.pedal()
        trainer.advanceSeconds(10)
    }

    /** The rider stops until the ride has auto-paused; the flywheel winds down on its own. Returns the virtual time. */
    fun stopUntilAutoPaused(): Long {
        trainer.rest()
        trainer.advanceSeconds(6)
        check(now.pauseReason == PauseReason.AUTO) { "expected an auto-pause, got ${now.state} ${now.pauseReason}" }
        return scope.testScheduler.currentTime
    }

    /** The first stroke: the rider pedals again and exactly one notification passes. */
    fun firstStroke() {
        trainer.pedal()
        trainer.advanceOneNotification()
    }

    /** Whether the ride was riding in any snapshot published since [fromIndex]. */
    fun rodeSince(fromIndex: Int): Boolean = rig.snapshots.drop(fromIndex).any { it.state == RideState.RIDING }
}

/** Resuming after a pause (spec §4.2, §4.5, docs/adr/0032): the rider pedals again and the ride must follow at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResumeAfterPauseTest {
    // region the deadlock and its way out

    @Test
    fun `the first stroke after an auto-pause resumes the ride within one notification`() =
        runTest {
            val ride = FlywheelRide(this)
            val pausedAt = ride.stopUntilAutoPaused()
            assertEquals(target(25), ride.rig.commands.last(), "the small hold, so that the trainer can feel a stroke")

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state, "a trainer that shows no cadence at its resting target")
            assertNull(ride.now.pauseReason)
            val first = ride.rig.trainer.received.last()
            assertEquals(pausedAt + 250, first.timeMillis, "told in the instant of that notification, not at a tick")
            assertEquals(target(70), first.command, "25 W plus a third of the way to 160 W")
        }

    @Test
    fun `with the hold and the new signs turned off the ride stays paused which was the deadlock`() =
        runTest {
            val settings =
                RideSettings(
                    ftp = 200,
                    autoPauseTargetWatts = 0,
                    autoResumePowerWatts = 0,
                    autoResumeFlywheelStepKmh = 0.0,
                )
            val ride = FlywheelRide(this, settings = settings)
            ride.stopUntilAutoPaused()
            assertEquals(target(0), ride.rig.commands.last())

            ride.trainer.pedal()
            ride.trainer.advanceSeconds(60)
            assertEquals(RideState.PAUSED, ride.now.state, "pedalling for a minute against 0 W: cadence reads 0")
            assertEquals(PauseReason.AUTO, ride.now.pauseReason)
            assertEquals(
                0,
                ride.rig.commands
                    .filterIsInstance<TrainerCommand.TargetPower>()
                    .last()
                    .watts,
            )
        }

    @Test
    fun `cadence alone resumes the ride`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused()
            ride.options.cadenceNeedsWatts = 0
            ride.options.reportsPower = false
            ride.options.reportsSpeed = false

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state)
        }

    @Test
    fun `power alone resumes the ride because the hold is something to push against`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused()
            ride.options.cadenceNeedsWatts = 10_000 // the cadence field stays at 0
            ride.options.reportsSpeed = false

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state, "the trainer shows the 25 W it holds")
        }

    @Test
    fun `power alone cannot end a hold of 0 W`() =
        runTest {
            val ride = FlywheelRide(this, settings = RideSettings(ftp = 200, autoPauseTargetWatts = 0))
            ride.stopUntilAutoPaused()
            ride.options.cadenceNeedsWatts = 10_000
            ride.options.reportsSpeed = false

            ride.trainer.pedal()
            ride.trainer.advanceSeconds(20)
            assertEquals(RideState.PAUSED, ride.now.state, "no cadence, no power at 0 W, no speed: nothing to see")
        }

    @Test
    fun `the flywheel speed ends a hold of 0 W`() =
        runTest {
            val ride = FlywheelRide(this, settings = RideSettings(ftp = 200, autoPauseTargetWatts = 0))
            ride.stopUntilAutoPaused()
            ride.options.cadenceNeedsWatts = 10_000

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state, "the flywheel is being accelerated: no cadence, no power")
        }

    @Test
    fun `flywheel speed alone resumes the ride`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused()
            ride.options.cadenceNeedsWatts = 10_000
            ride.options.reportsPower = false

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state, "eight km/h a second is two km/h in one notification")
        }

    // endregion

    // region a flywheel that only coasts must not resume the ride

    @Test
    fun `a flywheel that winds down by itself does not resume the ride whatever power it reports`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused()
            val seen = ride.rig.snapshots.size
            // Still turning at 14 km/h, 0.4 km/h less each notification, power 25 W as the ERG trainer regulates.
            ride.trainer.advanceSeconds(8)
            assertEquals(PauseReason.AUTO, ride.now.pauseReason)
            assertTrue(ride.trainer.speedKmh < 2.0, "it has all but stopped: ${ride.trainer.speedKmh}")
            assertTrue(ride.trainer.notifications > 40)
            assertEquals(false, ride.rodeSince(seen), "no moment of riding while it coasted")
        }

    @Test
    fun `a speed sensor that repeats its value between updates does not make a coast look like a ride`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.options.speedRefreshEvery = 4 // the speed changes once a second
            ride.stopUntilAutoPaused()
            val seen = ride.rig.snapshots.size
            ride.trainer.advanceSeconds(8)
            assertEquals(false, ride.rodeSince(seen))
        }

    @Test
    fun `a coasting flywheel with no power field does not resume the ride either`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.options.reportsPower = false
            ride.stopUntilAutoPaused()
            val seen = ride.rig.snapshots.size
            ride.trainer.advanceSeconds(8)
            assertEquals(false, ride.rodeSince(seen))
        }

    @Test
    fun `without a speed field a coast that reports power looks like a rider and the rule can be turned off`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.options.reportsSpeed = false
            ride.trainer.rest()
            ride.trainer.advanceSeconds(8)
            // Not what anyone wants, but all that 25 W says: the ride pauses and resumes again and again for as long as the
            // flywheel turns. autoResumePowerWatts = 0 is the way out.
            val states = ride.rig.snapshots.map { it.state }
            assertTrue(RideState.PAUSED in states, "it did auto-pause")
            assertTrue(RideState.RIDING in states.dropWhile { it != RideState.PAUSED }, "and came straight back")

            val off = FlywheelRide(this, settings = RideSettings(ftp = 200, autoResumePowerWatts = 0))
            off.options.reportsSpeed = false
            off.stopUntilAutoPaused()
            val seen = off.rig.snapshots.size
            off.trainer.advanceSeconds(8)
            assertEquals(false, off.rodeSince(seen))
        }

    // endregion

    // region manual pause

    @Test
    fun `pedalling never lifts a manual pause resume does and the ramp-in starts from 0 W`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.rig.engine.pause()
            ride.rig.settle()
            assertEquals(PauseReason.MANUAL, ride.now.pauseReason)
            assertEquals(
                target(0),
                ride.rig.commands.last(),
                "a manual pause holds pausedTargetWatts, not the auto hold",
            )

            ride.trainer.advanceSeconds(10) // the rider keeps pedalling the whole time
            assertEquals(PauseReason.MANUAL, ride.now.pauseReason)
            val before = ride.rig.commands.size

            ride.rig.engine.resume()
            ride.rig.settle()
            assertEquals(RideState.RIDING, ride.now.state)
            ride.trainer.advanceSeconds(3)
            assertEquals(listOf(target(53), target(107), target(160)), ride.rig.commands.drop(before))
            assertEquals(RideState.RIDING, ride.now.state)
        }

    @Test
    fun `a manual pause that the rider rides through keeps the 0 W hold`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.rig.engine.pause()
            ride.rig.settle()
            ride.trainer.advanceSeconds(30)
            assertTrue(ride.rig.commands.none { it == target(25) })
            assertNull(ride.now.secondsSinceResume)
        }

    // endregion

    // region the ramp-in

    @Test
    fun `the ramp-in starts from the hold and is full after the ramp-in time`() =
        runTest {
            val ride = FlywheelRide(this)
            val pausedAt = ride.stopUntilAutoPaused()
            val before = ride.rig.trainer.received.size

            ride.firstStroke()
            ride.trainer.advanceSeconds(3)
            val sent = ride.rig.trainer.received.drop(before)
            assertEquals(
                listOf(pausedAt + 250 to target(70), pausedAt + 1000 to target(115), pausedAt + 2000 to target(160)),
                sent.map { it.timeMillis to it.command },
                "25, then 70 and 115 and 160 W: one step at the stroke, the rest with the ticks",
            )
            assertEquals(160, ride.now.targetPower, "the overlay shows the target, never the ramp")
        }

    @Test
    fun `a ramp-in of 0 seconds goes to the full target at once`() =
        runTest {
            val ride = FlywheelRide(this, settings = RideSettings(ftp = 200, rampInSeconds = 0))
            ride.stopUntilAutoPaused()
            ride.firstStroke()
            assertEquals(target(160), ride.rig.commands.last())
        }

    @Test
    fun `a hold above what the step asks for is never commanded`() =
        runTest {
            // 10% of FTP is 20 W: the hold is the smaller of 25 W and the step's target.
            val ride = FlywheelRide(this, workout = steady(fraction = 0.1))
            ride.options.cadenceNeedsWatts = 0
            ride.stopUntilAutoPaused()
            assertEquals(target(20), ride.rig.commands.last())
            ride.firstStroke()
            assertEquals(target(20), ride.rig.commands.last(), "and the ramp is from there")
        }

    @Test
    fun `the auto-pause hold is a setting`() =
        runTest {
            val ride = FlywheelRide(this, settings = RideSettings(ftp = 200, autoPauseTargetWatts = 40))
            ride.stopUntilAutoPaused()
            assertEquals(target(40), ride.rig.commands.last())
            ride.firstStroke()
            assertEquals(target(40 + (160 - 40) / 3), ride.rig.commands.last())
        }

    @Test
    fun `an auto-pause hold of 0 holds the paused target like any pause`() =
        runTest {
            val settings = RideSettings(ftp = 200, autoPauseTargetWatts = 0, pausedTargetWatts = 15)
            val ride = FlywheelRide(this, settings = settings)
            ride.stopUntilAutoPaused()
            assertEquals(target(15), ride.rig.commands.last())
        }

    // endregion

    // region the trainer's link

    @Test
    fun `a trainer that drops during an auto-pause is commanded the hold again and resumes on the first stroke`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused()

            ride.rig.trainer.disconnect()
            ride.rig.settle()
            assertEquals(PauseReason.TRAINER_DISCONNECTED, ride.now.pauseReason)
            ride.trainer.pedal() // nothing reaches the engine
            ride.trainer.advanceSeconds(5)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, ride.now.pauseReason)

            val before = ride.rig.commands.size
            ride.rig.trainer.connect()
            ride.rig.settle()
            assertEquals(PauseReason.AUTO, ride.now.pauseReason, "the auto-pause is still there")
            assertEquals(listOf(TrainerCommand.RequestControl, target(25)), ride.rig.commands.drop(before))

            ride.trainer.advanceOneNotification()
            assertEquals(RideState.RIDING, ride.now.state)
            assertEquals(0, ride.rig.trainer.refusedCommands, "the strict trainer never saw an unwanted command")
        }

    @Test
    fun `a trainer that drops while riding comes back to a ramp-in from 0 W`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.rig.trainer.disconnect()
            ride.rig.settle()
            ride.trainer.advanceSeconds(3)
            val before = ride.rig.commands.size
            ride.rig.trainer.connect()
            ride.rig.settle()
            ride.trainer.advanceSeconds(3)
            assertEquals(
                listOf(TrainerCommand.RequestControl, target(0), target(53), target(107), target(160)),
                ride.rig.commands.drop(before),
            )
        }

    // endregion

    // region free ride, auto-pause off, records

    @Test
    fun `a free-ride step is commanded as before and resumes on the first stroke`() =
        runTest {
            val ride =
                FlywheelRide(this, workout = Workout(1, "free", "Free", steps = listOf(FreeStep(duration = 600))))
            ride.stopUntilAutoPaused()
            assertTrue(ride.rig.commands.none { it is TrainerCommand.TargetPower }, "no ERG target in simulation")
            val before = ride.rig.commands.size

            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state)
            ride.trainer.advanceSeconds(5)
            assertEquals(before, ride.rig.commands.size, "no hold, no ramp: the gear is all the trainer is told")
        }

    @Test
    fun `a ride without a workout is a free ride`() =
        runTest {
            val ride = FlywheelRide(this, workout = null)
            ride.stopUntilAutoPaused()
            ride.firstStroke()
            assertEquals(RideState.RIDING, ride.now.state)
            assertTrue(ride.rig.commands.none { it is TrainerCommand.TargetPower })
        }

    @Test
    fun `with auto-pause off the ride never pauses and nothing is held`() =
        runTest {
            val ride = FlywheelRide(this, settings = RideSettings(ftp = 200, autoPause = false))
            ride.trainer.rest()
            ride.trainer.advanceSeconds(30)
            assertEquals(RideState.RIDING, ride.now.state)
            assertTrue(ride.rig.commands.none { it == target(25) })
            ride.trainer.pedal()
            ride.trainer.advanceSeconds(10)
            assertNull(ride.now.secondsSinceResume, "it never paused, so it never resumed")
            assertTrue(ride.rig.record.samples.none { it.paused })
        }

    @Test
    fun `the records of the paused seconds are as they were`() =
        runTest {
            val ride = FlywheelRide(this)
            ride.stopUntilAutoPaused() // paused from the 13th second
            ride.firstStroke()
            ride.trainer.advanceSeconds(4)

            val samples = ride.rig.record.samples
            val paused = samples.filter { it.paused }
            assertEquals((14..16).toList(), paused.map { it.timeOffsetSeconds }, "the seconds 14 to 16, none else")
            assertTrue(paused.all { it.targetPower == 160 && it.stepIndex == 0 }, "the plan, not the hold")
            assertTrue(paused.all { it.speedKmh == 0.0 }, "no movement while paused")
            assertEquals(paused.first().distanceMeters, paused.last().distanceMeters)
            assertTrue(paused.all { it.cadence == 0 }, "the sensor's own values are recorded")
            // The second of the first stroke (17) is riding: the state at the moment of its tick.
            assertEquals(List(13) { false } + List(3) { true } + List(4) { false }, samples.map { it.paused })
            assertEquals(17, ride.now.elapsedSeconds, "13 seconds before the pause and 4 after it")
            assertEquals(17, ride.now.workout!!.workoutElapsedSeconds, "the workout stood still while paused")
        }

    // endregion

    // region the guard for the stall check

    @Test
    fun `seconds since the resume are counted from a resume and are null while paused`() =
        runTest {
            val ride = FlywheelRide(this)
            assertNull(ride.now.secondsSinceResume, "the start of a ride is not a resume")
            ride.stopUntilAutoPaused()
            assertNull(ride.now.secondsSinceResume)

            ride.firstStroke()
            assertEquals(0, ride.now.secondsSinceResume, "from the moment of the resume")
            ride.trainer.advanceSeconds(1)
            assertEquals(1, ride.now.secondsSinceResume)
            ride.trainer.advanceSeconds(4)
            assertEquals(5, ride.now.secondsSinceResume)

            ride.rig.engine.pause()
            ride.rig.settle()
            assertNull(ride.now.secondsSinceResume)
            ride.rig.engine.resume()
            ride.rig.settle()
            assertEquals(0, ride.now.secondsSinceResume, "a manual resume counts too")
        }

    // endregion
}
