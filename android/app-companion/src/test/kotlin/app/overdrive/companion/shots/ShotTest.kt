package app.overdrive.companion.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.tokens.OverdriveColors
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base of the JVM screenshot tests of the companion (ADR 0005, ADR 0024): Robolectric renders the Compose UI with native graphics
 * on a 412 dp wide phone at 2x, the size of the design mock-up PNGs, and Roborazzi writes the image to `build/shots/`. The pages
 * are 1700 dp tall so a whole screen is in one image (the lazy lists compose every item that fits); [DeviceShotTest] is the
 * 412 × 915 dp phone. The images are looked at, never committed, and never compared to goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1700dp-xhdpi")
abstract class ShotTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    /** Renders [content] on the app background and writes `build/shots/<name>.png`. */
    protected fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            OverdriveTheme {
                Box(Modifier.fillMaxSize().background(OverdriveColors.bg)) { content() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }
}

/** The phone itself: 412 × 915 dp, what the rider sees at a time. */
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
abstract class DeviceShotTest : ShotTest()
