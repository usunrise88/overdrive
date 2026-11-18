package app.overdrive.ride.host

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * The three media buttons of the ride bar (design/system.md §6.2): play or pause whatever app is playing, and the
 * volume. They are the same calls the Click's buttons 3 to 5 make in M4. Both are system APIs that need no permission;
 * a refusal is swallowed because a ride must never fail on a media key.
 */
interface MediaController {
    fun playPause()

    fun volumeUp()

    fun volumeDown()
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
