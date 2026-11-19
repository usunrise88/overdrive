package app.overdrive.companion

import android.content.Intent
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
import app.overdrive.companion.model.ImportAction
import app.overdrive.companion.share.ShareIntents
import app.overdrive.companion.ui.FitImportScreen
import app.overdrive.companion.ui.Route
import app.overdrive.companion.ui.Tab
import app.overdrive.uikit.OverdriveTheme

/**
 * Where a FIT file lands (spec §11.8 path A, ADR 0028, design/companion.md §4.11): the share sheet of OnelapFit (`ACTION_SEND`), or a
 * file manager's **Open with** (`ACTION_VIEW`), for the FIT types and for any type whose name ends in `.fit`.
 *
 * It holds no state and does no work: it hands the file to the [CompanionContainer], which reads it, keeps it on disk and sends it in
 * its own scope, so rotating, going back or being killed changes nothing about the file; this screen only shows what the container says.
 * A recreated activity (`savedInstanceState` set) shows what is there and does not import again.
 */
class ImportActivity : ComponentActivity() {
    private val container get() = (application as CompanionApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handle(intent)
        val motion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent {
            OverdriveTheme(motionEnabled = motion) {
                FitImportScreen(
                    container,
                    onDone = ::finish,
                    onAction = ::open,
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
                )
            }
        }
    }

    /** A second file shared while this screen is open (it is `singleTop`): the newer replaces the older on screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val file = ShareIntents.sharedFileOf(intent)
        if (file != null) container.shareFit(file) else container.fitScreenClosed()
    }

    override fun onDestroy() {
        if (isFinishing) container.fitScreenClosed()
        super.onDestroy()
    }

    /** **Open Overdrive** (the Rides tab, where the ride is) or **Settings** (the token or the address): the app comes forward. */
    private fun open(action: ImportAction) {
        when (action) {
            ImportAction.Rides -> {
                container.nav.select(Tab.Rides)
                container.setRidesMode(RidesMode.History)
            }

            ImportAction.Settings -> {
                container.nav.select(Tab.Today)
                container.nav.push(Route.Settings)
            }
        }
        startActivity(
            Intent(
                this,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
