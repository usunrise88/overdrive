package app.overdrive.core.remote

import app.overdrive.core.notebook.NotebookApiJson
import app.overdrive.core.notebook.NotebookLimits
import app.overdrive.core.notebook.NotebookResponse
import app.overdrive.core.notebook.NotebookSection
import app.overdrive.core.notebook.NotebookSectionView
import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach notebook as the phone reads it (spec §11.6, ADR 0035): one cached resource, read-only, offline-safe. */
class NotebookRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    private fun notebook(
        transport: ScriptedTransport,
        store: MemoryTextStore? = MemoryTextStore(),
        cfg: () -> SyncConfig = { config },
    ) = notebookResource(ServiceClient(cfg, transport), cfg, store, { wall }, { elapsed })

    private val body = NotebookSamples.json(NotebookSamples.filled())

    @Test
    fun `the notebook is asked from its route shown and kept in the cache in the services own words`() =
        runTest {
            val store = MemoryTextStore()
            val transport = ScriptedTransport(ok(body))
            val resource = notebook(transport, store)
            resource.load()
            resource.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/notebook", transport.gets.single().url)
            assertFalse(transport.gets.single().toString().contains(TOKEN), "the request line holds no token")
            val state = resource.state.value
            val value = assertNotNull(state.value)
            assertEquals(NotebookSection.entries.size, value.sections.size)
            assertEquals(NotebookSection.METHODOLOGY, value.sections[4].key)
            assertNull(state.problem)
            assertTrue(
                store.text.orEmpty().endsWith(body),
                "the cache holds the answer exactly as the service wrote it",
            )
        }

    @Test
    fun `a new process shows the cached notebook with no network and says why it is old`() =
        runTest {
            val store = MemoryTextStore()
            notebook(ScriptedTransport(ok(body)), store).also {
                it.load()
                it.refresh(Refresh.START)
            }
            val restarted = notebook(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), store)
            restarted.load()
            assertEquals(
                NotebookSection.entries.size,
                assertNotNull(restarted.state.value.value).sections.size,
                "the cache is there before any request",
            )
            restarted.refresh(Refresh.REQUESTED)
            val state = restarted.state.value
            assertNotNull(state.value, "the last good answer stays")
            assertTrue(state.isStale)
            assertTrue(assertNotNull(state.problem).isOffline)
        }

    @Test
    fun `an empty notebook is an answer not an error`() =
        runTest {
            val resource = notebook(ScriptedTransport(ok(NotebookSamples.json(NotebookSamples.empty()))))
            resource.load()
            resource.refresh(Refresh.START)
            val state = resource.state.value
            assertTrue(assertNotNull(state.value).isEmpty)
            assertNull(state.problem)
        }

    @Test
    fun `a service without the route says so and an answer that is not the contract's is not the service`() =
        runTest {
            val missing = notebook(ScriptedTransport(error(404, "not_found")))
            missing.load()
            missing.refresh(Refresh.START)
            assertEquals(ProblemKind.NO_SUCH_ROUTE, assertNotNull(missing.state.value.problem).kind)
            assertNull(missing.state.value.value)

            val portal = notebook(ScriptedTransport(ok("""{"scope":"coach","sections":"<html>"}""")))
            portal.load()
            portal.refresh(Refresh.START)
            assertEquals(ProblemKind.NOT_THE_SERVICE, assertNotNull(portal.state.value.problem).kind)
            assertNull(portal.state.value.value)
        }

    @Test
    fun `a cache written for another address is no cache and a damaged one reads as none`() =
        runTest {
            val store = MemoryTextStore()
            notebook(ScriptedTransport(ok(body)), store).also {
                it.load()
                it.refresh(Refresh.START)
            }
            val other = SyncConfig("https://other.example.org", TOKEN)
            val elsewhere = notebook(ScriptedTransport(), store, cfg = { other })
            elsewhere.load()
            assertNull(elsewhere.state.value.value, "an answer of another service is not this rider's")
            store.text = store.text!!.replace("\"version\":3", "\"version\":9")
            val damaged = notebook(ScriptedTransport(), store)
            damaged.load()
            assertNull(damaged.state.value.value, "a body that fails its checksum reads as no cache")
        }

    @Test
    fun `a failed write of the cache does not lose the answer on screen`() =
        runTest {
            val store = MemoryTextStore().also { it.failWrites = true }
            val resource = notebook(ScriptedTransport(ok(body)), store)
            resource.load()
            resource.refresh(Refresh.START)
            assertNotNull(resource.state.value.value)
            assertNull(resource.state.value.problem)
        }

    @Test
    fun `the decoded answer is the contract's model`() {
        val decoded: NotebookResponse = NotebookApiJson.decodeNotebook(body)
        assertEquals(NotebookSamples.filled(), decoded)
    }
}

/** Synthetic notebooks, valid by the contract's own rules (the models check them when they are built). Nothing here is a real rider's. */
internal object NotebookSamples {
    private val texts =
        listOf(
            "Works full-time, rides four mornings a week. Indoor only until March.",
            "Two quality sessions a week, a rest day after each.",
            "Three hard days in a row: fatigue shows by day three.",
            "Left knee niggle after long standing efforts.",
            "Base block, then sweet spot progression. Review on 2027-01-10.",
            "Does the rider have a heart-rate strap on every ride?",
            "2026-11-10 Moved the threshold session to Friday.\n2026-11-16 FTP lowered to 240 W after the test.",
        )

    fun filled(): NotebookResponse =
        notebook(
            NotebookSection.entries.mapIndexed { index, key ->
                section(key, texts[index], version = index + 1)
            },
        )

    fun empty(): NotebookResponse = notebook(NotebookSection.entries.map { key -> section(key, "", version = 0) })

    fun json(response: NotebookResponse): String = NotebookApiJson.encodeNotebook(response)

    private fun notebook(sections: List<NotebookSectionView>) =
        NotebookResponse(
            scope = "coach",
            generatedAt = "2026-11-19T07:30:00Z",
            limits = NotebookLimits(maxChars = 12_000, logMaxChars = 3_000),
            totalChars = sections.sumOf { it.text.length },
            sections = sections,
        )

    private fun section(
        key: NotebookSection,
        text: String,
        version: Int,
    ) = NotebookSectionView(
        key = key,
        title = key.title,
        text = text,
        version = version,
        updatedAt = if (version == 0) null else "2026-11-${10 + version}T08:00:00Z",
        reason = if (version == 0) null else "Synthetic reason.",
    )
}
