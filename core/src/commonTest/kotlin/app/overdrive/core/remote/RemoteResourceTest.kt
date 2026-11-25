package app.overdrive.core.remote

import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.remote.RemoteSamples.json
import app.overdrive.core.remote.RemoteSamples.metrics
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One resource of the service on the phone (spec §6.1, ADR 0024) against a scripted transport and an in-memory cache: what is
 * asked, when, what every answer comes to, and what stays when the service cannot be reached.
 */
class RemoteResourceTest {
    private var config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L
    private val answer = ok(json(metrics("2026-11-16", "2026-11-18")))

    private fun resource(
        transport: ScriptedTransport,
        store: MemoryTextStore? = MemoryTextStore(),
        client: ServiceClient = ServiceClient({ config }, transport),
    ) = RemoteResource(
        kind = "metrics-test",
        client = client,
        config = { config },
        path = { "/v1/metrics?from=2026-11-16&to=2026-11-18" },
        decode = MetricsApiJson::decodeMetrics,
        store = store,
        wallMillis = { wall },
        elapsedMillis = { elapsed },
    )

    @Test
    fun `asks the service for the path with the token in the header and says when it got the answer`() =
        runTest {
            val transport = ScriptedTransport(answer)
            val resource = resource(transport)
            resource.load()
            resource.refresh(Refresh.START)
            val request = transport.gets.single()
            assertEquals("$ADDRESS/v1/metrics?from=2026-11-16&to=2026-11-18", request.url)
            assertEquals(TOKEN, request.bearerToken)
            assertFalse(request.toString().contains(TOKEN))
            val state = resource.state.value
            assertEquals("2026-11-18", state.value?.to)
            assertEquals(wall, state.fetchedAtMillis)
            assertNull(state.problem)
            assertFalse(state.refreshing)
            assertFalse(state.isLoading)
        }

    @Test
    fun `nothing cached and nothing asked yet is loading not empty`() =
        runTest {
            val resource = resource(ScriptedTransport(answer))
            assertTrue(resource.state.value.isLoading)
            resource.load()
            assertTrue(resource.state.value.isLoading, "the cache is read, there is nothing in it and nobody has asked")
        }

    @Test
    fun `the answer is on disk by the time it is published`() =
        runTest {
            val store = MemoryTextStore()
            val resource = resource(ScriptedTransport(answer), store)
            resource.refresh(Refresh.START)
            val text = assertNotNull(store.text)
            val cached = assertNotNull(CacheText.decode("metrics-test", text))
            assertEquals(CacheText.sourceOf(ADDRESS), cached.source)
            assertEquals(wall, cached.fetchedAtMillis)
            assertFalse(text.contains(TOKEN), "no token on disk")
        }

