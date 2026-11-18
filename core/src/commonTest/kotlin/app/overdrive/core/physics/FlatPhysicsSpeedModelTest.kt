package app.overdrive.core.physics

import kotlin.math.cbrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatPhysicsSpeedModelTest {
    private val gravity = 9.80665

    /**
     * The expected speed by a different method than the model's Newton iteration: plain bisection on
     * `η·P = Crr·m·g·v + ½·ρ·CdA·v³`, written out here from the formula in spec §4.3.
     */
    private fun bisectedSpeedKmh(
        watts: Double,
        mass: Double,
        cdA: Double,
        crr: Double,
        rho: Double = 1.225,
        efficiency: Double = 1.0,
    ): Double {
        var low = 0.0
        var high = 100.0
        repeat(200) {
            val mid = (low + high) / 2
            val used = crr * mass * gravity * mid + 0.5 * rho * cdA * mid * mid * mid
            if (used > efficiency * watts) high = mid else low = mid
        }
        return low * 3.6
    }

    @Test
    fun `200 W at 80 kg, CdA 0,35 and Crr 0,004 is about 33,4 km per hour`() {
        val speed = FlatPhysicsSpeedModel(PhysicsSettings(systemMassKg = 80.0, cdA = 0.35, crr = 0.004)).speedKmh(200.0)
        assertEquals(bisectedSpeedKmh(200.0, 80.0, 0.35, 0.004), speed, 1e-9)
        assertTrue(speed in 33.0..34.0, "got $speed")
    }

    @Test
    fun `at 10 m per second the power is rolling plus drag, worked out by hand`() {
        // Rolling: 0.004 * 80 kg * 9.80665 m/s² * 10 m/s = 31.38128 W.
        // Drag:    0.5 * 1.225 kg/m³ * 0.35 m² * (10 m/s)³ = 214.375 W.
        val watts = 31.38128 + 214.375
        assertEquals(36.0, FlatPhysicsSpeedModel().speedKmh(watts), 1e-6)
        assertEquals(watts, FlatPhysicsSpeedModel().powerForSpeed(10.0), 1e-9)
    }

    @Test
    fun `matches the bisected solution across the power range and several riders`() {
        val riders =
            listOf(
                PhysicsSettings(),
                PhysicsSettings(systemMassKg = 100.0, cdA = 0.40, crr = 0.006),
                PhysicsSettings(systemMassKg = 62.0, cdA = 0.30, crr = 0.003, airDensity = 1.1),
                PhysicsSettings(drivetrainEfficiency = 0.975),
            )
        for (settings in riders) {
            val model = FlatPhysicsSpeedModel(settings)
            for (watts in listOf(1.0, 5.0, 25.0, 100.0, 150.0, 250.0, 400.0, 800.0, 1500.0)) {
                val expected =
                    bisectedSpeedKmh(
                        watts,
                        settings.systemMassKg,
                        settings.cdA,
                        settings.crr,
                        settings.airDensity,
                        settings.drivetrainEfficiency,
                    )
                assertEquals(expected, model.speedKmh(watts), 1e-7, "$watts W for $settings")
            }
        }
    }

    @Test
    fun `speed grows strictly with power`() {
        val model = FlatPhysicsSpeedModel()
        val speeds = (1..400).map { model.speedKmh(it * 5.0) }
        assertTrue(speeds.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `no power means no speed`() {
        val model = FlatPhysicsSpeedModel()
        assertEquals(0.0, model.speedKmh(0.0))
        assertEquals(0.0, model.speedKmh(-5.0))
        assertEquals(0.0, model.speedKmh(Double.NaN))
    }

    @Test
    fun `a huge power is capped instead of failing`() {
        val speed = FlatPhysicsSpeedModel().speedKmh(Double.POSITIVE_INFINITY)
        assertTrue(speed.isFinite() && speed > 0.0)
    }

    @Test
    fun `with no rolling resistance the speed is the cube root of power over the drag coefficient`() {
        val settings = PhysicsSettings(crr = 0.0)
        val expected = cbrt(300.0 / settings.windResistanceCoefficient) * 3.6
        assertEquals(expected, FlatPhysicsSpeedModel(settings).speedKmh(300.0), 1e-9)
    }

    @Test
    fun `a less efficient drivetrain is slower`() {
        val lossless = FlatPhysicsSpeedModel(PhysicsSettings(drivetrainEfficiency = 1.0)).speedKmh(250.0)
        val lossy = FlatPhysicsSpeedModel(PhysicsSettings(drivetrainEfficiency = 0.95)).speedKmh(250.0)
        assertTrue(lossy < lossless)
        // 250 W at 95% is the same speed as 237.5 W lossless.
        assertEquals(FlatPhysicsSpeedModel().speedKmh(237.5), lossy, 1e-9)
    }

    @Test
    fun `heavier and draggier riders are slower, and the settings are what the trainer is told`() {
        val base = FlatPhysicsSpeedModel().speedKmh(250.0)
        assertTrue(FlatPhysicsSpeedModel(PhysicsSettings(systemMassKg = 100.0)).speedKmh(250.0) < base)
        assertTrue(FlatPhysicsSpeedModel(PhysicsSettings(cdA = 0.45)).speedKmh(250.0) < base)
        val settings = PhysicsSettings(cdA = 0.4, airDensity = 1.2)
        assertEquals(0.24, settings.windResistanceCoefficient, 1e-12)
        assertEquals(0.004 * 80.0 * gravity, PhysicsSettings().rollingResistanceNewtons, 1e-12)
    }

    @Test
    fun `invalid settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(systemMassKg = 0.0) }
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(cdA = -0.1) }
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(crr = -0.001) }
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(airDensity = 0.0) }
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(drivetrainEfficiency = 1.1) }
        assertFailsWith<IllegalArgumentException> { PhysicsSettings(drivetrainEfficiency = 0.0) }
    }
}
