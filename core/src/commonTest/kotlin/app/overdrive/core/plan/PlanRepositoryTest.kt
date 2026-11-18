package app.overdrive.core.plan

import app.overdrive.core.plan.PlanSamples.json
import app.overdrive.core.plan.PlanSamples.planned
import app.overdrive.core.plan.PlanSamples.response
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planned workouts on the tablet (spec §5.7, §8.3, ADR 0017) against a scripted transport and an in-memory cache: what is
 * asked, when, what every answer comes to, and what stays when the service cannot be reached.
 */
class PlanRepositoryTest {
    private val token = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"
    private val address = "https://overdrive.example.org"
    private var config = SyncConfig(address, token)
    private var today = "2026-11-18"
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    private fun repository(
        transport: ScriptedGetTransport,
        cache: MemoryPlanCache = MemoryPlanCache(),
    ) = PlanRepository(
        config = { config },
        transport = transport,
        cache = cache,
        today = { today },
        wallMillis = { wall },
        elapsedMillis = { elapsed },
    )

    private fun fortnight(
        workouts: List<PlannedWorkout> = emptyList(),
        generatedAt: String? = "2026-11-18T06:30:00Z",
        stale: Boolean = false,
        configured: Boolean = true,
    ) = HttpAnswer(200, json(response("2026-11-18", "2026-12-01", workouts, generatedAt, stale, configured)))

    private val today1 = planned("icu-1", "2026-11-18")
    private val friday = planned("icu-2", "2026-11-20")

