package app.overdrive.data.rides

import app.overdrive.core.fit.FitOptions
import app.overdrive.core.fit.FitRoutes
import app.overdrive.core.fit.FitWriter
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordBuilder
import app.overdrive.core.route.Route
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.RouteSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FIT file a stored ride gets (spec §4.8, §11.4): the places of the route it followed, or the file every ride had. All places are invented. */
class RideStoreRoutesTest {
    private val directory = TestRides.tempDirectory()
    private val store = RideStore(directory)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val road =
        Route(
            "route-a",
            "A road",
            RouteSource.GPX,
            4000,
            200,
            true,
            "2026-11-19T09:00:00Z",
            (0..200).map { RoutePoint(it * 20.0, 100.0 + it, 45.0 + it * 20.0 * 9e-6, 7.0) },
        )

    private fun record(onRoute: Boolean): RideRecord =
        RideRecordBuilder.recover(
            TestRides.header().let { if (onRoute) it.copy(routeId = road.id, routeName = road.name) else it },
            TestRides.samples(90),
            endEpochMillis = TestRides.START + 90_000L,
        )

    private fun fit(id: String = "ride-1") = java.io.File(directory, "$id.fit").readBytes()

    private fun <T> StoreResult<T>.ok(): T =
        when (this) {
            is StoreResult.Ok -> value
            is StoreResult.Failed -> throw AssertionError("Expected success, got $cause")
        }

    @Test
    fun theFitFileOfARideOnARouteHasItsPlaces() {
        val record = record(onRoute = true)
        store.finalize(record, "A road ride", 3600, FitRoutes { road }).ok()
        val expected = FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(record, FitRoutes { road })
        assertArrayEquals(expected, fit())
        assertTrue(
            "longer than the same ride on the flat",
            fit().size > FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(record).size,
        )
    }

    @Test
    fun aRideWithNoRouteGetsTheFileItAlwaysHad() {
        val record = record(onRoute = false)
        store.finalize(record, "Free ride", 3600).ok()
        assertArrayEquals(FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(record), fit())
        store.finalize(record, "Free ride", 3600, FitRoutes.None).ok()
        assertArrayEquals(FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(record), fit())
    }

    @Test
    fun aRideCompletedFromItsLogAsksForItsRoutesOnceItIsRead() {
        val record = TestRides.writeRide(store, seconds = 90, finished = true)
        val asked = mutableListOf<RideRecord>()
        val routes: (RideRecord) -> FitRoutes = { found ->
            asked += found
            FitRoutes { road }
        }
        store.finalizeStored("ride-1", { 3600 }, routes).ok()
        assertEquals("asked once, with the ride that was read", 1, asked.size)
        assertEquals(record.ride.id, asked.single().ride.id)
        assertEquals(90, asked.single().samples.size)
        assertFalse(fit().isEmpty())
        val flat = FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(asked.single())
        assertTrue("and the file has the places", fit().size > flat.size)
    }

    @Test
    fun aRideCompletedWithNoRoutesIsTheFlatFile() {
        TestRides.writeRide(store, seconds = 90, finished = true)
        store.finalizeStored("ride-1") { 3600 }.ok()
        val read = RideRecordBuilderCheck.recordOf(store, "ride-1")
        assertArrayEquals(FitWriter(FitOptions(utcOffsetSeconds = 3600)).write(read), fit())
    }
}

/** Reads the record a store kept, for a test that compares the FIT file with what the writer makes of it. */
private object RideRecordBuilderCheck {
    fun recordOf(
        store: RideStore,
        id: String,
    ): RideRecord =
        app.overdrive.core.record.RideRecordJson
            .decode(java.io.File(store.fitFile(id).parentFile, "$id.json").readText())
}
