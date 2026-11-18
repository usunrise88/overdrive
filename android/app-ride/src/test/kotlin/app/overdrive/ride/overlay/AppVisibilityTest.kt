package app.overdrive.ride.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app counts as visible while any activity is started; the overlay window follows that. */
class AppVisibilityTest {
    private val visibility = AppVisibility()

    @Test
    fun startsHidden() = assertFalse(visibility.visible.value)

    @Test
    fun anActivityThatStartsMakesTheAppVisibleAndStoppingItHidesIt() {
        visibility.activityStarted()
        assertTrue(visibility.visible.value)
        visibility.activityStopped()
        assertFalse(visibility.visible.value)
    }

    @Test
    fun aRecreatedActivityStartsBeforeTheOldOneStopsOnSomeDevicesAndStaysVisible() {
        visibility.activityStarted()
        visibility.activityStarted()
        visibility.activityStopped()
        assertTrue(visibility.visible.value)
        visibility.activityStopped()
        assertFalse(visibility.visible.value)
    }

    @Test
    fun aStrayStopNeverGoesBelowZero() {
        visibility.activityStopped()
        assertFalse(visibility.visible.value)
        visibility.activityStarted()
        assertTrue(visibility.visible.value)
    }
}
