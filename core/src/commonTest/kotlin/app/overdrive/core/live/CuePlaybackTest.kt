package app.overdrive.core.live

import app.overdrive.core.wellness.WellnessTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The decisions about playing a cue (ttl, queue, mute, focus refused, errors), over a fake player and in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CuePlaybackTest {
    private sealed interface Plan {
        /** Speaks for this many milliseconds. */
        class Speaks(
            val millis: Long,
        ) : Plan

        data object Refuses : Plan

        data object Fails : Plan

        data object Throws : Plan

        /** Begins and never ends, until it is cancelled. */
        data object Hangs : Plan
    }

    private class Rig(
        val test: TestScope,
        private val plans: Map<Int, Plan> = emptyMap(),
    ) : CuePlayer {
        val played = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val reports = mutableListOf<Pair<Int, Boolean>>()
        val plates = mutableListOf<Pair<Long, String?>>()

        val playback =
            CuePlayback(
                scope = test,
                player = this,
                nowMillis = { test.currentTime },
                report = { id, heard -> reports += id to heard },
                onPlate = { plates += test.currentTime to it },
            )

        override suspend fun play(
            audio: ByteArray,
            format: LiveAudioFormat,
            onStarted: () -> Unit,
        ): CuePlayResult {
            val id = audio[0].toInt()
            played += id
            return when (val plan = plans[id] ?: Plan.Speaks(4_000)) {
                is Plan.Speaks -> {
                    onStarted()
                    try {
                        delay(plan.millis)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelled += id
                        throw e
                    }
                    CuePlayResult.PLAYED
                }

                Plan.Refuses -> {
                    CuePlayResult.FOCUS_REFUSED
                }

                Plan.Fails -> {
                    CuePlayResult.FAILED
                }

                Plan.Throws -> {
                    error("the player broke")
                }

                Plan.Hangs -> {
                    onStarted()
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled += id
                    }
                }
            }
        }
    }

    private fun cue(
        id: Int,
        ttlMs: Int = 8_000,
        text: String = "Cue $id",
    ): LiveCue =
        LiveCue(
            id = id,
            text = text,
            trigger = "step-start",
            audio = LiveCueAudio(LiveAudioFormat.MP3, Base64.encode(byteArrayOf(id.toByte(), 0, 0))),
            createdAt = "2026-11-19T07:42:10.250Z",
            expiresAt =
                WellnessTime.formatInstant(checkNotNull(WellnessTime.parseInstant("2026-11-19T07:42:10.250Z")) + ttlMs),
            ttlMs = ttlMs,
        )

    @Test
    fun `a cue within its time is played shown while it is spoken and for six seconds after and reported`() =
        runTest {
            val rig = Rig(this)
            rig.playback.offer(cue(1))
            runCurrent()
            assertEquals(listOf(1), rig.played)
            assertEquals(listOf<Pair<Long, String?>>(0L to "Cue 1"), rig.plates)
            advanceTimeBy(3_999)
            assertEquals(0, rig.reports.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(1 to true), rig.reports, "reported when playback completes")
            advanceTimeBy(5_999)
            assertEquals(1, rig.plates.size, "the plate stays")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf<Pair<Long, String?>>(0L to "Cue 1", 10_000L to null),
                rig.plates,
                "4 s of speech and 6 s after",
            )
        }

    @Test
    fun `a cue that waited behind another past its time is dropped silently and never played late`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Speaks(9_000)))
            rig.playback.offer(cue(1, ttlMs = 20_000))
            runCurrent()
            rig.playback.offer(cue(2, ttlMs = 8_000))
            advanceTimeBy(9_000)
            runCurrent()
            assertEquals(listOf(1), rig.played, "the second ran out of time at 8 s: the player was never asked")
            assertEquals(listOf(1 to true, 2 to false), rig.reports)
            assertFalse(rig.plates.any { it.second == "Cue 2" }, "no plate for a cue that was not spoken")
        }

    @Test
    fun `a cue that waited and is still within its time is played after the first`() =
        runTest {
            val rig = Rig(this)
            rig.playback.offer(cue(1))
            runCurrent()
            advanceTimeBy(1_000)
            rig.playback.offer(cue(2, ttlMs = 8_000))
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(listOf(1, 2), rig.played)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf(1 to true, 2 to true), rig.reports)
            // The second's plate replaced the first's without going away in between.
            assertEquals(listOf<Pair<Long, String?>>(0L to "Cue 1", 4_000L to "Cue 2"), rig.plates.take(2))
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(null, rig.plates.last().second)
            assertEquals(14_000L, rig.plates.last().first)
        }

    @Test
    fun `one cue at a time and at most two wait behind it the oldest dropped`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Speaks(30_000)))
            rig.playback.offer(cue(1, ttlMs = 600_000))
            runCurrent()
            for (id in 2..5) rig.playback.offer(cue(id, ttlMs = 600_000))
            runCurrent()
            assertEquals(listOf(1), rig.played, "only one at a time")
            assertEquals(listOf(2 to false, 3 to false), rig.reports, "the oldest waiting were dropped")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(1, 4), rig.played)
        }

    @Test
    fun `focus refused drops the cue with no plate and the next one is still tried`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Refuses))
            rig.playback.offer(cue(1))
            rig.playback.offer(cue(2))
            runCurrent()
            assertEquals(listOf(1, 2), rig.played)
            assertEquals(listOf(1 to false), rig.reports.take(1))
            assertEquals("Cue 2", rig.plates.first().second, "the first had no plate")
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf(1 to false, 2 to true), rig.reports)
        }

    @Test
    fun `a player that fails or throws drops the cue raises nothing and the next cue plays`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Fails, 2 to Plan.Throws))
            for (id in 1..3) rig.playback.offer(cue(id, ttlMs = 60_000))
            runCurrent()
            assertEquals(listOf(1, 2, 3), rig.played)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf(1 to false, 2 to false, 3 to true), rig.reports)
        }

    @Test
    fun `mute cuts off what is being spoken drops what waits and the plate and ignores every cue after`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Hangs))
            rig.playback.offer(cue(1))
            runCurrent()
            rig.playback.offer(cue(2))
            runCurrent()
            assertEquals(listOf<Pair<Long, String?>>(0L to "Cue 1"), rig.plates)
            advanceTimeBy(1_000)
            rig.playback.mute()
            runCurrent()
            assertEquals(listOf(1), rig.cancelled, "the sound was stopped")
            assertEquals(
                listOf<Pair<Long, String?>>(0L to "Cue 1", 1_000L to null),
                rig.plates,
                "the plate went at once",
            )
            assertEquals(setOf(1 to false, 2 to false), rig.reports.toSet())
            rig.playback.offer(cue(3))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf(1), rig.played, "nothing more is played, ever")
            assertEquals(3 to false, rig.reports.last(), "an ignored cue says it was not heard")
            assertEquals(2, rig.plates.size)
        }

    @Test
    fun `mute before any cue is the same and sticky`() =
        runTest {
            val rig = Rig(this)
            rig.playback.mute()
            runCurrent()
            for (id in 1..3) rig.playback.offer(cue(id))
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(0, rig.played.size)
            assertEquals(listOf(1 to false, 2 to false, 3 to false), rig.reports)
        }

    @Test
    fun `closing cuts off the sound and ignores what comes after with no report for a cue it never saw`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Hangs))
            rig.playback.offer(cue(1))
            runCurrent()
            rig.playback.close()
            runCurrent()
            assertEquals(listOf(1), rig.cancelled)
            rig.playback.offer(cue(2))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf(1), rig.played)
            assertEquals(listOf(1 to false), rig.reports)
            assertEquals(null, rig.plates.last().second)
        }

    @Test
    fun `a cue the stream resends is not played twice and says nothing`() =
        runTest {
            val rig = Rig(this)
            rig.playback.offer(cue(1))
            runCurrent()
            advanceTimeBy(4_000)
            rig.playback.offer(cue(1))
            rig.playback.offer(cue(1))
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf(1), rig.played)
            assertEquals(listOf(1 to true), rig.reports)
        }

    @Test
    fun `a cue's time is counted from when it arrived not from when it was made`() =
        runTest {
            // ttlMs 8000 from receipt: arrives at 10 s, may start up to 18 s.
            val rig = Rig(this, mapOf(1 to Plan.Speaks(7_000)))
            advanceTimeBy(10_000)
            rig.playback.offer(cue(1, ttlMs = 20_000))
            runCurrent()
            rig.playback.offer(cue(2, ttlMs = 8_000))
            advanceTimeBy(7_000)
            runCurrent()
            // The second arrived at 10 s with 8 s to live: it was due by 18 s, and the first ended at 17 s.
            assertEquals(listOf(1, 2), rig.played)
        }

    @Test
    fun `a cue that is exactly out of time is not played`() =
        runTest {
            val rig = Rig(this, mapOf(1 to Plan.Speaks(8_000)))
            rig.playback.offer(cue(1, ttlMs = 30_000))
            runCurrent()
            rig.playback.offer(cue(2, ttlMs = 8_000))
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals(listOf(1), rig.played, "at exactly its ttl it is over")
        }
}
