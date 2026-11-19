package app.overdrive.ride.routes

import app.overdrive.core.remote.ResourceState
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteDetailsState
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.RouteSource
import app.overdrive.core.route.RouteSummary
import app.overdrive.core.route.RoutesResponse
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.RecoveryFact
import app.overdrive.core.wellness.RecoveryMetric
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryRule
import app.overdrive.core.wellness.RecoveryState
import app.overdrive.core.wellness.RecoveryUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words of the route row, the route list and the recovery line (spec §11.4, §11.5), as pure functions of what the tablet has:
 * every sentence the Ready screen says about routes is here, offline cases included. All routes are invented.
 */
class RouteUiTest {
    private fun summary(
        id: String,
        name: String,
        meters: Int,
        ascent: Int,
        map: Boolean = true,
    ) = RouteSummary(
        id,
        name,
        if (map) RouteSource.GPX else RouteSource.PROFILE,
        meters,
        ascent,
        map,
        "2026-11-19T09:00:00Z",
    )

    private val north = summary("route-north", "North road", 12_400, 340)
    private val hill = summary("route-hill", "Rolling hill", 5_000, 120, map = false)
    private val short = summary("route-short", "Round the block", 640, 6)
    private val long = summary("route-long", "Grand tour", 142_300, 2_100)

    private fun pointsOf(summary: RouteSummary) =
        Route(
            summary.id,
            summary.name,
            summary.source,
            summary.distanceMeters,
            summary.ascentMeters,
            summary.hasCoordinates,
            summary.createdAt,
            listOf(RoutePoint(0.0, 100.0), RoutePoint(summary.distanceMeters.toDouble(), 100.0 + summary.ascentMeters))
                .let { pts ->
                    if (summary.hasCoordinates) pts.map { it.copy(lat = 45.0 + it.d * 9e-6, lon = 7.0) } else pts
                },
        )

    private fun view(
        routes: List<RouteSummary> = listOf(north, hill),
        downloaded: List<RouteSummary> = emptyList(),
        downloading: Set<String> = emptySet(),
        failed: Map<String, String> = emptyMap(),
    ) = RoutesView(routes, RouteDetailsState(downloaded.associate { it.id to pointsOf(it) }, downloading, failed))

    // ---- the route row of a free ride ----

    @Test
    fun withNoRoutesAndNoneChosenThereIsNoRowAndNothingIsSaid() {
        assertNull(RouteChoiceUi.of(RoutesView.None, null))
        assertNull(RouteChoiceUi.of(view(routes = emptyList()), null))
    }

    @Test
    fun theFlatRoadIsTheDefaultAndTheFirstOfTheList() {
        val ui = checkNotNull(RouteChoiceUi.of(view(), chosen = null))
        assertEquals("Flat road", ui.title)
        assertNull(ui.detail)
        assertEquals(listOf(null, "route-north", "route-hill"), ui.options.map { it.id })
        assertEquals(listOf("Flat road", "North road", "Rolling hill"), ui.options.map { it.title })
        assertEquals(listOf(true, false, false), ui.options.map { it.selected })
        assertEquals("No hills", ui.options.first().detail)
    }

    @Test
    fun eachRouteInTheListSaysItsDistanceItsAscentAndWhetherItHasAMap() {
        val ui = checkNotNull(RouteChoiceUi.of(view(routes = listOf(north, hill, short, long)), null))
        assertEquals(
            listOf(
                "No hills",
                "12.4 km · 340 m up · has a map",
                "5.0 km · 120 m up · profile only",
                "640 m · 6 m up · has a map",
                "142 km · 2100 m up · has a map",
            ),
            ui.options.map { it.detail },
        )
    }

    @Test
    fun aChosenRouteThatIsOnTheTabletSaysWhatItIs() {
        val ui = checkNotNull(RouteChoiceUi.of(view(downloaded = listOf(north)), chosen = "route-north"))
        assertEquals("North road", ui.title)
        assertEquals("12.4 km · 340 m up · has a map", ui.detail)
        assertEquals(listOf(false, true, false), ui.options.map { it.selected })
    }

    @Test
    fun aChosenRouteThatIsNotOnTheTabletSaysSoPlainlyBecauseTheRideWillBeFlat() {
        val missing = checkNotNull(RouteChoiceUi.of(view(), chosen = "route-north"))
        assertEquals("North road", missing.title)
        assertEquals("Route not downloaded yet", missing.detail)

        val failed =
            checkNotNull(RouteChoiceUi.of(view(failed = mapOf("route-north" to "Offline")), chosen = "route-north"))
        assertEquals("Route not downloaded yet", failed.detail)

        val coming = checkNotNull(RouteChoiceUi.of(view(downloading = setOf("route-north")), chosen = "route-north"))
        assertEquals("Downloading…", coming.detail)
    }

