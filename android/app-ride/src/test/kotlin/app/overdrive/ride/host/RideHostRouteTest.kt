package app.overdrive.ride.host

import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.core.route.Route
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.RouteSource
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.rides.FitFileCheck
import app.overdrive.ride.rides.FitRecords
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DemoSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A route and the easier variant through the real host, engine, simulated devices and library on their real threads, at 60 times speed
 * (spec §11.4, §11.5, ADR 0037): what the rider chooses on the Ready screen is what is ridden, what the record says and what the FIT file
 * holds. Every coordinate and elevation here is invented.
 */
class RideHostRouteTest {
    private val directory = RideFixtures.directory()
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var host: RideHost? = null

    @After
    fun cleanUp() {
        host?.close()
        diskScope.cancel()
        directory.deleteRecursively()
    }

    /** 5 km due north of an invented point, a 5% road: a point every 20 m, 1 m up each. */
    private val road: Route =
        Route(
            id = "route-test",
            name = "Test road",
            source = RouteSource.GPX,
            distanceMeters = 5000,
            ascentMeters = 250,
            hasCoordinates = true,
            createdAt = "2026-11-19T09:00:00Z",
            points = (0..250).map { RoutePoint(it * 20.0, 100.0 + it, lat = 45.0 + it * 20.0 * 9e-6, lon = 7.0) },
        )

    /** The same road with no coordinates: a profile, which draws no map. */
    private val profile: Route =
        road.copy(
            id = "route-profile",
            name = "Profile",
            source = RouteSource.PROFILE,
            hasCoordinates = false,
            points = road.points.map { RoutePoint(it.d, it.e) },
        )

    private val workout =
        WorkoutImport.fromJson(
            """{ "schema": 1, "id": "icu-9", "name": "Easy spin", "steps": [ { "type": "free", "duration": 20 } ] }""",
        )

    private fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun host(
        choice: RideChoice = RideChoice.Free,
        difficulty: Int = 50,
        downloaded: Map<String, Route> = mapOf(road.id to road, profile.id to profile),
    ): RideHost {
        val library = RideLibrary(RideStore(directory), diskScope)
        return RideHost(
            SimulatedRideSessionFactory(),
            MutableStateFlow(AppSettings(ftp = 250, demoSpeed = DemoSpeed.Faster, routeDifficultyPercent = difficulty)),
            choice,
            persistence = library,
            downloadedRoutes = { downloaded },
        ).also { host = it }
    }

    private fun RideHost.ready() = await("the ride is ready") { snapshots.value.state == RideState.READY }

    /** Rides until [condition] and finishes; returns the stored record and the FIT bytes. */
    private fun RideHost.rideAndSave(condition: () -> Boolean): Pair<RideRecord, ByteArray> {
        ready()
        actions.start()
        try {
            await("the ride went somewhere", condition = condition)
        } catch (e: AssertionError) {
            throw AssertionError("${e.message} (the ride was ${snapshots.value})", e)
        }
        actions.finish()
        await("the ride is saved") { ui.value.finished?.save is SaveState.Saved }
        val saved = (checkNotNull(ui.value.finished).save as SaveState.Saved).ride
        val fit = File(directory, "${saved.id}.fit").readBytes()
        FitFileCheck.verify(fit)
        return RideRecordJson.decode(File(directory, "${saved.id}.json").readText()) to fit
    }

    // ---- a free ride on a route ----

    @Test
    fun aFreeRideOnARouteFollowsItAndTheRecordAndTheFitFileSayWhichRoute() {
        val host = host()
        host.ready()
        host.selectFreeRoute("route-test")
        await("the ride is built on the route") {
            host.freeRoute.value == "route-test" &&
                host.snapshots.value.route != null
        }
        assertEquals("route-test", host.snapshots.value.route?.routeId)

        val (record, fit) = host.rideAndSave { (host.snapshots.value.route?.distanceMeters ?: 0.0) > 200.0 }
        assertEquals("route-test", record.ride.routeId)
        assertEquals("Test road", record.ride.routeName)
        assertNull(record.ride.workoutId)
        assertNull(record.ride.variantRidden)

        val records = FitRecords(fit)
        assertTrue("positions on a route with coordinates", records.hasPositions)
        assertTrue("and the altitude", records.hasAltitude)
        val first = records.records.first()
        // The first second is 0 to a few metres along the road: about (45, 7) in semicircles, about 100 m up.
        assertEquals(536_870_912.0, checkNotNull(first[0]).toDouble(), 5_000.0)
        assertEquals(83_513_253.0, checkNotNull(first[1]).toDouble(), 1.0)
        assertEquals(3_000.0, checkNotNull(first[2]).toDouble(), 100.0)
        val last = records.records.last()
        assertTrue("the ride went north", checkNotNull(last[0]) > checkNotNull(first[0]))
    }

