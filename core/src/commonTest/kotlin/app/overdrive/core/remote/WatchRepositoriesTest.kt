package app.overdrive.core.remote

import app.overdrive.core.insights.Insight
import app.overdrive.core.insights.InsightKind
import app.overdrive.core.insights.InsightsApiJson
import app.overdrive.core.insights.InsightsResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.RemoteSamples.error
import app.overdrive.core.remote.RemoteSamples.ok
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.Reading
import app.overdrive.core.wellness.UsualSleepTimes
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessDay
import app.overdrive.core.wellness.WellnessResponse
import app.overdrive.core.wellness.WellnessSourceId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun reading(
    value: Double?,
    low: Double? = null,
    high: Double? = null,
    deviation: Deviation? = null,
    streak: Int = 0,
) = Reading(value, if (value == null) null else WellnessSourceId.HEALTH_CONNECT, low, high, deviation, streak)

internal fun wellnessResponse(
    from: String,
    to: String,
    stale: Boolean = false,
): WellnessResponse {
    val first = checkNotNull(PlanDates.epochDay(from))
    val last = checkNotNull(PlanDates.epochDay(to))
    val days =
        (first..last).map { day ->
            WellnessDay(
                date = PlanCalendar.ofEpochDay(day),
                restingHr = reading(52.0, 50.0, 56.0, Deviation.INSIDE),
                sleepSeconds = reading(27_600.0, 24_600.0, 28_200.0, Deviation.INSIDE),
                bedtimeMinutes = reading(-55.0, -70.0, -20.0, Deviation.INSIDE),
                wakeMinutes = reading(405.0, 380.0, 425.0, Deviation.INSIDE),
                respiratoryRate = reading(14.2, 13.6, 14.6, Deviation.INSIDE),
                spo2 = reading(null),
                sleepingHr = reading(null),
                hrvRmssd = reading(null),
                sleepStart = "${PlanCalendar.addDays(PlanCalendar.ofEpochDay(day), -1)}T22:55:00Z",
                sleepEnd = "${PlanCalendar.ofEpochDay(day)}T06:45:00Z",
                steps = null,
                hrvSdnn = null,
                weightKg = null,
                weightKg7d = null,
            )
        }
    return WellnessResponse(from, to, "${to}T07:40:00Z", stale, 31, 14, 42, UsualSleepTimes(null, null), days)
}

internal fun insight(
    id: Long,
    createdAt: String,
    kind: InsightKind = InsightKind.MORNING,
    text: String = "Note $id",
) = Insight(id, kind, createdAt, text, null, null)

class WatchRepositoriesTest {
    private val config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    @Test
    fun `the ranges end today and a year is two requests of at most 186 days that meet`() {
        assertEquals(listOf("2026-10-22" to "2026-11-18"), WellnessRange.FOUR_WEEKS.parts("2026-11-18"))
        assertEquals(listOf("2026-08-20" to "2026-11-18"), WellnessRange.THREE_MONTHS.parts("2026-11-18"))
        val year = WellnessRange.ONE_YEAR.parts("2026-11-18")
        assertEquals(2, year.size)
        assertEquals("2025-11-19", year.first().first)
        assertEquals("2026-11-18", year.last().second)
        assertEquals(PlanCalendar.addDays(year[0].second, 1), year[1].first, "no day twice and none missing")
        for ((from, to) in year) {
            val count = checkNotNull(PlanDates.epochDay(to)) - checkNotNull(PlanDates.epochDay(from)) + 1
            assertTrue(count <= WellnessResources.MAX_SERVED_DAYS)
        }
    }

    private fun TestScope.resources(transport: ScriptedTransport) =
        WellnessResources(
            scope = backgroundScope,
            client = ServiceClient({ config }, transport),
            config = { config },
            today = { "2026-11-18" },
            storeFor = { MemoryTextStore() },
            wallMillis = { wall },
            elapsedMillis = { elapsed },
        )

    @Test
    fun `four weeks are one request, shown with the answer's own days and cached`() =
        runTest(UnconfinedTestDispatcher()) {
            val body = WellnessApiJson.encodeWellnessResponse(wellnessResponse("2026-10-22", "2026-11-18"))
            val transport = ScriptedTransport(ok(body))
            val resources = resources(transport)
            resources.load()
            resources.refresh(WellnessRange.FOUR_WEEKS, Refresh.START)
            assertEquals("$ADDRESS/v1/wellness?from=2026-10-22&to=2026-11-18", transport.gets.single().url)
            val state = resources.view(WellnessRange.FOUR_WEEKS).state.value
            val data = assertNotNull(state.value)
            assertEquals(28, data.days.size)
            assertEquals("2026-11-18", data.days.last().date)
            assertNull(state.problem)
            assertEquals(wall, state.fetchedAtMillis)
        }

