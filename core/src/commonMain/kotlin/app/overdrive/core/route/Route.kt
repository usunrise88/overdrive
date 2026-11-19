package app.overdrive.core.route

import app.overdrive.core.wellness.WellnessTime
import app.overdrive.core.workout.isValidRouteId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How a route came in (`contracts/schema/route.schema.json`): a GPX file, waypoints the service routed over roads, or a synthetic
 * elevation profile. Only [PROFILE] has no coordinates, so no map.
 */
@Serializable
public enum class RouteSource {
    @SerialName("gpx")
    GPX,

    @SerialName("waypoints")
    WAYPOINTS,

    @SerialName("profile")
    PROFILE,
}

/**
 * One resampled point of a route: [d] metres along the route from its start (the first is 0), [e] the smoothed elevation in metres
 * above sea level, and, on routes that follow real roads, [lat] and [lon] in degrees. The service sends no grade: the tablet works it
 * out from the elevations ([RouteGrade]).
 */
@Serializable
public data class RoutePoint(
    val d: Double,
    val e: Double,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    init {
        require(d.isFinite() && d >= 0.0 && d <= RoutesApiJson.MAX_DISTANCE_METERS) {
            "A point's distance is 0 to 1000000 m, got $d"
        }
        require(e.isFinite() && e >= MIN_ELEVATION_METERS && e <= MAX_ELEVATION_METERS) {
            "A point's elevation is $MIN_ELEVATION_METERS to $MAX_ELEVATION_METERS m, got $e"
        }
        require((lat == null) == (lon == null)) { "A point has both lat and lon or neither" }
        require(lat == null || (lat.isFinite() && lat >= -MAX_LATITUDE && lat <= MAX_LATITUDE)) {
            "Latitude is -90 to 90, got $lat"
        }
        require(lon == null || (lon.isFinite() && lon >= -MAX_LONGITUDE && lon <= MAX_LONGITUDE)) {
            "Longitude is -180 to 180, got $lon"
        }
    }

    /** Whether the point has coordinates. */
    public val hasCoordinates: Boolean get() = lat != null
}

private const val MIN_ELEVATION_METERS = -500.0
private const val MAX_ELEVATION_METERS = 9000.0
private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0

/** A route without its points: a row of `GET /v1/routes` (`contracts/schema/route.schema.json` `$defs/summary`). */
@Serializable
public data class RouteSummary(
    val id: String,
    val name: String,
    val source: RouteSource,
    /** The last point's `d`, rounded to a whole metre (at least 100). */
    val distanceMeters: Int,
    /** The sum of the rises between consecutive points, rounded to a whole metre. */
    val ascentMeters: Int,
    /** Every point has `lat` and `lon`: the route follows real roads and can write positions into the FIT file, so Strava can draw a map. */
    val hasCoordinates: Boolean,
    /** UTC instant. */
    val createdAt: String,
) {
    init {
        RoutesApiJson.requireSummary(id, name, source, distanceMeters, ascentMeters, hasCoordinates, createdAt)
    }
}

/**
 * One route with its points: `GET /v1/routes/{id}` (spec §8.3, §11.4, ADR 0036). Read as strictly as the schema and its rules:
 * 2 to 5000 points, the first at distance 0 and distances strictly increasing, [distanceMeters] the last distance rounded, [ascentMeters]
 * within a metre of the sum of the rises, coordinates on every point or none, and `hasCoordinates` and the source in agreement.
 */
@Serializable
public data class Route(
    val id: String,
    val name: String,
    val source: RouteSource,
    val distanceMeters: Int,
    val ascentMeters: Int,
    val hasCoordinates: Boolean,
    val createdAt: String,
    val points: List<RoutePoint>,
) {
    init {
        RoutesApiJson.requireSummary(id, name, source, distanceMeters, ascentMeters, hasCoordinates, createdAt)
        require(points.size in RoutesApiJson.MIN_POINTS..RoutesApiJson.MAX_POINTS) {
            "A route has ${RoutesApiJson.MIN_POINTS} to ${RoutesApiJson.MAX_POINTS} points, got ${points.size}"
        }
        require(points.first().d == 0.0) { "The first point is at 0 m, not ${points.first().d}" }
        for (i in 1 until points.size) {
            require(
                points[i].d > points[i - 1].d,
            ) { "Point $i at ${points[i].d} m does not come after ${points[i - 1].d} m" }
        }
        require(distanceMeters == points.last().d.roundHalfUp()) {
            "distanceMeters is $distanceMeters, the last point is at ${points.last().d} m"
        }
        var rise = 0.0
        for (i in 1 until points.size) rise += maxOf(0.0, points[i].e - points[i - 1].e)
        require(abs(ascentMeters - rise) <= 1.0) { "ascentMeters is $ascentMeters, the points rise $rise m" }
        val withCoordinates = points.count { it.hasCoordinates }
        require(
            withCoordinates == 0 || withCoordinates == points.size,
        ) { "Either every point has coordinates or none does" }
        require(hasCoordinates == (withCoordinates == points.size)) {
            "hasCoordinates is $hasCoordinates but $withCoordinates of ${points.size} points have coordinates"
        }
    }

    /** The route as the list shows it, without its points. */
    public val summary: RouteSummary
        get() = RouteSummary(id, name, source, distanceMeters, ascentMeters, hasCoordinates, createdAt)
}

