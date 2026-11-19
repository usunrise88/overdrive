package app.overdrive.data.sync

import app.overdrive.core.live.CueStream
import app.overdrive.core.live.CueStreamEnd
import app.overdrive.core.live.LiveAudioFormat
import app.overdrive.core.live.LiveCoachClient
import app.overdrive.core.live.LiveCoachJson
import app.overdrive.core.live.LiveCue
import app.overdrive.core.live.LiveCueAudio
import app.overdrive.core.live.LiveStreamEndReason
import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.sync.HttpStreamRequest
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live coach's cue stream over the real `HttpURLConnection` transport (docs/adr/0039) against a local server that writes `text/event-stream` the way the
 * service does (chunked, flushed event by event, and closed properly): the text arrives **as it is written**, the headers are the right ones, a status other
 * than 200 is the answer, a quiet connection times out, cancelling closes the connection, and the whole chain (the service client, the reader that reconnects with
 * `Last-Event-ID`) runs on real sockets. Waits are a few milliseconds.
 */
class UrlConnectionTransportStreamTest {
    private class Connection(
        val path: String,
        val authorization: String?,
        val accept: String?,
        val lastEventId: String?,
    )

    /** What the server does with the next connection to the stream. */
    private class Plan(
        val status: Int = 200,
        val body: (HttpExchange) -> Unit = {},
    )