    private fun error(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message"}}"""

    // region what is asked

    @Test
    fun `asks for today to thirteen days on with the token in the header`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight())
            repository(transport).refresh(PlanRefresh.START)
            val request = transport.requests.single()
            assertEquals("$address/v1/workouts?from=2026-11-18&to=2026-12-01", request.url)
            assertEquals(token, request.bearerToken)
            assertEquals(PlanRepository.MAX_ANSWER_BYTES, request.maxBytes)
            assertFalse(request.toString().contains(token), "the request prints without its token")
        }

    @Test
    fun `the range crosses a month, a year and a leap day`() =
        runTest {
            for ((first, last) in listOf(
                "2026-12-25" to "2027-01-07",
                "2028-02-20" to "2028-03-04",
                "2027-02-20" to "2027-03-05",
                "2026-11-18" to "2026-12-01",
            )) {
                today = first
                val transport = ScriptedGetTransport(HttpAnswer(200, json(response(first, last))))
                repository(transport).refresh(PlanRefresh.START)
                assertEquals("$address/v1/workouts?from=$first&to=$last", transport.requests.single().url, first)
            }
        }

    @Test
    fun `a trailing slash on the address does not double`() =
        runTest {
            config = SyncConfig("$address/", token)
            val transport = ScriptedGetTransport(fortnight())
            repository(transport).refresh(PlanRefresh.START)
            assertTrue(transport.requests.single().url.startsWith("$address/v1/workouts?"))
        }

    // endregion

    // region fresh, stale, offline

    @Test
    fun `a fresh answer is the list, is kept on disk and is not stale`() =
        runTest {
            val cache = MemoryPlanCache()
            val repository = repository(ScriptedGetTransport(fortnight(listOf(today1, friday))), cache)
            repository.refresh(PlanRefresh.START)
            val state = repository.state.value
            assertTrue(state.visible && state.loaded)
            assertEquals(listOf("icu-1", "icu-2"), state.workouts.map { it.id })
            assertEquals("2026-11-18T06:30:00Z", state.generatedAt)
            assertFalse(state.stale)
            assertNull(state.problem)
            assertFalse(state.refreshing)
            val kept = checkNotNull(PlanCacheFormat.decode(checkNotNull(cache.text)))
            assertEquals(1_700_000_000_000L, kept.fetchedAtMillis)
            assertEquals(listOf("icu-1", "icu-2"), kept.response.workouts.map { it.id })
        }

    @Test
    fun `an answer the service marks stale is shown as stale`() =
        runTest {
            val repository =
                repository(
                    ScriptedGetTransport(fortnight(listOf(today1), generatedAt = "2026-11-17T20:00:00Z", stale = true)),
                )
            repository.refresh(PlanRefresh.START)
            val state = repository.state.value
            assertTrue(state.visible && state.loaded && state.stale)
            assertEquals("2026-11-17T20:00:00Z", state.generatedAt)
            assertNull(state.problem, "the tablet's own request worked")
        }

    @Test
    fun `an answer that was never refreshed has no time and is stale`() =
        runTest {
            val repository = repository(ScriptedGetTransport(fortnight(generatedAt = null, stale = true)))
            repository.refresh(PlanRefresh.START)
            val state = repository.state.value
            assertTrue(state.visible && state.loaded && state.stale)
            assertNull(state.generatedAt)
            assertTrue(state.workouts.isEmpty())
        }

    @Test
    fun `a failed request keeps the last good list and says it may be out of date`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight(listOf(today1)))
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            transport.then(TransportException(TransportFailure.Unreachable))
            repository.refresh(PlanRefresh.REQUESTED)
            val state = repository.state.value
            assertEquals(listOf("icu-1"), state.workouts.map { it.id })
            assertTrue(state.loaded && state.stale)
            assertEquals(PlanProblem.Failed("Service unreachable"), state.problem)
            assertEquals("2026-11-18T06:30:00Z", state.generatedAt, "still the time of the list it shows")
        }

    @Test
    fun `a restart with no network shows the cached list`() =
        runTest {
            val cache = MemoryPlanCache()
            repository(ScriptedGetTransport(fortnight(listOf(today1, friday))), cache).refresh(PlanRefresh.START)

            val offline = ScriptedGetTransport(TransportException(TransportFailure.Unreachable))
            val restarted = repository(offline, cache)
            assertFalse(restarted.state.value.visible, "nothing before the cache is read")
            restarted.load()
            assertEquals(
                listOf("icu-1", "icu-2"),
                restarted.state.value.workouts.map { it.id },
                "from disk, no request yet",
            )
            assertTrue(offline.requests.isEmpty())
            assertFalse(restarted.state.value.stale)

            restarted.refresh(PlanRefresh.START)
            val state = restarted.state.value
            assertEquals(listOf("icu-1", "icu-2"), state.workouts.map { it.id })
            assertTrue(state.stale)
            assertEquals(PlanProblem.Failed("Service unreachable"), state.problem)
        }

    @Test
    fun `no network and no cache says what happened and has no list`() =
        runTest {
            val repository = repository(ScriptedGetTransport(TransportException(TransportFailure.TimedOut)))
            repository.refresh(PlanRefresh.START)
            val state = repository.state.value
            assertTrue(state.visible, "the rider is told, once there is something to tell")
            assertFalse(state.loaded)
            assertTrue(state.workouts.isEmpty())
            assertEquals(PlanProblem.Failed("The service did not answer in time"), state.problem)
        }

    @Test
    fun `a later good answer replaces the list and clears the problem`() =
        runTest {
            val transport = ScriptedGetTransport(TransportException(TransportFailure.Unreachable))
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            transport.then(fortnight(listOf(friday)))
            repository.refresh(PlanRefresh.REQUESTED)
            val state = repository.state.value
            assertEquals(listOf("icu-2"), state.workouts.map { it.id })
            assertNull(state.problem)
            assertFalse(state.stale)
        }

    @Test
    fun `a cache that cannot be written costs nothing but the cache`() =
        runTest {
            val cache = MemoryPlanCache().also { it.failWrites = true }
            val repository = repository(ScriptedGetTransport(fortnight(listOf(today1))), cache)
            repository.refresh(PlanRefresh.START)
            assertEquals(listOf("icu-1"), repository.state.value.workouts.map { it.id })
            assertNull(repository.state.value.problem)
        }

    // endregion

    // region a damaged or foreign cache

    @Test
    fun `a corrupt cache is ignored, not shown and not fatal`() =
        runTest {
            val good =
                PlanCacheFormat.encode(
                    CachedPlan(
                        PlanCacheFormat.sourceOf(address),
                        wall,
                        response("2026-11-18", "2026-12-01", listOf(today1)),
                    ),
                )
            for (damaged in listOf(
                "",
                "garbage",
                good.replace("icu-1", "icu-9"),
                good.take(good.length / 2),
                good.replace("overdrive-plan 1", "overdrive-plan 2"),
            )) {
                val repository =
                    repository(
                        ScriptedGetTransport(TransportException(TransportFailure.Unreachable)),
                        MemoryPlanCache(damaged),
                    )
                repository.load()
                assertFalse(repository.state.value.visible, damaged.take(20))
                assertTrue(repository.state.value.workouts.isEmpty())
            }
        }

    @Test
    fun `a cache from another address is not this rider's plan`() =
        runTest {
            val cache = MemoryPlanCache()
            repository(ScriptedGetTransport(fortnight(listOf(today1))), cache).refresh(PlanRefresh.START)

            config = SyncConfig("https://other.example.org", token)
            val repository = repository(ScriptedGetTransport(TransportException(TransportFailure.Unreachable)), cache)
            repository.load()
            assertTrue(repository.state.value.workouts.isEmpty())
            assertFalse(repository.state.value.visible)
        }

    // endregion

    // region not configured

    @Test
    fun `without an address or a token nothing is asked and nothing is shown`() =
        runTest {
            for (missing in listOf(SyncConfig("", token), SyncConfig(address, ""), SyncConfig("", ""))) {
                config = missing
                val transport = ScriptedGetTransport(fortnight(listOf(today1)))
                val repository = repository(transport)
                repository.load()
                repository.refresh(PlanRefresh.START)
                repository.refresh(PlanRefresh.REQUESTED)
                assertTrue(transport.requests.isEmpty())
                assertEquals(PlanState(), repository.state.value, "no error either")
            }
        }

    @Test
    fun `a service with no plan source has no section and no error`() =
        runTest {
            val repository = repository(ScriptedGetTransport(fortnight(configured = false, generatedAt = null)))
            repository.refresh(PlanRefresh.START)
            assertEquals(PlanState(), repository.state.value)
        }

    @Test
    fun `clearing the address or the token hides a list that is on screen`() =
        runTest {
            val repository = repository(ScriptedGetTransport(fortnight(listOf(today1))))
            repository.refresh(PlanRefresh.START)
            assertTrue(repository.state.value.visible)
            config = SyncConfig("", "")
            repository.settingsChanged()
            assertEquals(PlanState(), repository.state.value)
            config = SyncConfig(address, token)
            repository.settingsChanged()
            assertEquals(
                listOf("icu-1"),
                repository.state.value.workouts.map { it.id },
                "the cache of that address is still good",
            )
        }

    // endregion

    // region 401 and other statuses

    @Test
    fun `a rejected token is said in the sync settings' words, keeps the list and is not asked again`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight(listOf(today1)))
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            transport.then(HttpAnswer(401, error("unauthorized", "Missing or invalid bearer token")))
            repository.refresh(PlanRefresh.REQUESTED)
            assertEquals(PlanProblem.TokenRejected, repository.state.value.problem)
            assertEquals(listOf("icu-1"), repository.state.value.workouts.map { it.id })
            assertTrue(repository.state.value.stale)
            assertEquals(2, transport.requests.size)

            elapsed += 10 * PlanRepository.MIN_GAP_MILLIS
            repository.refresh(PlanRefresh.START)
            repository.refresh(PlanRefresh.SCREEN_OPENED)
            repository.refresh(PlanRefresh.SETTINGS_CHANGED)
            assertEquals(2, transport.requests.size, "the same address and token are not tried again by themselves")
            assertEquals(PlanProblem.TokenRejected, repository.state.value.problem)

            transport.then(fortnight(listOf(friday)))
            repository.refresh(PlanRefresh.REQUESTED)
            assertEquals(3, transport.requests.size, "the rider asks, so it asks")
            assertNull(repository.state.value.problem)
        }

    @Test
    fun `a changed token is tried`() =
        runTest {
            val transport = ScriptedGetTransport(HttpAnswer(401, error("unauthorized", "no")))
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            assertEquals(PlanProblem.TokenRejected, repository.state.value.problem)
            config = SyncConfig(address, "tok-another-0123456789abcdef0123456789")
            transport.then(fortnight(listOf(today1)))
            repository.settingsChanged()
            repository.refresh(PlanRefresh.SETTINGS_CHANGED)
            assertEquals(2, transport.requests.size)
            assertNull(repository.state.value.problem)
            assertEquals(listOf("icu-1"), repository.state.value.workouts.map { it.id })
        }

    @Test
    fun `a token that cannot be a header is rejected without a request`() =
        runTest {
            config = SyncConfig(address, "tok with a space")
            val transport = ScriptedGetTransport(fortnight())
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            assertTrue(transport.requests.isEmpty())
            assertEquals(PlanProblem.TokenRejected, repository.state.value.problem)
        }

    @Test
    fun `every other answer is a plain problem and changes nothing on the list`() =
        runTest {
            val cases =
                listOf(
                    HttpAnswer(503, "") to "Service error (503)",
                    HttpAnswer(500, error("internal_error", "boom")) to "Service error (500)",
                    HttpAnswer(408, "") to "The service timed out",
                    HttpAnswer(429, "") to "The service is busy",
                    HttpAnswer(302, "") to "The address redirects elsewhere",
                    HttpAnswer(404, error("not_found", "No such route")) to "The service has no planned workouts",
                    HttpAnswer(404, "<html>nope</html>") to "The service refused the request (404)",
                    HttpAnswer(400, error("invalid_range", "from is after to")) to
                        "The service refused the request (400)",
                    HttpAnswer(204, "") to "That is not the Overdrive service",
                    HttpAnswer(200, "<html>captive portal</html>") to "That is not the Overdrive service",
                    HttpAnswer(200, """{"workouts":[]}""") to "That is not the Overdrive service",
                )
            for ((answer, reason) in cases) {
                val transport = ScriptedGetTransport(fortnight(listOf(today1)))
                val repository = repository(transport)
                repository.refresh(PlanRefresh.START)
                transport.then(answer)
                repository.refresh(PlanRefresh.REQUESTED)
                assertEquals(
                    PlanProblem.Failed(reason),
                    repository.state.value.problem,
                    "${answer.status} ${answer.body.take(20)}",
                )
                assertEquals(listOf("icu-1"), repository.state.value.workouts.map { it.id })
                assertTrue(repository.state.value.stale)
            }
        }

    @Test
    fun `a failed transport names the kind of failure and never the request`() =
        runTest {
            for ((failure, reason) in listOf(
                TransportFailure.Unreachable to "Service unreachable",
                TransportFailure.TimedOut to "The service did not answer in time",
                TransportFailure.SecureConnection to "Secure connection failed",
                TransportFailure.Other to "Could not talk to the service",
            )) {
                val repository = repository(ScriptedGetTransport(TransportException(failure)))
                repository.refresh(PlanRefresh.START)
                assertEquals(PlanProblem.Failed(reason), repository.state.value.problem)
                assertFalse(repository.state.value.toString().contains(token))
            }
        }

    // endregion

    // region when

    @Test
    fun `opening the screen asks at most once a minute, start and the rider always ask`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight())
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            assertEquals(1, transport.requests.size)

            elapsed += PlanRepository.MIN_GAP_MILLIS - 1
            repository.refresh(PlanRefresh.SCREEN_OPENED)
            assertEquals(1, transport.requests.size, "a second short of a minute")

            elapsed += 1
            repository.refresh(PlanRefresh.SCREEN_OPENED)
            assertEquals(2, transport.requests.size, "a minute")

            repository.refresh(PlanRefresh.SCREEN_OPENED)
            assertEquals(2, transport.requests.size, "the minute starts again")

            repository.refresh(PlanRefresh.REQUESTED)
            assertEquals(3, transport.requests.size, "the rider's own request is never held back")
            repository.refresh(PlanRefresh.START)
            assertEquals(4, transport.requests.size)
        }

    @Test
    fun `a failed request also counts for the minute`() =
        runTest {
            val transport = ScriptedGetTransport(TransportException(TransportFailure.Unreachable))
            val repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            repository.refresh(PlanRefresh.SCREEN_OPENED)
            assertEquals(1, transport.requests.size, "an offline tablet does not retry at every glance")
        }

    @Test
    fun `one request at a time, and a settings change during one asks again afterwards`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight(listOf(today1)))
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            transport.beforeAnswer = {
                started.complete(Unit)
                release.await()
            }
            val repository = repository(transport)
            val first = launch { repository.refresh(PlanRefresh.START) }
            started.await()
            assertTrue(repository.state.value.refreshing)

            repository.refresh(PlanRefresh.REQUESTED)
            repository.refresh(PlanRefresh.SCREEN_OPENED)
            assertEquals(1, transport.requests.size, "dropped: the one under way answers for them")

            config = SyncConfig(address, "tok-another-0123456789abcdef0123456789")
            repository.refresh(PlanRefresh.SETTINGS_CHANGED)
            release.complete(Unit)
            first.join()
            assertEquals(2, transport.requests.size, "the change was made with the old token, so once more")
            assertEquals("tok-another-0123456789abcdef0123456789", transport.requests.last().bearerToken)
            assertFalse(repository.state.value.refreshing)
        }

    @Test
    fun `refreshing is true while the request is out and false after`() =
        runTest {
            val transport = ScriptedGetTransport(fortnight())
            val seen = mutableListOf<Boolean>()
            lateinit var repository: PlanRepository
            transport.beforeAnswer = { seen += repository.state.value.refreshing }
            repository = repository(transport)
            repository.refresh(PlanRefresh.START)
            assertEquals(listOf(true), seen)
            assertFalse(repository.state.value.refreshing)
        }

    // endregion
}
