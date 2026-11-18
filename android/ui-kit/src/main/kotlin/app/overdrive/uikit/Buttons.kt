package app.overdrive.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion

/** The three button looks of design/system.md §7.1. */
enum class ButtonVariant {
    /** The one main action of a screen: near-white fill, dark text. */
    Primary,

    /** Everything else: surface 2 with a hairline. */
    Secondary,

    /** Tertiary actions and the icon-only media buttons. */
    Ghost,
}

private val buttonShape = RoundedCornerShape(OverdriveDimens.radiusMd)

private data class ButtonColours(
    val fill: Color,
    val edge: Color,
    val content: Color,
)

private fun buttonColours(
    variant: ButtonVariant,
    pressed: Boolean,
    enabled: Boolean,
): ButtonColours =
    when {
        !enabled -> {
            ButtonColours(OverdriveColors.surface1, Color.Transparent, OverdriveColors.textTertiary)
        }

        pressed -> {
            ButtonColours(OverdriveColors.surface3, OverdriveColors.borderStrong, OverdriveColors.textPrimary)
        }

        else -> {
            when (variant) {
                ButtonVariant.Primary -> {
                    ButtonColours(
                        OverdriveColors.textPrimary,
                        Color.Transparent,
                        OverdriveColors.textOnPrimary,
                    )
                }

                ButtonVariant.Secondary -> {
                    ButtonColours(
                        OverdriveColors.surface2,
                        OverdriveColors.border,
                        OverdriveColors.textPrimary,
                    )
                }

                ButtonVariant.Ghost -> {
                    ButtonColours(
                        Color.Transparent,
                        Color.Transparent,
                        OverdriveColors.textSecondary,
                    )
                }
            }
        }
    }

@Composable
private fun pressColour(target: Color): Color {
    val motion = LocalMotionEnabled.current
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = if (motion) tween(OverdriveMotion.durationPress) else snap(),
        label = "press",
    )
    return animated
}

/**
 * A button (design/system.md §7.1): 48 dp high (64 dp on the ride screens), radius 8, label in the body-strong
 * style, an optional 20 dp icon before or after the label. Pressed is a surface 3 fill with a strong hairline,
 * disabled is surface 1 with tertiary text; there is no ripple.
 *
 * @param repeating fire [onClick] on press and then repeatedly while held, for controls like volume.
 * @param forcePressed draw the pressed look without a touch, for previews and screenshots.
 */
@Composable
fun OdButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Secondary,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    height: Dp = OverdriveDimens.sizeButtonHeight,
    minWidth: Dp = 0.dp,
    horizontalPadding: Dp = 20.dp,
    enabled: Boolean = true,
    repeating: Boolean = false,
    forcePressed: Boolean = false,
) {
    val source = rememberInteractionSource()
    val pressed = source.pressed() || forcePressed
    val colours = buttonColours(variant, pressed, enabled)
    val fill = pressColour(colours.fill)
    val edge = pressColour(colours.edge)
    val gesture =
        if (repeating) {
            Modifier.repeatingClickable(source, enabled, onClick = onClick)
        } else {
            Modifier.plainClickable(source, enabled, onClick = onClick)
        }
    Box(
        modifier
            .height(height)
            .defaultMinSize(minWidth = minWidth)
            .surface(fill, buttonShape, edge)
            .then(gesture)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) OdIcon(leadingIcon, tint = colours.content)
            if (text !=
                null
            ) {
                OdText(text, style = OdType.bodyStrong, color = colours.content, maxLines = 1, softWrap = false)
            }
            if (trailingIcon != null) OdIcon(trailingIcon, tint = colours.content)
        }
    }
}

/**
 * An icon-only button: [size] square (48 dp, 56 dp for the media group, 64 dp on the ride bar) with a 20 dp
 * icon. [contentDescription] is required because there is no label.
 */
@Composable
fun OdIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Ghost,
    size: Dp = OverdriveDimens.sizeTouchMin,
    iconSize: Dp = OverdriveDimens.sizeIcon,
    enabled: Boolean = true,
    repeating: Boolean = false,
    tint: Color = Color.Unspecified,
    forcePressed: Boolean = false,
) {
    val source = rememberInteractionSource()
    val pressed = source.pressed() || forcePressed
    val colours = buttonColours(variant, pressed, enabled)
    val fill = pressColour(colours.fill)
    val edge = pressColour(colours.edge)
    val gesture =
        if (repeating) {
            Modifier.repeatingClickable(source, enabled, onClick = onClick)
        } else {
            Modifier.plainClickable(source, enabled, onClick = onClick)
        }
    Box(
        modifier
            .size(size)
            .surface(fill, buttonShape, edge)
            .semantics { this.contentDescription = contentDescription }
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        OdIcon(icon, size = iconSize, tint = tint.orElse(colours.content))
    }
}
