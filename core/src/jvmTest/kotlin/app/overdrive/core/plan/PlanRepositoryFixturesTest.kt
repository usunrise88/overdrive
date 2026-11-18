package app.overdrive.core.plan

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.workout.FreeStep
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The repository against the answers of `contracts/fixtures/plan/workouts-response`, the same files the service's tests and
 * `PlanApiContractTest` use: every valid answer is taken in and shown as its flags say, every invalid one is "not the service"
 * and leaves the cache as it was. Plus the calendar arithmetic against `java.time`.
 */
class PlanRepositoryFixturesTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(kind: String): List<File> =
        File(contracts, "fixtures/plan/workouts-response/$kind")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    private fun fixture(name: String) = File(contracts, "fixtures/plan/workouts-response/valid/$name.json").readText()

    private val config = SyncConfig("https://overdrive.example.org", "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7")

    private fun repository(
        answer: String,
        cache: MemoryPlanCache = MemoryPlanCache(),
        today: String = "2026-11-18",
    ) = PlanRepository(
        config = { config },
        transport = ScriptedGetTransport(HttpAnswer(200, answer)),
        cache = cache,
        today = { today },
        wallMillis = { 1_700_000_000_000L },
        elapsedMillis = { 1L },
    )

    @Test
    fun `every valid answer is taken in and shown as its flags say`() =
        runTest {
            for (file in fixtures("valid")) {
                val answer = PlanApiJson.decodeWorkoutsResponse(file.readText())
                val cache = MemoryPlanCache()
                val repository = repository(file.readText(), cache)
                repository.refresh(PlanRefresh.START)
                val state = repository.state.value
                assertNull(state.problem, file.name)
                if (answer.configured) {
                    assertTrue(state.visible && state.loaded, file.name)
                    assertEquals(answer.workouts, state.workouts, file.name)
                    assertEquals(answer.generatedAt, state.generatedAt, file.name)
                    assertEquals(answer.stale, state.stale, file.name)
                } else {
                    assertEquals(PlanState(), state, "${file.name}: not configured shows nothing")
                }
                // Whatever the service said is what the cache keeps, and a restart reads it back the same.
                val restarted = repository(file.readText(), MemoryPlanCache(cache.text))
                restarted.load()
                assertEquals(state.copy(refreshing = false), restarted.state.value, file.name)
            }
        }

    @Test
    fun `the week fixture reads through the clock into today and the days after`() =
        runTest {
            val repository = repository(fixture("week"), today = "2026-11-20")
            repository.refresh(PlanRefresh.START)
            val agenda = PlanAgenda.of(repository.state.value.workouts, "2026-11-20")
            assertEquals(listOf("icu-1001"), agenda.today.map { it.id })
            assertEquals(listOf("2026-11-22"), agenda.thisWeek.map { it.date })
            assertEquals(listOf("icu-1002", "icu-1003"), agenda.thisWeek.single().workouts.map { it.id })
            val free =
                agenda.thisWeek
                    .single()
                    .workouts
                    .last()
                    .workout
            assertEquals(FreeStep(duration = 3600), free.steps.single())
            assertTrue(agenda.today.single().workout.playability() is Playability.Runnable)
            assertTrue(agenda.today.single().workout.intensityFactor()!! in 0.7..0.9)
            assertNull(free.intensityFactor(), "a free ride prescribes nothing")
        }

    @Test
    fun `the stale and never-refreshed fixtures are marked stale, the first with its time`() =
        runTest {
            val stale = repository(fixture("stale-cache"))
            stale.refresh(PlanRefresh.START)
            assertTrue(stale.state.value.stale)
            assertEquals("2026-11-18T06:15:00.250Z", stale.state.value.generatedAt)
            assertEquals(listOf("icu-998877"), stale.state.value.workouts.map { it.id })

            val never = repository(fixture("never-refreshed"))
            never.refresh(PlanRefresh.START)
            assertTrue(never.state.value.stale)
            assertNull(never.state.value.generatedAt)
        }

    @Test
    fun `every invalid answer is not the service, and the last good list stays`() =
        runTest {
            val lastGood = listOf(PlanSamples.planned("icu-1", "2026-11-18"))
            val good = PlanSamples.json(PlanSamples.response("2026-11-18", "2026-12-01", lastGood))
            assertTrue(fixtures("invalid").size >= 15)
            for (file in fixtures("invalid")) {
                val transport = ScriptedGetTransport(HttpAnswer(200, good))
                val repository =
                    PlanRepository(
                        config = { config },
                        transport = transport,
                        cache = MemoryPlanCache(),
                        today = { "2026-11-18" },
                        wallMillis = { 1_700_000_000_000L },
                        elapsedMillis = { 1L },
                    )
                repository.refresh(PlanRefresh.START)
                transport.then(HttpAnswer(200, file.readText()))
                repository.refresh(PlanRefresh.REQUESTED)
                val state = repository.state.value
                assertEquals(PlanProblem.Failed("That is not the Overdrive service"), state.problem, file.name)
                assertEquals(listOf("icu-1"), state.workouts.map { it.id }, file.name)
                assertTrue(state.stale, file.name)
            }
        }

    @Test
    fun `the fortnight the repository asks for is inside what the service serves`() {
        assertTrue(PlanRepository.HORIZON_DAYS <= PlanApiJson.MAX_RANGE_DAYS)
    }

    @Test
    fun `calendar arithmetic agrees with java time over the years`() {
        var date = LocalDate.of(1999, 12, 25)
        repeat(12_000) {
            assertEquals(date.plusDays(13).toString(), PlanCalendar.addDays(date.toString(), 13), date.toString())
            assertEquals(date.plusDays(-7).toString(), PlanCalendar.addDays(date.toString(), -7), date.toString())
            assertEquals(date.dayOfWeek.value, PlanCalendar.dayOfWeek(date.toString()), date.toString())
            assertEquals(date.toEpochDay(), PlanDates.epochDay(date.toString()))
            assertEquals(date.toString(), PlanCalendar.ofEpochDay(date.toEpochDay()))
            date = date.plusDays(1)
        }
    }
}