    @Test
    fun `after a restart the cache is shown with no network and the failure is said next to it`() =
        runTest {
            val store = MemoryTextStore()
            resource(ScriptedTransport(answer), store).refresh(Refresh.START)
            val restarted = resource(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), store)
            restarted.load()
            assertEquals("2026-11-18", restarted.state.value.value?.to)
            assertFalse(restarted.state.value.isLoading)
            restarted.refresh(Refresh.START)
            val state = restarted.state.value
            assertEquals("2026-11-18", state.value?.to)
            assertTrue(state.isStale)
            assertEquals(ProblemKind.NO_NETWORK, state.problem?.kind)
            assertTrue(state.problem?.isOffline == true)
            assertEquals("No network connection", state.problem?.plain)
        }

    @Test
    fun `a resource that never loaded reads its cache before it asks`() =
        runTest {
            val store = MemoryTextStore()
            resource(ScriptedTransport(answer), store).refresh(Refresh.START)
            val fresh = resource(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), store)
            fresh.refresh(Refresh.SCREEN_OPENED)
            assertEquals("2026-11-18", fresh.state.value.value?.to)
            assertTrue(fresh.state.value.isStale)
            assertTrue(fresh.state.value.loaded)
        }

    @Test
    fun `a failure with nothing cached is an error and not loading`() =
        runTest {
            val resource =
                resource(
                    ScriptedTransport(TransportException(TransportFailure.NameNotFound, null, "UnknownHostException")),
                )
            resource.load()
            resource.refresh(Refresh.START)
            val state = resource.state.value
            assertNull(state.value)
            assertEquals(ProblemKind.NAME_NOT_FOUND, state.problem?.kind)
            assertEquals("UnknownHostException", state.problem?.hint)
            assertTrue(state.problem?.needsSettings == true)
            assertFalse(state.isLoading)
        }

    @Test
    fun `the kinds of failure are told apart`() =
        runTest {
            val cases =
                listOf<Pair<Any, ProblemKind>>(
                    TransportException(TransportFailure.NetworkBlocked) to ProblemKind.NETWORK_BLOCKED,
                    TransportException(TransportFailure.Refused) to ProblemKind.NO_ANSWER,
                    TransportException(TransportFailure.Dropped) to ProblemKind.NO_ANSWER,
                    TransportException(TransportFailure.TimedOut) to ProblemKind.NO_ANSWER,
                    TransportException(TransportFailure.SecureConnection) to ProblemKind.SECURE_CONNECTION,
                    error(500) to ProblemKind.SERVER_ERROR,
                    error(503) to ProblemKind.SERVER_ERROR,
                    error(404, "not_found") to ProblemKind.NO_SUCH_ROUTE,
                    error(422, "invalid_query") to ProblemKind.REQUEST_REFUSED,
                    HttpAnswer(200, "<html>not json</html>") to ProblemKind.NOT_THE_SERVICE,
                    HttpAnswer(302, "") to ProblemKind.NOT_THE_SERVICE,
                )
            for ((script, kind) in cases) {
                val resource = resource(ScriptedTransport(script))
                resource.refresh(Refresh.START)
                assertEquals(kind, resource.state.value.problem?.kind, "for $script")
            }
            val server = resource(ScriptedTransport(error(500))).also { it.refresh(Refresh.START) }
            assertEquals("Service error (500)", server.state.value.problem?.plain)
            assertEquals(500, server.state.value.problem?.status)
        }

    @Test
    fun `a good answer clears the problem`() =
        runTest {
            val transport = ScriptedTransport(error(503), answer)
            val resource = resource(transport)
            resource.refresh(Refresh.START)
            assertNotNull(resource.state.value.problem)
            resource.refresh(Refresh.REQUESTED)
            assertNull(resource.state.value.problem)
            assertEquals(2, transport.gets.size)
        }

    @Test
    fun `opening a screen asks at most once a minute pulling down always asks`() =
        runTest {
            val transport = ScriptedTransport(answer)
            val resource = resource(transport)
            resource.refresh(Refresh.SCREEN_OPENED)
            elapsed += 59_999
            resource.refresh(Refresh.SCREEN_OPENED)
            assertEquals(1, transport.gets.size)
            resource.refresh(Refresh.REQUESTED)
            assertEquals(2, transport.gets.size)
            elapsed += 60_000
            resource.refresh(Refresh.SCREEN_OPENED)
            assertEquals(3, transport.gets.size)
        }

    @Test
    fun `a rejected token is asked once then not again until the rider asks or the settings change`() =
        runTest {
            val transport = ScriptedTransport(error(401, "unauthorized"), answer)
            val client = ServiceClient({ config }, transport)
            val resource = resource(transport, client = client)
            resource.refresh(Refresh.START)
            assertEquals(ProblemKind.TOKEN_REJECTED, resource.state.value.problem?.kind)
            elapsed += 120_000
            resource.refresh(Refresh.SCREEN_OPENED)
            assertEquals(1, transport.gets.size, "not asked again with the same token")
            assertEquals(ProblemKind.TOKEN_REJECTED, resource.state.value.problem?.kind)
            resource.refresh(Refresh.REQUESTED)
            assertEquals(2, transport.gets.size, "the rider asked: one more try")
            assertNull(resource.state.value.problem)
        }

    @Test
    fun `a token that cannot be a header value is rejected without asking`() =
        runTest {
            config = SyncConfig(ADDRESS, "tök en")
            val transport = ScriptedTransport(answer)
            val resource = resource(transport)
            resource.refresh(Refresh.START)
            assertTrue(transport.gets.isEmpty())
            assertEquals(ProblemKind.TOKEN_REJECTED, resource.state.value.problem?.kind)
        }

    @Test
    fun `nothing set up is not an error and asks nothing`() =
        runTest {
            config = SyncConfig("", "")
            val transport = ScriptedTransport(answer)
            val resource = resource(transport)
            resource.load()
            resource.refresh(Refresh.START)
            assertTrue(transport.gets.isEmpty())
            assertNull(resource.state.value.problem)
            assertNull(resource.state.value.value)
        }

    @Test
    fun `an answer cached for another address is not shown`() =
        runTest {
            val store = MemoryTextStore()
            resource(ScriptedTransport(answer), store).refresh(Refresh.START)
            config = SyncConfig("https://other.example.org", TOKEN)
            val resource = resource(ScriptedTransport(answer), store)
            resource.load()
            assertNull(resource.state.value.value)
            resource.settingsChanged()
            assertNull(resource.state.value.value)
        }

    @Test
    fun `a damaged torn or foreign cache reads as no cache never as an exception`() =
        runTest {
            val good = MemoryTextStore().also { resource(ScriptedTransport(answer), it).refresh(Refresh.START) }.text!!
            val body = good.substringAfter('\n')
            val broken =
                listOf(
                    "",
                    "garbage",
                    good.dropLast(5),
                    good.replace("overdrive-cache 1", "overdrive-cache 2"),
                    good.replace("metrics-test", "rides"),
                    good.replace("\"from\":\"2026-11-16\"", "\"from\":\"2026-11-17\""),
                    good.substringBefore('\n') + "\n" + body.replace("2026-11-18", "2026-13-18"),
                )
            for (text in broken) {
                val resource = resource(ScriptedTransport(answer), MemoryTextStore(text))
                resource.load()
                assertNull(resource.state.value.value, "for ${text.take(40)}")
                assertTrue(resource.state.value.loaded)
            }
        }

    @Test
    fun `a disk that refuses the write costs the cache and nothing else`() =
        runTest {
            val resource = resource(ScriptedTransport(answer), MemoryTextStore().also { it.failWrites = true })
            resource.refresh(Refresh.START)
            assertEquals("2026-11-18", resource.state.value.value?.to)
            assertNull(resource.state.value.problem)
        }

    @Test
    fun `a settings change during a request is asked again once it is done`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val transport = ScriptedTransport(answer)
            transport.beforeAnswer = { if (transport.gets.size == 1) gate.await() }
            val resource = resource(transport)
            val first = launch { resource.refresh(Refresh.START) }
            yield()
            config = SyncConfig("https://new.example.org", TOKEN)
            val second = launch { resource.refresh(Refresh.SETTINGS_CHANGED) }
            yield()
            assertEquals(1, transport.gets.size, "the one under way is not doubled")
            gate.complete(Unit)
            first.join()
            second.join()
            assertEquals(2, transport.gets.size)
            assertTrue(transport.gets.last().url.startsWith("https://new.example.org/"))
        }

    @Test
    fun `the rider's request during a request waits its turn and asks once more however often it is pressed`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val transport = ScriptedTransport(answer)
            transport.beforeAnswer = { if (transport.gets.size == 1) gate.await() }
            val resource = resource(transport)
            val first = launch { resource.refresh(Refresh.START) }
            yield()
            val pressed = List(3) { launch { resource.refresh(Refresh.REQUESTED) } }
            yield()
            assertEquals(1, transport.gets.size, "nothing is asked while one is under way")
            gate.complete(Unit)
            first.join()
            pressed.forEach { it.join() }
            assertEquals(2, transport.gets.size, "one more request, not one for each press")
        }

    @Test
    fun `refreshing is on while a request is under way and off after`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val transport = ScriptedTransport(answer)
            transport.beforeAnswer = { gate.await() }
            val resource = resource(transport)
            val job = launch { resource.refresh(Refresh.START) }
            yield()
            assertTrue(resource.state.value.refreshing)
            gate.complete(Unit)
            job.join()
            assertFalse(resource.state.value.refreshing)
        }

    @Test
    fun `the state holds no token`() =
        runTest {
            val resource = resource(ScriptedTransport(TransportException(TransportFailure.Refused)))
            resource.refresh(Refresh.START)
            assertFalse(resource.state.value.toString().contains(TOKEN))
        }

    @Suppress("unused")
    private fun typeCheck(state: ResourceState<MetricsResponse>) = state.value
}
