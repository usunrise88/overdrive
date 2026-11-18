package app.overdrive.data.sync

import app.overdrive.core.sync.HttpPatchRequest
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
 * The profile's `PATCH` over the real `HttpURLConnection` transport (ADR 0021) against a local server: the same stack, timeouts
 * and rules as the upload, a JSON body of known length, and an answer read only up to the size the request allows. The JDK's
 * `HttpURLConnection` refuses the verb, so the tests write it in ([JdkPatch]); Android's accepts it as it is.
 */
class UrlConnectionTransportPatchTest {
    private val service = FakeService()
    private val transport = UrlConnectionTransport(setPatchMethod = JdkPatch::allow)

    @After
    fun cleanUp() {
        service.close()
    }

    private fun patch(
        body: String = """{"ftp":180}""",
        token: String = service.token,
        maxBytes: Int = 65_536,
        transport: UrlConnectionTransport = this.transport,
        base: String = service.baseUrl,
    ) = runBlocking { transport.patch(HttpPatchRequest("$base/v1/profile", token, body, maxBytes)) }

    @Test
    fun `sends a PATCH with the bearer token and the JSON body exactly, and reads the answer`() {
        service.script += FakeService.Reply(200, """{"ftp":180}""")
        val answer = patch("""{"ftp":180,"physics":{"cdA":0.31}}""")
        assertEquals(200, answer.status)
        assertEquals("""{"ftp":180}""", answer.body)
        val request = service.received.single()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/profile", request.path)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals("application/json", request.contentType)
        assertEquals("""{"ftp":180,"physics":{"cdA":0.31}}""", request.body.decodeToString())
    }

    @Test
    fun `a body with multibyte text is sent whole`() {
        service.script += FakeService.Reply(200, "{}")
        patch("""{"goals":["Ride the Alps – 3×10 minutes"]}""")
        assertEquals("""{"goals":["Ride the Alps – 3×10 minutes"]}""", service.received.single().body.decodeToString())
    }

    @Test
    fun `error answers come back with their status and their message`() {
        // The status is what the repository reads of a 401 (the JDK drops the body of a 401 to a streamed request).
        assertEquals(401, patch(token = "wrong").status)
        service.script +=
            FakeService.Reply(422, """{"error":{"code":"invalid_profile","message":"Not a valid change"}}""")
        val refused = patch()
        assertEquals(422, refused.status)
        assertTrue(refused.body, "invalid_profile" in refused.body)
        service.script += FakeService.Reply(503, "<html>down</html>")
        assertEquals(503, patch().status)
    }

    @Test
    fun `a redirect is returned as it is, not followed`() {
        service.script +=
            FakeService.Reply(308, "", headers = mapOf("Location" to "https://elsewhere.example/v1/profile"))
        assertEquals(308, patch().status)
        assertEquals(1, service.received.size)
    }

    @Test
    fun `an answer longer than the request allows is a failure, not a truncated profile`() {
        service.script += FakeService.Reply(200, "x".repeat(10_001))
        assertEquals(TransportFailure.Other, failureOf { patch(maxBytes = 10_000) }.failure)
        service.script += FakeService.Reply(200, "x".repeat(10_000))
        assertEquals("exactly the limit is fine", 10_000, patch(maxBytes = 10_000).body.length)
    }

    @Test
    fun `a closed port is refused, a name that does not resolve is a missing address, and a slow server times out`() {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(TransportFailure.Refused, failureOf { patch(base = "http://127.0.0.1:$closed") }.failure)
        assertEquals(TransportFailure.NameNotFound, failureOf { patch(base = "http://nonexistent.invalid") }.failure)
        service.script += FakeService.Reply(200, "{}", delayMillis = 2_000)
        val impatient =
            UrlConnectionTransport(
                connectTimeoutMillis = 500,
                readTimeoutMillis = 300,
                setPatchMethod = JdkPatch::allow,
            )
        assertEquals(TransportFailure.TimedOut, failureOf { patch(transport = impatient) }.failure)
    }

    @Test
    fun `a platform that refuses the verb is a failure with no answer, not a crash`() {
        val refusing = UrlConnectionTransport() // the JDK's own refusal: no way round it was given
        assertEquals(TransportFailure.Other, failureOf { patch(transport = refusing) }.failure)
        assertTrue(service.received.isEmpty())
    }

    @Test
    fun `no failure and no request prints the token or the body`() {
        val token = "tok-secret-0123456789abcdef0123456789ab"
        val closed = ServerSocket(0).use { it.localPort }
        val failure = failureOf { patch(body = """{"ftp":199}""", token = token, base = "http://127.0.0.1:$closed") }
        var cause: Throwable? = failure
        while (cause != null) {
            assertFalse("${cause::class}: ${cause.message}", cause.message.orEmpty().contains(token))
            cause = cause.cause
        }
        assertFalse(failure.stackTraceToString().contains(token))
        val printed = HttpPatchRequest("http://x/v1/profile", token, """{"ftp":199}""", 10).toString()
        assertFalse(printed.contains(token))
        assertFalse(printed.contains("199"))
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
