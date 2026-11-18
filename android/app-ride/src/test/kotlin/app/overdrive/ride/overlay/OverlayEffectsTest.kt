package app.overdrive.ride.overlay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.overdrive.ride.ui.EnsureOverlayService
import app.overdrive.ride.ui.rememberOverlayAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stand-in for the Android side of the overlay that the activity uses: records calls, answers with what the test says. */
class FakeOverlayController(
    var access: OverlayAccess = OverlayAccess.Granted,
) : OverlayController {
    val calls = mutableListOf<String>()

    override fun access(): OverlayAccess = access

    override fun openOverlaySettings() {
        calls += "openOverlaySettings"
    }

    override fun openNotificationSettings() {
        calls += "openNotificationSettings"
    }

    override fun ensureRunning() {
        calls += "ensureRunning"
    }
}

/** The two things the ride screen does about the overlay: re-read the permissions on resume, start the service when live. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayEffectsTest {
    @get:Rule
    val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>()

    private fun leaveAndReturn() {
        val scenario = rule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED) // pause and stop: another app is in front
        scenario.moveToState(Lifecycle.State.RESUMED) // and back
        rule.waitForIdle()
    }

    @Test
    fun theAccessIsReadAgainWhenTheRiderComesBackFromTheSystemScreen() {
        val overlay =
            FakeOverlayController(
                OverlayAccess(canDrawOverlays = false, notificationsAllowed = true, blurAvailable = true),
            )
        rule.setContent {
            val access = rememberOverlayAccess(overlay)
            BasicText(if (access.canDrawOverlays) "granted" else "missing")
        }
        rule.onNodeWithText("missing").assertExists()

        // The rider allows it on the system screen; nothing is reported back, the app just resumes.
        overlay.access = OverlayAccess.Granted
        leaveAndReturn()
        rule.onNodeWithText("granted").assertExists()
    }

    @Test
    fun theServiceIsStartedFromTheVisibleScreenOnlyWhileTheRideIsLive() {
        val overlay = FakeOverlayController()
        var live by mutableStateOf(false)
        rule.setContent { EnsureOverlayService(live, overlay) }
        rule.waitForIdle()
        assertEquals("no ride, no service", emptyList<String>(), overlay.calls)

        live = true
        rule.waitForIdle()
        assertEquals(listOf("ensureRunning"), overlay.calls)

        // Back from another app during the ride: the service is made sure of again (the system may have stopped it).
        leaveAndReturn()
        assertEquals(listOf("ensureRunning", "ensureRunning"), overlay.calls)

        live = false
        rule.waitForIdle()
        leaveAndReturn()
        assertEquals("a finished ride asks for nothing", 2, overlay.calls.size)
    }
}
