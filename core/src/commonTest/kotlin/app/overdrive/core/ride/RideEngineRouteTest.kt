package app.overdrive.core.ride

import app.overdrive.core.device.SimulatedTrainerDevice
import app.overdrive.core.physics.FlatPhysicsSpeedModel
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.route.RideRoutes
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.lineRoute
import app.overdrive.core.route.testRoute
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val POWER = 200
private const val CADENCE = 90

/** A road of a steady 10%: 0.1 m up for every metre. At the default difficulty of 50% the trainer feels 5%. */
private fun climb(
    length: Double,
    id: String = "route-climb",
) = lineRoute(length, elevation = { 100.0 + 0.1 * it }, id = id, name = "Climb $id")

/** The engine on a route over a simulated trainer in virtual time (spec §11.4). */
@OptIn(ExperimentalCoroutinesApi::class)
private class RouteRig(
    private val testScope: TestScope,
    routes: RideRoutes,
    workout: Workout? = null,
    variantRidden: Boolean = false,
    settings: RideSettings = RideSettings(ftp = 200),
) {
    val trainer = SimulatedTrainerDevice(clock = { testScope.testScheduler.currentTime })
    val recorder = InMemoryRideRecorder()
    val engine =
        RideEngine(
            scope = testScope.backgroundScope,
            trainer = trainer,
            heartRate = null,
            workout = workout,
            settings = settings,
            recorder = recorder,
            rideId = "route-ride",
            clock = { ENGINE_TEST_START_MILLIS + testScope.testScheduler.currentTime },
            routes = routes,
            variantRidden = variantRidden,
        )

    init {
        trainer.connect()
        testScope.runCurrent()
    }

    val now: RideSnapshot get() = engine.snapshots.value
    val record: RideRecord get() = checkNotNull(recorder.record)

    fun start() {
        engine.start()
        testScope.runCurrent()
    }

    /** Lets the engine process the commands issued so far, without a second passing. */
    fun settle() = testScope.runCurrent()

    fun second(
        power: Int? = POWER,
        cadence: Int? = CADENCE,
    ) {
        trainer.emit(power = power, cadence = cadence)
        testScope.advanceSeconds(1)
    }

    fun seconds(count: Int) = repeat(count) { second() }

    val simulatedGrade: Double? get() = trainer.simulation?.gradePercent
}

/** What the model says for 200 W on a road of [grade] percent: the independent reference the engine's numbers are checked against. */
private fun metersPerSecond(
    grade: Double,
    power: Int = POWER,
) = FlatPhysicsSpeedModel().speedKmh(power.toDouble(), grade) / 3.6

