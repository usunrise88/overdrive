package app.overdrive.ride.shots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base of the JVM screenshot tests (ADR 0005): Robolectric renders the Compose UI with native graphics on a
 * 1280 x 800 dp tablet at 2x, the size of the design mock-up PNGs, and Roborazzi writes the image to
 * `build/shots/`. The images are looked at, never committed, and never compared to goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h1200dp-xhdpi")
abstract class ShotTest {
    @get:Rule
    val rule: AndroidComposeTestRule<*, ComponentActivity> = createAndroidComposeRule<ComponentActivity>()

    /** Renders [content] and writes `build/shots/<name>.png`. */
    protected fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        rule.setContent(content)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }
}
