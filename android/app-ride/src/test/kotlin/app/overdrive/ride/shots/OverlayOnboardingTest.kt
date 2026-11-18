package app.overdrive.ride.shots

import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.overlay.OverlayAccess
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.AllowStep
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.ride.ui.allowStep
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** The permission onboarding of the overlay (ADR 0006): the row on the Ready screen and the rows in Settings. */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class OverlayOnboardingTest : ShotTest() {
    private val topBar = TopBarActions({}, {}, {})
    private val settings = AppSettings(ftp = 250, massKg = 82.0, cdA = 0.32)
    private val workouts = TestData.library.workouts.value
    private val missing = OverlayAccess(canDrawOverlays = false, notificationsAllowed = false, blurAvailable = true)

    private fun ready(
        access: OverlayAccess,
        onAllow: () -> Unit = {},
    ) = rule.setContent {
        OverdriveTheme {
            ReadyScreen(
                choice = RideChoice.Planned(TestData.sweetSpot),
                workouts = workouts,
                settings = settings,
                devices = TestData.devicesOk,
                startEnabled = true,
                openError = null,
                topBar = topBar,
                actions = ReadyActions({}, {}, {}, {}, onAllowOverlay = onAllow),
                overlayAccess = access,
            )
        }
    }

    @Test
    fun readyScreenAsksForTheOverlayPermission() =
        shot("ride--ready-overlay-permission") {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices = TestData.devicesOk,
                    startEnabled = true,
                    openError = null,
                    topBar = topBar,
                    actions = ReadyActions({}, {}, {}, {}),
                    overlayAccess = missing,
                )
            }
        }

    @Test
    fun settingsShowsWhatIsMissing() =
        shot("settings--overlay-permission") {
            OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {}), overlayAccess = missing) }
        }

    @Test
    fun theReadyRowOffersAllowAndCallsTheAction() {
        var allowed = 0
        ready(missing) { allowed++ }
        rule.onNodeWithText("Show numbers over other apps").assertIsDisplayed()
        rule.onNodeWithText("Allow", substring = false).performClick()
        assertEquals(1, allowed)
    }

    @Test
    fun theReadyRowIsGoneOnceThePermissionIsGranted() {
        ready(OverlayAccess(canDrawOverlays = true, notificationsAllowed = false, blurAvailable = true))
        rule.onAllNodesWithText("Show numbers over other apps").assertCountEquals(0)
        // The rest of the screen is untouched.
        rule.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun settingsOpensTheOverlayAndNotificationScreens() {
        val calls = mutableListOf<String>()
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    settings,
                    SettingsActions(
                        onBack = {},
                        onChange = {},
                        onResetOverlayPosition = {},
                        onOpenOverlaySettings = { calls += "overlay" },
                        onOpenNotificationSettings = { calls += "notifications" },
                    ),
                    overlayAccess = missing,
                )
            }
        }
        rule.onNodeWithText("Display over other apps").assertIsDisplayed()
        rule.onAllNodes(hasText("Allow") and hasClickAction())[0].performClick()
        rule.onAllNodes(hasText("Allow") and hasClickAction())[1].performClick()
        assertEquals(listOf("overlay", "notifications"), calls)
    }

    @Test
    fun settingsOffersManageOnceTheOverlayIsAllowed() {
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(settings, SettingsActions({}, {}, {}), overlayAccess = OverlayAccess.Granted)
            }
        }
        rule.onNodeWithText("Manage").assertIsDisplayed()
        rule.onAllNodesWithText("Allow").assertCountEquals(0)
    }

    @Test
    fun theBlurSwitchIsHiddenWhereTheSystemCannotBlur() {
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    settings,
                    SettingsActions({}, {}, {}),
                    overlayAccess =
                        OverlayAccess(
                            canDrawOverlays = true,
                            notificationsAllowed = true,
                            blurAvailable = false,
                        ),
                )
            }
        }
        rule.onAllNodesWithText("Blur the video behind").assertCountEquals(0)
    }

    @Test
    fun theNotificationQuestionComesFirstOnlyOnAndroid13AndLater() {
        val noNotifications = OverlayAccess(canDrawOverlays = false, notificationsAllowed = false, blurAvailable = true)
        assertEquals(AllowStep.AskNotifications, allowStep(noNotifications, Build.VERSION_CODES.TIRAMISU))
        assertEquals(AllowStep.AskNotifications, allowStep(noNotifications, Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertEquals(AllowStep.OpenOverlaySettings, allowStep(noNotifications, Build.VERSION_CODES.S_V2))
        // Already allowed: straight to the overlay screen.
        assertEquals(
            AllowStep.OpenOverlaySettings,
            allowStep(noNotifications.copy(notificationsAllowed = true), Build.VERSION_CODES.VANILLA_ICE_CREAM),
        )
    }
}
