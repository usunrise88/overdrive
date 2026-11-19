package app.overdrive.companion

import android.app.Application
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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.model.RepeatsUi
import app.overdrive.companion.ui.CompanionRoot
import app.overdrive.companion.ui.Route
import app.overdrive.companion.ui.Tab
import app.overdrive.uikit.OverdriveTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Rides tab's second view and the page of a repeated session, driven through the real container and the loopback service
 * (design/companion.md §4.12): History | Repeats, a session opens, its trend is in words with the service's caution under the chart, a
 * tap on the chart selects an attempt (and the pill says **Latest**), Values opens, an attempt opens its ride and Back goes back, and the
 * choice of view is kept. Every wait is for something on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class RepeatsFlowTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private fun showsText(
        text: String,
        substring: Boolean = false,
    ) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun showsDescription(text: String) =
        rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = rule.waitUntil(60_000) { showsText(text) }

    /** The page is a lazy list, and the step table inside it scrolls sideways: the first scrollable is the list. */
    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    private val falling = listOf(152.0, 150.0, 151.0, 147.0, 146.0)

    private fun serve() {
        service.repeats =
            RepeatSamples.list(RepeatSamples.Listed(RepeatSamples.NAME, RepeatSamples.HASH, falling, "2026-11-18"))
        service.repeatDetails[RepeatSamples.HASH] = RepeatSamples.detail(falling)
        // The attempts are rides of the service, so a tap on one opens the ride's own page.
        val attempts =
            falling.indices.map {
                Samples.ride(RepeatSamples.rideId(it), RepeatSamples.date(it), RepeatSamples.NAME, 3600, 70.0, 180.0)
            }
        service.rides = (service.rides + attempts).sortedByDescending { it.start }
    }

    @Test
    fun `Repeats lists the session, its page says the trend, a tap selects an attempt, and one opens its ride`() {
        serve()
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        rule.onNodeWithContentDescription("Rides, tab, 5 of 5").performClick()
        waitForText("Threshold 2×20")
        assertTrue("the selector is there", showsText("History") && showsText("Repeats"))

        // The second view lists the session in one plain line.
        rule.onNodeWithText("Repeats").performClick()
        waitForText("5 attempts · last Wed 18 Nov")
        assertTrue(showsText("Heart rate at the same power 146 bpm, 1 lower than last time"))
        assertFalse("the history is not shown under it", showsText("Threshold 2×20"))

        // The session: the trend in words only because the service says so, the caution under the chart.
        rule.onNodeWithText(RepeatSamples.NAME).performClick()
        waitForText("Heart rate at the same power is trending down: fitter")
        assertTrue(showsText("5 attempts · 8 Oct to 5 Nov"))
        assertTrue(showsText("heat, sleep and caffeine", substring = true))
        assertTrue(showsText("Attempt 5 · Thu 5 Nov"))
        assertEquals(Route.RepeatDetail(RepeatSamples.HASH), container.nav.route)

        // A tap on the chart selects an attempt; the pill says Latest and returns to rest.
        rule
            .onNode(
                hasContentDescription("Heart rate at the same power over 5 attempts", substring = true),
            ).performTouchInput {
                click(Offset(width * 0.1f, height / 2f))
            }
        rule.waitUntil(20_000) { showsDescription("Back to the latest attempt") }
        rule.onNode(hasContentDescription("Attempt 1 · Thu 8 Oct, Heart rate", substring = true)).assertExists()
        rule.onAllNodesWithContentDescription("Back to the latest attempt")[0].performClick()
        rule.waitUntil(10_000) { !showsDescription("Back to the latest attempt") }

        // Values: the table twin opens and closes with Back.
        rule.onAllNodesWithText("Values")[0].performClick()
        rule.waitUntil(10_000) { showsText("Attempt") && showsText("bpm") }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { !showsText("bpm") }

        // An attempt opens the existing ride detail; Back returns to the session, Back again to the list.
        scrollTo("Attempt 2 · Thu 15 Oct")
        rule.onNodeWithText("Attempt 2 · Thu 15 Oct").performClick()
        rule.waitUntil(30_000) { container.nav.route == Route.RideDetail(RepeatSamples.rideId(1)) }
        waitForText("Normalised")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Heart rate at the same power is trending down: fitter")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("5 attempts · last Wed 18 Nov")
        assertEquals("the Repeats view is kept", RidesMode.Repeats, container.ridesMode.value)
        assertEquals(Tab.Rides, container.nav.tab)
    }

    @Test
    fun `with no repeated session the second view says what to do`() {
        service.repeats = RepeatSamples.list()
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        rule.onNodeWithContentDescription("Rides, tab, 5 of 5").performClick()
        waitForText("Threshold 2×20")
        rule.onNodeWithText("Repeats").performClick()
        waitForText("No repeated sessions yet")
        assertTrue(showsText(RepeatsUi.EMPTY_TEXT))
        assertTrue(RepeatsUi.EMPTY_TEXT.startsWith("Ride the same workout twice"))
        rule.onNodeWithText("History").performClick()
        waitForText("Threshold 2×20")
    }

    @Test
    fun `a service that is down shows the cause next to the list it had`() {
        serve()
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        container.setRidesMode(RidesMode.Repeats)
        rule.onNodeWithContentDescription("Rides, tab, 5 of 5").performClick()
        waitForText("5 attempts · last Wed 18 Nov")
        await("the list is on disk") { java.io.File(world.cache, "repeats").isFile }
        service.down = true
        container.refresh(RefreshTarget.Repeats)
        rule.waitUntil(30_000) { showsText("Out of date", substring = true) }
        assertTrue("the session is still listed", showsText("5 attempts · last Wed 18 Nov"))
    }
}
