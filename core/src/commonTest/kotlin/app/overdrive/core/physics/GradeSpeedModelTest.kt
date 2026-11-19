package app.overdrive.core.physics

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The speed model with the gravity term (spec §4.3, §11.4) against **an independent solver**: plain bisection on the same force
 * balance, which shares no code and no idea with the model's Newton iteration (it needs no start above the root and no sign cases).
 */
class GradeSpeedModelTest {
    private val physics = PhysicsSettings()
    private val model = FlatPhysicsSpeedModel(physics)

    /** The root of `c v³ + a v − η P` on [0, 200 m/s] by bisection, in m/s; or the terminal speed when there is no power. */
    private fun bisect(
        settings: PhysicsSettings,
        watts: Double,
        gradePercent: Double,
    ): Double {
        val angle = atan(gradePercent / 100.0)
        val slope =
            settings.crr * settings.systemMassKg * PhysicsSettings.GRAVITY * cos(angle) +
                settings.systemMassKg * PhysicsSettings.GRAVITY * sin(angle)
        val drag = 0.5 * settings.airDensity * settings.cdA
        val wheel = settings.drivetrainEfficiency * watts

        fun balance(v: Double) = drag * v * v * v + slope * v - wheel
        var low = 0.0
        var high = 200.0
        if (wheel <= 0.0) return if (slope < 0.0) kotlin.math.sqrt(-slope / drag) else 0.0
        repeat(200) {
            val middle = (low + high) / 2
            if (balance(middle) < 0.0) low = middle else high = middle
        }
        return (low + high) / 2
    }

    private fun cappedKmh(metersPerSecond: Double) = minOf(metersPerSecond * 3.6, FlatPhysicsSpeedModel.MAX_SPEED_KMH)

    @Test
    fun `on the flat it is the model it always was, to the last bit`() {
        // The values of the old flat-only model, which this reproduces: the bracket at grade 0 is the rolling resistance alone.
        for (watts in listOf(50.0, 150.0, 250.0, 400.0, 1000.0)) {
            assertEquals(model.speedKmh(watts), model.speedKmh(watts, 0.0))
            assertEquals(cappedKmh(bisect(physics, watts, 0.0)), model.speedKmh(watts), 1e-9)
        }
        assertEquals(29.984125418542373, model.speedKmh(150.0), 1e-9)
    }

    @Test
    fun `a climb at 150 W is slow where the flat model said 30 km h`() {
        assertEquals(12.074223525267596, model.speedKmh(150.0, 5.0), 1e-9)
        assertEquals(10.82700993583311, model.speedKmh(250.0, 10.0), 1e-9)
        assertEquals(4.5068645282805715, model.speedKmh(150.0, 15.0), 1e-9)
        assertEquals(13.882374774088687, model.speedKmh(100.0, 2.5), 1e-9)
        assertTrue(model.speedKmh(150.0, 5.0) < 0.5 * model.speedKmh(150.0))
    }

    @Test
    fun `a descent is faster than the flat and a steep one needs no power at all`() {
        assertEquals(52.88049166407559, model.speedKmh(150.0, -5.0), 1e-9)
        assertEquals(73.60446183912123, model.speedKmh(300.0, -10.0), 1e-9)
        // Coasting at 0 W down 5%: the terminal speed, where gravity and drag balance.
        assertEquals(46.6797567093871, model.speedKmh(0.0, -5.0), 1e-9)
        // On the flat and on a climb no power is no speed, as before.
        assertEquals(0.0, model.speedKmh(0.0, 0.0))
        assertEquals(0.0, model.speedKmh(0.0, 3.0))
        // A descent shallower than the rolling resistance (0.4%) does not roll by itself.
        assertEquals(0.0, model.speedKmh(0.0, -0.2))
    }

    @Test
    fun `a descent is capped at 80 km h`() {
        // The physics alone would give 85.8 km/h here; the model stops at 80.
        assertEquals(85.76426005234765, bisect(physics, 200.0, -15.0) * 3.6, 1e-9)
        assertEquals(80.0, model.speedKmh(200.0, -15.0), 1e-12)
        assertEquals(80.0, model.speedKmh(1500.0, -15.0), 1e-12)
        assertEquals(80.0, model.speedKmh(0.0, -30.0), 1e-12)
        assertEquals(FlatPhysicsSpeedModel.MAX_SPEED_KMH, 80.0)
    }

