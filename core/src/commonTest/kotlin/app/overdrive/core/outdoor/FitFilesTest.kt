package app.overdrive.core.outdoor

import app.overdrive.core.remote.ADDRESS
import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ScriptedTransport
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.TOKEN
import app.overdrive.core.sync.ApiErrorCode
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpPostBytesRequest
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is said of a shared file by its name and bytes, and the client's way of sending bytes (ADR 0028). */
class FitFilesTest {
    @Test
    fun `a FIT type is a FIT file whatever its name a generic type needs the extension`() {
        assertTrue(FitFileCheck.looksLikeFit("ride", "application/vnd.ant.fit"))
        assertTrue(FitFileCheck.looksLikeFit(null, "application/x-fit"))
        assertTrue(FitFileCheck.looksLikeFit("ride", "Application/VND.ANT.FIT; charset=binary"))
        assertTrue(FitFileCheck.looksLikeFit("Ride_2027-03-14.fit", "application/octet-stream"))
        assertTrue(FitFileCheck.looksLikeFit("RIDE.FIT", "*/*"))
        assertTrue(FitFileCheck.looksLikeFit(" ride.fit ", null))
        assertFalse(FitFileCheck.looksLikeFit("ride.gpx", "application/octet-stream"))
        assertFalse(FitFileCheck.looksLikeFit("ride.fit.zip", "application/octet-stream"))
        assertFalse(FitFileCheck.looksLikeFit("ride", "application/octet-stream"))
        assertFalse(FitFileCheck.looksLikeFit(null, "*/*"))
        assertFalse(FitFileCheck.looksLikeFit("holiday.jpg", "image/jpeg"))
        assertFalse(FitFileCheck.looksLikeFit("fit", "text/plain"))
    }

    @Test
    fun `the header of the file is what says it is FIT the longer header too`() {
        assertNull(FitFileCheck.refusalOf(FitSamples.bytes()))
        val long = FitSamples.bytes(1, 80).also { it[0] = 14 }
        assertNull(FitFileCheck.refusalOf(long))
        val badHeaderSize = FitSamples.bytes().also { it[0] = 13 }
        assertEquals(FitRefusal.NOT_A_FIT_FILE, FitFileCheck.refusalOf(badHeaderSize))
        assertEquals(FitRefusal.EMPTY_FILE, FitFileCheck.refusalOf(ByteArray(0)))
        assertEquals(FitRefusal.NOT_A_FIT_FILE, FitFileCheck.refusalOf(ByteArray(2000)))
    }

    @Test
    fun `the checksum is the CRC-32 in eight hex digits`() {
        assertEquals("cbf43926", FitFileCheck.checksumOf("123456789".encodeToByteArray()))
        assertEquals("00000000", FitFileCheck.checksumOf(ByteArray(0)))
    }

    // region the client's POST of bytes

    private val file = FitSamples.bytes()

    private fun client(
        transport: ScriptedFitTransport?,
        config: SyncConfig = SyncConfig(ADDRESS, TOKEN),
    ) = ServiceClient({ config }, ScriptedTransport(), null, transport)

    @Test
    fun `bytes go to the path under the address with the token and the type and a 2xx is the answer`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, "{}"))
            val outcome = client(transport).postBytes("/v1/activities/fit", FIT_CONTENT_TYPE, file, 1000)
            assertEquals(201, assertIs<Outcome.Ok<HttpAnswer>>(outcome).value.status)
            val request: HttpPostBytesRequest = transport.posts.single()
            assertEquals("$ADDRESS/v1/activities/fit", request.url)
            assertEquals(TOKEN, request.bearerToken)
            assertEquals("application/vnd.ant.fit", request.contentType)
            assertEquals(1000, request.maxBytes)
            assertTrue(file.contentEquals(request.body))
            assertFalse(request.toString().contains(TOKEN))
            assertEquals(
                "POST $ADDRESS/v1/activities/fit (application/vnd.ant.fit, ${file.size} bytes)",
                request.toString(),
            )
        }

    @Test
    fun `a refusal carries the services error code a failure of the network carries none`() =
        runTest {
            val refused = ScriptedFitTransport(FitSamples.error(422, "unsupported_sport"))
            val failed =
                assertIs<Outcome.Failed>(client(refused).postBytes("/v1/activities/fit", FIT_CONTENT_TYPE, file, 10))
            assertEquals(ApiErrorCode.UNSUPPORTED_SPORT, failed.code)
            assertEquals(ProblemKind.REQUEST_REFUSED, failed.problem.kind)
            assertEquals(422, failed.problem.status)

            val html = ScriptedFitTransport(HttpAnswer(502, "<html>bad gateway</html>"))
            assertNull(assertIs<Outcome.Failed>(client(html).postBytes("/p", FIT_CONTENT_TYPE, file, 10)).code)

            val offline = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val down = assertIs<Outcome.Failed>(client(offline).postBytes("/p", FIT_CONTENT_TYPE, file, 10))
            assertNull(down.code)
            assertTrue(down.problem.isOffline)
            assertFalse(down.problem.toString().contains(TOKEN))
        }

    @Test
    fun `nothing set up asks nothing no byte transport is not the service and a refused token is not sent twice`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, "{}"))
            assertEquals(
                Outcome.NotSetUp,
                client(transport, SyncConfig("", "")).postBytes("/p", FIT_CONTENT_TYPE, file, 10),
            )
            assertTrue(transport.posts.isEmpty())
            val none = assertIs<Outcome.Failed>(client(null).postBytes("/p", FIT_CONTENT_TYPE, file, 10))
            assertEquals(ProblemKind.NOT_THE_SERVICE, none.problem.kind)

            val refused = ScriptedFitTransport(FitSamples.error(401, "unauthorized"))
            val client = client(refused)
            assertEquals(
                ProblemKind.TOKEN_REJECTED,
                assertIs<Outcome.Failed>(client.postBytes("/p", FIT_CONTENT_TYPE, file, 10)).problem.kind,
            )
            client.postBytes("/p", FIT_CONTENT_TYPE, file, 10)
            assertEquals(1, refused.posts.size, "a refused token is remembered")
            client.forgetRejection()
            client.postBytes("/p", FIT_CONTENT_TYPE, file, 10)
            assertEquals(2, refused.posts.size)
        }

    @Test
    fun `a token that cannot be a header is refused without going out`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, "{}"))
            val outcome = client(transport, SyncConfig(ADDRESS, "tok é")).postBytes("/p", FIT_CONTENT_TYPE, file, 10)
            assertEquals(ProblemKind.TOKEN_REJECTED, assertIs<Outcome.Failed>(outcome).problem.kind)
            assertTrue(transport.posts.isEmpty())
        }
}
