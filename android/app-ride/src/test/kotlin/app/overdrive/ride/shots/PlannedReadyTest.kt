package app.overdrive.ride.shots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.overdrive.core.plan.PlanProblem
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Step
import app.overdrive.core.workout.Workout
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.rides.RiddenWorkout
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.uikit.OverdriveTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The Ready screen's **Planned** section (spec §5.7, ADR 0017) as the rider meets it: what it shows, what a tap or a key does,
 * what is collapsed, disabled or absent, and that every control can be reached and used by moving focus (a keyboard or any
 * controller that does). Robolectric, no emulator, synthetic workouts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class PlannedReadyTest : ShotTest() {
    private var inputModes: InputModeManager? = null
    private val zone = ZoneId.of("Europe/Paris")
    private val today = "2026-11-18"
    private val now = LocalDateTime.parse("2026-11-18T09:00:00").atZone(zone).toInstant().toEpochMilli()
    private val settings = AppSettings(ftp = 250)

    private fun workout(
        id: String,
        name: String,
        description: String? = null,
        steps: List<Step> = listOf(SteadyStep(duration = 2700, target = 0.55)),
    ) = Workout(1, id, name, description, steps)

    private val sweetSpot =
        workout(
            "icu-1001",
            "Over-under 3x10",
            "Three 10-minute efforts with 5-minute recoveries.",
            listOf(
                RampStep(duration = 600, from = 0.45, to = 0.7),
                RepeatStep(
                    count = 3,
                    steps = listOf(SteadyStep(duration = 600, target = 0.88), SteadyStep(duration = 300, target = 0.5)),
                ),
                RampStep(duration = 300, from = 0.55, to = 0.4),
            ),
        )
    private val threshold =
        workout(
            "icu-1002",
            "Threshold 2x20",
            "Two 20-minute efforts at threshold.",
            listOf(
                SteadyStep(duration = 900, target = 0.6),
                RepeatStep(
                    2,
                    listOf(SteadyStep(duration = 1200, target = 0.95), SteadyStep(duration = 300, target = 0.5)),
                ),
            ),
        )
    private val epic = workout("icu-1003", "Epic day", null, listOf(FreeStep(duration = 13 * 3600)))
    private val spin = workout("icu-1004", "Easy spin")
    private val tempo = workout("icu-1005", "Tempo 2x15", "Two tempo blocks.")

    private fun planned(
        date: String,
        workout: Workout,
    ) = PlannedWorkout(workout.id, date, workout.name, PlanSource.INTERVALS_ICU, workout)

    private val allWorkouts =
        listOf(
            planned(today, sweetSpot),
            planned(today, threshold),
            planned(today, epic),
            planned("2026-11-19", spin),
            planned("2026-11-22", tempo),
        )

    private fun plan(
        state: PlanState =
            PlanState(
                visible = true,
                workouts = allWorkouts,
                generatedAt = "2026-11-18T06:30:00Z",
                loaded = true,
            ),
        ridden: List<RiddenWorkout> = emptyList(),
    ) = PlanUi.of(state, ridden, today, now, zone, Locale.ENGLISH)

    private val rodeSweetSpot =
        listOf(
            RiddenWorkout(
                "icu-1001",
                LocalDateTime.parse("2026-11-18T07:45:00").atZone(zone).toInstant().toEpochMilli(),
            ),
        )

    private class Chosen {
        var choice: RideChoice = RideChoice.Free
        var refreshes = 0
    }

    @Composable
    private fun Ready(
        plan: PlanUi,
        chosen: Chosen,
    ) {
        var choice by remember { mutableStateOf(chosen.choice) }
        inputModes = LocalInputModeManager.current
        OverdriveTheme {
            ReadyScreen(
                choice = choice,
                workouts = TestData.library.workouts.value,
                settings = settings,
                devices = TestData.devicesOk,
                startEnabled = true,
                openError = null,
                topBar = TopBarActions({}, {}, {}),
                actions =
                    ReadyActions(
                        onSelect = {
                            choice = it
                            chosen.choice = it
                        },
                        onStart = {},
                        onOpenFile = {},
                        onDismissError = {},
                        onRefreshPlan = { chosen.refreshes++ },
                    ),
                plan = plan,
            )
        }
    }

    /** The row (the one control) of a workout, not the detail panel that may carry the same words. */
    private fun row(name: String) = rule.onNode(hasText(name, substring = true) and hasClickAction())

    // region what is shown

    @Test
    fun theSectionShowsTodayAndKeepsTheWeekCollapsed() {
        val chosen = Chosen()
        shot("ride--ready-planned") { Ready(plan(ridden = rodeSweetSpot), chosen) }
        rule.onNodeWithText("Planned").assertExists()
        rule.onNodeWithText("Updated 07:30").assertExists()
        row("Over-under 3x10").assertExists()
        row("Threshold 2x20").assertExists()
        rule.onNodeWithText("60 min").assertExists()
        rule.onNodeWithText("IF 0.76").assertExists()
        rule.onNodeWithText("Done 07:45 · Three 10-minute efforts with 5-minute recoveries.").assertExists()
        rule.onNodeWithText("Longer than 12 hours").assertExists()
        rule.onNodeWithText("This week").assertExists()
        rule.onNodeWithText("2 workouts").assertExists()
        rule.onNodeWithText("Easy spin").assertDoesNotExist()
        rule.onNodeWithText("Tempo 2x15").assertDoesNotExist()
        // The local list is still there and unchanged beneath it.
        rule.onNodeWithText("Simulation on the flat · gears").assertExists()
        rule.onNodeWithText("Open workout file").assertExists()
    }

    @Test
    fun theWeekOpensByItsRowAndClosesAgain() {
        val chosen = Chosen()
        shot("ride--ready-planned-week") { Ready(plan(), chosen) }
        val toggle = rule.onNode(hasStateDescription("Collapsed"))
        toggle.performClick()
        rule.onNode(hasStateDescription("Expanded")).assertExists()
        rule.onNodeWithText("Tomorrow").assertExists()
        rule.onNodeWithText("Easy spin").assertExists()
        rule.onNodeWithText("Sun 22 Nov").assertExists()
        rule.onNodeWithText("Tempo 2x15").assertExists()
        rule.onNode(hasStateDescription("Expanded")).performClick()
        rule.onNodeWithText("Easy spin").assertDoesNotExist()
    }

    @Test
    fun nothingPlannedTodayIsOneQuietLine() {
        val chosen = Chosen()
        rule.setContent {
            Ready(
                plan(PlanState(visible = true, loaded = true, generatedAt = "2026-11-18T06:30:00Z")),
                chosen,
            )
        }
        rule.onNodeWithText("Nothing planned today").assertExists()
        rule.onNodeWithText("This week").assertDoesNotExist()
    }

    @Test
    fun withNoPlanTheSectionIsNotThereAndNothingIsSaidAboutIt() {
        val chosen = Chosen()
        shot("ride--ready-no-plan") { Ready(PlanUi.Hidden, chosen) }
        rule.onNodeWithText("Planned").assertDoesNotExist()
        rule.onNodeWithContentDescription("Refresh planned workouts").assertDoesNotExist()
        rule.onNodeWithText("Simulation on the flat · gears").assertExists()
        rule.onNodeWithText("Ride").assertExists()
    }

    @Test
    fun aStaleOrOfflineListSaysSoAndKeepsTheRows() {
        val chosen = Chosen()
        val offline =
            PlanState(
                visible = true,
                workouts = allWorkouts,
                generatedAt = "2026-11-17T20:15:00Z",
                loaded = true,
                stale = true,
                problem = PlanProblem.Failed("Service unreachable"),
            )
        shot("ride--ready-planned-offline") { Ready(plan(offline), chosen) }
        rule.onNodeWithText("Out of date · updated 17 Nov 21:15").assertExists()
        rule.onNodeWithText("Service unreachable").assertExists()
        row("Threshold 2x20").assertExists()
    }

    @Test
    fun aRejectedTokenSaysItInTheWordsOfTheSyncSettings() {
        val chosen = Chosen()
        val rejected = PlanState(visible = true, loaded = false, problem = PlanProblem.TokenRejected)
        shot("ride--ready-planned-token") { Ready(plan(rejected), chosen) }
        rule.onNodeWithText("The service did not accept the token. Check the token in Settings").assertExists()
    }

    // endregion

    // region what a tap does

    @Test
    fun tappingARowChoosesThatWorkoutAndMarksItSelected() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        row("Threshold 2x20").performClick()
        val choice = chosen.choice as RideChoice.Planned
        assertEquals("icu-1002", choice.workout.id)
        assertEquals(threshold, choice.workout)
        row("Threshold 2x20").assertIsSelected()
        row("Over-under 3x10").assertExists()
        // The right-hand panel is the existing workout detail, for the chosen workout.
        rule.onAllNodesWithText("Two 20-minute efforts at threshold.", substring = true).assertCountEquals(2)
    }

    @Test
    fun aDoneWorkoutCanBeRiddenAgain() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(ridden = rodeSweetSpot), chosen) }
        row("Done 07:45").assertIsEnabled()
        row("Done 07:45").performClick()
        assertEquals("icu-1001", (chosen.choice as RideChoice.Planned).workout.id)
    }

    @Test
    fun aWorkoutThePlayerCannotRunIsDisabledWithItsReasonAndNeverChosen() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        row("Epic day").assertIsNotEnabled()
        row("Epic day").performClick()
        assertEquals(RideChoice.Free, chosen.choice)
        rule.onNodeWithText("Longer than 12 hours").assertExists()
    }

    @Test
    fun theRefreshButtonAsksForThePlan() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        rule.onNodeWithContentDescription("Refresh planned workouts").assertIsEnabled().performClick()
        assertEquals(1, chosen.refreshes)
    }

    @Test
    fun theRefreshButtonRestsWhileTheServiceIsBeingAsked() {
        val chosen = Chosen()
        val asking = PlanState(visible = true, loaded = true, generatedAt = "2026-11-18T06:30:00Z", refreshing = true)
        rule.setContent { Ready(plan(asking), chosen) }
        rule.onNodeWithText("Updating…").assertExists()
        rule.onNodeWithContentDescription("Refresh planned workouts").assertIsNotEnabled()
    }

    @Test
    fun theLocalWorkoutsStillChooseAndStillShowNoPlannedRow() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        row(TestData.sweetSpot.name).performClick()
        assertEquals(TestData.sweetSpot.id, (chosen.choice as RideChoice.Planned).workout.id)
        rule.onNodeWithText("Simulation on the flat · gears").performClick()
        assertEquals(RideChoice.Free, chosen.choice)
    }

    // endregion

    // region reaching it by moving focus

    /**
     * Compose lets clickable things take focus only in keyboard input mode, which a first hardware key (Tab, an arrow, the
     * d-pad) switches on. The test says "a key was pressed" the same way before it moves focus.
     */
    private fun keyboardMode() {
        rule.runOnUiThread { checkNotNull(inputModes).requestInputMode(InputMode.Keyboard) }
        rule.waitForIdle()
    }

    private fun focusRow(name: String) = row(name).performSemanticsAction(SemanticsActions.RequestFocus)

    private fun focusRefresh() =
        rule
            .onNodeWithContentDescription(
                "Refresh planned workouts",
            ).performSemanticsAction(SemanticsActions.RequestFocus)

    @Test
    fun focusWalksTheSectionTopToBottomAndTheKeyThatActivatesAFocusedControlUsesIt() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        keyboardMode()
        focusRefresh()
        rule.onNodeWithContentDescription("Refresh planned workouts").assertIsFocused()

        fun tab() = rule.onNodeWithContentDescription("Refresh planned workouts").performKeyInput { pressKey(Key.Tab) }

        // Tab moves to the next control: today's rows in order, then the week row.
        tab()
        row("Over-under 3x10").assertIsFocused()
        row("Over-under 3x10").performKeyInput { pressKey(Key.Tab) }
        row("Threshold 2x20").assertIsFocused()
        // The disabled row takes no focus, so the next stop after Threshold is the week row.
        row("Threshold 2x20").performKeyInput { pressKey(Key.Tab) }
        row("Epic day").assertIsNotEnabled()
        rule.onNode(hasStateDescription("Collapsed")).assertIsFocused()

        // Enter on the week row opens it; the d-pad centre does the same.
        rule.onNode(hasStateDescription("Collapsed")).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Easy spin").assertExists()
        rule.onNode(hasStateDescription("Expanded")).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Easy spin").assertDoesNotExist()

        // And a row is chosen from the keyboard like a tap.
        focusRow("Threshold 2x20")
        row("Threshold 2x20").performKeyInput { pressKey(Key.Enter) }
        assertEquals("icu-1002", (chosen.choice as RideChoice.Planned).workout.id)
        assertEquals("focus and Enter on rows never refreshed", 0, chosen.refreshes)
    }

    @Test
    fun theRefreshButtonCanBeUsedFromTheKeyboardToo() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(), chosen) }
        keyboardMode()
        focusRefresh()
        rule.onNodeWithContentDescription("Refresh planned workouts").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, chosen.refreshes)
    }

    @Test
    fun aFocusedRowShowsItsRing() {
        val chosen = Chosen()
        rule.setContent { Ready(plan(ridden = rodeSweetSpot), chosen) }
        keyboardMode()
        focusRow("Threshold 2x20")
        rule.waitForIdle()
        row("Threshold 2x20").assertIsFocused()
        rule.onRoot().captureRoboImage("build/shots/ride--ready-planned-focus-ring.png")
    }

    // endregion
}
