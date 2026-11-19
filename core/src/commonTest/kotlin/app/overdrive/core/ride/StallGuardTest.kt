package app.overdrive.core.ride

import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure stall guard (spec §11.1, docs/adr/0033): virtual seconds, no clock. */
class StallGuardTest {
    /** Rides [count] seconds at [cadence]; returns the ticks on which the guard took hold. */
    private fun StallGuard.ride(
        count: Int,
        cadence: Int?,
        sinceResume: Int? = null,
    ): List<Int> = (1..count).filter { tick(cadence, sinceResume) }

    private fun armed(
        settings: StallGuardSettings = StallGuardSettings(),
        zeroIsStop: Boolean = true,
    ) = StallGuard(settings, zeroIsStop).also { it.ride(1, cadence = 90) }

    // region trigger, hold, release, ramp back

    @Test
    fun `cadence below the floor for 3 s takes hold, on the third tick`() {
        val guard = armed()
        assertEquals(emptyList(), guard.ride(2, cadence = 50))
        assertFalse(guard.active)
        assertTrue(guard.tick(50, null), "the third second below 55 rpm")
        assertTrue(guard.active)
        assertTrue(guard.holding)
        assertEquals(0.7, guard.factor)
        assertEquals(112, guard.limit(160), "70% of 160 W")
    }

    @Test
    fun `the seconds below the floor must be in a row`() {
        val guard = armed()
        assertEquals(emptyList(), guard.ride(2, cadence = 50))
        guard.tick(70, null)
        assertEquals(emptyList(), guard.ride(2, cadence = 50), "the count started over")
        assertFalse(guard.active)
    }

    @Test
    fun `exactly at the floor is not a stall`() {
        val guard = armed()
        assertEquals(emptyList(), guard.ride(10, cadence = 55))
        assertFalse(guard.active)
    }

    @Test
    fun `it holds while cadence stays low, and does not take hold again`() {
        val guard = armed()
        assertEquals(listOf(3), guard.ride(20, cadence = 40), "one activation, not one a second")
        assertEquals(0.7, guard.factor)
    }

    @Test
    fun `it lets go after cadence above floor plus 10 for 5 s, then climbs back over 5 s`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        // Above 55 but not above 65 does not release.
        guard.ride(30, cadence = 64)
        assertTrue(guard.holding)
        // 65 rpm is the floor plus the margin: it counts.
        guard.ride(4, cadence = 65)
        assertTrue(guard.holding, "4 s is not enough")
        guard.tick(65, null)
        assertTrue(guard.active)
        assertFalse(guard.holding, "5 s in a row: releasing")
        assertEquals(0.7, guard.factor, "the climb starts from the hold")

