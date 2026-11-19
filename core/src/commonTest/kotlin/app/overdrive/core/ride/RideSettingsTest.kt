package app.overdrive.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RideSettingsTest {
    @Test
    fun `the defaults of the resume settings are those of docs-adr-0032`() {
        val settings = RideSettings()
        assertEquals(25, settings.autoPauseTargetWatts)
        assertEquals(10, settings.autoResumePowerWatts)
        assertEquals(0.5, settings.autoResumeFlywheelStepKmh)
        assertEquals(0, settings.pausedTargetWatts, "a manual pause keeps its 0 W")
        assertEquals(3, settings.rampInSeconds)
    }

    @Test
    fun `the auto-pause target is within 0 and the limit`() {
        RideSettings(autoPauseTargetWatts = 0)
        RideSettings(autoPauseTargetWatts = 1500)
        assertFailsWith<IllegalArgumentException> { RideSettings(autoPauseTargetWatts = -1) }
        assertFailsWith<IllegalArgumentException> { RideSettings(autoPauseTargetWatts = 1501) }
        RideSettings(maxTargetWatts = 100, autoPauseTargetWatts = 100)
        assertFailsWith<IllegalArgumentException> { RideSettings(maxTargetWatts = 100, autoPauseTargetWatts = 101) }
    }

    @Test
    fun `the resume power is within 0 and the limit, and 0 is off`() {
        RideSettings(autoResumePowerWatts = 0)
        RideSettings(autoResumePowerWatts = 1500)
        assertFailsWith<IllegalArgumentException> { RideSettings(autoResumePowerWatts = -1) }
        assertFailsWith<IllegalArgumentException> { RideSettings(autoResumePowerWatts = 1501) }
    }

    @Test
    fun `the flywheel step is a finite number of km-h from 0, and 0 is off`() {
        RideSettings(autoResumeFlywheelStepKmh = 0.0)
        RideSettings(autoResumeFlywheelStepKmh = 12.5)
        assertFailsWith<IllegalArgumentException> { RideSettings(autoResumeFlywheelStepKmh = -0.1) }
        assertFailsWith<IllegalArgumentException> { RideSettings(autoResumeFlywheelStepKmh = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { RideSettings(autoResumeFlywheelStepKmh = Double.POSITIVE_INFINITY) }
    }
}
