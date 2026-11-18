package app.overdrive.companion

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import app.overdrive.companion.ui.CompanionRoot
import app.overdrive.uikit.OverdriveTheme

/**
 * The one activity, portrait (design/companion.md §1). It holds no state: the screens, the answers and the back stacks live in the
 * [CompanionContainer] of the [CompanionApp], so rotating or recreating this activity changes nothing.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as CompanionApp).container
        // The system's "remove animations" setting switches off every short animation (design/system.md §9).
        val motion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent {
            OverdriveTheme(motionEnabled = motion) {
                CompanionRoot(container, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars))
            }
        }
    }
}
