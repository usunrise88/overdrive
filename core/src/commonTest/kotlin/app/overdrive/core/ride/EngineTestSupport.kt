package app.overdrive.core.ride

import app.overdrive.core.device.SimulatedHeartRateDevice
import app.overdrive.core.device.SimulatedTrainerDevice
import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.device.collectAll
import app.overdrive.core.record.CompositeRideRecorder
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal const val ENGINE_TEST_START_MILLIS = 1_700_000_000_000L

/**
 * A [RideEngine] over a manually driven [SimulatedTrainerDevice] and [SimulatedHeartRateDevice] in virtual time.
 * [second] delivers the readings of one second and lets the engine tick, so a test reads like a ride.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineRig(
    private val testScope: TestScope,
    val workout: Workout? = null,
    val settings: RideSettings = RideSettings(ftp = 200),
    withStrap: Boolean = true,
    connectTrainer: Boolean = true,
    connectStrap: Boolean = true,
    tickInterval: Duration = 1.seconds,
    /** Another recorder that gets the same calls as [recorder], such as the crash-safe log. */
    extraRecorder: RideRecorder? = null,
) {
    val trainer = SimulatedTrainerDevice(clock = { testScope.testScheduler.currentTime })
    val strap = SimulatedHeartRateDevice()
    val recorder = InMemoryRideRecorder()
    val engine =
        RideEngine(
            scope = testScope.backgroundScope,
            trainer = trainer,
            heartRate = if (withStrap) strap else null,
            workout = workout,
            settings = settings,
            recorder = if (extraRecorder == null) recorder else CompositeRideRecorder(recorder, extraRecorder),
            tickInterval = tickInterval,
            rideId = "test-ride",
            clock = { ENGINE_TEST_START_MILLIS + testScope.testScheduler.currentTime },
        )

    /** Every snapshot the engine publishes, starting with the first. */
    val snapshots: List<RideSnapshot> = testScope.collectAll(engine.snapshots)

    init {
        if (connectTrainer) trainer.connect()
        if (connectStrap) strap.connect()
        testScope.runCurrent()
    }

    val now: RideSnapshot get() = engine.snapshots.value

    val record: RideRecord get() = checkNotNull(recorder.record) { "the ride has not started" }

    /** The commands the trainer received, without their time stamps. */
    val commands: List<TrainerCommand> get() = trainer.received.map { it.command }

    /** Starts the ride and lets the engine process it. */
    fun start() {
        engine.start()
        testScope.runCurrent()
    }

    /** Runs the engine's pending work without advancing time, such as after a command or a device event. */
    fun settle() = testScope.runCurrent()

    /**
     * One second of riding: the devices deliver these readings, then a second passes and the engine ticks.
     * A null [bpm] leaves the strap silent; [trainerSilent] and [strapSilent] suppress a device's notification.
     */
    fun second(
        power: Int? = 200,
        cadence: Int? = 90,
        bpm: Int? = 140,
        rr: List<Int> = emptyList(),
        trainerSilent: Boolean = false,
        strapSilent: Boolean = false,
    ) {
        if (!trainerSilent) trainer.emit(power = power, cadence = cadence)
        if (!strapSilent && bpm != null) strap.emit(bpm, rr)
        testScope.advanceSeconds(1)
    }

    fun seconds(
        count: Int,
        power: Int? = 200,
        cadence: Int? = 90,
        bpm: Int? = 140,
    ) = repeat(count) { second(power, cadence, bpm) }

    /** The rider follows the trainer: the ERG target if there is one, else [freePower]. */
    fun followTrainer(
        count: Int,
        freePower: Int = 110,
        cadence: Int? = 90,
        bpm: Int? = 140,
    ) = repeat(count) { second(power = trainer.targetPowerWatts ?: freePower, cadence = cadence, bpm = bpm) }
}

/** Seconds of the ride on which each snapshot was published, for a test that wants to see the state per second. */
internal fun List<RideSnapshot>.states(): List<RideState> = map { it.state }
