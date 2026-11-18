package app.overdrive.uikit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveDimens

/**
 * A Lucide icon ([app.overdrive.uikit.icons.OverdriveIcons]) tinted with [tint] or the context colour.
 * Sizes: 20 dp default, 24 dp in bottom navigation, 16 dp inline in chips and status text.
 */
@Composable
fun OdIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = OverdriveDimens.sizeIcon,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val painter = rememberVectorPainter(icon)
    val colour = tint.orElse(LocalContentColor.current)
    Box(
        modifier
            .size(size)
            .paint(painter, colorFilter = ColorFilter.tint(colour))
            .then(
                if (contentDescription !=
                    null
                ) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    )
}

/** The small icon size used inline with text. */
val InlineIconSize: Dp = 16.dp
