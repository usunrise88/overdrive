package app.overdrive.core.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model and [RideRecordJson] must agree with `contracts/schema/ride-record.schema.json`
 * on every shared fixture under `contracts/fixtures/rides`. The TypeScript side runs the same fixtures against the schema.
 *
 * Four of the valid fixtures are generated from records built here, and the tests below fail if the file and the generator
 * disagree. To regenerate them after changing a generator on purpose, run once with `OVERDRIVE_WRITE_FIXTURES=1`:
 * `cd core && OVERDRIVE_WRITE_FIXTURES=1 ./gradlew jvmTest --tests '*RideRecordContractTest*' --rerun`.
 */
class RideRecordContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))
    private val fixtures = File(contracts, "fixtures/rides")

    /** The record fixtures, and those of stage A2b-1 under `fixtures/routes/ride-record` (`routeId`, `routeName`, `variantRidden`). */
    private fun fixtures(kind: String): List<File> =
        listOf(File(fixtures, kind), File(contracts, "fixtures/routes/ride-record/$kind"))
            .flatMap { dir -> dir.listFiles { f -> f.extension == "json" }.orEmpty().toList() }
            .sortedBy { it.name }

    /** The valid fixtures that are generated, by file name. */
    private fun generated(): Map<String, RideRecord> {
        val header =
            RideHeader(
                "free-ride-1",
                SYNTHETIC_START_MILLIS,
                workoutId = null,
                ftp = 220,
                devices = listOf("Test trainer"),
            )
        val samples =
            (1..3).map {
                RideSample(
                    timeOffsetSeconds = it,
                    power = 145 + 5 * it,
                    cadence = 84 + it,
                    speedKmh = 24.0 + it,
                    distanceMeters = 7.0 * it,
                    gear = 12,
                )
            }
        return mapOf(
            "minimal-free-ride.json" to
                RideRecordBuilder.recover(header, samples, endEpochMillis = SYNTHETIC_START_MILLIS + 3_000),
            "empty-ride.json" to
                RideRecordBuilder.recover(RideHeader("not-started", SYNTHETIC_START_MILLIS, ftp = 200), emptyList()),
            "fit-golden-3s.json" to FitGoldenRide.record(),
            "synthetic-5min.json" to SyntheticRide.record(),
        )
    }

    /** One top-level key per line and one array element per line, so a fixture diffs well. */
    private fun layout(record: RideRecord): String {
        val document = Json.parseToJsonElement(RideRecordJson.encode(record)).jsonObject
        val parts =
            document.entries.map { (key, value) ->
                when {
                    value is JsonArray && value.isNotEmpty() -> {
                        "  \"$key\": [\n" + value.joinToString(",\n") { "    $it" } + "\n  ]"
                    }

                    else -> {
                        "  \"$key\": $value"
                    }
                }
            }
        return "{\n" + parts.joinToString(",\n") + "\n}\n"
    }

    init {
        if (System.getenv("OVERDRIVE_WRITE_FIXTURES") != null) {
            File(fixtures, "valid").mkdirs()
            for ((name, record) in generated()) File(fixtures, "valid/$name").writeText(layout(record))
        }
    }

    @Test
    fun `finds fixtures`() {
        assertTrue(fixtures("valid").size >= 5, "too few valid fixtures under $fixtures")
        assertTrue(fixtures("invalid").size >= 10, "too few invalid fixtures under $fixtures")
    }

    @Test
    fun `decodes every valid fixture and round-trips it`() {
        for (file in fixtures("valid")) {
            val record = RideRecordJson.decode(file.readText())
            assertEquals(record, RideRecordJson.decode(RideRecordJson.encode(record)), file.name)
            // The writer's own output is stable: encode, decode, encode again gives the same text.
            assertEquals(
                RideRecordJson.encode(record),
                RideRecordJson.encode(RideRecordJson.decode(RideRecordJson.encode(record))),
            )
        }
    }

    @Test
    fun `rejects every invalid fixture`() {
        for (file in fixtures("invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { RideRecordJson.decode(file.readText()) }
        }
    }

    @Test
    fun `the generated fixtures are what their generators produce`() {
        for ((name, record) in generated()) {
            val file = File(fixtures, "valid/$name")
            assertTrue(file.exists(), "missing $file; see this test's comment to generate it")
            assertEquals(record, RideRecordJson.decode(file.readText()), name)
            assertEquals(layout(record), file.readText(), "$name is not in the generated layout")
        }
    }

    @Test
    fun `nulls are read as gaps, so a producer that writes them is understood`() {
        val minimal = RideRecordJson.decode(File(fixtures, "valid/minimal-free-ride.json").readText())
        val withNulls = RideRecordJson.decode(File(fixtures, "valid/explicit-nulls.json").readText())
        assertEquals(minimal, withNulls)
        assertNull(withNulls.samples.first().heartRate)
    }

    private fun routeFixture(name: String) = File(contracts, "fixtures/routes/ride-record/valid/$name").readText()

    @Test
    fun `the route and the variant of a ride are in its header, and a ride without them is written as it was`() {
        val onRoute = RideRecordJson.decode(routeFixture("route-ride.json")).ride
        assertEquals("route-gpx-0001", onRoute.routeId)
        assertEquals("North road", onRoute.routeName)
        assertEquals(false, onRoute.wasVariantRidden)

        val variant = RideRecordJson.decode(routeFixture("variant-ride.json")).ride
        assertEquals("icu-5001", variant.workoutId)
        assertEquals(true, variant.variantRidden)
        assertTrue(variant.wasVariantRidden)
        assertNull(variant.routeId)

        // False, and a producer's explicit nulls, are a workout ridden as planned.
        assertEquals(false, RideRecordJson.decode(routeFixture("variant-not-ridden.json")).ride.wasVariantRidden)
        assertEquals(false, RideRecordJson.decode(routeFixture("explicit-nulls.json")).ride.wasVariantRidden)

        val both = RideRecordJson.decode(routeFixture("workout-with-route-and-variant.json")).ride
        assertTrue(both.wasVariantRidden && both.routeId != null && both.workoutId != null)

        // The new keys are written only when there is something to say: a plain ride is byte for byte what it always was.
        val plain = RideRecordJson.decode(File(fixtures, "valid/minimal-free-ride.json").readText())
        val written = RideRecordJson.encode(plain)
        assertTrue("routeId" !in written && "routeName" !in written && "variantRidden" !in written, written)
        val ridden = RideRecordJson.encode(RideRecordJson.decode(routeFixture("workout-with-route-and-variant.json")))
        assertTrue("\"variantRidden\":true" in ridden && "\"routeId\":" in ridden && "\"routeName\":" in ridden, ridden)
    }

    @Test
    fun `the synthetic ride fixture is a record the engine could have made`() {
        val record = RideRecordJson.decode(File(fixtures, "valid/synthetic-5min.json").readText())
        assertEquals(300, record.samples.size)
        assertEquals(listOf(0, 1, 2, 3), record.laps.map { it.stepIndex })
        assertTrue(
            record.samples.any { it.paused } && record.samples.any { it.heartRate == null } &&
                record.rrIntervals.isNotEmpty(),
        )
        assertEquals(record, RideRecordBuilder.recover(record.ride, record.samples, record.rrIntervals))
    }
}
