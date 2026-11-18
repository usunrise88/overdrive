package app.overdrive.ride

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import app.overdrive.ride.ui.RideApp
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The one activity, sensor landscape (spec §4.6). It holds no ride state: the ride lives in the [AppContainer] of the
 * [OverdriveApp], so rotating or recreating this activity changes nothing about the ride.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as OverdriveApp).container
        // The system's "remove animations" setting switches off every short animation (design/system.md §9).
        val motion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent {
            OverdriveTheme(motionEnabled = motion) {
                RideApp(container, Modifier.fillMaxSize().background(OverdriveColors.bg).windowInsetsPadding(WindowInsets.safeDrawing))
            }
        }
    }
}
