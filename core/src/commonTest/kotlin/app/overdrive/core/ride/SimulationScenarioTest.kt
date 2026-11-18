package app.overdrive.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulationScenarioTest {
    @Test
    fun `the defaults follow the spec`() {
        val scenario = SimulationScenario()
        assertEquals(24, scenario.gearCount)
        assertEquals(12, scenario.neutralGear)
        assertTrue(scenario.dropouts.isEmpty())
    }

    @Test
    fun `a dropout covers its seconds and nothing else`() {
        val dropout = SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 10, durationSeconds = 3)
        assertFalse(dropout.covers(9, SimulatedSignal.HEART_RATE))
        assertTrue(dropout.covers(10, SimulatedSignal.HEART_RATE))
        assertTrue(dropout.covers(12, SimulatedSignal.HEART_RATE))
        assertFalse(dropout.covers(13, SimulatedSignal.HEART_RATE))
        assertFalse(dropout.covers(11, SimulatedSignal.POWER))
    }

    @Test
    fun `invalid dropouts are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SignalDropout(
                SimulatedSignal.POWER,
                startSecond = 0,
                durationSeconds = 5,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignalDropout(
                SimulatedSignal.POWER,
                startSecond = 5,
                durationSeconds = 0,
            )
        }
    }

    @Test
    fun `invalid scenarios are rejected`() {
        assertFailsWith<IllegalArgumentException> { SimulationScenario(gearCount = 0) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(gearCount = 10, neutralGear = 11) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(gearCount = 10, neutralGear = 0) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(powerPerGear = -0.1) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(freeRideFtpFraction = 0.0) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(cadenceRpm = 0.0) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(restingHeartRate = 170, heartRateAtFtp = 170) }
        assertFailsWith<IllegalArgumentException> { SimulationScenario(noise = -1.0) }
    }
}
