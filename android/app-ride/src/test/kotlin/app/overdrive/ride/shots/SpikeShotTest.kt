package app.overdrive.ride.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveTypography
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class SpikeShotTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun spike() {
        rule.setContent {
            OverdriveTheme {
                Box(Modifier.size(600.dp, 200.dp).background(OverdriveColors.bg)) {
                    BasicText("223 34.6 18:42", style = OverdriveTypography.numHero.copy(color = OverdriveColors.z3))
                }
            }
        }
        rule.onRoot().captureRoboImage("build/shots/spike.png")
    }
}
