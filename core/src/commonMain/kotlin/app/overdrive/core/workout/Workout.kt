package app.overdrive.core.workout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Native workout format, schema 1 (spec §8.1). Targets are fractions of FTP, durations are seconds.
 *
 * The JSON Schema in `contracts/schema/workout.schema.json` is the source of truth. This model must
 * accept every fixture under `contracts/fixtures/workouts/valid` and reject every one under
 * `invalid`; `WorkoutContractTest` enforces that.
 */
@Serializable
public data class Workout(
    val schema: Int,
    val id: String,
    val name: String,
    val description: String? = null,
    val steps: List<Step>,
) {
    init {
        require(schema == SCHEMA_VERSION) { "Unsupported workout schema $schema, expected $SCHEMA_VERSION" }
        require(id.isNotEmpty()) { "Workout id must not be empty" }
        require(name.isNotEmpty()) { "Workout name must not be empty" }
        require(steps.isNotEmpty()) { "A workout needs at least one step" }
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1
    }
}

/** One element of a workout. The JSON `type` field selects the subtype. */
@Serializable
public sealed interface Step

/** A step that cannot contain other steps. Repeats do not nest in schema 1. */
@Serializable
public sealed interface LeafStep : Step {
    /** Seconds. */
    public val duration: Int

    /** Target cadence in rpm. Display only. */
    public val cadence: Double?
    public val label: String?
}

@Serializable
@SerialName("steady")
public data class SteadyStep(
    override val duration: Int,
    /** Fraction of FTP. */
    val target: Double,
    override val cadence: Double? = null,
    override val label: String? = null,
) : LeafStep {
    init {
        requireValidDuration(duration)
        requireValidTarget(target)
        requireValidCadence(cadence)
    }
}

@Serializable
@SerialName("ramp")
public data class RampStep(
    override val duration: Int,
    val from: Double,
    val to: Double,
    override val cadence: Double? = null,
    override val label: String? = null,
) : LeafStep {
    init {
        requireValidDuration(duration)
        requireValidTarget(from)
        requireValidTarget(to)
        requireValidCadence(cadence)
    }
}

/** Free ride: simulation mode with gears, no power target. */
@Serializable
@SerialName("free")
public data class FreeStep(
    override val duration: Int,
    override val cadence: Double? = null,
    override val label: String? = null,
) : LeafStep {
    init {
        requireValidDuration(duration)
        requireValidCadence(cadence)
    }
}

/**
 * Heart-rate hold (spec §11.2): the trainer runs in ERG mode, and a slow controller (`HeartRateHold` in the ride package)
 * chooses the target power so that the heart rate stays at the target. There is no FTP in it: the target is a heart rate
 * and the power limits are absolute watts.
 *
 * Exactly one of [targetBpm] (beats per minute) and [targetLthr] (a fraction of the rider's threshold heart rate, which
 * the service resolves to bpm when it serves the workout to the tablet, docs/adr/0033; a step still in this form has no
 * heart rate the tablet can hold, and `flatSteps` turns it into a free-ride step unless it is told the threshold).
 */
@Serializable
@SerialName("hr")
public data class HeartRateStep(
    override val duration: Int,
    @SerialName("target_bpm") val targetBpm: Int? = null,
    @SerialName("target_lthr") val targetLthr: Double? = null,
    /** Half-width of the band in bpm: the heart rate is inside the band within this many bpm of the target. */
    val band: Int = DEFAULT_BAND_BPM,
    /** The least power in watts the controller may command; the start ramp begins here. */
    @SerialName("power_min") val powerMin: Int,
    /** The most power in watts the controller may command. */
    @SerialName("power_max") val powerMax: Int,
    override val cadence: Double? = null,
    override val label: String? = null,
) : LeafStep {
    init {
        requireValidDuration(duration)
        requireValidCadence(cadence)
        require((targetBpm == null) != (targetLthr == null)) {
            "A heart-rate step needs exactly one of target_bpm and target_lthr"
        }
        require(targetBpm == null || targetBpm in MIN_BPM..MAX_BPM) {
            "Target heart rate must be $MIN_BPM..$MAX_BPM bpm, got $targetBpm"
        }
        require(targetLthr == null || (targetLthr >= MIN_LTHR_FRACTION && targetLthr <= MAX_LTHR_FRACTION)) {
            "Threshold fraction must be $MIN_LTHR_FRACTION..$MAX_LTHR_FRACTION, got $targetLthr"
        }
        require(band in 1..MAX_BAND_BPM) { "Band must be 1..$MAX_BAND_BPM bpm, got $band" }
        require(powerMin in 1..MAX_POWER_WATTS && powerMax in 1..MAX_POWER_WATTS) {
            "Power limits must be 1..$MAX_POWER_WATTS W, got $powerMin and $powerMax"
        }
        require(powerMin < powerMax) { "power_min ($powerMin) must be below power_max ($powerMax)" }
    }

    public companion object {
        /** The band of a step that does not say (spec §11.2). */
        public const val DEFAULT_BAND_BPM: Int = 5
        public const val MIN_BPM: Int = 40
        public const val MAX_BPM: Int = 230
        public const val MIN_LTHR_FRACTION: Double = 0.5
        public const val MAX_LTHR_FRACTION: Double = 1.2
        public const val MAX_BAND_BPM: Int = 30
        public const val MAX_POWER_WATTS: Int = 2000
    }
}

@Serializable
@SerialName("repeat")
public data class RepeatStep(
    val count: Int,
    val steps: List<LeafStep>,
    val label: String? = null,
) : Step {
    init {
        require(count >= 1) { "Repeat count must be at least 1, got $count" }
        require(steps.isNotEmpty()) { "A repeat needs at least one step" }
    }
}

private const val MAX_FTP_FRACTION = 3.0

private fun requireValidDuration(duration: Int) =
    require(duration >= 1) { "Duration must be at least 1 second, got $duration" }

private fun requireValidTarget(fraction: Double) =
    require(fraction > 0.0 && fraction <= MAX_FTP_FRACTION) {
        "FTP fraction must be in (0, $MAX_FTP_FRACTION], got $fraction"
    }

private fun requireValidCadence(cadence: Double?) =
    require(cadence == null || cadence > 0.0) { "Cadence must be positive, got $cadence" }
