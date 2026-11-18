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
