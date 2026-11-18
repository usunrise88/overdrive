package app.overdrive.core.insights

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
 * Cross-language contract: the Kotlin model of the insights bodies must agree with the schemas in `contracts/schema/`
 * (`insights-response`, `discussion-topic`) on every fixture under `contracts/fixtures/insights`. The TypeScript tests of
 * `contracts/` run the same fixtures.
 */
class InsightsContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/insights/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (a fixture writes `12`, Kotlin writes a Long as `12`). */
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
    fun `reads every valid insights answer and writes it back the same`() {
        readsAndWritesBack(
            "insights-response",
            InsightsApiJson::decodeResponse,
            InsightsApiJson::encodeResponse,
            8,
        )
    }

    @Test
    fun `rejects every invalid insights answer`() {
        rejectsEvery("insights-response", InsightsApiJson::decodeResponse, 60)
    }

    @Test
    fun `reads every valid discussion topic and writes it back the same`() {
        readsAndWritesBack("discussion-topic", InsightsApiJson::decodeTopic, InsightsApiJson::encodeTopic, 5)
    }

    @Test
    fun `rejects every invalid discussion topic`() {
        rejectsEvery("discussion-topic", InsightsApiJson::decodeTopic, 18)
    }

    @Test
    fun `every kind of note and every status of a topic is in the fixtures`() {
        val notes =
            fixtures("insights-response/valid").flatMap {
                InsightsApiJson.decodeResponse(it.readText()).insights
            }
        assertEquals(InsightKind.entries.toSet(), notes.map { it.kind }.toSet())
        assertEquals(TopicStatus.entries.toSet(), notes.mapNotNull { it.topic?.status }.toSet())
    }

    @Test
    fun `a ride note carries its topic and the others carry none`() {
        val response =
            InsightsApiJson.decodeResponse(
                File(contracts, "fixtures/insights/insights-response/valid/every-kind.json").readText(),
            )
        assertEquals(
            listOf(
                InsightKind.MORNING,
                InsightKind.RIDE,
                InsightKind.WEEK,
                InsightKind.DEVIATION,
                InsightKind.DRIFT,
            ),
            response.insights.map { it.kind },
        )
        val ride = response.insights[1]
        assertEquals("ride-1794984000000", ride.rideId)
        val topic = assertNotNull(ride.topic)
        assertEquals(TopicStatus.OPEN, topic.status)
        assertEquals(ride.rideId, topic.rideId)
        for (other in response.insights - ride) {
            assertNull(other.topic)
            if (other.kind != InsightKind.RIDE) assertNull(other.rideId)
        }
    }

    @Test
    fun `the longest range is 366 days and one more is refused`() {
        val body = File(contracts, "fixtures/insights/insights-response/valid/empty.json").readText()
        val ok = body.replace("2026-11-09", "2025-11-18")
        assertEquals(0, InsightsApiJson.decodeResponse(ok).insights.size)
        assertFailsWith<IllegalArgumentException> {
            InsightsApiJson.decodeResponse(body.replace("2026-11-09", "2025-11-17"))
        }
    }

    @Test
    fun `a message names the rule and never the text of a note`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                Insight(
                    1,
                    InsightKind.MORNING,
                    "2026-11-18T06:00:00Z",
                    "resting heart rate 99 and awful",
                    "ride-1",
                    null,
                )
            }
        assertTrue("awful" !in error.message.orEmpty(), error.message)
        assertTrue("99" !in error.message.orEmpty(), error.message)
    }
}
