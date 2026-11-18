package app.overdrive.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The patch the tablet writes ([ProfileApiJson.encodePatch]) and merges ([ProfilePatch.andThen]): the shapes of the schema. */
class ProfilePatchTest {
    @Test
    fun `an empty patch is nothing and is written as an empty object`() {
        assertTrue(ProfilePatch().isEmpty)
        assertEquals("{}", ProfileApiJson.encodePatch(ProfilePatch()))
        assertFalse(ProfilePatch(ftp = Change.Set(null)).isEmpty, "clearing the FTP is a change")
        assertFalse(ProfilePatch(goals = emptyList()).isEmpty, "so is clearing the goals")
    }

    @Test
    fun `every kind of field is written as the schema says`() {
        val patch =
            ProfilePatch(
                ftp = Change.Set(null),
                zones = Change.Set(PowerZones(listOf(50, 70, 88, 100, 115, 140))),
                weeklyAvailability = mapOf(Weekday.WED to 0, Weekday.MON to null, Weekday.SUN to 90),
                goals = listOf("Ride the Alps", "Finish"),
                physics =
                    PhysicsPatch(
                        systemMassKg = 78.5,
                        cdA = 0.31,
                        crr = 0.005,
                        airDensity = 1.2,
                        drivetrainEfficiency = 0.975,
                    ),
            )
        val written = ProfileApiJson.encodePatch(patch)
        assertEquals(
            """{"ftp":null,"zones":{"upperPercentOfFtp":[50,70,88,100,115,140]},""" +
                """"weeklyAvailability":{"mon":null,"wed":0,"sun":90},"goals":["Ride the Alps","Finish"],""" +
                """"physics":{"systemMassKg":78.5,"cdA":0.31,"crr":0.005,"airDensity":1.2,"drivetrainEfficiency":0.975}}""",
            written,
        )
        assertEquals(patch, ProfileApiJson.decodePatch(written))
        assertEquals("""{"zones":null}""", ProfileApiJson.encodePatch(ProfilePatch(zones = Change.Set(null))))
    }

    @Test
    fun `small numbers are still numbers`() {
        // Kotlin writes 0.0005 as 5.0E-4, which is a JSON number the service reads as 0.0005.
        val written = ProfileApiJson.encodePatch(ProfilePatch(physics = PhysicsPatch(crr = 0.0005)))
        assertEquals(0.0005, (ProfileApiJson.decodePatch(written)).physics.crr)
    }

    @Test
    fun `two patches in turn are one, the later winning per field`() {
        val first = ProfilePatch(ftp = Change.Set(180), physics = PhysicsPatch(systemMassKg = 80.0, crr = 0.004))
        val second =
            ProfilePatch(
                ftp = Change.Set(185),
                physics = PhysicsPatch(systemMassKg = 79.0, cdA = 0.3),
                weeklyAvailability = mapOf(Weekday.TUE to 60),
            )
        val merged = first.andThen(second)
        assertEquals(
            ProfilePatch(
                ftp = Change.Set(185),
                physics = PhysicsPatch(systemMassKg = 79.0, cdA = 0.3, crr = 0.004),
                weeklyAvailability = mapOf(Weekday.TUE to 60),
            ),
            merged,
        )
        val profile = Profile.default()
        assertEquals(second.applyTo(first.applyTo(profile)), merged.applyTo(profile))
        assertEquals(first, first.andThen(ProfilePatch()))
        assertEquals(second, ProfilePatch().andThen(second))
        assertEquals(185, merged.ftp.let { (it as Change.Set).value })
    }

    @Test
    fun `a later ftp that clears it wins over an earlier one that set it`() {
        val merged = ProfilePatch(ftp = Change.Set(180)).andThen(ProfilePatch(ftp = Change.Set(null)))
        assertEquals(null, merged.applyTo(Profile.default().copy(ftp = 175)).ftp)
    }
}
