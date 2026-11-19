package app.overdrive.core.physics

import app.overdrive.core.ride.SpeedModel
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Virtual speed from rider power (spec §4.3): the speed at which rolling resistance, gravity and aerodynamic drag use up exactly the
 * power the rider produces,
 *
 * ```
 * η · P = (Crr · m · g · cos θ + m · g · sin θ) · v + ½ · ρ · CdA · v³        θ = atan(grade / 100)
 * ```
 *
 * with η the drivetrain efficiency. On the **flat** (grade 0, which is what [speedKmh] with one argument is) the bracket is the
 * rolling resistance alone and this is the model of spec §4.3 to the last bit. A **climb** adds the gravity term; a **descent**
 * subtracts it, and a descent steeper than the rolling resistance makes the bracket negative, so the rider is pushed along.
 *
 * **Solving it.** With `a` the bracket and `c = ½ρCdA`, `f(v) = c·v³ + a·v − ηP` has `f(0) = −ηP ≤ 0`, is convex for `v > 0`, and
 * increases without bound, so for `P > 0` there is exactly one positive root, whatever the sign of `a`. Newton's method **started
 * at or above the root** converges monotonically from above and never overshoots: the start is `∛(ηP/c)` (the drag-only speed) when
 * `a ≥ 0`, and `∛(ηP/c) + √(−a/c)` when `a < 0`, where `f(start) ≥ 2·|a|·∛(ηP/c) ≥ 0` (proved in the code below). With **no power**
 * a rider on a descent coasts at the terminal speed `√(−a/c)`, and one on the flat or a climb stops at once.
 *
 * **Steady state, no inertia**: the speed for the power given at this instant, like the flat model always was; a rider who stops
 * pedalling on a climb stops at once. **A descent is capped at [MAX_SPEED_KMH]** (80 km/h): a model has no brakes and a tablet
 * showing 120 km/h is a bug report. The trainer's own speed value is never used (spec §4.3).
 *
 * The class keeps its name from when it was flat-only: [speedKmh] with one argument is still the flat model.
 */
public class FlatPhysicsSpeedModel(
    public val settings: PhysicsSettings = PhysicsSettings(),
) : SpeedModel {
    private val rolling = settings.rollingResistanceNewtons
    private val weight = settings.systemMassKg * PhysicsSettings.GRAVITY
    private val drag = settings.windResistanceCoefficient

    override fun speedKmh(powerWatts: Double): Double = speedMetersPerSecond(powerWatts) * METERS_PER_SECOND_TO_KMH

    override fun speedKmh(
        powerWatts: Double,
        gradePercent: Double,
    ): Double = speedMetersPerSecond(powerWatts, gradePercent) * METERS_PER_SECOND_TO_KMH

    /** Steady-state speed in m/s for [powerWatts] on the flat. Zero for zero, negative or NaN power. */
    public fun speedMetersPerSecond(powerWatts: Double): Double = speedMetersPerSecond(powerWatts, 0.0)

    /**
     * Steady-state speed in m/s for [powerWatts] on a road of [gradePercent] (positive climbs). Zero for NaN power, and for zero or
     * negative power unless the road descends steeper than the rolling resistance, when the rider coasts at the terminal speed.
     * At most [MAX_SPEED_KMH].
     */
    public fun speedMetersPerSecond(
        powerWatts: Double,
        gradePercent: Double,
    ): Double {
        if (powerWatts.isNaN() || gradePercent.isNaN()) return 0.0
        val slope = slopeForce(gradePercent)
        val wheelPower = settings.drivetrainEfficiency * powerWatts.coerceIn(0.0, MAX_POWER_WATTS)
        if (!(wheelPower > 0.0)) {
            // No power: the rider coasts at the terminal speed on a steep descent, and stops on the flat and on a climb.
            return if (slope < 0.0) sqrt(-slope / drag).coerceAtMost(MAX_SPEED_METERS_PER_SECOND) else 0.0
        }
        // The drag-only speed is at or above the root when nothing pushes the rider.
        val dragOnly = cbrt(wheelPower / drag)
        // On a steep descent (slope < 0) the root lies above it by less than √(−a/c). With x = ∛(P/c) and y = √(−a/c):
        // c·(x + y)³ − |a|·(x + y) − P ≥ 3c·x·y² + c·y³ + P − |a|·x − |a|·y − P = 2|a|·x ≥ 0,
        // so x + y is at or above the root, where Newton's method converges from above.
        var v = if (slope < 0.0) dragOnly + sqrt(-slope / drag) else dragOnly
        repeat(MAX_ITERATIONS) {
            val residual = drag * v * v * v + slope * v - wheelPower
            val next = v - residual / (3.0 * drag * v * v + slope)
            val converged = v - next <= CONVERGED_METERS_PER_SECOND
            v = next
            if (converged) return v.coerceAtMost(MAX_SPEED_METERS_PER_SECOND)
        }
        return v.coerceAtMost(MAX_SPEED_METERS_PER_SECOND)
    }

    /** Power in watts a rider has to produce to hold [speedMetersPerSecond] on a road of [gradePercent]: the inverse of the model. */
    public fun powerForSpeed(
        speedMetersPerSecond: Double,
        gradePercent: Double = 0.0,
    ): Double {
        val v = speedMetersPerSecond.coerceAtLeast(0.0)
        return (drag * v * v * v + slopeForce(gradePercent) * v) / settings.drivetrainEfficiency
    }

    /** The force in newtons that gravity and rolling resistance put against the rider at [gradePercent]; negative on a steep descent. */
    private fun slopeForce(gradePercent: Double): Double {
        val angle = atan(gradePercent / PERCENT)
        return rolling * cos(angle) + weight * sin(angle)
    }

    public companion object {
        /** A descent is capped here: no brakes in the model, and no screen should say more. */
        public const val MAX_SPEED_KMH: Double = 80.0

        private const val METERS_PER_SECOND_TO_KMH = 3.6
        private const val MAX_SPEED_METERS_PER_SECOND = MAX_SPEED_KMH / METERS_PER_SECOND_TO_KMH
        private const val PERCENT = 100.0
        private const val MAX_POWER_WATTS = 10_000.0
        private const val MAX_ITERATIONS = 64
        private const val CONVERGED_METERS_PER_SECOND = 1e-12
    }
}
