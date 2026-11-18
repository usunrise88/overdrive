package app.overdrive.uikit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Root theme for both apps. Dark only: the ride app is used in a dim room next to video (spec §7.4).
 * Placeholder palette until the design system in `design/` defines the real tokens.
 */
@Composable
fun OverdriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
