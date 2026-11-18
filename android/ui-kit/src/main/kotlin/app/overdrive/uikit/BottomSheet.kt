package app.overdrive.uikit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveMotion

/**
 * A bottom sheet (design/system.md §9, §13): a 70% scrim over the screen and a surface 1 panel with a 12 dp top radius and a grab
 * handle, in 200 ms decelerating and out 150 ms accelerating, instant when the system removes animations (design/companion.md
 * §8). Tapping the scrim dismisses it; the caller also closes it on Back. It fills the screen: put it last in the screen's box.
 *
 * @param heightFraction the share of the screen the panel fills, or null for as tall as its content.
 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val noInteraction = remember { MutableInteractionSource() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) fadeIn(tween(OverdriveMotion.durationStandard)) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(OverdriveMotion.durationFast)) else ExitTransition.None,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(OverdriveColors.scrim)
                .clickable(interactionSource = noInteraction, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .animateEnterExit(
                        enter =
                            if (motion) {
                                slideInVertically(
                                    tween(OverdriveMotion.durationStandard, easing = OverdriveMotion.easingDecelerate),
                                ) { it }
                            } else {
                                EnterTransition.None
                            },
                        exit =
                            if (motion) {
                                slideOutVertically(
                                    tween(OverdriveMotion.durationFast, easing = OverdriveMotion.easingAccelerate),
                                ) { it }
                            } else {
                                ExitTransition.None
                            },
                    ).fillMaxWidth()
                    .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
                    .clickable(interactionSource = noInteraction, indication = null) {}
                    .surface(
                        OverdriveColors.surface1,
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        OverdriveColors.border,
                    ).padding(horizontal = 16.dp),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(
                                32.dp,
                            ).height(4.dp)
                            .background(OverdriveColors.surface3, RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}
