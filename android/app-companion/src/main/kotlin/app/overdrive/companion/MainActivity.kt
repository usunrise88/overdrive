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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
        // The app is on screen: read what is new from the watch (at most every five minutes), as the daily job does at 06:00.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) container.appOpened() },
        )
        setContent {
            OverdriveTheme(motionEnabled = motion) {
                CompanionRoot(container, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars))
            }
        }
    }
}
