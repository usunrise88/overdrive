package app.overdrive.core.remote

import app.overdrive.core.remote.RemoteSamples.json
import app.overdrive.core.remote.RemoteSamples.metrics
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.remote.RemoteSamples.ridesPage
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three kinds of resource the screens read: the metrics per range, the rides list by cursor, one ride by its id. */
class CompanionRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private val jobs = mutableListOf<CoroutineScope>()

    /** The repository's own scope, apart from the test's (a collector that never ends would hold `runTest` for a minute). */
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()).also { jobs += it }

    @AfterTest
    fun stop() {
        jobs.forEach { it.cancel() }
    }

    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    @Test
    fun `a range is whole Monday-first weeks that end with the current one`() {
        // Wednesday 18 November 2026: its Monday is the 16th.
        assertEquals("2026-10-12", MetricsRange.SIX_WEEKS.from("2026-11-18"))
        assertEquals("2026-05-25", MetricsRange.SIX_MONTHS.from("2026-11-18"))
        assertEquals("2025-11-24", MetricsRange.ONE_YEAR.from("2026-11-18"))
        // On a Monday the week is the day itself; on a Sunday it started six days back.
        assertEquals("2026-10-12", MetricsRange.SIX_WEEKS.from("2026-11-16"))
        assertEquals("2026-10-12", MetricsRange.SIX_WEEKS.from("2026-11-22"))
        // The longest range stays within what the service serves, whatever the weekday.
        for (day in 16..22) {
            val today = "2026-11-$day"
            val days =
                app.overdrive.core.plan.PlanCalendar
                    .daysBetween(MetricsRange.ONE_YEAR.from(today), today) + 1
            assertTrue(days <= 366, "$days days for $today")
        }
    }

    @Test
    fun `each range asks for its own days, caches under its own name, and the answers can be told apart`() =
        runTest {
            val transport = ScriptedTransport(ok(json(metrics("2026-10-12", "2026-11-18"))))
            val stores = mutableMapOf<String, MemoryTextStore>()
            val repository =
                MetricsRepository(
                    ServiceClient({ config }, transport),
                    { config },
                    { "2026-11-18" },
                    { stores.getOrPut(it) { MemoryTextStore() } },
                    { wall },
                    { elapsed },
                )
            repository.load()
            repository.resource(MetricsRange.SIX_WEEKS).refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/metrics?from=2026-10-12&to=2026-11-18", transport.gets.single().url)
            assertEquals(setOf("metrics-6w", "metrics-26w", "metrics-52w"), stores.keys)
            assertNotNull(stores.getValue("metrics-6w").text)
            assertNull(stores.getValue("metrics-26w").text, "the other ranges have not been fetched")
            assertNull(repository.resource(MetricsRange.SIX_MONTHS).state.value.value)
            assertEquals(
                "2026-11-18",
                repository
                    .resource(MetricsRange.SIX_WEEKS)
                    .state.value.value
                    ?.to,
            )
        }

    @Test
    fun `the rides list pages by the service's cursor and drops older pages when the newest is fetched again`() =
        runTest {
            val first = ridesPage("2026-11-18", count = 3, hasMore = true, limit = 3)
            val second = ridesPage("2026-11-15", count = 3, hasMore = true, before = first.nextBefore, limit = 3)
            val third = ridesPage("2026-11-12", count = 2, hasMore = false, before = second.nextBefore, limit = 3)
            val transport = ScriptedTransport(ok(json(first)), ok(json(second)), ok(json(third)), ok(json(first)))
            val repository =
                RidesRepository(
                    scope(),
                    ServiceClient({ config }, transport),
                    { config },
                    MemoryTextStore(),
                    { wall },
                    { elapsed },
                    pageSize = 3,
                )
            repository.load()
            repository.refresh(Refresh.START)
            assertEquals(3, repository.view.value.list.items.size)
            assertTrue(repository.view.value.list.hasMore)
            repository.loadMore()
            assertEquals("$ADDRESS/v1/rides?before=${first.nextBefore}&limit=3", transport.gets[1].url)
            assertEquals(6, repository.view.value.list.items.size)
            repository.loadMore()
            assertEquals(8, repository.view.value.list.items.size)
            assertEquals(false, repository.view.value.list.hasMore)
            val calls = transport.gets.size
            repository.loadMore()
            assertEquals(calls, transport.gets.size, "nothing more to ask")
            wall += 1000
            repository.refresh(Refresh.REQUESTED)
            assertEquals(3, repository.view.value.list.items.size, "the newest page again: the older ones are dropped")
            assertTrue(repository.view.value.list.hasMore)
        }

    @Test
    fun `a page asked for while one is on its way is not fetched twice, and the next one is asked once it is shown`() =
        runTest {
            val first = ridesPage("2026-11-18", count = 3, hasMore = true, limit = 3)
            val second = ridesPage("2026-11-15", count = 3, hasMore = true, before = first.nextBefore, limit = 3)
            val third = ridesPage("2026-11-12", count = 2, hasMore = false, before = second.nextBefore, limit = 3)
            val transport = ScriptedTransport(ok(json(first)), ok(json(second)), ok(json(third)))
            val repository =
                RidesRepository(
                    scope(),
                    ServiceClient({ config }, transport),
                    { config },
                    null,
                    { wall },
                    { elapsed },
                    pageSize = 3,
                )
            repository.refresh(Refresh.START)
            val answer = CompletableDeferred<Unit>()
            transport.beforeAnswer = { answer.await() }
            val one = scope().launch { repository.loadMore() }
            val again = scope().launch { repository.loadMore() }
            assertTrue(repository.view.value.list.loadingMore, "the page is on its way")
            again.join()
            answer.complete(Unit)
            one.join()
            transport.beforeAnswer = {}
            assertEquals(2, transport.gets.size, "one page was asked for")
            assertEquals(6, repository.view.value.list.items.size)
            assertEquals(false, repository.view.value.list.loadingMore)
            // The page is shown: the next call goes out at once.
            repository.loadMore()
            assertEquals(8, repository.view.value.list.items.size)
            assertEquals(3, transport.gets.size)
        }

    @Test
    fun `a page fetch that is cancelled leaves the list as it was and the next ask goes out`() =
        runTest {
            val first = ridesPage("2026-11-18", count = 3, hasMore = true, limit = 3)
            val second = ridesPage("2026-11-15", count = 2, hasMore = false, before = first.nextBefore, limit = 3)
            val transport = ScriptedTransport(ok(json(first)), ok(json(second)))
            val repository =
                RidesRepository(
                    scope(),
                    ServiceClient({ config }, transport),
                    { config },
                    null,
                    { wall },
                    { elapsed },
                    pageSize = 3,
                )
            repository.refresh(Refresh.START)
            transport.beforeAnswer = { awaitCancellation() }
            val job = scope().launch { repository.loadMore() }
            assertTrue(repository.view.value.list.loadingMore, "the page is on its way")
            job.cancel()
            job.join()
            transport.beforeAnswer = {}
            assertEquals(3, repository.view.value.list.items.size)
            assertEquals(
                false,
                repository.view.value.list.loadingMore,
                "the page that was cancelled is not still coming",
            )
            repository.loadMore()
            assertEquals(5, repository.view.value.list.items.size)
        }

    @Test
    fun `an older page that cannot be fetched keeps the list and says so`() =
        runTest {
            val first = ridesPage("2026-11-18", count = 3, hasMore = true, limit = 3)
            val transport = ScriptedTransport(ok(json(first)), TransportException(TransportFailure.NoNetwork))
            val repository =
                RidesRepository(
                    scope(),
                    ServiceClient({ config }, transport),
                    { config },
                    null,
                    { wall },
                    { elapsed },
                    pageSize = 3,
                )
            repository.refresh(Refresh.START)
            repository.loadMore()
            val list = repository.view.value.list
            assertEquals(3, list.items.size)
            assertEquals(ProblemKind.NO_NETWORK, list.moreProblem?.kind)
            assertTrue(list.hasMore)
        }

    @Test
    fun `the rides list is on disk and shown after a restart with no network`() =
        runTest {
            val store = MemoryTextStore()
            val first = ridesPage("2026-11-18", count = 3, hasMore = false, limit = 3)
            RidesRepository(
                scope(),
                ServiceClient({
                    config
                }, ScriptedTransport(ok(json(first)))),
                { config },
                store,
                { wall },
                { elapsed },
                3,
            ).refresh(Refresh.START)
            val offline = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val restarted =
                RidesRepository(
                    scope(),
                    ServiceClient({ config }, offline),
                    { config },
                    store,
                    { wall },
                    { elapsed },
                    pageSize = 3,
                )
            restarted.load()
            assertEquals(3, restarted.view.value.list.items.size)
            restarted.refresh(Refresh.START)
            assertEquals(3, restarted.view.value.list.items.size)
            assertTrue(restarted.view.value.first.isStale)
        }

    @Test
    fun `a ride's detail asks for its series and keeps its own cache`() =
        runTest {
            val transport = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val stores = mutableMapOf<String, MemoryTextStore>()
            val details =
                RideDetails(
                    ServiceClient({
                        config
                    }, transport),
                    { config },
                    { stores.getOrPut(it) { MemoryTextStore() } },
                    { wall },
                    { elapsed },
                )
            val one = details.resource("ride-1")
            one.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/rides/ride-1?series=true", transport.gets.single().url)
            assertTrue(details.resource("ride-1") === one, "one resource per ride")
            assertEquals(setOf("ride-1"), stores.keys)
            assertFailsWith<IllegalArgumentException> { details.resource("../etc/passwd") }
        }

    @Test
    fun `only the most recent rides are kept in memory`() =
        runTest {
            val details =
                RideDetails(
                    ServiceClient({ config }, ScriptedTransport()),
                    { config },
                    { null },
                    { wall },
                    { elapsed },
                    keep = 2,
                )
            val a = details.resource("a")
            details.resource("b")
            details.resource("c")
            assertTrue(details.resource("a") !== a, "a was let go")
        }
}
