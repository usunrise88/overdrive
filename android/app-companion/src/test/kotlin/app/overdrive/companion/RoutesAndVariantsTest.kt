package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.model.TodayContent
import app.overdrive.companion.model.TodayUi
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedVariant
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesView
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The companion and the routes and variants of stage A2b-1 (ADR 0036, ADR 0037): the companion decodes the planned workouts and a ride's plan
 * with the same strict classes as the tablet, so a plan whose workouts carry a `route_id` or an easier `variant` must read, and Today must say
 * what it always said. Before this stage a service that wrote either made the companion say "That is not the Overdrive service".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoutesAndVariantsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private val primary =
        Workout(
            1,
            "icu-sweet",
            "Sweet spot 3×10",
            null,
            listOf(
                RampStep(300, 0.45, 0.65, label = "Warm-up"),
                SteadyStep(600, 0.88, label = "Sweet spot"),
                FreeStep(120, routeId = "route-gpx-0001", label = "Ride out"),
            ),
        )
    private val easier = listOf(SteadyStep(600, 0.8, label = "Tempo"), FreeStep(120, routeId = "route-gpx-0001"))

    private fun planned(
        variant: PlannedVariant? = null,
        workout: Workout = primary,
    ) = PlannedWorkout("icu-sweet", Samples.TODAY, workout.name, PlanSource.INTERVALS_ICU, workout, variant)

    private fun response(vararg workouts: PlannedWorkout) =
        WorkoutsResponse(Samples.TODAY, Samples.TODAY, "${Samples.TODAY}T07:40:00Z", false, true, workouts.toList())

    @Test
    fun theContainerReadsAPlanWhoseWorkoutsCarryARouteAndAnEasierVersion() {
        val variant = PlannedVariant(easier, "Shorter, same road.", "0123456789abcdef")
        service.workoutsBody = PlanApiJson.encodeWorkoutsResponse(response(planned(variant)))
        val c = world.container()
        with(world) { c.configure() }
        c.refresh(RefreshTarget.Today)
        await("today's plan arrives") { c.workouts.state.value.value != null }
        assertNull("the plan is the service's, not 'not the service'", c.workouts.state.value.problem)
        val entry =
            c.workouts.state.value.value!!
                .workouts
                .single()
        assertEquals("route-gpx-0001", (entry.workout.steps.last() as FreeStep).routeId)
        assertEquals("Shorter, same road.", entry.variant?.note)
        assertEquals(easier, entry.variant?.steps)
        assertEquals(entry.id, entry.variantWorkout()?.id)
    }

    private fun today(workouts: WorkoutsResponse) =
        TodayUi.of(
            ResourceState(loaded = true, asked = true),
            fresh(Samples.metrics("2026-05-25")),
            fresh(workouts),
            RidesView(),
            ProfileState(ready = true, profile = Samples.profile()),
            FtpAnswers(),
            null,
            Samples.TODAY,
            TestClock.words,
            "overdrive.example.net",
        )

    private fun TodayContent.row() = checkNotNull(planned).single()

    @Test
    fun todayShowsAWorkoutWithARouteAndAnEasierVersionAsItShowedTheWorkoutBefore() {
        val flat = primary.copy(steps = primary.steps.dropLast(1) + FreeStep(120, label = "Ride out"))
        val plain = (today(response(planned(workout = flat))).body as Body.Content).value.row()
        val withKeys =
            (
                today(
                    response(planned(PlannedVariant(easier, "Shorter.", "0123456789abcdef"))),
                ).body as Body.Content
            ).value.row()
        // The route is a part of the step and the variant is offered on the tablet: the card says the same.
        assertEquals(plain.name, withKeys.name)
        assertEquals(plain.subtitle, withKeys.subtitle)
        assertNotNull(withKeys.profile)
    }

    @Test
    fun aRidesPlanWithAFreeStepOnARouteIsReadAndWrittenBackWithItsRouteId() {
        val detail = Samples.rideDetail()
        val plan = checkNotNull(detail.plan)
        val onRoute =
            detail.copy(
                plan =
                    plan.copy(
                        workout =
                            plan.workout.copy(
                                steps =
                                    listOf(FreeStep(600, routeId = "route-gpx-0001")) + plan.workout.steps.drop(1),
                            ),
                    ),
            )
        val text = MetricsApiJson.encodeRideDetail(onRoute)
        val read = MetricsApiJson.decodeRideDetail(text)
        assertEquals("route-gpx-0001", (checkNotNull(read.plan).workout.steps.first() as FreeStep).routeId)
        assertEquals(onRoute, read)
    }
}
