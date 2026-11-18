package app.overdrive.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The upload of a stored ride to the service (spec §8.3, ADR 0014 sections 3 and 6) against a scripted transport: what is
 * sent, in which order and with which headers, and what every answer the service can give comes to.
 */
class HttpSyncProviderTest {
    private val token = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"
    private val rideId = "ride-1700000000000"
    private val entry = OutboxEntry(rideId, 1, 1_700_000_000_000, 600)
    private val recordBytes = """{"schema":1,"ride":{"id":"$rideId"}}""".encodeToByteArray()
    private val fitBytes = byteArrayOf(14, 0x10, 0, 0, 1, 2, 3, 4, 0x2E, 0x46, 0x49, 0x54)

    private fun stored(id: String = rideId) = """{"rideId":"$id","status":"stored"}"""

    private fun already(id: String = rideId) = """{"rideId":"$id","status":"already-stored"}"""

    private fun error(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message"}}"""

    /** What one request was, as the fake transport saw it. */
    private class Sent(
        val url: String,
        val contentType: String,
        val bearer: String,
        val body: ByteArray,
    )

    /** A transport that answers from a script, in order, and writes down what it was asked. A throwable is thrown. */
    private class ScriptedTransport(
        vararg script: Any,
    ) : HttpTransport {
        private val script = script.toMutableList()
        val sent = mutableListOf<Sent>()

        override suspend fun put(request: HttpRequest): HttpAnswer {
            val out = ByteArrayCollector()
            request.body.writeTo(out)
            check(out.size.toLong() == request.body.length) { "the body wrote ${out.size} bytes, not its length" }
            sent += Sent(request.url, request.contentType, request.bearerToken, out.bytes())
            return when (val next = script.removeAt(0)) {
                is HttpAnswer -> next
                is Throwable -> throw next
                else -> error("bad script entry")
            }
        }
    }

    private class ByteArrayCollector : ChunkSink {
        private var data = ByteArray(0)
        val size: Int get() = data.size

        fun bytes() = data

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            data += bytes.copyOfRange(offset, offset + length)
        }
    }

    private class FakeRides(
        private val payload: RidePayload? = null,
        private val failure: Exception? = null,
    ) : RidePayloads {
        var asked = 0

        override suspend fun payload(entry: OutboxEntry): RidePayload {
            asked++
            failure?.let { throw it }
            return checkNotNull(payload)
        }
    }

    private fun answer(
        status: Int,
        body: String = "",
    ) = HttpAnswer(status, body)

    private fun payload(id: String = rideId) = RidePayload(id, ByteArrayBody(recordBytes), ByteArrayBody(fitBytes))

    private fun provider(
        transport: HttpTransport,
        config: SyncConfig = SyncConfig("https://overdrive.example.org", token),
        rides: RidePayloads = FakeRides(payload()),
    ) = HttpSyncProvider({ config }, rides, transport)

    // region what is sent

    @Test
    fun `sends the record, then the FIT, to the ride's own paths with the token and the right types`() =
        runTest {
            val transport = ScriptedTransport(answer(201, stored()), answer(201, stored()))
            assertEquals(SyncResult.Acknowledged, provider(transport).upload(entry))
            assertEquals(2, transport.sent.size)
            val (record, fit) = transport.sent
            assertEquals("https://overdrive.example.org/v1/rides/$rideId", record.url)
            assertEquals("application/json", record.contentType)
            assertEquals("https://overdrive.example.org/v1/rides/$rideId/fit", fit.url)
            assertEquals("application/vnd.ant.fit", fit.contentType)
            assertEquals(token, record.bearer)
            assertEquals(token, fit.bearer)
            assertTrue(recordBytes.contentEquals(record.body), "the record's bytes as they are")
            assertTrue(fitBytes.contentEquals(fit.body), "the FIT's bytes as they are")
        }

    @Test
    fun `an identical repeat answers 200 on both and still counts as uploaded`() =
        runTest {
            val transport = ScriptedTransport(answer(200, already()), answer(200, already()))
            assertEquals(SyncResult.Acknowledged, provider(transport).upload(entry))
        }

