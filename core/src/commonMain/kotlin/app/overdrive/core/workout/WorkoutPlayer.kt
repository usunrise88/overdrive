package app.overdrive.core.workout

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.core.ride.totalDurationSeconds
import kotlin.math.roundToInt

/** What the trainer should be doing right now, as decided by the [WorkoutPlayer]. */
public sealed interface PlayerTarget {
    public val mode: TrainerMode

    /**
     * ERG: hold [watts].
     *
     * @property watts what to command: the step's target at this second, times the intensity setting, and after a pause
     * the ramp-in from where the hold target was (see [WorkoutPlayer.beginRampIn]).
     * @property nominalWatts the same without the ramp-in: where the target is heading, and what the ride record and
     * the overlay show as "target".
     */
    public data class Erg(
        val watts: Int,
        val nominalWatts: Int,
    ) : PlayerTarget {
        override val mode: TrainerMode get() = TrainerMode.ERG
    }

    /** Free ride: the trainer simulates a road at the rider's virtual gear. */
    public data object Free : PlayerTarget {
        override val mode: TrainerMode get() = TrainerMode.SIMULATION
    }
}

/**
 * Walks a flattened workout (spec §4.5) and says what the trainer should do. It is pure and
 * deterministic: no clock, no coroutines, no I/O. The caller says how much workout time has passed with
 * [advance]; the player answers with the current step, the ERG target and the progress. The ride engine calls
 * `advance(1)` once for every second of ride time, and not while the ride is paused.
 *
 * - **Targets.** In an ERG step the target is the step's FTP fraction, linear over the step in a ramp
 *   ([FlatStep.targetFractionAt]), times [ftp] and the [intensityPercent] (50 to 150 in steps of 5), rounded to whole
 *   watts, halves up. A free-ride step has no target and runs in simulation mode with gears.
 * - **Skipping.** [nextInterval] jumps to the start of the next step and does nothing in the last one.
 *   [previousInterval] restarts the current step when more than
 *   [RideSession.PREVIOUS_INTERVAL_RESTART_AFTER_SECONDS] seconds into it, otherwise goes to the start of
 *   the previous step (in the first step it restarts that step).
 * - **Ramp-in.** After a pause the ERG target climbs linearly from the hold target of the pause (0 W unless the caller
 *   says otherwise) to its full value over [rampInSeconds] seconds of workout time, in whole-second steps (spec §4.5,
 *   docs/adr/0032). Call [beginRampIn] when the ride resumes; it is not applied when the workout starts, and not in
 *   free-ride steps. A caller that knows the rider is pedalling already can bring the first step forward with
 *   [stepRampIn] instead of waiting for the next second.
 * - **End.** Once the last second has been advanced, [isComplete] is true and the player stays on the last step.
 */
