package app.overdrive.core.physics

import app.overdrive.core.ride.SpeedModel
import kotlin.math.cbrt

/**
 * Virtual speed on the flat from rider power (spec §4.3): the speed at which rolling resistance plus
 * aerodynamic drag use up exactly the power the rider produces,
 *
 * ```
 * η · P = Crr · m · g · v + ½ · ρ · CdA · v³
 * ```
 *
 * with η the drivetrain efficiency. The right side grows strictly with speed, so there is exactly one
 * positive solution, found with Newton's method started above it: the function is convex there, so
 * the iteration only ever moves down towards the root and converges in a handful of steps.
 *
 * This is the **steady-state** speed for the power given: it has no inertia, so a rider who stops
 * pedalling stops at once. The trainer's own speed value is never used (spec §4.3).
 */
public class FlatPhysicsSpeedModel(
    public val settings: PhysicsSettings = PhysicsSettings(),
) : SpeedModel {
    private val rolling = settings.rollingResistanceNewtons
    private val drag = settings.windResistanceCoefficient

    override fun speedKmh(powerWatts: Double): Double = speedMetersPerSecond(powerWatts) * METERS_PER_SECOND_TO_KMH

    /** Steady-state speed in m/s for [powerWatts]. Zero for zero, negative or NaN power. */
    public fun speedMetersPerSecond(powerWatts: Double): Double {
        if (!(powerWatts > 0.0)) return 0.0
        val wheelPower = settings.drivetrainEfficiency * powerWatts.coerceAtMost(MAX_POWER_WATTS)
        var v = cbrt(wheelPower / drag) // the drag-only speed, always at or above the solution
        repeat(MAX_ITERATIONS) {
            val residual = drag * v * v * v + rolling * v - wheelPower
            val next = v - residual / (3.0 * drag * v * v + rolling)
            val converged = v - next <= CONVERGED_METERS_PER_SECOND
            v = next
            if (converged) return v
        }
        return v
    }

    /** Power in watts a rider has to produce to hold [speedMetersPerSecond] on the flat: the inverse of the model. */
    public fun powerForSpeed(speedMetersPerSecond: Double): Double {
        val v = speedMetersPerSecond.coerceAtLeast(0.0)
        return (drag * v * v * v + rolling * v) / settings.drivetrainEfficiency
    }

    private companion object {
        const val METERS_PER_SECOND_TO_KMH = 3.6
        const val MAX_POWER_WATTS = 10_000.0
        const val MAX_ITERATIONS = 64
        const val CONVERGED_METERS_PER_SECOND = 1e-12
    }
}