    @Test
    fun `the address may end in a slash and may have a path`() =
        runTest {
            val transport = ScriptedTransport(answer(201, stored()), answer(201, stored()))
            provider(transport, SyncConfig("http://192.168.1.20:8080/overdrive/", token)).upload(entry)
            assertEquals("http://192.168.1.20:8080/overdrive/v1/rides/$rideId", transport.sent[0].url)
            assertEquals("http://192.168.1.20:8080/overdrive/v1/rides/$rideId/fit", transport.sent[1].url)
        }

    @Test
    fun `the ride's own id decides the path, not the outbox's file name`() =
        runTest {
            val transport = ScriptedTransport(answer(201, stored("real.id-1")), answer(201, stored("real.id-1")))
            val rides = FakeRides(payload("real.id-1"))
            val result = provider(transport, rides = rides).upload(entry.copy(rideId = "real_id-1-3f2a9c10"))
            assertEquals(SyncResult.Acknowledged, result)
            assertTrue(transport.sent[0].url.endsWith("/v1/rides/real.id-1"))
        }

    // endregion

    // region not configured

    @Test
    fun `no address or no token means nowhere to upload and nothing is read or sent`() =
        runTest {
            for (config in listOf(SyncConfig("", token), SyncConfig("https://h.example", ""), SyncConfig("", ""))) {
                val transport = ScriptedTransport()
                val rides = FakeRides(payload())
                assertEquals(SyncResult.NotConfigured, provider(transport, config, rides).upload(entry), "$config")
                assertEquals(emptyList(), transport.sent)
                assertEquals(0, rides.asked)
            }
        }

    @Test
    fun `a token that is not visible ASCII is rejected without being sent`() =
        runTest {
            for (bad in listOf("two words", "café-0123456789", "tab\there")) {
                val transport = ScriptedTransport()
                val config = SyncConfig("https://overdrive.example.org", bad)
                assertEquals(SyncResult.TokenRejected, provider(transport, config).upload(entry), bad)
                assertEquals(emptyList(), transport.sent)
            }
        }

    @Test
    fun `a change of the settings is read at the next upload with no new provider`() =
        runTest {
            var config = SyncConfig("", "")
            val transport = ScriptedTransport(answer(201, stored()), answer(201, stored()))
            val provider = HttpSyncProvider({ config }, FakeRides(payload()), transport)
            assertEquals(SyncResult.NotConfigured, provider.upload(entry))
            config = SyncConfig("https://overdrive.example.org", token)
            assertEquals(SyncResult.Acknowledged, provider.upload(entry))
        }

    // endregion

    // region 401: the token

    @Test
    fun `a 401 is a rejected token and is not asked again until the address or the token changes`() =
        runTest {
            var config = SyncConfig("https://overdrive.example.org", token)
            val transport =
                ScriptedTransport(
                    answer(401, error("unauthorized", "Missing or invalid token")),
                    answer(201, stored()),
                    answer(201, stored()),
                )
            val provider = HttpSyncProvider({ config }, FakeRides(payload()), transport)
            assertEquals(SyncResult.TokenRejected, provider.upload(entry))
            assertEquals(1, transport.sent.size, "the record request only")

            // Again and again with the same settings: the answer is known, nothing goes out.
            repeat(3) { assertEquals(SyncResult.TokenRejected, provider.upload(entry)) }
            assertEquals(1, transport.sent.size)

            // A new token is a new question.
            config = SyncConfig("https://overdrive.example.org", "tok-another-0123456789abcdef0123456789")
            assertEquals(SyncResult.Acknowledged, provider.upload(entry))
            assertEquals(3, transport.sent.size)
        }

