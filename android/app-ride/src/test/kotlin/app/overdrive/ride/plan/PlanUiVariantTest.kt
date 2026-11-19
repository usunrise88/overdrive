package app.overdrive.ride.plan

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
import app.overdrive.ride.routes.RoutesView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The easier version, the routes and the recovery line in the Planned section (spec §11.4, §11.5, ADR 0037): a planned workout with no
 * variant and no route looks exactly as before, one with a variant offers both, the route is named or said not to be downloaded.
 */
class PlanUiVariantTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val today = "2026-11-19"
    private val tomorrow = "2026-11-20"

    private val sweetSpot =
        Workout(
            1,
            "icu-5001",
            "Sweet spot 3x10",
            null,
            listOf(SteadyStep(duration = 2400, target = 0.88), SteadyStep(duration = 300, target = 0.5)),
        )
    private val easier = listOf(SteadyStep(duration = 1500, target = 0.8), SteadyStep(duration = 300, target = 0.5))

    private fun planned(
        date: String,
        workout: Workout = sweetSpot,
        variant: PlannedVariant? = null,
    ) = PlannedWorkout(workout.id, date, workout.name, PlanSource.INTERVALS_ICU, workout, variant)

    private fun variant(
        note: String? = "Two efforts at 80%, about 25 minutes shorter.",
        steps: List<SteadyStep> = easier.map { it },
    ) = PlannedVariant(steps, note, "0123456789abcdef")

    private fun state(vararg workouts: PlannedWorkout) =
        PlanState(true, workouts.toList(), "2026-11-19T06:30:00Z", true, false, null, false)

    private fun ui(
        state: PlanState,
        routes: RoutesView = RoutesView.None,
        recovery: String? = null,
    ) = PlanUi.of(state, emptyList(), today, 0L, zone, Locale.ENGLISH, routes, recovery)

    private fun summary(
        id: String,
        name: String,
    ) = RouteSummary(id, name, RouteSource.GPX, 12_400, 340, true, "2026-11-19T09:00:00Z")

    private fun route(summary: RouteSummary) =
        Route(
            summary.id,
            summary.name,
            summary.source,
            summary.distanceMeters,
            summary.ascentMeters,
            true,
            summary.createdAt,
            listOf(RoutePoint(0.0, 100.0, 45.0, 7.0), RoutePoint(12_400.0, 440.0, 45.1, 7.0)),
        )

    private fun routes(
        known: List<RouteSummary>,
        downloaded: List<RouteSummary> = emptyList(),
    ) = RoutesView(known, RouteDetailsState(downloaded.associate { it.id to route(it) }))

    // ---- a workout with no variant and no route is what it was ----

    @Test
    fun aPlannedWorkoutWithNoVariantAndNoRouteLooksExactlyAsBefore() {
        val row = ui(state(planned(today))).today.single()
        assertNull(row.variant)
        assertNull(row.recovery)
        assertEquals("Steady", row.subtitle)
        assertEquals(PlanRow(row.workout, "Sweet spot 3x10", "Steady", PlanMark.None, "45 min", row.intensity), row)
    }

    @Test
    fun aRecoveryLineWithNoVariantToChooseHasNothingToBeBesideAndIsNotShown() {
        val row = ui(state(planned(today)), recovery = "Recovery: caution").today.single()
        assertNull(row.recovery)
    }

    // ---- the easier version ----

    @Test
    fun aPlannedWorkoutWithAVariantOffersBothVersionsAndTheNoteIsUnderTheVariantsName() {
        val row = ui(state(planned(today, variant = variant()))).today.single()
        assertEquals("the planned version's own row is unchanged", "Steady", row.subtitle)
        val easier = checkNotNull(row.variant)
        assertEquals("Easier version", easier.title)
        assertEquals("Two efforts at 80%, about 25 minutes shorter.", easier.subtitle)
        assertEquals("30 min", easier.duration)
        assertEquals(PlanMark.None, easier.mark)
        assertTrue(easier.enabled)
        assertEquals("Two efforts at 80%, about 25 minutes shorter.", easier.note)
    }

    @Test
    fun theVariantPlaysThePlannedWorkoutWithTheVariantsStepsSoTheRideKeepsThePlannedId() {
        val easier = checkNotNull(ui(state(planned(today, variant = variant()))).today.single().variant)
        assertEquals("icu-5001", easier.workout.id)
        assertEquals("Sweet spot 3x10", easier.workout.name)
        assertEquals(this.easier, easier.workout.steps)
    }

    @Test
    fun withNoNoteTheVariantSaysWhatKindsOfStepItHas() {
        val row = ui(state(planned(today, variant = variant(note = null)))).today.single()
        assertEquals("Steady", checkNotNull(row.variant).subtitle)
        assertNull(row.variant?.note)
    }

    @Test
    fun theVariantIsOfferedOnEveryDayAndTheRecoveryLineOnlyOnToday() {
        val ui =
            ui(
                state(planned(today, variant = variant()), planned(tomorrow, variant = variant())),
                recovery = "Recovery: caution",
            )
        assertEquals("Recovery: caution", ui.today.single().recovery)
        val later = ui.week.single().rows.single()
        checkNotNull(later.variant)
        assertNull("recovery is this morning's, not tomorrow's", later.recovery)
    }

    @Test
    fun withNoRecoveryTheRowSaysNothingAboutIt() {
        val row = ui(state(planned(today, variant = variant())), recovery = null).today.single()
        checkNotNull(row.variant)
        assertNull(row.recovery)
    }

    @Test
    fun aVariantThePlayerCannotRunIsShownDisabledWithTheReason() {
        val tooLong = variant(steps = listOf(SteadyStep(duration = 50_000, target = 0.5)))
        val easier = checkNotNull(ui(state(planned(today, variant = tooLong))).today.single().variant)
        assertFalse(easier.enabled)
        assertEquals("Longer than 12 hours", easier.subtitle)
        assertEquals(PlanMark.Blocked, easier.mark)
    }

    // ---- the route a free step names ----

    private val north = summary("route-north", "North road")
    private val rideOut =
        Workout(
            1,
            "icu-7001",
            "Ride out",
            null,
            listOf(SteadyStep(duration = 600, target = 0.5), FreeStep(duration = 3600, routeId = "route-north")),
        )

    @Test
    fun aRouteOnTheTabletIsNamedInThePlanRow() {
        val row = ui(state(planned(today, rideOut)), routes(listOf(north), downloaded = listOf(north))).today.single()
        assertEquals("Steady, free · Route: North road", row.subtitle)
    }

    @Test
    fun aRouteThatIsNotDownloadedYetIsSaidPlainlyAndTheRideWillBeFlat() {
        val missing = ui(state(planned(today, rideOut)), routes(listOf(north))).today.single()
        assertEquals("Steady, free · Route not downloaded yet", missing.subtitle)
        val unknown = ui(state(planned(today, rideOut)), RoutesView.None).today.single()
        assertEquals("Steady, free · Route not downloaded yet", unknown.subtitle)
    }

    @Test
    fun aWorkoutWithTwoRoutesNamesBothAndAnyMissingOneSaysItIsNotDownloaded() {
        val hill = summary("route-hill", "Rolling hill")
        val two =
            Workout(
                1,
                "icu-7002",
                "Two roads",
                null,
                listOf(
                    FreeStep(duration = 600, routeId = "route-north"),
                    FreeStep(duration = 600, routeId = "route-hill"),
                ),
            )
        val both =
            ui(
                state(planned(today, two)),
                routes(listOf(north, hill), downloaded = listOf(north, hill)),
            ).today.single()
        assertEquals("Free ride, gears · Routes: North road, Rolling hill", both.subtitle)
        val one = ui(state(planned(today, two)), routes(listOf(north, hill), downloaded = listOf(north))).today.single()
        assertEquals("Free ride, gears · Route not downloaded yet", one.subtitle)
    }

    @Test
    fun theVariantNamesItsOwnRoutes() {
        val onRoad = variant(note = "Same road, easier.", steps = listOf(SteadyStep(duration = 600, target = 0.5)))
        val withRoute =
            PlannedVariant(
                listOf(FreeStep(duration = 1800, routeId = "route-north")),
                "Just the road.",
                "0123456789abcdef",
            )
        checkNotNull(onRoad)
        val row =
            ui(
                state(planned(today, rideOut, withRoute)),
                routes(listOf(north), downloaded = listOf(north)),
            ).today.single()
        assertEquals("Just the road. · Route: North road", checkNotNull(row.variant).subtitle)
    }

    @Test
    fun aDoneMarkAndTheRouteAndTheDescriptionAreOneLine() {
        val described = rideOut.copy(description = "Out and back along the river.")
        val ridden =
            listOf(
                app.overdrive.ride.rides.RiddenWorkout(
                    "icu-7001",
                    java.time.Instant.parse("2026-11-19T16:04:00Z").toEpochMilli(),
                ),
            )
        val row =
            PlanUi
                .of(
                    state(planned(today, described)),
                    ridden,
                    today,
                    0L,
                    zone,
                    Locale.ENGLISH,
                    routes(listOf(north), downloaded = listOf(north)),
                ).today
                .single()
        assertEquals("Done 17:04 · Out and back along the river. · Route: North road", row.subtitle)
        assertEquals(PlanMark.Done, row.mark)
    }
}
