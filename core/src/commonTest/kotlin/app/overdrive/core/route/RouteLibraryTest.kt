package app.overdrive.core.route

import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedVariant
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.remote.ADDRESS
import app.overdrive.core.remote.MemoryTextStore
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.Refresh
import app.overdrive.core.remote.ScriptedTransport
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.TOKEN
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The routes on the tablet (spec §11.4, ADR 0037): the list cached and shown offline, the points fetched once and kept on disk. */
class RouteLibraryTest {
    private var config = SyncConfig(ADDRESS, TOKEN)
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L

    private val hill = hillRoute("route-hill")
    private val line = lineRoute(200.0, id = "route-line", name = "Line")
    private val listBody =
        RoutesApiJson.encodeRoutes(RoutesResponse("2026-11-19T10:00:00Z", listOf(hill.summary, line.summary)))

    private fun ok(route: Route) = HttpAnswer(200, RoutesApiJson.encodeRoute(route))

    private class Disk {
        val list = MemoryTextStore()
        val details = HashMap<String, MemoryTextStore>()

        fun of(id: String) = details.getOrPut(id) { MemoryTextStore() }
    }

    private fun library(
        transport: ScriptedTransport,
        disk: Disk = Disk(),
    ) = RouteLibrary(
        client = ServiceClient({ config }, transport),
        config = { config },
        listStore = disk.list,
        detailStore = disk::of,
        wallMillis = { wall },
        elapsedMillis = { elapsed },
    )

    @Test
    fun `the list is asked from the routes route, shown, and kept for the next start and for no network`() =
        runTest {
            val disk = Disk()
            val transport = ScriptedTransport(HttpAnswer(200, listBody))
            val library = library(transport, disk)
            library.load()
            library.refreshList(Refresh.START)
            assertEquals("$ADDRESS/v1/routes", transport.gets.single().url)
            assertEquals(
                listOf("route-hill", "route-line"),
                assertNotNull(library.list.state.value.value).routes.map { it.id },
            )
            assertTrue(
                disk.list.text.orEmpty().endsWith(listBody),
                "the cache holds the answer as the service wrote it",
            )

            // A new process with no network: the list is there before any request, and says why it is old.
            val offline = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val restarted = library(offline, disk)
            restarted.load()
            assertEquals(2, assertNotNull(restarted.list.state.value.value).routes.size)
            assertTrue(offline.gets.isEmpty())
            restarted.refreshList(Refresh.REQUESTED)
            assertTrue(restarted.list.state.value.isStale)
            assertTrue(assertNotNull(restarted.list.state.value.problem).isOffline)
        }

    @Test
    fun `a route's points are fetched when asked for, kept on disk, and never asked for again`() =
        runTest {
            val disk = Disk()
            val transport = ScriptedTransport(ok(hill))
            val library = library(transport, disk)
            assertEquals(RouteStatus.NOT_DOWNLOADED, library.details.value.statusOf("route-hill"))

            library.ensure("route-hill", Refresh.REQUESTED)
            assertEquals("$ADDRESS/v1/routes/route-hill", transport.gets.single().url)
            assertEquals(RouteStatus.READY, library.details.value.statusOf("route-hill"))
            assertEquals(hill, library.details.value.downloaded["route-hill"])
            assertTrue(
                disk.of("route-hill").text.orEmpty().startsWith("overdrive-cache 1 route "),
                "the file is one route",
            )

            library.ensure("route-hill")
            library.ensure(listOf("route-hill", "route-hill"))
            assertEquals(1, transport.gets.size, "a route on the tablet is not asked for again")
        }

    @Test
    fun `after a restart a route comes from disk and a ride on it needs no network`() =
        runTest {
            val disk = Disk()
            library(ScriptedTransport(ok(hill)), disk).ensure("route-hill")

            val offline = ScriptedTransport(TransportException(TransportFailure.NoNetwork))
            val restarted = library(offline, disk)
            restarted.ensure("route-hill")
            assertEquals(RouteStatus.READY, restarted.details.value.statusOf("route-hill"))
            assertEquals(hill, restarted.details.value.downloaded["route-hill"])
            assertTrue(offline.gets.isEmpty(), "nothing was asked: the disk had it")
        }

    @Test
    fun `with no network and nothing on disk the route is not downloaded, says why, and works the next time`() =
        runTest {
            val transport = ScriptedTransport(TransportException(TransportFailure.NoNetwork), ok(line))
            val library = library(transport)
            library.ensure("route-line", Refresh.REQUESTED)
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-line"))
            assertTrue(library.details.value.downloaded.isEmpty())
            assertTrue(assertNotNull(library.details.value.failed["route-line"]).isNotEmpty())
            assertFalse(library.details.value.downloading.contains("route-line"))

            library.ensure("route-line", Refresh.REQUESTED)
            assertEquals(RouteStatus.READY, library.details.value.statusOf("route-line"))
            assertTrue(library.details.value.failed.isEmpty(), "the failure is forgotten when it works")
        }

    @Test
    fun `a route the service does not have is a failure with its own words`() =
        runTest {
            val library =
                library(
                    ScriptedTransport(HttpAnswer(404, """{"error":{"code":"not_found","message":"No such route"}}""")),
                )
            library.ensure("route-gone", Refresh.REQUESTED)
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-gone"))
            assertEquals("The service does not have this route", library.details.value.failed["route-gone"])
        }

    @Test
    fun `an answer for another route is not the service`() =
        runTest {
            val library = library(ScriptedTransport(ok(hill)))
            library.ensure("route-line", Refresh.REQUESTED)
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-line"))
            assertTrue(library.details.value.downloaded.isEmpty())
        }

