package app.overdrive.core.route

import app.overdrive.core.gear.GearSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grade a ride on a route feels (spec §11.4, §4.4), worked by hand on [hillRoute]: flat to 100 m, then 2 m up every 20 m, so
 * 10% to the end at 200 m (10 m of ascent). The window is 40 m, the difficulty 50% and the gears the default 24, 1% each, neutral 12.
 */
class RouteGradeTest {
    private val route = hillRoute()
    private val course = RouteCourse(route)
    private val grade = RouteGrade(course)

    @Test
    fun `the elevation is interpolated between the points and held at the ends`() {
        assertEquals(0.0, course.elevationAt(0.0))
        assertEquals(0.0, course.elevationAt(100.0))
        assertEquals(1.0, course.elevationAt(110.0), 1e-12)
        assertEquals(2.5, course.elevationAt(125.0), 1e-12)
        assertEquals(10.0, course.elevationAt(200.0))
        assertEquals(0.0, course.elevationAt(-50.0))
        assertEquals(10.0, course.elevationAt(500.0))
        assertEquals(200.0, course.lengthMeters)
    }

    @Test
    fun `the road's grade is the rise over a window of 40 m centred on the rider`() {
        assertEquals(0.0, grade.roadPercentAt(50.0), 1e-12) // [30, 70]: flat
        assertEquals(5.0, grade.roadPercentAt(100.0), 1e-12) // [80, 120]: 0 to 2 m
        assertEquals(7.5, grade.roadPercentAt(110.0), 1e-12) // [90, 130]: 0 to 3 m
        assertEquals(6.25, grade.roadPercentAt(105.0), 1e-12) // [85, 125]: 0 to 2.5 m
        assertEquals(10.0, grade.roadPercentAt(150.0), 1e-12) // [130, 170]: 3 to 7 m
    }

    @Test
    fun `near the ends the window slides inside the route instead of shrinking`() {
        assertEquals(0.0, grade.roadPercentAt(0.0), 1e-12) // [0, 40]
        assertEquals(0.0, grade.roadPercentAt(10.0), 1e-12) // still [0, 40]
        assertEquals(10.0, grade.roadPercentAt(190.0), 1e-12) // [160, 200]: 6 to 10 m
        assertEquals(10.0, grade.roadPercentAt(199.9), 1e-12) // still [160, 200]
    }

    @Test
    fun `the difficulty scales the grade and 50 is the default`() {
        assertEquals(RouteGrade.DEFAULT_DIFFICULTY_PERCENT, 50)
        assertEquals(5.0, grade.routePercentAt(150.0), 1e-12) // 10% road at 50%
        assertEquals(2.5, grade.routePercentAt(100.0), 1e-12) // 5% road
        assertEquals(10.0, RouteGrade(course, difficultyPercent = 100).routePercentAt(150.0), 1e-12)
        assertEquals(1.0, RouteGrade(course, difficultyPercent = 10).routePercentAt(150.0), 1e-12)
        assertEquals(0.0, RouteGrade(course, difficultyPercent = 0).routePercentAt(150.0), 1e-12)
        assertFailsWith<IllegalArgumentException> { RouteGrade(course, difficultyPercent = 101) }
        assertFailsWith<IllegalArgumentException> { RouteGrade(course, difficultyPercent = -1) }
    }

    @Test
    fun `the grade is rounded to the trainer's resolution of 0,01 percent`() {
        // 6.25% road at 50% is 3.125, which FTMS cannot say: it is 3.13.
        assertEquals(3.13, grade.routePercentAt(105.0), 1e-12)
        // 7.5% road at 50% is 3.75, which it can.
        assertEquals(3.75, grade.routePercentAt(110.0), 1e-12)
    }

    @Test
    fun `the gear's offset is added on top and the total is clamped to the trainer's limit`() {
        val gears = GearSettings() // 24 gears, 1% a gear, neutral 12, limit 15
        assertEquals(5.0, grade.trainerPercentAt(150.0, gears.neutralGear), 1e-12)
        assertEquals(7.0, grade.trainerPercentAt(150.0, 14), 1e-12) // two gears harder
        assertEquals(-6.0, grade.trainerPercentAt(150.0, 1), 1e-12) // eleven gears easier: 5 - 11
        assertEquals(15.0, grade.trainerPercentAt(150.0, 24), 1e-12) // 5 + 12 = 17, held at 15
        // The route's own part is the same in every gear: only the pedals feel the gear.
        assertEquals(5.0, grade.routePercentAt(150.0), 1e-12)
    }

    @Test
    fun `a road steeper than the trainer can do is held at 15 percent, climbing and descending alike`() {
        val wall = testRoute(listOf(RoutePoint(0.0, 0.0), RoutePoint(100.0, 0.0), RoutePoint(200.0, 40.0)))
        val drop = testRoute(listOf(RoutePoint(0.0, 100.0), RoutePoint(100.0, 100.0), RoutePoint(200.0, 60.0)))
        // [130, 170] is 12 to 28 m: a 40% road, 40% at the full difficulty, and the trainer takes 15.
        assertEquals(15.0, RouteGrade(RouteCourse(wall), difficultyPercent = 100).routePercentAt(150.0), 1e-12)
        assertEquals(-15.0, RouteGrade(RouteCourse(drop), difficultyPercent = 100).routePercentAt(150.0), 1e-12)
        // At 50% it is 20%, still over; at 25% it is 10%, under.
        assertEquals(15.0, RouteGrade(RouteCourse(wall), difficultyPercent = 50).routePercentAt(150.0), 1e-12)
        assertEquals(10.0, RouteGrade(RouteCourse(wall), difficultyPercent = 25).routePercentAt(150.0), 1e-12)
        // A tighter trainer limit is the gear settings'.
        val tight = GearSettings(maxGradePercent = 8.0)
        assertEquals(
            8.0,
            RouteGrade(RouteCourse(wall), difficultyPercent = 100, gears = tight).routePercentAt(150.0),
            1e-12,
        )
        assertEquals(
            -8.0,
            RouteGrade(RouteCourse(drop), difficultyPercent = 100, gears = tight).trainerPercentAt(150.0, 12),
            1e-12,
        )
    }

