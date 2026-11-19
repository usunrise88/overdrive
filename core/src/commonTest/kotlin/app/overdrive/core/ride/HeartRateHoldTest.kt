package app.overdrive.core.ride

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a [HeartRateHold] did against a [LagRider]: per second, the power it commanded and the rider's true heart rate. */
private class Trace(
    val watts: List<Int>,
    val heartRate: List<Double>,
)

private fun run(
    hold: HeartRateHold,
    rider: LagRider,
    seconds: Int,
    reading: (Int) -> Int? = { rider.reading() },
): Trace {
    val watts = mutableListOf<Int>()
    val heartRate = mutableListOf<Double>()
    repeat(seconds) { t ->
        rider.pedal(hold.watts)
        hold.tick(reading(t))
        watts += hold.watts
        heartRate += rider.heartRate
    }
    return Trace(watts, heartRate)
}

private val STEP = HeartRateTarget(targetBpm = 130, band = 5, powerMin = 80, powerMax = 220)

/** The heart-rate hold controller (spec §11.2, docs/adr/0033): pure, in virtual seconds, against a lag model. */
class HeartRateHoldTest {
    // region the start ramp

    @Test
    fun `the start ramp climbs from power_min and the controller is not in charge`() {
        val hold = HeartRateHold(STEP)
        assertEquals(80, hold.watts, "power_min at the start")
        assertTrue(hold.startRamp)
        // 30% of the range 80..220 is 122 W, reached after 3 min.
        repeat(90) { hold.tick(200) } // a heart rate far above the target does not matter yet
        assertEquals(101, hold.watts, "halfway: 80 + 21")
        assertTrue(hold.startRamp)
        repeat(89) { hold.tick(200) }
        assertTrue(hold.startRamp)
        hold.tick(200)
        assertEquals(122, hold.watts)
        assertFalse(hold.startRamp, "after 180 s the controller takes over")
    }

    @Test
    fun `the ramp is a setting, in length and in where it ends`() {
        val hold = HeartRateHold(STEP, HeartRateHoldSettings(startRampSeconds = 60, startRampFraction = 0.5))
        repeat(60) { hold.tick(130) }
        assertEquals(150, hold.watts, "halfway through the range, after one minute")
        assertFalse(hold.startRamp)
        val none = HeartRateHold(STEP, HeartRateHoldSettings(startRampSeconds = 0))
        assertFalse(none.startRamp)
        assertEquals(80, none.watts)
    }

    @Test
    fun `the controller takes over from the power the ramp reached`() {
        val hold = HeartRateHold(STEP)
        // A heart rate right on the target the whole time: nothing for the controller to correct.
        repeat(180 + 100) { hold.tick(130) }
        assertEquals(122, hold.watts)
    }

    // endregion

    // region settling on a lag model

    @Test
    fun `it settles inside the band and does not oscillate beyond a stated amplitude, across riders`() {
        val band = 5
        for (steadyFraction in listOf(0.2, 0.5, 0.9)) {
            for (gain in listOf(0.2, 0.45, 0.7)) {
                for (tau in listOf(25.0, 50.0, 80.0)) {
                    val rider = LagRider(gain, tau, noiseBpm = 2)
                    val steadyWatts = 80 + steadyFraction * 140
                    val target = (65.0 + gain * steadyWatts).roundToInt()
                    val hold = HeartRateHold(HeartRateTarget(target, band, 80, 220))
                    val trace = run(hold, rider, 3000)
                    val label = "fraction $steadyFraction gain $gain tau $tau target $target"
                    // Whatever happens during the first 10 minutes after the ramp, the last 40 minutes are inside the band...
                    val settled = trace.heartRate.drop(180 + 600)
                    assertTrue(settled.all { abs(it - target) <= band }, "inside the band: $label")
                    // ...and calm: the heart rate moves by at most 4 bpm peak to peak, the power by at most 8 W.
                    assertTrue(settled.max() - settled.min() <= 4.0, "heart rate steady: $label")
                    val power = trace.watts.drop(180 + 600)
                    assertTrue(power.max() - power.min() <= 8, "power steady: $label")
                }
            }
        }
    }

    @Test
    fun `it does not overshoot far above the target on its way`() {
        // A start ramp that ends well above what the rider needs: the controller backs off before the heart rate runs away.
        val rider = LagRider(gain = 0.7, tau = 50.0, noiseBpm = 0)
        val target = (65 + 0.7 * 96).roundToInt() // steady state at 96 W, the ramp ends at 122 W
        val hold = HeartRateHold(HeartRateTarget(target, 5, 80, 220))
        val trace = run(hold, rider, 1500)
        assertTrue(
            trace.heartRate.max() - target <= 12.0,
            "the heart rate peaks ${trace.heartRate.max() - target} bpm over",
        )
        assertTrue(trace.heartRate.drop(180 + 400).all { abs(it - target) <= 5 })
    }

