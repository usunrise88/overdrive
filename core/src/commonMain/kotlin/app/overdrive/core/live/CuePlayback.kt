package app.overdrive.core.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How one cue's speech ended. */
public enum class CuePlayResult {
    /** It played to its end. */
    PLAYED,

    /** The system would not give audio focus (a call, an alarm, another app that insists): the cue is not spoken. */
    FOCUS_REFUSED,

    /** Anything else went wrong: the audio could not be decoded or played. */
    FAILED,
}

/**
 * Speaks one cue: the only thing the platform has to do (docs/adr/0039). The Android implementation asks for **transient audio focus that
 * lets the video duck** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, speech content), plays the bytes with `MediaPlayer` from a file in the app's
 * cache that it deletes when done, and gives the focus back on every path out. A test gives a fake.
 */
public interface CuePlayer {
    /**
     * Plays [audio] (in [format]) and suspends until it has been heard to its end. [onStarted] is called when the sound begins, which is when the
     * cue's plate appears. **Cancelling stops the sound at once** and gives audio focus back. Returns how it went and **never throws** except for
     * cancellation: a refusal of focus and every error are a [CuePlayResult].
     */
    public suspend fun play(
        audio: ByteArray,
        format: LiveAudioFormat,
        onStarted: () -> Unit,
    ): CuePlayResult
}

/**
 * The decisions about playing the service's cues (spec §12.1, docs/adr/0039), over a [CuePlayer]: pure Kotlin with no Android, tested with a fake
 * player in virtual time.
 *
 * - **A cue is played only within its `ttlMs` of receipt**, the moment [offer] was called (the app does not trust its clock against the
 *   service's): one that was waiting behind another and has run out of time is dropped, **never played late**. The player is not asked and
 *   nothing is shown.
 * - **One at a time.** A cue that arrives while another is being spoken waits, behind at most [MAX_WAITING] others: the oldest waiting is dropped
 *   when more come (the newest is the one worth hearing).
 * - **Mute** is for the rest of the ride: from then on every cue is ignored, what waits is dropped and what is being spoken is cut off, with the plate.
 * - **A cue is played once.** The id of a cue is its number in the session, so one at or below the last seen (a stream that came back and
 *   resent it) is ignored.
 * - **Focus refused, an error, a cue that cannot be decoded:** the cue is dropped, the next one is tried, and nothing is raised.
 * - **The plate** (the cue's text, [onPlate]) appears when the sound begins and goes [plateMillis] after it ends, or at once on mute and close; a cue
 *   that is not spoken has no plate.
 * - **Every cue is reported** through [report] with whether it was heard (`POST …/played`, optional for the service): a cue that was dropped for
 *   any reason says `false`, so the log of the ride tells the planning agent whether the rider was told. A repeated cue says nothing.
 * - **The audio is held no longer than it takes to play it:** decoded when its turn comes and given to the player, kept by nothing here.
 *
 * Everything runs on [scope], which must be single-threaded (the app gives it one thread): [offer], [mute] and [close] may be called from any
 * thread and are carried out there, in the order they were called. [nowMillis] is a monotonic clock in the time of `delay` (virtual in a test).
 */
public class CuePlayback(
    private val scope: CoroutineScope,
    private val player: CuePlayer,
    private val nowMillis: () -> Long,
    private val report: (cueId: Int, played: Boolean) -> Unit,
    private val onPlate: (String?) -> Unit,
    private val plateMillis: Long = PLATE_MILLIS,
) {
    private class Waiting(
        val cue: LiveCue,
        val deadline: Long,
    )

    private val waiting = ArrayDeque<Waiting>()
    private var playing: Job? = null
    private var plateHide: Job? = null
    private var lastSeenId = 0
    private var current = 0
    private var plateShown = false
    private var muted = false
    private var closed = false

    /** A cue arrived from the stream, now. Safe from any thread. */
    public fun offer(cue: LiveCue) {
        val receivedAt = nowMillis()
        scope.launch { accept(cue, receivedAt) }
    }

    /** The rider muted the coach for the rest of the ride. */
    public fun mute() {
        scope.launch {
            muted = true
            clear()
        }
    }

    /** The session is over (the ride ended, the coach stopped): nothing more is played and what is playing is cut off. */
    public fun close() {
        scope.launch {
            closed = true
            clear()
        }
    }

    private fun accept(
        cue: LiveCue,
        receivedAt: Long,
    ) {
        if (closed || cue.id <= lastSeenId) return
        lastSeenId = cue.id
        if (muted) {
            report(cue.id, false)
            return
        }
        waiting.addLast(Waiting(cue, receivedAt + cue.ttlMs))
        while (waiting.size > MAX_WAITING) report(waiting.removeFirst().cue.id, false)
        if (playing == null) startNext()
    }

    private fun clear() {
        while (waiting.isNotEmpty()) report(waiting.removeFirst().cue.id, false)
        playing?.cancel()
        hidePlate()
    }

    private fun startNext() {
        while (true) {
            val next = waiting.removeFirstOrNull() ?: return
            if (muted || closed || nowMillis() >= next.deadline) {
                report(next.cue.id, false)
                continue
            }
            playing = scope.launch { speak(next.cue) }
            return
        }
    }

    private suspend fun speak(cue: LiveCue) {
        var heard = false
        current = cue.id
        try {
            val result =
                try {
                    val audio = cue.audio.decode()
                    // The player may call this from another thread: the plate is changed here, where everything else is.
                    player.play(audio, cue.audio.format) {
                        scope.launch {
                            // Not for a cue that was cut off in the meantime.
                            if (current == cue.id) {
                                plateHide?.cancel()
                                plateHide = null
                                plateShown = true
                                onPlate(cue.text)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CuePlayResult.FAILED
                }
            heard = result == CuePlayResult.PLAYED
            if (heard) {
                plateHide =
                    scope.launch {
                        delay(plateMillis)
                        plateHide = null
                        hidePlate()
                    }
            } else {
                hidePlate()
            }
        } finally {
            // Cut off by a mute or a close: the cue was not heard to its end, and the plate goes with it.
            report(cue.id, heard)
            playing = null
            current = 0
        }
        startNext()
    }

    private fun hidePlate() {
        plateHide?.cancel()
        plateHide = null
        if (plateShown) {
            plateShown = false
            onPlate(null)
        }
    }

    public companion object {
        /** The plate stays this long after the cue has been spoken. */
        public const val PLATE_MILLIS: Long = 6_000

        /** At most this many cues wait behind the one being spoken. */
        public const val MAX_WAITING: Int = 2
    }
}
