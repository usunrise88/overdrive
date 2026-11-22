package app.overdrive.companion.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.overdrive.companion.ChartScreenSamples
import app.overdrive.companion.Samples
import app.overdrive.companion.model.EffortUi
import app.overdrive.companion.model.PowerSkillsUi
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.ui.EffortCard
import app.overdrive.companion.ui.PowerActions
import app.overdrive.companion.ui.PowerSkillsActions
import app.overdrive.companion.ui.PowerSkillsCard
import app.overdrive.companion.ui.PowerSkillsHost
import app.overdrive.companion.ui.PowerView
import app.overdrive.companion.ui.RangeActions
import app.overdrive.companion.ui.SettingsActions
import app.overdrive.companion.ui.SettingsUiState
import app.overdrive.companion.ui.SettingsView
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.uikit.chart.ChartSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The touch of the two cards on the real composables (design/companion.md §4.14, §4.15, §8): the radar's tap picks the axis by angle, an
 * Effort column selects its week and the verdict follows, TalkBack's week actions, and the Custom range's sheet (opened from the control,
 * applied only by Show, changed by nothing else). Driven from the synthetic answers.
 */
class ChartsInteractionTest : ShotTest() {
    private val words = TestClock.words
    private val today = LocalDate.parse(ChartScreenSamples.TODAY)

    // region the radar

    private val radar = hasContentDescription("Power skills, last 8 weeks", substring = true)

