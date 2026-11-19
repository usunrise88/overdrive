package app.overdrive.ride.settings

import app.overdrive.core.profile.Change
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfilePhysics
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.profile.RiderWeight
import app.overdrive.ride.host.toRideSettings
import app.overdrive.ride.profile.ProfileUi
import app.overdrive.ride.profile.ValueSource
import app.overdrive.ride.profile.bikeNote
import app.overdrive.ride.profile.massLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ride's mass from the rider's weight and the bike's (spec §12.5, docs/adr/0034): which mass applies when, how it reaches the engine, how
 * the bike weight is edited, and the one line the Settings screen says about it.
 */
class MassFromWeightsTest {
    private val own = AppSettings(ftp = 200, massKg = 75.0)
    private val rider = RiderWeight(78.4, "2026-11-18", 5)

    private fun profile(
        bike: Double? = 9.0,
        weight: RiderWeight? = rider,
        mass: Double = 82.0,
    ) = Profile.default().copy(
        ftp = 175,
        physics = ProfilePhysics.DEFAULT.copy(systemMassKg = mass),
        updatedAt = "2026-11-18T09:30:00Z",
        bikeWeightKg = bike,
        riderWeight = weight,
    )

    // ---- Which mass applies -------------------------------------------------------------------------------------------

    @Test
    fun `both weights make the mass, and the profile's physics mass stands aside`() {
        val effective = own.withProfile(profile())
        assertEquals(87.4, effective.massKg, 1e-9)
        assertEquals(9.0, effective.bikeWeightKg)
        assertEquals(rider, effective.riderWeight)
    }

    @Test
    fun `without a bike weight the profile's mass applies as it always did`() {
        assertEquals(82.0, own.withProfile(profile(bike = null)).massKg, 0.0)
    }

    @Test
    fun `without a weighing this week the profile's mass applies`() {
        val effective = own.withProfile(profile(weight = null))
        assertEquals(82.0, effective.massKg, 0.0)
        assertEquals(9.0, effective.bikeWeightKg)
        assertNull(effective.riderWeight)
    }

    @Test
    fun `with no profile the tablet's own mass applies and the weights are nothing`() {
        val effective = own.withProfile(null)
        assertEquals(75.0, effective.massKg, 0.0)
        assertNull(effective.bikeWeightKg)
        assertNull(effective.riderWeight)
    }

    @Test
    fun `a profile that has never been given a weight or a bike changes nothing about the mass`() {
        val plain = Profile.default().copy(ftp = 175)
        assertEquals(PhysicsDefaults.MASS, own.withProfile(plain).massKg, 0.0)
    }

    @Test
    fun `the weights reach the engine as the system mass and nowhere else`() {
        val engine = own.withProfile(profile()).toRideSettings()
        assertEquals(87.4, engine.physics.systemMassKg, 1e-9)
        assertEquals(175, engine.ftp)
        assertEquals(RideTuningDefaults.engine().stallGuard, engine.stallGuard)
    }

    @Test
    fun `a new weighing is a change of what a ready ride is built from, an identical one is not`() {
        val a = own.withProfile(profile())
        val b = own.withProfile(profile(weight = rider.copy(kg = 77.9)))
        assertTrue("the mass moved", a.rideInputs() != b.rideInputs())
        val sameMassNewDay = own.withProfile(profile(weight = rider.copy(date = "2026-11-19", days = 6)))
        assertEquals("the same mass is the same ride", a.rideInputs(), sameMassNewDay.rideInputs())
    }

    // ---- Editing the bike weight --------------------------------------------------------------------------------------

    @Test
    fun `setting the bike weight is a patch of the profile and clearing it is a null`() {
        val shown = own.withProfile(profile(bike = null))
        assertEquals(ProfilePatch(bikeWeightKg = Change.Set(9.5)), riderPatch(shown, shown.copy(bikeWeightKg = 9.5)))
        val withBike = own.withProfile(profile(bike = 9.0))
        assertEquals(
            ProfilePatch(bikeWeightKg = Change.Set(null)),
            riderPatch(withBike, withBike.copy(bikeWeightKg = null)),
        )
        assertTrue(riderPatch(withBike, withBike).isEmpty)
        // Nothing else rides along: the rider's weight is derived and cannot be sent.
        assertEquals(
            ProfilePatch(bikeWeightKg = Change.Set(10.0)),
            riderPatch(withBike, withBike.copy(bikeWeightKg = 10.0)),
        )
    }

