package app.overdrive.ride.host

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import app.overdrive.core.control.MediaKeys

/**
 * The three media buttons of the ride bar (design/system.md §6.2): play or pause whatever app is playing, and the
 * volume. They are core's [MediaKeys], so the same object is the media of the Click's router (spec §4.7, ADR 0012): a
 * press on the handlebar with a video app in front reaches the video through these calls. Both are system APIs that need
 * no permission; a refusal is swallowed because a ride must never fail on a media key. That a media key and a volume step
 * from the app's background reach the video app on the tablet is bench-checked (overlay-notes §7, bench 5.6).
 */
interface MediaController : MediaKeys {
    override fun playPause()

    override fun volumeUp()

    override fun volumeDown()
}

class AudioManagerMediaController(
    context: Context,
) : MediaController {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun playPause() {
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
    }

    override fun volumeUp() = adjust(AudioManager.ADJUST_RAISE)

    override fun volumeDown() = adjust(AudioManager.ADJUST_LOWER)

    private fun adjust(direction: Int) {
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }
}

/** Does nothing; for previews and tests. */
object NoMedia : MediaController {
    override fun playPause() = Unit

    override fun volumeUp() = Unit

    override fun volumeDown() = Unit
}
