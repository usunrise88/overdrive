package app.overdrive.companion

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.ui.CompanionRoot
import app.overdrive.companion.ui.Tab
import app.overdrive.uikit.OverdriveTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch data's screens driven in the real app against the local stand-in of the service (design/companion.md §2, §3.5, §4.1,
 * §4.2, §4.5, §4.9): Wellness' one selection for every card, a fact of the recovery card opening Wellness at its metric, the note
 * card and Notes with the topic's Copy, and the heart-rate stepper reaching the service. Every wait is for something on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class WatchFlowTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)

    @After
    fun cleanUp() = world.close()

    private fun showsText(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showsDescription(text: String) =
        rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = rule.waitUntil(60_000) { showsText(text) }

    /** The screens are lazy lists: a card below the fold is not composed until the list is scrolled to it. */
    private fun scrollTo(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun start(): CompanionContainer {
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        return container
    }

    @Test
    fun `Wellness draws every card, and one tap selects a day on all of them with the Today pill to come back`() {
        val container = start()
        rule.onNodeWithContentDescription("Wellness, tab, 4 of 5").performClick()
        waitForText("Resting heart rate")
        // The cards in the design's order; weight and heart rate in sleep only when the service has them.
        rule.waitUntil(20_000) { !showsText("Planned today") }
        for (name in listOf("Sleep", "Sleep timing", "Respiratory rate", "Oxygen saturation")) {
            scrollTo(name)
            assertTrue("$name card", showsText(name))
        }
        assertFalse(showsText("Weight"))
        scrollTo("Resting heart rate")
        assertTrue(showsText("usual 50–56"))
        rule.onNode(hasContentDescription("Resting heart rate, 4 weeks", substring = true)).performTouchInput {
            click(Offset(width * 0.3f, height / 2f))
        }
        rule.waitUntil(
            20_000,
        ) { rule.onAllNodesWithText("Showing", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { showsDescription("Back to today") }
        rule.onAllNodesWithContentDescription("Back to today")[0].performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Showing", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(Tab.Wellness, container.nav.tab)
    }

    @Test
    fun `a fact of the recovery card opens Wellness at that metric`() {
        val container = start()
        scrollTo("Oxygen saturation")
        rule.onAllNodesWithText("Oxygen saturation")[0].performClick()
        rule.waitUntil(20_000) { container.nav.tab == Tab.Wellness }
        // The request to scroll is made once and then forgotten.
        rule.waitUntil(20_000) { container.wellnessFocus.value == null }
        waitForText("Sleep timing")
    }

    @Test
    fun `the note card opens Notes, a ride note carries its topic, and Copy puts the question on the clipboard`() {
        start()
        scrollTo("All notes")
        rule.onNodeWithText("All notes").performClick()
        waitForText("Heads-up · 07:10")
        assertTrue(showsText("Yesterday"))
        scrollTo("Plan check · 07:00")
        assertTrue(showsText("Plan check · 07:00"))
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Copy question"))
        waitForText("Worth a chat with your coach")
        rule.onNodeWithText("Copy question").performClick()
        waitForText("Copied")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(WatchSamples.topic().openingQuestion, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // Read only: nothing was sent from here.
        assertTrue(world.service.requests("POST", "/v1/insights").isEmpty())
    }

    @Test
    fun `the heart-rate stepper changes the value at once and reaches the service`() {
        val container = start()
        await("the profile") { container.profile.state.value.profile != null }
        rule.onNodeWithContentDescription("Settings").performClick()
        waitForText("Heart rate")
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Increase maximum"))
        assertTrue(showsText("Not set"))
        rule.onNodeWithContentDescription("Increase maximum").performClick()
        waitForText("180 bpm")
        await("the service has it") { world.service.profile?.maxHeartRate == 180 }
        assertNull(container.profile.state.value.pending)
    }
}
