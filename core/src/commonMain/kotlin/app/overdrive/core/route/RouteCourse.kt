package app.overdrive.core.route

/** A place on the earth in degrees (the FIT writer turns it into the semicircles `position_lat` and `position_long` take). */
public data class GeoPosition(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A [Route]'s points as the ride reads them: the elevation (and, on a route with coordinates, the position) at any distance along
 * the route, by linear interpolation between the points the service resampled (one every 20 m or so). Pure and immutable; built once
 * per route per ride.
 *
 * A distance before the start is the start and one beyond the end is the end: the route does not extend, and does not loop (spec
 * §11.4, [RouteGrade] says what the ride does at the end).
 */
public class RouteCourse(
    public val route: Route,
) {
    private val distances = DoubleArray(route.points.size) { route.points[it].d }
    private val elevations = DoubleArray(route.points.size) { route.points[it].e }

    /** The length of the route in metres: the distance of its last point. */
    public val lengthMeters: Double = distances.last()

    /** Every point has coordinates: the route can write positions into a FIT file. */
    public val hasCoordinates: Boolean get() = route.hasCoordinates

    /** The elevation in metres at [distanceMeters] along the route. */
    public fun elevationAt(distanceMeters: Double): Double {
        val at = distanceMeters.coerceIn(0.0, lengthMeters)
        val i = segmentOf(at)
        return lerp(elevations[i], elevations[i + 1], fraction(i, at))
    }

    /** The position at [distanceMeters] along the route, or null for a route with no coordinates (a profile). */
    public fun positionAt(distanceMeters: Double): GeoPosition? {
        if (!hasCoordinates) return null
        val at = distanceMeters.coerceIn(0.0, lengthMeters)
        val i = segmentOf(at)
        val from = route.points[i]
        val to = route.points[i + 1]
        val t = fraction(i, at)
        return GeoPosition(
            latitude = lerp(checkNotNull(from.lat), checkNotNull(to.lat), t),
            longitude = lerp(checkNotNull(from.lon), checkNotNull(to.lon), t),
        )
    }

    /** The index of the segment (points `i` and `i + 1`) that [at] lies in; the last point belongs to the last segment. */
    private fun segmentOf(at: Double): Int {
        var low = 0
        var high = distances.size - 2
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (distances[middle] <= at) low = middle else high = middle - 1
        }
        return low
    }

    private fun fraction(
        segment: Int,
        at: Double,
    ): Double = (at - distances[segment]) / (distances[segment + 1] - distances[segment])

    private fun lerp(
        from: Double,
        to: Double,
        t: Double,
    ): Double = from + (to - from) * t
}
