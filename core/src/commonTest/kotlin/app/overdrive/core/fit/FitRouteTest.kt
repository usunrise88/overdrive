package app.overdrive.core.fit

import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordBuilder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.SYNTHETIC_START_MILLIS
import app.overdrive.core.route.Route
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.testRoute
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEMICIRCLES_PER_DEGREE = 2_147_483_648.0 / 180.0

/**
 * The positions and the altitude of a ride on a route (spec §11.4, `docs/protocols/fit.md` 4.3), read back with the independent test
 * reader (`FitReader`, which shares no code with the writer) and checked against numbers worked out here: semicircles by their own
 * formula and golden values, the altitude by the profile's scale and offset. All coordinates and elevations are invented.
 */
class FitRouteTest {
    /** A line due north from (45, 7): a point every 20 m, 9e-6 degrees of latitude a metre, rising 0.1 m a metre from 100 m. */
    private val gpx =
        testRoute(
            (0..20).map { RoutePoint(it * 20.0, 100.0 + it * 2.0, lat = 45.0 + it * 20.0 * 9e-6, lon = 7.0) },
            id = "route-north",
            name = "North line",
        )

    /** The same line with no coordinates: a profile. */
    private val profile =
        testRoute((0..20).map { RoutePoint(it * 20.0, 100.0 + it * 2.0) }, id = "route-profile", name = "Profile")

    private fun sample(
        second: Int,
        distance: Double,
        stepIndex: Int? = null,
    ) = RideSample(
        timeOffsetSeconds = second,
        power = 180,
        heartRate = 130,
        cadence = 85,
        speedKmh = 36.0,
        distanceMeters = distance,
        stepIndex = stepIndex,
    )

    /** A free ride of 6 seconds at 10 m/s, the first record at the start of the route. */
    private fun freeRide(routeId: String? = "route-north"): RideRecord =
        RideRecordBuilder.recover(
            RideHeader(
                "free-route",
                SYNTHETIC_START_MILLIS,
                ftp = 200,
                routeId = routeId,
                routeName = routeId?.let { "A route" },
            ),
            (1..6).map { sample(it, distance = (it - 1) * 10.0) },
        )

    private fun routes(
        route: Route?,
        stepIndex: Int? = null,
    ) = FitRoutes { if (it == stepIndex) route else null }

    private fun degrees(semicircles: Long) = semicircles / SEMICIRCLES_PER_DEGREE

    @Test
    fun `semicircles are two to the 31 for 180 degrees with golden numbers`() {
        assertEquals(536_870_912L, semicircles(45.0)) // 2^29
        assertEquals(83_513_253L, semicircles(7.0))
        assertEquals(-1_109_533_218L, semicircles(-93.0))
        assertEquals(-1_073_741_824L, semicircles(-90.0)) // -2^30
        assertEquals(1_073_741_824L, semicircles(90.0))
        assertEquals(0L, semicircles(0.0))
        assertEquals(536_871_986L, semicircles(45.00009))
        // The altitude: a fifth of a metre above a datum 500 m down.
        assertEquals(3_005L, rawAltitude(101.0))
        assertEquals(2_440L, rawAltitude(-12.0))
        assertEquals(2_500L, rawAltitude(0.0))
        assertEquals(3_000L, rawAltitude(100.0))
        assertEquals(3_001L, rawAltitude(100.2))
    }

    @Test
    fun `a ride on a route with coordinates writes its positions and altitude at the distance ridden`() {
        val file = parseFit(FitWriter().write(freeRide(), routes(gpx)))
        val records = file.messages("record")
        assertEquals(6, records.size)
        for ((index, message) in records.withIndex()) {
            val along = index * 10.0 // the sample's distance: 0, 10, ... 50 m
            val expectedLatitude = 45.0 + along * 9e-6
            assertEquals(
                semicircles(expectedLatitude),
                checkNotNull(message.raw("position_lat")),
                "latitude of second ${index + 1}",
            )
            assertEquals(semicircles(7.0), message.raw("position_long"))
            assertEquals(expectedLatitude, degrees(checkNotNull(message.raw("position_lat"))), 1e-7)
            // 100 m at the start, 0.1 m up for every metre: the altitude is 100 + 0.1 d, stored in fifths of a metre above -500.
            val expectedAltitude = 100.0 + 0.1 * along
            assertEquals(expectedAltitude, checkNotNull(message.scaled("altitude")), 0.11)
            assertEquals(message.raw("altitude"), message.raw("enhanced_altitude"), "both altitude fields say the same")
            assertEquals(rawAltitude(expectedAltitude), message.raw("altitude"))
        }
        // Golden: the first record is at the start of the route, (45, 7), 100 m up.
        assertEquals(536_870_912L, records.first().raw("position_lat"))
        assertEquals(83_513_253L, records.first().raw("position_long"))
        assertEquals(3_000L, records.first().raw("altitude"))
        // The rest of the record is what it always was.
        assertEquals(130L, records.first().raw("heart_rate"))
        assertEquals(180L, records.first().raw("power"))
    }

