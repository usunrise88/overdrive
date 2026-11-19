package app.overdrive.core.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The ride record of spec §8.2, in memory: what the ride engine recorded, from which the FIT file is generated
 * (spec §4.8) and which can be regenerated at any time. All times are offsets in whole seconds from the start
 * of the ride; the first second is 1.
 *
 * **Gaps are nulls.** A power, heart-rate or cadence value that was not measured is null in its
 * [RideSample]; the sample itself is still there, so [RideRecord.samples] has one entry for every second of the
 * ride, however many sensor readings were missing.
 *
 * The wire format is `contracts/schema/ride-record.schema.json`; [RideRecordJson] reads and writes it. The parts
 * (header, sample, RR interval, lap, summary) are `@Serializable` under exactly their property names.
 */
public data class RideRecord(
    val ride: RideHeader,
    /** One per second of the ride, in order, offsets `1..duration`. */
    val samples: List<RideSample>,
    /** Beat-to-beat intervals from the strap, in the order received; may be empty. */
    val rrIntervals: List<RrInterval>,
    /** One per stretch of the ride spent in one workout step, in order; see [Lap]. */
    val laps: List<Lap>,
    val summary: RideSummary,
    /**
     * Things that happened during the ride and are worth telling the coach, in the order they happened (spec §11.1): the
     * ERG stall guard taking hold. Empty for a ride where nothing did, and for every record written before they existed.
     */
    val events: List<RideEvent> = emptyList(),
)

/** The kinds of [RideEvent]; the wire names are the schema's. */
@Serializable
public enum class RideEventType {
    /** The ERG stall guard lowered the target power because cadence fell below the floor (spec §11.1). */
    @SerialName("stall-guard")
    STALL_GUARD,
}

/**
 * An implicit-feedback event of the ride (spec §11.1, `contracts/schema/ride-record.schema.json` `events`).
 */
@Serializable
public data class RideEvent(
    val type: RideEventType,
    /** The tick at which it was decided: a sample offset, from 1 to the number of samples. */
    val timeOffsetSeconds: Int,
    /** The index in the flattened workout of the step it happened in, null without a workout. */
    val stepIndex: Int? = null,
) {
    init {
        require(timeOffsetSeconds >= 1) { "An event's offset is at least 1 second, got $timeOffsetSeconds" }
        require(stepIndex == null || stepIndex >= 0) { "A step index must not be negative, got $stepIndex" }
    }
}

/**
 * The ride's identity (spec §8.2 "Ride"). The sync state of the finished ride lives in the outbox of the data
 * layer (spec §4.9), not here.
 */
@Serializable
public data class RideHeader(
    val id: String,
    /** Wall-clock start of the ride, milliseconds since the Unix epoch. */
    val startEpochMillis: Long,
    /** Wall-clock end of the ride, or null while it is still going. */
    val endEpochMillis: Long? = null,
    /** The id of the workout that was played, or null for a free ride. */
    val workoutId: String? = null,
    /** FTP in watts at the time of the ride, which the zones, the intensity factor and the load are based on. */
    val ftp: Int,
    /** Names of the devices the ride used, trainer first. */
    val devices: List<String> = emptyList(),
) {
    init {
        require(id.isNotEmpty()) { "A ride needs an id" }
        require(startEpochMillis >= 0) { "A ride cannot start before 1970, got $startEpochMillis" }
        require(ftp > 0) { "FTP must be positive, got $ftp" }
    }
}

/**
 * One second of the ride (spec §8.2 "Sample"). The sample at offset *k* describes the second that ended *k*
 * seconds after the start: the sensor readings at its end, and the plan that was in force during it (target,
 * gear, step). So the samples of a workout step add up to exactly the step's duration.
 */
@Serializable
public data class RideSample(
    /** Seconds since the start of the ride, at the end of the second described. At least 1. */
    val timeOffsetSeconds: Int,
    /** Watts, or null when no power was measured. */
    val power: Int? = null,
    /** Beats per minute, or null when there was no heart-rate signal. */
    val heartRate: Int? = null,
    /** Revolutions per minute, or null when there was no cadence signal. */
    val cadence: Int? = null,
    /** Virtual speed from the physics model (spec §4.3). 0 while paused or without power. */
    val speedKmh: Double = 0.0,
    /** Virtual distance travelled since the start, in metres. */
    val distanceMeters: Double = 0.0,
    /** The virtual gear (1-based) in a free-ride step, else null. */
    val gear: Int? = null,
    /**
     * The workout's target power for this second in watts: the step's target times the intensity setting,
     * without the ramp-in after a pause. Null in a free-ride step and without a workout.
     */
    val targetPower: Int? = null,
    /** Index of the workout step in the flattened workout (repeats expanded), null without a workout. */
    val stepIndex: Int? = null,
    /** Whether the ride was paused (manually, automatically or because the trainer was gone) during this second. */
    val paused: Boolean = false,
    /**
     * In a heart-rate step (spec §11.2): the heart rate in bpm the controller aimed at during this second, the step's
     * target moved by the rider's gear buttons. Null in every other step and during the heart-rate step's start ramp,
     * when the controller is not in charge yet: a second without it is not judged for the step's compliance.
     */
    val targetHeartRate: Int? = null,
) {
    init {
        require(timeOffsetSeconds >= 1) { "A sample's offset is at least 1 second, got $timeOffsetSeconds" }
        require(speedKmh.isFinite() && speedKmh >= 0.0) { "Speed must be a number, not negative, got $speedKmh" }
        require(distanceMeters.isFinite() && distanceMeters >= 0.0) {
            "Distance must be a number, not negative, got $distanceMeters"
        }
        require(gear == null || gear >= 1) { "Gears are numbered from 1, got $gear" }
        require(targetPower == null || targetPower >= 0) { "Target power must not be negative, got $targetPower" }
        require(stepIndex == null || stepIndex >= 0) { "A step index must not be negative, got $stepIndex" }
        require(targetHeartRate == null || targetHeartRate >= 1) {
            "A target heart rate is positive, got $targetHeartRate"
        }
        require(power == null || power >= 0) { "Power must not be negative, got $power" }
        require(heartRate == null || heartRate >= 0) { "Heart rate must not be negative, got $heartRate" }
        require(cadence == null || cadence >= 0) { "Cadence must not be negative, got $cadence" }
    }
}

