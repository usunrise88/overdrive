package app.overdrive.core.route

import kotlin.math.floor

/**
 * A valid [Route] from points, with the distance and the ascent worked out the way the service does it, so a test writes only the
 * shape of the road. A route whose points all have coordinates is a GPX route, else a profile. All coordinates and elevations in the
 * tests are invented.
 */
internal fun testRoute(
    points: List<RoutePoint>,
    id: String = "route-test",
    name: String = "Test route",
    createdAt: String = "2026-11-19T09:00:00Z",
): Route {
    val withCoordinates = points.all { it.hasCoordinates }
    var rise = 0.0
    for (i in 1 until points.size) rise += maxOf(0.0, points[i].e - points[i - 1].e)
    return Route(
        id = id,
        name = name,
        source = if (withCoordinates) RouteSource.GPX else RouteSource.PROFILE,
        distanceMeters = floor(points.last().d + 0.5).toInt(),
        ascentMeters = floor(rise + 0.5).toInt(),
        hasCoordinates = withCoordinates,
        createdAt = createdAt,
        points = points,
    )
}

/**
 * A profile of 200 m: flat to 100 m, then up 2 m every 20 m (10%) to 10 m of ascent. The numbers of the hand-worked goldens in
 * [RouteGradeTest] are on it.
 */
internal fun hillPoints(): List<RoutePoint> =
    (
        listOf(0.0 to 0.0, 20.0 to 0.0, 40.0 to 0.0, 60.0 to 0.0, 80.0 to 0.0, 100.0 to 0.0) +
            listOf(120.0 to 2.0, 140.0 to 4.0, 160.0 to 6.0, 180.0 to 8.0, 200.0 to 10.0)
    ).map { (d, e) -> RoutePoint(d, e) }

internal fun hillRoute(id: String = "route-hill"): Route = testRoute(hillPoints(), id = id, name = "Hill")

/** [length] metres along a line of invented coordinates near (45.0, 7.0), a point every [step] metres, with the elevations [elevations] repeated as needed. */
internal fun lineRoute(
    length: Double,
    step: Double = 20.0,
    elevation: (Double) -> Double = { 100.0 },
    id: String = "route-line",
    name: String = "Line",
): Route {
    val count = (length / step).toInt()
    val points =
        (0..count).map { i ->
            val d = i * step
            // About 0.00001 degrees of latitude per metre of the invented line: 1 m = 9e-6 degrees, far from any real road.
            RoutePoint(d, elevation(d), lat = 45.0 + d * 9e-6, lon = 7.0)
        }
    return testRoute(points, id = id, name = name)
}