    @Test
    fun `positions are interpolated between the points of the route`() {
        // 5 s at 4 m/s: 0, 4, 8, ... 20 m. The points are 20 m apart, so every second is inside the first segment.
        val slow =
            RideRecordBuilder.recover(
                RideHeader(
                    "slow",
                    SYNTHETIC_START_MILLIS,
                    ftp = 200,
                    routeId = "route-north",
                    routeName = "North line",
                ),
                (1..6).map { sample(it, distance = (it - 1) * 4.0) },
            )
        val records = parseFit(FitWriter().write(slow, routes(gpx))).messages("record")
        assertEquals(semicircles(45.0 + 12.0 * 9e-6), records[3].raw("position_lat")) // 12 m
        assertEquals(semicircles(45.0 + 20.0 * 9e-6), records[5].raw("position_lat")) // 20 m: the next point
    }

    @Test
    fun `a ride that outlasts its route stays at the last point`() {
        // The route is 400 m; this ride is 7 s at 100 m/s.
        val fast =
            RideRecordBuilder.recover(
                RideHeader(
                    "fast",
                    SYNTHETIC_START_MILLIS,
                    ftp = 200,
                    routeId = "route-north",
                    routeName = "North line",
                ),
                (1..7).map { sample(it, distance = (it - 1) * 100.0) },
            )
        val records = parseFit(FitWriter().write(fast, routes(gpx))).messages("record")
        assertEquals(semicircles(45.0 + 400.0 * 9e-6), records[4].raw("position_lat")) // 400 m, the end
        assertEquals(records[4].raw("position_lat"), records[5].raw("position_lat"))
        assertEquals(records[4].raw("position_lat"), records[6].raw("position_lat"))
        assertEquals(records[4].raw("altitude"), records[6].raw("altitude"))
    }

    @Test
    fun `a profile writes the altitude and no position so it draws no map`() {
        val bytes = FitWriter().write(freeRide("route-profile"), routes(profile))
        val file = parseFit(bytes)
        val definition = file.definitions.single { it.globalNumber == 20 }
        val numbers = definition.fields.map { it.first }
        assertTrue(2 in numbers && 78 in numbers, "altitude fields: $numbers")
        assertFalse(0 in numbers || 1 in numbers, "no position fields: $numbers")
        val first = file.messages("record").first()
        assertEquals(3_000L, first.raw("altitude"))
        assertFalse(first.has("position_lat"))
    }

    @Test
    fun `a ride with no route is byte for byte what it was`() {
        val record = freeRide(routeId = null)
        val plain = FitWriter().write(record)
        assertContentEquals(plain, FitWriter().write(record, FitRoutes.None))
        assertContentEquals(plain, FitWriter().write(record, routes(null)))
        val numbers = parseFit(plain).definitions.single { it.globalNumber == 20 }.fields.map { it.first }
        assertEquals(listOf(253, 3, 4, 5, 6, 7, 73), numbers, "the layout every file had")
        // And the options that turn the new fields off give the same file for a ride that has a route.
        val off = FitWriter(FitOptions(positions = false, altitude = false)).write(freeRide(), routes(gpx))
        assertContentEquals(FitWriter().write(freeRide()), off)
    }

    @Test
    fun `the options turn the positions and the altitude off one at a time`() {
        val noPositions = parseFit(FitWriter(FitOptions(positions = false)).write(freeRide(), routes(gpx)))
        val first = noPositions.messages("record").first()
        assertFalse(first.has("position_lat"))
        assertEquals(3_000L, first.raw("altitude"))
        val noAltitude = parseFit(FitWriter(FitOptions(altitude = false)).write(freeRide(), routes(gpx)))
        val second = noAltitude.messages("record").first()
        assertFalse(second.has("altitude") || second.has("enhanced_altitude"))
        assertEquals(536_870_912L, second.raw("position_lat"))
    }

