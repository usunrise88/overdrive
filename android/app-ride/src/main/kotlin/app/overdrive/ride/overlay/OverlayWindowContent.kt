package app.overdrive.ride.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.overlay.OverlayContent
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.overlay.OverlayUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/** The overlay look the rider chose: the backing opacity and the mode. Nothing else of the settings reaches the window. */
fun AppSettings.toOverlaySettings(): OverlaySettings =
    OverlaySettings(
        opacity = overlayOpacityPercent / PERCENT,
        mode = if (overlayMode == OverlayStartMode.Detailed) OverlayMode.Detailed else OverlayMode.Compact,
    )

private const val PERCENT = 100f

/**
 * What the overlay window shows: the ui-kit's overlay composable over [states], which is already mapped, filtered and
 * rate limited (see [overlayStates]) and is collected on `Dispatchers.Default`, so nothing but the final value touches the
 * main thread. [look] is the opacity and mode only, so a change of position or any other setting does not reach
 * Compose. No motion: the overlay has no animation at rest (spec §4.6).
 *
 * @param onRecompose called each time the overlay's content is composed, so tests can count recompositions.
 */
@Composable
internal fun OverlayWindowContent(
    states: Flow<OverlayUiState>,
    initial: OverlayUiState,
    look: Flow<OverlaySettings>,
    initialLook: OverlaySettings,
    onRecompose: () -> Unit = {},
) {
    val state by states.collectAsState(initial, Dispatchers.Default)
    val settings by look.collectAsState(initialLook)
    OverdriveTheme(motionEnabled = false) {
        SideEffect(onRecompose)
        OverlayContent(state, settings)
    }
}