    private fun showCard(
        onAxis: (Int) -> Unit,
        selected: () -> Int? = { null },
    ) {
        val ui = PowerSkillsUi.of(fresh(ChartScreenSamples.skills()), PowerSkillsWindow.EightWeeks, words)
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                Column(Modifier.padding(16.dp)) {
                    PowerSkillsCard(ui, selected, PowerSkillsActions(onAxis = onAxis))
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a tap on the radar picks the axis by angle, wherever in its box`() {
        val picked = mutableListOf<Int>()
        showCard({ picked += it })
        // Right of the plot, level with the centre: 2 min, axis 3. The top and the bottom of the box: axes 0 and 6.
        rule.onNode(radar).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        rule.onNode(radar).performTouchInput { click(Offset(width / 2f, 4f)) }
        rule.onNode(radar).performTouchInput { click(Offset(width / 2f, height - 4f)) }
        rule.onNode(radar).performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        assertEquals(listOf(3, 0, 6, 9), picked)
    }

    @Test
    fun `the radar is one node with the values in its description and no per-axis actions`() {
        showCard({})
        val node = rule.onNode(radar).fetchSemanticsNode()
        assertTrue("no per-axis children", node.children.isEmpty())
        assertTrue(!node.config.contains(SemanticsActions.CustomActions))
    }

    // region Effort

    private val chart = hasContentDescription("Effort, last 12 weeks", substring = true)

    private fun showEffort(selection: ChartSelection) {
        val ui = EffortUi.of(fresh(ChartScreenSamples.effort()), words)
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                Column(Modifier.padding(16.dp)) {
                    EffortCard(ui, selection, onValues = {}, onOpenRow = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a tap on a column selects its week, the verdict and the rows follow, and This week returns to rest`() {
        val selection = ChartSelection()
        showEffort(selection)
        rule.onNodeWithText("Below your usual range so far").assertIsDisplayed()
        // The plot runs from a 32 dp gutter to 6 dp short of the right edge: column 10 is the 11th of 12 slots.
        rule.onNode(chart).performTouchInput {
            val gutter = 32.dp.toPx()
            val slot = (width - gutter - 6.dp.toPx()) / 12f
            click(Offset(gutter + slot * 10.5f, height / 2f))
        }
        assertEquals(10.0, selection.x!!, 0.0)
        rule.onNodeWithText("Within your usual range").assertIsDisplayed()
        rule.onNodeWithText("Threshold 2×20").assertIsDisplayed()
        rule.onNodeWithText("This week").performClick()
        assertNull(selection.x)
        rule.onNodeWithText("Below your usual range so far").assertIsDisplayed()
    }

    @Test
    fun `TalkBack's Previous week and Next week move the selection a week at a time`() {
        val selection = ChartSelection()
        showEffort(selection)

        fun action(label: String) {
            val actions = rule.onNode(chart).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            rule.runOnUiThread { actions.first { it.label == label }.action() }
        }
        action("Previous week")
        assertEquals(10.0, selection.x!!, 0.0)
        action("Previous week")
        assertEquals(9.0, selection.x!!, 0.0)
        action("Next week")
        assertEquals(10.0, selection.x!!, 0.0)
    }

    @Test
    fun `a past week is shown with the pill that goes back to this week and nothing relabels it`() {
        val selection = ChartSelection().also { it.select(10.0) }
        showEffort(selection)
        rule.onNodeWithText("This week").assertIsDisplayed()
        rule.onNodeWithText("Week of 9 Nov, by day").assertIsDisplayed()
    }

    // region the Custom range

    private fun showPower(
        onWindow: (PowerSkillsWindow) -> Unit,
        window: PowerSkillsWindow = PowerSkillsWindow.EightWeeks,
    ) {
        val metrics = Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY))
        val ui =
            PowerUi.of(
                fresh(metrics),
                ProfileState(ready = true, profile = Samples.profile()),
                FtpAnswers(),
                MetricsRange.SIX_MONTHS,
                words,
                "x",
            )
        val response =
            if (window is PowerSkillsWindow.Custom) ChartScreenSamples.customSkills() else ChartScreenSamples.skills()
        val skills =
            PowerSkillsHost(
                PowerSkillsUi.of(fresh(response), window, words),
                listOf("2026-11-02", "2026-11-12").map(LocalDate::parse),
                today,
                words,
                onWindow,
            )
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                PowerView(
                    ui,
                    MetricsRange.SIX_MONTHS,
                    PowerActions(
                        RangeActions({
                        }, {}, {}, {}),
                        {},
                        {},
                        {},
                    ),
                    Modifier.fillMaxSize(),
                    skills = skills,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `Custom opens the sheet, Show applies the two days, and the first and last day are the range`() {
        val applied = mutableListOf<PowerSkillsWindow>()
        showPower({ applied += it })
        rule.onNodeWithText("Custom").performClick()
        rule.onNodeWithText("Custom range").assertIsDisplayed()
        rule.onNodeWithText("Choose the first day").assertIsDisplayed()
        rule.onNode(hasText("Show")).assertIsNotEnabled()
        rule.onNode(hasContentDescription("Monday 2 November", substring = true)).performClick()
        rule.onNodeWithText("From 2 Nov · choose the last day").assertIsDisplayed()
        rule.onNode(hasContentDescription("Sunday 15 November", substring = true)).performClick()
        rule.onNodeWithText("2 Nov – 15 Nov · 14 days · 2 rides").assertIsDisplayed()
        assertTrue("nothing is applied before Show", applied.isEmpty())
        rule.onNodeWithText("Show 2 Nov – 15 Nov").performClick()
        assertEquals(listOf<PowerSkillsWindow>(PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")), applied)
        rule.onNodeWithText("Custom range").assertDoesNotExist()
    }

    @Test
    fun `Cancel changes nothing, and a day after today cannot be chosen`() {
        val applied = mutableListOf<PowerSkillsWindow>()
        showPower({ applied += it })
        rule.onNodeWithText("Custom").performClick()
        rule.onNode(hasContentDescription("Monday 2 November", substring = true)).performClick()
        rule.onNode(hasContentDescription("Thursday 19 November", substring = true)).performClick()
        rule.onNodeWithText("From 2 Nov · choose the last day").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Custom range").assertDoesNotExist()
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `the range line has Change and a button that clears the range back to 8 weeks`() {
        val applied = mutableListOf<PowerSkillsWindow>()
        showPower({ applied += it }, PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"))
        rule.onNodeWithText("2 Nov – 15 Nov 2026 · 9 rides").assertIsDisplayed()
        rule.onNode(hasContentDescription("Clear custom range")).performClick()
        assertEquals(listOf<PowerSkillsWindow>(PowerSkillsWindow.EightWeeks), applied)
        rule.onNodeWithText("Change").performClick()
        // The picker opens on the month of the current end.
        rule.onNodeWithText("November 2026").assertIsDisplayed()
    }

    @Test
    fun `the segments choose 8 weeks and All time at once`() {
        val applied = mutableListOf<PowerSkillsWindow>()
        showPower({ applied += it })
        rule.onNode(hasText("All time") and hasClickAction()).performClick()
        rule.onNode(hasText("8 weeks") and hasClickAction()).performClick()
        assertEquals(listOf(PowerSkillsWindow.AllTime, PowerSkillsWindow.EightWeeks), applied)
    }

    @Test
    fun `a tap on a skill row opens its sheet and a ride in it is opened by id`() {
        val opened = mutableListOf<String>()
        val metrics = Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY))
        val ui =
            PowerUi.of(
                fresh(metrics),
                ProfileState(ready = true, profile = Samples.profile()),
                FtpAnswers(),
                MetricsRange.SIX_MONTHS,
                words,
                "x",
            )
        val skills =
            PowerSkillsHost(
                PowerSkillsUi.of(fresh(ChartScreenSamples.skills()), PowerSkillsWindow.EightWeeks, words),
                emptyList(),
                today,
                words,
            ) {
            }
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                PowerView(
                    ui,
                    MetricsRange.SIX_MONTHS,
                    PowerActions(
                        RangeActions({
                        }, {}, {}, {}),
                        {},
                        {},
                        { opened += it },
                    ),
                    Modifier.fillMaxSize(),
                    skills = skills,
                )
            }
        }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("Climb, 5 to 10 minutes", substring = true)).performClick()
        rule.onNodeWithText("5–10 min · Fit, level 4 of 9 in 8 weeks").assertIsDisplayed()
        rule.onNodeWithText("Tap a value to open its ride.").assertIsDisplayed()
        rule.onNode(hasContentDescription("5 minutes, 316 watts", substring = true)).performClick()
        assertEquals(1, opened.size)
        assertTrue(opened.single(), opened.single().startsWith("ride-2026-11-12"))
    }

    // region Settings

    private val settings =
        SettingsUiState(
            address = "overdrive.example.net",
            addressMistake = null,
            notEncrypted = false,
            token = "tok-3f9a7c1e5b2d4a6f",
            tokenRefused = false,
            lastSyncValue = "Today 07:40",
            lastSyncNote = "Received your latest figures.",
            syncing = false,
            version = "0.4.1",
            powerTable = PowerProfileTable.MEN,
        )

    @Test
    fun `Settings has the power profile table, men's selected by default, and choosing women says so`() {
        val chosen = mutableListOf<PowerProfileTable>()
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                SettingsView(
                    settings,
                    SettingsActions({}, {}, {}, {}, {}, onPowerTable = { chosen += it }),
                )
            }
        }
        rule.onNodeWithText("Power profile table").assertIsDisplayed()
        rule.onNodeWithText("Levels in Power skills are approximate", substring = true).assertIsDisplayed()
        rule.onNode(hasText("Men")).assertIsSelected()
        rule.onNodeWithText("Women").performClick()
        assertEquals(listOf(PowerProfileTable.WOMEN), chosen)
    }

    @Test
    fun `Settings leaves the power profile table out while there is no profile to edit`() {
        rule.setContent {
            app.overdrive.uikit.OverdriveTheme {
                SettingsView(settings.copy(powerTable = null), SettingsActions({}, {}, {}, {}, {}))
            }
        }
        rule.onNodeWithText("Power profile table").assertDoesNotExist()
    }
}
