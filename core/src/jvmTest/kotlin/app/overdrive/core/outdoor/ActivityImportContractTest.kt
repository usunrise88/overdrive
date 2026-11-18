package app.overdrive.core.outdoor

import app.overdrive.core.wellness.WellnessApiJson
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
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the answer of `POST /v1/activities/fit` must agree with
 * `contracts/schema/activity-import-response.schema.json` on every fixture under `contracts/fixtures/outdoor`, and the activity
 * model with `activity.schema.json` on the outdoor ride's fixtures. The TypeScript tests of `contracts/` run the same files.
 */
class ActivityImportContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

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
        val files = fixtures("outdoor/activity-import-response/valid")
        assertTrue(files.size >= 6)
        for (file in files) {
            val value = ActivityImportJson.decode(file.readText())
            val written = ActivityImportJson.encode(value)
            assertEquals(value, ActivityImportJson.decode(written), file.name)
            assertEquals(
                normalize(Json.parseToJsonElement(file.readText())),
                normalize(Json.parseToJsonElement(written)),
                "${file.name} written back",
            )
        }
    }

    @Test
    fun `rejects every invalid answer`() {
        val files = fixtures("outdoor/activity-import-response/invalid")
        assertTrue(files.size >= 25)
        for (file in files) {
            assertFailsWith<IllegalArgumentException>(file.name) { ActivityImportJson.decode(file.readText()) }
        }
    }

    @Test
    fun `every status and every reason is among the valid answers`() {
        val answers =
            fixtures(
                "outdoor/activity-import-response/valid",
            ).map { ActivityImportJson.decode(it.readText()) }
        assertEquals(ActivityImportStatus.entries.toSet(), answers.map { it.status }.toSet())
        assertEquals(ActivityImportReason.entries.toSet() + null, answers.map { it.reason }.toSet())
    }

    @Test
    fun `the limit and the sport are the contract's`() {
        assertEquals(16 * 1024 * 1024, ACTIVITY_FIT_MAX_BYTES)
        assertEquals("cycling-outdoor", OUTDOOR_RIDE_SPORT)
    }

    @Test
    fun `an outdoor ride is an activity with source fit and its distance and climb`() {
        val ride =
            WellnessApiJson.decodeActivity(
                File(contracts, "fixtures/wellness/activity/valid/outdoor-ride.json").readText(),
            )
        assertEquals("fit", ride.source)
        assertEquals(OUTDOOR_RIDE_SPORT, ride.type)
        assertNotNull(ride.distanceMeters)
        assertNotNull(ride.elevationGainMeters)
    }

    @Test
    fun `a message names the rule and never a figure`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ActivityImportSummary(
                    "Outdoor ride",
                    "2027-03-14T09:02:11Z",
                    "2027-03-14T11:07:41Z",
                    "2027-03-14",
                    7530,
                    1.0,
                    1.0,
                    712.0,
                    171,
                    null,
                    false,
                )
            }
        assertTrue("712" !in error.message.orEmpty(), error.message)
    }
}
