package app.overdrive.ride.shots

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.overdrive.ride.profile.ProfileUi
import app.overdrive.ride.profile.ValueSource
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The rider's profile on the Settings screen (ADR 0021, design/system.md §6.6): the marker beside a value that comes from the
 * service or has not been sent yet, the one line when the service could not be read, and that the steppers of FTP and the physics
 * go through the rider's change (which the container routes to the profile) while every other setting goes the ordinary way.
 * Pictures in `build/shots/`, never committed.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class ProfileSettingsUiTest : ShotTest() {
    private val settings = AppSettings(ftp = 175, massKg = 82.0, cdA = 0.31)

    @Test
    fun withNoProfileThereIsNoMarkerAndNoNote() {
        shot("settings--profile-none") { OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {})) } }
        rule.onAllNodesWithText("From service").assertCountEquals(0)
        rule.onAllNodesWithText("Not sent yet").assertCountEquals(0)
        rule.onNodeWithText("Sets power zones and workout targets").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun valuesFromTheServiceSaySoAndAChangeNotYetSentSaysThat() {
        val ui =
            ProfileUi(
                ftp = ValueSource.NotSent,
                mass = ValueSource.Service,
                cdA = ValueSource.Service,
                crr = ValueSource.Service,
            )
        shot("settings--profile-service") {
            OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {}), profile = ui) }
        }
        rule.onAllNodesWithText("From service").assertCountEquals(3)
        rule.onNodeWithText("Not sent yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aProfileThatCouldNotBeReadIsOneLineInPlaceOfTheFtpNote() {
        val ui = ProfileUi(note = "Profile: Service unreachable · using this tablet's values")
        shot("settings--profile-unreachable") {
            OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {}), profile = ui) }
        }
        rule
            .onNodeWithText(
                "Profile: Service unreachable · using this tablet's values",
            ).performScrollTo()
            .assertIsDisplayed()
        rule.onAllNodesWithText("Sets power zones and workout targets").assertCountEquals(0)
    }

    @Test
    fun theSteppersOfFtpAndPhysicsAreTheRidersChangeAndTheOthersAreTheOrdinaryOne() {
        var rider = settings
        var ordinary = settings
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    rider,
                    SettingsActions(
                        onBack = {},
                        onChange = { change -> ordinary = change(ordinary) },
                        onResetOverlayPosition = {},
                        onRiderChange = { change -> rider = change(rider) },
                    ),
                )
            }
        }
        rule.onNodeWithContentDescription("Increase FTP").performScrollTo().performClick()
        assertEquals(180, rider.ftp)
        rule.onNodeWithContentDescription("Increase mass").performScrollTo().performClick()
        assertEquals(83.0, rider.massKg, 0.0)
        rule.onNodeWithContentDescription("Decrease drag area").performScrollTo().performClick()
        assertEquals(0.30, rider.cdA, 1e-9)
        rule.onNodeWithContentDescription("Increase rolling resistance").performScrollTo().performClick()
        assertEquals(0.0045, rider.crr, 1e-9)
        assertEquals("none of those went the ordinary way", settings, ordinary)

        rule.onNodeWithContentDescription("Increase gears").performScrollTo().performClick()
        assertEquals(settings.gearCount + 1, ordinary.gearCount)
    }
}
