package app.overdrive.uikit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors

private val markPath: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
    PathParser().parsePathString(MARK_PATH).toPath()
}

/**
 * The Overdrive mark (design/brand.md): a ring wound twice whose outer turn leaves as a tail. White by default, flat, with no
 * glow and no noise: those live in the launcher icon only (design/system.md §2). Decorative, so it has no content description;
 * put it next to the name, never instead of it. Square, [size] on a side (the mark itself is a quarter lower than wide).
 */
@Composable
fun OverdriveMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = OverdriveColors.textPrimary,
) {
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 1000f, pivot = Offset.Zero) {
            drawPath(markPath, color)
        }
    }
}