    @Test
    fun `power never leaves the limits, however far the heart rate is from the target`() {
        val tooLow = run(HeartRateHold(HeartRateTarget(200, 5, 80, 220)), LagRider(0.3, 40.0), 2000)
        assertTrue(tooLow.watts.all { it in 80..220 })
        assertEquals(220, tooLow.watts.last(), "a target the rider cannot reach at power_max: held at power_max")

        val tooHigh = run(HeartRateHold(HeartRateTarget(60, 5, 80, 220)), LagRider(0.3, 40.0), 2000)
        assertTrue(tooHigh.watts.all { it in 80..220 })
        assertEquals(80, tooHigh.watts.last(), "a target below what power_min gives: held at power_min")
    }

    @Test
    fun `decisions come every 10 s and are limited in size`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0, decisionIntervalSeconds = 10)
        val hold = HeartRateHold(HeartRateTarget(150, 5, 80, 220), settings)
        // A heart rate 30 bpm under target every second: the controller wants to climb as fast as it may.
        val watts = mutableListOf<Int>()
        repeat(200) {
            hold.tick(120)
            watts += hold.watts
        }
        val changes = watts.indices.filter { it > 0 && watts[it] != watts[it - 1] }
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.zipWithNext().all { (a, b) -> b - a == 10 }, "one decision every 10 s: $changes")
        assertTrue(changes.all { watts[it] - watts[it - 1] == 5 }, "the step limit up is 5 W")
        assertTrue(changes.first() >= 31, "nothing until a 30 s window of heart rate is known: ${changes.first()}")
    }

    @Test
    fun `it comes down faster than it goes up`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0)
        // Let it climb to power_max first, a heart rate far under the target the whole time.
        val climber = HeartRateHold(HeartRateTarget(170, 5, 80, 220), settings)
        repeat(400) { climber.tick(120) }
        assertEquals(220, climber.watts)
        val down = mutableListOf<Int>()
        repeat(80) {
            climber.tick(190) // far above a 170 target
            down += climber.watts
        }
        val drops = down.zipWithNext { a, b -> a - b }.filter { it > 0 }
        assertTrue(drops.isNotEmpty())
        assertEquals(10, drops.max(), "the step limit down is 10 W, twice the one up: $drops")
        assertTrue(drops.all { it <= 10 })
    }

    @Test
    fun `noise inside the dead band is not chased`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0)
        val hold = HeartRateHold(HeartRateTarget(130, 5, 80, 220), settings)
        repeat(300) { hold.tick(if (it % 2 == 0) 129 else 131) }
        assertEquals(80, hold.watts, "a heart rate that wobbles around the target by 1 bpm changes nothing")
    }

    // endregion

    // region strap loss

    private fun holdAtSteadyState(): HeartRateHold {
        val hold = HeartRateHold(HeartRateTarget(130, 5, 80, 220))
        // Climb to a power well above power_min first, so that a drop is visible.
        repeat(180 + 200) { hold.tick(100) }
        check(hold.watts > 130) { "expected a raised power, got ${hold.watts}" }
        return hold
    }

    @Test
    fun `a strap that drops out holds the last power for 15 s, and after 60 s drops to power_min`() {
        val hold = holdAtSteadyState()
        val before = hold.watts
        repeat(14) { hold.tick(null) }
        assertFalse(hold.signalLost)
        assertEquals(before, hold.watts)
        hold.tick(null)
        assertTrue(hold.signalLost, "15 s without a heart rate")
        assertFalse(hold.strapLost)
        assertEquals(before, hold.watts, "the last power is held")
        repeat(44) { hold.tick(null) }
        assertFalse(hold.strapLost, "59 s")
        assertEquals(before, hold.watts)
        hold.tick(null)
        assertTrue(hold.strapLost, "60 s")
        assertEquals(80, hold.watts, "power_min")
        assertEquals(60, hold.secondsWithoutHeartRate)
        assertTrue(hold.state().strapLost)
        assertNull(hold.inBand)
    }

    @Test
    fun `a strap that comes back after the drop clears the flags and the controller starts over from power_min`() {
        val hold = holdAtSteadyState()
        repeat(61) { hold.tick(null) }
        assertEquals(80, hold.watts)
        hold.tick(100)
        assertFalse(hold.strapLost)
        assertFalse(hold.signalLost)
        assertEquals(0, hold.secondsWithoutHeartRate)
        // The filter starts over: no decision until a window of 30 s is known again.
        repeat(25) { hold.tick(100) }
        assertEquals(80, hold.watts)
        repeat(30) { hold.tick(100) }
        assertTrue(hold.watts > 80, "then it climbs again, in small steps: ${hold.watts}")
        assertTrue(hold.watts <= 80 + 5 * 3)
    }

    @Test
    fun `a short dropout is bridged and decides nothing`() {
        val hold = holdAtSteadyState()
        val before = hold.watts
        repeat(10) { hold.tick(null) }
        assertEquals(before, hold.watts)
        assertFalse(hold.signalLost)
        hold.tick(100)
        assertEquals(0, hold.secondsWithoutHeartRate)
    }

    // endregion

    // region the target, the band, the gear buttons

    @Test
    fun `the gear buttons move the target by 2 bpm, within a limit`() {
        val hold = HeartRateHold(STEP)
        assertEquals(130, hold.targetBpm)
        assertTrue(hold.adjustTarget(+1))
        assertEquals(132, hold.targetBpm)
        assertTrue(hold.adjustTarget(-1))
        assertTrue(hold.adjustTarget(-1))
        assertEquals(128, hold.targetBpm)
        assertEquals(-2, hold.targetOffsetBpm)
        repeat(20) { hold.adjustTarget(+1) }
        assertEquals(150, hold.targetBpm, "20 bpm is the most the buttons can move it")
        assertFalse(hold.adjustTarget(+1), "at the end of the range")
        assertEquals(150, hold.state().targetBpm)
    }

    @Test
    fun `the rider's adjustment carries over to the next heart-rate step`() {
        val first = HeartRateHold(STEP)
        first.adjustTarget(-3)
        val next = HeartRateHold(STEP, targetOffsetBpm = first.targetOffsetBpm)
        assertEquals(124, next.targetBpm)
    }

    @Test
    fun `the controller aims at the adjusted target`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0)
        val hold = HeartRateHold(HeartRateTarget(130, 5, 80, 220), settings)
        hold.adjustTarget(-5) // 120
        repeat(200) { hold.tick(125) } // 5 bpm above the adjusted target: it must back off, not hold
        assertEquals(80, hold.watts)
    }

    @Test
    fun `in band is within the band either side of the target, edges included`() {
        val hold = HeartRateHold(HeartRateTarget(130, 4, 80, 220))
        assertNull(hold.inBand, "no reading yet")
        hold.tick(134)
        assertEquals(true, hold.inBand)
        hold.tick(126)
        assertEquals(true, hold.inBand)
        hold.tick(135)
        assertEquals(false, hold.inBand)
        hold.tick(125)
        assertEquals(false, hold.inBand)
        hold.adjustTarget(-1)
        hold.tick(125)
        assertEquals(true, hold.inBand, "the band moved with the target: 128 +- 4")
        hold.tick(null)
        assertNull(hold.inBand)
    }

    // endregion

    // region resume and freeze

    @Test
    fun `after a resume the filter starts over and nothing is decided until its window is full`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0)
        val hold = HeartRateHold(HeartRateTarget(150, 5, 80, 220), settings)
        repeat(100) { hold.tick(120) }
        val before = hold.watts
        assertTrue(before > 80)
        hold.resume()
        repeat(30) { hold.tick(100) }
        assertEquals(before, hold.watts, "the heart rate fell while the rider rested; it is not judged for 30 s")
        repeat(10) { hold.tick(100) }
        assertTrue(hold.watts > before)
    }

    @Test
    fun `frozen it decides nothing but the filter keeps following`() {
        val settings = HeartRateHoldSettings(startRampSeconds = 0)
        val hold = HeartRateHold(HeartRateTarget(150, 5, 80, 220), settings)
        repeat(100) { hold.tick(120, frozen = true) }
        assertEquals(80, hold.watts)
        repeat(11) { hold.tick(120) }
        assertTrue(hold.watts > 80, "a decision at once when thawed: the window was already full")
    }

    // endregion

    @Test
    fun `settings and the step are checked`() {
        assertFailsWith<IllegalArgumentException> { HeartRateHoldSettings(decisionIntervalSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { HeartRateHoldSettings(gainWattsPerBpm = -1.0) }
        assertFailsWith<IllegalArgumentException> {
            HeartRateHoldSettings(
                signalHoldSeconds = 30,
                signalDropSeconds = 20,
            )
        }
        assertFailsWith<IllegalArgumentException> { HeartRateHoldSettings(startRampFraction = 1.5) }
        assertFailsWith<IllegalArgumentException> { HeartRateTarget(130, 5, 200, 100) }
        val defaults = HeartRateHoldSettings()
        assertEquals(180, defaults.startRampSeconds)
        assertEquals(10, defaults.decisionIntervalSeconds)
        assertEquals(15, defaults.signalHoldSeconds)
        assertEquals(60, defaults.signalDropSeconds)
        assertEquals(2, defaults.targetStepBpm)
    }
}
