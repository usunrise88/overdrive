package app.overdrive.core.remote

import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.repeats.RepeatPreviousAttempt
import app.overdrive.core.repeats.RepeatPreviousResponse
import app.overdrive.core.repeats.RepeatPreviousStep
import app.overdrive.core.repeats.RepeatsApiJson
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The overlay's one request for the previous attempt (docs/adr/0034): it comes back with the attempt, or with null for **everything**
 * else, and never throws, so a missing answer can never be an error on the riding screen.
 */
class PreviousAttemptFetchTest {
    private val hash = RepeatSamples.HASH
    private var config = SyncConfig(ADDRESS, TOKEN)

    private val attempt =
        RepeatPreviousAttempt(
            rideId = "ride-1791500000000",
            date = "2026-11-12",
            startTime = "2026-11-12T08:00:00Z",
            durationSeconds = 1200,
            ftp = 250,
            attempt = 2,
            heartRateAtPower = 148.0,
            steps = listOf(RepeatPreviousStep(2, "Sweet spot", 300, 900, 220.0, 148.0)),
        )

    private fun body(
        previous: RepeatPreviousAttempt?,
        answerHash: String = hash,
    ) = RepeatsApiJson.encodePrevious(RepeatPreviousResponse("2026-11-19T07:30:00Z", answerHash, null, previous))

    private suspend fun fetch(
        transport: ScriptedTransport,
        askedHash: String = hash,
    ) = previousAttemptOf(ServiceClient({ config }, transport), askedHash)

    @Test
    fun `asks the repeats route for the session's previous attempt with the token in the header and no ride id`() =
        runTest {
            val transport = ScriptedTransport(ok(body(attempt)))
            val got = fetch(transport)
            assertEquals(attempt, got)
            val request = transport.gets.single()
            assertEquals("$ADDRESS/v1/repeats/$hash/previous", request.url)
            assertEquals(TOKEN, request.bearerToken)
            assertFalse(request.toString().contains(TOKEN))
        }

    @Test
    fun `no earlier attempt is no line`() = runTest { assertNull(fetch(ScriptedTransport(ok(body(null))))) }

    @Test
    fun `no address or token asks nothing`() =
        runTest {
            config = SyncConfig("", "")
            val transport = ScriptedTransport(ok(body(attempt)))
            assertNull(fetch(transport))
            assertEquals(0, transport.gets.size)
        }

    @Test
    fun `a hash that is not a session hash asks nothing`() =
        runTest {
            val transport = ScriptedTransport(ok(body(attempt)))
            for (bad in listOf("", "xyz", "B1ADD004E12D9617", "b1add004e12d96170", "../repeats")) {
                assertNull(fetch(transport, bad), bad)
            }
            assertEquals(0, transport.gets.size)
        }

    @Test
    fun `no network a down service a 404 a refused token and a bad body are all no line and none throws`() =
        runTest {
            val failures =
                listOf<Any>(
                    TransportException(TransportFailure.NoNetwork),
                    TransportException(TransportFailure.TimedOut),
                    error(503),
                    error(404, "not_found"),
                    error(404, "ride_not_found"),
                    error(401, "unauthorized"),
                    error(500),
                    HttpAnswer(200, "not json at all"),
                    HttpAnswer(200, """{"hash":"$hash"}"""),
                    HttpAnswer(200, "{}"),
                    HttpAnswer(200, body(attempt).replace("\"steps\"", "\"stepz\"")),
                )
            for (failure in failures) assertNull(fetch(ScriptedTransport(failure)), failure.toString())
        }

    @Test
    fun `an answer for another session is not used`() =
        runTest { assertNull(fetch(ScriptedTransport(ok(body(attempt, answerHash = RepeatSamples.OTHER_HASH))))) }

    @Test
    fun `reads the previous attempt in full`() =
        runTest {
            val got = assertNotNull(fetch(ScriptedTransport(ok(body(attempt)))))
            assertEquals(220.0, got.steps.single().targetWatts)
            assertEquals(148.0, got.steps.single().averageHeartRate)
        }
}
