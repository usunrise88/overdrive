package app.overdrive.core.gear

import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.ride.RideSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GearSettingsTest {
    private val gears = GearSettings()

    @Test
    fun `defaults follow the spec and the ride snapshot`() {
        assertEquals(24, gears.gearCount)
        assertEquals(12, gears.neutralGear)
        assertEquals(1.0, gears.gradePercentPerGear)
        assertEquals(15.0, gears.maxGradePercent)
        assertEquals(RideSnapshot.DEFAULT_GEAR_COUNT, gears.gearCount)
        assertEquals(RideSnapshot.DEFAULT_NEUTRAL_GEAR, gears.neutralGear)
    }

    @Test
    fun `the neutral gear is the flat and each gear moves the grade by one step`() {
        assertEquals(0.0, gears.gradePercent(12))
        assertEquals(1.0, gears.gradePercent(13))
        assertEquals(-1.0, gears.gradePercent(11))
        assertEquals(12.0, gears.gradePercent(24))
        assertEquals(-11.0, gears.gradePercent(1))
    }

    @Test
    fun `the offset is added to the route grade`() {
        assertEquals(6.0, gears.gradePercent(14, routeGradePercent = 4.0))
        assertEquals(1.0, gears.gradePercent(10, routeGradePercent = 3.0))
    }

    @Test
    fun `the total is clamped to the trainer's limit in both directions`() {
        assertEquals(15.0, gears.gradePercent(24, routeGradePercent = 8.0))
        assertEquals(-15.0, gears.gradePercent(1, routeGradePercent = -8.0))
        assertTrue(gears.isClamped(24, routeGradePercent = 8.0))
        assertFalse(gears.isClamped(24))

        val big = GearSettings(gearCount = 30, gradePercentPerGear = 2.0)
        assertEquals(15.0, big.gradePercent(30), "gear 30 would be +30%")
        assertEquals(-15.0, big.gradePercent(1), "gear 1 would be -28%")
        assertEquals(14.0, big.gradePercent(big.neutralGear + 7))
    }

    @Test
    fun `fractional steps are rounded to the FTMS resolution of one hundredth of a percent`() {
        val fine = GearSettings(gradePercentPerGear = 0.3)
        assertEquals(0.9, fine.gradePercent(15))
        assertEquals(-2.1, fine.gradePercent(5))
        assertEquals(3.33, GearSettings(gradePercentPerGear = 1.111).gradePercent(15))
    }

    @Test
    fun `simulation parameters carry the physics settings`() {
        val physics = PhysicsSettings(cdA = 0.4, crr = 0.005, airDensity = 1.2)
        val parameters = gears.simulationParameters(gear = 15, physics = physics)
        assertEquals(3.0, parameters.gradePercent)
        assertEquals(0.005, parameters.crr)
        assertEquals(0.24, parameters.windResistance, 1e-12)
    }

    @Test
    fun `an odd gear count has a true middle and a configured neutral is honoured`() {
        assertEquals(4, GearSettings(gearCount = 7).neutralGear)
        val low = GearSettings(gearCount = 10, neutralGear = 3)
        assertEquals(-2.0, low.gradePercent(1))
        assertEquals(7.0, low.gradePercent(10))
    }

    @Test
    fun `gears are clamped to the available range`() {
        assertEquals(1, gears.clampGear(0))
        assertEquals(24, gears.clampGear(99))
        assertEquals(7, gears.clampGear(7))
        assertFailsWith<IllegalArgumentException> { gears.gradePercent(0) }
        assertFailsWith<IllegalArgumentException> { gears.gradePercent(25) }
    }

    @Test
    fun `invalid settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { GearSettings(gearCount = 0) }
        assertFailsWith<IllegalArgumentException> { GearSettings(gearCount = 24, neutralGear = 25) }
        assertFailsWith<IllegalArgumentException> { GearSettings(gradePercentPerGear = 0.0) }
        assertFailsWith<IllegalArgumentException> { GearSettings(maxGradePercent = 0.0) }
    }
}
