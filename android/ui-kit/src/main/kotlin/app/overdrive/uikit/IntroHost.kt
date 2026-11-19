package app.overdrive.uikit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/**
 * The launch film over an app (design/brand.md "The launch animation", ADR 0031). [content] is the app's own root; it is
 * composed from the first frame, under the film, so the app's start-up does not wait for the film. While the film is up, the
 * film's layer is opaque, covers the whole window (the status and navigation bars too: the host must not pad it), takes every
 * touch, and nothing under it can take focus.
 *
 * - **With [continueLabel]** (the ride app): when the film ends, or on a tap during it, a primary button with that label is
 *   shown at the bottom end of the screen, inside the system bars, with initial focus so that Enter works from a keyboard. The
 *   film's last frame stays behind it. Pressing it fades the layer out.
 * - **Without** (the companion): the end of the film, or a tap, fades the layer out at once.
 *
 * The fade takes [fadeMillis] and is the two halves of [IntroFade]. A film that fails removes the layer at once. [play] false
 * shows no film. That the film is over is saved with the instance state, so a rotation does not play it again.
 */
@Composable
fun IntroHost(
    play: Boolean,
    rawRes: Int,
    fadeMillis: Int,
    modifier: Modifier = Modifier,
    continueLabel: String? = null,
    content: @Composable () -> Unit,
) {
    var over by rememberSaveable { mutableStateOf(false) }
    val showing = play && !over
    Box(modifier.fillMaxSize()) {
        // Under the film nothing takes focus (a focus property reaches the whole subtree, so the film's own button is outside).
        Box(
            Modifier.fillMaxSize().then(if (showing) Modifier.focusProperties { canFocus = false } else Modifier),
        ) { content() }
        if (showing) IntroLayer(rawRes, continueLabel, fadeMillis, onFinished = { over = true })
    }
}

@Composable
private fun IntroLayer(
    rawRes: Int,
    continueLabel: String?,
    fadeMillis: Int,
    onFinished: () -> Unit,
) {
    val needsContinue = continueLabel != null
    var stage by remember { mutableStateOf(IntroStage.Playing) }
    val send =
        rememberUpdatedState<
            (
                IntroEvent,
            ) -> Unit,
        > { event -> stage = nextIntroStage(stage, event, needsContinue) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(stage) {
        when (stage) {
            IntroStage.Leaving -> {
                fade.animateTo(1f, tween(fadeMillis, easing = LinearEasing))
                send.value(IntroEvent.FadeEnded)
            }

            IntroStage.Finished -> {
                onFinished()
            }

            IntroStage.Playing, IntroStage.Waiting -> {
                Unit
            }
        }
    }

    val playerVisible by remember { derivedStateOf { IntroFade.playerVisible(fade.value) } }
    Box(
        Modifier.fillMaxSize().drawBehind { drawRect(IntroBackground.copy(alpha = IntroFade.layerAlpha(fade.value))) },
    ) {
        if (playerVisible) {
            IntroVideo(
                rawRes,
                onEnded = { send.value(IntroEvent.FilmEnded) },
                onFailed = { send.value(IntroEvent.FilmFailed) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The cover: dark over the film while it leaves, and the one thing that takes the touches (the player's own view is
        // below it). It is empty when the film plays.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(IntroBackground.copy(alpha = IntroFade.coverAlpha(fade.value))) }
                .pointerInput(Unit) { detectTapGestures { send.value(IntroEvent.Tapped) } },
        )
        if (continueLabel != null && (stage == IntroStage.Waiting || stage == IntroStage.Leaving)) {
            ContinueButton(
                label = continueLabel,
                onClick = { send.value(IntroEvent.ContinuePressed) },
                // The button goes with the first half of the fade, over the cover.
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(20.dp)
                        .graphicsLayer { alpha = 1f - IntroFade.coverAlpha(fade.value) },
            )
        }
    }
}

/**
 * The ride app's primary button at the end of the film (design/system.md §7.1, the 64 dp ride height). A second press while the
 * layer fades is ignored by [nextIntroStage]. It takes focus when it appears, so Enter or the d-pad centre presses it; focus shows as the 2 dp ring of §7.1, 2 dp outside the edge.
 */
@Composable
private fun ContinueButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // In touch mode a clickable cannot hold focus and the request is refused: the first key press then moves focus here, as
    // nothing else under the film can take it.
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Box(
        modifier
            .border(
                OverdriveDimens.strokeFocus,
                if (focused) OverdriveColors.textPrimary else Color.Transparent,
                RoundedCornerShape(OverdriveDimens.radiusMd + 4.dp),
            ).padding(4.dp),
    ) {
        OdButton(
            text = label,
            onClick = onClick,
            variant = ButtonVariant.Primary,
            height = OverdriveDimens.sizeButtonHeightRide,
            minWidth = 160.dp,
            horizontalPadding = 28.dp,
            modifier = Modifier.onFocusChanged { focused = it.isFocused }.focusRequester(requester),
        )
    }
}
