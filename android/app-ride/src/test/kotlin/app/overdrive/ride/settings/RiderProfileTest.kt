package app.overdrive.ride.settings

import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PhysicsPatch
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfilePhysics
import app.overdrive.ride.host.toRideSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings a ride is built from: the tablet's own with the service's profile on top where it has an answer (ADR 0021). */
class RiderProfileTest {
    private val own = AppSettings(ftp = 200, massKg = 75.0, cdA = 0.32, crr = 0.005)

    private fun profile(
        ftp: Int? = 175,
        physics: ProfilePhysics = ProfilePhysics(82.5, 0.31, 0.0042, 1.19, 0.975),
    ) = Profile.default().copy(ftp = ftp, physics = physics, updatedAt = "2026-11-18T09:30:00Z")

    @Test
    fun `with no profile the settings are the tablet's own, untouched`() {
        assertSame(own, own.withProfile(null))
        assertEquals(200, own.withProfile(null).ftp)
    }

    @Test
    fun `the profile's ftp and physics are what the ride uses, the gears and the rest stay the tablet's`() {
        val own = own.copy(gearCount = 18, gradePerGear = 1.2, overlayOpacityPercent = 90)
        val effective = own.withProfile(profile())
        assertEquals(175, effective.ftp)
        assertEquals(82.5, effective.massKg, 0.0)
        assertEquals(0.31, effective.cdA, 0.0)
        assertEquals(0.0042, effective.crr, 0.0)
        assertEquals(1.19, effective.airDensity, 0.0)
        assertEquals(0.975, effective.drivetrainEfficiency, 0.0)
        assertEquals(18, effective.gearCount)
        assertEquals(1.2, effective.gradePerGear, 0.0)
        assertEquals(90, effective.overlayOpacityPercent)
    }

    @Test
    fun `a profile with no ftp leaves the tablet's own, and still brings its physics`() {
        val effective = own.withProfile(profile(ftp = null))
        assertEquals(200, effective.ftp)
        assertEquals(82.5, effective.massKg, 0.0)
    }

    @Test
    fun `a value off the tablet's grid is ridden as it is, not snapped`() {
        val effective = own.withProfile(profile(ftp = 177, physics = ProfilePhysics.DEFAULT.copy(systemMassKg = 82.5)))
        assertEquals(177, effective.ftp)
        assertEquals(82.5, effective.massKg, 0.0)
        assertTrue("the tablet's own grid is steps of 5 from 50", SettingsRules.ftp.normalize(177) != 177)
    }

    @Test
    fun `the tablet's own values are not changed by it, so taking the service away gives them back`() {
        val before = own.copy()
        own.withProfile(profile())
        assertEquals(before, own)
        assertEquals(200, own.withProfile(null).ftp)
    }

    @Test
    fun `the ride engine's settings carry the profile's physics, the engine's defaults where there is no profile`() {
        val withProfile = own.withProfile(profile()).toRideSettings()
        assertEquals(175, withProfile.ftp)
        assertEquals(PhysicsSettings(82.5, 0.31, 0.0042, 1.19, 0.975), withProfile.physics)
        val local = own.toRideSettings()
        assertEquals(200, local.ftp)
        assertEquals(PhysicsSettings(75.0, 0.32, 0.005), local.physics)
    }

    @Test
    fun `a change of the profile's constants is a change of what a ride is built from`() {
        assertTrue(own.rideInputs() != own.withProfile(profile()).rideInputs())
        val a = own.withProfile(profile(physics = ProfilePhysics.DEFAULT.copy(airDensity = 1.1)))
        val b = own.withProfile(profile(physics = ProfilePhysics.DEFAULT.copy(airDensity = 1.2)))
        assertTrue("air density alone rebuilds a ready ride", a.rideInputs() != b.rideInputs())
    }

    @Test
    fun `the stored settings never hold the service's constants`() {
        // Not written and not read: these two fields are only ever the engine's or the profile's.
        val defaults = AppSettings()
        assertEquals(PhysicsSettings.STANDARD_AIR_DENSITY, defaults.airDensity, 0.0)
        assertEquals(1.0, defaults.drivetrainEfficiency, 0.0)
        assertEquals(defaults, defaults.sanitized())
    }

    @Test
    fun `the patch of a change is the values that differ, and nothing when none do`() {
        val before = AppSettings().withProfile(profile())
        assertTrue(riderPatch(before, before).isEmpty)
        assertEquals(ProfilePatch(ftp = Change.Set(180)), riderPatch(before, before.copy(ftp = 180)))
        assertEquals(
            ProfilePatch(physics = PhysicsPatch(systemMassKg = 83.0, crr = 0.0045)),
            riderPatch(before, before.copy(massKg = 83.0, crr = 0.0045)),
        )
        assertEquals(
            ProfilePatch(ftp = Change.Set(185), physics = PhysicsPatch(cdA = 0.3)),
            riderPatch(before, before.copy(ftp = 185, cdA = 0.3)),
        )
        // What the profile holds but the tablet does not edit is never part of it.
        assertTrue(riderPatch(before, before.copy(gearCount = 12, overlayBlur = true)).isEmpty)
    }
}
