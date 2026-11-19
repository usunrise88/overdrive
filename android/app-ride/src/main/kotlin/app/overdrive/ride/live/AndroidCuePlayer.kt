package app.overdrive.ride.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import app.overdrive.core.live.CuePlayResult
import app.overdrive.core.live.CuePlayer
import app.overdrive.core.live.LiveAudioFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Speaks one cue with the platform's `MediaPlayer` (spec §12.1, docs/adr/0039), as little as it can be: **all the decisions are `core`'s** ([CuePlayer]'s
 * callers); this asks the system for **transient audio focus that lets the video duck**, plays the bytes, and gives the focus and the player back on every
 * way out.
 *
 * - **Audio focus**: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with attributes for **speech** and **navigation-style guidance**, which is what a video app lowers its
 *   volume for and raises again when the focus is abandoned. A request the system refuses (a call, an alarm) is [CuePlayResult.FOCUS_REFUSED]; the cue is
 *   dropped, never played over it. Delayed focus is not accepted: a cue is worth hearing now or not at all. Losing the focus while speaking (a call comes in)
 *   stops the cue, which then counts as not heard.
 * - **The speech** is given to `MediaPlayer` from memory (a `MediaDataSource` over the decoded bytes), so **no file is written anywhere** and nothing is left to
 *   clean up; the data source lets go of the bytes when the player is released.
 * - **Every path out** (done, an error, focus lost, cancelled by a mute or the end of the ride, the time limit) releases the player and abandons the focus, in a
 *   `finally`.
 * - **It runs on the main thread**, where `MediaPlayer` wants to be (its callbacks come back there); the work is a few calls and a wait.
 *
 * Nothing here logs: not the text, which this never sees, and not the bytes.
 *
 * @param maxMillis the longest a cue may take, from the request for focus: a player that never reports is cut off (a cue is at most about 8 s of speech).
 */
class AndroidCuePlayer(
    private val context: Context,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val maxMillis: Long = MAX_CUE_MILLIS,
) : CuePlayer {
    @Suppress("UNUSED_PARAMETER")
    override suspend fun play(
        audio: ByteArray,
        format: LiveAudioFormat,
        onStarted: () -> Unit,
    ): CuePlayResult =
        withContext(main) {
            val manager = context.getSystemService(AudioManager::class.java) ?: return@withContext CuePlayResult.FAILED
            val lost = CompletableDeferred<Unit>()
            val focus =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(ATTRIBUTES)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        ) {
                            lost.complete(Unit)
                        }
                    }.build()
            val granted =
                try {
                    manager.requestAudioFocus(focus)
                } catch (e: RuntimeException) {
                    return@withContext CuePlayResult.FAILED
                }
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@withContext CuePlayResult.FOCUS_REFUSED
            val source = BytesDataSource(audio)
            var player: MediaPlayer? = null
            try {
                withTimeout(maxMillis) {
                    val finished = CompletableDeferred<CuePlayResult>()
                    val created = MediaPlayer()
                    player = created
                    created.setAudioAttributes(ATTRIBUTES)
                    created.setDataSource(source)
                    created.setOnPreparedListener {
                        it.start()
                        onStarted()
                    }
                    created.setOnCompletionListener { finished.complete(CuePlayResult.PLAYED) }
                    created.setOnErrorListener { _, _, _ ->
                        finished.complete(CuePlayResult.FAILED)
                        true
                    }
                    lost.invokeOnCompletion { finished.complete(CuePlayResult.FAILED) }
                    created.prepareAsync()
                    finished.await()
                }
            } catch (e: TimeoutCancellationException) {
                CuePlayResult.FAILED
            } catch (e: java.io.IOException) {
                CuePlayResult.FAILED
            } catch (e: IllegalStateException) {
                CuePlayResult.FAILED
            } catch (e: IllegalArgumentException) {
                CuePlayResult.FAILED
            } finally {
                // Whatever happened, including a cancel: the sound stops, the player is let go, the video gets its volume back.
                try {
                    player?.release()
                } catch (e: RuntimeException) {
                    // Nothing to do about a player that will not let go.
                }
                source.close()
                manager.abandonAudioFocusRequest(focus)
            }
        }

    /** The speech, from memory. Closed when the player is released, and the bytes go with it. */
    private class BytesDataSource(
        bytes: ByteArray,
    ) : MediaDataSource() {
        private var data: ByteArray? = bytes

        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int {
            val bytes = data ?: return END
            if (position < 0 || position >= bytes.size) return END
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data?.size?.toLong() ?: END.toLong()

        override fun close() {
            data = null
        }

        private companion object {
            const val END = -1
        }
    }

    companion object {
        /** The longest a cue may take from the request for focus: far more than 80 characters of speech. */
        const val MAX_CUE_MILLIS = 30_000L

        /** Speech, as guidance: what a video app lowers its sound for (the navigation prompts of a map are the same). */
        private val ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
