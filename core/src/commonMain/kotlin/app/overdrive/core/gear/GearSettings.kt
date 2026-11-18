package app.overdrive.core.gear

import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.physics.PhysicsSettings
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Virtual gears (spec §4.4): the trainer has one sprocket, so a gear is a **grade offset** applied to the
 * trainer's simulation on top of the route grade. Gear 1 is the easiest and [gearCount] the hardest;
 * [neutralGear] is the one that equals the flat. The defaults are 24 gears, 1% per gear and the middle
 * gear neutral, with the spare gear on the hard side (11 gears easier than the flat, 12 harder), to be
 * tuned by feel on the D500.
 *
 * **Limit.** The trainer simulates at most [maxGradePercent] (15% on the D500, spec §2). The total of
 * route grade and gear offset is clamped to `-maxGradePercent..maxGradePercent`, so a configuration with
 * more or larger gears than the trainer can express saturates at its extremes instead of sending
 * values the trainer would reject.
 *
 * **Known difference from a real drivetrain** (spec §4.4): flywheel inertia does not change with the
 * gear. A real easy gear spins the flywheel slowly and a hard one fast, so shifting changes how a pedal
 * stroke feels, not only the resistance. Here the flywheel keeps the same effective inertia in every
 * gear, as in Zwift's own virtual shifting on a Cog. Gear feel can only be judged on the bike.
 */
public data class GearSettings(
    val gearCount: Int = DEFAULT_GEAR_COUNT,
    /** The gear whose offset is zero. 1-based. */
    val neutralGear: Int = (gearCount + 1) / 2,
    /** Grade added per gear step away from [neutralGear], in percent. */
    val gradePercentPerGear: Double = DEFAULT_GRADE_PERCENT_PER_GEAR,
    /** The trainer's simulation limit in percent, applied to both climbing and descending. */
    val maxGradePercent: Double = DEFAULT_MAX_GRADE_PERCENT,
) {
    init {
        require(gearCount >= 1) { "Gear count must be at least 1, got $gearCount" }
        require(neutralGear in 1..gearCount) { "Neutral gear $neutralGear is outside 1..$gearCount" }
        require(gradePercentPerGear > 0.0 && gradePercentPerGear.isFinite()) {
            "Grade per gear must be positive, got $gradePercentPerGear"
        }
        require(maxGradePercent > 0.0 && maxGradePercent.isFinite()) {
            "The grade limit must be positive, got $maxGradePercent"
        }
    }

    /** [gear] limited to the available gears `1..gearCount`. */
    public fun clampGear(gear: Int): Int = gear.coerceIn(1, gearCount)

    /** The grade offset of [gear] in percent, before any limit: `(gear - neutralGear) · gradePercentPerGear`. */
    public fun gradeOffsetPercent(gear: Int): Double {
        require(gear in 1..gearCount) { "Gear $gear is outside 1..$gearCount" }
        return (gear - neutralGear) * gradePercentPerGear
    }

    /**
     * The total grade the trainer simulates in [gear] on a route with [routeGradePercent]: the sum of the
     * two, clamped to the trainer's limit and rounded to FTMS's resolution of 0.01%.
     */
    public fun gradePercent(
        gear: Int,
        routeGradePercent: Double = 0.0,
    ): Double {
        val total = (routeGradePercent + gradeOffsetPercent(gear)).coerceIn(-maxGradePercent, maxGradePercent)
        return (total * GRADE_STEPS_PER_PERCENT).roundToLong() / GRADE_STEPS_PER_PERCENT
    }

    /** Whether [gear] on a route with [routeGradePercent] hits the trainer's limit, so the rider cannot feel more. */
    public fun isClamped(
        gear: Int,
        routeGradePercent: Double = 0.0,
    ): Boolean = abs(routeGradePercent + gradeOffsetPercent(gear)) > maxGradePercent

    /** What to send to the trainer in [gear]: the clamped grade plus rolling and wind resistance from [physics]. */
    public fun simulationParameters(
        gear: Int,
        physics: PhysicsSettings = PhysicsSettings(),
        routeGradePercent: Double = 0.0,
    ): SimulationParameters =
        SimulationParameters(
            gradePercent = gradePercent(gear, routeGradePercent),
            crr = physics.crr,
            windResistance = physics.windResistanceCoefficient,
        )

    public companion object {
        /** Spec §4.4. Equals `RideSnapshot.DEFAULT_GEAR_COUNT`. */
        public const val DEFAULT_GEAR_COUNT: Int = 24
        public const val DEFAULT_GRADE_PERCENT_PER_GEAR: Double = 1.0

        /** Spec §2: the D500 simulates up to 15%. */
        public const val DEFAULT_MAX_GRADE_PERCENT: Double = 15.0

        private const val GRADE_STEPS_PER_PERCENT = 100.0
    }
}
