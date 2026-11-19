package app.overdrive.core.route

import app.overdrive.core.gear.GearSettings

/**
 * The grade a ride on a route feels (spec §11.4, §4.4), from the route's elevations and the distance ridden along it. Pure: no clock,
 * no state; the engine asks it once a second.
 *
 * 1. **Smoothing.** The grade at distance *s* is the rise over a window of [windowMeters] (default **40 m**) centred on *s*, divided by
 *    the run: `(e(s + w/2) − e(s − w/2)) / w`, which is exactly the mean of the road's grade over the window. Near the start and the
 *    end the window keeps its width and slides inside the route instead of shrinking (a route shorter than the window uses all of it),
 *    so the grade is steady over the first and last half-window and never jumps. The service already smoothed the elevations over about
 *    60 m, so this takes the corners off the resampled points and no more.
 * 2. **Difficulty.** The smoothed grade is multiplied by `difficultyPercent / 100` (default **50**, 0 to 100): at 50 a 10% road feels
 *    like 5%, so steep roads stay rideable on one cog. 0 is the flat.
 * 3. **Gears and the trainer's limit** are the existing model ([GearSettings.gradePercent], spec §4.4): the gear's offset is added on
 *    top of the route's grade and the total is clamped to `-maxGradePercent..maxGradePercent` (15 on the D500, **climbing and
 *    descending alike**) and rounded to FTMS's 0.01%.
 * 4. **The end.** At or beyond the end of the route the grade is 0 (the road is flat from there on) and [isFinished] is true. The
 *    route does not loop.
 *
 * [routePercentAt] is the route's own contribution, **clamped to the trainer's limit with no gear on it** (the neutral gear): what
 * the trainer feels in the neutral gear, and what the virtual speed uses, so speed and resistance agree. The gear offset only changes
 * what the pedals feel (spec §4.4: a gear is a feel, as it is on the flat), never the road's speed.
 */
public class RouteGrade(
    public val course: RouteCourse,
    /** 0 to 100. */
    public val difficultyPercent: Int = DEFAULT_DIFFICULTY_PERCENT,
    public val gears: GearSettings = GearSettings(),
    public val windowMeters: Double = DEFAULT_WINDOW_METERS,
) {
    init {
        require(difficultyPercent in MIN_DIFFICULTY_PERCENT..MAX_DIFFICULTY_PERCENT) {
            "Difficulty is $MIN_DIFFICULTY_PERCENT to $MAX_DIFFICULTY_PERCENT percent, got $difficultyPercent"
        }
        require(
            windowMeters > 0.0 && windowMeters.isFinite(),
        ) { "The smoothing window must be positive, got $windowMeters" }
    }

    /** The length of the route in metres. */
    public val lengthMeters: Double get() = course.lengthMeters

    /** Whether [distanceMeters] along the route is its end: there is no more road. */
    public fun isFinished(distanceMeters: Double): Boolean = distanceMeters >= lengthMeters

    /** The road's own grade in percent at [distanceMeters], smoothed over the window, before the difficulty; 0 at the end. */
    public fun roadPercentAt(distanceMeters: Double): Double {
        if (isFinished(distanceMeters)) return 0.0
        val window = minOf(windowMeters, lengthMeters)
        val low = (distanceMeters - window / 2).coerceIn(0.0, lengthMeters - window)
        return (course.elevationAt(low + window) - course.elevationAt(low)) / window * PERCENT
    }

    /**
     * The route's grade at [distanceMeters] as the trainer feels it with no gear on top: [roadPercentAt] times the difficulty, clamped
     * to the trainer's limit and rounded to 0.01%. 0 at the end of the route.
     */
    public fun routePercentAt(distanceMeters: Double): Double =
        gears.gradePercent(gears.neutralGear, roadPercentAt(distanceMeters) * difficultyPercent / PERCENT)

    /** What the trainer is told at [distanceMeters] in [gear]: the route's grade plus the gear's offset, clamped and rounded. */
    public fun trainerPercentAt(
        distanceMeters: Double,
        gear: Int,
    ): Double = gears.gradePercent(gear, routePercentAt(distanceMeters))

    public companion object {
        /** Spec §11.4: steep roads stay rideable on one cog. */
        public const val DEFAULT_DIFFICULTY_PERCENT: Int = 50
        public const val MIN_DIFFICULTY_PERCENT: Int = 0
        public const val MAX_DIFFICULTY_PERCENT: Int = 100

        /** The smoothing window of the grade, in metres. */
        public const val DEFAULT_WINDOW_METERS: Double = 40.0

        private const val PERCENT = 100.0
    }
}
