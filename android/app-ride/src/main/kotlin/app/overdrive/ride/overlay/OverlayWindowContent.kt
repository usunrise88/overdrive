package app.overdrive.ride.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import app.overdrive.core.live.LiveCoachState
import app.overdrive.ride.live.LiveCoachUi
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.overlay.OverlayCoach
import app.overdrive.uikit.overlay.OverlayCoachControl
import app.overdrive.uikit.overlay.OverlayContent
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.overlay.OverlayUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The overlay look the rider chose: the backing opacity and the mode. Nothing else of the settings reaches the window. */
fun AppSettings.toOverlaySettings(): OverlaySettings =
    OverlaySettings(
        opacity = overlayOpacityPercent / PERCENT,
        mode = if (overlayMode == OverlayStartMode.Detailed) OverlayMode.Detailed else OverlayMode.Compact,
    )

private const val PERCENT = 100f

/**
 * What the overlay shows of the live coach (spec §12.1, docs/adr/0039), in the ui-kit's words: the mute control while a session is on (with the state in
 * words), the one-time notice that the coach is unavailable, and the plate only while a session is on.
 */
fun LiveCoachUi.toOverlayCoach(): OverlayCoach =
    when (val coach = state) {
        LiveCoachState.Off -> {
            OverlayCoach.None
        }

        LiveCoachState.Unavailable -> {
            OverlayCoach(notice = OverlayCoach.UNAVAILABLE)
        }

        is LiveCoachState.On -> {
            OverlayCoach(
                control = if (coach.muted) OverlayCoachControl.Muted else OverlayCoachControl.On,
                plate = plate,
            )
        }
    }

/**
 * What the overlay window shows: the ui-kit's overlay composable over [states], which is already mapped, filtered and
 * rate limited (see [overlayStates]) and is collected on `Dispatchers.Default`, so nothing but the final value touches the
 * main thread. [look] is the opacity and mode only, so a change of position or any other setting does not reach
 * Compose. [coach] is the live coach's own value (docs/adr/0039): it changes on its own clock, a cue or a mute, never as often as a second, and it is
 * not spaced by the rate limit. No motion: the overlay has no animation at rest (spec §4.6).
 *
 * @param onRecompose called each time the overlay's content is composed, so tests can count recompositions.
 * @param onControlBounds where the mute control is, for the window's touches (see [OverlayContent]).
 */
@Composable
internal fun OverlayWindowContent(
    states: Flow<OverlayUiState>,
    initial: OverlayUiState,
    look: Flow<OverlaySettings>,
    initialLook: OverlaySettings,
    coach: Flow<OverlayCoach> = emptyFlow(),
    initialCoach: OverlayCoach = OverlayCoach.None,
    onControlBounds: (Rect?) -> Unit = {},
    onRecompose: () -> Unit = {},
) {
    val state by states.collectAsState(initial, Dispatchers.Default)
    val settings by look.collectAsState(initialLook)
    val coachState by coach.collectAsState(initialCoach)
    OverdriveTheme(motionEnabled = false) {
        SideEffect(onRecompose)
        OverlayContent(state, settings, coach = coachState, onControlBounds = onControlBounds)
    }
}
