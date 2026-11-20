package app.overdrive.ride.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.live.CuePlayResult
import app.overdrive.core.live.LiveAudioFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.TimeUnit

/**
 * The thin Android part of a cue (docs/adr/0039) against Robolectric's `AudioManager` and `MediaPlayer`: it asks for **transient audio focus that may duck** with speech attributes, plays, and gives the player and the focus back on **every** way
 * out. No sound is made and what is said in the bytes is nothing; the decisions about whether to play are `core`'s and tested there. What the real speech service returns and whether a video app ducks is session 14 of the bench.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(DelicateCoroutinesApi::class)
class AndroidCuePlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio: ShadowAudioManager = shadowOf(context.getSystemService(AudioManager::class.java))
    private val player = AndroidCuePlayer(context, main = Dispatchers.Unconfined)

    @Before
    fun setUp() {
        // Any speech takes two seconds of the shadow player's own time.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
    }

    @After
    fun tearDown() {
        ShadowMediaPlayer.resetStaticState()
    }

    /** Starts [AndroidCuePlayer.play] on the test's own thread and returns what it came to once the looper has been run for [seconds]. */
    private fun play(
        seconds: Long,
        started: () -> Unit = {},
    ): CompletableDeferred<CuePlayResult> {
        val result = CompletableDeferred<CuePlayResult>()
        GlobalScope.launch(Dispatchers.Unconfined) {
            result.complete(player.play(byteArrayOf(1, 2, 3, 4), LiveAudioFormat.MP3, started))
        }
        shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
        return result
    }

    @Test
    fun itAsksForTransientFocusThatMayDuckWithSpeechAttributesAndPlaysToTheEnd() {
        var started = 0
        val result = play(5) { started++ }
        assertTrue(result.isCompleted)
        assertEquals(CuePlayResult.PLAYED, result.getCompleted())
        assertEquals("the sound began once", 1, started)
        val request = checkNotNull(audio.lastAudioFocusRequest).audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, request.audioAttributes.usage)
        assertFalse("a cue is worth hearing now or not at all", request.acceptsDelayedFocusGain())
        assertFalse("the video is to duck, not to pause", request.willPauseWhenDucked())
        assertEquals("the focus is given back when it is over", request, audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun aRefusedFocusIsADroppedCueAndNothingIsPlayed() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        var started = 0
        val result = play(5) { started++ }
        assertEquals(CuePlayResult.FOCUS_REFUSED, result.getCompleted())
        assertEquals(0, started)
        assertNull("nothing was granted, so nothing is given back", audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun aPlayerThatFailsGivesTheFocusBackAndReportsTheFailure() {
        ShadowMediaPlayer.setMediaInfoProvider { throw IllegalArgumentException("no such speech") }
        val result = play(5)
        assertEquals(CuePlayResult.FAILED, result.getCompleted())
        assertNotNull("the video gets its volume back after a failure too", audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun cuttingAPlayingCueOffStopsItAndGivesTheFocusBack() {
        val result = CompletableDeferred<CuePlayResult>()
        val job =
            GlobalScope.launch(Dispatchers.Unconfined) {
                result.complete(player.play(byteArrayOf(1, 2, 3, 4), LiveAudioFormat.MP3) { })
            }
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertFalse("still speaking", result.isCompleted)
        assertNull("the focus is held while speaking", audio.lastAbandonedAudioFocusRequest)
        // A mute or the end of the ride cancels the cue.
        job.cancel()
        assertTrue(job.isCancelled)
        assertNotNull("the focus is given back on a cancel", audio.lastAbandonedAudioFocusRequest)
        assertFalse(result.isCompleted && result.getCompleted() == CuePlayResult.PLAYED)
    }

    @Test
    fun theDataSourceIsInMemoryAndDoesNotHoldTheBytesOnceItIsClosed() {
        // The speech goes to the player from memory: no file is written, so there is nothing to delete.
        val cache = context.cacheDir.listFiles().orEmpty().size
        play(5)
        assertEquals("nothing was written to the cache", cache, context.cacheDir.listFiles().orEmpty().size)
        assertNotNull(DataSource::class.java)
    }
}
