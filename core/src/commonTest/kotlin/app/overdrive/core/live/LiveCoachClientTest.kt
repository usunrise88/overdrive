package app.overdrive.core.live

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.HttpStreamRequest
import app.overdrive.core.sync.HttpStreamTransport
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The live coach's requests through the app's one service client: paths, bodies, and what an answer comes to. */
class LiveCoachClientTest {
    private class Posts : HttpPostTransport {
        val requests = mutableListOf<HttpPostRequest>()
        var reply: () -> HttpAnswer = { HttpAnswer(204) }
        var fails: TransportFailure? = null

        override suspend fun post(request: HttpPostRequest): HttpAnswer {
            requests += request
            fails?.let { throw TransportException(it) }
            return reply()
        }
    }

    private class Streams : HttpStreamTransport {
        val requests = mutableListOf<HttpStreamRequest>()

        override suspend fun stream(
            request: HttpStreamRequest,
            onChunk: suspend (String) -> Unit,
        ): HttpAnswer {
            requests += request
            onChunk("id: 1\nevent: end\ndata: {\"reason\":\"ended\"}\n\n")
            return HttpAnswer(200)
        }
    }

    private object NoGets : HttpGetTransport {
        override suspend fun get(request: HttpGetRequest): HttpAnswer = error("the live coach asks nothing with a GET")
    }

    private val config = SyncConfig("https://overdrive.example/", "tok-test-0000")
    private val posts = Posts()
    private val streams = Streams()
    private val client = LiveCoachClient(ServiceClient({ config }, NoGets, posts, streamer = streams))

    private val on =
        """{"sessionId":"ls-3f9a2c41d0b84e6a","coach":{"on":true,"reason":null},""" +
            """"serverTime":1795000000456,"snapshotEverySeconds":5}"""

    @Test
    fun `a start is posted to the sessions with the contract's body and the token and the answer is read`() =
        runTest {
            posts.reply = { HttpAnswer(201, on) }
            val start = LiveSessionStart("ride-1795000000000", "icu-6001", "833a9d4d7f4ebf35", false, 1_795_000_000_000)
            assertEquals(LiveStartAnswer.Started("ls-3f9a2c41d0b84e6a"), client.start(start))
            val request = posts.requests.single()
            assertEquals("https://overdrive.example/v1/live/sessions", request.url)
            assertEquals("tok-test-0000", request.bearerToken)
            assertEquals(start, LiveCoachJson.decodeSessionStart(request.body))
        }

    @Test
    fun `a snapshot goes to its session a 404 is a session that is gone and a failure is the next one's problem`() =
        runTest {
            val snapshot =
                LiveSnapshot(
                    seq = 4,
                    clock = 1_795_000_020_000,
                    elapsedSeconds = 20,
                    paused = false,
                    muted = false,
                    step = LiveSnapshotStep(0, LiveStepType.STEADY, "Threshold", 600, 580, targetWatts = 238),
                    stepCount = 7,
                    power = 240,
                )
            assertEquals(LiveSnapshotAnswer.Accepted, client.snapshot("ls-1", snapshot))
            val request = posts.requests.single()
            assertEquals("https://overdrive.example/v1/live/sessions/ls-1/snapshots", request.url)
            assertEquals(snapshot, LiveCoachJson.decodeSnapshot(request.body))
            posts.reply = { HttpAnswer(404, """{"error":{"code":"not_found","message":"No such session"}}""") }
            assertEquals(LiveSnapshotAnswer.SessionGone, client.snapshot("ls-1", snapshot))
            posts.reply = { HttpAnswer(503, "") }
            assertEquals(LiveSnapshotAnswer.Failed, client.snapshot("ls-1", snapshot))
            posts.fails = TransportFailure.NoNetwork
            assertEquals(LiveSnapshotAnswer.Failed, client.snapshot("ls-1", snapshot))
        }

    @Test
    fun `an end and a played report are posted to their own paths and never fail the caller`() =
        runTest {
            posts.reply = { HttpAnswer(200, """{"sessionId":"ls-1","status":"ended","cues":2}""") }
            client.end("ls-1", LiveSessionEnd(LiveSessionEndReason.FINISHED, 1_795_000_900_000))
            posts.reply = { HttpAnswer(204) }
            client.played("ls-1", 7, LiveCuePlayed(true, 1_795_000_901_000))
            posts.fails = TransportFailure.Dropped
            client.played("ls-1", 8, LiveCuePlayed(false))
            assertEquals(
                listOf(
                    "https://overdrive.example/v1/live/sessions/ls-1/end",
                    "https://overdrive.example/v1/live/sessions/ls-1/cues/7/played",
                    "https://overdrive.example/v1/live/sessions/ls-1/cues/8/played",
                ),
                posts.requests.map { it.url },
            )
            assertEquals(
                LiveSessionEnd(LiveSessionEndReason.FINISHED, 1_795_000_900_000),
                LiveCoachJson.decodeSessionEnd(posts.requests[0].body),
            )
            assertEquals(LiveCuePlayed(true, 1_795_000_901_000), LiveCoachJson.decodeCuePlayed(posts.requests[1].body))
            assertEquals(LiveCuePlayed(false), LiveCoachJson.decodeCuePlayed(posts.requests[2].body))
        }

    @Test
    fun `the cue stream is the session's own with the last event id`() =
        runTest {
            val chunks = mutableListOf<String>()
            val outcome = client.cues("ls-1").open("41") { chunks += it }
            assertIs<Outcome.Ok<HttpAnswer>>(outcome)
            val request = streams.requests.single()
            assertEquals("https://overdrive.example/v1/live/sessions/ls-1/cues", request.url)
            assertEquals("tok-test-0000", request.bearerToken)
            assertEquals("41", request.lastEventId)
            assertTrue(chunks.single().contains("end"))
            assertFalse(request.toString().contains("tok-test-0000"), "the token is in no printed request")
        }

    @Test
    fun `nothing set up asks nothing and says nothing`() =
        runTest {
            val unset = LiveCoachClient(ServiceClient({ SyncConfig("", "") }, NoGets, posts, streamer = streams))
            assertEquals(LiveStartAnswer.Unsupported, unset.start(LiveSessionStart("r1", clock = 1)))
            assertEquals(Outcome.NotSetUp, unset.cues("ls-1").open(null) { })
            assertEquals(0, posts.requests.size)
            assertEquals(0, streams.requests.size)
            assertFalse(unset.isConfigured)
        }

    @Test
    fun `a token the service refused is not sent again`() =
        runTest {
            posts.reply = { HttpAnswer(401, """{"error":{"code":"unauthorized","message":"No"}}""") }
            assertEquals(LiveStartAnswer.Unsupported, client.start(LiveSessionStart("r1", clock = 1)))
            assertEquals(LiveSnapshotAnswer.Failed, client.snapshot("ls-1", snapshotOf(0)))
            client.end("ls-1", LiveSessionEnd())
            assertEquals(1, posts.requests.size, "one request, and the refusal is remembered")
        }

    private fun snapshotOf(seq: Int) =
        LiveSnapshot(
            seq = seq,
            clock = 1,
            elapsedSeconds = 1,
            paused = false,
            muted = false,
            step = LiveSnapshotStep(0, LiveStepType.FREE, null, 60, 59),
            stepCount = 1,
        )
}
