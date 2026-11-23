package app.overdrive.companion.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.overdrive.companion.ProgressSamples
import app.overdrive.companion.ProgressScreenSamples
import app.overdrive.companion.model.ProgressChartUi
import app.overdrive.companion.model.ProgressUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.ui.ProgressActions
import app.overdrive.companion.ui.ProgressEntryRow
import app.overdrive.companion.ui.ProgressSelections
import app.overdrive.companion.ui.ProgressView
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.ProgressRange
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The touch of the Progress screen on the real composables (design/companion.md §3.5, §4.16, §8): a tap selects in its own card only and
 * the Latest pill returns it, the readout of a ride measure opens the ride, the range selector asks for its range, the stacked pair is one
 * node for TalkBack, the targets are 48 dp.
 */
@Config(sdk = [35], qualifiers = "w412dp-h3000dp-xhdpi")
class ProgressInteractionTest : ShotTest() {
    private val words = TestClock.words
    private val response = ProgressScreenSamples.full()
    private val selections = ProgressSelections()
    private val ranges = mutableListOf<ProgressRange>()
    private val opened = mutableListOf<String>()
    private var back = 0

    private fun show(range: ProgressRange = ProgressRange.SIX_MONTHS) {
        val ui = ProgressUi.of(fresh(response), range, words, "overdrive.example.net")
        rule.setContent {
            OverdriveTheme {
                ProgressView(
                    ui,
                    range,
                    ProgressActions({ back++ }, {}, {}, {}, { ranges += it }, { opened += it }),
                    selections = selections,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun day(date: String) = checkNotNull(PlanDates.epochDay(date)).toDouble()

    private val efficiency = hasContentDescription("Heart rate at 180 watts, last 6 months", substring = true)
    private val factor = hasContentDescription("Efficiency factor by ride", substring = true)

    @Test
    fun `a tap selects a week in its own card only, and Latest returns it to rest`() {
        show()
        rule.onNode(efficiency).performTouchInput { click(Offset(width * 0.5f, height / 2f)) }
        val x = checkNotNull(selections.of("efficiency").x)
        assertTrue("the middle of the range, in the weeks of the card", x in day("2026-07-27")..day("2026-09-21"))
        assertNull("another card does not follow", selections.of("factor").x)
        assertNull(selections.of("best").x)
        rule.onNodeWithText("Latest").performClick()
        rule.waitForIdle()
        assertNull(selections.of("efficiency").x)
        assertEquals(0, rule.onAllNodesWithText("Latest").fetchSemanticsNodes().size)
    }

    @Test
    fun `the verdict and the numbers do not follow the selection`() {
        show()
        rule.onNode(efficiency).performTouchInput { click(Offset(width * 0.5f, height / 2f)) }
        rule.waitForIdle()
        rule.onNodeWithText("Heart rate at the same power is trending down: fitter").assertExists()
        rule.onNodeWithText("138 bpm at 180 W, 7 lower than in May · 186 W at 140 bpm, 13 more").assertExists()
    }

    @Test
    fun `a pair of plots is one node for TalkBack and Previous and Next point move the shared selection`() {
        show()
        val nodes =
            rule
                .onAllNodesWithContentDescription(
                    "Power at 140 beats per minute",
                    substring = true,
                ).fetchSemanticsNodes()
        assertEquals("one node holds both series", 1, nodes.size)
        assertTrue(nodes.single().config.contains(SemanticsActions.CustomActions))
        val actions = nodes.single().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Previous point", "Next point"), actions.map { it.label })
        rule.runOnUiThread { actions.first { it.label == "Previous point" }.action() }
        val moved = checkNotNull(selections.of("efficiency").x)
        val card = ProgressUi.aerobicEfficiency(response, ProgressRange.SIX_MONTHS, words) as ProgressChartUi
        assertEquals(card.plots[0].spec.snapXs[card.plots[0].spec.snapXs.size - 2], moved, 0.0)
        // The Latest pill is called what TalkBack should say.
        rule.onNode(hasContentDescription("Back to the latest point")).assertExists()
    }

    @Test
    fun `the readout of a ride measure opens the ride, in a 48 dp target`() {
        show()
        val opener = hasContentDescription("Mon 16 Nov · Endurance 90, opens the ride")
        rule.onNode(opener).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(response.efficiencyFactor.rides.last().rideId), opened)
        // A readout of weeks has no ride to open.
        assertEquals(
            0,
            rule.onAllNodesWithContentDescription("Week of 16 Nov, opens the ride").fetchSemanticsNodes().size,
        )
        rule.onNode(factor).performTouchInput { click(Offset(width * 0.2f, height / 2f)) }
        rule.waitForIdle()
        assertTrue(checkNotNull(selections.of("factor").x) < day("2026-08-01"))
    }

    @Test
    fun `the range selector asks for its range and Back goes back`() {
        show()
        rule.onNodeWithText("1 year").performClick()
        rule.onNodeWithText("All time").performClick()
        assertEquals(listOf(ProgressRange.ONE_YEAR, ProgressRange.ALL_TIME), ranges)
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals(1, back)
    }

    @Test
    fun `every Values button is a 48 dp target`() {
        show()
        val buttons = rule.onAllNodes(hasText("Values")).fetchSemanticsNodes()
        assertEquals(6, buttons.size)
        for (i in buttons.indices) rule.onAllNodes(hasText("Values"))[i].assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the entry row is one button with the headline in its description`() {
        val entry = ProgressUi.entry(fresh(ProgressSamples.heartRateFalling()))
        var clicked = 0
        rule.setContent { OverdriveTheme { ProgressEntryRow(entry, { clicked++ }) } }
        val row = rule.onNode(hasContentDescription(entry.description))
        row.assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(1, clicked)
    }
}