    @Test
    fun `an answer that is not a route is not the service and is not kept`() =
        runTest {
            val disk = Disk()
            val library = library(ScriptedTransport(HttpAnswer(200, """{"hello":"world"}""")), disk)
            library.ensure("route-line", Refresh.REQUESTED)
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-line"))
            assertNull(disk.of("route-line").text)
        }

    @Test
    fun `a damaged file on disk is no cache and the route is asked for again`() =
        runTest {
            val disk = Disk()
            library(ScriptedTransport(ok(hill)), disk).ensure("route-hill")
            disk.of("route-hill").text = disk.of("route-hill").text!!.dropLast(40)
            val transport = ScriptedTransport(ok(hill))
            val restarted = library(transport, disk)
            restarted.ensure("route-hill")
            assertEquals(1, transport.gets.size)
            assertEquals(RouteStatus.READY, restarted.details.value.statusOf("route-hill"))
        }

    @Test
    fun `a string that is not a route id is never put on a url`() =
        runTest {
            val transport = ScriptedTransport(ok(hill))
            val library = library(transport)
            library.ensure(listOf("../secret", "a/b", "", "x".repeat(65)))
            assertTrue(transport.gets.isEmpty())
            assertTrue(library.details.value.downloaded.isEmpty() && library.details.value.failed.isEmpty())
        }

    @Test
    fun `with no address or token nothing is asked and the route is simply not there`() =
        runTest {
            config = SyncConfig("", "")
            val transport = ScriptedTransport(ok(hill))
            val library = library(transport)
            library.ensure("route-hill", Refresh.REQUESTED)
            assertTrue(transport.gets.isEmpty())
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-hill"))
            assertEquals("The service is not set up", library.details.value.failed["route-hill"])
        }

    @Test
    fun `two askers for one route make one request`() =
        runTest {
            val transport = ScriptedTransport(ok(hill))
            val library = library(transport)
            val first = async { library.ensure("route-hill", Refresh.REQUESTED) }
            val second = async { library.ensure("route-hill", Refresh.START) }
            first.await()
            second.await()
            assertEquals(1, transport.gets.size)
            assertEquals(RouteStatus.READY, library.details.value.statusOf("route-hill"))
        }

    @Test
    fun `another service's address drops what was loaded from the first`() =
        runTest {
            val library = library(ScriptedTransport(ok(hill)))
            library.ensure("route-hill")
            assertEquals(RouteStatus.READY, library.details.value.statusOf("route-hill"))
            config = SyncConfig("https://other.example.org", TOKEN)
            library.settingsChanged()
            assertTrue(library.details.value.downloaded.isEmpty())
            // The same address with another token is the same service: nothing is dropped.
            config = SyncConfig("https://other.example.org", "$TOKEN-2")
            val other = library(ScriptedTransport(ok(hill)))
            other.ensure("route-hill")
            other.settingsChanged()
            assertEquals(RouteStatus.READY, other.details.value.statusOf("route-hill"))
        }

    @Test
    fun `a refused token is a failure that says so`() =
        runTest {
            val library =
                library(ScriptedTransport(HttpAnswer(401, """{"error":{"code":"unauthorized","message":"No"}}""")))
            library.ensure("route-hill", Refresh.REQUESTED)
            assertEquals(RouteStatus.FAILED, library.details.value.statusOf("route-hill"))
            assertEquals("The service did not accept the token", library.details.value.failed["route-hill"])
            assertEquals(ProblemKind.TOKEN_REJECTED, ProblemKind.TOKEN_REJECTED)
        }

    // ---- which routes to fetch ahead ----

    private fun planned(
        id: String,
        date: String,
        steps: List<app.overdrive.core.workout.Step>,
        variant: List<app.overdrive.core.workout.Step>? = null,
    ) = PlannedWorkout(
        id,
        date,
        "W $id",
        PlanSource.INTERVALS_ICU,
        Workout(1, id, "W $id", steps = steps),
        variant?.let { PlannedVariant(it, "Easier", "0123456789abcdef") },
    )

    @Test
    fun `the routes to fetch ahead are those the next days' free steps name, the easier version's included`() {
        val workouts =
            listOf(
                planned("icu-0", "2026-11-18", listOf(FreeStep(duration = 60, routeId = "route-yesterday"))),
                planned(
                    "icu-1",
                    "2026-11-19",
                    listOf(SteadyStep(duration = 60, target = 0.5), FreeStep(duration = 600, routeId = "route-a")),
                    variant = listOf(FreeStep(duration = 300, routeId = "route-b")),
                ),
                planned(
                    "icu-2",
                    "2026-11-21",
                    listOf(
                        RepeatStep(
                            2,
                            listOf(
                                FreeStep(duration = 300, routeId = "route-c"),
                                SteadyStep(duration = 60, target = 0.5),
                            ),
                        ),
                        FreeStep(duration = 60, routeId = "route-a"),
                        FreeStep(duration = 60),
                    ),
                ),
                planned("icu-3", "2026-11-25", listOf(FreeStep(duration = 60, routeId = "route-d"))),
                planned("icu-4", "2026-11-26", listOf(FreeStep(duration = 60, routeId = "route-too-far"))),
            )
        // Today is the 19th: the list covers today and six days on, so route-d (the 25th) is in and route-too-far (the 26th) is out.
        assertEquals(
            listOf("route-a", "route-b", "route-c", "route-d"),
            RouteLibrary.routeIdsOf(workouts, today = "2026-11-19"),
        )
        assertEquals(emptyList(), RouteLibrary.routeIdsOf(emptyList(), today = "2026-11-19"))
        assertEquals(listOf("route-c", "route-a"), RouteLibrary.routeIdsOf(workouts[2].workout.steps))
    }
}
