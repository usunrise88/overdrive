package app.overdrive.core.powerskills

import app.overdrive.core.profile.PowerProfileTable
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
 * Cross-language contract: the Kotlin model of `GET /v1/power-skills` must agree with `contracts/schema/power-skills-response.schema.json`
 * on every fixture under `contracts/fixtures/power-skills/response`. The TypeScript tests of `contracts/` run the same fixtures.
 */
class PowerSkillsContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(dir: String): List<File> =
        File(contracts, "fixtures/power-skills/response/$dir")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    private fun valid(name: String): PowerSkillsResponse =
        PowerSkillsApiJson.decodePowerSkills(
            File(contracts, "fixtures/power-skills/response/valid/$name.json").readText(),
        )

    /** Numbers compare by value (a fixture writes `780`, Kotlin writes a Double as `780.0`). */
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
        assertTrue(files.size >= 10, "at least ten valid fixtures")
        for (file in files) {
            val value = PowerSkillsApiJson.decodePowerSkills(file.readText())
            val written = PowerSkillsApiJson.encodePowerSkills(value)
            assertEquals(value, PowerSkillsApiJson.decodePowerSkills(written), file.name)
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
        assertTrue(files.size >= 70, "at least 70 invalid fixtures")
        for (file in files) {
            assertFailsWith<IllegalArgumentException>("invalid/${file.name}") {
                PowerSkillsApiJson.decodePowerSkills(file.readText())
            }
        }
    }

    @Test
    fun `the constants are the contract's`() {
        assertEquals(12, POWER_SKILL_DURATIONS.size)
        assertEquals(
            listOf("Sprint", "Attack", "Climb", "Time trial", "Endurance"),
            PowerSkillId.entries.map { it.title },
        )
        assertEquals(
            POWER_SKILL_DURATIONS,
            PowerSkillId.entries.flatMap { it.durations },
            "the skills together cover every duration once, in order",
        )
        assertEquals(
            listOf("Starting", "Casual", "Regular", "Fit", "Strong", "Very strong", "Racer", "Elite", "World class"),
            POWER_LEVEL_NAMES,
        )
        assertEquals(56, POWER_SKILLS_DEFAULT_DAYS)
        assertEquals(1096, POWER_SKILLS_MAX_DAYS)
    }

    @Test
    fun `a full answer gives the figures a screen draws, in the order of the radar`() {
        val full = valid("full")
        assertEquals(PowerProfileTable.MEN, full.table)
        assertEquals(POWER_SKILL_DURATIONS, full.durations.map { it.seconds })
        val sprint = full.skill(PowerSkillId.SPRINT)
        assertEquals(listOf(15, 30), sprint.id.durations)
        assertNotNull(sprint.level)
        assertNotNull(full.duration(15).window?.score)
        assertNotNull(full.duration(15).allTime?.rideName)
        assertNotNull(full.riderWeight)
    }

    @Test
    fun `the women's table is read, and a missing weight leaves no scores and no levels`() {
        assertEquals(PowerProfileTable.WOMEN, valid("women-table").table)
        val none = valid("no-weight")
        assertNull(none.riderWeight)
        assertTrue(none.skills.all { it.level == null })
        assertTrue(none.durations.all { it.window?.score == null && it.window?.wattsPerKg == null })
        val empty = valid("empty-history")
        assertTrue(empty.durations.all { it.window == null && it.allTime == null })
        assertEquals(0, empty.window.rideCount)
    }

    @Test
    fun `a score held at both ends and a level of World class or Starting are read as they are`() {
        val top = valid("score-held-at-nine")
        assertEquals(9.0, top.duration(15).window?.score)
        assertEquals("World class", top.skill(PowerSkillId.SPRINT).level?.name)
        val bottom = valid("score-held-at-zero")
        assertEquals(0.0, bottom.duration(2700).window?.score)
        assertEquals(1, bottom.skill(PowerSkillId.ENDURANCE).level?.index)
    }

    @Test
    fun `a level is the floor of the mean of the scores of its durations`() {
        assertEquals(2, levelIndexOf(listOf(2.4, 2.21)))
        assertEquals(1, levelIndexOf(listOf(0.0, 0.36, 1.58)))
        assertEquals(9, levelIndexOf(listOf(9.0, 9.0)))
        assertEquals(4, levelIndexOf(listOf(4.99, 5.0)))
    }

    @Test
    fun `a rejection names the rule and never a figure or a ride`() {
        val text = File(contracts, "fixtures/power-skills/response/valid/full.json").readText()
        val tampered =
            text
                .replace(
                    "\"rideName\": \"VO2 5x3\"",
                    "\"rideName\": \"SECRET-RIDE-7731\"",
                ).replace("\"watts\": 780", "\"watts\": 99999")
        val error = assertFailsWith<IllegalArgumentException> { PowerSkillsApiJson.decodePowerSkills(tampered) }
        assertTrue(error.message.orEmpty().isNotEmpty())
        assertTrue("SECRET-RIDE-7731" !in error.message.orEmpty(), error.message)
        assertTrue("99999" !in error.message.orEmpty(), error.message)
    }
}
