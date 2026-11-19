package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The route and the easier variant in the ride's header (spec §8.2, §11.4, §11.5): what is written, when, and what is refused. */
class RideHeaderRouteTest {
    private fun record(header: RideHeader) =
        RideRecordBuilder.recover(header, listOf(RideSample(1, power = 150, speedKmh = 25.0, distanceMeters = 6.9)))

    @Test
    fun `a ride without a route or a variant is written exactly as before`() {
        val json = RideRecordJson.encode(record(RideHeader("t", 1000, ftp = 200)))
        assertTrue(
            json.startsWith(
                """{"schema":1,"ride":{"id":"t","startEpochMillis":1000,"ftp":200,"devices":[]},"samples":""",
            ),
            json,
        )
        assertFalse("route" in json || "variant" in json, json)
    }

    @Test
    fun `the route and the variant are written after the devices, in this order`() {
        val header =
            RideHeader(
                "t",
                1000,
                workoutId = "icu-9",
                ftp = 200,
                routeId = "route-abc",
                routeName = "North road",
                variantRidden = true,
            )
        val json = RideRecordJson.encode(record(header))
        assertTrue(
            json.contains(
                """"ride":{"id":"t","startEpochMillis":1000,"workoutId":"icu-9","ftp":200,"devices":[],""" +
                    """"routeId":"route-abc","routeName":"North road","variantRidden":true}""",
            ),
            json,
        )
        assertEquals(header, RideRecordJson.decode(json).ride)
    }

    @Test
    fun `a variant ridden is only ever written as true`() {
        val ridden = RideRecordJson.encode(record(RideHeader("t", 1, workoutId = "w", ftp = 200, variantRidden = true)))
        assertTrue("\"variantRidden\":true" in ridden)
        val planned = RideRecordJson.encode(record(RideHeader("t", 1, workoutId = "w", ftp = 200)))
        assertFalse("variantRidden" in planned)
        // A producer that writes false or null says the same as one that writes nothing.
        val explicit = ridden.replace("\"variantRidden\":true", "\"variantRidden\":false")
        assertEquals(false, RideRecordJson.decode(explicit).ride.variantRidden)
        assertFalse(RideRecordJson.decode(explicit).ride.wasVariantRidden)
        val nulled = ridden.replace("\"variantRidden\":true", "\"variantRidden\":null")
        assertNull(RideRecordJson.decode(nulled).ride.variantRidden)
    }

    @Test
    fun `the header refuses what the schema and its rules refuse`() {
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, routeId = "not/an/id") }
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, routeId = "") }
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, routeId = "x".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, routeName = "A name without an id") }
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, routeId = "route-a", routeName = "") }
        assertFailsWith<IllegalArgumentException> {
            RideHeader("t", 1, ftp = 200, routeId = "route-a", routeName = "n".repeat(101))
        }
        assertFailsWith<IllegalArgumentException> { RideHeader("t", 1, ftp = 200, variantRidden = true) }
        // The edges are valid: 64 characters of id, 100 of name, no name at all, a variant of a workout.
        RideHeader("t", 1, ftp = 200, routeId = "r".repeat(64), routeName = "n".repeat(100))
        RideHeader("t", 1, ftp = 200, routeId = "route-a")
        RideHeader("t", 1, workoutId = "w", ftp = 200, variantRidden = false)
    }

    @Test
    fun `the crash-safe log keeps the route and the variant in its header line`() {
        val header =
            RideHeader(
                "g",
                1_794_996_000_000L,
                workoutId = "icu-9",
                ftp = 250,
                routeId = "route-abc",
                routeName = "North road",
                variantRidden = true,
            )
        val store = InMemoryByteStore()
        val recorder = RideLogRecorder(store)
        recorder.onRideStarted(header)
        recorder.onSample(RideSample(1, power = 180, speedKmh = 31.2, distanceMeters = 8.7))
        recorder.onRideFinished(1_794_996_001_000L)

        val first = store.readAll().decodeToString().lineSequence().first()
        assertTrue(first.endsWith(""""routeId":"route-abc","routeName":"North road","variantRidden":true}}"""), first)
        val recovered = assertNotNull(RideLog.recover(store))
        assertEquals(header, recovered.header)
        assertEquals(header.copy(endEpochMillis = 1_794_996_001_000L), recovered.toRecord().ride)
        assertTrue(recovered.toRecord().ride.wasVariantRidden)
    }
}
