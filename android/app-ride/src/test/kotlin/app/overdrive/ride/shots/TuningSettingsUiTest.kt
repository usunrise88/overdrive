package app.overdrive.ride.shots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.overdrive.core.profile.RiderWeight
import app.overdrive.ride.profile.ProfileUi
import app.overdrive.ride.profile.ValueSource
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.RideTuning
import app.overdrive.ride.settings.TuningFields
import app.overdrive.ride.settings.TuningGroup
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The engine's tuning numbers and the weights on the Settings screen (docs/adr/0034, design/system.md §6.6): each number a row with a plain
 * label, its unit and its default; the steppers cannot leave their rule; Reset puts a group back; the overlay's expansion has its switch; the
 * mass says where it comes from. Pictures in `build/shots/`, never committed.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class TuningSettingsUiTest : ShotTest() {
    private var stored by mutableStateOf(AppSettings())
    private var rider by mutableStateOf(AppSettings())

    private fun content(
        initial: AppSettings = AppSettings(),
        profile: ProfileUi = ProfileUi(),
    ) {
        stored = initial
        rider = initial
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    rider.copy(tuning = stored.tuning, overlayExpandsAtSteps = stored.overlayExpandsAtSteps),
                    SettingsActions(
                        onBack = {},
                        onChange = { change -> stored = change(stored) },
                        onResetOverlayPosition = {},
                        onRiderChange = { change -> rider = change(rider) },
                    ),
                    profile = profile,
                )
            }
        }
    }

    @Test
    fun everyTuningNumberIsARowWithItsLabelItsUnitAndItsDefault() {
        content()
        for (field in TuningFields.all) {
            rule.onNodeWithText(field.label).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("${field.note}. Default ${field.defaultText()}").assertExists()
        }
        for (group in TuningGroup.entries) rule.onNodeWithText(group.title).assertExists()
    }

    @Test
    fun theCadenceGuardHasASwitchAndAFloorWithTheirDefaults() {
        content()
        rule.onNodeWithContentDescription("Cadence guard").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("55 rpm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Cadence guard").performClick()
        assertFalse(stored.tuning.stallGuardEnabled)
        rule.onNodeWithContentDescription("Increase Cadence floor").performScrollTo().performClick()
        assertEquals(56, stored.tuning.stallGuardFloorRpm)
        rule.onNodeWithContentDescription("Decrease Cadence floor").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Decrease Cadence floor").performScrollTo().performClick()
        assertEquals(54, stored.tuning.stallGuardFloorRpm)
    }

    @Test
    fun aStepperStopsAtTheEndOfItsRule() {
        content(AppSettings(tuning = RideTuning(stallGuardFloorRpm = 90)))
        rule.onNodeWithContentDescription("Increase Cadence floor").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease Cadence floor").assertIsEnabled()
    }

    @Test
    fun resetPutsOneGroupBackAndIsOffAtTheDefaults() {
        content(AppSettings(tuning = RideTuning(stallGuardFloorRpm = 70, autoPauseTargetWatts = 50)))
        rule.onAllNodesWithText("Reset").filter(isEnabled()).assertCountEquals(2)
        rule.onAllNodesWithText("Reset").filter(isEnabled())[0].performScrollTo().performClick()
        rule.onAllNodesWithText("Reset").filter(isEnabled()).assertCountEquals(1)
        assertTrue(
            "exactly one group is back, the other is left alone",
            (stored.tuning.stallGuardFloorRpm == 55) != (stored.tuning.autoPauseTargetWatts == 25),
        )
        rule.onAllNodesWithText("Reset").filter(isEnabled())[0].performScrollTo().performClick()
        assertEquals(RideTuning(), stored.tuning)
        rule.onAllNodesWithText("Reset").filter(isEnabled()).assertCountEquals(0)
    }

    @Test
    fun theStrapTimesSayWhyAStepIsRefused() {
        content(AppSettings(tuning = RideTuning(hrSignalHoldSeconds = 30, hrSignalDropSeconds = 30)))
        rule
            .onNode(hasText("Not above 30 s (the other strap time).", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
        rule
            .onNodeWithContentDescription(
                "Increase Strap lost: hold power after",
            ).performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun aDecimalStepperMovesByItsStep() {
        content()
        rule.onNodeWithContentDescription("Increase Gain").performScrollTo().performClick()
        assertEquals(0.6, stored.tuning.hrGainWattsPerBpm, 1e-9)
        rule.onNodeWithContentDescription("Decrease Resume at flywheel speed-up").performScrollTo().performClick()
        assertEquals(0.4, stored.tuning.autoResumeFlywheelStepKmh, 1e-9)
    }

    @Test
    fun theOverlayExpansionHasItsSwitchInTheOverlayGroup() {
        content()
        rule.onNodeWithContentDescription("Expand at step changes").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Expand at step changes").performClick()
        assertFalse(stored.overlayExpandsAtSteps)
    }

    @Test
    fun withNoProfileThereIsNoBikeWeightRow() {
        content()
        rule.onAllNodesWithText("Bike and kit weight").assertCountEquals(0)
    }

    @Test
    fun theMassSaysWhereItComesFromAndTheBikeWeightTakesTheStepper() {
        val ui =
            ProfileUi(
                mass = ValueSource.Weight,
                massFromWeights = true,
                massLine = "Rider 78.4 kg (7-day average) + bike 9 kg",
                bikeWeightShown = true,
                bike = ValueSource.Service,
                bikeNote = "Added to your weight for the mass",
            )
        val settings = AppSettings(massKg = 87.4, bikeWeightKg = 9.0, riderWeight = RiderWeight(78.4, "2026-11-18", 5))
        shot("settings--mass-from-weights") {
            OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {}), profile = ui) }
        }
        rule.onNodeWithText("Rider 78.4 kg (7-day average) + bike 9 kg").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("87.4 kg").assertIsDisplayed()
        rule.onNodeWithText("From your weight").assertIsDisplayed()
        // The mass is not a stepper then: the bike weight is.
        rule.onAllNodesWithText("Increase mass").assertCountEquals(0)
        rule.onNodeWithText("9.0 kg").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBikeWeightStartsAtNineAndStepsDownToNotSet() {
        val ui =
            ProfileUi(
                bikeWeightShown = true,
                bikeNote = "Set it to use your weight (78.4 kg, 7-day average) for the mass",
            )
        content(AppSettings(riderWeight = RiderWeight(78.4, "2026-11-18", 5)), ui)
        rule.onNodeWithText("Not set").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Decrease bike weight").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Increase bike weight").performClick()
        assertEquals(9.0, rider.bikeWeightKg)
        rule.onNodeWithContentDescription("Increase bike weight").performClick()
        assertEquals(9.5, rider.bikeWeightKg)
        rule.onNodeWithContentDescription("Decrease bike weight").performClick()
        rule.onNodeWithContentDescription("Decrease bike weight").performClick()
        assertEquals(8.5, rider.bikeWeightKg)
        // Below the lowest it is cleared: the physics mass is in charge again.
        rider = rider.copy(bikeWeightKg = 3.0)
        rule.onNodeWithContentDescription("Decrease bike weight").performClick()
        assertNull(rider.bikeWeightKg)
    }

    @Test
    fun theWholeScreenWithTheNewGroups() {
        shot("settings--tuning") { OverdriveTheme { SettingsScreen(AppSettings(), SettingsActions({}, {}, {})) } }
        rule.onNodeWithText("Cadence guard").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Reset to defaults").fetchSemanticsNodes().size >= 4)
    }
}
