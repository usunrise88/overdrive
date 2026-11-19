package app.overdrive.ride.host

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.overlay.HoldPhase
import app.overdrive.uikit.overlay.HoldPlace
import app.overdrive.uikit.overlay.OverlayControl
import app.overdrive.uikit.overlay.OverlayHold
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
     * - While the **stall guard** has hold (spec §11.1) the fifth cell says "Cadence guard" and shows what the trainer is held at.
     * - In a **heart-rate step** (spec §11.2) the fifth cell is the controller's power with no intensity beside "Target", and the
     *   [hold][OverlayUiState.hold] line says where the heart rate is against the band.
     * - The interval section exists only while a workout plays; [previousLine] is the line against the previous attempt of the session
     *   (spec §11.7, docs/adr/0034), or null.
     */
    fun overlayState(
        snapshot: RideSnapshot,
        profile: ProfileData?,
        previousLine: String? = null,
    ): OverlayUiState =
        OverlayUiState(
            power = snapshot.power,
            zone = snapshot.zone?.number,
            heartRate = snapshot.heartRate,
            cadence = snapshot.cadence,
            elapsedSeconds = snapshot.elapsedSeconds,
            paused = snapshot.state == RideState.PAUSED,
            control = control(snapshot),
            speedKmh = snapshot.speedKmh,
            distanceMeters = snapshot.distanceMeters,
            interval = interval(snapshot, profile, previousLine),
            hold = hold(snapshot),
        )

    private fun control(snapshot: RideSnapshot): OverlayControl =
        when {
            snapshot.mode != TrainerMode.ERG -> {
                OverlayControl.Gear(snapshot.gear)
            }

            snapshot.stallGuardActive && snapshot.stallGuardWatts != null -> {
                OverlayControl.Guard(snapshot.stallGuardWatts)
            }

            // The controller chooses the power: an intensity percentage would be a number that does nothing.
            snapshot.heartRateHold != null -> {
                OverlayControl.Target(snapshot.targetPower, null)
            }

            else -> {
                OverlayControl.Target(snapshot.targetPower, snapshot.intensityPercent)
            }
        }

    /**
     * The heart-rate step's line, or null outside one. The place is "In" when the controller says the last reading was inside the band,
     * else above or below by the live heart rate; it is null while the start ramp runs (not judged) and without a heart rate. The
     * offset is what the gear buttons moved the target by: the target shown less the step's own.
     */
    fun hold(snapshot: RideSnapshot): OverlayHold? {
        val state = snapshot.heartRateHold ?: return null
        val heartRate = snapshot.heartRate
        val own = snapshot.workout?.step?.heartRate?.targetBpm
        val place =
            when {
                state.startRamp || state.signalLost || heartRate == null || state.inBand == null -> null
                state.inBand == true -> HoldPlace.In
                heartRate > state.targetBpm -> HoldPlace.Above
                else -> HoldPlace.Below
            }
        return OverlayHold(
            targetBpm = state.targetBpm,
            band = state.band,
            offsetBpm = if (own == null) 0 else state.targetBpm - own,
            heartRate = heartRate,
            place = place,
            phase =
                when {
                    state.strapLost -> HoldPhase.StrapDropped
                    state.signalLost -> HoldPhase.StrapLost
                    state.startRamp -> HoldPhase.EasingIn
                    else -> HoldPhase.Holding
                },
            watts = snapshot.targetPower,
            minimumWatts = state.powerMin,
        )
    }

    private fun interval(
        snapshot: RideSnapshot,
        profile: ProfileData?,
        previousLine: String?,
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
            previousLine = previousLine,
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