    @Test
    fun aChosenRouteThatTheListNoLongerHasButTheTabletKeepsStillWorks() {
        val ui =
            checkNotNull(
                RouteChoiceUi.of(view(routes = listOf(hill), downloaded = listOf(north)), chosen = "route-north"),
            )
        assertEquals("the name is on the downloaded route", "North road", ui.title)
        assertEquals("12.4 km · 340 m up · has a map", ui.detail)
    }

    @Test
    fun aChosenRouteThatIsNowhereIsAPlainRouteThatIsNotDownloaded() {
        val ui = checkNotNull(RouteChoiceUi.of(view(routes = listOf(hill)), chosen = "route-gone"))
        assertEquals("A route", ui.title)
        assertEquals("Route not downloaded yet", ui.detail)
    }

    @Test
    fun theListOfRoutesIsReadFromTheResourceState() {
        val list = RoutesResponse("2026-11-19T10:00:00Z", listOf(hill, north))
        val built = RoutesView.of(ResourceState(loaded = true, value = list), RouteDetailsState())
        assertEquals(listOf("route-hill", "route-north"), built.routes.map { it.id })
        assertEquals(RoutesView.None, RoutesView.of(ResourceState(), RouteDetailsState()))
    }

    // ---- the recovery line ----

    private fun recovery(
        state: RecoveryState,
        date: String = "2026-11-19",
        stale: Boolean = false,
    ): RecoveryResponse {
        val fact =
            RecoveryFact(
                RecoveryMetric.RESTING_HR,
                "Resting heart rate",
                52.0,
                RecoveryUnit.BPM,
                49.4,
                55.6,
                Deviation.INSIDE,
                0,
            )
        return when (state) {
            RecoveryState.READY -> {
                RecoveryResponse(
                    date,
                    state,
                    "Usual.",
                    "Train as planned.",
                    RecoveryRule.OK,
                    31,
                    14,
                    "${date}T07:40:00Z",
                    stale,
                    listOf(fact),
                )
            }

            RecoveryState.CAUTION -> {
                RecoveryResponse(
                    date,
                    state,
                    "A bit off.",
                    "Keep it short.",
                    RecoveryRule.C1,
                    31,
                    14,
                    "${date}T07:40:00Z",
                    stale,
                    listOf(fact),
                )
            }

            RecoveryState.REST -> {
                RecoveryResponse(
                    date,
                    state,
                    "Off.",
                    "Take it easy.",
                    RecoveryRule.R1,
                    31,
                    14,
                    "${date}T07:40:00Z",
                    stale,
                    listOf(fact),
                )
            }

            RecoveryState.LEARNING -> {
                RecoveryResponse(
                    date,
                    state,
                    "Learning.",
                    null,
                    null,
                    9,
                    14,
                    "${date}T07:40:00Z",
                    stale,
                    listOf(fact),
                )
            }

            RecoveryState.NO_DATA -> {
                RecoveryResponse(
                    date,
                    state,
                    "No reading.",
                    null,
                    null,
                    31,
                    14,
                    "${date}T07:40:00Z",
                    stale,
                    emptyList(),
                )
            }
        }
    }

    private fun line(
        response: RecoveryResponse?,
        problem: Boolean = false,
    ) = RecoveryLine.of(
        ResourceState(
            loaded = true,
            value = response,
            problem = if (problem) app.overdrive.core.remote.ServiceProblem.NotTheService else null,
        ),
        "2026-11-19",
    )

    @Test
    fun aRatedStateOfTodayIsOneWord() {
        assertEquals("Recovery: ready", line(recovery(RecoveryState.READY)))
        assertEquals("Recovery: caution", line(recovery(RecoveryState.CAUTION)))
        assertEquals("Recovery: rest", line(recovery(RecoveryState.REST)))
    }

    @Test
    fun whatIsNotARatingOfTodayIsNothing() {
        assertNull("still learning the usual range", line(recovery(RecoveryState.LEARNING)))
        assertNull("no reading from last night", line(recovery(RecoveryState.NO_DATA)))
        assertNull("yesterday's rating is not today's", line(recovery(RecoveryState.CAUTION, date = "2026-11-18")))
        assertNull("nothing cached, nothing said", line(null))
    }

    @Test
    fun theCachedStateStillShowsWhenTheServiceCannotBeReached() {
        assertEquals("Recovery: caution", line(recovery(RecoveryState.CAUTION), problem = true))
        assertEquals("Recovery: caution", line(recovery(RecoveryState.CAUTION, stale = true)))
    }
}