    @Test
    fun `a changed address is a new question too, and so is the riders's send now`() =
        runTest {
            var config = SyncConfig("https://one.example.org", token)
            val transport =
                ScriptedTransport(
                    answer(401),
                    answer(401),
                    answer(401),
                    answer(201, stored()),
                    answer(201, stored()),
                )
            val provider = HttpSyncProvider({ config }, FakeRides(payload()), transport)
            assertEquals(SyncResult.TokenRejected, provider.upload(entry))
            config = SyncConfig("https://two.example.org", token)
            assertEquals(SyncResult.TokenRejected, provider.upload(entry), "the second address says 401 too")
            assertEquals(2, transport.sent.size)
            assertEquals(SyncResult.TokenRejected, provider.upload(entry))
            assertEquals(2, transport.sent.size, "and then it is known again")
            provider.retryRejected()
            assertEquals(SyncResult.TokenRejected, provider.upload(entry))
            assertEquals(3, transport.sent.size, "send now asks again")
            provider.retryRejected()
            assertEquals(SyncResult.Acknowledged, provider.upload(entry))
        }

    @Test
    fun `a 401 on the FIT after the record went through is a rejected token too`() =
        runTest {
            val transport = ScriptedTransport(answer(201, stored()), answer(401))
            assertEquals(SyncResult.TokenRejected, provider(transport).upload(entry))
        }

    // endregion

    // region retry later

    @Test
    fun `5xx, 408 and 429 are a retry with a plain reason`() =
        runTest {
            val expected =
                mapOf(
                    500 to "Service error (500)",
                    502 to "Service error (502)",
                    503 to "Service error (503)",
                    504 to "Service error (504)",
                    408 to "The service timed out",
                    429 to "The service is busy",
                )
            for ((status, reason) in expected) {
                val transport = ScriptedTransport(answer(status, error("internal_error", "Something went wrong")))
                assertEquals(SyncResult.RetryLater(reason), provider(transport).upload(entry), "status $status")
                assertEquals(1, transport.sent.size, "nothing else is sent after a failure")
            }
        }

    @Test
    fun `no answer is a retry whatever the failure, with its own words`() =
        runTest {
            val expected =
                mapOf(
                    TransportFailure.NameNotFound to "Cannot find the service address (DNS)",
                    TransportFailure.NetworkBlocked to "The system blocks this app's network access",
                    TransportFailure.NoNetwork to "No network connection",
                    TransportFailure.Refused to "The service refused the connection",
                    TransportFailure.Dropped to "The connection to the service was cut",
                    TransportFailure.Unreachable to "Service unreachable",
                    TransportFailure.TimedOut to "The service did not answer in time",
                    TransportFailure.SecureConnection to "Secure connection failed",
                    TransportFailure.Other to "Could not talk to the service",
                )
            assertEquals(TransportFailure.entries.toSet(), expected.keys, "every kind has its words")
            assertEquals(expected.values.size, expected.values.toSet().size, "and no two kinds share them")
            for ((failure, reason) in expected) {
                val transport = ScriptedTransport(TransportException(failure))
                assertEquals(SyncResult.RetryLater(reason), provider(transport).upload(entry), "$failure")
            }
        }

    @Test
    fun `the technical hint follows the words in brackets, for the owner to read`() =
        runTest {
            val blocked = TransportException(TransportFailure.NetworkBlocked, hint = "SocketException · EACCES")
            assertEquals(
                SyncResult.RetryLater("The system blocks this app's network access (SocketException · EACCES)"),
                provider(ScriptedTransport(blocked)).upload(entry),
            )
            val dns = TransportException(TransportFailure.NameNotFound, hint = "UnknownHostException")
            assertEquals(
                SyncResult.RetryLater("Cannot find the service address (DNS) (UnknownHostException)"),
                provider(ScriptedTransport(dns)).upload(entry),
            )
        }

    @Test
    fun `a failure on the FIT after the record is a retry, and the retry sends both again and succeeds`() =
        runTest {
            val transport =
                ScriptedTransport(
                    answer(201, stored()),
                    answer(503),
                    // The retry: the record is already stored, the FIT goes through.
                    answer(200, already()),
                    answer(201, stored()),
                )
            val provider = provider(transport)
            assertEquals(SyncResult.RetryLater("Service error (503)"), provider.upload(entry))
            assertEquals(SyncResult.Acknowledged, provider.upload(entry))
            assertEquals(
                listOf("", "/fit", "", "/fit").map { "https://overdrive.example.org/v1/rides/$rideId$it" },
                transport.sent.map { it.url },
            )
            assertTrue(transport.sent[0].body.contentEquals(transport.sent[2].body), "the same bytes again")
        }

