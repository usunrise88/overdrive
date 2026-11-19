package app.overdrive.core.fit

import app.overdrive.core.record.RideRecord
import app.overdrive.core.route.GeoPosition
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteCourse
import kotlin.math.roundToLong

/**
 * Which route (spec §11.4) the seconds of a ride rode, for the FIT file's positions and altitude (docs/protocols/fit.md 4.3). A ride
 * record keeps only the route's id and name; the route's points are the tablet's, so the writer is told which route each stretch of the
 * ride was on.
 *
 * A **stretch** is a lap of the record: a run of seconds in one workout step (a ride without a workout is one lap). The ride's distance
 * along the route is the distance ridden since the stretch began, which is how the engine counts it too (the route starts again at 0
 * when the step changes), so a record and the route are enough to place every second.
 */
public fun interface FitRoutes {
    /** The route the stretch in workout step [stepIndex] (null for a ride without a workout) followed, or null for the flat road. */
    public fun routeFor(stepIndex: Int?): Route?

    public companion object {
        /** No route anywhere: the file has no positions and no altitude, and is byte for byte what it was before routes. */
        public val None: FitRoutes = FitRoutes { null }
    }
}

/** A place and an altitude on a route at one second of the ride. [position] is null on a route with no coordinates. */
internal class RouteFix(
    val position: GeoPosition?,
    val altitudeMeters: Double,
)

/**
 * The fix of every second of [record] on the routes [routes] says it followed, by sample index (offset − 1); null where the ride was on
 * the flat road. Distance along the route is the sample's distance minus the distance at the start of its lap, and the route does not
 * extend: past its end the last point is the place.
 */
internal fun routeFixes(
    record: RideRecord,
    routes: FitRoutes,
): Array<RouteFix?> {
    val fixes = arrayOfNulls<RouteFix>(record.samples.size)
    val courses = HashMap<String, RouteCourse>()
    for (lap in record.laps) {
        val route = routes.routeFor(lap.stepIndex) ?: continue
        val course = courses.getOrPut(route.id) { RouteCourse(route) }
        val startDistance = if (lap.startSeconds == 0) 0.0 else record.samples[lap.startSeconds - 1].distanceMeters
        for (index in lap.startSeconds until lap.endSeconds) {
            val along = record.samples[index].distanceMeters - startDistance
            fixes[index] = RouteFix(course.positionAt(along), course.elevationAt(along))
        }
    }
    return fixes
}

/** Degrees in the FIT format's semicircles: 2³¹ semicircles are 180 degrees (docs/protocols/fit.md 4.3). */
internal fun semicircles(degrees: Double): Long = (degrees * SEMICIRCLES_PER_DEGREE).roundToLong()

/** The raw value of the record's `altitude` and `enhanced_altitude`: a fifth of a metre above a datum 500 m below sea level. */
internal fun rawAltitude(meters: Double): Long = ((meters + ALTITUDE_OFFSET_METERS) * ALTITUDE_SCALE).roundToLong()

private const val SEMICIRCLES_PER_DEGREE = 2_147_483_648.0 / 180.0
private const val ALTITUDE_OFFSET_METERS = 500.0
private const val ALTITUDE_SCALE = 5.0
