package app.overdrive.ride.host

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.overlay.OverlayControl
import app.overdrive.uikit.overlay.OverlayInterval
import app.overdrive.uikit.overlay.OverlayUiState

/**
 * The step from the engine's [RideSnapshot] to the plain UI state the ui-kit's overlay draws (ADR 0005). Pure
 * functions: the overlay composables stay free of `core`, and the rules (what the fifth cell shows, when the overlay
 * counts as paused, what "Next" says) are unit-tested here.
 */
object RideMapping {
    /**
     * The overlay's state for [snapshot]. [profile] is the workout's chart geometry, one instance per ride
     * (`RideUi.profile`); it is ignored without a workout.
     *
     * - The fifth cell is the **target and intensity** in an ERG step and the **gear** in a free-ride step: the Click's
     *   feedback channel (design/system.md §5.2).
     * - **Paused** is any pause, whatever its reason: values dim and the time label reads "Paused".
     * - The interval section exists only while a workout plays.
     */
    fun overlayState(
        snapshot: RideSnapshot,
        profile: ProfileData?,
    ): OverlayUiState =
        OverlayUiState(
            power = snapshot.power,
            zone = snapshot.zone?.number,
            heartRate = snapshot.heartRate,
            cadence = snapshot.cadence,
            elapsedSeconds = snapshot.elapsedSeconds,
            paused = snapshot.state == RideState.PAUSED,
            control =
                if (snapshot.mode == TrainerMode.ERG) {
                    OverlayControl.Target(snapshot.targetPower, snapshot.intensityPercent)
                } else {
                    OverlayControl.Gear(snapshot.gear)
                },
            speedKmh = snapshot.speedKmh,
            distanceMeters = snapshot.distanceMeters,
            interval = interval(snapshot, profile),
        )

    private fun interval(
        snapshot: RideSnapshot,
        profile: ProfileData?,
    ): OverlayInterval? {
        val workout = snapshot.workout ?: return null
        val chart = profile ?: return null
        val step = workout.step
        val repeats = step.repeatCount
        val next = workout.nextStep
        return OverlayInterval(
            name = step.label,
            repeatText =
                if (step.repeatIteration != null && repeats != null &&
                    repeats > 1
                ) {
                    "${step.repeatIteration} of $repeats"
                } else {
                    null
                },
            remainingSeconds = workout.stepRemainingSeconds,
            next =
                if (next ==
                    null
                ) {
                    "Finish"
                } else {
                    WorkoutDescriber.nextLine(next, snapshot.ftp, snapshot.intensityPercent)
                },
            profile = chart,
            nowSeconds = workout.workoutElapsedSeconds,
        )
    }

    /** "2 of 3" for a step of a repeat set, else null: the ride screen's interval line. */
    fun repeatText(snapshot: RideSnapshot): String? {
        val step = snapshot.workout?.step ?: return null
        val count = step.repeatCount ?: return null
        val iteration = step.repeatIteration ?: return null
        return if (count > 1) "$iteration of $count" else null
    }
}
