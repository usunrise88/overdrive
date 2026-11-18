package app.overdrive.ride.overlay

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ride.RideState
import app.overdrive.ride.MainActivity
import app.overdrive.ride.settings.OverlayStartMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.util.concurrent.TimeUnit

/**
 * The overlay service end to end against Robolectric: it starts in the foreground, shows exactly one window while a ride
 * is live and the app is hidden, and stops by its own rules. The ride is a [FakeOverlayModel] the test drives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi", application = FakeOverlayApp::class)
class OverlayServiceTest {
    private val app: FakeOverlayApp = ApplicationProvider.getApplicationContext()
    private val model = app.model
    private val windowManager: WindowManager = (app as Context).getSystemService(WindowManager::class.java)

    private fun views(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(windowManager).views

    /** Lets the main looper run, advancing its clock a little each round, until [condition] holds (the ride's state is mapped on a background thread). */
    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $what")
    }

    private fun startService() = Robolectric.buildService(OverlayService::class.java).create()

    @Test
    fun startsInTheForegroundWithTheRideNotification() {
        val service = startService().get()
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Ride in progress", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(OverlayNotification.CHANNEL_ID, notification.channelId)
        assertNotNull("tapping the notification opens the app", notification.contentIntent)
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            shadowOf(notification.contentIntent).savedIntent.component,
        )
        assertEquals(0, views().size)
    }

    @Test
    fun showsExactlyOneWindowWhileARideIsLiveAndTheAppIsHidden() {
        val service = startService().get()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }
        // More ride updates and more settling change nothing about the window count.
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING, power = 230)
        model.rides.value = FakeOverlayModel.ride(RideState.PAUSED, power = null)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, views().size)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun theWindowGoesWhenTheAppComesBackAndReturnsWhenItLeavesAgain() {
        startService()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }

        model.appVisible.value = true
        awaitUntil("the window to go") { views().isEmpty() }

        model.appVisible.value = false
        awaitUntil("the window again") { views().size == 1 }
    }

    @Test
    fun aShortLeaveDoesNotFlashTheWindow() {
        startService()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        model.appVisible.value = false // the activity is being recreated...
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        model.appVisible.value = true // ...and is back
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, views().size)
    }

    @Test
    fun noWindowWithoutALiveRide() {
        startService()
        model.rides.value = FakeOverlayModel.ride(RideState.READY)
        model.appVisible.value = false
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, views().size)
    }

    @Test
    fun stopsAndRemovesTheWindowWhenTheRideIsDismissedAfterItsSummary() {
        val service = startService().get()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }

        model.rides.value = FakeOverlayModel.ride(RideState.FINISHED)
        awaitUntil("the window to go on finish") { views().isEmpty() }
        assertFalse("the summary has not been seen yet: the notification stays", shadowOf(service).isStoppedBySelf)

        model.rides.value = FakeOverlayModel.ride(RideState.READY) // Done
        awaitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }
        assertEquals(0, views().size)
    }

    @Test
    fun finishingBringsTheAppToTheFrontAndTheNotificationSaysSo() {
        val service = startService().get()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }

        model.rides.value = FakeOverlayModel.ride(RideState.FINISHED)
        awaitUntil("the app to be started") { shadowOf(app as Application).peekNextStartedActivity() != null }
        val launched = shadowOf(app as Application).nextStartedActivity
        assertEquals(ComponentName(app, MainActivity::class.java), launched.component)

        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val posted = shadowOf(manager).getNotification(OverlayNotification.ID)
        assertEquals("Ride finished", posted.extras.getString(android.app.Notification.EXTRA_TITLE))

        // The summary is on screen now.
        model.appVisible.value = true
        awaitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun aServiceWhoseRideNeverStartsStopsAfterTheGracePeriod() {
        val service = startService().get()
        shadowOf(Looper.getMainLooper()).idleFor(14, TimeUnit.SECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun tapTogglesTheModeAndLongPressOpensTheApp() {
        val service = startService().get()
        assertEquals(OverlayStartMode.Compact, model.settings.value.overlayMode)
        service.onTap()
        assertEquals(OverlayStartMode.Detailed, model.settings.value.overlayMode)
        service.onTap()
        assertEquals(OverlayStartMode.Compact, model.settings.value.overlayMode)

        assertNull(shadowOf(app as Application).peekNextStartedActivity())
        service.onLongPress()
        val launched = shadowOf(app as Application).nextStartedActivity
        assertEquals(ComponentName(app, MainActivity::class.java), launched.component)
        assertTrue(launched.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun theSavedPositionGoesToTheSettingsAndTheWindowStartsThere() {
        val service = startService().get()
        service.onPositionChanged(0.0125f, 0.5f)
        assertEquals(0.0125f, model.settings.value.overlayX)
        assertEquals(0.5f, model.settings.value.overlayY)

        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }
        val params = views().single().layoutParams as WindowManager.LayoutParams
        assertEquals(32, params.x) // 16 dp at 2x
        assertEquals(800, params.y)
    }

    @Test
    fun destroyingTheServiceRemovesTheWindow() {
        val controller = startService()
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        model.appVisible.value = false
        awaitUntil("the window") { views().size == 1 }
        controller.destroy()
        assertEquals(0, views().size)
    }
}