    @Test
    fun `the bike weight rule steps by half a kilo, starts at nine and is the tablet's to clear`() {
        assertEquals(9.5, SettingsRules.bikeWeight.stepped(9.0, +1), 0.0)
        assertEquals(8.5, SettingsRules.bikeWeight.stepped(9.0, -1), 0.0)
        assertFalse(SettingsRules.bikeWeight.canStep(30.0, +1))
        assertEquals(9.0, SettingsRules.DEFAULT_BIKE_WEIGHT_KG, 0.0)
    }

    // ---- The one line -------------------------------------------------------------------------------------------------

    @Test
    fun `the line says where the mass comes from`() {
        assertEquals("Rider 78.4 kg (7-day average) + bike 9 kg", massLine(profile(), today = "2026-11-19"))
        assertEquals("Rider 78.4 kg (7-day average) + bike 9.5 kg", massLine(profile(bike = 9.5), today = "2026-11-18"))
    }

    @Test
    fun `an old weighing is said to be old`() {
        assertEquals(
            "Rider 78.4 kg (7-day average, last weighed 4 days ago) + bike 9 kg",
            massLine(profile(), today = "2026-11-22"),
        )
        assertEquals(
            "Rider 78.4 kg (7-day average) + bike 9 kg",
            massLine(profile(), today = "2026-11-20"),
        )
        assertEquals(
            "without a date there is no age",
            "Rider 78.4 kg (7-day average) + bike 9 kg",
            massLine(profile(), today = null),
        )
    }

    @Test
    fun `a bike weight with no weighing says the mass is the one set`() {
        assertEquals(
            "No weighing in the last 7 days, so the mass is the one set here",
            massLine(profile(weight = null), today = null),
        )
    }

    @Test
    fun `with no bike weight there is no line, and the bike row says what setting it would do`() {
        assertNull(massLine(profile(bike = null), today = null))
        assertEquals(
            "Set it to use your weight (78.4 kg, 7-day average) for the mass",
            bikeNote(profile(bike = null)),
        )
        assertEquals(
            "With your weight from Health Connect it makes the mass of the ride",
            bikeNote(profile(bike = null, weight = null)),
        )
        assertEquals("Added to your weight for the mass", bikeNote(profile()))
        assertEquals("Used once your weight is in Health Connect (7-day average)", bikeNote(profile(weight = null)))
    }

    @Test
    fun `the Settings rows know the mass is from the weights, and the bike row is shown only with a profile`() {
        val ui = ProfileUi.of(ProfileState(ready = true, profile = profile()), today = "2026-11-19")
        assertTrue(ui.massFromWeights)
        assertEquals(ValueSource.Weight, ui.mass)
        assertEquals("From your weight", ValueSource.Weight.marker)
        assertTrue(ui.bikeWeightShown)
        assertEquals(ValueSource.Service, ui.bike)
        assertEquals("Rider 78.4 kg (7-day average) + bike 9 kg", ui.massLine)

        val none = ProfileUi.of(ProfileState(ready = true))
        assertFalse(none.massFromWeights)
        assertFalse(none.bikeWeightShown)
        assertNull(none.massLine)

        val withoutBike = ProfileUi.of(ProfileState(ready = true, profile = profile(bike = null)))
        assertFalse(withoutBike.massFromWeights)
        assertEquals(ValueSource.Service, withoutBike.mass)
        assertTrue(withoutBike.bikeWeightShown)
        assertEquals(ValueSource.Tablet, withoutBike.bike)
    }

    @Test
    fun `a bike weight changed on the tablet and not yet taken says so`() {
        val state =
            ProfileState(
                ready = true,
                profile = profile(bike = 9.5),
                pending = ProfilePatch(bikeWeightKg = Change.Set(9.5)),
            )
        assertEquals(ValueSource.NotSent, ProfileUi.of(state).bike)
    }
}

/** The engine's own defaults, for what must not change. */
private object PhysicsDefaults {
    val MASS = ProfilePhysics.DEFAULT.systemMassKg
}

private object RideTuningDefaults {
    fun engine() = RideTuning().applyTo(app.overdrive.core.ride.RideSettings())
}
