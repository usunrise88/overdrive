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
import app.overdrive.uikit.shouldPlayIntro
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
        val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val motion = animatorScale > 0f
        // The launch film plays on a cold start only, and not when the animations are off (ADR 0031).
        val playIntro = shouldPlayIntro(coldStart = savedInstanceState == null, animatorScale = animatorScale)
        setContent {
            OverdriveTheme(motionEnabled = motion) {
                RideApp(
                    container,
                    Modifier.fillMaxSize().background(OverdriveColors.bg).windowInsetsPadding(WindowInsets.safeDrawing),
                    playIntro = playIntro,
                )
            }
        }
    }
}