    @Test
    fun `in a workout only the seconds of a route-backed step have a place`() {
        // Steps: 0 ERG (3 s), 1 a free step on the route (4 s), 2 ERG (2 s), at 10 m/s the whole way: the distance is cumulative.
        val steps = listOf(0, 0, 0, 1, 1, 1, 1, 2, 2)
        val record =
            RideRecordBuilder.recover(
                RideHeader("workout-route", SYNTHETIC_START_MILLIS, workoutId = "icu-9", ftp = 200),
                steps.mapIndexed { index, step -> sample(index + 1, distance = (index + 1) * 10.0, stepIndex = step) },
            )
        val records = parseFit(FitWriter().write(record, routes(gpx, stepIndex = 1))).messages("record")
        for (index in 0..2) assertNull(records[index].raw("position_lat"), "ERG second ${index + 1}")
        // The route starts at the start of the step: the distance at the end of the third second was 30 m, so the fourth second is
        // 10 m along the route, the fifth 20 m, and so on (the same count the engine keeps).
        for (index in 3..6) {
            val along = (index - 2) * 10.0
            assertEquals(semicircles(45.0 + along * 9e-6), records[index].raw("position_lat"), "second ${index + 1}")
            assertEquals(rawAltitude(100.0 + 0.1 * along), records[index].raw("altitude"))
        }
        for (index in 7..8) assertNull(records[index].raw("position_lat"), "ERG second ${index + 1}")
        assertNull(records[0].raw("altitude"))
    }

    @Test
    fun `a step that is ridden again starts its route again`() {
        // Step 1, then step 0, then step 1 again: two laps on the route, each from its start.
        val steps = listOf(1, 1, 0, 1, 1)
        val record =
            RideRecordBuilder.recover(
                RideHeader("again", SYNTHETIC_START_MILLIS, workoutId = "icu-9", ftp = 200),
                steps.mapIndexed { index, step -> sample(index + 1, distance = (index + 1) * 10.0, stepIndex = step) },
            )
        val records = parseFit(FitWriter().write(record, routes(gpx, stepIndex = 1))).messages("record")
        assertEquals(semicircles(45.0 + 10.0 * 9e-6), records[0].raw("position_lat"))
        assertEquals(semicircles(45.0 + 20.0 * 9e-6), records[1].raw("position_lat"))
        assertNull(records[2].raw("position_lat"))
        assertEquals(
            semicircles(45.0 + 10.0 * 9e-6),
            records[3].raw("position_lat"),
            "the third lap starts at the start",
        )
        assertEquals(semicircles(45.0 + 20.0 * 9e-6), records[4].raw("position_lat"))
    }

    @Test
    fun `west and south are negative semicircles and the largest longitude is held below the invalid value`() {
        val south =
            testRoute(
                listOf(
                    RoutePoint(0.0, 10.0, lat = -33.0, lon = -93.0),
                    RoutePoint(100.0, 10.0, lat = -33.001, lon = 180.0),
                ),
                id = "route-south",
            )
        val record =
            RideRecordBuilder.recover(
                RideHeader("south", SYNTHETIC_START_MILLIS, ftp = 200, routeId = "route-south", routeName = "South"),
                listOf(sample(1, 0.0), sample(2, 100.0)),
            )
        val records = parseFit(FitWriter().write(record, routes(south))).messages("record")
        assertEquals(semicircles(-33.0), records[0].raw("position_lat"))
        assertEquals(-1_109_533_218L, records[0].raw("position_long"))
        // 180 degrees is 2^31, one more than a sint32 holds: held at its largest real value, not at the invalid one.
        assertEquals(2_147_483_646L, records[1].raw("position_long"))
    }

    @Test
    fun `the file with positions is deterministic and the writer reads back what it wrote`() {
        val a = FitWriter().write(freeRide(), routes(gpx))
        val b = FitWriter().write(freeRide(), routes(gpx))
        assertContentEquals(a, b)
        assertEquals(0, FitReader.crc16(a, 0, a.size))
        // The profile's facts agree with every field written (parseFit checks base types, names and numbers).
        parseFit(a)
    }
}