public class WorkoutPlayer(
    public val steps: List<FlatStep>,
    public val ftp: Int,
    public val rampInSeconds: Int = DEFAULT_RAMP_IN_SECONDS,
) {
    init {
        require(steps.isNotEmpty()) { "A workout needs at least one step" }
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        require(rampInSeconds >= 0) { "The ramp-in must not be negative, got $rampInSeconds" }
    }

    /** Length of the whole workout in seconds. */
    public val totalSeconds: Int = steps.totalDurationSeconds()

    /** Seconds of the workout done so far. */
    public var positionSeconds: Int = 0
        private set

    /** Index into [steps] of the current step. After the end it stays on the last step. */
    public var stepIndex: Int = 0
        private set

    /** Workout intensity in percent, 50 to 150 in steps of 5; scales ERG targets only. */
    public var intensityPercent: Int = RideSnapshot.DEFAULT_INTENSITY_PERCENT
        private set

    // Steps of the ramp-in taken so far; rampInSeconds means there is no ramp in progress.
    private var rampSteps = rampInSeconds
    private var rampFromWatts = 0

    // The power a heart-rate step's controller asks for (spec §11.2); the caller keeps it up to date with setHeartRateWatts.
    private var heartRateWatts = 0

    /** The current step. */
    public val step: FlatStep get() = steps[stepIndex]

    /** Whether the whole workout has been played. */
    public val isComplete: Boolean get() = positionSeconds >= totalSeconds

    /** How the trainer is driven in the current step. */
    public val mode: TrainerMode get() = step.mode

    /** Seconds spent in the current step. */
    public val stepElapsedSeconds: Int get() = positionSeconds - step.startSeconds

    /** Seconds left in the current step. */
    public val stepRemainingSeconds: Int get() = step.durationSeconds - stepElapsedSeconds

    /** What the trainer should do now. */
    public val target: PlayerTarget
        get() {
            if (step.heartRate != null) {
                // A heart-rate step (spec §11.2): ERG at the power a controller chose; intensity does not apply.
                return PlayerTarget.Erg(watts = rampedWatts(heartRateWatts.toDouble()), nominalWatts = heartRateWatts)
            }
            val fraction = step.targetFractionAt(stepElapsedSeconds) ?: return PlayerTarget.Free
            val nominal = fraction * ftp * intensityPercent / PERCENT
            return PlayerTarget.Erg(watts = rampedWatts(nominal), nominalWatts = roundWatts(nominal))
        }

    /**
     * Tells the player the power the heart-rate controller asks for (spec §11.2). In a heart-rate step [target] is ERG at
     * these watts (after a pause with the ramp-in on top); the player does not choose it, because it has no clock to
     * judge a heart rate by. Ignored outside a heart-rate step.
     */
    public fun setHeartRateWatts(watts: Int) {
        require(watts >= 0) { "A target cannot be negative, got $watts" }
        heartRateWatts = watts
    }

    /** Moves the workout on by [seconds] seconds of ride time. Does nothing once [isComplete]. */
    public fun advance(seconds: Int = 1) {
        require(seconds >= 0) { "Cannot advance by a negative time, got $seconds" }
        repeat(seconds) {
            if (isComplete) return
            positionSeconds++
            stepRampIn()
            while (stepIndex < steps.lastIndex && positionSeconds >= steps[stepIndex].endSeconds) stepIndex++
        }
    }

    /** Jumps to the start of the next step. Returns false, and does nothing, in the last step. */
    public fun nextInterval(): Boolean {
        if (stepIndex >= steps.lastIndex) return false
        jumpTo(stepIndex + 1)
        return true
    }

    /** Restarts the current step, or jumps to the start of the previous one right at the start of a step. */
    public fun previousInterval() {
        val restart = stepIndex == 0 || stepElapsedSeconds > RideSession.PREVIOUS_INTERVAL_RESTART_AFTER_SECONDS
        jumpTo(if (restart) stepIndex else stepIndex - 1)
    }

    /** Intensity up by 5 points, in an ERG step. Returns whether it changed. */
    public fun intensityUp(): Boolean = changeIntensity(+RideSnapshot.INTENSITY_STEP_PERCENT)

    /** Intensity down by 5 points, in an ERG step. Returns whether it changed. */
    public fun intensityDown(): Boolean = changeIntensity(-RideSnapshot.INTENSITY_STEP_PERCENT)

    /**
     * Starts the ramp-in of the ERG target: call when the ride resumes after a pause. The ramp starts at [fromWatts], the
     * target the trainer was holding during the pause (never above the target it is heading for), so the target right
     * after this call is [fromWatts]; it then moves one of [rampInSeconds] equal steps with each second that [advance]
     * counts and is full after [rampInSeconds] of them. With a ramp-in of 0 seconds the target is full at once.
     */
    public fun beginRampIn(fromWatts: Int = 0) {
        require(fromWatts >= 0) { "The ramp-in cannot start below 0 W, got $fromWatts" }
        rampFromWatts = fromWatts
        rampSteps = 0
    }

    /**
     * Takes the next step of a ramp-in that is in progress right now instead of with the next second, so that the ramp
     * is a second shorter and its first step is not a wait. Does nothing when no ramp-in is in progress.
     */
    public fun stepRampIn() {
        if (rampSteps < rampInSeconds) rampSteps++
    }

    /** The progress as the overlay shows it. */
    public fun progress(workoutName: String): WorkoutProgress =
        WorkoutProgress(
            workoutName = workoutName,
            steps = steps,
            stepIndex = stepIndex,
            stepElapsedSeconds = stepElapsedSeconds,
            stepRemainingSeconds = stepRemainingSeconds,
            workoutElapsedSeconds = positionSeconds,
            workoutRemainingSeconds = totalSeconds - positionSeconds,
        )

    private fun jumpTo(index: Int) {
        stepIndex = index
        positionSeconds = steps[index].startSeconds
    }

    private fun changeIntensity(delta: Int): Boolean {
        // A heart-rate step has no fixed power for a percentage to scale: its buttons move the target heart rate instead.
        if (mode != TrainerMode.ERG || step.heartRate != null) return false
        val next =
            (intensityPercent + delta).coerceIn(RideSnapshot.MIN_INTENSITY_PERCENT, RideSnapshot.MAX_INTENSITY_PERCENT)
        if (next == intensityPercent) return false
        intensityPercent = next
        return true
    }

    /**
     * Whole watts, halves rounded up. The workout's fractions are binary floating-point numbers, so a target of
     * exactly 162.5 W can come out as 162.49999999999997; a tiny allowance makes such ties round the same way
     * whichever way they were computed.
     */
    private fun roundWatts(watts: Double): Int = (watts + ROUNDING_ALLOWANCE_WATTS).roundToInt()

    /** The commanded watts: [nominal] itself, or the step of the ramp-in it is in. */
    private fun rampedWatts(nominal: Double): Int {
        if (rampInSeconds == 0 || rampSteps >= rampInSeconds) return roundWatts(nominal)
        val from = minOf(rampFromWatts.toDouble(), nominal)
        return roundWatts(from + (nominal - from) * rampSteps / rampInSeconds)
    }

    public companion object {
        /** Spec §4.5: after a pause the target ramps back in over 3 s. */
        public const val DEFAULT_RAMP_IN_SECONDS: Int = 3

        private const val PERCENT = 100.0
        private const val ROUNDING_ALLOWANCE_WATTS = 1e-9
    }
}
