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
            val original = parse(file.readText()).jsonObject
            // The weight keys are written only when they have a value, so a null one in the fixture is an absent one here.
            val expected = JsonObject(original.filter { (key, value) -> key !in weightKeys || value != JsonNull })
            assertEquals(
                normalize(expected),
                normalize(withoutAbsentNulls(parse(written).jsonObject, original)),
                file.name,
            )
        }
    }

    /** The four heart-rate keys are optional on read (a profile cached before they existed), and always written. */
    private val heartRateKeys =
        setOf("maxHeartRate", "thresholdHeartRate", "estimatedMaxHeartRate", "estimatedThresholdHeartRate")

    /** `bikeWeightKg` and `riderWeight` (spec §12.5, docs/adr/0034): optional on read, written only when they have a value. */
    private val weightKeys = setOf("bikeWeightKg", "riderWeight", "powerProfileTable")

    private fun withoutAbsentNulls(
        written: JsonObject,
        original: JsonObject,
    ): JsonObject =
        JsonObject(written.filter { (key, value) -> key !in heartRateKeys || key in original || value != JsonNull })

    @Test
    fun `reads the weights of the fixtures and the system mass they make`() {
        val both =
            ProfileApiJson.decodeProfile(
                File(contracts, "fixtures/profile/profile/valid/weights.json").readText(),
            )
        assertEquals(9.0, both.bikeWeightKg)
        assertEquals(RiderWeight(78.4, "2026-11-18", 5), both.riderWeight)
        // Both weights, and only both, make the mass: rider plus bike.
        assertEquals(87.4, both.systemMassKgFromWeights()!!, 1e-9)
        val bikeOnly =
            ProfileApiJson.decodeProfile(
                File(contracts, "fixtures/profile/profile/valid/bike-weight-only.json").readText(),
            )
        assertEquals(8.5, bikeOnly.bikeWeightKg)
        assertNull(bikeOnly.riderWeight)
        assertNull(bikeOnly.systemMassKgFromWeights())
        val none =
            ProfileApiJson.decodeProfile(
                File(contracts, "fixtures/profile/profile/valid/weights-not-set.json").readText(),
            )
        assertNull(none.bikeWeightKg)
        assertNull(none.riderWeight)
        assertNull(none.systemMassKgFromWeights())
        assertNull(Profile.default().systemMassKgFromWeights())
        // A rider weight without the bike weight leaves the mass to the physics.
        assertNull(both.copy(bikeWeightKg = null).systemMassKgFromWeights())
    }

    @Test
    fun `a profile written without weights has no weight keys at all`() {
        val written = parse(ProfileApiJson.encodeProfile(Profile.default())).jsonObject
        assertTrue("bikeWeightKg" !in written.keys)
        assertTrue("riderWeight" !in written.keys)
        val withWeights =
            parse(
                ProfileApiJson.encodeProfile(
                    Profile.default().copy(bikeWeightKg = 9.0, riderWeight = RiderWeight(78.4, "2026-11-18", 5)),
                ),
            ).jsonObject
        assertEquals(JsonPrimitive(9.0), withWeights.getValue("bikeWeightKg"))
        assertEquals(setOf("kg", "date", "days"), withWeights.getValue("riderWeight").jsonObject.keys)
    }

    @Test
    fun `bike weight patches set, clear and merge like the other fields`() {
        val before = Profile.default().copy(bikeWeightKg = 9.0)
        assertEquals(9.5, ProfileApiJson.decodePatch("""{"bikeWeightKg":9.5}""").applyTo(before).bikeWeightKg)
        assertNull(ProfileApiJson.decodePatch("""{"bikeWeightKg":null}""").applyTo(before).bikeWeightKg)
        assertEquals(9.0, ProfileApiJson.decodePatch("""{"ftp":250}""").applyTo(before).bikeWeightKg)
        val patch = ProfilePatch(bikeWeightKg = Change.Set(8.0)).andThen(ProfilePatch(bikeWeightKg = Change.Set(null)))
        assertEquals("""{"bikeWeightKg":null}""", ProfileApiJson.encodePatch(patch))
        assertEquals(patch, ProfileApiJson.decodePatch(ProfileApiJson.encodePatch(patch)))
        assertTrue(!patch.isEmpty)
        assertFailsWith<IllegalArgumentException> { ProfileApiJson.decodePatch("""{"bikeWeightKg":"9"}""") }
        assertFailsWith<IllegalArgumentException> {
            ProfileApiJson.decodePatch("""{"bikeWeightKg":0}""").applyTo(before)
        }
        // The rider's weight is derived: no patch can carry it.
        assertFailsWith<IllegalArgumentException> {
            ProfileApiJson.decodePatch("""{"riderWeight":{"kg":70,"date":"2026-11-18","days":7}}""")
        }
    }

    @Test
    fun `the power profile table is read, written only when chosen, and patched like the other fields`() {
        val women =
            ProfileApiJson.decodeProfile(
                File(contracts, "fixtures/profile/profile/valid/power-profile-table-women.json").readText(),
            )
        assertEquals(PowerProfileTable.WOMEN, women.powerProfileTable)
        assertTrue("\"powerProfileTable\":\"women\"" in ProfileApiJson.encodeProfile(women))
        assertNull(
            ProfileApiJson
                .decodeProfile(
                    File(contracts, "fixtures/profile/profile/valid/power-profile-table-null.json").readText(),
                ).powerProfileTable,
        )
        assertTrue("powerProfileTable" !in ProfileApiJson.encodeProfile(Profile.default()))
        val patched = ProfileApiJson.decodePatch("""{"powerProfileTable":"women"}""").applyTo(Profile.default())
        assertEquals(PowerProfileTable.WOMEN, patched.powerProfileTable)
        assertNull(ProfileApiJson.decodePatch("""{"powerProfileTable":null}""").applyTo(patched).powerProfileTable)
        assertEquals(
            PowerProfileTable.WOMEN,
            ProfileApiJson.decodePatch("""{"ftp":250}""").applyTo(patched).powerProfileTable,
        )
        val twice =
            ProfilePatch(powerProfileTable = Change.Set(PowerProfileTable.MEN))
                .andThen(ProfilePatch(powerProfileTable = Change.Set(PowerProfileTable.WOMEN)))
        assertEquals("""{"powerProfileTable":"women"}""", ProfileApiJson.encodePatch(twice))
        assertEquals(twice, ProfileApiJson.decodePatch(ProfileApiJson.encodePatch(twice)))
        assertFailsWith<IllegalArgumentException> { ProfileApiJson.decodePatch("""{"powerProfileTable":"children"}""") }
        assertFailsWith<IllegalArgumentException> { ProfileApiJson.decodePatch("""{"powerProfileTable":1}""") }
        assertFailsWith<IllegalArgumentException> {
            ProfileApiJson.decodeProfile(
                File(contracts, "fixtures/profile/profile/invalid/power-profile-table-unknown.json").readText(),
            )
        }
    }

    @Test
    fun `a mass above what the profile allows is not made from the weights`() {
        val heavy = Profile.default().copy(bikeWeightKg = 100.0, riderWeight = RiderWeight(450.0, "2026-11-18", 7))
        assertNull(heavy.systemMassKgFromWeights())
    }

    @Test
    fun `a profile without the heart-rate keys reads them as null`() {
        val file = File(contracts, "fixtures/profile/profile/valid/full.json")
        val profile = ProfileApiJson.decodeProfile(file.readText())
        assertNull(profile.maxHeartRate)
        assertNull(profile.thresholdHeartRate)
        assertNull(profile.estimatedMaxHeartRate)
        assertNull(profile.estimatedThresholdHeartRate)
    }

    @Test
    fun `the heart-rate fixture reads every field`() {
        val file = File(contracts, "fixtures/profile/profile/valid/heart-rates.json")
        val profile = ProfileApiJson.decodeProfile(file.readText())
        assertEquals(186, profile.maxHeartRate)
        assertEquals(166, profile.thresholdHeartRate)
        assertEquals(184, profile.estimatedMaxHeartRate)
        assertEquals(162, profile.estimatedThresholdHeartRate)
    }

    @Test
    fun `heart-rate patches change only what they name`() {
        val before = Profile.default().copy(maxHeartRate = 186, thresholdHeartRate = 166)
        val set = ProfileApiJson.decodePatch("""{"thresholdHeartRate":160}""").applyTo(before)
        assertEquals(186, set.maxHeartRate)
        assertEquals(160, set.thresholdHeartRate)
        val cleared = ProfileApiJson.decodePatch("""{"maxHeartRate":null}""").applyTo(before)
        assertNull(cleared.maxHeartRate)
        assertEquals(166, cleared.thresholdHeartRate)
        val patch =
            ProfilePatch(
                maxHeartRate = Change.Set(190),
            ).andThen(ProfilePatch(thresholdHeartRate = Change.Set(null)))
        assertEquals("""{"maxHeartRate":190,"thresholdHeartRate":null}""", ProfileApiJson.encodePatch(patch))
        assertEquals(patch, ProfileApiJson.decodePatch(ProfileApiJson.encodePatch(patch)))
        assertTrue(ProfilePatch().isEmpty)
        assertTrue(!patch.isEmpty)
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
        assertEquals(
            setOf("ftp", "wPrimeKj", "zones", "weeklyAvailability", "goals", "physics", "updatedAt") + heartRateKeys,
            written.keys,
        )
        assertEquals(JsonNull, written.getValue("ftp"))
        assertEquals(JsonNull, written.getValue("wPrimeKj"))
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
    fun `W prime is set and cleared by a patch, and a profile without it is read as not valid`() {
        val profile = Profile.default().copy(ftp = 250)
        val set = ProfileApiJson.decodePatch("""{"wPrimeKj":18.5}""").applyTo(profile)
        assertEquals(18.5, set.wPrimeKj)
        assertNull(ProfileApiJson.decodePatch("""{"wPrimeKj":null}""").applyTo(set).wPrimeKj)
        assertEquals(set, ProfileApiJson.decodePatch("{}").applyTo(set))
        assertFailsWith<IllegalArgumentException> { ProfileApiJson.decodePatch("""{"wPrimeKj":"20"}""") }
        assertFailsWith<IllegalArgumentException> { ProfileApiJson.decodePatch("""{"wPrimeKj":0}""").applyTo(profile) }
        assertFailsWith<IllegalArgumentException> { Profile.default().copy(wPrimeKj = Double.NaN) }
        // Code may build a profile without it, but the wire says it every time.
        val written = ProfileApiJson.encodeProfile(profile)
        assertTrue("\"wPrimeKj\":null" in written, written)
        assertFailsWith<IllegalArgumentException> {
            ProfileApiJson.decodeProfile(written.replace(",\"wPrimeKj\":null", ""))
        }
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
