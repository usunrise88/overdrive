package app.overdrive.core.ride

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.SimulatedTrainerDevice
import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.gear.GearSettings
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the trainer is told (spec §4.1, §4.4, §4.5): control, ERG targets, simulation and gears. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineTrainerTest {
    private val physics = PhysicsSettings()

    private fun simulation(grade: Double) =
        TrainerCommand.Simulation(SimulationParameters(grade, physics.crr, physics.windResistanceCoefficient))

    private fun target(watts: Int) = TrainerCommand.TargetPower(watts)

    @Test
    fun `after connecting the engine requests control and holds the flat`() =
        runTest {
            val rig = EngineRig(this)
            assertEquals(listOf(TrainerCommand.RequestControl, simulation(0.0)), rig.commands)
            assertEquals(0.004, (rig.commands.last() as TrainerCommand.Simulation).parameters.crr)
            assertEquals(0.214375, (rig.commands.last() as TrainerCommand.Simulation).parameters.windResistance, 1e-9)
        }

    @Test
    fun `a trainer that connects later is commanded when it does`() =
        runTest {
            val rig = EngineRig(this, connectTrainer = false)
            advanceSeconds(5)
            assertTrue(rig.commands.isEmpty())
            rig.trainer.connect()
            rig.settle()
            assertEquals(listOf(TrainerCommand.RequestControl, simulation(0.0)), rig.commands)
        }

    @Test
    fun `gears change the simulated grade by one percent each, from flat at the neutral gear`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            repeat(3) {
                rig.engine.gearUp()
                rig.settle()
            }
            assertEquals(15, rig.now.gear)
            assertEquals(simulation(3.0), rig.commands.last())

            repeat(5) {
                rig.engine.gearDown()
                rig.settle()
            }
            assertEquals(10, rig.now.gear)
            assertEquals(simulation(-2.0), rig.commands.last())

            assertEquals(
                listOf(0.0, 1.0, 2.0, 3.0, 2.0, 1.0, 0.0, -1.0, -2.0),
                rig.commands.filterIsInstance<TrainerCommand.Simulation>().map { it.parameters.gradePercent },
                "one command for each gear, none repeated",
            )
        }

    @Test
    fun `presses that arrive together send only the gear they end on`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            repeat(3) { rig.engine.gearUp() }
            rig.settle()
            assertEquals(15, rig.now.gear)
            assertEquals(
                listOf(0.0, 3.0),
                rig.commands.filterIsInstance<TrainerCommand.Simulation>().map {
                    it.parameters.gradePercent
                },
            )
        }

    @Test
    fun `gears work before the ride starts and while paused`() =
        runTest {
            val rig = EngineRig(this)
            rig.engine.gearUp()
            rig.settle()
            assertEquals(13, rig.now.gear)
            assertEquals(simulation(1.0), rig.commands.last())
            rig.start()
            rig.engine.pause()
            rig.engine.gearUp()
            rig.settle()
            assertEquals(RideState.PAUSED, rig.now.state)
            assertEquals(14, rig.now.gear)
            assertEquals(simulation(2.0), rig.commands.last())
        }

    @Test
    fun `the gear stops at both ends`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            repeat(40) { rig.engine.gearUp() }
            rig.settle()
            assertEquals(24, rig.now.gear)
            assertEquals(simulation(12.0), rig.commands.last())
            repeat(60) { rig.engine.gearDown() }
            rig.settle()
            assertEquals(1, rig.now.gear)
            assertEquals(simulation(-11.0), rig.commands.last())
        }

    @Test
    fun `the total grade never goes past the trainer's limit`() =
        runTest {
            val gears = GearSettings(gearCount = 24, gradePercentPerGear = 2.0)
            val rig = EngineRig(this, settings = RideSettings(ftp = 200, gears = gears))
            rig.start()
            repeat(30) { rig.engine.gearUp() }
            rig.settle()
            assertEquals(24, rig.now.gear)
            assertEquals(simulation(15.0), rig.commands.last(), "gear 24 would be +24%")
            repeat(30) { rig.engine.gearDown() }
            rig.settle()
            assertEquals(simulation(-15.0), rig.commands.last(), "gear 1 would be -22%")
        }

    @Test
    fun `the gear settings and the physics settings reach the trainer`() =
        runTest {
            val settings =
                RideSettings(
                    ftp = 200,
                    physics = PhysicsSettings(cdA = 0.4, crr = 0.006, airDensity = 1.2),
                    gears = GearSettings(gearCount = 10, neutralGear = 3, gradePercentPerGear = 0.5),
                )
            val rig = EngineRig(this, settings = settings)
            assertEquals(3, rig.now.gear)
            assertEquals(10, rig.now.gearCount)
            assertEquals(3, rig.now.neutralGear)
            rig.engine.gearUp()
            rig.engine.gearUp()
            rig.settle()
            val parameters = (rig.commands.last() as TrainerCommand.Simulation).parameters
            assertEquals(1.0, parameters.gradePercent)
            assertEquals(0.006, parameters.crr)
            assertEquals(0.24, parameters.windResistance, 1e-12)
        }

    @Test
    fun `the gear is only for free-ride steps and is kept between them`() =
        runTest {
            val workout =
                Workout(
                    schema = 1,
                    id = "gears",
                    name = "Gears",
                    steps =
                        listOf(
                            SteadyStep(duration = 5, target = 0.5),
                            FreeStep(duration = 5),
                            SteadyStep(duration = 5, target = 0.5),
                            FreeStep(duration = 5),
                        ),
                )
            val rig = EngineRig(this, workout = workout)
            rig.start()
            assertNull(rig.now.gear, "ERG step")
            rig.engine.gearUp()
            rig.settle()
            assertNull(rig.now.gear)
            assertEquals(listOf(target(0), target(100)), rig.commands.drop(1), "gear buttons do nothing in ERG")

            rig.followTrainer(5) // into the free step
            assertEquals(RideState.RIDING, rig.now.state)
            assertEquals(12, rig.now.gear)
            assertEquals(simulation(0.0), rig.commands.last())
            rig.engine.gearUp()
            rig.engine.gearUp()
            rig.settle()
            assertEquals(14, rig.now.gear)
            assertEquals(simulation(2.0), rig.commands.last())

            rig.followTrainer(5) // back to ERG
            assertNull(rig.now.gear)
            assertEquals(target(100), rig.commands.last())
            rig.followTrainer(5) // the second free step keeps the gear
            assertEquals(14, rig.now.gear)
            assertEquals(simulation(2.0), rig.commands.last())
        }

    @Test
    fun `an ERG workout is commanded step by step, and a pause drops the target to 0 W`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout) // A 50%, B 80%, free: at FTP 200
            assertEquals(
                listOf(TrainerCommand.RequestControl, target(0)),
                rig.commands,
                "nothing is held before the start",
            )
            rig.start()
            assertEquals(target(100), rig.commands.last())
            rig.followTrainer(60)
            assertEquals(target(160), rig.commands.last())
            rig.engine.pause()
            rig.settle()
            assertEquals(target(0), rig.commands.last())
            rig.engine.nextInterval()
            rig.settle()
            assertEquals(
                simulation(0.0),
                rig.commands.last(),
                "the next step is a free ride: simulation, even while paused",
            )
        }

    @Test
    fun `the target ramps back in over three seconds after a pause`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(5)
            rig.engine.pause()
            rig.settle()
            rig.seconds(4)
            val before = rig.commands.size

            rig.engine.resume()
            rig.settle()
            assertEquals(before, rig.commands.size, "the ramp starts from the paused 0 W")
            rig.second()
            assertEquals(target(33), rig.commands.last())
            rig.second()
            assertEquals(target(67), rig.commands.last())
            rig.second()
            assertEquals(target(100), rig.commands.last())
            rig.seconds(5)
            assertEquals(target(100), rig.commands.last())
            assertEquals(listOf(target(33), target(67), target(100)), rig.commands.drop(before))
            assertEquals(100, rig.now.targetPower, "the overlay shows the target, not the ramp")
        }

    @Test
    fun `auto-pause drops and ramps the target like a manual one`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.seconds(5)
            rig.seconds(3, cadence = 0, power = 0)
            assertEquals(PauseReason.AUTO, rig.now.pauseReason)
            assertEquals(target(0), rig.commands.last())
            rig.second(cadence = 70) // pedalling again
            assertEquals(RideState.RIDING, rig.now.state)
            rig.second(cadence = 70)
            assertEquals(target(33), rig.commands.last())
            rig.seconds(2)
            assertEquals(target(100), rig.commands.last())
        }

    @Test
    fun `intensity scales the ERG target in steps of 5 percent between 50 and 150`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(105, rig.now.intensityPercent)
            assertEquals(105, rig.now.targetPower)
            assertEquals(target(105), rig.commands.last())

            repeat(20) { rig.engine.intensityUp() }
            rig.settle()
            assertEquals(150, rig.now.intensityPercent)
            assertEquals(target(150), rig.commands.last())

            repeat(30) { rig.engine.intensityDown() }
            rig.settle()
            assertEquals(50, rig.now.intensityPercent)
            assertEquals(target(50), rig.commands.last())

            rig.engine.intensityUp()
            rig.followTrainer(60)
            assertEquals(55, rig.now.intensityPercent)
            assertEquals(target(88), rig.commands.last(), "step B at 55% of 160 W")
        }

    @Test
    fun `intensity does nothing in a free-ride step but is remembered`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.engine.intensityUp()
            rig.followTrainer(120) // into step C, the free ride
            assertEquals(TrainerMode.SIMULATION, rig.now.mode)
            val before = rig.commands.size
            rig.engine.intensityUp()
            rig.engine.intensityDown()
            rig.settle()
            assertEquals(105, rig.now.intensityPercent)
            assertEquals(before, rig.commands.size)
        }

    @Test
    fun `intensity does nothing without a workout`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.engine.intensityUp()
            rig.settle()
            assertEquals(100, rig.now.intensityPercent)
        }

    @Test
    fun `the target is limited to what the trainer can hold`() =
        runTest {
            val workout = Workout(1, "hard", "Hard", steps = listOf(SteadyStep(duration = 30, target = 1.2)))
            val rig = EngineRig(this, workout = workout, settings = RideSettings(ftp = 1200))
            rig.start()
            assertEquals(target(1440), rig.commands.last())
            repeat(4) { rig.engine.intensityUp() }
            rig.settle()
            assertEquals(target(1500), rig.commands.last(), "1440 W at 120% would be 1728 W")
            assertEquals(1728, rig.now.targetPower, "the workout still shows what it asks for")
        }

    @Test
    fun `a ramp step moves the target every few seconds without repeating a value`() =
        runTest {
            val workout = Workout(1, "ramp", "Ramp", steps = listOf(RampStep(duration = 60, from = 0.5, to = 1.0)))
            val rig = EngineRig(this, workout = workout)
            rig.start()
            rig.followTrainer(59)
            val targets = rig.commands.filterIsInstance<TrainerCommand.TargetPower>().map { it.watts }.drop(1)
            assertEquals(targets.sorted(), targets)
            assertEquals(targets.distinct(), targets)
            assertEquals(100, targets.first())
            assertTrue(targets.last() in 197..199)
        }

    @Test
    fun `after a reconnect the engine starts over with a request for control and the current command`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(5)
            val before = rig.commands.size

            rig.trainer.disconnect()
            rig.settle()
            rig.seconds(5, power = null, cadence = null)
            assertEquals(before, rig.commands.size, "nothing is sent to a trainer that is gone")
            rig.trainer.connect()
            rig.settle()
            rig.followTrainer(4)
            assertEquals(
                listOf(TrainerCommand.RequestControl, target(0), target(33), target(67), target(100)),
                rig.commands.drop(before),
                "control first, then the ramp-in",
            )
            assertEquals(0, rig.trainer.refusedCommands, "the strict trainer never saw an unwanted command")
        }

    @Test
    fun `a reconnect in the free ride sends the gear again`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.engine.gearUp()
            rig.engine.gearUp()
            rig.settle()
            rig.trainer.disconnect()
            rig.settle()
            rig.trainer.connect()
            rig.settle()
            assertEquals(listOf(TrainerCommand.RequestControl, simulation(2.0)), rig.commands.takeLast(2))
            assertEquals(simulation(2.0).parameters, rig.trainer.simulation)
        }

    @Test
    fun `a command that fails is retried after a second, starting over with control`() =
        runTest {
            val rig = EngineRig(this, connectTrainer = false)
            rig.trainer.failNextCommands(2)
            rig.trainer.connect()
            rig.settle()
            assertTrue(rig.commands.isEmpty())
            assertEquals(1, rig.trainer.refusedCommands)
            advanceSeconds(1)
            assertEquals(2, rig.trainer.refusedCommands)
            assertTrue(rig.commands.isEmpty())
            advanceSeconds(1)
            assertEquals(listOf(TrainerCommand.RequestControl, simulation(0.0)), rig.commands)
            advanceSeconds(10)
            assertEquals(2, rig.commands.size, "and it stops retrying once it worked")
        }

    @Test
    fun `finishing leaves the trainer on the flat, not holding the last target`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(10)
            assertEquals(target(100), rig.commands.last())
            rig.engine.gearUp() // no effect in ERG, but must not matter at the end either
            rig.engine.finish()
            rig.settle()
            assertEquals(RideState.FINISHED, rig.now.state)
            assertEquals(simulation(0.0), rig.commands.last())
            assertEquals(simulation(0.0).parameters, rig.trainer.simulation)
            assertNull(rig.trainer.targetPowerWatts)
        }

    @Test
    fun `the last command of a finished ride is tried a few times and then given up on`() =
        runTest {
            val rig = EngineRig(this, workout = abcWorkout)
            rig.start()
            rig.followTrainer(3)
            rig.trainer.failNextCommands(100)
            rig.engine.finish()
            rig.settle()
            advanceSeconds(30)
            assertEquals(RideState.FINISHED, rig.now.state)
            assertEquals(3, rig.trainer.refusedCommands)
        }

    @Test
    fun `a workout that ends frees the trainer too`() =
        runTest {
            val workout = Workout(1, "short", "Short", steps = listOf(SteadyStep(duration = 5, target = 0.9)))
            val rig = EngineRig(this, workout = workout)
            rig.start()
            rig.followTrainer(5)
            assertEquals(RideState.FINISHED, rig.now.state)
            assertEquals(simulation(0.0), rig.commands.last())
        }

    @Test
    fun `a trainer that is gone when the ride ends is simply left`() =
        runTest {
            val rig = EngineRig(this)
            rig.start()
            rig.seconds(3)
            rig.trainer.disconnect()
            rig.settle()
            val before = rig.commands.size
            rig.engine.finish()
            rig.settle()
            advanceSeconds(10)
            assertEquals(RideState.FINISHED, rig.now.state)
            assertEquals(before, rig.commands.size)
        }

    /** A trainer whose writes take [writeMillis] each, like a congested Bluetooth link. */
    private class SlowTrainer(
        private val inner: SimulatedTrainerDevice,
        private val writeMillis: Long,
    ) : TrainerDevice {
        override val name: String get() = inner.name
        override val connection get() = inner.connection
        override val data get() = inner.data

        override suspend fun requestControl() {
            delay(writeMillis)
            inner.requestControl()
        }

        override suspend fun setTargetPower(watts: Int) {
            delay(writeMillis)
            inner.setTargetPower(watts)
        }

        override suspend fun setSimulation(parameters: SimulationParameters) {
            delay(writeMillis)
            inner.setSimulation(parameters)
        }
    }

    @Test
    fun `a slow trainer never delays the ride clock and only the latest target is sent`() =
        runTest {
            val inner = SimulatedTrainerDevice(clock = { testScheduler.currentTime })
            val workout = Workout(1, "ramp", "Ramp", steps = listOf(RampStep(duration = 100, from = 0.2, to = 1.0)))
            val engine =
                RideEngine(
                    scope = backgroundScope,
                    trainer = SlowTrainer(inner, writeMillis = 4_500),
                    workout = workout,
                    settings = RideSettings(ftp = 250),
                )
            inner.connect()
            settle()
            engine.start()
            settle()
            repeat(60) {
                inner.emit(power = 100, cadence = 90)
                advanceSeconds(1)
            }
            assertEquals(60, engine.snapshots.value.elapsedSeconds, "the clock does not wait for the trainer")
            val targets = inner.received.map { it.command }.filterIsInstance<TrainerCommand.TargetPower>()
            assertTrue(
                targets.size in 5..20,
                "writes took 4.5 s each, so about ${60 / 4.5} of the 41 changes went out: $targets",
            )
            advanceSeconds(5)
            assertEquals(ConnectionState.CONNECTED, inner.connection.value)
            // The last write is never older than the write time: 60 s in the ramp is 0.2 + 0.8 * 0.6 = 68% of 250 W.
            assertTrue((inner.targetPowerWatts ?: 0) in 160..175, "got ${inner.targetPowerWatts}")
        }
}
