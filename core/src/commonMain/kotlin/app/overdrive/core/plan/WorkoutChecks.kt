package app.overdrive.core.plan

import app.overdrive.core.ride.flatSteps
import app.overdrive.core.workout.LeafStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.Workout
import kotlin.math.sqrt

/** Whether the workout player can run a workout from the plan. */
public sealed interface Playability {
    public data object Runnable : Playability

    /** [reason] is a short sentence for the row that is shown disabled: plain words, no apology. */
    public data class Unrunnable(
        val reason: String,
    ) : Playability
}

/**
 * What the ride app will play (ADR 0017). Every workout the native schema accepts has steps the player knows (steady, ramp,
 * repeat, free), so what is left to refuse is a workout the **ride** cannot hold: the player expands repeats into a list
 * of steps, and a ride is one record of one sample per second that must still fit the upload (spec §8.2, ADR 0014 section 7).
 */
public object WorkoutLimits {
    /** Twelve hours: a ride record of that length is about 10 MB of JSON, under the service's 16 MiB limit. */
    public const val MAX_SECONDS: Long = 12L * 3600

    /** Steps after the repeats are expanded: far more than any session (a 6 h workout of 30 s on and off is 720). */
    public const val MAX_STEPS: Long = 2_000
}

/**
 * Whether the player can run this workout, decided from the numbers in its steps **without expanding them**: a repeat of
 * two billion rounds is refused here and never reaches `flatSteps`, which would run out of memory (and whose start times
 * would overflow an `Int`). Never throws.
 */
public fun Workout.playability(): Playability {
    var seconds = 0L
    var steps = 0L
    for (step in this.steps) {
        when (step) {
            is LeafStep -> {
                seconds = saturatingAdd(seconds, step.duration.toLong())
                steps = saturatingAdd(steps, 1)
            }

            is RepeatStep -> {
                val round = step.steps.sumOf { it.duration.toLong() }
                seconds = saturatingAdd(seconds, saturatingMultiply(step.count.toLong(), round))
                steps = saturatingAdd(steps, saturatingMultiply(step.count.toLong(), step.steps.size.toLong()))
            }
        }
    }
    return when {
        seconds > WorkoutLimits.MAX_SECONDS -> Playability.Unrunnable("Longer than 12 hours")
        steps > WorkoutLimits.MAX_STEPS -> Playability.Unrunnable("More than 2000 steps")
        else -> Playability.Runnable
    }
}

/**
 * The intensity factor the workout's own targets add up to: the normalised power of the targeted power (a 30 s rolling
 * average, the mean of its fourth powers, the fourth root) as a fraction of FTP, which is what a rider calls IF. Targets are
 * already fractions of FTP, so no FTP is needed. Free-ride steps prescribe no power and are left out: the figure describes
 * the structured part. Null when that part is shorter than one 30 s window (nothing to normalise), or when the workout is
 * one [playability] refuses.
 */
public fun Workout.intensityFactor(): Double? {
    if (playability() !is Playability.Runnable) return null
    val flat = flatSteps().filter { it.targetFrom != null }
    val targets = DoubleArray(flat.sumOf { it.durationSeconds })
    var at = 0
    for (step in flat) {
        for (second in 0 until step.durationSeconds) targets[at++] = checkNotNull(step.targetFractionAt(second))
    }
    if (targets.size < NP_WINDOW_SECONDS) return null
    var windowSum = 0.0
    for (i in 0 until NP_WINDOW_SECONDS) windowSum += targets[i]
    var fourthPowerSum = 0.0
    var windows = 0
    for (end in NP_WINDOW_SECONDS..targets.size) {
        if (end > NP_WINDOW_SECONDS) windowSum += targets[end - 1] - targets[end - 1 - NP_WINDOW_SECONDS]
        val average = windowSum / NP_WINDOW_SECONDS
        val squared = average * average
        fourthPowerSum += squared * squared
        windows++
    }
    return sqrt(sqrt(fourthPowerSum / windows))
}

private fun saturatingAdd(
    a: Long,
    b: Long,
): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

private fun saturatingMultiply(
    a: Long,
    b: Long,
): Long = if (a != 0L && b > Long.MAX_VALUE / a) Long.MAX_VALUE else a * b

private const val NP_WINDOW_SECONDS = 30
