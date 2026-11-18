package app.overdrive.core.ble.integration

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.STRAP_ID
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.fake.ReplayPeripheral
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.record.RideRecord
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.seconds

/**
 * Synthetic rides that are run live over the fake Bluetooth, recorded through the decorator, and replayed: the source
 * of the session fixtures of `contracts/fixtures/ble/` and of the record-and-replay test. Nothing here comes from a real
 * device or a real body: every byte is generated.
 */
internal enum class SyntheticRide(
    /** File name stem of the fixtures: `<stem>.trainer.session.txt` and `<stem>.strap.session.txt`. */
    val stem: String,
    val workout: Workout,
    /** How long the scenario runs after the ride starts, in milliseconds. */
    val durationMillis: Long,
) {
    /** A 90 s workout in ERG then free ride, nothing going wrong. */
    ERG_WORKOUT("erg-workout", bleWorkout, 95_000),

    /**
     * A 60 s ERG ride in which the trainer's link drops with status 133 after 20 s and the first reconnect attempt fails
     * with 133 too, and the strap is off the chest for ten seconds.
     */
    DROPOUTS("dropouts", steadyWorkout(60, 0.8), 75_000),
    ;

    /** Scripts what goes wrong, at ride-relative times (the ride starts one second after the devices). */
    fun script(ride: BleRide) {
        when (this) {
            ERG_WORKOUT -> {
                Unit
            }

            DROPOUTS -> {
                val trainer = ride.rig.fakeTrainer
                // Queued now, after the first connection: the first reconnect attempt fails with 133 too.
                trainer.faults.failNextConnects(1, GattStatus.GATT_ERROR)
                trainer.dropLinkAt(21.seconds, GattStatus.GATT_ERROR)
                val strap = ride.rig.fakeStrap
                ride.rig.testScope.backgroundScope.launch {
                    delay(29.seconds) // the clock stands at 1 s: now it is 30 s
                    strap.onChest = false
                    delay(10.seconds)
                    strap.onChest = true
                }
            }
        }
    }
}

/** A ride that ran live, with the two sessions it recorded and the record the engine produced. */
internal class RecordedRide(
    val trainerSession: String,
    val strapSession: String,
    val record: RideRecord,
)

/** Runs [scenario] live over the fake Bluetooth, recording both devices, and returns what was recorded. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.recordLiveRide(scenario: SyntheticRide): RecordedRide {
    val rig = BleRig(this)
    val trainerLines = rig.recording(model = "D500")
    val strapLines = rig.recording(model = "HR7")
    val ride =
        BleRide(
            this,
            rig,
            scenario.workout,
            trainerFactory = trainerLines.factory,
            strapFactory = strapLines.factory,
        )
    scenario.script(ride)
    ride.start()
    rig.advance(scenario.durationMillis)
    ride.trainer.close()
    ride.strap.close()
    rig.settle()
    trainerLines.recorder.close()
    strapLines.recorder.close()
    rig.settle()
    return RecordedRide(
        trainerLines.lines.joinToString("\n", postfix = "\n"),
        strapLines.lines.joinToString("\n", postfix = "\n"),
        ride.record,
    )
}

/** Plays the two recorded sessions back through the real adapters and engine, and returns the ride record. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.replayRide(
    scenario: SyntheticRide,
    trainerSession: String,
    strapSession: String,
): RideRecord {
    val rig = BleRig(this)
    rig.ble.add(TRAINER_ID, ReplayPeripheral(BleSessionFormat.parse(trainerSession)))
    rig.ble.add(STRAP_ID, ReplayPeripheral(BleSessionFormat.parse(strapSession)))
    val ride = BleRide(this, rig, scenario.workout)
    ride.start()
    rig.advance(scenario.durationMillis)
    check(rig.ble.violations.isEmpty()) { "The replay broke the contract: ${rig.ble.violations}" }
    return ride.record
}
