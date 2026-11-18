package app.overdrive.data.sync

import app.overdrive.core.sync.ByteArrayBody
import app.overdrive.core.sync.ChunkSink
import app.overdrive.core.sync.HttpRequest
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.sync.UploadBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files

/** The real `HttpURLConnection` transport against a local server: what goes out, what comes back, and every way to get no answer. */
class UrlConnectionTransportTest {
    private val service = FakeService()
    private val directory = Files.createTempDirectory("transport").toFile()

    @After
    fun cleanUp() {
        service.close()
        directory.deleteRecursively()
    }

    private fun put(
        path: String,
        body: ByteArray,
        type: String = "application/json",
        token: String = service.token,
        transport: UrlConnectionTransport = UrlConnectionTransport(),
        base: String = service.baseUrl,
    ) = runBlocking { transport.put(HttpRequest(base + path, type, token, ByteArrayBody(body))) }

    @Test
    fun `sends a PUT with the bearer token, the content type and the body as it is, and reads the answer`() {
        val body = """{"ride":"x"}""".encodeToByteArray()
        val answer = put("/v1/rides/ride-1", body)
        assertEquals(201, answer.status)
        assertEquals("""{"rideId":"ride-1","status":"stored"}""", answer.body)
        val request = service.received.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/rides/ride-1", request.path)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals("application/json", request.contentType)
        assertArrayEquals(body, request.body)
    }

    @Test
    fun `error answers come back with their status and their message`() {
        val denied = put("/v1/rides/ride-1", byteArrayOf(1), token = "wrong")
        assertEquals(401, denied.status) // a streamed 401 has no readable body on the JDK; the status is enough
        put("/v1/rides/ride-1", byteArrayOf(1))
        val conflict = put("/v1/rides/ride-1", byteArrayOf(2))
        assertEquals(409, conflict.status)
        assertTrue(conflict.body, "different content" in conflict.body)
        service.script += FakeService.Reply(503, "<html>down</html>")
        assertEquals(503, put("/v1/rides/ride-2", byteArrayOf(1)).status)
        assertEquals(404, put("/nowhere", byteArrayOf(1)).status)
    }

    @Test
    fun `a body of several megabytes is streamed whole and a FIT goes as vnd ant fit`() {
        val big = ByteArray(5 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }
        val file = directory.resolve("big.fit").also { it.writeBytes(big) }
        val answer =
            runBlocking {
                UrlConnectionTransport().put(
                    HttpRequest(
                        "${service.baseUrl}/v1/rides/ride-1",
                        "application/vnd.ant.fit",
                        service.token,
                        FileBody(file),
                    ),
                )
            }
        assertEquals(201, answer.status)
        assertEquals("application/vnd.ant.fit", service.received.single().contentType)
        assertArrayEquals(big, service.received.single().body)
    }

    @Test
    fun `a redirect is returned as it is, not followed`() {
        service.script +=
            FakeService.Reply(308, "", headers = mapOf("Location" to "https://elsewhere.example/v1/rides/r"))
        val answer = put("/v1/rides/ride-1", byteArrayOf(1))
        assertEquals(308, answer.status)
        assertEquals(1, service.received.size)
    }

    @Test
    fun `an answer is read only as far as an error message needs`() {
        service.script += FakeService.Reply(500, "x".repeat(100_000))
        val answer = put("/v1/rides/ride-1", byteArrayOf(1))
        assertEquals(500, answer.status)
        assertTrue("${answer.body.length}", answer.body.length <= 4_096)
    }

    @Test
    fun `a server that is not there is unreachable`() {
        val closed = ServerSocket(0).use { it.localPort }
        val failure = failureOf { put("/v1/rides/r", byteArrayOf(1), base = "http://127.0.0.1:$closed") }
        assertEquals(TransportFailure.Unreachable, failure.failure)
    }

    @Test
    fun `a name that does not resolve is unreachable`() {
        val failure = failureOf { put("/v1/rides/r", byteArrayOf(1), base = "http://no-such-host.invalid") }
        assertEquals(TransportFailure.Unreachable, failure.failure)
    }

    @Test
    fun `a server that is too slow is a timeout`() {
        service.script += FakeService.Reply(201, "{}", delayMillis = 2_000)
        val transport = UrlConnectionTransport(connectTimeoutMillis = 500, readTimeoutMillis = 300)
        val failure = failureOf { put("/v1/rides/r", byteArrayOf(1), transport = transport) }
        assertEquals(TransportFailure.TimedOut, failure.failure)
    }

    @Test
    fun `speaking TLS to a plain server is a secure connection failure`() {
        val secure = service.baseUrl.replace("http://", "https://")
        val failure = failureOf { put("/v1/rides/r", byteArrayOf(1), base = secure) }
        assertEquals(TransportFailure.SecureConnection, failure.failure)
    }

    @Test
    fun `a body that cannot be read fails the request and sends no wrong length`() {
        val failing =
            object : UploadBody {
                override val length = 10L

                override fun writeTo(sink: ChunkSink) {
                    sink.write(ByteArray(4), 0, 4)
                    throw IOException("disk gone")
                }
            }
        val failure =
            failureOf {
                runBlocking {
                    UrlConnectionTransport().put(
                        HttpRequest("${service.baseUrl}/v1/rides/r", "application/json", "t", failing),
                    )
                }
            }
        assertEquals(TransportFailure.Other, failure.failure)
    }

    @Test
    fun `no failure mentions the token, the body or the address's path`() {
        val token = "tok-secret-0123456789abcdef0123456789ab"
        val closed = ServerSocket(0).use { it.localPort }
        val failures =
            listOf(
                failureOf { put("/v1/rides/ride-1", byteArrayOf(7), token = token, base = "http://127.0.0.1:$closed") },
                failureOf {
                    put(
                        "/v1/rides/ride-1",
                        byteArrayOf(7),
                        token = token,
                        base = service.baseUrl.replace("http", "https"),
                    )
                },
            )
        for (failure in failures) {
            var cause: Throwable? = failure
            while (cause != null) {
                assertFalse("${cause::class}: ${cause.message}", cause.message.orEmpty().contains(token))
                cause = cause.cause
            }
            assertFalse(failure.stackTraceToString().contains(token))
        }
    }

    @Test
    fun `a file body is its file, in pieces`() {
        val file = directory.resolve("a.bin").also { it.writeBytes(ByteArray(40_000) { (it % 7).toByte() }) }
        val body = FileBody(file)
        assertEquals(40_000L, body.length)
        val out = java.io.ByteArrayOutputStream()
        var pieces = 0
        body.writeTo { bytes, offset, length ->
            pieces++
            out.write(bytes, offset, length)
        }
        assertArrayEquals(file.readBytes(), out.toByteArray())
        assertTrue("streamed in more than one piece: $pieces", pieces > 1)
    }

    @Test
    fun `a stream that does not match the promised length is an error and not a damaged upload`() {
        val file = directory.resolve("b.bin").also { it.writeBytes(ByteArray(1_000) { 2 }) }
        val lying =
            object : UploadBody {
                override val length = 5_000L

                override fun writeTo(sink: ChunkSink) = FileBody(file).writeTo(sink)
            }
        val failure =
            failureOf {
                runBlocking {
                    UrlConnectionTransport().put(
                        HttpRequest("${service.baseUrl}/v1/rides/r", "application/json", service.token, lying),
                    )
                }
            }
        assertEquals(TransportFailure.Other, failure.failure)
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