    @Test
    fun `agrees with an independent bisection over a grid of powers and grades`() {
        for (watts in listOf(1.0, 10.0, 50.0, 100.0, 150.0, 200.0, 300.0, 500.0, 1000.0, 1500.0, 3000.0)) {
            for (grade in listOf(
                -30.0,
                -15.0,
                -10.0,
                -5.0,
                -1.0,
                -0.5,
                -0.4,
                0.0,
                0.4,
                1.0,
                2.5,
                5.0,
                8.0,
                10.0,
                15.0,
                20.0,
            )) {
                assertEquals(
                    cappedKmh(bisect(physics, watts, grade)),
                    model.speedKmh(watts, grade),
                    1e-8,
                    "$watts W at $grade%",
                )
            }
        }
    }

    @Test
    fun `agrees with the bisection for random riders, bikes and roads`() {
        val random = Random(20261119)
        repeat(3000) {
            val settings =
                PhysicsSettings(
                    systemMassKg = random.nextDouble(40.0, 200.0),
                    cdA = random.nextDouble(0.1, 1.0),
                    crr = random.nextDouble(0.001, 0.02),
                    airDensity = random.nextDouble(0.9, 1.4),
                    drivetrainEfficiency = random.nextDouble(0.5, 1.0),
                )
            val watts = random.nextDouble(0.0, 2500.0)
            val grade = random.nextDouble(-25.0, 25.0)
            val expected = cappedKmh(bisect(settings, watts, grade))
            assertEquals(
                expected,
                FlatPhysicsSpeedModel(settings).speedKmh(watts, grade),
                1e-7,
                "$settings $watts W $grade%",
            )
        }
    }

    @Test
    fun `the speed does not fall as the power rises or rise as the road steepens`() {
        for (grade in listOf(-12.0, -3.0, 0.0, 4.0, 12.0)) {
            var last = -1.0
            for (watts in 0..600 step 25) {
                val speed = model.speedKmh(watts.toDouble(), grade)
                assertTrue(speed >= last - 1e-12, "power: $watts W at $grade%")
                last = speed
            }
        }
        for (watts in listOf(100.0, 250.0, 600.0)) {
            var last = Double.MAX_VALUE
            for (tenths in -150..150 step 10) {
                val speed = model.speedKmh(watts, tenths / 10.0)
                assertTrue(speed <= last + 1e-12, "grade: $tenths at $watts W")
                last = speed
            }
        }
    }

    @Test
    fun `the inverse of the model gives the power back`() {
        for (grade in listOf(-8.0, 0.0, 3.0, 9.0)) {
            for (watts in listOf(80.0, 180.0, 320.0)) {
                val metersPerSecond = model.speedMetersPerSecond(watts, grade)
                if (metersPerSecond < FlatPhysicsSpeedModel.MAX_SPEED_KMH / 3.6) {
                    assertEquals(watts, model.powerForSpeed(metersPerSecond, grade), 1e-6, "$watts W at $grade%")
                }
            }
        }
    }

    @Test
    fun `a NaN is no speed and never an exception`() {
        assertEquals(0.0, model.speedKmh(Double.NaN, 5.0))
        assertEquals(0.0, model.speedKmh(200.0, Double.NaN))
        assertTrue(abs(model.speedKmh(10_000_000.0, 15.0)) < 200.0)
    }

    @Test
    fun `the drivetrain takes its share on a climb too`() {
        val lossy = FlatPhysicsSpeedModel(PhysicsSettings(drivetrainEfficiency = 0.975))
        assertTrue(lossy.speedKmh(200.0, 6.0) < model.speedKmh(200.0, 6.0))
        assertEquals(
            cappedKmh(bisect(PhysicsSettings(drivetrainEfficiency = 0.975), 200.0, 6.0)),
            lossy.speedKmh(200.0, 6.0),
            1e-9,
        )
    }
}
