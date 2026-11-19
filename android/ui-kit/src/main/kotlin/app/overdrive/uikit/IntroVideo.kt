package app.overdrive.uikit

import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.delay

/** The film's own background (`#05070a`, design/tools/intro/intro.html): the colour of its first frame and of its edges. */
val IntroBackground: Color = Color(0xFF05070A)

/**
 * Plays the raw video [rawRes] once, with Android's own [VideoView] (no library, no Play Services), and nothing else: it fills
 * its box with the aspect ratio kept, on [IntroBackground] so that the bars of a screen of another shape do not show, without
 * sound and without taking audio focus (the rider's own music or video keeps playing).
 *
 * [onEnded] is called when the film has played to its end; the last frame stays on screen. [onFailed] is called when the
 * player reports an error, or when nothing has been rendered [INTRO_START_LIMIT_MS] after the start. The film pauses when the
 * activity stops and carries on when it starts again ([introResume]); the player is released when this leaves the composition.
 *
 * The player draws on a surface of its own. Whatever Compose draws above this composable is seen above the film, but alpha,
 * clip and scale applied to it or to a parent do not reach the film itself: fade it with a cover drawn above it ([IntroFade]).
 */
@Composable
fun IntroVideo(
    rawRes: Int,
    onEnded: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestEnded = rememberUpdatedState(onEnded)
    val latestFailed = rememberUpdatedState(onFailed)
    val rendered = remember { mutableStateOf(false) }
    val player = remember { IntroPlayer() }

    // A film that does not start within the limit is given up, so that a broken player never holds the app behind a black screen.
    LaunchedEffect(Unit) {
        delay(INTRO_START_LIMIT_MS)
        if (introFailedToStart(rendered.value, player.positionMs())) latestFailed.value()
    }

    // The activity's lifecycle, found through the view tree: no lifecycle-compose dependency in this module.
    val view = LocalView.current
    val lifecycle = remember(view) { view.findViewTreeLifecycleOwner()?.lifecycle }
    DisposableEffect(lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> player.stopped()
                    Lifecycle.Event.ON_START -> player.started()
                    else -> Unit
                }
            }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            player.release()
        }
    }

    Box(modifier.background(IntroBackground)) {
        AndroidView(
            factory = { context ->
                val video =
                    VideoView(context).apply {
                        // Muted film: do not ask for audio focus, which would pause what the rider is listening to (API 26).
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        setOnPreparedListener { it.setVolume(0f, 0f) }
                        setOnInfoListener { _, what, _ ->
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendered.value = true
                            false
                        }
                        setOnCompletionListener {
                            player.filmEnded = true
                            latestEnded.value()
                        }
                        // True: the error is handled here and the player shows no "Can't play this video" dialog.
                        setOnErrorListener { _, _, _ ->
                            latestFailed.value()
                            true
                        }
                        setVideoURI(Uri.parse("android.resource://${context.packageName}/$rawRes"))
                        start()
                    }
                player.video = video
                // The video view takes the largest size of the film's shape that fits and the frame puts it in the middle: left
                // to fill the host view directly, the surface would be stretched to the box.
                FrameLayout(context).apply {
                    addView(
                        video,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { player.release() },
        )
        // Until the first frame is on screen the surface is transparent: hide it behind the film's own colour.
        if (!rendered.value) Box(Modifier.fillMaxSize().background(IntroBackground))
    }
}

/** The player behind [IntroVideo]: the view, and what is needed to put it back after the activity was stopped. */
private class IntroPlayer {
    var video: VideoView? = null
    var filmEnded = false
    private var resume: IntroResume? = null

    fun positionMs(): Int = video?.currentPosition ?: 0

    fun stopped() {
        val view = video ?: return
        resume = introResume(filmEnded, view.currentPosition, view.duration)
        if (!filmEnded) view.pause()
    }

    fun started() {
        val view = video ?: return
        val plan = resume ?: return
        resume = null
        view.seekTo(plan.seekToMs)
        if (plan.play) view.start()
    }

    fun release() {
        video?.stopPlayback()
        video = null
    }
}
