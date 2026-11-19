package app.overdrive.ride.shots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedVariant
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteDetailsState
import app.overdrive.core.route.RoutePoint
import app.overdrive.core.route.RouteSource
import app.overdrive.core.route.RouteSummary
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.routes.RouteChoiceUi
import app.overdrive.ride.routes.RoutesView
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.uikit.OverdriveTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/**
 * The Ready screen with routes and the easier version (spec §11.4, §11.5, ADR 0037) as the rider meets it: the route row of a free ride and
 * the list behind it, the route a planned workout names, the two versions of a workout and the recovery line. Robolectric, synthetic data,
 * invented routes; the images go to `build/shots/` and are looked at, never committed.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class RoutesReadyTest : ShotTest() {
    private val zone = ZoneId.of("UTC")
    private val today = "2026-11-19"
    private val settings = AppSettings(ftp = 250)
    private val localWorkouts = TestData.library.workouts.value

    private fun summary(
        id: String,
        name: String,
        meters: Int,
        ascent: Int,
        map: Boolean,
    ) = RouteSummary(
        id,
        name,
        if (map) RouteSource.GPX else RouteSource.PROFILE,
        meters,
        ascent,
        map,
        "2026-11-19T09:00:00Z",
    )

    private val north = summary("route-north", "North road", 12_400, 340, map = true)
    private val hill = summary("route-hill", "Rolling hill", 5_000, 120, map = false)

    private fun pointsOf(summary: RouteSummary) =
        Route(
            summary.id,
            summary.name,
            summary.source,
            summary.distanceMeters,
            summary.ascentMeters,
            summary.hasCoordinates,
            summary.createdAt,
            listOf(
                RoutePoint(0.0, 100.0),
                RoutePoint(summary.distanceMeters.toDouble(), 100.0 + summary.ascentMeters),
            ).map {
                if (summary.hasCoordinates) it.copy(lat = 45.0, lon = 7.0 + it.d * 1e-5) else it
            },
        )

    private fun view(
        downloaded: List<RouteSummary> = listOf(north, hill),
        routes: List<RouteSummary> = listOf(north, hill),
        failed: Map<String, String> = emptyMap(),
    ) = RoutesView(routes, RouteDetailsState(downloaded.associate { it.id to pointsOf(it) }, emptySet(), failed))

    private val sweetSpot =
        Workout(
            1,
            "icu-5001",
            "Over-under 3x10",
            "Three 10-minute efforts with 5-minute recoveries.",
            listOf(SteadyStep(duration = 2400, target = 0.88), SteadyStep(duration = 300, target = 0.5)),
        )
    private val easier = listOf(SteadyStep(duration = 1500, target = 0.8), SteadyStep(duration = 300, target = 0.5))
    private val rideOut =
        Workout(
            1,
            "icu-7001",
            "Ride out",
            null,
            listOf(SteadyStep(duration = 600, target = 0.5), FreeStep(duration = 3600, routeId = "route-north")),
        )

    private fun plan(
        routes: RoutesView = view(),
        recovery: String? = "Recovery: caution",
        vararg workouts: PlannedWorkout,
    ) = PlanUi.of(
        PlanState(true, workouts.toList(), "2026-11-19T06:30:00Z", true),
        emptyList(),
        today,
        0L,
        zone,
        Locale.ENGLISH,
        routes,
        recovery,
    )

    private fun planned(
        workout: Workout,
        variant: PlannedVariant? = null,
    ) = PlannedWorkout(workout.id, today, workout.name, PlanSource.INTERVALS_ICU, workout, variant)

    private class Chosen {
        var choice: RideChoice = RideChoice.Free
        var route: String? = "unset"
    }

    @Composable
    private fun Ready(
        plan: PlanUi,
        routes: RoutesView,
        chosen: Chosen,
        freeRoute: String? = null,
    ) {
        var choice by remember { mutableStateOf(chosen.choice) }
        var route by remember { mutableStateOf(freeRoute) }
        OverdriveTheme {
            ReadyScreen(
                choice = choice,
                workouts = localWorkouts,
                settings = settings,
                devices = TestData.devicesOk,
                startEnabled = true,
                openError = null,
                topBar = TopBarActions({}, {}, {}),
                actions =
                    ReadyActions(
                        onSelect = {
                            choice = it
                            chosen.choice = it
                        },
                        onStart = {},
                        onOpenFile = {},
                        onDismissError = {},
                        onChooseRoute = {
                            route = it
                            chosen.route = it
                        },
                    ),
                plan = plan,
                routeChoice = RouteChoiceUi.of(routes, route),
            )
        }
    }

    private fun row(name: String) = rule.onNode(hasText(name, substring = true) and hasClickAction())

    // ---- the route row of a free ride ----

    @Test
    fun aFreeRideHasOneRouteRowThatSaysFlatRoadAndOpensTheList() {
        val chosen = Chosen()
        shot("ride--ready-route") { Ready(PlanUi.Hidden, view(), chosen) }
        rule.onNodeWithText("Route").assertExists()
        rule.onNodeWithText("Flat road").assertExists()
        rule.onNodeWithText("North road").assertDoesNotExist()

        rule.onNodeWithText("Flat road").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/ride--ready-route-list.png")
        rule.onNodeWithText("12.4 km · 340 m up · has a map").assertExists()
        rule.onNodeWithText("5.0 km · 120 m up · profile only").assertExists()
        rule.onNodeWithText("No hills").assertExists()
    }

    @Test
    fun choosingARouteClosesTheListAndTheRowSaysWhatIsChosen() {
        val chosen = Chosen()
        shot("ride--ready-route-chosen") { Ready(PlanUi.Hidden, view(), chosen) }
        rule.onNodeWithText("Flat road").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("North road") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals("route-north", chosen.route)
        rule.onNodeWithText("12.4 km · 340 m up · has a map").assertExists()
        rule
            .onNodeWithText(
                "Simulation on the route's grade with virtual gears. Ride as long as you like, then finish.",
            ).assertExists()
        // The flat road is a choice too.
        rule.onNode(hasText("North road") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Flat road") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertNull(chosen.route)
    }

    @Test
    fun aChosenRouteThatIsNotDownloadedSaysSoPlainly() {
        val chosen = Chosen()
        shot("ride--ready-route-missing") {
            Ready(PlanUi.Hidden, view(downloaded = emptyList()), chosen, freeRoute = "route-north")
        }
        rule.onNodeWithText("North road").assertExists()
        rule.onNodeWithText("Route not downloaded yet").assertExists()
    }

    @Test
    fun withNoRoutesThereIsNoRowAndNothingIsSaid() {
        val chosen = Chosen()
        shot("ride--ready-no-routes") { Ready(PlanUi.Hidden, RoutesView.None, chosen) }
        rule.onNodeWithText("Route").assertDoesNotExist()
        rule.onNodeWithText("Flat road").assertDoesNotExist()
        rule
            .onNodeWithText(
                "Simulation on the flat with virtual gears. Ride as long as you like, then finish.",
            ).assertExists()
    }

    // ---- the plan row ----

    @Test
    fun aPlannedWorkoutNamesItsRoute() {
        val chosen = Chosen()
        val on = plan(view(), null, planned(rideOut))
        shot("ride--ready-planned-route") { Ready(on, view(), chosen) }
        rule.onNodeWithText("Steady, free · Route: North road").assertExists()
    }

    @Test
    fun aPlannedWorkoutWhoseRouteIsNotDownloadedSaysSo() {
        val missing = view(downloaded = emptyList())
        shot("ride--ready-planned-route-missing") { Ready(plan(missing, null, planned(rideOut)), missing, Chosen()) }
        rule.onNodeWithText("Steady, free · Route not downloaded yet").assertExists()
    }

    // ---- the easier version ----

    @Test
    fun aWorkoutWithAnEasierVersionOffersBothWithTheNoteAndTheRecoveryLine() {
        val chosen = Chosen()
        val variant = PlannedVariant(easier, "Two efforts at 80%, about 25 minutes shorter.", "0123456789abcdef")
        val ui = plan(view(), "Recovery: caution", planned(sweetSpot, variant))
        shot("ride--ready-variant") { Ready(ui, view(), chosen) }
        row("Over-under 3x10").assertExists()
        rule.onNode(hasText("Easier version") and hasClickAction()).assertExists()
        rule.onNodeWithText("Two efforts at 80%, about 25 minutes shorter.").assertExists()
        rule.onNodeWithText("Recovery: caution").assertExists()
        rule.onNodeWithText("30 min").assertExists()
    }

    @Test
    fun oneTapChoosesEachVersionAndTheDetailNamesTheEasierOne() {
        val chosen = Chosen()
        val variant = PlannedVariant(easier, "Two efforts at 80%, about 25 minutes shorter.", "0123456789abcdef")
        val ui = plan(view(), "Recovery: ready", planned(sweetSpot, variant))
        shot("ride--ready-variant-chosen") { Ready(ui, view(), chosen) }

        rule.onNode(hasText("Easier version") and hasClickAction()).performClick()
        rule.waitForIdle()
        val easierChoice = chosen.choice as RideChoice.Planned
        assertEquals(true, easierChoice.variant)
        assertEquals("icu-5001", easierChoice.workout.id)
        assertEquals(easier, easierChoice.workout.steps)
        assertEquals("0123456789abcdef", easierChoice.structureHash)
        rule.onNodeWithText("Easier version · Two efforts at 80%, about 25 minutes shorter.").assertExists()

        row("Over-under 3x10").performClick()
        rule.waitForIdle()
        val planned = chosen.choice as RideChoice.Planned
        assertEquals(false, planned.variant)
        assertEquals(sweetSpot.steps, planned.workout.steps)
    }

    @Test
    fun aWorkoutWithNoEasierVersionLooksAsItAlwaysDid() {
        val chosen = Chosen()
        shot("ride--ready-no-variant") { Ready(plan(view(), "Recovery: caution", planned(sweetSpot)), view(), chosen) }
        rule.onNodeWithText("Easier version").assertDoesNotExist()
        rule.onNodeWithText("Recovery: caution").assertDoesNotExist()
        row("Over-under 3x10").assertExists()
    }
}
