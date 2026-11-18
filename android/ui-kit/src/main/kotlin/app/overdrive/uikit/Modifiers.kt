package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.overdrive.uikit.tokens.OverdriveDimens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A flat fill with an optional 1 dp hairline edge: the only way surfaces are separated (no shadows). */
fun Modifier.surface(
    fill: Color,
    shape: Shape,
    edge: Color? = null,
): Modifier = this.background(fill, shape).then(if (edge != null) Modifier.border(OverdriveDimens.strokeHairline, edge, shape) else Modifier)

/** A click without any indication: pressed feedback is drawn by the component itself (design/system.md §7.1). */
fun Modifier.plainClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = this.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = role, onClick = onClick)

/**
 * Fires [onClick] on press, then again every [intervalMillis] after [initialDelayMillis] while the pointer stays
 * down: the volume buttons (150 ms) and the steppers. Reports presses to [interactionSource] for the pressed look.
 */
@Composable
fun Modifier.repeatingClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    initialDelayMillis: Long = REPEAT_INITIAL_DELAY_MS,
    intervalMillis: Long = REPEAT_INTERVAL_MS,
    onClick: () -> Unit,
): Modifier {
    val latest by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    return if (!enabled) {
        this
    } else {
        this
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    latest()
                    true
                }
            }.pointerInput(interactionSource, initialDelayMillis, intervalMillis) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        coroutineScope {
                            val repeater =
                                scope.launch {
                                    latest()
                                    delay(initialDelayMillis)
                                    while (true) {
                                        latest()
                                        delay(intervalMillis)
                                    }
                                }
                            val released = tryAwaitRelease()
                            repeater.cancel()
                            interactionSource.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        }
                    },
                )
            }
    }
}

/** True while the pointer is down on [this] source. */
@Composable
fun MutableInteractionSource.pressed(): Boolean {
    val state by collectIsPressedAsState()
    return state
}

/** A fresh interaction source for one component. */
@Composable
fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

const val REPEAT_INITIAL_DELAY_MS: Long = 400
const val REPEAT_INTERVAL_MS: Long = 150
