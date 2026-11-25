package app.overdrive.core.ride

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlywheelWatchTest {
    private fun watch(step: Double = 0.5) = FlywheelWatch(step)

    @Test
    fun `a rise of a step between two consecutive readings is an acceleration a smaller one is not`() {
        val watch = watch()
        watch.offer(10.0)
        assertFalse(watch.accelerated, "there is nothing to compare the first reading with")
        watch.offer(10.25)
        assertFalse(watch.accelerated)
        watch.offer(10.75)
        assertTrue(watch.accelerated, "exactly the step")
        watch.offer(10.75)
        assertFalse(watch.accelerated, "it is about this reading and the one before")
    }

    @Test
    fun `a flywheel that only falls is never an acceleration`() {
        val watch = watch()
        var speed = 30.0
        repeat(100) {
            watch.offer(speed)
            assertFalse(watch.accelerated)
            speed -= 0.3
        }
        assertTrue(watch.coasting)
    }

    @Test
    fun `a slow coast is seen across readings and a repeated value does not end it`() {
        val watch = watch()
        watch.offer(20.0)
        assertFalse(watch.coasting, "no history")
        watch.offer(19.97)
        assertFalse(watch.coasting, "inside the flicker of the sensor")
        watch.offer(19.94)
        assertTrue(watch.coasting, "0.06 km/h below where it settled")
        repeat(5) {
            watch.offer(19.94)
            assertTrue(watch.coasting, "a sensor that has not updated yet says the same again")
        }
        watch.offer(20.0)
        assertFalse(watch.coasting, "it went up by more than the flicker")
    }

    @Test
    fun `a speed that wobbles around a steady value is neither`() {
        val watch = watch()
        repeat(50) {
            watch.offer(24.0 + if (it % 2 == 0) 0.02 else -0.02)
            assertFalse(watch.accelerated)
            assertFalse(watch.coasting)
        }
    }

    @Test
    fun `a reading without speed and a new link start over`() {
        val watch = watch()
        watch.offer(20.0)
        watch.offer(19.0)
        assertTrue(watch.coasting)
        watch.offer(null)
        assertFalse(watch.coasting)
        watch.offer(30.0)
        assertFalse(watch.accelerated, "nothing is compared across the gap")

        watch.offer(29.0)
        assertTrue(watch.coasting)
        watch.reset()
        assertFalse(watch.coasting)
        watch.offer(40.0)
        assertFalse(watch.accelerated)

        watch.offer(Double.NaN)
        watch.offer(50.0)
        assertFalse(watch.accelerated, "a reading that is not a number is a gap")
    }

    @Test
    fun `a step of 0 turns it off`() {
        val watch = watch(step = 0.0)
        watch.offer(1.0)
        watch.offer(50.0)
        assertFalse(watch.accelerated)
        watch.offer(1.0)
        assertFalse(watch.coasting)
    }
}