/** JavaScript's `Math.round` for the non-negative distances here: halves go up. */
private fun Double.roundHalfUp(): Int = floor(this + HALF).toInt()

private const val HALF = 0.5

/**
 * The service's answer to `GET /v1/routes` (`contracts/schema/routes-response.schema.json`): the routes the agent created, newest first
 * and then by id, without their points. [generatedAt] is when the service answered (UTC). Not cached by the service; the tablet keeps
 * the last good answer on disk so the list shows at once and offline.
 */
@Serializable
public data class RoutesResponse(
    val generatedAt: String,
    val routes: List<RouteSummary>,
) {
    init {
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant: '$generatedAt'" }
        val seen = HashSet<String>()
        var previous: RouteSummary? = null
        for (route in routes) {
            require(seen.add(route.id)) { "Route ${route.id} is listed twice" }
            val before = previous
            if (before != null) {
                val created = checkNotNull(WellnessTime.parseInstant(route.createdAt))
                val earlier = checkNotNull(WellnessTime.parseInstant(before.createdAt))
                require(created < earlier || (created == earlier && route.id > before.id)) {
                    "Routes are newest first, then by id: ${route.id} is out of order"
                }
            }
            previous = route
        }
    }
}

/**
 * Reads and writes the routes (spec §8.3, §11.4). Strict like the schema: unknown keys, an id outside the accepted syntax, and every rule
 * of the schema's description are rejected with an [IllegalArgumentException].
 */
public object RoutesApiJson {
    public const val MIN_POINTS: Int = 2
    public const val MAX_POINTS: Int = 5000
    public const val MIN_DISTANCE_METERS: Int = 100
    public const val MAX_DISTANCE_METERS: Double = 1_000_000.0
    public const val MAX_NAME_CHARS: Int = 100

    private val json = Json

    /** @throws IllegalArgumentException if [text] is not the routes list of the schema. */
    public fun decodeRoutes(text: String): RoutesResponse = json.decodeFromString(RoutesResponse.serializer(), text)

    /** @throws IllegalArgumentException if [text] is not a route of the schema. */
    public fun decodeRoute(text: String): Route = json.decodeFromString(Route.serializer(), text)

    public fun encodeRoutes(response: RoutesResponse): String =
        json.encodeToString(RoutesResponse.serializer(), response)

    public fun encodeRoute(route: Route): String = json.encodeToString(Route.serializer(), route)

    /** Whether [id] is a route id: 1 to 64 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit. */
    public fun isValidRouteId(id: String): Boolean = app.overdrive.core.workout.isValidRouteId(id)

    internal fun requireSummary(
        id: String,
        name: String,
        source: RouteSource,
        distanceMeters: Int,
        ascentMeters: Int,
        hasCoordinates: Boolean,
        createdAt: String,
    ) {
        require(isValidRouteId(id)) { "Not a route id: '$id'" }
        require(name.length in 1..MAX_NAME_CHARS) { "A route's name has 1 to $MAX_NAME_CHARS characters" }
        require(distanceMeters in MIN_DISTANCE_METERS..MAX_DISTANCE_METERS.roundToInt()) {
            "A route is $MIN_DISTANCE_METERS to 1000000 m long, got $distanceMeters"
        }
        require(ascentMeters >= 0) { "The ascent is not negative, got $ascentMeters" }
        require(WellnessTime.isUtcInstant(createdAt) && WellnessTime.parseInstant(createdAt) != null) {
            "createdAt is not a real UTC instant: '$createdAt'"
        }
        require(hasCoordinates == (source != RouteSource.PROFILE)) {
            "hasCoordinates is $hasCoordinates for source $source: only a profile has none"
        }
    }
}
