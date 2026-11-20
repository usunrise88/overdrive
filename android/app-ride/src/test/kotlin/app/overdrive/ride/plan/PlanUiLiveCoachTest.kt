package app.overdrive.ride.plan

import app.overdrive.core.plan.PlanProblem
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedVariant
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.workout.MustSay
import app.overdrive.core.workout.MustSayMoment
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * What a planned row says of the live coach (spec §12.1, docs/adr/0039): "Live coach", with "needs the connection" when the last refresh could not reach the service, "Live coach off"
 * when the rider switched it off, and nothing for a workout that did not ask; and **never** the agent's brief or a line it wants said.
 */
class PlanUiLiveCoachTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val today = "2026-11-18"
    private val brief = "He starts too hard and fades: keep him smooth, calm tone."
    private val line = "First one, ease into it."

    private fun workout(
        coach: Boolean?,
        description: String? = "Three 10-minute efforts.",
    ) = Workout(
        schema = 1,
        id = "icu-6001",
        name = "Threshold progression",
        description = description,
        steps =
            listOf(
                SteadyStep(duration = 600, target = 0.95, mustSay = listOf(MustSay(MustSayMoment.Start, line))),
            ),
        liveCoach = coach,
        coachBrief = brief.takeIf { coach != null },
    )

    private fun ui(
        workout: Workout,
        problem: PlanProblem? = null,
        liveCoach: Boolean = true,
        variant: PlannedVariant? = null,
    ): PlanUi {
        val planned = PlannedWorkout(workout.id, today, workout.name, PlanSource.INTERVALS_ICU, workout, variant)
        val state =
            PlanState(
                true,
                listOf(planned),
                "2026-11-18T06:30:00Z",
                loaded = true,
                stale = problem != null,
                problem = problem,
            )
        return PlanUi.of(state, emptyList(), today, 0L, zone, Locale.ENGLISH, liveCoach = liveCoach)
    }

    @Test
    fun aWorkoutThatAsksForTheCoachSaysSoAfterItsDescription() {
        assertEquals("Three 10-minute efforts. · Live coach", ui(workout(true)).today.single().subtitle)
    }

    @Test
    fun withNoDescriptionItIsTheKindsOfStepThenTheCoach() {
        assertEquals("Steady · Live coach", ui(workout(true, description = null)).today.single().subtitle)
    }

    @Test
    fun offlineItSaysThatTheCoachNeedsTheConnection() {
        val subtitle = ui(workout(true), PlanProblem.Failed("No network connection")).today.single().subtitle
        assertEquals("Three 10-minute efforts. · Live coach · needs the connection", subtitle)
    }

    @Test
    fun aRejectedTokenIsNotTheTabletBeingOffline() {
        assertEquals(
            "Three 10-minute efforts. · Live coach",
            ui(workout(true), PlanProblem.TokenRejected).today.single().subtitle,
        )
    }

    @Test
    fun aRiderWhoSwitchedTheCoachOffIsToldItIsOffAndNothingAboutTheConnection() {
        val subtitle =
            ui(
                workout(true),
                PlanProblem.Failed("No network connection"),
                liveCoach = false,
            ).today.single().subtitle
        assertEquals("Three 10-minute efforts. · Live coach off", subtitle)
    }

    @Test
    fun aWorkoutThatDoesNotAskForTheCoachIsAsItWas() {
        assertEquals("Three 10-minute efforts.", ui(workout(null)).today.single().subtitle)
        assertEquals("Three 10-minute efforts.", ui(workout(false)).today.single().subtitle)
        assertEquals(
            "Three 10-minute efforts.",
            ui(workout(false), PlanProblem.Failed("No network connection")).today.single().subtitle,
        )
    }

    @Test
    fun theEasierVersionSaysItToo() {
        val variant = PlannedVariant(listOf(SteadyStep(duration = 600, target = 0.9)), "Shorter.", "69140a858e5fc8af")
        val row = ui(workout(true), variant = variant).today.single()
        assertEquals("Shorter. · Live coach", row.variant?.subtitle)
    }

    @Test
    fun theBriefAndTheLinesAreInNoTextOfAnyRow() {
        val variant = PlannedVariant(listOf(SteadyStep(duration = 600, target = 0.9)), "Shorter.", "69140a858e5fc8af")
        for (problem in listOf(null, PlanProblem.Failed("No network connection"))) {
            val row = ui(workout(true), problem, variant = variant).today.single()
            val texts =
                listOfNotNull(
                    row.title,
                    row.subtitle,
                    row.duration,
                    row.intensity,
                    row.recovery,
                    row.variant?.title,
                    row.variant?.subtitle,
                    row.variant?.note,
                )
            for (text in texts) {
                assertFalse(text, text.contains("smooth") || text.contains("fades") || text.contains("ease into it"))
            }
        }
        assertTrue(ui(workout(true)).today.isNotEmpty())
        assertNull(ui(workout(true)).today.single().recovery)
    }
}
