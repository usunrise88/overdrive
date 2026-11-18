package app.overdrive.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.tokens.OverdriveColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OverdriveTheme {
                Box(Modifier.fillMaxSize().background(OverdriveColors.bg), contentAlignment = Alignment.Center) {
                    OdText("Overdrive Companion", style = OdType.title)
                }
            }
        }
    }
}
