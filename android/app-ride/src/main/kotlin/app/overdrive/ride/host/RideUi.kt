package app.overdrive.ride.host

import androidx.compose.runtime.Immutable
import app.overdrive.core.click.UnlockSignal
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideSummary
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.PowerZone
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.Workout
import app.overdrive.ride.rides.SaveState
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.chart.TracePoint
import kotlin.math.roundToInt

/** What the rider chose to ride on the Ready screen. */
sealed interface RideChoice {
    val title: String

    /** Simulation on the flat with virtual gears and no end. */
    data object Free : RideChoice {
        override val title: String = "Free ride"
    }

    /**
     * A workout, played step by step. [variant] says it is the easier version of a planned workout (spec §11.5): [workout] is then
     * the planned workout with the variant's steps, so its id is the planned workout's, and [variantNote] is what the agent wrote with
     * it.
     */
    data class Planned(
        val workout: Workout,
        val variant: Boolean = false,
        val variantNote: String? = null,
    ) : RideChoice {
        override val title: String get() = workout.name
    }

    val workoutOrNull: Workout? get() = (this as? Planned)?.workout
}

/** One lap row of the finished ride's table. */
@Immutable
data class LapRow(
    val number: Int,
    val name: String,
    val durationSeconds: Int,
    val averagePower: Int?,
    val averageHeartRate: Int?,
)

/** The finished ride's summary screen (design/system.md §6.4), computed once from the engine's record. */
@Immutable
data class FinishedRide(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val summary: RideSummary,
    val laps: List<LapRow>,
    /** Seconds in Z1 to Z7 over the seconds that were not paused and had a power reading. */
    val zoneSeconds: List<Int>,
    /** Whether the ride is stored yet: the record and the FIT file are written after the finish (ADR 0008). */
    val save: SaveState = SaveState.Saving,
) {
    companion object {
        /** Builds the summary from [record]; [steps] name the laps (empty for a free ride, whose single lap is the ride). */
        fun from(
            record: RideRecord,
            title: String,
            steps: List<FlatStep>,
        ): FinishedRide {
            val zones = IntArray(7)
            for (sample in record.samples) {
                val power = sample.power
                if (!sample.paused && power != null) zones[PowerZone.of(power, record.ride.ftp).number - 1]++
            }
            return FinishedRide(
                title = title,
                startEpochMillis = record.ride.startEpochMillis,
                endEpochMillis = record.ride.endEpochMillis,
                summary = record.summary,
                laps =
                    record.laps.mapIndexed { index, lap ->
                        LapRow(
                            number = index + 1,
                            name = lap.stepIndex?.let { steps.getOrNull(it)?.label } ?: title,
                            durationSeconds = lap.durationSeconds,
                            averagePower = lap.averagePower?.roundToInt(),
                            averageHeartRate = lap.averageHeartRate?.roundToInt(),
                        )
                    },
                zoneSeconds = zones.toList(),
            )
        }
    }
}

/**
 * Everything the ride screens show, in one value: the engine's snapshot plus what the screens add to it
 * (averages, the power trace, the finished summary). Republished with every snapshot.
 */
data class RideUi(
    val snapshot: RideSnapshot,
    val choice: RideChoice,
    /** The workout's steps for the profile chart, one instance per ride; null for a free ride. */
    val profile: ProfileData?,
    /** Mean power over the riding seconds so far, or null before there is any. */
    val averagePower: Int?,
    /** Mean power since the current workout step began. */
    val lapAveragePower: Int?,
    /** The rider's power over the workout, one point per 10 s, refreshed once per 10 s. */
    val trace: List<TracePoint>,
    /** Set once the ride is [RideState.FINISHED]. */
    val finished: FinishedRide?,
    /** 1, 2, 3, … per ride in this process; changes when a new ride is prepared. */
    val rideNumber: Int,
    /** True while the ride is not being written to disk (a full disk, a missing folder); the ride itself is unaffected. */
    val logFailed: Boolean = false,
)

/** One row of the Devices screen. */
@Immutable
data class DeviceRowUi(
    val status: DeviceStatus,
    val subtitle: String,
    /** The row has a paired device that **Forget** can remove. */
    val canForget: Boolean = false,
    /**
     * Set on a Click row that looks locked by Zwift, with the sign that showed it, for the notice below the row (spec
     * §4.7, ADR 0012): its service is hidden, or it answered and sent nothing. Null for every other row.
     */
    val locked: UnlockSignal? = null,
)

/** Why Bluetooth cannot be used right now, as the rider can fix it (design/system.md §6.5). */
enum class RadioIssue {
    /** The adapter is off: "Turn on" opens the system's Bluetooth settings. */
    BluetoothOff,

    /** The Nearby-devices permission is missing: "Allow" asks for it. */
    PermissionNeeded,

    /** The tablet has no Bluetooth LE: nothing to fix, the simulated devices stay. */
    Unsupported,
}

/**
 * The three device rows of the Devices screen and top bar: trainer, heart-rate strap, Click; and what is true of all of
 * them.
 *
 * @property radio why Bluetooth cannot be used, or null. Shown as one notice with one action on the Ready and Devices
 * screens (the pattern of the overlay's permission notice).
 * @property simulated the rows are the simulated devices of the developer setting, not Bluetooth.
 * @property canChange pairing and forgetting are offered; false while a ride is live, because a ride never changes its
 * devices.
 */
@Immutable
data class DevicesUi(
    val trainer: DeviceRowUi,
    val heartRate: DeviceRowUi,
    val click: DeviceRowUi,
    val radio: RadioIssue? = null,
    val simulated: Boolean = false,
    val canChange: Boolean = true,
) {
    companion object {
        val Unknown =
            DevicesUi(
                DeviceRowUi(DeviceStatus.Searching, "Connecting…"),
                DeviceRowUi(DeviceStatus.NotPaired, "Not paired"),
                DeviceRowUi(DeviceStatus.NotPaired, "Not paired"),
            )
    }
}