        val factors = (1..5).map { guard.tick(90, null).let { _ -> guard.factor } }
        assertEquals(listOf(0.76, 0.82, 0.88, 0.94, 1.0), factors.map { round(it * 100) / 100.0 })
        assertFalse(guard.active, "the climb is over: the target is what the step asks")
        assertEquals(160, guard.limit(160))
    }

    @Test
    fun `climbing back in whole watts`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        guard.ride(5, cadence = 80)
        // 160 W: 112 at the start of the climb, then 122, 131, 141, 150 and 160 (0.76, 0.82, 0.88, 0.94, 1).
        val watts = mutableListOf(guard.limit(160))
        repeat(5) {
            guard.tick(80, null)
            watts += guard.limit(160)
        }
        assertEquals(listOf(112, 122, 131, 141, 150, 160), watts)
    }

    @Test
    fun `a dip back below the release cadence restarts the count for letting go`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        guard.ride(4, cadence = 80)
        guard.tick(60, null)
        guard.ride(4, cadence = 80)
        assertTrue(guard.holding, "the 5 s were broken by one second at 60 rpm")
    }

    @Test
    fun `a stall while it climbs back takes hold again at once and is a new activation`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        guard.ride(5, cadence = 80)
        guard.tick(80, null)
        assertTrue(guard.active)
        assertEquals(listOf(3), guard.ride(3, cadence = 30), "three seconds low during the climb")
        assertTrue(guard.holding)
        assertEquals(0.7, guard.factor)
    }

    @Test
    fun `limit never raises a target`() {
        val guard = armed()
        assertEquals(0, guard.limit(0))
        guard.ride(3, cadence = 40)
        assertTrue(guard.limit(300) < 300)
        assertEquals(0, guard.limit(0))
    }

    // endregion

    // region not judged

    @Test
    fun `a missing cadence is not a low cadence`() {
        val guard = armed()
        assertEquals(emptyList(), guard.ride(30, cadence = null))
        assertFalse(guard.active)
    }

    @Test
    fun `a gap in the cadence breaks the run of low seconds`() {
        val guard = armed()
        guard.ride(2, cadence = 40)
        guard.tick(null, null)
        assertEquals(emptyList(), guard.ride(2, cadence = 40))
        assertEquals(listOf(1), guard.ride(1, cadence = 40))
    }

    @Test
    fun `a gap does not let go either`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        guard.ride(10, cadence = null)
        assertTrue(guard.holding)
    }

    @Test
    fun `a ride that starts is not judged until the rider has pedalled above the floor once`() {
        val guard = StallGuard(StallGuardSettings(), zeroCadenceIsAStop = true)
        assertEquals(emptyList(), guard.ride(25, cadence = 30), "getting up to speed")
        assertFalse(guard.active)
        guard.tick(60, null)
        assertEquals(listOf(3), guard.ride(5, cadence = 30), "armed by the first cadence above the floor")
    }

    @Test
    fun `the wait for the first good cadence is bounded, so a rider who never gets going is still protected`() {
        val guard = StallGuard(StallGuardSettings(armTimeoutSeconds = 30), zeroCadenceIsAStop = true)
        val fired = guard.ride(40, cadence = 30)
        // Armed at the 30th second; the third low second after that is the 32nd tick (the 30th counts as the first).
        assertEquals(listOf(32), fired)
    }

    @Test
    fun `just after a resume nothing is judged, and then it is`() {
        val guard = armed()
        // The ticks right after a resume carry 1, 2, 3, 4 seconds since the resume: inside the 5 s grace.
        var fired = false
        for (since in 1..4) fired = fired or guard.tick(20, since)
        assertFalse(fired, "a rider restarting after a pause is not stalling")
        assertFalse(guard.active)
        // From the fifth second on a low cadence counts, from a count of zero.
        assertEquals(emptyList(), (5..6).filter { guard.tick(20, it) })
        assertTrue(guard.tick(20, 7))
    }

    @Test
    fun `the grace is a setting, and 0 turns it off`() {
        val guard = armed(StallGuardSettings(resumeGraceSeconds = 0))
        assertEquals(listOf(3), guard.ride(3, cadence = 20, sinceResume = 1))
    }

    @Test
    fun `reset forgets a guard that has hold and leaves it unarmed`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        assertTrue(guard.active)
        guard.reset()
        assertFalse(guard.active)
        assertEquals(1.0, guard.factor)
        assertEquals(emptyList(), guard.ride(10, cadence = 30), "a rider who restarts slowly is not stalling")
    }

    @Test
    fun `turned off it never takes hold`() {
        val guard = StallGuard(StallGuardSettings(enabled = false))
        assertEquals(emptyList(), guard.ride(60, cadence = 20))
        assertFalse(guard.active)
        assertEquals(160, guard.limit(160))
    }

    // endregion

    // region the auto-pause at 0 rpm

    @Test
    fun `at 0 rpm the auto-pause wins, a stop is not a stall`() {
        val guard = armed()
        assertEquals(emptyList(), guard.ride(30, cadence = 0))
        assertFalse(guard.active)
    }

    @Test
    fun `a stop breaks the run of low seconds, it does not complete it`() {
        val guard = armed()
        guard.ride(2, cadence = 40)
        guard.tick(0, null)
        assertEquals(emptyList(), guard.ride(2, cadence = 40))
    }

    @Test
    fun `a guard that has hold stays through zeros until the pause resets it`() {
        val guard = armed()
        guard.ride(3, cadence = 40)
        guard.ride(10, cadence = 0)
        assertTrue(guard.holding)
        guard.reset()
        assertFalse(guard.active)
    }

    @Test
    fun `with auto-pause off 0 rpm is below the floor like any cadence`() {
        val guard = armed(zeroIsStop = false)
        assertEquals(listOf(3), guard.ride(5, cadence = 0))
    }

    // endregion

    @Test
    fun `settings are checked`() {
        assertFailsWith<IllegalArgumentException> { StallGuardSettings(targetFraction = 1.0) }
        assertFailsWith<IllegalArgumentException> { StallGuardSettings(targetFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { StallGuardSettings(triggerSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { StallGuardSettings(cadenceFloorRpm = 0) }
        assertFailsWith<IllegalArgumentException> { StallGuardSettings(releaseSeconds = 0) }
        assertEquals(55, StallGuardSettings().cadenceFloorRpm)
        assertEquals(0.7, StallGuardSettings().targetFraction)
    }

    @Test
    fun `a custom floor, trigger and release are used`() {
        val guard =
            StallGuard(
                StallGuardSettings(
                    cadenceFloorRpm = 70,
                    triggerSeconds = 2,
                    releaseMarginRpm = 5,
                    releaseSeconds = 2,
                    rampBackSeconds = 0,
                ),
            )
        guard.tick(90, null)
        assertEquals(listOf(2), guard.ride(3, cadence = 69))
        guard.ride(2, cadence = 75)
        assertFalse(guard.active, "no ramp back: the target is back at once")
    }
}
