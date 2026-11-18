package app.overdrive.ride.profile

import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PhysicsPatch
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfileProblem
import app.overdrive.core.profile.ProfileState
import app.overdrive.ride.plan.PlanUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the numbers on the Settings rows come from, and the words the rows and the note say about it (ADR 0021). */
class ProfileUiTest {
    private val profile = Profile.default().copy(ftp = 175, updatedAt = "2026-11-18T09:30:00Z")
    private val inForce = ProfileState(ready = true, profile = profile)

    private fun markers(ui: ProfileUi) = listOf(ui.ftp, ui.mass, ui.cdA, ui.crr)

    @Test
    fun `with no profile every value is the tablet's own and nothing is said`() {
        val ui = ProfileUi.of(ProfileState(ready = true))
        assertEquals(ProfileUi(), ui)
        assertEquals(List(4) { ValueSource.Tablet }, markers(ui))
        assertNull(ui.note)
        assertEquals(listOf(null, "From service", "Not sent yet"), ValueSource.entries.map { it.marker })
    }

    @Test
    fun `with a profile the four values are from the service`() {
        val ui = ProfileUi.of(inForce)
        assertEquals(List(4) { ValueSource.Service }, markers(ui))
        assertNull(ui.note)
    }

    @Test
    fun `a profile with no ftp leaves the ftp the tablet's, and the physics the service's`() {
        val ui = ProfileUi.of(ProfileState(ready = true, profile = profile.copy(ftp = null)))
        assertEquals(ValueSource.Tablet, ui.ftp)
        assertEquals(listOf(ValueSource.Service, ValueSource.Service, ValueSource.Service), markers(ui).drop(1))
    }

    @Test
    fun `a value changed on the tablet and not yet taken says so, the others stay from the service`() {
        val ftp = ProfileUi.of(inForce.copy(pending = ProfilePatch(ftp = Change.Set(180))))
        assertEquals(
            listOf(ValueSource.NotSent, ValueSource.Service, ValueSource.Service, ValueSource.Service),
            markers(ftp),
        )
        val physics =
            ProfileUi.of(inForce.copy(pending = ProfilePatch(physics = PhysicsPatch(systemMassKg = 83.0, crr = 0.005))))
        assertEquals(
            listOf(ValueSource.Service, ValueSource.NotSent, ValueSource.Service, ValueSource.NotSent),
            markers(physics),
        )
    }

    @Test
    fun `a failed request is one plain line, and what it means for the numbers`() {
        val unreachable = ProfileProblem.Failed("Service unreachable")
        assertEquals(
            "Profile: Service unreachable · using this tablet's values",
            ProfileUi.of(ProfileState(ready = true, problem = unreachable)).note,
        )
        assertEquals(
            "Profile: Service unreachable · showing the last known profile",
            ProfileUi.of(inForce.copy(problem = unreachable)).note,
        )
        assertEquals(
            "Profile: Service unreachable · your change is sent when the service answers",
            ProfileUi.of(inForce.copy(pending = ProfilePatch(ftp = Change.Set(180)), problem = unreachable)).note,
        )
    }

    @Test
    fun `a rejected token says what the plan says`() {
        assertEquals(
            PlanUi.TOKEN_REJECTED,
            ProfileUi.of(ProfileState(ready = true, problem = ProfileProblem.TokenRejected)).note,
        )
    }
}
