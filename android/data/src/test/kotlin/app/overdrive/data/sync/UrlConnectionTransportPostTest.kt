package app.overdrive.data.sync

import app.overdrive.core.sync.HttpPostRequest
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
 * The companion's `POST` (the answer to an FTP suggestion, ADR 0024) over the real `HttpURLConnection` transport against a local
 * server: the same stack, timeouts and rules as the other verbs, a JSON body of known length, and an answer read only up to the
 * size the request allows. The token and the body stay out of every failure and every printed request.
 */
class UrlConnectionTransportPostTest {
    private val service = FakeService()
    private val transport = UrlConnectionTransport()

    @After
    fun cleanUp() {
        service.close()
    }

    private fun post(
        body: String = """{"suggestionId":7}""",
        token: String = service.token,
        maxBytes: Int = 65_536,
        transport: UrlConnectionTransport = this.transport,
        base: String = service.baseUrl,
    ) = runBlocking { transport.post(HttpPostRequest("$base/v1/ftp-suggestion/dismiss", token, body, maxBytes)) }

    @Test
    fun `sends a POST with the bearer token and the JSON body exactly, and reads the answer`() {
        service.script += FakeService.Reply(200, """{"suggestionId":7,"status":"dismissed"}""")
        val answer = post()
        assertEquals(200, answer.status)
        assertEquals("""{"suggestionId":7,"status":"dismissed"}""", answer.body)
        val request = service.received.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/ftp-suggestion/dismiss", request.path)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals("application/json", request.contentType)
        assertEquals("""{"suggestionId":7}""", request.body.decodeToString())
    }

    @Test
    fun `a body with multibyte text is sent whole`() {
        service.script += FakeService.Reply(200, "{}")
        post("""{"note":"Ride the Alps – 3×10 minutes"}""")
        assertEquals("""{"note":"Ride the Alps – 3×10 minutes"}""", service.received.single().body.decodeToString())
    }

    @Test
    fun `error answers come back with their status and their message`() {
        assertEquals(401, post(token = "wrong").status)
        service.script += FakeService.Reply(404, """{"error":{"code":"not_found","message":"No such suggestion"}}""")
        val gone = post()
        assertEquals(404, gone.status)
        assertTrue(gone.body, "not_found" in gone.body)
        service.script += FakeService.Reply(503, "<html>down</html>")
        assertEquals(503, post().status)
    }

    @Test
    fun `a redirect is returned as it is, not followed`() {
        service.script +=
            FakeService.Reply(
                308,
                "",
                headers =
                    mapOf("Location" to "https://elsewhere.example/v1/ftp-suggestion/dismiss"),
            )
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
    fun `no failure and no request prints the token or the body`() {
        val token = "tok-secret-0123456789abcdef0123456789ab"
        val closed = ServerSocket(0).use { it.localPort }
        val failure =
            failureOf { post(body = """{"suggestionId":199}""", token = token, base = "http://127.0.0.1:$closed") }
        var cause: Throwable? = failure
        while (cause != null) {
            assertFalse("${cause::class}: ${cause.message}", cause.message.orEmpty().contains(token))
            cause = cause.cause
        }
        assertFalse(failure.stackTraceToString().contains(token))
        val printed =
            HttpPostRequest(
                "http://x/v1/ftp-suggestion/dismiss",
                token,
                """{"suggestionId":199}""",
                10,
            ).toString()
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
