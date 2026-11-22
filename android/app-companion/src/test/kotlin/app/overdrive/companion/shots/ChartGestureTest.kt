package app.overdrive.companion.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.overdrive.companion.Samples
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.FitnessUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.remote.MetricsRange
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.PlotChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The charts' touch (design/companion.md §3.5): a tap selects the nearest point, a hold and slide scrubs, TalkBack's two actions move
 * the selection. Driven on the real charts of the Fitness screen from the synthetic answers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ChartGestureTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    private val content =
        (
            FitnessUi
                .of(
                    fresh(Samples.metrics("2026-05-25")),
                    MetricsRange.SIX_MONTHS,
                    TestClock.words,
                    "x",
                ).body as Body.Content
        ).value
    private val selection = ChartSelection()
    private val chart = hasContentDescription("Fitness and fatigue, 6 months", substring = true)

    private fun show() {
        rule.setContent {
            OverdriveTheme {
                Column(Modifier.padding(16.dp)) {
                    PlotChart(content.fitness.spec, selection, "Fitness and fatigue, 6 months")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a tap selects the nearest day and rest is null until then`() {
        show()
        assertNull(selection.x)
        rule.onNode(chart).performTouchInput { click(Offset(width * 0.5f, height / 2f)) }
        val x = need(selection.x)
        val days = content.fitness.spec.snapXs
        assertTrue("a day of the chart", x in days.toList())
        // The middle of the plot is about the middle of the range.
        assertEquals(days[days.size / 2], x, days.size * 0.08)
    }

    @Test
    fun `a hold and slide scrubs and snaps to days`() {
        show()
        rule.onNode(chart).performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            advanceEventTime(400)
            moveTo(Offset(width * 0.7f, height / 2f))
            up()
        }
        val x = need(selection.x)
        val days = content.fitness.spec.snapXs
        assertTrue(x in days.toList())
        assertTrue("it slid to the right of the start", x > days[days.size * 4 / 10])
    }

    @Test
    fun `a long press selects too`() {
        show()
        rule.onNode(chart).performTouchInput { longClick(Offset(width * 0.2f, height / 2f)) }
        need(selection.x)
    }

    private fun <T : Any> need(value: T?): T = checkNotNull(value)

    private fun customAction(label: String) {
        val actions = rule.onNode(chart).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnUiThread { actions.first { it.label == label }.action() }
    }

    @Test
    fun `TalkBack's Previous and Next point move the selection a day at a time`() {
        show()
        val days = content.fitness.spec.snapXs
        customAction("Previous point")
        assertEquals(days[days.size - 2], need(selection.x), 0.0)
        customAction("Next point")
        assertEquals(days.last(), need(selection.x), 0.0)
    }
}