    @Test
    fun `a year is asked in two parts and joined into one run of days`() =
        runTest(UnconfinedTestDispatcher()) {
            val (older, newer) = WellnessRange.ONE_YEAR.parts("2026-11-18")
            val transport =
                ScriptedTransport(
                    ok(WellnessApiJson.encodeWellnessResponse(wellnessResponse(older.first, older.second))),
                    ok(WellnessApiJson.encodeWellnessResponse(wellnessResponse(newer.first, newer.second))),
                )
            val resources = resources(transport)
            resources.load()
            resources.refresh(WellnessRange.ONE_YEAR, Refresh.START)
            assertEquals(2, transport.gets.size)
            assertEquals(
                setOf(
                    "$ADDRESS/v1/wellness?from=${older.first}&to=${older.second}",
                    "$ADDRESS/v1/wellness?from=${newer.first}&to=${newer.second}",
                ),
                transport.gets.map { it.url }.toSet(),
            )
            val data = assertNotNull(resources.view(WellnessRange.ONE_YEAR).state.value.value)
            assertEquals(365, data.days.size)
            assertEquals(older.first, data.days.first().date)
            assertEquals("2026-11-18", data.days.last().date)
            assertEquals(data.days.map { it.date }, data.days.map { it.date }.distinct(), "no day twice")
        }

    @Test
    fun `a year whose older half failed shows the newer half and says why`() =
        runTest(UnconfinedTestDispatcher()) {
            val (older, newer) = WellnessRange.ONE_YEAR.parts("2026-11-18")
            val transport =
                ScriptedTransport(
                    error(500),
                    ok(WellnessApiJson.encodeWellnessResponse(wellnessResponse(newer.first, newer.second))),
                )
            val resources = resources(transport)
            resources.load()
            resources.refresh(WellnessRange.ONE_YEAR, Refresh.START)
            val state = resources.view(WellnessRange.ONE_YEAR).state.value
            assertEquals(newer.first, assertNotNull(state.value).from)
            assertEquals(ProblemKind.SERVER_ERROR, state.problem?.kind)
            assertTrue(older.first < newer.first)
        }

    @Test
    fun `recovery is asked from its route and read by the contract`() =
        runTest {
            val reason = "Overdrive rates recovery after 14 nights with the watch. 9 nights so far."
            val body =
                """{"date":"2026-11-18","nightsLogged":9,"nightsNeeded":14,"generatedAt":"2026-11-18T07:40:00Z",""" +
                    """"stale":false,"state":"learning","reason":"$reason","guidance":null,"rule":null,"facts":[]}"""
            val transport = ScriptedTransport(ok(body))
            val client = ServiceClient({ config }, transport)
            val resource = recoveryResource(client, { config }, MemoryTextStore(), { wall }, { elapsed })
            resource.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/recovery", transport.gets.single().url)
            assertEquals(9, resource.state.value.value?.nightsLogged)
        }

    private fun TestScope.repository(
        transport: ScriptedTransport,
        windows: Int = InsightsRepository.MAX_WINDOWS,
    ) = InsightsRepository(
        backgroundScope,
        ServiceClient({ config }, transport),
        { config },
        { "2026-11-18" },
        MemoryTextStore(),
        { wall },
        { elapsed },
        maxWindows = windows,
    )

    private fun page(
        from: String,
        to: String,
        vararg notes: Insight,
    ) = ok(InsightsApiJson.encodeResponse(InsightsResponse(from, to, "${to}T07:40:00Z", false, notes.toList())))

    @Test
    fun `notes are 30 days at a time, older windows are appended, and a new newest drops them`() =
        runTest(UnconfinedTestDispatcher()) {
            val newest = insight(3, "2026-11-18T07:30:00Z")
            val earlier = insight(2, "2026-11-10T07:30:00Z")
            val transport =
                ScriptedTransport(
                    page("2026-10-20", "2026-11-18", newest, earlier),
                    page("2026-09-20", "2026-10-19", insight(1, "2026-10-01T07:30:00Z")),
                    page("2026-10-20", "2026-11-18", insight(4, "2026-11-18T09:30:00Z")),
                )
            val repository = repository(transport)
            repository.load()
            repository.refresh(Refresh.START)
            assertEquals("$ADDRESS/v1/insights?from=2026-10-20&to=2026-11-18", transport.gets[0].url)
            assertEquals(
                listOf(3L, 2L),
                repository.view.value.list.items
                    .map { it.id },
            )
            assertTrue(repository.view.value.list.hasMore)

            repository.loadMore()
            assertEquals("$ADDRESS/v1/insights?from=2026-09-20&to=2026-10-19", transport.gets[1].url)
            assertEquals(
                listOf(3L, 2L, 1L),
                repository.view.value.list.items
                    .map { it.id },
            )

            wall += 10
            repository.refresh(Refresh.REQUESTED)
            assertEquals(
                listOf(4L),
                repository.view.value.list.items
                    .map { it.id },
                "the older windows are dropped",
            )
        }

    @Test
    fun `a failing older window is said and the notes already fetched stay`() =
        runTest(UnconfinedTestDispatcher()) {
            val newest = insight(3, "2026-11-18T07:30:00Z")
            val transport = ScriptedTransport(page("2026-10-20", "2026-11-18", newest), error(503))
            val repository = repository(transport)
            repository.refresh(Refresh.START)
            repository.loadMore()
            val list = repository.view.value.list
            assertEquals(listOf(3L), list.items.map { it.id })
            assertEquals(ProblemKind.SERVER_ERROR, list.moreProblem?.kind)
        }

    @Test
    fun `at most a year of notes is offered`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = ScriptedTransport(page("2026-10-20", "2026-11-18"))
            val repository = repository(transport, windows = 3)
            repository.refresh(Refresh.START)
            repository.loadMore()
            repository.loadMore()
            repository.loadMore()
            assertEquals(3, transport.gets.size, "the newest and two more")
            assertEquals(false, repository.view.value.list.hasMore)
        }
}