@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineRouteTest {
    @Test
    fun `a free ride on a route feels its grade and rides at the speed that grade allows`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0)))
            assertEquals(0.0, rig.simulatedGrade, "before the start the road is flat: the rider spins up")
            rig.start()
            assertEquals(5.0, rig.simulatedGrade, "10% road at the default difficulty of 50%")
            rig.seconds(10)

            // The speed is the model's at the grade the trainer feels, not the flat one (30 km/h at 150 W, 33 at 200).
            val expected = metersPerSecond(5.0)
            assertEquals(expected * 3.6, rig.now.speedKmh, 1e-9)
            assertTrue(rig.now.speedKmh < 20.0, "a climb at 200 W is not 33 km/h: ${rig.now.speedKmh}")
            assertEquals(10 * expected, rig.now.distanceMeters, 1e-6)
            val route = assertNotNull(rig.now.route)
            assertEquals("route-climb", route.routeId)
            assertEquals(1000.0, route.lengthMeters)
            assertEquals(10 * expected, route.distanceMeters, 1e-6)
            assertEquals(5.0, route.gradePercent)
            assertFalse(route.finished)
        }

    @Test
    fun `the difficulty scales what the trainer feels and what the speed uses together`() =
        runTest {
            val easy = RouteRig(this, RideRoutes(difficultyPercent = 20, freeRide = climb(1000.0)))
            easy.start()
            easy.seconds(5)
            assertEquals(2.0, easy.simulatedGrade)
            assertEquals(metersPerSecond(2.0) * 3.6, easy.now.speedKmh, 1e-9)

            val flat = RouteRig(this, RideRoutes(difficultyPercent = 0, freeRide = climb(1000.0)))
            flat.start()
            flat.seconds(5)
            assertEquals(0.0, flat.simulatedGrade)
            assertEquals(metersPerSecond(0.0) * 3.6, flat.now.speedKmh, 1e-9)
        }

    @Test
    fun `the gear's offset is added on top for the trainer but does not change the road's speed`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0)))
            rig.start()
            rig.seconds(3)
            val before = rig.now.speedKmh
            repeat(2) { rig.engine.gearUp() }
            rig.settle()
            assertEquals(7.0, rig.simulatedGrade, "5% of the road and two gears of 1%")
            rig.seconds(1)
            assertEquals(
                before,
                rig.now.speedKmh,
                1e-9,
                "speed follows the power, which the rider changes with the gear",
            )
            assertEquals(5.0, assertNotNull(rig.now.route).gradePercent, "the route's own grade has no gear on it")
        }

    @Test
    fun `the total is clamped to the trainer's limit and a road steeper than that is held at it for the speed too`() =
        runTest {
            val wall = lineRoute(1000.0, elevation = { 100.0 + 0.4 * it }, id = "route-wall") // a 40% road
            val rig = RouteRig(this, RideRoutes(difficultyPercent = 100, freeRide = wall))
            rig.start()
            rig.seconds(5)
            assertEquals(15.0, rig.simulatedGrade, "the D500 simulates 15% at most")
            assertEquals(metersPerSecond(15.0) * 3.6, rig.now.speedKmh, 1e-9)
            repeat(6) { rig.engine.gearUp() }
            rig.settle()
            assertEquals(15.0, rig.simulatedGrade, "a harder gear does not pass the limit")
        }

    @Test
    fun `a descent is clamped the same way`() =
        runTest {
            val drop = lineRoute(1000.0, elevation = { 500.0 - 0.4 * it }, id = "route-drop")
            val rig = RouteRig(this, RideRoutes(difficultyPercent = 100, freeRide = drop))
            rig.start()
            rig.seconds(3)
            assertEquals(-15.0, rig.simulatedGrade)
            assertTrue(rig.now.speedKmh <= FlatPhysicsSpeedModel.MAX_SPEED_KMH)
            assertEquals(metersPerSecond(-15.0) * 3.6, rig.now.speedKmh, 1e-9)
        }

    @Test
    fun `at the end of the route the grade is 0 the ride goes on flat and the route does not loop`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(100.0)))
            rig.start()
            var seconds = 0
            while (rig.now.route?.finished == false && seconds < 200) {
                rig.second()
                seconds++
            }
            val route = assertNotNull(rig.now.route)
            assertTrue(route.finished, "the 100 m route ends")
            assertEquals(100.0, route.distanceMeters)
            assertEquals(0.0, route.gradePercent)
            assertEquals(0.0, rig.simulatedGrade, "the trainer is flat from the end on")
            // About 100 m at 5%: 24 s or so at 200 W.
            assertTrue(seconds in 20..30, "$seconds s")

            val distanceAtTheEnd = rig.now.distanceMeters
            rig.seconds(5)
            val flatSpeed = FlatPhysicsSpeedModel().speedKmh(POWER.toDouble())
            assertEquals(flatSpeed, rig.now.speedKmh, 1e-9, "flat again")
            assertEquals(distanceAtTheEnd + 5 * flatSpeed / 3.6, rig.now.distanceMeters, 1e-6)
            assertEquals(100.0, assertNotNull(rig.now.route).distanceMeters, "the route's distance stays at its length")
            assertTrue(assertNotNull(rig.now.route).finished)
        }

    @Test
    fun `a pause stops the road and a resume goes on from the same place`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0)))
            rig.start()
            rig.seconds(5)
            val along = assertNotNull(rig.now.route).distanceMeters
            rig.engine.pause()
            rig.settle()
            rig.seconds(5) // paused seconds are not ridden
            assertEquals(along, assertNotNull(rig.now.route).distanceMeters, 1e-9)
            rig.engine.resume()
            rig.settle()
            rig.seconds(1)
            assertTrue(assertNotNull(rig.now.route).distanceMeters > along)
        }

    @Test
    fun `the record has the route in its header and the distance in its samples`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0, id = "route-abc")))
            rig.start()
            rig.seconds(4)
            val header = rig.record.ride
            assertEquals("route-abc", header.routeId)
            assertEquals("Climb route-abc", header.routeName)
            assertNull(header.workoutId)
            assertFalse(header.wasVariantRidden)
            assertEquals(4 * metersPerSecond(5.0), rig.record.samples.last().distanceMeters, 1e-6)
        }

    @Test
    fun `a ride without a route is exactly as it was`() =
        runTest {
            val rig = RouteRig(this, RideRoutes.None)
            assertEquals(0.0, rig.simulatedGrade)
            rig.start()
            rig.seconds(5)
            assertNull(rig.now.route)
            assertNull(rig.record.ride.routeId)
            assertNull(rig.record.ride.routeName)
            assertNull(rig.record.ride.variantRidden)
            assertEquals(FlatPhysicsSpeedModel().speedKmh(POWER.toDouble()), rig.now.speedKmh, 1e-12)
            assertEquals(0.0, rig.simulatedGrade)
        }

    // ---- a route on a free step of a workout ----

    /** ERG 60 s, a free step on route-a for 120 s, ERG 60 s, a free step on route-b for 120 s, a free step with no route. */
    private val workout =
        Workout(
            1,
            "icu-9",
            "Ride out",
            steps =
                listOf(
                    SteadyStep(duration = 60, target = 0.5),
                    FreeStep(duration = 120, routeId = "route-a"),
                    SteadyStep(duration = 60, target = 0.5),
                    FreeStep(duration = 120, routeId = "route-b"),
                    FreeStep(duration = 60),
                ),
        )

    private fun RouteRig.pedalThroughTheWarmUp() = repeat(60) { second(power = trainer.targetPowerWatts ?: POWER) }

    @Test
    fun `a free step with a route rides it from its start and the steps around it are flat`() =
        runTest {
            val rig =
                RouteRig(
                    this,
                    RideRoutes(
                        byId = mapOf("route-a" to climb(2000.0, "route-a"), "route-b" to climb(2000.0, "route-b")),
                    ),
                    workout = workout,
                )
            rig.start()
            assertNull(rig.now.route, "an ERG step has no road")
            rig.pedalThroughTheWarmUp()
            assertEquals("Free ride", rig.now.workout?.step?.label)
            assertEquals(5.0, rig.simulatedGrade, "the free step is on route-a")
            assertEquals(0.0, assertNotNull(rig.now.route).distanceMeters, "it starts at the start of the route")
            rig.seconds(10)
            assertEquals("route-a", assertNotNull(rig.now.route).routeId)
            assertEquals(10 * metersPerSecond(5.0), assertNotNull(rig.now.route).distanceMeters, 1e-6)
        }

    @Test
    fun `the header names the first route of the workout and the next step starts its own route at 0`() =
        runTest {
            val rig =
                RouteRig(
                    this,
                    RideRoutes(
                        byId = mapOf("route-a" to climb(2000.0, "route-a"), "route-b" to climb(2000.0, "route-b")),
                    ),
                    workout = workout,
                )
            rig.start()
            assertEquals("route-a", rig.record.ride.routeId)
            assertEquals("icu-9", rig.record.ride.workoutId)
            rig.engine.nextInterval() // into the free step on route-a
            rig.settle()
            rig.seconds(10)
            val onA = assertNotNull(rig.now.route).distanceMeters
            assertTrue(onA > 0.0)
            rig.engine.nextInterval() // the ERG step
            rig.engine.nextInterval() // the free step on route-b
            rig.settle()
            assertEquals("route-b", assertNotNull(rig.now.route).routeId)
            assertEquals(0.0, assertNotNull(rig.now.route).distanceMeters, "route-b starts at its start")
        }

    @Test
    fun `a route the tablet has not downloaded is a flat step and the header does not claim it`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(byId = mapOf("route-b" to climb(2000.0, "route-b"))), workout = workout)
            rig.start()
            assertEquals(
                "route-b",
                rig.record.ride.routeId,
                "the first route that fed the trainer, not the first one named",
            )
            rig.engine.nextInterval()
            rig.settle()
            assertNull(rig.now.route, "route-a is not here: the step is the flat free ride")
            assertEquals(0.0, rig.simulatedGrade)
            rig.seconds(5)
            assertEquals(FlatPhysicsSpeedModel().speedKmh(POWER.toDouble()), rig.now.speedKmh, 1e-9)

            val none = RouteRig(this, RideRoutes.None, workout = workout)
            none.start()
            assertNull(none.record.ride.routeId)
        }

    @Test
    fun `a free step with no route stays flat even when a route was chosen for free rides`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0)), workout = workout)
            rig.start()
            repeat(4) { rig.engine.nextInterval() }
            rig.settle()
            assertEquals("Free ride", rig.now.workout?.step?.label)
            assertNull(rig.now.route)
            assertEquals(0.0, rig.simulatedGrade)
            assertNull(rig.record.ride.routeId, "the chosen route is for a ride with no workout")
        }

    @Test
    fun `the variant's flag is in the header only for a ride of a workout`() =
        runTest {
            val rig = RouteRig(this, RideRoutes.None, workout = workout, variantRidden = true)
            rig.start()
            assertEquals(true, rig.record.ride.variantRidden)
            assertEquals("icu-9", rig.record.ride.workoutId)

            val free = RouteRig(this, RideRoutes.None, variantRidden = true)
            free.start()
            assertNull(free.record.ride.variantRidden, "a ride with no workout has no variant to ride")
        }

    @Test
    fun `the engine never leaves the trainer on a hill after the ride`() =
        runTest {
            val rig = RouteRig(this, RideRoutes(freeRide = climb(1000.0)))
            rig.start()
            rig.seconds(5)
            assertEquals(5.0, rig.simulatedGrade)
            rig.engine.finish()
            rig.settle()
            assertEquals(0.0, rig.simulatedGrade, "the trainer is flat at the neutral gear again")
            assertNull(rig.now.route)
        }

    @Test
    fun `a ride on a route and a ride on the flat differ only by the grade`() =
        runTest {
            val route = testRoute(listOf(RoutePoint(0.0, 10.0), RoutePoint(1000.0, 10.0))) // a flat profile
            val onRoute = RouteRig(this, RideRoutes(freeRide = route))
            val flat = RouteRig(this, RideRoutes.None)
            onRoute.start()
            flat.start()
            repeat(20) {
                // Both engines run on this test's clock: give each its reading, then let one second pass for both.
                onRoute.trainer.emit(power = POWER, cadence = CADENCE)
                flat.trainer.emit(power = POWER, cadence = CADENCE)
                advanceSeconds(1)
            }
            assertEquals(flat.now.distanceMeters, onRoute.now.distanceMeters, 1e-9)
            assertEquals(flat.now.speedKmh, onRoute.now.speedKmh, 1e-12)
        }
}
