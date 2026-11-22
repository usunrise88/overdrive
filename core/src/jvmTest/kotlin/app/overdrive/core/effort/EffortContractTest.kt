package app.overdrive.core.effort

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
 * Cross-language contract: the Kotlin model of `GET /v1/effort` must agree with `contracts/schema/effort-response.schema.json` on every
 * fixture under `contracts/fixtures/effort/response`. The TypeScript tests of `contracts/` run the same fixtures.
 */
class EffortContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(dir: String): List<File> =
        File(contracts, "fixtures/effort/response/$dir")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    private fun valid(name: String): EffortResponse =
        EffortApiJson.decodeEffort(File(contracts, "fixtures/effort/response/valid/$name.json").readText())

    /** Numbers compare by value (a fixture writes `100`, Kotlin writes a Double as `100.0`). */
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
                if (element.isString || element.content == "true" || element.content == "false") {
                    element
                } else {
                    element.content.toDoubleOrNull()?.let(::JsonPrimitive) ?: element
                }
            }
        }

    @Test
    fun `reads every valid answer and writes it back the same`() {
        val files = fixtures("valid")
        assertTrue(files.size >= 14, "at least 14 valid fixtures")
        for (file in files) {
            val value = EffortApiJson.decodeEffort(file.readText())
            val written = EffortApiJson.encodeEffort(value)
            assertEquals(value, EffortApiJson.decodeEffort(written), file.name)
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
            assertFailsWith<IllegalArgumentException>("invalid/${file.name}") {
                EffortApiJson.decodeEffort(file.readText())
            }
        }
    }

    @Test
    fun `every verdict, both kinds and an activity with no load are read as they are`() {
        val all = fixtures("valid").map { EffortApiJson.decodeEffort(it.readText()) }
        assertEquals(EffortVerdict.entries.toSet(), all.flatMap { r -> r.weeks.map { it.verdict } }.toSet())
        val activities = all.flatMap { r -> r.weeks.flatMap { it.activities } }
        assertEquals(EffortKind.entries.toSet(), activities.map { it.kind }.toSet())
        assertTrue(activities.any { it.sport == "cycling-outdoor" })
        assertTrue(activities.any { it.load == null && it.share == null && !it.approximate })
        assertEquals(52, all.maxOf { it.weeks.size })
    }

    @Test
    fun `a week in progress above its range says above, and one with nothing yet says none`() {
        val above = valid("above-so-far")
        assertEquals(EffortVerdict.ABOVE, above.currentWeek.verdict)
        assertTrue(above.currentWeek.current)
        val range = assertNotNull(above.currentWeek.usual)
        assertTrue(above.currentWeek.load > range.endInclusive)
        assertEquals(EFFORT_WEEKS_NEEDED, above.weeksLogged)
        assertEquals(EffortVerdict.NONE, valid("nothing-yet-this-week").currentWeek.verdict)
    }

    @Test
    fun `a week that is learning has no range and counts the weeks so far`() {
        val learning = valid("learning")
        assertNull(learning.currentWeek.usual)
        assertEquals(EffortVerdict.LEARNING, learning.currentWeek.verdict)
        assertEquals(learning.currentWeek.weeksLogged, learning.weeksLogged)
        assertTrue(learning.weeksLogged < learning.weeksNeeded)
        // The usual range appears in the ninth week of training and not before.
        val appears = valid("usual-range-appears-after-eight-weeks")
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 8), appears.weeks.map { it.weeksLogged })
        assertEquals(listOf(8, 9), appears.weeks.indices.filter { appears.weeks[it].usual != null })
    }

    @Test
    fun `the days of a week are seven from its Monday, the days after today being empty`() {
        val response = valid("within-this-week")
        for (week in response.weeks) assertEquals(7, week.days.size)
        val current = response.currentWeek
        assertEquals(listOf(0.0, 58.5, 63.2, 0.0, 0.0, 0.0, 0.0), current.days.map { it.load })
    }

    @Test
    fun `the constants are the contract's`() {
        assertEquals(8, EFFORT_WEEKS_NEEDED)
        assertEquals(12, EFFORT_DEFAULT_WEEKS)
        assertEquals(52, EFFORT_MAX_WEEKS)
    }
}