/** One RR interval (spec §8.2 "RR series"): the time between two heartbeats. */
@Serializable
public data class RrInterval(
    /** The second of the ride in which the strap's notification carrying it arrived. */
    val timeOffsetSeconds: Int,
    /** Milliseconds. */
    val intervalMs: Int,
) {
    init {
        require(timeOffsetSeconds >= 1) { "An offset is at least 1 second, got $timeOffsetSeconds" }
        require(intervalMs > 0) { "An RR interval is positive, got $intervalMs" }
    }
}

/**
 * A stretch of the ride in one workout step (spec §8.2 "Lap"): the samples from `startSeconds + 1` to
 * `endSeconds`. Laps follow each other without gaps. A new lap starts whenever the step changes, so skipping
 * back to an earlier step starts another lap for it and a skipped step has none; a ride without a workout is a
 * single lap with a null [stepIndex].
 */
@Serializable
public data class Lap(
    val stepIndex: Int?,
    /** Offset at which the lap starts, which is the end of the previous lap (0 for the first). */
    val startSeconds: Int,
    /** Offset at which the lap ends. */
    val endSeconds: Int,
    /** Average power in watts over the lap's non-paused seconds with a power reading, or null if there were none. */
    val averagePower: Double?,
    /** Average heart rate in bpm over the lap's non-paused seconds with a heart-rate reading, or null. */
    val averageHeartRate: Double?,
) {
    init {
        require(startSeconds >= 0 && endSeconds > startSeconds) {
            "A lap ends after it starts, got $startSeconds to $endSeconds"
        }
    }

    /** Length of the lap in seconds. */
    public val durationSeconds: Int get() = endSeconds - startSeconds
}

/**
 * The summary of a ride (spec §8.2 "Summary"). Everything is computed from the samples the rider actually pedalled:
 * **paused seconds are left out** of the averages, the maxima, the work and the normalised power, as a head unit with
 * auto-pause would; a second without a reading for a quantity is left out of that quantity only.
 */
@Serializable
public data class RideSummary(
    /** Length of the ride in seconds, pauses included: the number of samples. */
    val durationSeconds: Int,
    /** Seconds not paused. */
    val movingSeconds: Int,
    /** Virtual distance in metres. */
    val distanceMeters: Double,
    /** Mean power in watts over the moving seconds with a power reading; null if there were none. */
    val averagePower: Double?,
    /**
     * Normalised power in watts (Coggan): the 30 s rolling average of power, each value raised to the fourth power,
     * averaged, and the fourth root taken. The series is the moving seconds with a power reading, in order; null when
     * it has fewer than 30 values, as there is no complete window then.
     */
    val normalizedPower: Double?,
    val maxPower: Int?,
    /** Mean heart rate in bpm over the moving seconds with a reading; null if there were none. */
    val averageHeartRate: Double?,
    val maxHeartRate: Int?,
    /** Mechanical work in kilojoules: the sum of power over the moving seconds. */
    val workKilojoules: Double,
    /** Intensity factor: [normalizedPower] over FTP. Null when there is no normalised power. */
    val intensityFactor: Double?,
    /**
     * Training stress score (the "load" of spec §8.2), Coggan's formula `t · NP · IF / (FTP · 3600) · 100` with `t`
     * the length of the power series in seconds. 100 is one hour at FTP. Null when there is no normalised power.
     */
    val trainingStressScore: Double?,
) {
    public companion object {
        /** The summary of [samples] for a rider with the given [ftp], as the engine's recorder computes it. */
        public fun of(
            samples: List<RideSample>,
            ftp: Int,
        ): RideSummary {
            val accumulator = SummaryAccumulator(ftp)
            samples.forEach(accumulator::add)
            return accumulator.summary()
        }
    }
}
