package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.ui.CompanionRoot
import app.overdrive.uikit.OverdriveTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The companion driven through its screens (design/companion.md §2, §3.5, §3.6): the first run's connect, the tabs and their
 * contents from a local service, a tap on a chart (the selection, its readout and the Today pill), the Values sheet, a ride's detail
 * and back. Real threads and sockets; every wait is for something to be on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class CompanionFlowTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)

    @After
    fun cleanUp() = world.close()

    private fun showsText(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(
        text: String,
        timeoutMs: Long = 60_000,
    ) = rule.waitUntil(timeoutMs) { showsText(text) }

    /** Waits for [condition]; on a timeout the failure carries the semantics tree, which is what a failed wait needs. */
    private fun waitUntilOrDump(
        what: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "Timed out waiting for $what\n" +
                    rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 30).take(5000),
            )
        }
    }

    private fun showsDescription(text: String) =
        rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the first run says what is wrong with the token, then connects and shows the tabs`() {
        val container = world.container()
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Connect to your service")
        rule.onNodeWithContentDescription("Service address").performTextInput(world.service.baseUrl)
        rule.onNodeWithContentDescription("Token").performTextInput("tok-wrong")
        rule.onNodeWithText("Connect").performClick()
        waitForText("The service did not accept this token (401).")
        assertTrue("still on the connect step", showsText("Connect to your service"))
        rule.onNodeWithContentDescription("Token").performTextClearance()
        rule.onNodeWithContentDescription("Token").performTextInput(world.service.token)
        rule.onNodeWithText("Connect").performClick()
        // Connected: the watch data's step. Not now goes on without access, and Today says where to allow it.
        waitForText("Read your watch data")
        assertTrue(showsText("Step 2 of 3"))
        rule.onNodeWithText("Not now").performClick()
        waitForText("Planned today")
        assertEquals(world.service.baseUrl, container.settings.value!!.serviceUrl)
        waitForText("Learning your usual range")
        assertTrue(showsText("Overdrive rates recovery after 14 nights with the watch. Allow Health Connect in Settings to rate recovery."))
    }

    @Test
    fun `the first run asks for the watch data, reads the last 60 days and lands on Today`() {
        val health = FakeHealth()
        FakeHealth.sixtyDays(health, TestClock.now, 3)
        val container = world.container(health = health)
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Connect to your service")
        rule.onNodeWithContentDescription("Service address").performTextInput(world.service.baseUrl)
        rule.onNodeWithContentDescription("Token").performTextInput(world.service.token)
        rule.onNodeWithText("Connect").performClick()

        // Step 2: what is asked and why, nothing read yet. The list is the design's, with the three optional ones marked.
        waitForText("Read your watch data")
        assertTrue(showsText("Resting heart rate"))
        assertTrue(showsText("The main recovery signal"))
        assertTrue(showsText("Weight · optional"))
        assertTrue(world.service.wellnessBodies.isEmpty())
        health.allow()
        rule.onNodeWithText("Continue").performClick()

        // Step 3: everything allowed goes on by itself.
        waitForText("Read in the background")
        assertTrue(showsText("Step 3 of 3"))
        assertTrue(showsText("Autostart"))
        assertTrue(showsText("Not checked"))
        rule.onNodeWithText("Skip").performClick()

        // Step 4 reads the first 60 days; Today opens when it ends.
        waitForText("Planned today")
        await("the watch data reached the service") { world.service.wellnessRecords.size == FakeHealth.idsOf(3).size }
        assertEquals(app.overdrive.companion.settings.OnboardingStep.Done, container.settings.value!!.onboarding)
    }

    @Test
    fun `a partly allowed Health Connect lists what is off, says what is lost and goes on`() {
        val health = FakeHealth()
        val container = world.container(health = health)
        with(world) { container.configure(onboarded = false) }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Read your watch data")
        health.allow(app.overdrive.core.health.HealthDataType.entries.toSet() - app.overdrive.core.health.HealthDataType.SLEEP)
        rule.onNodeWithText("Continue").performClick()
        waitForText("Sleep is off")
        assertTrue(showsText("Health Connect allowed 8 of 9."))
        assertTrue(showsText("Recovery works from the other signals and says less. You can allow it later in Settings."))
        assertTrue(showsText("Allow sleep"))
        rule.onNodeWithText("Continue").performClick()
        waitForText("Read in the background")
    }

    @Test
    fun `tabs, a chart's selection and its Values sheet, a ride and back`() {
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        assertTrue("the recovery card of the service", showsText("Ready"))
        assertTrue("this morning's note", showsText("Morning note · 07:30"))
        assertTrue("the planned workout", showsText("Sweet spot 3×10"))
        assertTrue("the FTP suggestion", showsText("Your FTP may be 256 W"))

        // Fitness: a tap on the chart selects a day, the readout follows and the Today pill appears.
        rule.onNodeWithContentDescription("Fitness, tab, 2 of 5").performClick()
        waitForText("Fitness and fatigue")
        // The screens crossfade: wait until Today's is gone, so its header is not counted below.
        rule.waitUntil(20_000) { !showsText("Planned today") }
        rule.onNode(hasContentDescription("Fitness and fatigue, 6 months", substring = true)).performTouchInput {
            click(Offset(width * 0.3f, height / 2f))
        }
        waitUntilOrDump("the Today pill") { showsDescription("Back to today") }
        // The pill returns the chart to rest.
        rule.onAllNodesWithContentDescription("Back to today")[0].performClick()
        rule.waitUntil(10_000) { !showsDescription("Back to today") }

        // Values: the table twin opens and closes with Back.
        rule.onAllNodesWithText("Values")[0].performClick()
        waitForText("Fitness")
        rule.waitUntil(10_000) { showsText("Day") }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { !showsText("Day") }

        // Rides: a row opens the ride, Back returns to the list.
        rule.onNodeWithContentDescription("Rides, tab, 5 of 5").performClick()
        waitForText("Threshold 2×20")
        rule.onNodeWithText("VO2 5×3").performClick()
        waitForText("Planned workout")
        assertTrue(showsText("Normalised"))
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Threshold 2×20")

        // Back from another tab goes to Today.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Planned today")
        assertEquals(app.overdrive.companion.ui.Tab.Today, container.nav.tab)
    }

    @Test
    fun `Use on Today sends the FTP and says so`() {
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Your FTP may be 256 W")
        rule.onNodeWithText("Use 256 W").performClick()
        waitForText("FTP is now 256 W")
        await("the service has it") { world.service.profile?.ftp == 256 }
    }

    @Test
    fun `a long press on a chart selects a point and shows the Today pill`() {
        val container = world.container()
        with(world) { container.configure() }
        rule.setContent { OverdriveTheme { CompanionRoot(container) } }
        waitForText("Planned today")
        rule.onNodeWithContentDescription("Fitness, tab, 2 of 5").performClick()
        waitForText("Fitness and fatigue")
        rule.waitUntil(20_000) { !showsText("Planned today") }
        rule.onNode(hasContentDescription("Fitness and fatigue, 6 months", substring = true)).performTouchInput {
            longClick(Offset(width * 0.6f, height / 2f))
        }
        waitUntilOrDump("the Today pill") { showsDescription("Back to today") }
    }
}
