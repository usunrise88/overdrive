package app.overdrive.core.wellness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the wellness bodies must agree with the schemas in `contracts/schema/`
 * (`wellness-upload`, `wellness-upload-response`, `wellness-response`, `recovery-response`, `activity`) on every fixture
 * under `contracts/fixtures/wellness`. The TypeScript tests of `contracts/` (and the service) run the same fixtures.
 */
class WellnessContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/wellness/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (a fixture writes `52`, Kotlin writes a Double as `52.0`). */
    private fun normalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(element.mapValues { normalize(it.value) })
            }

            is JsonArray -> {
                JsonArray(element.map(::normalize))
            }

            is JsonNull -> {
                element
            }

            is JsonPrimitive -> {
                if (element.isString) element else element.content.toDoubleOrNull()?.let(::JsonPrimitive) ?: element
            }
        }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun <T> readsAndWritesBack(
        set: String,
        decode: (String) -> T,
        encode: (T) -> String,
        minimum: Int,
    ) {
        val files = fixtures("$set/valid")
        assertTrue(files.size >= minimum, "$set has at least $minimum valid fixtures")
        for (file in files) {
            val value = decode(file.readText())
            val written = encode(value)
            assertEquals(value, decode(written), file.name)
            assertEquals(normalize(parse(file.readText())), normalize(parse(written)), "${file.name} written back")
        }
    }

    private fun <T> rejectsEvery(
        set: String,
        decode: (String) -> T,
        minimum: Int,
    ) {
        val files = fixtures("$set/invalid")
        assertTrue(files.size >= minimum, "$set has at least $minimum invalid fixtures")
        for (file in files) {
            assertFailsWith<IllegalArgumentException>("$set/invalid/${file.name}") { decode(file.readText()) }
        }
    }

    @Test
    fun `reads every valid upload and writes it back the same`() {
        readsAndWritesBack("upload", WellnessApiJson::decodeUpload, WellnessApiJson::encodeUpload, 6)
    }

    @Test
    fun `rejects every invalid upload`() {
        rejectsEvery("upload", WellnessApiJson::decodeUpload, 50)
    }

    @Test
    fun `an upload names its records by kind and leaves out what is not there`() {
        val upload =
            WellnessApiJson.decodeUpload(File(contracts, "fixtures/wellness/upload/valid/minimal.json").readText())
        val record = upload.records.single() as RestingHeartRateRecord
        assertEquals("rhr-0001", record.id)
        assertEquals(52, record.bpm)
        assertNull(record.lastModified)
        val written = parse(WellnessApiJson.encodeUpload(upload)).let { it as JsonObject }
        assertEquals(setOf("source", "records"), written.keys)
        val first = (written.getValue("records") as JsonArray).single() as JsonObject
        assertEquals(setOf("kind", "id", "time", "bpm"), first.keys)
    }

    @Test
    fun `every kind of record is in the every-kind fixture`() {
        val upload =
            WellnessApiJson.decodeUpload(File(contracts, "fixtures/wellness/upload/valid/every-kind.json").readText())
        assertEquals(9, upload.records.size)
        assertEquals(
            listOf(
                RestingHeartRateRecord::class,
                HeartRateSamplesRecord::class,
                SleepSessionRecord::class,
                RespiratoryRateRecord::class,
                OxygenSaturationRecord::class,
                StepsRecord::class,
                ExerciseSessionRecord::class,
                HeartRateVariabilityRmssdRecord::class,
                WeightRecord::class,
            ),
            upload.records.map { it::class },
        )
        assertEquals(WellnessApiJson.RECORD_KINDS.size, upload.records.size)
    }

    @Test
    fun `refuses more records than one request may carry`() {
        fun steps(i: Int) = StepsRecord("s$i", "2026-11-18T07:00:00Z", "2026-11-18T08:00:00Z", 1)
        assertEquals(2000, WellnessUpload("healthconnect", List(2000, ::steps)).records.size)
        assertFailsWith<IllegalArgumentException> { WellnessUpload("healthconnect", List(2001, ::steps)) }
    }

    @Test
    fun `a message names the rule and never the value`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                RestingHeartRateRecord("a", "2026-11-18T06:30:00Z", 73317)
            }
        assertTrue("73317" !in error.message.orEmpty(), error.message)
    }

    @Test
    fun `reads every valid upload response and writes it back the same`() {
        readsAndWritesBack(
            "upload-response",
            WellnessApiJson::decodeUploadResponse,
            WellnessApiJson::encodeUploadResponse,
            2,
        )
    }

    @Test
    fun `rejects every invalid upload response`() {
        rejectsEvery("upload-response", WellnessApiJson::decodeUploadResponse, 6)
    }

    @Test
    fun `reads every valid wellness answer and writes it back the same`() {
        readsAndWritesBack(
            "wellness",
            WellnessApiJson::decodeWellnessResponse,
            WellnessApiJson::encodeWellnessResponse,
            3,
        )
    }

    @Test
    fun `rejects every invalid wellness answer`() {
        rejectsEvery("wellness", WellnessApiJson::decodeWellnessResponse, 20)
    }

    @Test
    fun `a wellness day has the figures and the streaks the fixture says`() {
        val response =
            WellnessApiJson.decodeWellnessResponse(
                File(contracts, "fixtures/wellness/wellness/valid/with-baselines.json").readText(),
            )
        assertEquals(3, response.days.size)
        val resting = response.days[2].restingHr
        assertEquals(58.0, resting.value)
        assertEquals(Deviation.ABOVE, resting.deviation)
        assertEquals(2, resting.streak)
        assertEquals(WellnessSourceId.INTERVALS, response.days[0].respiratoryRate.source)
        assertNotNull(response.usual.bedtime)
        assertEquals(-80.0, response.usual.bedtime.low)
        assertEquals(78.4, response.days[0].weightKg)
        assertEquals(78.6, response.days[0].weightKg7d)
        assertNull(response.days[1].weightKg)
    }

    @Test
    fun `reads every valid recovery answer and writes it back the same`() {
        readsAndWritesBack(
            "recovery",
            WellnessApiJson::decodeRecoveryResponse,
            WellnessApiJson::encodeRecoveryResponse,
            7,
        )
    }

    @Test
    fun `rejects every invalid recovery answer`() {
        rejectsEvery("recovery", WellnessApiJson::decodeRecoveryResponse, 20)
    }

    @Test
    fun `every recovery state has a fixture`() {
        val states =
            fixtures("recovery/valid").map { WellnessApiJson.decodeRecoveryResponse(it.readText()).state }.toSet()
        assertEquals(RecoveryState.entries.toSet(), states)
    }

    @Test
    fun `reads every valid activity and writes it back the same`() {
        readsAndWritesBack("activity", WellnessApiJson::decodeActivity, WellnessApiJson::encodeActivity, 4)
    }

    @Test
    fun `rejects every invalid activity`() {
        rejectsEvery("activity", WellnessApiJson::decodeActivity, 20)
    }

    @Test
    fun `instants are real or they are not`() {
        assertEquals(1_794_996_000_000L, WellnessTime.parseInstant("2026-11-18T10:00:00Z"))
        assertEquals(1_794_996_000_500L, WellnessTime.parseInstant("2026-11-18T10:00:00.5Z"))
        assertEquals(1_794_996_000_123L, WellnessTime.parseInstant("2026-11-18T10:00:00.123456789Z"))
        for (bad in listOf("2026-02-30T06:30:00Z", "2026-11-18T24:00:00Z", "2026-11-18T06:30:00", "")) {
            assertNull(WellnessTime.parseInstant(bad), bad)
        }
    }
}
