package app.overdrive.core.ride

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.LeafStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout

/** How the trainer is driven during a step (spec §4.5). */
public enum class TrainerMode {
    /** The trainer holds a target power: steady and ramp steps. */
    ERG,

    /** The trainer simulates a road at the rider's virtual gear: free-ride steps. */
    SIMULATION,
}

/**
 * One step of a workout with repeats expanded, which is what a player and a profile chart walk over.
 *
 * Targets are fractions of FTP (1.0 = FTP), as in the workout file. A steady step has
 * `targetFrom == targetTo`, a ramp keeps its `from` and `to`, and a free-ride step has both null.
 */
public data class FlatStep(
    /** The step's own label, else its repeat's label, else a default ("Steady", "Ramp", "Free ride"). */
    val label: String,
    val durationSeconds: Int,
    /** FTP fraction at the start of the step, null for a free ride. */
    val targetFrom: Double?,
    /** FTP fraction at the end of the step, null for a free ride. */
    val targetTo: Double?,
    val mode: TrainerMode,
    /** Offset of the step from the start of the workout, in seconds. */
    val startSeconds: Int = 0,
    /** Target cadence in rpm, for display only. */
    val cadence: Double? = null,
    /** 1-based iteration when the step comes from a repeat set, else null. */
    val repeatIteration: Int? = null,
    /** Iteration count of the repeat set the step comes from, else null. */
    val repeatCount: Int? = null,
) {
    init {
        require(durationSeconds >= 1) { "A step lasts at least one second, got $durationSeconds" }
        require(startSeconds >= 0) { "Step start must not be negative, got $startSeconds" }
        require((targetFrom == null) == (targetTo == null)) { "Both targets or neither must be set" }
        require(
            (mode == TrainerMode.ERG) == (targetFrom != null),
        ) { "ERG steps have a target, simulation steps do not" }
        require((repeatIteration == null) == (repeatCount == null)) { "Repeat iteration and count go together" }
        require(repeatIteration == null || repeatIteration in 1..checkNotNull(repeatCount)) {
            "Repeat iteration $repeatIteration is outside 1..$repeatCount"
        }
    }

    /** Offset of the end of the step from the start of the workout, in seconds. */
    public val endSeconds: Int get() = startSeconds + durationSeconds

    /**
     * The target as an FTP fraction [secondInStep] seconds into the step, or null for a free ride.
     *
     * A ramp is linear in time: it equals [targetFrom] at second 0 and approaches [targetTo] at the
     * end of the step, where the next step takes over. [secondInStep] is clamped to the step.
     */
    public fun targetFractionAt(secondInStep: Int): Double? {
        val from = targetFrom ?: return null
        val to = checkNotNull(targetTo)
        val t = secondInStep.coerceIn(0, durationSeconds)
        return from + (to - from) * t / durationSeconds
    }
}

/** Total length of a flattened workout in seconds. */
public fun List<FlatStep>.totalDurationSeconds(): Int = lastOrNull()?.endSeconds ?: 0

/**
 * Expands the workout's `repeat` steps into one flat, ordered list (spec §4.5, §8.1).
 *
 * `repeat { count = 3, steps = [A, B] }` becomes `A B A B A B`, each carrying its 1-based
 * iteration. Every step gets its [FlatStep.startSeconds], so the list can be drawn as a profile.
 */
public fun Workout.flatSteps(): List<FlatStep> {
    val flat = ArrayList<FlatStep>()
    var start = 0

    fun add(
        leaf: LeafStep,
        repeatLabel: String?,
        iteration: Int?,
        count: Int?,
    ) {
        val (default, from, to) =
            when (leaf) {
                is SteadyStep -> Triple("Steady", leaf.target, leaf.target)
                is RampStep -> Triple("Ramp", leaf.from, leaf.to)
                is FreeStep -> Triple("Free ride", null, null)
            }
        flat +=
            FlatStep(
                label = leaf.label ?: repeatLabel ?: default,
                durationSeconds = leaf.duration,
                targetFrom = from,
                targetTo = to,
                mode = if (from == null) TrainerMode.SIMULATION else TrainerMode.ERG,
                startSeconds = start,
                cadence = leaf.cadence,
                repeatIteration = iteration,
                repeatCount = count,
            )
        start += leaf.duration
    }

    for (step in steps) {
        when (step) {
            is LeafStep -> {
                add(step, null, null, null)
            }

            is RepeatStep -> {
                for (iteration in 1..step.count) {
                    for (leaf in step.steps) add(leaf, step.label, iteration, step.count)
                }
            }
        }
    }
    return flat
}