    @Test
    fun `a 404 for the FIT of a record the service has just stored is a retry, not a refusal`() =
        runTest {
            val transport =
                ScriptedTransport(answer(201, stored()), answer(404, error("ride_not_found", "Ride is not stored yet")))
            assertEquals(
                SyncResult.RetryLater("The service lost the ride's record"),
                provider(transport).upload(entry),
            )
        }

    @Test
    fun `an answer that is not the service's upload response is not a success`() =
        runTest {
            val bad =
                listOf(
                    answer(200, ""),
                    answer(200, "<html>Welcome to the hotel wifi</html>"),
                    answer(201, stored("ride-1")),
                    answer(200, """{"rideId":"$rideId","status":"done"}"""),
                    answer(204),
                    answer(202, stored()),
                )
            for (first in bad) {
                val transport = ScriptedTransport(first)
                assertEquals(
                    SyncResult.RetryLater("That is not the Overdrive service"),
                    provider(transport).upload(entry),
                    "${first.status} ${first.body}",
                )
                assertEquals(1, transport.sent.size)
            }
        }

    @Test
    fun `a redirect is a retry, because a PUT cannot follow it and the ride is not at fault`() =
        runTest {
            for (status in listOf(301, 302, 307, 308)) {
                val transport = ScriptedTransport(answer(status))
                assertEquals(
                    SyncResult.RetryLater("The address redirects elsewhere"),
                    provider(transport).upload(entry),
                    "$status",
                )
            }
        }

    @Test
    fun `cancelling an upload is not a retry`() =
        runTest {
            val transport = ScriptedTransport(CancellationException("stop"))
            assertFailsWith<CancellationException> { provider(transport).upload(entry) }
        }

    // endregion

    // region refused for good

    @Test
    fun `a 409 or 422 is a refusal that carries the service's own words`() =
        runTest {
            val cases =
                listOf(
                    409 to ("ride_conflict" to "Ride ride-1 is already stored with different content"),
                    409 to ("start_time_conflict" to "A ride that started at 1700 is already stored as ride-9"),
                    422 to ("invalid_record" to "samples[3].t: expected 3"),
                    422 to ("ride_id_mismatch" to "The path says a, the record says b"),
                    400 to ("invalid_ride_id" to "The ride id must be 1 to 128 characters"),
                    413 to ("payload_too_large" to "The body is larger than the limit"),
                    415 to ("unsupported_media_type" to "Send application/json"),
                    404 to ("not_found" to "No such path"),
                )
            for ((status, coded) in cases) {
                val (code, message) = coded
                val transport = ScriptedTransport(answer(status, error(code, message)))
                assertEquals(SyncResult.Refused(message), provider(transport).upload(entry), "$status $code")
                assertEquals(1, transport.sent.size, "$code: nothing more is sent")
            }
        }

    @Test
    fun `a 409 or 422 on the FIT is a refusal as well`() =
        runTest {
            val transport =
                ScriptedTransport(
                    answer(201, stored()),
                    answer(409, error("fit_conflict", "Ride already has a different FIT file")),
                )
            assertEquals(
                SyncResult.Refused("Ride already has a different FIT file"),
                provider(transport).upload(entry),
            )
            val transport2 =
                ScriptedTransport(answer(201, stored()), answer(422, error("invalid_fit", "The CRC does not match")))
            assertEquals(SyncResult.Refused("The CRC does not match"), provider(transport2).upload(entry))
        }

    @Test
    fun `a refusal without a readable body still says what happened`() =
        runTest {
            for (body in listOf("", "<html>Bad request</html>", """{"error":{"code":"nope","message":"x"}}""")) {
                val transport = ScriptedTransport(answer(409, body))
                val refused = SyncResult.Refused("The service refused the ride (409)")
                assertEquals(refused, provider(transport).upload(entry), body)
            }
        }

