package app.overdrive.core.route

import app.overdrive.core.ride.flatSteps
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Which route a step of a ride follows (spec §11.4): the chosen one for a ride with no workout, the step's own otherwise. */
class RideRoutesTest {
    private val a = hillRoute("route-a")
    private val b = hillRoute("route-b")
    private val workout =
        Workout(
            1,
            "icu-1",
            "Out",
            steps =
                listOf(
                    SteadyStep(duration = 60, target = 0.5),
                    RepeatStep(
                        count = 2,
                        steps =
                            listOf(
                                FreeStep(duration = 300, routeId = "route-a"),
                                SteadyStep(duration = 60, target = 0.5),
                            ),
                    ),
                    FreeStep(duration = 300, routeId = "route-b"),
                    FreeStep(duration = 300),
                ),
        )
    private val steps = workout.flatSteps()

    @Test
    fun `a ride with no workout follows the chosen route and a step of a workout its own`() {
        val chosen = RideRoutes(freeRide = a)
        assertSame(a, chosen.routeOf(emptyList(), null))
        assertSame(a, chosen.firstRoute(emptyList()))
        assertNull(RideRoutes.None.routeOf(emptyList(), null))

        val routes = RideRoutes(byId = mapOf("route-a" to a, "route-b" to b))
        // Flattened: 0 ERG, 1 free on a, 2 ERG, 3 free on a, 4 ERG, 5 free on b, 6 free with no route.
        assertEquals(
            listOf(null, a, null, a, null, b, null),
            steps.indices.map { routes.routeOf(steps, it) },
        )
        assertSame(a, routes.firstRoute(steps))
    }

    @Test
    fun `a route that is not downloaded is a flat step and the first route is the first that is there`() {
        val onlyB = RideRoutes(byId = mapOf("route-b" to b))
        assertEquals(listOf(null, null, null, null, null, b, null), steps.indices.map { onlyB.routeOf(steps, it) })
        assertSame(b, onlyB.firstRoute(steps))
        assertNull(RideRoutes.None.firstRoute(steps))
    }

    @Test
    fun `the chosen route is for a ride with no workout only`() {
        val chosen = RideRoutes(freeRide = a)
        assertNull(chosen.routeOf(steps, 6), "a free step of a workout with no route stays flat")
        assertNull(chosen.firstRoute(steps))
    }

    @Test
    fun `the routes of the FIT file are the same ones by step`() {
        val routes = RideRoutes(byId = mapOf("route-a" to a))
        val fit = routes.forFit(steps)
        assertSame(a, fit.routeFor(1))
        assertNull(fit.routeFor(0))
        assertNull(fit.routeFor(null))
        assertNull(fit.routeFor(99))
        assertSame(a, RideRoutes(freeRide = a).forFit(emptyList()).routeFor(null))
    }

    @Test
    fun `the difficulty is a percentage`() {
        assertFailsWith<IllegalArgumentException> { RideRoutes(difficultyPercent = 101) }
        assertFailsWith<IllegalArgumentException> { RideRoutes(difficultyPercent = -1) }
        assertEquals(50, RideRoutes.None.difficultyPercent)
    }
}