    @Test
    fun `a descent is a negative grade and the gears still add to it`() {
        val drop = testRoute(listOf(RoutePoint(0.0, 100.0), RoutePoint(100.0, 100.0), RoutePoint(200.0, 80.0)))
        val down = RouteGrade(RouteCourse(drop))
        // [130, 170]: 94 to 86 m = -8 m in 40 m = -20% road, -10% at the default difficulty.
        assertEquals(-10.0, down.routePercentAt(150.0), 1e-12)
        assertEquals(-8.0, down.trainerPercentAt(150.0, 14), 1e-12)
        assertEquals(-15.0, down.trainerPercentAt(150.0, 1), 1e-12) // -10 - 11 = -21, held at -15
    }

    @Test
    fun `at the end of the route the grade is 0 and the route is finished`() {
        assertFalse(grade.isFinished(199.99))
        assertTrue(grade.isFinished(200.0))
        assertTrue(grade.isFinished(1000.0))
        assertEquals(0.0, grade.roadPercentAt(200.0))
        assertEquals(0.0, grade.routePercentAt(250.0))
        assertEquals(0.0, grade.trainerPercentAt(250.0, 12))
        // The gear's own offset is still there once the road has ended: the rider is on the flat in gear 14.
        assertEquals(2.0, grade.trainerPercentAt(250.0, 14), 1e-12)
    }

    @Test
    fun `a route shorter than the window uses all of it`() {
        val short = testRoute(listOf(RoutePoint(0.0, 0.0), RoutePoint(100.0, 5.0)))
        val tiny = RouteGrade(RouteCourse(short), windowMeters = 500.0)
        assertEquals(5.0, tiny.roadPercentAt(0.0), 1e-12)
        assertEquals(5.0, tiny.roadPercentAt(60.0), 1e-12)
    }

    @Test
    fun `the smoothed grade is the mean of the road's grade over the window, found independently`() {
        // The road's grade is constant between points; integrate it over the window with a fine step and compare.
        val points = route.points

        fun segmentGrade(at: Double): Double {
            val i = points.indexOfLast { it.d <= at }.coerceAtMost(points.size - 2)
            return (points[i + 1].e - points[i].e) / (points[i + 1].d - points[i].d) * 100
        }
        var distance = 0.0
        while (distance < 199.0) {
            val low = (distance - 20.0).coerceIn(0.0, 160.0)
            var sum = 0.0
            val steps = 8000
            for (k in 0 until steps) sum += segmentGrade(low + (k + 0.5) * 40.0 / steps)
            // A midpoint rule is off by at most half a cell where the grade jumps: 0.0025 m of 40 m at a jump of 10%.
            assertEquals(sum / steps, grade.roadPercentAt(distance), 1e-3, "at $distance m")
            distance += 7.3
        }
    }

    @Test
    fun `the grade has no jumps and is continuous along the road`() {
        var previous = grade.roadPercentAt(0.0)
        var distance = 0.1
        while (distance < 199.9) {
            val now = grade.roadPercentAt(distance)
            assertTrue(kotlin.math.abs(now - previous) < 0.3, "a jump of ${now - previous} at $distance m")
            previous = now
            distance += 0.1
        }
    }

    @Test
    fun `positions are interpolated on a route with coordinates and absent on a profile`() {
        val line = RouteCourse(lineRoute(length = 100.0))
        val start = assertNotNull(line.positionAt(0.0))
        assertEquals(45.0, start.latitude, 1e-12)
        assertEquals(7.0, start.longitude, 1e-12)
        val half = assertNotNull(line.positionAt(50.0))
        assertEquals(45.0 + 50.0 * 9e-6, half.latitude, 1e-12)
        val between = assertNotNull(line.positionAt(30.0)) // between the points at 20 m and 40 m
        assertEquals(45.0 + 30.0 * 9e-6, between.latitude, 1e-12)
        assertEquals(line.positionAt(100.0), line.positionAt(500.0)) // does not extend
        assertEquals(line.positionAt(0.0), line.positionAt(-5.0))
        assertNull(course.positionAt(100.0)) // the hill is a profile
        assertFalse(course.hasCoordinates)
    }

    @Test
    fun `a route of two points works`() {
        val two = RouteCourse(testRoute(listOf(RoutePoint(0.0, 10.0), RoutePoint(100.0, 14.0))))
        assertEquals(12.0, two.elevationAt(50.0), 1e-12)
        assertEquals(2.0, RouteGrade(two).routePercentAt(10.0), 1e-12) // 4% at 50%
    }
}