    @Test
    fun `a long or multi-line message is cut to one short line`() =
        runTest {
            val long = "x".repeat(300)
            val transport = ScriptedTransport(answer(422, error("invalid_record", long)))
            val refused = provider(transport).upload(entry) as SyncResult.Refused
            assertTrue(refused.message.length <= 160, "${refused.message.length}")
            assertTrue(refused.message.endsWith("…"))

            val multi = ScriptedTransport(answer(422, error("invalid_record", "first\\nsecond\\tthird")))
            assertEquals(SyncResult.Refused("first second third"), provider(multi).upload(entry))
        }

    @Test
    fun `a ride whose files are gone or damaged is refused without asking the service`() =
        runTest {
            val transport = ScriptedTransport()
            val rides = FakeRides(failure = RideUnavailableException("The ride's FIT file is missing on this tablet"))
            assertEquals(
                SyncResult.Refused("The ride's FIT file is missing on this tablet"),
                provider(transport, rides = rides).upload(entry),
            )
            assertEquals(emptyList(), transport.sent)
        }

    @Test
    fun `a ride id the service would not accept is refused before anything is sent`() =
        runTest {
            val transport = ScriptedTransport()
            val result = provider(transport, rides = FakeRides(payload("has space"))).upload(entry)
            assertEquals(SyncResult.Refused("The ride's id is not one the service accepts"), result)
            assertEquals(emptyList(), transport.sent)
        }

    @Test
    fun `a disk that fails is not the ride's fault and surfaces as an exception for the coordinator to retry`() =
        runTest {
            val rides = FakeRides(failure = IllegalStateException("disk gone"))
            assertFailsWith<IllegalStateException> { provider(ScriptedTransport(), rides = rides).upload(entry) }
        }

    // endregion

    // region the token stays secret

    @Test
    fun `the token is in the Authorization header only, never in a result, a reason, a message or a string`() =
        runTest {
            val everything = mutableListOf<String>()
            val scripts: List<List<Any>> =
                listOf(
                    listOf(answer(201, stored()), answer(201, stored())),
                    listOf(answer(401, error("unauthorized", "Missing or invalid token"))),
                    listOf(answer(503)),
                    listOf(answer(429)),
                    listOf(answer(409, error("ride_conflict", "already stored"))),
                    listOf(answer(200, "<html></html>")),
                    listOf(TransportException(TransportFailure.Unreachable, IllegalStateException("connect failed"))),
                    listOf(TransportException(TransportFailure.SecureConnection)),
                )
            for (script in scripts) {
                val transport = ScriptedTransport(*script.toTypedArray())
                val config = SyncConfig("https://overdrive.example.org", token)
                val result = provider(transport, config).upload(entry)
                everything += result.toString()
                everything += config.toString()
                everything += transport.sent.map { "${it.url} ${it.contentType}" }
            }
            val request = HttpRequest("https://h.example/x", "application/json", token, ByteArrayBody(recordBytes))
            everything += request.toString()
            everything += TransportException(TransportFailure.Other).message.orEmpty()
            // Every kind of failure, even with a cause that carries the address and the token in its message: what the owner
            // reads (the result's reason) and the exception's own message name neither.
            for (failure in TransportFailure.entries) {
                val cause = IllegalStateException("failed to connect to overdrive.example.org/203.0.113.7 with $token")
                val exception = TransportException(failure, cause, hint = "SocketException · EACCES")
                everything += exception.message.orEmpty()
                everything += provider(ScriptedTransport(exception)).upload(entry).toString()
            }
            for (text in everything) assertFalse(text.contains(token), "the token leaked into: $text")
            for (text in everything) assertFalse(text.contains("203.0.113.7"), "the address leaked into: $text")
            assertTrue(everything.any { "••••" in it }, "the config prints a mask where the token would be")
        }

    @Test
    fun `an empty token prints as none`() {
        assertEquals("SyncConfig(baseUrl=https://h, token=(none))", SyncConfig("https://h", "").toString())
        assertFalse(SyncConfig("https://h.example", "").isConfigured)
        assertFalse(SyncConfig("  ", "tok").isConfigured)
        assertTrue(SyncConfig("https://h.example", "t").isConfigured)
        assertEquals(SyncConfig("a", "b"), SyncConfig("a", "b"))
        assertTrue(SyncConfig("a", "b") != SyncConfig("a", "c"))
    }

    // endregion
}