    private val connections = CopyOnWriteArrayList<Connection>()
    private val plans = java.util.concurrent.ConcurrentLinkedQueue<Plan>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val token = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"
    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            connections +=
                Connection(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Accept"),
                    exchange.requestHeaders.getFirst("Last-Event-ID"),
                )
            val plan = plans.poll() ?: Plan(404) { it.responseBody.write(NOT_FOUND.encodeToByteArray()) }
            try {
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (plan.status ==
                        200
                    ) {
                        "text/event-stream"
                    } else {
                        "application/json"
                    },
                )
                exchange.responseHeaders.add("Cache-Control", "no-cache, no-transform")
                // Length 0 means chunked: the response is open-ended and every flush reaches the client at once.
                exchange.sendResponseHeaders(plan.status, 0)
                plan.body(exchange)
            } catch (e: IOException) {
                // The client went away: that is what some of the tests are about.
            } finally {
                exchange.close()
            }
        }
        server.executor = executor
        server.start()
    }

    @After
    fun cleanUp() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun HttpExchange.write(text: String) {
        responseBody.write(text.encodeToByteArray())
        responseBody.flush()
    }

    private fun HttpExchange.writeBytes(bytes: ByteArray) {
        responseBody.write(bytes)
        responseBody.flush()
    }

    private fun request(
        lastEventId: String? = null,
        path: String = "/v1/live/sessions/ls-1/cues",
        bearer: String = token,
    ) = HttpStreamRequest(baseUrl + path, bearer, lastEventId)

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

    @Test
    fun `the text arrives as the server writes it, before the response is over`() {
        val release = CountDownLatch(1)
        plans +=
            Plan {
                it.write("retry: 3000\n\n: open\n\n")
                release.await(10, TimeUnit.SECONDS)
                it.write(cueEvent(1))
            }
        val first = CompletableDeferred<String>()
        val all = StringBuilder()
        val answer =
            runBlocking {
                UrlConnectionTransport().let { transport ->
                    val job =
                        launch(Dispatchers.Default) {
                            transport.stream(request()) {
                                if (!first.isCompleted) first.complete(it)
                                all.append(it)
                            }
                        }
                    // The first piece is here while the server is still waiting for its latch: it was not held until the end.
                    val head = withTimeout(5_000) { first.await() }
                    assertTrue(head, head.startsWith("retry: 3000"))
                    assertFalse("the second event was not written yet", all.contains("event: cue"))
                    release.countDown()
                    job.join()
                    all.toString()
                }
            }
        assertTrue(answer.contains("event: cue"))
        assertTrue(answer, answer.contains("\"id\":1"))
    }

    @Test
    fun `it asks for an event stream with the token and the last event id, and follows nothing`() {
        plans += Plan { it.write(": open\n\n") }
        val answer = runBlocking { UrlConnectionTransport().stream(request(lastEventId = "41")) { } }
        assertEquals(200, answer.status)
        assertEquals("", answer.body)
        val seen = connections.single()
        assertEquals("/v1/live/sessions/ls-1/cues", seen.path)
        assertEquals("Bearer $token", seen.authorization)
        assertEquals("text/event-stream", seen.accept)
        assertEquals("41", seen.lastEventId)
        plans += Plan { }
        runBlocking { UrlConnectionTransport().stream(request()) { } }
        assertNull("no Last-Event-ID the first time", connections.last().lastEventId)
    }

    @Test
    fun `a response the server ends is a 200 with nothing more, and the whole text came through`() {
        plans +=
            Plan {
                it.write(": open\n\n")
                it.write(cueEvent(1))
                it.write(endEvent)
            }
        val text = StringBuilder()
        val answer = runBlocking { UrlConnectionTransport().stream(request()) { text.append(it) } }
        assertEquals(200, answer.status)
        assertEquals(": open\n\n" + cueEvent(1) + endEvent, text.toString())
    }

    @Test
    fun `a status other than 200 is the answer, with its message, and no text is given on`() {
        plans += Plan(404) { it.write(NOT_FOUND) }
        val chunks = mutableListOf<String>()
        val answer = runBlocking { UrlConnectionTransport().stream(request()) { chunks += it } }
        assertEquals(404, answer.status)
        assertTrue(answer.body, "not_found" in answer.body)
        assertTrue(chunks.isEmpty())
        plans += Plan(401) { it.write("""{"error":{"code":"unauthorized","message":"No"}}""") }
        assertEquals(401, runBlocking { UrlConnectionTransport().stream(request()) { } }.status)
    }

    @Test
    fun `multibyte text cut in the middle of a character is put together again`() {
        val text = ": café – 1×10 ☃\n\n"
        val bytes = text.encodeToByteArray()
        // Cut inside the three-byte "–" and inside the two-byte "é".
        val cuts = listOf(bytes.indexOf(0xA9.toByte()), bytes.indexOfFirst { it == 0xE2.toByte() } + 1)
        plans +=
            Plan {
                var from = 0
                for (cut in cuts.sorted() + bytes.size) {
                    it.writeBytes(bytes.copyOfRange(from, cut))
                    Thread.sleep(20)
                    from = cut
                }
            }
        val received = StringBuilder()
        runBlocking { UrlConnectionTransport().stream(request()) { received.append(it) } }
        assertEquals(text, received.toString())
    }

    @Test
    fun `a connection that says nothing for longer than the read timeout is a failure, not a wait for ever`() {
        val hold = CountDownLatch(1)
        plans +=
            Plan {
                it.write(": open\n\n")
                hold.await(10, TimeUnit.SECONDS)
            }
        val chunks = mutableListOf<String>()
        val started = System.nanoTime()
        try {
            runBlocking { UrlConnectionTransport(streamReadTimeoutMillis = 300).stream(request()) { chunks += it } }
            fail("it should have timed out")
        } catch (e: TransportException) {
            assertEquals(TransportFailure.TimedOut, e.failure)
        } finally {
            hold.countDown()
        }
        assertEquals(listOf(": open\n\n"), chunks)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMillis ms", tookMillis in 250..5_000)
    }

    @Test
    fun `the stream's read timeout is longer than the service's heartbeat and shorter than the other requests'`() {
        assertTrue(UrlConnectionTransport.STREAM_READ_TIMEOUT_MILLIS > 15_000)
        assertTrue(UrlConnectionTransport.STREAM_READ_TIMEOUT_MILLIS <= 30_000)
    }

    @Test
    fun `cancelling a request that waits closes the connection, at once`() {
        val clientGone = AtomicBoolean(false)
        plans +=
            Plan {
                // Like the service: a heartbeat as long as the client listens.
                try {
                    while (true) {
                        it.write(": hb\n\n")
                        Thread.sleep(20)
                    }
                } catch (e: IOException) {
                    clientGone.set(true)
                }
            }
        runBlocking {
            val transport = UrlConnectionTransport()
            val got = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) { transport.stream(request()) { got.complete(Unit) } }
            withTimeout(5_000) { got.await() }
            val started = System.nanoTime()
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(
                "cancelling took ${(System.nanoTime() - started) / 1_000_000} ms",
                (System.nanoTime() - started) < 2_000_000_000,
            )
        }
        val end = System.currentTimeMillis() + 5_000
        while (!clientGone.get() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("the server saw the connection close", clientGone.get())
    }

    @Test
    fun `cancelling a request while the server is idle, between two heartbeats, does not wait for the next one`() {
        val hold = CountDownLatch(1)
        plans +=
            Plan {
                it.write(": open\n\n")
                hold.await(20, TimeUnit.SECONDS)
            }
        val tookMillis =
            runBlocking {
                val got = CompletableDeferred<Unit>()
                val job =
                    launch(Dispatchers.Default) { UrlConnectionTransport().stream(request()) { got.complete(Unit) } }
                withTimeout(5_000) { got.await() }
                Thread.sleep(100)
                val started = System.nanoTime()
                withTimeout(10_000) { job.cancelAndJoin() }
                (System.nanoTime() - started) / 1_000_000
            }
        hold.countDown()
        assertTrue("cancelling an idle stream took $tookMillis ms", tookMillis < 2_000)
    }

    @Test
    fun `a server that is not there is a failure the caller can tell from a refusal`() {
        server.stop(0)
        try {
            runBlocking { UrlConnectionTransport().stream(request()) { } }
            fail("nothing was listening")
        } catch (e: TransportException) {
            assertEquals(TransportFailure.Refused, e.failure)
        }
    }

    // ---- The chain: the service client and the reader that reconnects ----------------------------------------------

    private fun liveClient(bearer: String = token): LiveCoachClient =
        LiveCoachClient(
            ServiceClient(
                { SyncConfig(baseUrl, bearer) },
                UrlConnectionTransport(),
                UrlConnectionTransport(),
                streamer = UrlConnectionTransport(),
            ),
        )

    @Test
    fun `the reader keeps a stream open across a close and resumes with the last event id`() {
        plans +=
            Plan {
                it.write("retry: 5\n\n: open\n\n")
                it.write(cueEvent(1))
            }
        plans +=
            Plan {
                it.write(": open\n\n")
                it.write(cueEvent(2))
                it.write(endEvent)
            }
        val cues = CopyOnWriteArrayList<Int>()
        val end =
            runBlocking {
                withTimeout(10_000) {
                    CueStream(liveClient().cues("ls-1"), { cues += it.id }, minRetryMillis = 5).run()
                }
            }
        assertEquals(CueStreamEnd.Ended(LiveStreamEndReason.ENDED), end)
        assertEquals(listOf(1, 2), cues.toList())
        assertEquals(2, connections.size)
        assertNull(connections[0].lastEventId)
        assertEquals("1", connections[1].lastEventId)
        assertTrue(connections.all { it.authorization == "Bearer $token" })
    }

    @Test
    fun `a session the service forgot and a token it refused stop the reader without a retry`() {
        // No plan queued: the server answers 404 not_found.
        val gone =
            runBlocking { withTimeout(10_000) { CueStream(liveClient().cues("ls-1"), { }, minRetryMillis = 5).run() } }
        assertEquals(CueStreamEnd.SessionGone, gone)
        assertEquals(1, connections.size)
        plans += Plan(401) { it.write("""{"error":{"code":"unauthorized","message":"No"}}""") }
        val refused =
            runBlocking { withTimeout(10_000) { CueStream(liveClient().cues("ls-1"), { }, minRetryMillis = 5).run() } }
        assertEquals(CueStreamEnd.TokenRejected, refused)
        assertEquals(2, connections.size)
    }

    @Test
    fun `a stream that drops is opened again after the interval, and cancelling the reader stops it`() {
        // First connection: a heartbeat, then the server hangs up. Every later one: a heartbeat and a long wait.
        plans += Plan { it.write(": open\n\n") }
        val hold = CountDownLatch(1)
        repeat(3) {
            plans +=
                Plan {
                    it.write(": open\n\n")
                    hold.await(10, TimeUnit.SECONDS)
                }
        }
        val job =
            runBlocking {
                val stream = CueStream(liveClient().cues("ls-1"), { }, defaultRetryMillis = 5, minRetryMillis = 5)
                val running = launch(Dispatchers.Default) { stream.run() }
                val end = System.currentTimeMillis() + 5_000
                while (connections.size < 2 && System.currentTimeMillis() < end) Thread.sleep(5)
                assertTrue("it came back", connections.size >= 2)
                running.cancelAndJoin()
                running
            }
        hold.countDown()
        assertTrue(job.isCancelled)
        val after = connections.size
        Thread.sleep(150)
        assertEquals("nothing is opened after it was cancelled", after, connections.size)
    }

    @Test
    fun `the service client reports a stream that did not open as a failure of its kind`() {
        val outcome = runBlocking { liveClient().cues("ls-1").open(null) { } }
        val failed = outcome as Outcome.Failed
        assertEquals(ProblemKind.NO_SUCH_ROUTE, failed.problem.kind)
    }

    private companion object {
        const val NOT_FOUND = """{"error":{"code":"not_found","message":"No such session"}}"""
    }
}
