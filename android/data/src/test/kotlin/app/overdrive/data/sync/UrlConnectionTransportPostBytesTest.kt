package app.overdrive.data.sync

import app.overdrive.core.sync.HttpPostBytesRequest
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * The companion's `POST` of a FIT file (ADR 0028) over the real `HttpURLConnection` transport against a local server: the same stack,
 * timeouts and rules as the other verbs, the bytes sent exactly with the type the request names and a fixed length, an answer read only
 * up to the size the request allows. The token and the file stay out of every failure and every printed request.
 */
class UrlConnectionTransportPostBytesTest {
    private val service = FakeService()
    private val transport = UrlConnectionTransport()

    @After
    fun cleanUp() {
        service.close()
    }

    /** Bytes with every value from 0 to 255 in them: a text encoding would damage them. */
    private fun fitBytes(size: Int = 2048): ByteArray = ByteArray(size) { (it * 7 + 3).toByte() }

    private fun post(
        body: ByteArray = fitBytes(),
        token: String = service.token,
        maxBytes: Int = 65_536,
        transport: UrlConnectionTransport = this.transport,
        base: String = service.baseUrl,
    ) = runBlocking {
        transport.postBytes(
            HttpPostBytesRequest("$base/v1/activities/fit", token, body, "application/vnd.ant.fit", maxBytes),
        )
    }

    @Test
    fun `sends a POST with the bearer token, the FIT type and the bytes exactly, and reads the answer`() {
        service.script += FakeService.Reply(201, """{"status":"imported"}""")
        val body = fitBytes()
        val answer = post(body)
        assertEquals(201, answer.status)
        assertEquals("""{"status":"imported"}""", answer.body)
        val request = service.received.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/activities/fit", request.path)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals("application/vnd.ant.fit", request.contentType)
        assertArrayEquals(body, request.body)
    }

    @Test
    fun `a file of several megabytes arrives whole and in order`() {
        service.script += FakeService.Reply(201, "{}")
        val body = fitBytes(5 * 1024 * 1024 + 17)
        post(body)
        assertArrayEquals(body, service.received.single().body)
    }

    @Test
    fun `error answers come back with their status and their message`() {
        assertEquals(401, post(token = "wrong").status)
        service.script += FakeService.Reply(422, """{"error":{"code":"unsupported_sport","message":"Not cycling"}}""")
        val refused = post()
        assertEquals(422, refused.status)
        assertTrue(refused.body, "unsupported_sport" in refused.body)
        service.script += FakeService.Reply(413, """{"error":{"code":"payload_too_large","message":"Too big"}}""")
        assertEquals(413, post().status)
        service.script += FakeService.Reply(503, "<html>down</html>")
        assertEquals(503, post().status)
    }

    @Test
    fun `a redirect is returned as it is, not followed, and the file is sent once`() {
        service.script +=
            FakeService.Reply(308, "", headers = mapOf("Location" to "https://elsewhere.example/v1/activities/fit"))
        assertEquals(308, post().status)
        assertEquals(1, service.received.size)
    }

    @Test
    fun `an answer longer than the request allows is a failure, not a truncated answer`() {
        service.script += FakeService.Reply(200, "x".repeat(10_001))
        assertEquals(TransportFailure.Other, failureOf { post(maxBytes = 10_000) }.failure)
        service.script += FakeService.Reply(200, "x".repeat(10_000))
        assertEquals("exactly the limit is fine", 10_000, post(maxBytes = 10_000).body.length)
    }

    @Test
    fun `a closed port is refused, a name that does not resolve is a missing address, and a slow server times out`() {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(TransportFailure.Refused, failureOf { post(base = "http://127.0.0.1:$closed") }.failure)
        assertEquals(TransportFailure.NameNotFound, failureOf { post(base = "http://nonexistent.invalid") }.failure)
        service.script += FakeService.Reply(200, "{}", delayMillis = 2_000)
        val impatient = UrlConnectionTransport(connectTimeoutMillis = 500, readTimeoutMillis = 300)
        assertEquals(TransportFailure.TimedOut, failureOf { post(transport = impatient) }.failure)
    }

    @Test
    fun `a request that is cancelled stops and is a cancellation, not a failure`() {
        service.script += FakeService.Reply(200, "{}", delayMillis = 3_000)
        runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    transport.postBytes(
                        HttpPostBytesRequest(
                            "${service.baseUrl}/v1/activities/fit",
                            service.token,
                            fitBytes(),
                            "application/vnd.ant.fit",
                            1000,
                        ),
                    )
                }
            // The request has reached the service: it is in the middle of waiting for the answer.
            val end = System.nanoTime() + 30_000_000_000L
            while (service.received.isEmpty() && System.nanoTime() < end) delay(5)
            assertEquals(1, service.received.size)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `no failure and no request prints the token or the file`() {
        val token = "tok-secret-0123456789abcdef0123456789ab"
        val closed = ServerSocket(0).use { it.localPort }
        val body = fitBytes()
        val failure = failureOf { post(body = body, token = token, base = "http://127.0.0.1:$closed") }
        var cause: Throwable? = failure
        while (cause != null) {
            assertFalse("${cause::class}: ${cause.message}", cause.message.orEmpty().contains(token))
            cause = cause.cause
        }
        assertFalse(failure.stackTraceToString().contains(token))
        val printed =
            HttpPostBytesRequest(
                "http://x/v1/activities/fit",
                token,
                body,
                "application/vnd.ant.fit",
                10,
            ).toString()
        assertFalse(printed.contains(token))
        assertFalse(printed.contains(body.joinToString(",")))
        assertEquals("POST http://x/v1/activities/fit (application/vnd.ant.fit, ${body.size} bytes)", printed)
        assertNull(service.received.firstOrNull { it.authorization?.contains(token) == true })
    }

    private fun failureOf(action: () -> Unit): TransportException {
        try {
            action()
        } catch (e: TransportException) {
            return e
        }
        fail("expected a TransportException")
        throw IllegalStateException()
    }
}
