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

/** The Power skills radar and the weekly Effort chart as the phone reads them (docs/adr/0042): cached resources, offline-safe. */
class ChartsRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L
    private var today = "2026-11-18"

    // region power skills

    private fun skills(
        transport: ScriptedTransport,
        stores: MutableMap<String, MemoryTextStore> = mutableMapOf(),
        cfg: () -> SyncConfig = { config },
        keep: Int = 4,
    ) = PowerSkillsResources(
        ServiceClient(cfg, transport),
        cfg,
        { today },
        { name -> stores.getOrPut(name) { MemoryTextStore() } },
        { wall },
        { elapsed },
        keep,
    )

    private val eightWeeks = PowerSkillsWindow.EightWeeks
    private val body = ChartSamples.json(ChartSamples.skills("2026-09-24", "2026-11-18"))

    @Test
    fun `the three windows are three routes over the phone's local days, and a window is a file-name-safe word`() {
        assertEquals("2026-09-24" to "2026-11-18", PowerSkillsWindow.EightWeeks.days("2026-11-18"))
        assertEquals("2023-11-19" to "2026-11-18", PowerSkillsWindow.AllTime.days("2026-11-18"))
        assertEquals(
            "2026-11-02" to "2026-11-15",
            PowerSkillsWindow.Custom("2026-11-02", "2026-11-15").days("2026-11-18"),
        )
        assertEquals(
            listOf("8w", "all", "c-2026-11-02-2026-11-15"),
            listOf(
                eightWeeks.key,
                PowerSkillsWindow.AllTime.key,
                PowerSkillsWindow.Custom("2026-11-02", "2026-11-15").key,
            ),
        )
    }

    @Test
    fun `a custom range is real days, not reversed and at most three years`() {
        PowerSkillsWindow.Custom("2026-11-15", "2026-11-15")
        PowerSkillsWindow.Custom("2023-11-19", "2026-11-18")
        assertFailsWith<IllegalArgumentException> { PowerSkillsWindow.Custom("2026-11-16", "2026-11-15") }
        assertFailsWith<IllegalArgumentException> { PowerSkillsWindow.Custom("2023-11-18", "2026-11-18") }
        assertFailsWith<IllegalArgumentException> { PowerSkillsWindow.Custom("2026-02-30", "2026-03-02") }
        assertFailsWith<IllegalArgumentException> { PowerSkillsWindow.Custom("../v1", "2026-03-02") }
    }

    @Test
    fun `the eight weeks are asked from the route, shown, and kept in the cache as the service wrote it`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val transport = ScriptedTransport(ok(body))
            val resources = skills(transport, stores)
            resources.load()
            val resource = resources.resource(eightWeeks)
            resource.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/power-skills?from=2026-09-24&to=2026-11-18", transport.gets.single().url)
            assertFalse(transport.gets.single().toString().contains(TOKEN), "the request line holds no token")
            val state = resource.state.value
            assertEquals(3, assertNotNull(state.value).window.rideCount)
            assertNull(state.problem)
            assertTrue(stores.getValue("power-skills-8w").text.orEmpty().endsWith(body))
        }

    @Test
    fun `each window has its own cache, so a window fetched before is there at once, and offline after a restart`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val all = ChartSamples.json(ChartSamples.skills("2023-11-19", "2026-11-18", rideCount = 120))
            val custom = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
            val customBody =
                ChartSamples.json(
                    ChartSamples.skills("2026-11-02", "2026-11-15", withWeight = false, rideCount = 2),
                )
            val first = skills(ScriptedTransport(ok(body), ok(all), ok(customBody)), stores)
            first.resource(eightWeeks).refresh(Refresh.REQUESTED)
            first.resource(PowerSkillsWindow.AllTime).refresh(Refresh.REQUESTED)
            first.resource(custom).refresh(Refresh.REQUESTED)

            val offline = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val restarted = skills(offline, stores)
            restarted.load()
            assertEquals(3, assertNotNull(restarted.resource(eightWeeks).state.value.value).window.rideCount)
            assertEquals(
                120,
                assertNotNull(restarted.resource(PowerSkillsWindow.AllTime).state.value.value).window.rideCount,
            )
            // A custom window's cache is read when it is first asked for, with its first refresh.
            restarted.resource(custom).refresh(Refresh.REQUESTED)
            val state = restarted.resource(custom).state.value
            assertEquals(2, assertNotNull(state.value).window.rideCount)
            assertNull(state.value!!.riderWeight, "a window fetched without a weight is shown without one")
            assertTrue(state.isStale)
            assertTrue(assertNotNull(state.problem).isOffline)
        }

    @Test
    fun `a window never fetched shows nothing, not another window's answer`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            val resources = skills(ScriptedTransport(ok(body)), stores)
            resources.resource(eightWeeks).refresh(Refresh.REQUESTED)
            val other = skills(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), stores)
            other.load()
            val state = other.resource(PowerSkillsWindow.AllTime).state.value
            assertNull(state.value)
        }

    @Test
    fun `a custom window is one resource, made once, and only the most recent are kept in memory`() {
        val resources = skills(ScriptedTransport(ok(body)), keep = 2)
        val a = PowerSkillsWindow.Custom("2026-11-01", "2026-11-02")
        val first = resources.resource(a)
        assertSame(first, resources.resource(PowerSkillsWindow.Custom("2026-11-01", "2026-11-02")))
        resources.resource(PowerSkillsWindow.Custom("2026-11-03", "2026-11-04"))
        resources.resource(PowerSkillsWindow.Custom("2026-11-05", "2026-11-06"))
        assertTrue(resources.resource(a) !== first, "the oldest was dropped and is made again")
        assertSame(
            resources.resource(eightWeeks),
            resources.resource(eightWeeks),
            "the standing windows are never dropped",
        )
    }

    @Test
    fun `the window follows the phone's clock at every request`() =
        runTest {
            val transport = ScriptedTransport(ok(body))
            val resource = skills(transport).resource(eightWeeks)
            resource.refresh(Refresh.REQUESTED)
            today = "2026-11-19"
            elapsed += 120_000
            resource.refresh(Refresh.REQUESTED)
            assertEquals(
                listOf(
                    "$ADDRESS/v1/power-skills?from=2026-09-24&to=2026-11-18",
                    "$ADDRESS/v1/power-skills?from=2026-09-25&to=2026-11-19",
                ),
                transport.gets.map { it.url },
            )
        }

    @Test
    fun `a service without the route says so, and an answer that is not the contract's is not the service`() =
        runTest {
            val missing = skills(ScriptedTransport(error(404, "not_found"))).resource(eightWeeks)
            missing.refresh(Refresh.START)
            assertEquals(ProblemKind.NO_SUCH_ROUTE, assertNotNull(missing.state.value.problem).kind)
            assertNull(missing.state.value.value)

            val portal = skills(ScriptedTransport(ok("""{"window":"<html>"}"""))).resource(eightWeeks)
            portal.refresh(Refresh.START)
            assertEquals(ProblemKind.NOT_THE_SERVICE, assertNotNull(portal.state.value.problem).kind)
            assertNull(portal.state.value.value)
        }

    @Test
    fun `a cache written for another address, or damaged, is no cache`() =
        runTest {
            val stores = mutableMapOf<String, MemoryTextStore>()
            skills(ScriptedTransport(ok(body)), stores).resource(eightWeeks).refresh(Refresh.REQUESTED)
            val other = SyncConfig("https://other.example.org", TOKEN)
            val elsewhere = skills(ScriptedTransport(), stores, cfg = { other })
            elsewhere.load()
            assertNull(
                elsewhere.resource(eightWeeks).state.value.value,
                "an answer of another service is not this rider's",
            )
            stores.getValue("power-skills-8w").text =
                stores.getValue("power-skills-8w").text!!.replace("\"rideCount\":3", "\"rideCount\":9")
            val damaged = skills(ScriptedTransport(), stores)
            damaged.load()
            assertNull(
                damaged.resource(eightWeeks).state.value.value,
                "a body that fails its checksum reads as no cache",
            )
        }

    // endregion

    // region effort

    private fun effort(
        transport: ScriptedTransport,
        store: MemoryTextStore? = MemoryTextStore(),
        weeks: Int = 52,
    ) = effortResource(ServiceClient({ config }, transport), { config }, store, { wall }, { elapsed }, weeks)

    private val effortBody = ChartSamples.json(ChartSamples.effort("2026-11-16"))

    @Test
    fun `the effort is asked for a year of weeks, shown, and kept in the cache in the services own words`() =
        runTest {
            val store = MemoryTextStore()
            val transport = ScriptedTransport(ok(effortBody))
            val resource = effort(transport, store)
            resource.load()
            resource.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/effort?weeks=52", transport.gets.single().url)
            val state = resource.state.value
            val value = assertNotNull(state.value)
            assertEquals(6, value.weeks.size)
            assertEquals("2026-11-16", value.currentWeek.weekStart)
            assertNull(state.problem)
            assertTrue(store.text.orEmpty().endsWith(effortBody))
        }

    @Test
    fun `a new process shows the cached effort with no network and says why it is old`() =
        runTest {
            val store = MemoryTextStore()
            effort(ScriptedTransport(ok(effortBody)), store).also {
                it.load()
                it.refresh(Refresh.START)
            }
            val restarted = effort(ScriptedTransport(TransportException(TransportFailure.NoNetwork)), store)
            restarted.load()
            assertEquals(
                6,
                assertNotNull(restarted.state.value.value).weeks.size,
                "the cache is there before any request",
            )
            restarted.refresh(Refresh.REQUESTED)
            val state = restarted.state.value
            assertNotNull(state.value, "the last good answer stays")
            assertTrue(state.isStale)
            assertTrue(assertNotNull(state.problem).isOffline)
        }

    @Test
    fun `a number of weeks that the service does not serve is refused before any request`() {
        assertFailsWith<IllegalArgumentException> { effort(ScriptedTransport(), weeks = 0) }
        assertFailsWith<IllegalArgumentException> { effort(ScriptedTransport(), weeks = 53) }
        effort(ScriptedTransport(), weeks = 12)
    }

    @Test
    fun `a service without the route, a portal's page and a damaged cache are each told apart`() =
        runTest {
            val missing = effort(ScriptedTransport(error(404, "not_found")))
            missing.refresh(Refresh.START)
            assertEquals(ProblemKind.NO_SUCH_ROUTE, assertNotNull(missing.state.value.problem).kind)

            val portal = effort(ScriptedTransport(ok("""{"weeks":"<html>"}""")))
            portal.refresh(Refresh.START)
            assertEquals(ProblemKind.NOT_THE_SERVICE, assertNotNull(portal.state.value.problem).kind)
            assertNull(portal.state.value.value)

            val store = MemoryTextStore()
            effort(ScriptedTransport(ok(effortBody)), store).also { it.refresh(Refresh.REQUESTED) }
            store.text = store.text!!.replace("\"weeksLogged\":5", "\"weeksLogged\":4")
            val damaged = effort(ScriptedTransport(), store)
            damaged.load()
            assertNull(damaged.state.value.value)
        }

    // endregion
}
