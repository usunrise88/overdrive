package app.overdrive.core.ble.integration

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.adapter.FtmsOptions
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.adapter.HeartRateStrapDevice
import app.overdrive.core.ble.fake.FakeTrainerCommand
import app.overdrive.core.device.collectAll
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.ride.RideEngine
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

internal const val RIDE_START_MILLIS = 1_700_000_000_000L

/** Three half-minute steps: ERG at 50 % FTP, ERG at 80 % FTP, then a free ride. At FTP 200: 100 W, 160 W, simulation. */
internal val bleWorkout =
    Workout(
        schema = 1,
        id = "ble-ride",
        name = "BLE ride",
        steps =
            listOf(
                SteadyStep(duration = 30, target = 0.5, label = "A"),
                SteadyStep(duration = 30, target = 0.8, label = "B"),
                FreeStep(duration = 30, label = "C"),
            ),
    )

/** One steady ERG step of [seconds] at [target] of FTP. */
internal fun steadyWorkout(
    seconds: Int,
    target: Double = 0.8,
) = Workout(1, "steady", "Steady", steps = listOf(SteadyStep(duration = seconds, target = target)))

/**
 * The real [RideEngine] over [FtmsTrainerDevice] and [HeartRateStrapDevice] over a [BleRig]'s simulated Bluetooth: the
 * whole chain of the ride app except the Android transport, in virtual time. Both devices are started and the rig has
 * advanced a second, so the engine is `READY`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BleRide(
    testScope: TestScope,
    val rig: BleRig = BleRig(testScope),
    workout: Workout? = null,
    val settings: RideSettings = RideSettings(ftp = 200),
    trainerFactory: GattTransportFactory = rig.ble,
    strapFactory: GattTransportFactory = rig.ble,
    trainerOptions: FtmsOptions = FtmsOptions(),
) {
    val trainer: FtmsTrainerDevice = rig.trainer(trainerOptions, factory = trainerFactory)
    val strap: HeartRateStrapDevice = rig.strap(factory = strapFactory)
    val recorder = InMemoryRideRecorder()
    val engine =
        RideEngine(
            scope = testScope.backgroundScope,
            trainer = trainer,
            heartRate = strap,
            workout = workout,
            settings = settings,
            recorder = recorder,
            rideId = "ble-ride",
            clock = { RIDE_START_MILLIS + testScope.testScheduler.currentTime },
        )
    val snapshots: List<RideSnapshot> = testScope.collectAll(engine.snapshots)

    init {
        trainer.start()
        strap.start()
        rig.advance(1_000)
    }

    val now: RideSnapshot get() = engine.snapshots.value
    val record: RideRecord get() = checkNotNull(recorder.record) { "the ride has not started" }

    fun start() {
        engine.start()
        rig.settle()
    }

    /** The commands the trainer received, as short strings: `RC`, `START`, `TARGET 160`, `SIM 0.0`. */
    fun commands(): List<String> =
        rig.fakeTrainer.commands.map {
            when (it) {
                is FakeTrainerCommand.RequestControl -> "RC"
                is FakeTrainerCommand.Reset -> "RESET"
                is FakeTrainerCommand.StartOrResume -> "START"
                is FakeTrainerCommand.StopOrPause -> "STOP"
                is FakeTrainerCommand.TargetPower -> "TARGET ${it.watts}"
                is FakeTrainerCommand.Simulation -> "SIM ${it.gradePercent}"
                is FakeTrainerCommand.Unknown -> "?"
            }
        }

    fun states(): List<RideState> = snapshots.map { it.state }
}
