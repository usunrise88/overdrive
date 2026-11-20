package app.overdrive.core.live

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.HeartRateHoldState
import app.overdrive.core.ride.HeartRateTarget
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress

/** Rides of invented numbers for the live coach's tests: nobody's body, nobody's workout. */
internal object LiveRides {
    const val FTP = 250

    /** warm-up ramp 5 min, threshold 10 min at 95%, recover 5 min at 50%, free 5 min, heart-rate hold 10 min, cool-down ramp 5 min. */
    val steps: List<FlatStep> =
        run {
            var start = 0

            fun step(
                label: String,
                seconds: Int,
                from: Double?,
                to: Double?,
                heartRate: HeartRateTarget? = null,
            ): FlatStep {
                val s =
                    FlatStep(
                        label = label,
                        durationSeconds = seconds,
                        targetFrom = from,
                        targetTo = to,
                        mode = if (from == null && heartRate == null) TrainerMode.SIMULATION else TrainerMode.ERG,
                        startSeconds = start,
                        heartRate = heartRate,
                    )
                start += seconds
                return s
            }
            listOf(
                step("Warm-up", 300, 0.5, 0.7),
                step("Threshold", 600, 0.95, 0.95),
                step("Recover", 300, 0.5, 0.5),
                step("Free ride", 300, null, null),
                step(
                    "Zone 2",
                    600,
                    null,
                    null,
                    HeartRateTarget(targetBpm = 135, band = 5, powerMin = 90, powerMax = 200),
                ),
                step("Cool-down", 300, 0.55, 0.4),
            )
        }

    /**
     * A ride snapshot at ride second [second] (the clock with the pauses in it), [elapsedInStep] seconds into step [step]. By default
     * the rider is exactly on the target of a steady step.
     */
    fun ride(
        second: Int,
        step: Int = 1,
        elapsedInStep: Int = 30,
        state: RideState = RideState.RIDING,
        power: Int? = 238,
        heartRate: Int? = 150,
        cadence: Int? = 90,
        target: Int? = 238,
        guard: Boolean = false,
        sinceResume: Int? = null,
        hold: HeartRateHoldState? = null,
        intensity: Int = 100,
        steps: List<FlatStep> = LiveRides.steps,
    ): RideSnapshot {
        val flat = steps[step]
        val remaining = flat.durationSeconds - elapsedInStep
        return RideSnapshot(
            state = state,
            ftp = FTP,
            elapsedSeconds = second,
            rideSeconds = second,
            power = power,
            heartRate = heartRate,
            cadence = cadence,
            mode = flat.mode,
            targetPower = if (flat.mode == TrainerMode.ERG) target else null,
            intensityPercent = intensity,
            workout =
                WorkoutProgress(
                    workoutName = "Invented",
                    steps = steps,
                    stepIndex = step,
                    stepElapsedSeconds = elapsedInStep,
                    stepRemainingSeconds = remaining,
                    workoutElapsedSeconds = flat.startSeconds + elapsedInStep,
                    workoutRemainingSeconds = steps.last().endSeconds - flat.startSeconds - elapsedInStep,
                ),
            pauseReason = null,
            secondsSinceResume = sinceResume,
            stallGuardActive = guard,
            stallGuardWatts = if (guard) 150 else null,
            heartRateHold = hold,
        )
    }

    fun hold(
        inBand: Boolean? = true,
        startRamp: Boolean = false,
        signalLost: Boolean = false,
        strapLost: Boolean = false,
        target: Int = 135,
    ) = HeartRateHoldState(
        targetBpm = target,
        band = 5,
        inBand = inBand,
        startRamp = startRamp,
        signalLost = signalLost,
        strapLost = strapLost,
        powerMin = 90,
        powerMax = 200,
    )
}
