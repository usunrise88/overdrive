package app.overdrive.core.progress

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
 * Cross-language contract: the Kotlin model of the progress body must agree with `contracts/schema/progress-response.schema.json` on
 * every fixture under `contracts/fixtures/progress`. The TypeScript tests of `contracts/` (and the service) run the same fixtures.
 */
class ProgressContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/progress/progress-response/$path")
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

    @Test
    fun `reads every valid answer and writes it back the same`() {
        val files = fixtures("valid")
        assertTrue(files.size >= 5, "at least 5 valid fixtures")
        for (file in files) {
            val value = ProgressApiJson.decode(file.readText())
            val written = ProgressApiJson.encode(value)
            assertEquals(value, ProgressApiJson.decode(written), file.name)
            assertEquals(
                normalize(Json.parseToJsonElement(file.readText())),
                normalize(Json.parseToJsonElement(written)),
                "${file.name} written back",
            )
        }
    }

    @Test
    fun `rejects every invalid answer`() {
        val files = fixtures("invalid")
        assertTrue(files.size >= 60, "at least 60 invalid fixtures")
        for (file in files) {
            assertFailsWith<IllegalArgumentException>(
                "invalid/${file.name}",
            ) { ProgressApiJson.decode(file.readText()) }
        }
    }

    @Test
    fun `every direction is among the valid answers, and so is a headline of each kind`() {
        val answers = fixtures("valid").map { ProgressApiJson.decode(it.readText()) }
        val directions =
            answers
                .flatMap {
                    listOf(
                        it.aerobicEfficiency.trend,
                        it.aerobicEfficiency.powerTrend,
                        it.efficiencyFactor.trend,
                        it.decoupling.trend,
                        it.restingHeartRate.trend,
                        it.weight.trend,
                        it.bestPower.trends.d300,
                        it.bestPower.trends.d1200,
                        it.bestPower.trends.d3600,
                    ).map { t -> t.direction }
                }.toSet()
        assertEquals(ProgressDirection.entries.toSet(), directions)
        assertEquals(
            setOf<ProgressHeadlineDirection?>(
                ProgressHeadlineDirection.IMPROVING,
                ProgressHeadlineDirection.WORSENING,
                null,
            ),
            answers.map { it.headline?.direction }.toSet(),
        )
    }

    @Test
    fun `the full answer has the figures the screen draws`() {
        val full =
            ProgressApiJson.decode(
                File(contracts, "fixtures/progress/progress-response/valid/full.json").readText(),
            )
        assertEquals(180, full.aerobicEfficiency.referencePower)
        assertEquals(135, full.aerobicEfficiency.referenceHeartRate)
        assertEquals(ProgressHeartRateBasis.THRESHOLD, full.aerobicEfficiency.referenceHeartRateBasis)
        assertEquals(3, full.bestPower.months.size)
        assertTrue(full.bestPower.months.last().partial)
        assertNotNull(full.bestPower.months[1].d3600)
        assertNotNull(full.weight.weightChange)
        assertEquals("Efficiency factor is trending up: fitter", full.efficiencyFactor.trend.verdictText)
    }

    @Test
    fun `an empty answer is valid and has no headline`() {
        val empty =
            ProgressApiJson.decode(
                File(contracts, "fixtures/progress/progress-response/valid/empty.json").readText(),
            )
        assertNull(empty.headline)
        assertTrue(empty.bestPower.months.isEmpty())
        assertEquals(ProgressDirection.UNKNOWN, empty.restingHeartRate.trend.direction)
    }

    @Test
    fun `a message names the rule and never a figure`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ProgressBestEffort(318.4, 4.37, null, "2026-09-12", "ride-1", "VO2 5x5")
            }
        assertTrue("318" !in error.message.orEmpty(), error.message)
        assertTrue("4.37" !in error.message.orEmpty(), error.message)
    }
}
