package app.overdrive.core.ride

import kotlinx.coroutines.flow.StateFlow

/**
 * One ride as the UI sees it: a stream of [RideSnapshot]s and a small set of commands (spec §4.2, §4.5, §4.7).
 *
 * This is the seam between the ride UI and whatever produces the ride: [SimulatedRideSession] today,
 * the real engine on top of the BLE devices from stage M2 on. A session is single-use: once it is
 * [RideState.FINISHED] it never runs again, and the next ride is a new session.
 *
 * **Collecting.** [snapshots] is a `StateFlow`, so a collector always gets the latest snapshot first and
 * sees every later one, conflated if it is slow. While riding a new snapshot arrives once a second, and
 * one more right after each command that changes something.
 *
 * **Commands are fire and forget.** They never block, never throw, and may be called from any thread.
 * They are queued and applied in call order on the session's own coroutine, so their effect appears in
 * [snapshots] a moment after the call returns, not before. A command that does not apply in the current
 * state is silently ignored (a no-op): the UI may bind them to buttons and a Click without checking
 * state first, and a held-down or doubled press does no harm. What each command does in each state:
 *
 * | Command | Does something when | Effect | No-op otherwise |
 * |---|---|---|---|
 * | [start] | `READY` | Becomes `RIDING`; the ride clock and the workout start from 0. | everything else |
 * | [pause] | `RIDING` | Becomes `PAUSED` with [PauseReason.MANUAL]; the ride clock and workout stop. | everything else |
 * | [resume] | `PAUSED` | Lifts a manual or automatic pause: becomes `RIDING`, and the clock and workout continue where they stopped. A ride that is paused because the trainer is gone stays `PAUSED` until it is back. | everything else |
 * | [finish] | `RIDING` or `PAUSED` | Becomes `FINISHED`. | `IDLE`, `READY`, `FINISHED` |
 * | [nextInterval] | `RIDING` or `PAUSED`, a workout, and not in the last step | Jumps to the start of the next step. | everything else |
 * | [previousInterval] | `RIDING` or `PAUSED` and a workout | Restarts the current step if more than 3 s into it, else jumps to the start of the previous step. In the first step it restarts that step. | everything else |
 * | [gearUp], [gearDown] | `READY`, `RIDING` or `PAUSED`, in a simulation step | Moves the gear by one, clamped to `1..gearCount`. | ERG steps, `IDLE`, `FINISHED`, and at the ends of the range |
 * | [intensityUp], [intensityDown] | `READY`, `RIDING` or `PAUSED`, in an ERG step | Moves the intensity by 5 points, clamped to 50..150; in a heart-rate step (spec §11.2) the target heart rate by 2 bpm instead. | simulation steps, `IDLE`, `FINISHED`, and at the ends of the range |
 *
 * **Pauses.** A ride is `PAUSED` for one or more reasons, reported as [RideSnapshot.pauseReason]: the rider paused it
 * ([PauseReason.MANUAL]), the real engine paused it because the rider stopped pedalling ([PauseReason.AUTO], spec §4.2),
 * or because the trainer's link is down ([PauseReason.TRAINER_DISCONNECTED]). While paused the sensors keep reporting what
 * they measure: 0 W and 0 rpm for a rider who is not pedalling, null (no signal) for a trainer that is gone, and the heart
 * rate as usual. The simulated session only ever pauses manually and its stopped rider reads 0 W.
 *
 * **Gears and intensity are separate commands.** On the handlebar the two gear buttons change the
 * intensity instead of the gear in ERG steps (spec §4.7); that routing belongs to the gesture layer,
 * which reads [RideSnapshot.mode] and calls [intensityUp] or [gearUp] accordingly. The session
 * itself does exactly what each command says, so on-screen buttons can map one to one.
 *
 * **Skipping and the clocks.** [RideSnapshot.elapsedSeconds] is ride time and is never rewound.
 * [nextInterval] and [previousInterval] move only the workout position, so
 * [WorkoutProgress.workoutElapsedSeconds] jumps, and a workout that is skipped through ends early.
 * Skipping past the last step is not possible; use [finish] to end the ride.
 */
public interface RideSession {
    public companion object {
        /** [previousInterval] restarts the current step when the rider is further into it than this. */
        public const val PREVIOUS_INTERVAL_RESTART_AFTER_SECONDS: Int = 3
    }

    /** The latest snapshot and every later one. Never completes. */
    public val snapshots: StateFlow<RideSnapshot>

    /** Starts the ride. */
    public fun start()

    /** Pauses the ride. */
    public fun pause()

    /** Resumes a paused ride. */
    public fun resume()

    /** Ends the ride. */
    public fun finish()

    /** Jumps to the next workout step. */
    public fun nextInterval()

    /** Restarts the current workout step, or jumps to the previous one right at the start of a step. */
    public fun previousInterval()

    /** One gear harder. Simulation steps only. */
    public fun gearUp()

    /** One gear easier. Simulation steps only. */
    public fun gearDown()

    /** Workout intensity up by 5 points, or the target heart rate up by 2 bpm in a heart-rate step. ERG steps only. */
    public fun intensityUp()

    /** Workout intensity down by 5 points, or the target heart rate down by 2 bpm in a heart-rate step. ERG steps only. */
    public fun intensityDown()
}
