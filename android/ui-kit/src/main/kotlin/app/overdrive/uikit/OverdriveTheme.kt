package app.overdrive.uikit

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveTypography

/** The colour text and icons take when a component does not say otherwise. */
val LocalContentColor = staticCompositionLocalOf { OverdriveColors.textPrimary }

/** The text style of running text. Components override it with the style their role needs. */
val LocalTextStyle = staticCompositionLocalOf { OverdriveTypography.body }

/**
 * Whether short UI motion (press feedback, switch thumb, screen crossfade) plays. Off when the system
 * "remove animations" setting is on (design/system.md §9). The device-search spinner is the only loop.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/**
 * Root theme for both apps. Dark only: the ride app is used in a dim room next to video (spec §7.4).
 *
 * There is no Material layer: no ripple, no elevation, no overscroll stretch. The tokens are the
 * generated `app.overdrive.uikit.tokens` objects; this only sets the defaults every component reads.
 */
@Composable
fun OverdriveTheme(
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides OverdriveColors.textPrimary,
        LocalTextStyle provides OverdriveTypography.body,
        LocalMotionEnabled provides motionEnabled,
        LocalOverscrollFactory provides null,
        content = content,
    )
}

/** [this] unless it is unspecified, then [other]. */
internal fun Color.orElse(other: Color): Color = if (this == Color.Unspecified) other else this
