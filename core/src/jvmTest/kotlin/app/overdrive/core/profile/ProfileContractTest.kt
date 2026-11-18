package app.overdrive.core.profile

import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.ride.PowerZone
import app.overdrive.core.sync.ApiErrorCode
import app.overdrive.core.sync.SyncApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the profile must agree with `contracts/schema/profile.schema.json` and
 * `profile-patch.schema.json` on every fixture under `contracts/fixtures/profile`. The TypeScript tests of `contracts/`
 * (and the service) run the same fixtures.
 */
class ProfileContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/profile/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (the fixtures write `80`, Kotlin writes a Double as `80.0`). */
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
                if (element.isString) {
                    element
                } else {
                    element.content.toDoubleOrNull()?.let(::JsonPrimitive)
                        ?: element
                }
            }
        }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `finds fixtures`() {
        assertTrue(fixtures("profile/valid").size >= 4)
        assertTrue(fixtures("profile/invalid").size >= 30)
        assertTrue(fixtures("patch/valid").size >= 12)
        assertTrue(fixtures("patch/invalid").size >= 18)
    }

    @Test
    fun `reads every valid profile and writes it back the same`() {
        for (file in fixtures("profile/valid")) {
            val profile = ProfileApiJson.decodeProfile(file.readText())
            val written = ProfileApiJson.encodeProfile(profile)
            assertEquals(profile, ProfileApiJson.decodeProfile(written), file.name)
            assertEquals(normalize(parse(file.readText())), normalize(parse(written)), file.name)
        }
    }

    @Test
    fun `rejects every invalid profile`() {
        for (file in fixtures("profile/invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { ProfileApiJson.decodeProfile(file.readText()) }
        }
    }

    @Test
    fun `the defaults are the defaults fixture`() {
        val file = File(contracts, "fixtures/profile/profile/valid/defaults.json")
        assertEquals(Profile.default(), ProfileApiJson.decodeProfile(file.readText()))
        // The profile's physics are the engine's defaults, and a profile makes the engine's settings back.
        assertEquals(PhysicsSettings(), Profile.default().physics.toPhysicsSettings())
    }

    @Test
    fun `every key is written, null included`() {
        val written = parse(ProfileApiJson.encodeProfile(Profile.default())).jsonObject
        assertEquals(setOf("ftp", "zones", "weeklyAvailability", "goals", "physics", "updatedAt"), written.keys)
        assertEquals(JsonNull, written.getValue("ftp"))
        assertEquals(JsonNull, written.getValue("updatedAt"))
        assertEquals(JsonNull, written.getValue("weeklyAvailability").jsonObject.getValue("mon"))
    }

    @Test
    fun `applies every valid patch to exactly the expected profile`() {
        for (file in fixtures("patch/valid")) {
            val fixture = parse(file.readText()).jsonObject
            val before = ProfileApiJson.decodeProfile(fixture.getValue("before").toString())
            val patch = ProfileApiJson.decodePatch(fixture.getValue("patch").toString())
            val after = ProfileApiJson.decodeProfile(fixture.getValue("after").toString())
            assertEquals(after, patch.applyTo(before), file.name)
        }
    }

    @Test
    fun `refuses every invalid patch, as a patch or once applied`() {
        for (file in fixtures("patch/invalid")) {
            val fixture = parse(file.readText()).jsonObject
            val before = ProfileApiJson.decodeProfile(fixture.getValue("before").toString())
            assertFailsWith<IllegalArgumentException>(file.name) {
                ProfileApiJson.decodePatch(fixture.getValue("patch").toString()).applyTo(before)
            }
        }
    }

    @Test
    fun `an empty patch changes nothing`() {
        val profile = Profile.default().copy(ftp = 250)
        assertEquals(profile, ProfileApiJson.decodePatch("{}").applyTo(profile))
    }

    @Test
    fun `null clears the ftp and resets the zones`() {
        val profile = Profile.default().copy(ftp = 250, zones = PowerZones(listOf(50, 70, 88, 100, 115, 140)))
        val patched = ProfileApiJson.decodePatch("""{"ftp":null,"zones":null}""").applyTo(profile)
        assertNull(patched.ftp)
        assertEquals(PowerZones.COGGAN, patched.zones)
    }

    @Test
    fun `the zone ranges are the fixture's`() {
        val file = JsonObject(parse(File(contracts, "fixtures/profile/zone-ranges.json").readText()).jsonObject)
        val cases = file.getValue("cases").jsonArray
        assertTrue(cases.size >= 5)
        for (case in cases.map { it.jsonObject }) {
            val ftp = case.getValue("ftp").jsonPrimitive.int
            val bounds = case.getValue("upperPercentOfFtp").jsonArray.map { it.jsonPrimitive.int }
            val zones = PowerZones(bounds)
            val expected =
                case.getValue("ranges").jsonArray.map {
                    PowerZoneRange(
                        zone = it.jsonObject.getValue("zone").jsonPrimitive.int,
                        minWatts = it.jsonObject.getValue("minWatts").jsonPrimitive.int,
                        maxWatts = it.jsonObject.getValue("maxWatts").jsonPrimitive.intOrNull,
                    )
                }
            assertEquals(expected, zones.ranges(ftp), "ftp $ftp, $bounds")
            for (watts in 0..MAX_WATTS_CHECKED) {
                val zone =
                    expected
                        .single { watts >= it.minWatts && (it.maxWatts == null || watts <= it.maxWatts) }
                        .zone
                assertEquals(zone, zones.zoneOf(watts, ftp), "$watts W at ftp $ftp, $bounds")
                // The ride app's PowerZone reads Coggan's bounds the same way.
                if (zones ==
                    PowerZones.COGGAN
                ) {
                    assertEquals(PowerZone.of(watts, ftp).number, zone, "$watts W at ftp $ftp")
                }
            }
        }
        assertEquals(1, PowerZones.COGGAN.zoneOf(-40, 250))
    }

    @Test
    fun `invalid_profile is a code of the API errors`() {
        val file = File(contracts, "fixtures/sync/api-error/valid/invalid-profile.json")
        assertEquals(ApiErrorCode.INVALID_PROFILE, SyncApiJson.decodeApiError(file.readText()).code)
        assertEquals(422, ApiErrorCode.INVALID_PROFILE.httpStatus)
    }

    private companion object {
        const val MAX_WATTS_CHECKED = 2500
    }
}