    @Test
    fun aProfileWritesTheAltitudeAndNoPositionSoNoMap() {
        val host = host()
        host.ready()
        host.selectFreeRoute("route-profile")
        await("the ride is built on the profile") { host.snapshots.value.route?.routeId == "route-profile" }
        val (record, fit) = host.rideAndSave { (host.snapshots.value.route?.distanceMeters ?: 0.0) > 100.0 }
        assertEquals("route-profile", record.ride.routeId)
        val records = FitRecords(fit)
        assertTrue(records.hasAltitude)
        assertFalse(records.hasPositions)
    }

    @Test
    fun theDifficultyIsASettingOfTheRide() {
        val flat = host(difficulty = 0)
        flat.ready()
        flat.selectFreeRoute("route-test")
        await("built on the route") { flat.snapshots.value.route != null }
        flat.actions.start()
        await("riding") { flat.snapshots.value.route?.distanceMeters ?: 0.0 > 100.0 }
        assertEquals("a difficulty of 0 is the flat", 0.0, flat.snapshots.value.route?.gradePercent ?: -1.0, 1e-9)
    }

    @Test
    fun aRouteThatIsNotDownloadedYetIsFlatAndTheFileIsTheOneEveryRideHad() {
        val host = host(downloaded = emptyMap())
        host.ready()
        val before = host.ui.value.rideNumber
        host.selectFreeRoute("route-test")
        await("the choice is kept and the ride built again") {
            host.freeRoute.value == "route-test" && host.ui.value.rideNumber > before &&
                host.snapshots.value.state == RideState.READY
        }
        assertNull("no points, no route", host.snapshots.value.route)
        val (record, fit) = host.rideAndSave { host.snapshots.value.distanceMeters > 100.0 }
        assertNull(record.ride.routeId)
        assertNull(record.ride.routeName)
        val records = FitRecords(fit)
        assertEquals(listOf(253, 3, 4, 5, 6, 7, 73), records.fieldNumbers)
        assertFalse(records.hasPositions || records.hasAltitude)
    }

    @Test
    fun theChosenRouteBelongsToTheFreeRideAndSurvivesAnotherChoiceAndBack() {
        val host = host()
        host.ready()
        host.selectFreeRoute("route-test")
        await("built on the route") { host.snapshots.value.route != null }
        host.select(RideChoice.Planned(workout))
        await("the workout is chosen") { host.choice.value is RideChoice.Planned }
        assertEquals("route-test", host.freeRoute.value)
        await("a workout ride with no route steps is flat") {
            host.ui.value.snapshot.workout != null &&
                host.snapshots.value.route == null
        }
        host.select(RideChoice.Free)
        await("back on the route") { host.snapshots.value.route?.routeId == "route-test" }
        host.selectFreeRoute(null)
        await("the flat road again") {
            host.snapshots.value.state == RideState.READY &&
                host.snapshots.value.route == null
        }
        assertNull(host.freeRoute.value)
    }

    @Test
    fun theRouteIsNotChangedWhileARideIsLive() {
        val host = host()
        host.ready()
        host.actions.start()
        await("riding") { host.snapshots.value.state == RideState.RIDING }
        host.selectFreeRoute("route-test")
        Thread.sleep(200)
        assertNull("a live ride is never rebuilt", host.freeRoute.value)
        assertNull(host.snapshots.value.route)
    }

    // ---- the easier variant ----

    @Test
    fun theEasierVersionIsRiddenAsThePlannedWorkoutWithTheVariantFlagInTheRecord() {
        val host = host(RideChoice.Planned(workout, variant = true, variantNote = "Shorter."))
        val (record, _) = host.rideAndSave { host.snapshots.value.elapsedSeconds >= 5 }
        assertEquals("icu-9", record.ride.workoutId)
        assertEquals(true, record.ride.variantRidden)
        assertNull(record.ride.routeId)
    }

    @Test
    fun thePlannedVersionLeavesTheFlagOutOfTheRecord() {
        val host = host(RideChoice.Planned(workout))
        val (record, _) = host.rideAndSave { host.snapshots.value.elapsedSeconds >= 5 }
        assertEquals("icu-9", record.ride.workoutId)
        assertNull(record.ride.variantRidden)
        val written = File(directory, "${record.ride.id}.json").readText()
        assertFalse("variantRidden" in written || "routeId" in written || "routeName" in written)
        assertNotNull(record.samples.firstOrNull())
    }
}
