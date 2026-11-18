package app.overdrive.data.sync

import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

/**
 * The planned workouts' `GET` over the real `HttpURLConnection` transport (ADR 0017) against a local server: the same stack,
 * timeouts and rules as the upload, and an answer that is read only up to the size the request allows.
 */
class UrlConnectionTransportGetTest {
    private val service = FakeService()

    @After
    fun cleanUp() {
        service.close()
    }

    private fun get(
        pathAndQuery: String = "/v1/workouts?from=2026-11-18&to=2026-12-01",
        token: String = service.token,
        maxBytes: Int = 1 shl 20,
        transport: UrlConnectionTransport = UrlConnectionTransport(),
        base: String = service.baseUrl,
    ) = runBlocking { transport.get(HttpGetRequest(base + pathAndQuery, token, maxBytes)) }

    @Test
    fun `sends a GET with the bearer token, no body and the query as it is, and reads the answer`() {
        service.script += FakeService.Reply(200, """{"workouts":[]}""")
        val answer = get()
        assertEquals(200, answer.status)
        assertEquals("""{"workouts":[]}""", answer.body)
        val request = service.received.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/workouts", request.path)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals(0, request.body.size)
    }

    @Test
    fun `error answers come back with their status and their message`() {
        val denied = get(token = "wrong")
        assertEquals(401, denied.status)
        assertTrue(denied.body, "unauthorized" in denied.body)
        service.script += FakeService.Reply(503, "<html>down</html>")
        assertEquals(503, get().status)
        assertEquals(404, get("/nowhere").status)
    }

    @Test
    fun `a redirect is returned as it is, not followed`() {
        service.script +=
            FakeService.Reply(308, "", headers = mapOf("Location" to "https://elsewhere.example/v1/workouts"))
        val answer = get()
        assertEquals(308, answer.status)
        assertEquals(1, service.received.size)
    }

    @Test
    fun `a body of several hundred kilobytes is read whole, with its multibyte text`() {
        val text = "Sweet spot – 3×10 ".repeat(30_000)
        service.script += FakeService.Reply(200, text)
        val answer = get()
        assertEquals(text, answer.body)
        assertTrue(answer.body.length > 400_000)
    }

    @Test
    fun `an answer longer than the request allows is a failure, not a truncated list`() {
        service.script += FakeService.Reply(200, "x".repeat(10_001))
        assertEquals(TransportFailure.Other, failureOf { get(maxBytes = 10_000) }.failure)
        service.script += FakeService.Reply(200, "x".repeat(10_000))
        assertEquals("exactly the limit is fine", 10_000, get(maxBytes = 10_000).body.length)
        service.script += FakeService.Reply(500, "x".repeat(100_000))
        assertEquals("an error body too", TransportFailure.Other, failureOf { get(maxBytes = 10_000) }.failure)
    }

    @Test
    fun `a closed port is refused, a name that does not resolve is a missing address, and a slow server times out`() {
        val closed = ServerSocket(0).use { it.localPort }
        val refused = failureOf { get(base = "http://127.0.0.1:$closed") }
        assertEquals(TransportFailure.Refused, refused.failure)
        assertTrue(refused.hint.orEmpty(), refused.hint.orEmpty().startsWith("ConnectException"))
        val missing = failureOf { get(base = "http://nonexistent.invalid") }
        assertEquals(TransportFailure.NameNotFound, missing.failure)
        assertTrue(missing.hint.orEmpty(), missing.hint.orEmpty().startsWith("UnknownHostException"))
        for (hint in listOf(refused.hint, missing.hint)) {
            assertFalse(hint.orEmpty(), hint.orEmpty().contains("127.0.0.1") || hint.orEmpty().contains("nonexistent"))
        }
        service.script += FakeService.Reply(200, "{}", delayMillis = 2_000)
        val impatient = UrlConnectionTransport(connectTimeoutMillis = 500, readTimeoutMillis = 300)
        assertEquals(TransportFailure.TimedOut, failureOf { get(transport = impatient) }.failure)
    }

    @Test
    fun `speaking TLS to a plain server is a secure connection failure`() {
        val secure = service.baseUrl.replace("http://", "https://")
        assertEquals(TransportFailure.SecureConnection, failureOf { get(base = secure) }.failure)
    }

    @Test
    fun `no failure and no request prints the token`() {
        val token = "tok-secret-0123456789abcdef0123456789ab"
        val closed = ServerSocket(0).use { it.localPort }
        val failure = failureOf { get(token = token, base = "http://127.0.0.1:$closed") }
        var cause: Throwable? = failure
        while (cause != null) {
            assertFalse("${cause::class}: ${cause.message}", cause.message.orEmpty().contains(token))
            cause = cause.cause
        }
        assertFalse(failure.stackTraceToString().contains(token))
        assertFalse(HttpGetRequest("http://x/v1/workouts", token, 10).toString().contains(token))
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
