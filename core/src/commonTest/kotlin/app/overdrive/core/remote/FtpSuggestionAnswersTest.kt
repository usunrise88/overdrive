package app.overdrive.core.remote

import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** **Keep 250 W**: the answer is hidden at once, written down before it is sent, and sent when the service can be reached. */
class FtpSuggestionAnswersTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private val dismissed = HttpAnswer(200, """{"suggestionId":7,"status":"dismissed"}""")

    private fun answers(
        transport: ScriptedTransport,
        store: MemoryTextStore? = MemoryTextStore(),
    ) = FtpSuggestionAnswers(ServiceClient({ config }, transport, transport), { config }, store)

    @Test
    fun `sends the id to the dismiss route as the contract's body with the token in the header`() =
        runTest {
            val transport = ScriptedTransport(dismissed)
            val answers = answers(transport)
            answers.dismiss(7)
            val request = transport.posts.single()
            assertEquals("$ADDRESS/v1/ftp-suggestion/dismiss", request.url)
            assertEquals(TOKEN, request.bearerToken)
            assertEquals(7, MetricsApiJson.decodeDismissRequest(request.body).suggestionId)
            assertFalse(request.toString().contains(TOKEN))
            assertEquals(FtpAnswers(setOf(7), waiting = 0), answers.state.value)
        }

    @Test
    fun `offline the suggestion is hidden at once the answer waits on disk and is sent at the next flush`() =
        runTest {
            val store = MemoryTextStore()
            val transport = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val answers = answers(transport, store)
            answers.dismiss(7)
            assertEquals(FtpAnswers(setOf(7), waiting = 1), answers.state.value)
            assertTrue(assertNotNull(store.text).contains("7 pending"), "written down before it was sent")

            // A restart: the answer is still hidden and still waiting.
            val restarted = answers(ScriptedTransport(dismissed), store)
            restarted.load()
            assertEquals(FtpAnswers(setOf(7), waiting = 1), restarted.state.value)
            restarted.flush()
            assertEquals(FtpAnswers(setOf(7), waiting = 0), restarted.state.value)
            assertTrue(assertNotNull(store.text).contains("7 sent"))
        }

    @Test
    fun `a server error leaves the answer waiting a refusal settles it`() =
        runTest {
            val transport = ScriptedTransport(error(503))
            val answers = answers(transport)
            answers.dismiss(7)
            assertEquals(1, answers.state.value.waiting)
            transport.then(error(404, "not_found"))
            answers.flush()
            assertEquals(0, answers.state.value.waiting, "the suggestion is gone: nothing left to send")
            assertEquals(setOf(7), answers.state.value.dismissed)
        }

    @Test
    fun `a rejected token leaves the answer waiting and is not sent again until the rider asks`() =
        runTest {
            val transport = ScriptedTransport(error(401, "unauthorized"))
            val client = ServiceClient({ config }, transport, transport)
            val answers = FtpSuggestionAnswers(client, { config }, MemoryTextStore())
            answers.dismiss(7)
            answers.flush()
            assertEquals(1, transport.posts.size)
            assertEquals(1, answers.state.value.waiting)
        }

    @Test
    fun `with nothing set up the answer is kept and nothing is sent`() =
        runTest {
            val transport = ScriptedTransport(dismissed)
            val none = SyncConfig("", "")
            val answers = FtpSuggestionAnswers(ServiceClient({ none }, transport, transport), { none }, null)
            answers.dismiss(7)
            assertTrue(transport.posts.isEmpty())
            assertEquals(FtpAnswers(setOf(7), waiting = 1), answers.state.value)
        }

    @Test
    fun `answers written for another service or damaged are not read`() =
        runTest {
            val good = MemoryTextStore().also { answers(ScriptedTransport(dismissed), it).dismiss(7) }.text!!
            val other = SyncConfig("https://other.example.org", TOKEN)
            val foreign =
                FtpSuggestionAnswers(
                    ServiceClient({ other }, ScriptedTransport(dismissed)),
                    { other },
                    MemoryTextStore(good),
                )
            foreign.load()
            assertEquals(FtpAnswers(), foreign.state.value)
            for (text in listOf(
                "",
                "nonsense",
                good + "x sent\n",
                good.replace("sent", "later"),
                good.replace("7 ", "-7 "),
            )) {
                val damaged = answers(ScriptedTransport(dismissed), MemoryTextStore(text))
                damaged.load()
                assertEquals(FtpAnswers(), damaged.state.value, "for ${text.take(30)}")
            }
        }

    @Test
    fun `only the last twenty answers are remembered`() =
        runTest {
            val answers = answers(ScriptedTransport(dismissed))
            for (id in 1..25) answers.dismiss(id)
            assertEquals((6..25).toSet(), answers.state.value.dismissed)
        }

    @Test
    fun `an id that is not a suggestion is refused`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { answers(ScriptedTransport(dismissed)).dismiss(0) }
        }
}
