package app.overdrive.core.remote

import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The repeated sessions as the phone reads them (ADR 0028): the list and one session, each a cached resource. */
class RepeatsRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    private fun list(
        transport: ScriptedTransport,
        store: MemoryTextStore? = MemoryTextStore(),
        cfg: () -> SyncConfig = { config },
    ) = repeatsResource(ServiceClient(cfg, transport), cfg, store, { wall }, { elapsed })

    private val listBody =
        RepeatSamples.json(
            RepeatSamples.list(
                Triple(RepeatSamples.NAME, RepeatSamples.HASH, listOf(150.0, 148.0, 146.0)),
                Triple("Threshold 4x8", RepeatSamples.OTHER_HASH, listOf(160.0, 161.0)),
            ),
        )

    @Test
    fun `the list is asked from the repeats route shown and kept in the cache in the services own words`() =
        runTest {
            val store = MemoryTextStore()
            val transport = ScriptedTransport(ok(listBody))
            val resource = list(transport, store)
            resource.load()
            resource.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/repeats", transport.gets.single().url)
            assertFalse(transport.gets.single().toString().contains(TOKEN), "the request line holds no token")
            val state = resource.state.value
            val value = assertNotNull(state.value)
            assertEquals(2, value.repeats.size)
            assertEquals(RepeatSamples.NAME, value.repeats.first().name)
            assertNull(state.problem)
            assertTrue(
                store.text.orEmpty().endsWith(listBody),
                "the cache holds the answer exactly as the service wrote it",
            )
        }

    @Test
    fun `a new process shows the cached list with no network and says why it is old`() =
        runTest {
            val store = MemoryTextStore()
            list(ScriptedTransport(ok(listBody)), store).also {
                it.load()
                it.refresh(Refresh.START)
            }
            val offline = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val restarted = list(offline, store)
            restarted.load()
            assertEquals(
                2,
                assertNotNull(restarted.state.value.value).repeats.size,
                "the cache is there before any request",
            )
            restarted.refresh(Refresh.REQUESTED)
            val state = restarted.state.value
            assertEquals(2, assertNotNull(state.value).repeats.size, "the last good answer stays")
            assertTrue(state.isStale)
            assertTrue(assertNotNull(state.problem).isOffline)
        }

    @Test
    fun `nothing cached and a failed request is an error with its cause not an empty list`() =
        runTest {
            val resource = list(ScriptedTransport(error(503)))
            resource.load()
            resource.refresh(Refresh.START)
            val state = resource.state.value
            assertNull(state.value)
            assertEquals(ProblemKind.SERVER_ERROR, assertNotNull(state.problem).kind)
            assertFalse(state.isLoading)
        }

    @Test
    fun `a service without the route says so and an answer that is not the contract's is not the service`() =
        runTest {
            val missing = list(ScriptedTransport(error(404, "not_found")))
            missing.load()
            missing.refresh(Refresh.START)
            assertEquals(ProblemKind.NO_SUCH_ROUTE, assertNotNull(missing.state.value.problem).kind)

            val portal = list(ScriptedTransport(ok("""{"repeats":"<html>"}""")))
            portal.load()
            portal.refresh(Refresh.START)
            assertEquals(ProblemKind.NOT_THE_SERVICE, assertNotNull(portal.state.value.problem).kind)
            assertNull(portal.state.value.value)
        }

    @Test
    fun `a cache written for another address or damaged is no cache`() =
        runTest {
            val store = MemoryTextStore()
            list(ScriptedTransport(ok(listBody)), store).also {
                it.load()
                it.refresh(Refresh.START)
            }
            val other = SyncConfig("https://other.example.org", TOKEN)
            val elsewhere = list(ScriptedTransport(), store, cfg = { other })
            elsewhere.load()
            assertNull(elsewhere.state.value.value, "an answer of another service is not this rider's")
            store.text = store.text!!.replace("\"attempts\":3", "\"attempts\":9")
            val damaged = list(ScriptedTransport(), store)
            damaged.load()
            assertNull(damaged.state.value.value, "a body that fails its checksum reads as no cache")
        }

    // region one session

    private fun details(
        transport: ScriptedTransport,
        stores: MutableMap<String, MemoryTextStore> = mutableMapOf(),
        keep: Int = 6,
    ) = RepeatDetails(
        ServiceClient({ config }, transport),
        { config },
        { hash -> stores.getOrPut(hash) { MemoryTextStore() } },
        { wall },
        { elapsed },
        keep,
    )

    @Test
    fun `a session is asked by its hash once a resource each and cached under that hash`() =
        runTest {
            val body = RepeatSamples.json(RepeatSamples.detail(listOf(150.0, 148.0, 146.0)))
            val transport = ScriptedTransport(ok(body))
            val stores = mutableMapOf<String, MemoryTextStore>()
            val repeats = details(transport, stores)
            val resource = repeats.resource(RepeatSamples.HASH)
            assertSame(resource, repeats.resource(RepeatSamples.HASH))
            resource.refresh(Refresh.SCREEN_OPENED)
            assertEquals("$ADDRESS/v1/repeats/${RepeatSamples.HASH}", transport.gets.single().url)
            val detail = assertNotNull(resource.state.value.value)
            assertEquals(3, detail.attempts.size)
            assertTrue(stores.getValue(RepeatSamples.HASH).text.orEmpty().endsWith(body))
        }

    @Test
    fun `an opened session opens offline after a restart with the attempts it had`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val body = RepeatSamples.json(RepeatSamples.detail(listOf(150.0, 148.0, 146.0, 144.0)))
            details(
                ScriptedTransport(ok(body)),
                stores,
            ).resource(RepeatSamples.HASH).also { it.refresh(Refresh.REQUESTED) }
            val offline = ScriptedTransport(TransportException(TransportFailure.Unreachable))
            val resource = details(offline, stores).resource(RepeatSamples.HASH)
            resource.refresh(Refresh.REQUESTED)
            val state = resource.state.value
            assertEquals(4, assertNotNull(state.value).attempts.size)
            assertTrue(state.isStale)
        }

    @Test
    fun `a session that is gone from the service answers not found and keeps what it had`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val body = RepeatSamples.json(RepeatSamples.detail(listOf(150.0, 148.0)))
            val transport = ScriptedTransport(ok(body), error(404, "not_found"))
            val resource = details(transport, stores).resource(RepeatSamples.HASH)
            resource.refresh(Refresh.REQUESTED)
            resource.refresh(Refresh.REQUESTED)
            val state = resource.state.value
            assertNotNull(state.value)
            assertEquals(ProblemKind.NO_SUCH_ROUTE, assertNotNull(state.problem).kind)
        }

    @Test
    fun `only a hash is accepted and only the most recent resources are kept in memory`() {
        val repeats = details(ScriptedTransport(ok("{}")), keep = 2)
        for (bad in listOf(
            "",
            "B1ADD004E12D9617",
            "b1add004e12d961",
            "b1add004e12d96177",
            "../v1/rides",
            "b1add004e12d961g",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { repeats.resource(bad) }
        }
        val a = repeats.resource("aaaaaaaaaaaaaaaa")
        repeats.resource("bbbbbbbbbbbbbbbb")
        repeats.resource("cccccccccccccccc")
        assertTrue(repeats.resource("aaaaaaaaaaaaaaaa") !== a, "the oldest was dropped and is made again")
    }
}
