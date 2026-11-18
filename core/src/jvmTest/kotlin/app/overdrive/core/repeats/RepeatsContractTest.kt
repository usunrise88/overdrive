package app.overdrive.core.repeats

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
 * Cross-language contract: the Kotlin model of the repeated-sessions bodies must agree with the schemas in `contracts/schema/`
 * (`repeats-response`, `repeat-detail-response`, `repeat-previous-response`) on every fixture under `contracts/fixtures/repeats`.
 * The TypeScript tests of `contracts/` (and the service) run the same fixtures.
 */
class RepeatsContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/repeats/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (a fixture writes `150`, Kotlin writes a Double as `150.0`). */
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
    fun `reads every valid list and writes it back the same`() {
        readsAndWritesBack("repeats-response", RepeatsApiJson::decodeRepeats, RepeatsApiJson::encodeRepeats, 5)
    }

    @Test
    fun `rejects every invalid list`() {
        rejectsEvery("repeats-response", RepeatsApiJson::decodeRepeats, 30)
    }

    @Test
    fun `reads every valid detail and writes it back the same`() {
        readsAndWritesBack("repeat-detail-response", RepeatsApiJson::decodeDetail, RepeatsApiJson::encodeDetail, 8)
    }

    @Test
    fun `rejects every invalid detail`() {
        rejectsEvery("repeat-detail-response", RepeatsApiJson::decodeDetail, 60)
    }

    @Test
    fun `reads every valid previous attempt and writes it back the same`() {
        readsAndWritesBack(
            "repeat-previous-response",
            RepeatsApiJson::decodePrevious,
            RepeatsApiJson::encodePrevious,
            5,
        )
    }

    @Test
    fun `rejects every invalid previous attempt`() {
        rejectsEvery("repeat-previous-response", RepeatsApiJson::decodePrevious, 20)
    }

    @Test
    fun `every direction of a trend is among the valid details`() {
        val directions =
            fixtures("repeat-detail-response/valid")
                .flatMap {
                    val trends = RepeatsApiJson.decodeDetail(it.readText()).trends
                    listOf(trends.heartRateAtPower, trends.driftPercent, trends.compliance).map { t -> t.direction }
                }.toSet()
        assertEquals(RepeatDirection.entries.toSet(), directions)
    }

    @Test
    fun `the definition and the note are the contract's`() {
        for (file in fixtures("repeat-detail-response/valid")) {
            val detail = RepeatsApiJson.decodeDetail(file.readText())
            assertEquals(RepeatDefinition.NOTE, detail.note, file.name)
            assertEquals(
                RepeatDefinition(
                    RepeatDefinition.POWER_TOLERANCE_PERCENT,
                    RepeatDefinition.SKIP_SECONDS,
                    RepeatDefinition.MIN_STEP_SECONDS,
                    RepeatDefinition.MIN_TREND_ATTEMPTS,
                ),
                detail.definition,
            )
        }
    }

    @Test
    fun `a session with no earlier attempt has a null previous`() {
        val text = File(contracts, "fixtures/repeats/repeat-previous-response/valid/no-earlier-attempt.json").readText()
        assertNull(RepeatsApiJson.decodePrevious(text).previous)
        val with =
            File(
                contracts,
                "fixtures/repeats/repeat-previous-response/valid/with-a-previous-attempt.json",
            ).readText()
        assertNotNull(RepeatsApiJson.decodePrevious(with).previous)
    }

    @Test
    fun `a message names the rule and never a figure`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                RepeatStepHeartRate(2, 220.0, 220.4, 143.987, 90, null)
            }
        assertTrue("143" !in error.message.orEmpty(), error.message)
        assertTrue("90" !in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a hash is 16 lower-case hex characters`() {
        assertTrue(isRepeatHash("b1add004e12d9617"))
        assertTrue(!isRepeatHash("B1ADD004E12D9617"))
        assertTrue(!isRepeatHash("b1add004"))
    }
}
