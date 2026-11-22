package app.overdrive.core.remote

import app.overdrive.core.progress.ProgressDirection
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

/** The Progress data as the phone reads it (ADR 0043): one cached resource per range, shown at once, offline with the cause when old. */
class ProgressRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L
    private var today = "2026-11-18"

    private fun repository(
        transport: ScriptedTransport,
        stores: MutableMap<String, MemoryTextStore> = mutableMapOf(),
        cfg: () -> SyncConfig = { config },
    ) = ProgressRepository(
        ServiceClient(cfg, transport),
        cfg,
        { today },
        { name -> stores.getOrPut(name) { MemoryTextStore() } },
        { wall },
        { elapsed },
    )

    private val answer = ProgressSamples.json(ProgressSamples.heartRateFalling())

    @Test
    fun `each range is asked with its own days, ending today`() =
        runTest {
            val transport = ScriptedTransport(ok(answer))
            val repo = repository(transport)
            for (range in ProgressRange.entries) {
                repo.resource(range).load()
                repo.resource(range).refresh(Refresh.REQUESTED)
            }
            assertEquals(
                listOf(
                    "$ADDRESS/v1/progress?from=2026-05-18&to=2026-11-18",
                    "$ADDRESS/v1/progress?from=2025-11-18&to=2026-11-18",
                    "$ADDRESS/v1/progress?from=2021-11-18&to=2026-11-18",
                ),
                transport.gets.map { it.url },
            )
            assertFalse(transport.gets.toString().contains(TOKEN), "the request line holds no token")
        }

    @Test
    fun `the range follows the clock and a month that is shorter clamps`() {
        assertEquals("2026-02-28", ProgressRange.SIX_MONTHS.from("2026-08-31"))
        assertEquals("2028-02-29", ProgressRange.SIX_MONTHS.from("2028-08-31"))
        assertEquals("2024-02-28", ProgressRange.ONE_YEAR.from("2025-02-28"))
        assertEquals("2027-02-28", ProgressRange.monthsBefore("2028-02-29", 12))
        assertEquals("2026-12-15", ProgressRange.monthsBefore("2027-06-15", 6))
        assertEquals("2025-11-30", ProgressRange.monthsBefore("2026-05-31", 6))
        assertEquals("2021-11-18", ProgressRange.ALL_TIME.from("2026-11-18"))
        assertEquals("2020-02-29", ProgressRange.monthsBefore("2025-03-29", 61))
    }

    @Test
    fun `the answer is shown and kept in the cache in the services own words, one cache per range`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val repo = repository(ScriptedTransport(ok(answer)), stores)
            repo.load()
            repo.resource(ProgressRange.SIX_MONTHS).refresh(Refresh.START)
            val state = repo.resource(ProgressRange.SIX_MONTHS).state.value
            val value = assertNotNull(state.value)
            assertEquals(ProgressDirection.IMPROVING, value.aerobicEfficiency.trend.direction)
            assertEquals("Heart rate at the same power is trending down: fitter", value.headline?.verdictText)
            assertNull(state.problem)
            assertTrue(stores.getValue("progress-6m").text.orEmpty().endsWith(answer))
            // The other ranges have nothing yet: an answer for one range is not another's.
            assertNull(repo.resource(ProgressRange.ONE_YEAR).state.value.value)
            assertNull(stores["progress-1y"]?.text)
        }

    @Test
    fun `a new process shows the cached answer of a range with no network and says why it is old`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            repository(ScriptedTransport(ok(answer)), stores).also {
                it.load()
                it.resource(ProgressRange.ONE_YEAR).refresh(Refresh.START)
            }
            val restarted = repository(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), stores)
            restarted.load()
            assertNotNull(
                restarted.resource(ProgressRange.ONE_YEAR).state.value.value,
                "the cache is there before any request",
            )
            restarted.resource(ProgressRange.ONE_YEAR).refresh(Refresh.REQUESTED)
            val state = restarted.resource(ProgressRange.ONE_YEAR).state.value
            assertNotNull(state.value, "the last good answer stays")
            assertTrue(state.isStale)
            assertTrue(assertNotNull(state.problem).isOffline)
            assertNull(
                restarted.resource(ProgressRange.SIX_MONTHS).state.value.value,
                "a range never fetched has no cache",
            )
        }

    @Test
    fun `nothing cached and a failed request is an error with its cause, and a service without the route says so`() =
        runTest {
            val down = repository(ScriptedTransport(error(503)))
            down.load()
            down.resource(ProgressRange.SIX_MONTHS).refresh(Refresh.START)
            val state = down.resource(ProgressRange.SIX_MONTHS).state.value
            assertNull(state.value)
            assertEquals(ProblemKind.SERVER_ERROR, assertNotNull(state.problem).kind)
            assertFalse(state.isLoading)

            val old = repository(ScriptedTransport(error(404, "not_found")))
            old.load()
            old.resource(ProgressRange.SIX_MONTHS).refresh(Refresh.START)
            assertEquals(
                ProblemKind.NO_SUCH_ROUTE,
                assertNotNull(old.resource(ProgressRange.SIX_MONTHS).state.value.problem).kind,
            )

            val portal = repository(ScriptedTransport(ok("""{"bestPower":"<html>"}""")))
            portal.load()
            portal.resource(ProgressRange.SIX_MONTHS).refresh(Refresh.START)
            assertEquals(
                ProblemKind.NOT_THE_SERVICE,
                assertNotNull(portal.resource(ProgressRange.SIX_MONTHS).state.value.problem).kind,
            )
        }

    @Test
    fun `a cache written for another address, or damaged, is no cache`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            repository(ScriptedTransport(ok(answer)), stores).also {
                it.load()
                it.resource(ProgressRange.SIX_MONTHS).refresh(Refresh.START)
            }
            val other = SyncConfig("https://other.example.org", TOKEN)
            val elsewhere = repository(ScriptedTransport(), stores, cfg = { other })
            elsewhere.load()
            assertNull(elsewhere.resource(ProgressRange.SIX_MONTHS).state.value.value)
            stores.getValue("progress-6m").text =
                stores.getValue("progress-6m").text!!.replace("\"referencePower\":180", "\"referencePower\":190")
            val damaged = repository(ScriptedTransport(), stores)
            damaged.load()
            assertNull(
                damaged.resource(ProgressRange.SIX_MONTHS).state.value.value,
                "a body that fails its checksum reads as no cache",
            )
        }
}
