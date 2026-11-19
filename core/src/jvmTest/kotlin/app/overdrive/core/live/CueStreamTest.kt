package app.overdrive.core.live

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cue stream's reconnecting reader (docs/adr/0039), over a scripted source and in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CueStreamTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun cue(id: Int): String =
        LiveCoachJson.encodeCue(
            LiveCue(
                id = id,
                text = "Cue $id",
                trigger = "step-start",
                audio = LiveCueAudio(LiveAudioFormat.MP3, "SUQzBAAAAAAAAA=="),
                createdAt = "2026-11-19T07:42:10.250Z",
                expiresAt = "2026-11-19T07:42:18.250Z",
                ttlMs = 8000,
            ),
        )

    private fun cueEvent(id: Int) = "id: $id\nevent: cue\ndata: ${cue(id)}\n\n"

    private val endEvent = "event: end\ndata: {\"reason\":\"ended\"}\n\n"

    private sealed interface Step {
        /** Gives these chunks and then the server closes the response. */
        class Chunks(
            val chunks: List<String>,
        ) : Step

        class Fails(
            val outcome: Outcome<HttpAnswer>,
        ) : Step

        /** Gives these chunks and then waits for ever. */
        class Hangs(
            val chunks: List<String>,
        ) : Step
    }

    private class Script(
        private val scope: TestScope,
        private val steps: List<Step>,
    ) : CueStreamSource {
        val openedAt = mutableListOf<Long>()
        val lastIds = mutableListOf<String?>()
        var cancelled = false

        override suspend fun open(
            lastEventId: String?,
            onChunk: suspend (String) -> Unit,
        ): Outcome<HttpAnswer> {
            openedAt += scope.currentTime
            lastIds += lastEventId
            val step = steps.getOrElse(openedAt.size - 1) { steps.last() }
            return when (step) {
                is Step.Chunks -> {
                    step.chunks.forEach { onChunk(it) }
                    Outcome.Ok(HttpAnswer(200), "")
                }

                is Step.Fails -> {
                    step.outcome
                }

                is Step.Hangs -> {
                    step.chunks.forEach { onChunk(it) }
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }
    }

    private fun failed(kind: TransportFailure = TransportFailure.Dropped): Step =
        Step.Fails(Outcome.Failed(ServiceProblem.of(TransportException(kind))))

    private fun answered(status: Int): Step =
        Step.Fails(
            Outcome.Failed(ServiceProblem.of(HttpAnswer(status, """{"error":{"code":"not_found","message":"x"}}"""))),
        )

    private fun TestScope.run(
        steps: List<Step>,
        cues: MutableList<LiveCue> = mutableListOf(),
    ): Triple<Script, CueStream, MutableList<LiveCue>> {
        val script = Script(this, steps)
        return Triple(script, CueStream(script, cues::add), cues)
    }

    @Test
    fun `reads the stream the service writes, in pieces, and stops at its end`() =
        runTest {
            val text = File(contracts, "fixtures/live-coach/stream/cues.sse.txt").readText()
            val (script, stream, cues) = run(listOf(Step.Chunks(text.chunked(7))))
            val end = stream.run()
            assertEquals(CueStreamEnd.Ended(LiveStreamEndReason.ENDED), end)
            assertEquals(listOf(3, 4), cues.map { it.id })
            assertEquals(1, script.openedAt.size)
            assertEquals(listOf<String?>(null), script.lastIds)
            assertEquals(0, stream.skipped)
        }

    @Test
    fun `reconnects after the interval the service asked for, with the last event id`() =
        runTest {
            val first = "retry: 5000\n\n: open\n\n${cueEvent(5)}"
            val second = "${cueEvent(6)}$endEvent"
            val (script, stream, cues) = run(listOf(Step.Chunks(listOf(first)), Step.Chunks(listOf(second))))
            stream.run()
            assertEquals(listOf(5, 6), cues.map { it.id })
            assertEquals(listOf(0L, 5_000L), script.openedAt)
            assertEquals(listOf<String?>(null, "5"), script.lastIds)
        }

    @Test
    fun `an end event stops it for each reason, with nothing opened again`() =
        runTest {
            for (reason in listOf("ended", "superseded", "shutdown")) {
                val (script, stream, _) =
                    run(listOf(Step.Chunks(listOf("event: end\ndata: {\"reason\":\"$reason\"}\n\n"))))
                val end = assertIs<CueStreamEnd.Ended>(stream.run())
                assertEquals(reason, end.reason.name.lowercase())
                assertEquals(1, script.openedAt.size, reason)
            }
        }

    @Test
    fun `an end event stops it even while the server keeps the response open`() =
        runTest {
            val (script, stream, cues) = run(listOf(Step.Hangs(listOf(cueEvent(1), endEvent))))
            assertEquals(CueStreamEnd.Ended(LiveStreamEndReason.ENDED), stream.run())
            assertEquals(listOf(1), cues.map { it.id })
            assertEquals(1, script.openedAt.size)
        }

    @Test
    fun `the interval is held between a second and a minute`() =
        runTest {
            for ((asked, held) in listOf(0L to 1_000L, 1L to 1_000L, 999_999L to 60_000L, 2_500L to 2_500L)) {
                val (script, stream, _) =
                    run(listOf(Step.Chunks(listOf("retry: $asked\n\n")), Step.Chunks(listOf(endEvent))))
                val start = currentTime
                stream.run()
                assertEquals(held, script.openedAt[1] - start, "retry: $asked")
            }
        }

    @Test
    fun `connections that give nothing back off, up to thirty seconds, and a good one starts it over`() =
        runTest {
            val steps =
                List(8) { failed() } + Step.Chunks(listOf(": hb\n\n")) + List(3) { failed() } +
                    Step.Chunks(listOf(endEvent))
            val (script, stream, _) = run(steps)
            stream.run()
            val gaps = script.openedAt.zipWithNext { a, b -> b - a }
            // 3 s, then doubled for each silent connection in a row, at most 30 s; a connection that gave a heartbeat starts it over.
            assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L), gaps.take(7))
            assertEquals(3_000L, gaps[8], "after the heartbeat it is 3 s again")
            assertEquals(listOf(3_000L, 6_000L, 12_000L), gaps.drop(9).take(3))
        }

    @Test
    fun `a network that is down for an hour costs a bounded number of attempts and no busy loop`() =
        runTest {
            val (script, stream, _) = run(listOf(failed(TransportFailure.NoNetwork)))
            val job = launch { stream.run() }
            advanceTimeBy(3_600_000)
            runCurrent()
            job.cancel()
            // 3, 6, 12, 24 s and then every 30 s: well under 150 in an hour.
            assertTrue(script.openedAt.size in 100..150, "attempts: ${script.openedAt.size}")
            assertTrue(script.openedAt.zipWithNext { a, b -> b - a }.all { it >= 3_000 })
        }

    @Test
    fun `a session the service forgot, a refused token and nothing set up each stop it without a retry`() =
        runTest {
            val (gone, goneStream, _) = run(listOf(answered(404)))
            assertEquals(CueStreamEnd.SessionGone, goneStream.run())
            assertEquals(1, gone.openedAt.size)

            val (refused, refusedStream, _) = run(listOf(answered(401)))
            assertEquals(CueStreamEnd.TokenRejected, refusedStream.run())
            assertEquals(1, refused.openedAt.size)

            val (unset, unsetStream, _) = run(listOf(Step.Fails(Outcome.NotSetUp)))
            assertEquals(CueStreamEnd.NotSetUp, unsetStream.run())
            assertEquals(1, unset.openedAt.size)
        }

    @Test
    fun `a server error or a refusal that is not a missing session is tried again`() =
        runTest {
            val (script, stream, _) = run(listOf(answered(503), answered(400), Step.Chunks(listOf(endEvent))))
            assertEquals(CueStreamEnd.Ended(LiveStreamEndReason.ENDED), stream.run())
            assertEquals(3, script.openedAt.size)
        }

    @Test
    fun `cancelling it closes the stream and opens nothing more`() =
        runTest {
            val (script, stream, cues) = run(listOf(Step.Hangs(listOf(cueEvent(1)))))
            val job = launch { stream.run() }
            runCurrent()
            assertEquals(listOf(1), cues.map { it.id })
            job.cancel()
            runCurrent()
            assertTrue(script.cancelled, "the source was cancelled")
            advanceTimeBy(600_000)
            assertEquals(1, script.openedAt.size)
        }

    @Test
    fun `cancelling it while it waits to reconnect opens nothing more`() =
        runTest {
            val (script, stream, _) = run(listOf(Step.Chunks(listOf(cueEvent(1)))))
            val job = launch { stream.run() }
            runCurrent()
            assertEquals(1, script.openedAt.size)
            job.cancel()
            advanceTimeBy(600_000)
            assertEquals(1, script.openedAt.size)
        }

    @Test
    fun `an event that is not the contract's is skipped, counted, and the next one is read`() =
        runTest {
            val bad =
                "event: cue\ndata: {\"id\":9,\"text\":\"\"}\n\n" +
                    "event: end\ndata: {\"reason\":\"never\"}\n\n" +
                    "event: cue\ndata: not json\n\n"
            val (_, stream, cues) =
                run(
                    listOf(Step.Chunks(listOf(bad, cueEvent(2), "event: other\ndata: x\n\n", endEvent))),
                )
            stream.run()
            assertEquals(listOf(2), cues.map { it.id })
            assertEquals(3, stream.skipped)
        }

    @Test
    fun `a stream that never ends a line is a broken connection, and backs off`() =
        runTest {
            val endless = "data: " + "x".repeat(SseParser.DEFAULT_MAX_CHARS + 10)
            val (script, stream, cues) =
                run(
                    listOf(Step.Chunks(listOf(endless)), Step.Chunks(listOf(endless)), Step.Chunks(listOf(endEvent))),
                )
            stream.run()
            assertTrue(cues.isEmpty())
            assertEquals(listOf(3_000L, 6_000L), script.openedAt.drop(1).zip(script.openedAt).map { (b, a) -> b - a })
        }

    @Test
    fun `a cue repeated after a reconnect is still handed on, for the player to drop by its number`() =
        runTest {
            // The service resends only what came after Last-Event-ID, but the app does not rely on it: ids are the player's to check.
            val (_, stream, cues) =
                run(
                    listOf(Step.Chunks(listOf(cueEvent(1))), Step.Chunks(listOf(cueEvent(1), endEvent))),
                )
            stream.run()
            assertEquals(listOf(1, 1), cues.map { it.id })
            assertFalse(cues.isEmpty())
            assertNull(null)
        }
}
