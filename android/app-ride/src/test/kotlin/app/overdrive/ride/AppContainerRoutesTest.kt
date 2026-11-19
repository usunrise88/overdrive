package app.overdrive.ride

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedVariant
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.core.route.Route
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.RouteSource
import app.overdrive.core.route.RouteStatus
import app.overdrive.core.route.RoutesApiJson
import app.overdrive.core.route.RoutesResponse
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.RecoveryFact
import app.overdrive.core.wellness.RecoveryMetric
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryRule
import app.overdrive.core.wellness.RecoveryState
import app.overdrive.core.wellness.RecoveryUnit
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.routes.RecoveryLine
import app.overdrive.ride.routes.RouteChoiceUi
import app.overdrive.ride.settings.DemoSpeed
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.ride.settings.SecretText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The routes and the recovery state in the real container (spec §11.4, §11.5, ADR 0037), against a local stand-in of the service: nothing is
 * asked until an address and token are set, the list and the routes the plan names are fetched and kept on disk, a route chosen for a free
 * ride is fetched and ridden, a restart with no service still has them, a route that cannot be had is said so and the ride is flat, and the
 * recovery state is asked for only when a workout of today has an easier version. Real threads, files and sockets on loopback; every
 * coordinate is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerRoutesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val extra = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = LocalService()
    private val today = "2026-11-19"

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        worldScope.cancel()
        service.close()
        directory.deleteRecursively()
        extra.deleteRecursively()
    }

    private fun container(name: String): AppContainer =
        AppContainer(
            context,
            directory,
            settingsName = name,
            devicesName = "devices-$name",
            bleLogDirectory = File(extra, "ble-$name"),
            bleStack = { _, _ -> FakeBleStack(FakeBle(worldScope, nanoClock = System::nanoTime)) },
            outboxFile = File(extra, "sync/outbox.log"),
            planFile = File(extra, "plan/workouts"),
            routesDirectory = File(extra, "routes"),
            recoveryFile = File(extra, "cache/recovery"),
            today = { today },
            syncSettleMillis = 50,
        ).also { containers += it }

    private fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun AppContainer.configure() {
        updateSettings {
            it.copy(
                syncUrl = service.baseUrl,
                syncToken = SecretText(service.token),
                deviceSource = DeviceSource.Simulated,
                demoSpeed = DemoSpeed.Faster,
            )
        }
    }

    /** A 3 km road due north of an invented point, a point every 20 m, 1 m up each. */
    private fun road(
        id: String,
        name: String,
        map: Boolean = true,
    ): Route =
        Route(
            id,
            name,
            if (map) RouteSource.GPX else RouteSource.PROFILE,
            3000,
            150,
            map,
            "2026-11-19T09:00:00Z",
            (0..150).map {
                if (map) {
                    RoutePoint(it * 20.0, 100.0 + it, 45.0 + it * 20.0 * 9e-6, 7.0)
                } else {
                    RoutePoint(
                        it * 20.0,
                        100.0 + it,
                    )
                }
            },
        )

    private val north = road("route-north", "North road")
    private val hill = road("route-hill", "Rolling hill", map = false)

    private fun serveRoutes(vararg routes: Route) {
        service.routes =
            RoutesApiJson.encodeRoutes(
                RoutesResponse("2026-11-19T10:00:00Z", routes.map { it.summary }.sortedBy { it.id }),
            )
        for (route in routes) service.routePoints[route.id] = RoutesApiJson.encodeRoute(route)
    }

    private fun servePlan(vararg workouts: PlannedWorkout) {
        service.plan =
            PlanApiJson.encodeWorkoutsResponse(
                WorkoutsResponse("2026-11-19", "2026-12-02", "2026-11-19T06:30:00Z", false, true, workouts.toList()),
            )
        service.planStatus = 200
    }

    private fun rideOut(variant: Boolean = false): PlannedWorkout {
        val workout =
            Workout(
                1,
                "icu-7001",
                "Ride out",
                null,
                listOf(SteadyStep(duration = 60, target = 0.5), FreeStep(duration = 1200, routeId = "route-north")),
            )
        val easier =
            if (variant) {
                PlannedVariant(
                    listOf(FreeStep(duration = 600, routeId = "route-hill")),
                    "Shorter.",
                    "0123456789abcdef",
                )
            } else {
                null
            }
        return PlannedWorkout("icu-7001", today, "Ride out", PlanSource.INTERVALS_ICU, workout, easier)
    }

    private fun serveRecovery(state: RecoveryState) {
        val fact =
            RecoveryFact(
                RecoveryMetric.RESTING_HR,
                "Resting heart rate",
                59.0,
                RecoveryUnit.BPM,
                49.4,
                55.6,
                Deviation.ABOVE,
                2,
            )
        service.recovery =
            WellnessApiJson.encodeRecoveryResponse(
                RecoveryResponse(
                    today,
                    state,
                    "Resting heart rate is up.",
                    "Keep the hard parts short.",
                    RecoveryRule.C1,
                    31,
                    14,
                    "${today}T07:40:00Z",
                    false,
                    listOf(fact),
                ),
            )
    }

    private fun cachedPoints(id: String) = File(extra, "routes/points/$id")

    // ---- the list ----

    @Test
    fun nothingIsAskedUntilAnAddressAndATokenAreSet() {
        serveRoutes(north)
        val c = container("routes-unset")
        c.planScreenOpened()
        c.refreshPlan()
        c.chooseRoute("route-north")
        Thread.sleep(300)
        assertTrue(service.routeRequests.isEmpty())
        assertTrue(c.routes.value.routes.isEmpty())
        assertNull("nothing to choose from: no row", RouteChoiceUi.of(c.routes.value, null))
    }

    @Test
    fun theListIsAskedForWithTheTokenShownAndKeptOnDisk() {
        serveRoutes(north, hill)
        val c = container("routes-list")
        c.configure()
        await("the list is there") { c.routes.value.routes.map { it.id } == listOf("route-hill", "route-north") }
        val request = service.routeRequests.first { it.path == "/v1/routes" }
        assertEquals("GET", request.method)
        assertEquals("Bearer ${service.token}", request.authorization)
        await("kept on disk") { File(extra, "routes/list").isFile }
        val choice = checkNotNull(RouteChoiceUi.of(c.routes.value, null))
        assertEquals(listOf("Flat road", "Rolling hill", "North road"), choice.options.map { it.title })
        assertEquals("3.0 km · 150 m up · profile only", choice.options[1].detail)
        assertEquals("3.0 km · 150 m up · has a map", choice.options[2].detail)
    }

    // ---- a route chosen for a free ride ----

    @Test
    fun aRouteChosenForAFreeRideIsFetchedKeptAndRiddenAndTheRecordSaysWhich() {
        serveRoutes(north, hill)
        val c = container("routes-choose")
        c.configure()
        await("the list is there") { c.routes.value.routes.size == 2 }
        await("the first ride is ready") { c.host.snapshots.value.state == RideState.READY }
        c.host.select(RideChoice.Free)

        c.chooseRoute("route-north")
        await("the points are on the tablet") { c.routes.value.statusOf("route-north") == RouteStatus.READY }
        assertTrue("and on disk", cachedPoints("route-north").isFile)
        assertTrue(
            "only the chosen route was fetched",
            service.routeRequests.none { it.path == "/v1/routes/route-hill" },
        )
        await("the ready ride follows the route") {
            c.host.snapshots.value.route
                ?.routeId == "route-north"
        }
        assertEquals("3.0 km · 150 m up · has a map", RouteChoiceUi.of(c.routes.value, "route-north")?.detail)

        await("ready") { c.host.snapshots.value.state == RideState.READY }
        c.host.actions.start()
        await("riding on the route") {
            (
                c.host.snapshots.value.route
                    ?.distanceMeters ?: 0.0
            ) > 60.0
        }
        c.host.actions.finish()
        await("saved") { directory.list().orEmpty().any { it.endsWith(".json") } }
        val record =
            RideRecordJson.decode(
                File(directory, directory.list()!!.first { it.endsWith(".json") }).readText(),
            )
        assertEquals("route-north", record.ride.routeId)
        assertEquals("North road", record.ride.routeName)
    }

    @Test
    fun aRouteThatCannotBeHadIsSaidSoAndTheRideIsFlat() {
        serveRoutes(north)
        service.routePoints.clear() // the list has it, the points are gone: a 404
        val c = container("routes-missing")
        c.configure()
        await("the list is there") { c.routes.value.routes.isNotEmpty() }
        c.host.select(RideChoice.Free)
        c.chooseRoute("route-north")
        await("the failure is known") { c.routes.value.statusOf("route-north") == RouteStatus.FAILED }
        assertEquals("Route not downloaded yet", RouteChoiceUi.of(c.routes.value, "route-north")?.detail)
        await("ready") { c.host.snapshots.value.state == RideState.READY }
        assertNull("the ride is flat", c.host.snapshots.value.route)
        assertEquals("route-north", c.host.freeRoute.value)

        // The points arrive later (the rider pressed refresh, or the service came back): the ready ride follows the route.
        service.routePoints["route-north"] = RoutesApiJson.encodeRoute(north)
        c.chooseRoute("route-north")
        await("now it is on the tablet") { c.routes.value.statusOf("route-north") == RouteStatus.READY }
        await("and the ready ride follows it") {
            c.host.snapshots.value.route
                ?.routeId == "route-north"
        }
    }

    // ---- what the plan names ----

    @Test
    fun theRoutesTheNextDaysPlannedWorkoutsNameAreFetchedAheadSoARideNeedsNoNetwork() {
        serveRoutes(north, hill)
        servePlan(rideOut(variant = true))
        val c = container("routes-plan")
        c.configure()
        await("the plan's routes are on the tablet") {
            c.routes.value.statusOf("route-north") == RouteStatus.READY &&
                c.routes.value.statusOf("route-hill") == RouteStatus.READY
        }
        val ui =
            PlanUi.of(
                c.plan.state.value,
                emptyList(),
                today,
                0L,
                java.time.ZoneOffset.UTC,
                Locale.ENGLISH,
                routes = c.routes.value,
            )
        val row = ui.today.single()
        assertTrue(row.subtitle, row.subtitle.endsWith("Route: North road"))
        assertTrue(checkNotNull(row.variant).subtitle.endsWith("Route: Rolling hill"))
        assertTrue(cachedPoints("route-north").isFile && cachedPoints("route-hill").isFile)
    }

    @Test
    fun theRoutesAndThePlanSurviveARestartWithNoService() {
        serveRoutes(north)
        servePlan(rideOut())
        val first = container("routes-first")
        first.configure()
        await("the route is on the tablet") { first.routes.value.statusOf("route-north") == RouteStatus.READY }
        await("kept on disk") { File(extra, "routes/list").isFile && cachedPoints("route-north").isFile }
        await("the settings are on disk") { File(context.filesDir, "datastore/routes-first.preferences_pb").isFile }
        first.close()
        containers.remove(first)
        File(context.filesDir, "datastore/routes-first.preferences_pb")
            .copyTo(File(context.filesDir, "datastore/routes-second.preferences_pb"), overwrite = true)

        service.down = true
        val second = container("routes-second")
        await("the cached list is shown") { second.routes.value.routes.map { it.id } == listOf("route-north") }
        await("and the cached points are loaded") { second.routes.value.statusOf("route-north") == RouteStatus.READY }
        assertEquals(NORTH_FACTS, RouteChoiceUi.of(second.routes.value, "route-north")?.detail)
        await("ready") { second.host.snapshots.value.state == RideState.READY }
        second.host.select(RideChoice.Free)
        second.chooseRoute("route-north")
        await("the ride follows it with no service") {
            second.host.snapshots.value.route
                ?.routeId == "route-north"
        }
    }

    // ---- the recovery state ----

    @Test
    fun theRecoveryStateIsAskedForOnlyWhenAWorkoutOfTodayHasAnEasierVersion() {
        serveRoutes(north, hill)
        serveRecovery(RecoveryState.CAUTION)
        servePlan(rideOut(variant = false))
        val plain = container("recovery-none")
        plain.configure()
        await("the plan is there") {
            plain.plan.state.value.workouts
                .isNotEmpty()
        }
        plain.planScreenOpened()
        Thread.sleep(400)
        assertTrue("a plan with no variant never asks", service.routeRequests.none { it.path == "/v1/recovery" })
        assertNull(RecoveryLine.of(plain.recovery.state.value, today))
        plain.close()
        containers.remove(plain)

        servePlan(rideOut(variant = true))
        val withVariant = container("recovery-asked")
        withVariant.configure()
        await("the recovery state is shown") {
            RecoveryLine.of(withVariant.recovery.state.value, today) ==
                "Recovery: caution"
        }
        assertTrue(service.routeRequests.any { it.path == "/v1/recovery" })
        assertNotNull(withVariant.recovery.state.value.value)
        await("kept on disk") { File(extra, "cache/recovery").isFile }
    }

    @Test
    fun aServiceWithNoRecoveryRouteIsNothingToSayAndNoError() {
        serveRoutes(north, hill)
        servePlan(rideOut(variant = true))
        val c = container("recovery-404")
        c.configure()
        await("the plan is there") {
            c.plan.state.value.workouts
                .isNotEmpty()
        }
        await("recovery was asked") { service.routeRequests.any { it.path == "/v1/recovery" } }
        assertNull(RecoveryLine.of(c.recovery.state.value, today))
        assertFalse(c.plan.state.value.stale)
    }

    private companion object {
        const val NORTH_FACTS = "3.0 km · 150 m up · has a map"
    }
}
