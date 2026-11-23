package app.overdrive.uikit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion

/**
 * The top bar of every screen (design/system.md §6): 64 dp including the 1 dp bottom hairline, 24 dp side
 * padding, 16 dp between items.
 */
@Composable
fun OdTopBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(
                    OverdriveDimens.sizeTopBar - OverdriveDimens.strokeHairline,
                ).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        HorizontalRule()
    }
}

/**
 * The top bar of Devices and Settings: a back chevron and the title in the heading style. [subtitle] is the line under the title of a
 * pushed companion screen ("Updated 07:40", design/companion.md §4.16).
 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
) {
    OdTopBar(modifier) {
        OdIconButton(OverdriveIcons.ChevronLeft, onBack, "Back", Modifier.offset(x = (-12).dp))
        if (subtitle == null) {
            OdText(title, Modifier.weight(1f).offset(x = (-12).dp), style = OdType.heading, maxLines = 1)
        } else {
            Column(Modifier.weight(1f).offset(x = (-12).dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OdText(title, style = OdType.heading, maxLines = 1)
                subtitle()
            }
        }
    }
}

/** How one device shows in the top bar's status cluster (design/system.md §7.7). */
enum class DeviceIconState {
    /** Connected: secondary text. */
    Ok,

    /** Not paired: tertiary text. */
    NotPaired,

    /** Lost or locked: primary text with an alert badge. */
    Attention,
}

@Immutable
data class DeviceStatusIcon(
    val icon: ImageVector,
    val state: DeviceIconState,
    val label: String,
)

/** Three 16 dp device icons in a row, in the order trainer, strap, Click. Tapping opens Devices. */
@Composable
fun DeviceStatusCluster(
    items: List<DeviceStatusIcon>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteractionSource()
    Row(
        modifier
            .height(OverdriveDimens.sizeTouchMin)
            .plainClickable(source, onClick = onClick)
            .semantics { contentDescription = items.joinToString(", ") { "${it.label} ${it.state.name}" } },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            val colour =
                when (item.state) {
                    DeviceIconState.Ok -> OverdriveColors.textSecondary
                    DeviceIconState.NotPaired -> OverdriveColors.textTertiary
                    DeviceIconState.Attention -> OverdriveColors.textPrimary
                }
            Row(
                Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdIcon(item.icon, size = InlineIconSize, tint = colour)
                if (item.state ==
                    DeviceIconState.Attention
                ) {
                    OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize, tint = colour)
                }
            }
        }
    }
}

/**
 * The device-search spinner (design/system.md §7.7): a 20 dp ring, 2 dp stroke, a surface 3 track and a secondary
 * text arc, one turn per second, linear. It loops only while it is on screen, which is while a search runs.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    diameter: Dp = 20.dp,
) {
    val motion = LocalMotionEnabled.current
    val turn =
        if (motion) {
            val transition = rememberInfiniteTransition(label = "spinner")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        tween(OverdriveMotion.durationSpinner, easing = LinearEasing),
                        RepeatMode.Restart,
                    ),
                label = "angle",
            )
            angle
        } else {
            0f
        }
    Canvas(modifier.size(diameter)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(OverdriveColors.surface3, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        rotate(turn) {
            drawArc(
                OverdriveColors.textSecondary,
                -90f,
                90f,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
    }
}

/**
 * A confirmation sheet at the bottom of the screen (design/system.md §6.3): a 70% black scrim and a surface 1
 * panel with a title and two 64 dp buttons. In 200 ms decelerating, out 150 ms accelerating; instant when the
 * system removes animations. Tapping the scrim dismisses it.
 */
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
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
                                ) {
                                    it
                                }
                            } else {
                                EnterTransition.None
                            },
                        exit =
                            if (motion) {
                                slideOutVertically(
                                    tween(OverdriveMotion.durationFast, easing = OverdriveMotion.easingAccelerate),
                                ) {
                                    it
                                }
                            } else {
                                ExitTransition.None
                            },
                    ).width(560.dp)
                    .clickable(interactionSource = noInteraction, indication = null) {}
                    .surface(
                        OverdriveColors.surface1,
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        OverdriveColors.border,
                    ).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OdText(title, style = OdType.title)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OdButton(
                        confirmLabel,
                        onConfirm,
                        Modifier.weight(1f),
                        height = OverdriveDimens.sizeButtonHeightRide,
                    )
                    OdButton(
                        dismissLabel,
                        onDismiss,
                        Modifier.weight(1f),
                        ButtonVariant.Primary,
                        height = OverdriveDimens.sizeButtonHeightRide,
                    )
                }
            }
        }
    }
}
