package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.model.TodayContent
import app.overdrive.companion.model.TodayUi
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesView
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.MustSay
import app.overdrive.core.workout.MustSayMoment
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The companion and the live coach's workout fields (stage A3, docs/adr/0038, 0039): the companion decodes the planned workouts with the same strict classes as the tablet, so a plan whose
 * workouts carry `live_coach`, `coach_brief` and `must_say` must read, and Today must say what it always said and show **neither the brief nor a line**. Before this stage a service that wrote
 * one made the companion say "That is not the Overdrive service": the companion of this release must be installed before the coach agent writes any of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LiveCoachWorkoutsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private val brief =
        "He starts intervals too hard and fades. Keep him smooth in the first two minutes. " +
            "Calm, technical tone."
    private val line = "First one. Ease into it."

    private val coached =
        Workout(
            schema = 1,
            id = "icu-sweet",
            name = "Sweet spot 3×10",
            steps =
                listOf(
                    RampStep(
                        300,
                        0.45,
                        0.65,
                        label = "Warm-up",
                        mustSay = listOf(MustSay(MustSayMoment.Second(60), "Spin up.")),
                    ),
                    SteadyStep(600, 0.88, label = "Sweet spot", mustSay = listOf(MustSay(MustSayMoment.Start, line))),
                    FreeStep(120, label = "Ride out"),
                ),
            liveCoach = true,
            coachBrief = brief,
        )

    private val plain = coached.copy(liveCoach = null, coachBrief = null, steps = coached.steps.map(::withoutLines))

    private fun withoutLines(step: app.overdrive.core.workout.Step) =
        when (step) {
            is RampStep -> step.copy(mustSay = null)
            is SteadyStep -> step.copy(mustSay = null)
            else -> step
        }

    private fun planned(workout: Workout) =
        PlannedWorkout(workout.id, Samples.TODAY, workout.name, PlanSource.INTERVALS_ICU, workout)

    private fun response(vararg workouts: PlannedWorkout) =
        WorkoutsResponse(Samples.TODAY, Samples.TODAY, "${Samples.TODAY}T07:40:00Z", false, true, workouts.toList())

    @Test
    fun theContainerReadsAPlanWhoseWorkoutCarriesTheFlagTheBriefAndLinesToSay() {
        val body = PlanApiJson.encodeWorkoutsResponse(response(planned(coached)))
        assertTrue(
            "the keys are on the wire",
            listOf("live_coach", "coach_brief", "must_say").all { body.contains(it) },
        )
        service.workoutsBody = body
        val c = world.container()
        with(world) { c.configure() }
        c.refresh(RefreshTarget.Today)
        await("today's plan arrives") { c.workouts.state.value.value != null }
        assertNull("the plan is the service's, not 'not the service'", c.workouts.state.value.problem)
        val entry =
            c.workouts.state.value.value!!
                .workouts
                .single()
        assertEquals(true, entry.workout.liveCoach)
        assertEquals(brief, entry.workout.coachBrief)
        assertEquals(coached.steps, entry.workout.steps)
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
    fun todayShowsAWorkoutThatAsksForTheCoachAsItShowedTheWorkoutBeforeAndNeverTheBriefOrALine() {
        val withCoach = (today(response(planned(coached))).body as Body.Content).value.row()
        val without = (today(response(planned(plain))).body as Body.Content).value.row()
        assertEquals(without.name, withCoach.name)
        assertEquals(without.subtitle, withCoach.subtitle)
        assertEquals(without.profile != null, withCoach.profile != null)
        // Nothing the card shows is the agent's words to the coach.
        val shown = listOfNotNull(withCoach.name, withCoach.subtitle, withCoach.toString())
        for (text in shown) {
            assertFalse(
                text,
                text.contains("too hard") || text.contains("technical tone") || text.contains("Ease into it"),
            )
        }
    }
}
