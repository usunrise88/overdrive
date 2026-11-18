package app.overdrive.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RideSnapshotTest {
    private fun snapshot(
        gear: Int? = null,
        intensityPercent: Int = 100,
        neutralGear: Int = 12,
        gearCount: Int = 24,
        ftp: Int = 200,
    ) = RideSnapshot(
        state = RideState.RIDING,
        ftp = ftp,
        gear = gear,
        intensityPercent = intensityPercent,
        neutralGear = neutralGear,
        gearCount = gearCount,
    )

    @Test
    fun `defaults describe an idle display with no data`() {
        val snapshot = RideSnapshot(state = RideState.READY, ftp = 250)
        assertNull(snapshot.power)
        assertNull(snapshot.heartRate)
        assertNull(snapshot.cadence)
        assertNull(snapshot.zone)
        assertNull(snapshot.workout)
        assertEquals(100, snapshot.intensityPercent)
        assertEquals(24, snapshot.gearCount)
        assertEquals(12, snapshot.neutralGear)
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { snapshot(ftp = 0) }
        assertFailsWith<IllegalArgumentException> { snapshot(gear = 0) }
        assertFailsWith<IllegalArgumentException> { snapshot(gear = 25) }
        assertFailsWith<IllegalArgumentException> { snapshot(neutralGear = 25) }
        assertFailsWith<IllegalArgumentException> { snapshot(gearCount = 0, neutralGear = 1) }
        assertFailsWith<IllegalArgumentException> { snapshot(intensityPercent = 45) }
        assertFailsWith<IllegalArgumentException> { snapshot(intensityPercent = 155) }
        assertFailsWith<IllegalArgumentException> { snapshot(intensityPercent = 101) }
        assertFailsWith<IllegalArgumentException> {
            RideSnapshot(
                state = RideState.RIDING,
                ftp = 200,
                elapsedSeconds = -1,
            )
        }
    }

    @Test
    fun `boundary values are accepted`() {
        snapshot(gear = 1)
        snapshot(gear = 24)
        snapshot(intensityPercent = 50)
        snapshot(intensityPercent = 150)
    }

    @Test
    fun `the default neutral gear is the middle one with the extra gear on the hard side`() {
        assertEquals(12, RideSnapshot.DEFAULT_NEUTRAL_GEAR)
        assertEquals(12, SimulationScenario().neutralGear)
        assertEquals(3, SimulationScenario(gearCount = 5).neutralGear)
        assertEquals(2, SimulationScenario(gearCount = 3).neutralGear)
        assertEquals(1, SimulationScenario(gearCount = 1).neutralGear)
    }
}
