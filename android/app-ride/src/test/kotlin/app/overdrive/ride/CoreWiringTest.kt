package app.overdrive.ride

import app.overdrive.core.workout.Workout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the Android build resolves the shared `core` build and can call into it. */
class CoreWiringTest {
    @Test
    fun seesTheSharedCore() {
        assertEquals(1, Workout.SCHEMA_VERSION)
    }
}
