package app.overdrive.companion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.HealthWords
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * What Health Connect shows when the rider asks how Overdrive uses the data it was allowed to read (Android's permission rationale):
 * the same list as the first run's step, in plain words, with the promises that matter. It reads nothing and asks nothing.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { OverdriveTheme { RationaleView(onClose = ::finish) } }
    }
}

/** The rationale screen for the permissions of the watch data. */
@Composable
fun RationaleView(onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(
                OverdriveColors.bg,
            ).safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OdText("How Overdrive uses your watch data", style = OdType.title)
            OdText(
                "Overdrive reads these from Health Connect and sends them only to the service you set up yourself. " +
                    "It never writes to Health Connect, and it shares nothing with anyone else.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
            HorizontalRule(Modifier.padding(top = 8.dp))
            for (row in HealthWords.rows(null, asked = false)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    OdText(row.name, style = OdType.bodyStrong)
                    OdText(row.reason, style = OdType.label, color = OverdriveColors.textSecondary)
                }
                HorizontalRule()
            }
        }
        OdButton("Close", onClose, Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}
