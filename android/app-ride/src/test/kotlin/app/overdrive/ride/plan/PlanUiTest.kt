package app.overdrive.ride.plan

import app.overdrive.core.plan.PlanProblem
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Step
import app.overdrive.core.workout.Workout
import app.overdrive.ride.rides.RiddenWorkout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Every word of the Planned section (spec §5.7): what a row says, what the status line says, and what is not shown. */
class PlanUiTest {
    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val locale = Locale.ENGLISH
    private val today = "2026-11-18"
    private val now = millis("2026-11-18T09:00:00")

    private fun millis(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun workout(
        id: String,
        name: String,
        description: String? = null,
        steps: List<Step> = listOf(SteadyStep(duration = 2700, target = 0.55)),
    ) = Workout(1, id, name, description, steps)

    private fun planned(
        date: String,
        workout: Workout,
    ) = PlannedWorkout(workout.id, date, workout.name, PlanSource.INTERVALS_ICU, workout)

    private val sweetSpot =
        workout(
            "icu-1001",
            "Sweet spot 3x10",
            "Three 10-minute efforts with 5-minute recoveries.",
            listOf(
                RampStep(duration = 600, from = 0.45, to = 0.7),
                RepeatStep(
                    count = 3,
                    steps = listOf(SteadyStep(duration = 600, target = 0.88), SteadyStep(duration = 300, target = 0.5)),
                ),
                RampStep(duration = 300, from = 0.55, to = 0.4),
            ),
        )

    private fun state(
        workouts: List<PlannedWorkout> = emptyList(),
        generatedAt: String? = "2026-11-18T06:30:00Z",
        loaded: Boolean = true,
        stale: Boolean = false,
        problem: PlanProblem? = null,
        refreshing: Boolean = false,
        visible: Boolean = true,
    ) = PlanState(visible, workouts, generatedAt, loaded, stale, problem, refreshing)

    private fun ui(
        state: PlanState,
        ridden: List<RiddenWorkout> = emptyList(),
    ) = PlanUi.of(state, ridden, today, now, zone, locale)

    // region rows

    @Test
    fun `a row is the name, the description, the length in minutes and the intensity factor`() {
        val ui = ui(state(listOf(planned(today, sweetSpot))))
        val row = ui.today.single()
        assertEquals("Sweet spot 3x10", row.title)
        assertEquals("Three 10-minute efforts with 5-minute recoveries.", row.subtitle)
        assertEquals("60 min", row.duration)
        assertEquals("IF 0.76", row.intensity)
        assertEquals(PlanMark.None, row.mark)
        assertTrue(row.enabled)
    }

    @Test
    fun `with no description the row says what kinds of step the workout has`() {
        val spin = workout("icu-2", "Easy spin")
        val mixed =
            workout("icu-3", "Mixed", null, listOf(SteadyStep(duration = 600, target = 0.6), FreeStep(duration = 600)))
        val free = workout("icu-4", "Free ride", null, listOf(FreeStep(duration = 3600)))
        val ui = ui(state(listOf(planned(today, spin), planned(today, mixed), planned(today, free))))
        assertEquals(listOf("Steady", "Steady, free", "Free ride, gears"), ui.today.map { it.subtitle })
        assertNull("a free ride prescribes no power to summarise", ui.today[2].intensity)
        assertEquals("IF 0.55", ui.today[0].intensity)
    }

    @Test
    fun `a description is one line of its first words, however it was written`() {
        val long =
            workout(
                "icu-5",
                "Long",
                "\n  - 10m   ramp 50-75%\n- 3x10m 88%\n",
                listOf(SteadyStep(duration = 600, target = 0.7)),
            )
        assertEquals("- 10m ramp 50-75%", ui(state(listOf(planned(today, long)))).today.single().subtitle)
    }

    @Test
    fun `a workout the player cannot run is a blocked row with the reason and no length`() {
        val huge = workout("icu-6", "Epic", null, listOf(FreeStep(duration = 13 * 3600)))
        val bomb =
            workout("icu-7", "Bomb", null, listOf(RepeatStep(Int.MAX_VALUE, listOf(SteadyStep(Int.MAX_VALUE, 0.5)))))
        val ui = ui(state(listOf(planned(today, huge), planned(today, bomb))))
        for (row in ui.today) {
            assertEquals(PlanMark.Blocked, row.mark)
            assertFalse(row.enabled)
            assertNull(row.duration)
            assertNull(row.intensity)
        }
        assertEquals(listOf("Longer than 12 hours", "Longer than 12 hours"), ui.today.map { it.subtitle })
    }

    // endregion

    // region done

    @Test
    fun `a ride of that workout on its day marks it done with the time, and it can still be ridden`() {
        val ridden = listOf(RiddenWorkout("icu-1001", millis("2026-11-18T18:04:00")))
        val row = ui(state(listOf(planned(today, sweetSpot))), ridden).today.single()
        assertEquals(PlanMark.Done, row.mark)
        assertEquals("Done 18:04 · Three 10-minute efforts with 5-minute recoveries.", row.subtitle)
        assertTrue("riding it again is allowed", row.enabled)
        assertEquals("60 min", row.duration)
    }

    @Test
    fun `a ride on another day says which day, and the newest ride is the one shown`() {
        val ridden =
            listOf(
                RiddenWorkout("icu-1001", millis("2026-11-16T07:30:00")),
                RiddenWorkout("icu-1001", millis("2026-11-17T19:15:00")),
            )
        val row = ui(state(listOf(planned(today, sweetSpot))), ridden).today.single()
        assertTrue(row.subtitle, row.subtitle.startsWith("Done 17 Nov 19:15 · "))
    }

    @Test
    fun `only a ride of that very workout counts`() {
        val ridden = listOf(RiddenWorkout("icu-9999", millis("2026-11-18T08:00:00")))
        assertEquals(PlanMark.None, ui(state(listOf(planned(today, sweetSpot))), ridden).today.single().mark)
    }

    @Test
    fun `the time is the tablet's zone, not UTC`() {
        // 23:30 UTC on the 17th is 00:30 on the 18th in Paris, the day the workout is planned for.
        val late = LocalDateTime.parse("2026-11-17T23:30:00").toInstant(ZoneOffset.UTC).toEpochMilli()
        val row = ui(state(listOf(planned(today, sweetSpot))), listOf(RiddenWorkout("icu-1001", late))).today.single()
        assertTrue(row.subtitle, row.subtitle.startsWith("Done 00:30 · "))
    }

    // endregion

    // region today and this week

    @Test
    fun `today's rows first, then the next six days, tomorrow by name, nothing earlier or later`() {
        fun w(id: String) = workout(id, "Workout $id")
        val ui =
            ui(
                state(
                    listOf(
                        planned("2026-11-17", w("icu-0")),
                        planned("2026-11-18", w("icu-1")),
                        planned("2026-11-19", w("icu-2")),
                        planned("2026-11-22", w("icu-3")),
                        planned("2026-11-22", w("icu-4")),
                        planned("2026-11-24", w("icu-5")),
                        planned("2026-11-25", w("icu-6")),
                    ),
                ),
            )
        assertEquals(listOf("Workout icu-1"), ui.today.map { it.title })
        assertEquals(listOf("Tomorrow", "Sun 22 Nov", "Tue 24 Nov"), ui.week.map { it.label })
        assertEquals(listOf("Workout icu-3", "Workout icu-4"), ui.week[1].rows.map { it.title })
        assertEquals(4, ui.weekCount)
    }

    @Test
    fun `nothing planned is an empty list, not an absent section`() {
        val ui = ui(state())
        assertTrue(ui.visible)
        assertTrue(ui.today.isEmpty() && ui.week.isEmpty())
        assertEquals(0, ui.weekCount)
    }

    // endregion

    // region visibility

    @Test
    fun `a plan that is not visible is nothing at all, whatever else it holds`() {
        assertSame(PlanUi.Hidden, ui(PlanState()))
        assertSame(PlanUi.Hidden, ui(state(listOf(planned(today, sweetSpot)), visible = false)))
        assertFalse(PlanUi.Hidden.visible)
    }

    // endregion

    // region the status line

    @Test
    fun `a good list says when it was updated, as a time today and as a date and time another day`() {
        assertEquals("Updated 07:30", ui(state()).status)
        assertFalse(ui(state()).statusIsAlert)
        assertNull(ui(state()).detail)
        val yesterday = ui(state(generatedAt = "2026-11-17T20:15:00Z"))
        assertEquals("Updated 17 Nov 21:15", yesterday.status)
    }

    @Test
    fun `a list the service says is stale is out of date and gives its age`() {
        val ui = ui(state(stale = true, generatedAt = "2026-11-17T20:15:00Z"))
        assertEquals("Out of date · updated 17 Nov 21:15", ui.status)
        assertTrue(ui.statusIsAlert)
        assertNull(ui.detail)
        val never = ui(state(stale = true, generatedAt = null))
        assertEquals("Out of date · not updated yet", never.status)
        assertTrue(never.statusIsAlert)
    }

    @Test
    fun `a failed refresh keeps the list, says it may be out of date and why`() {
        val ui = ui(state(stale = true, problem = PlanProblem.Failed("Service unreachable")))
        assertEquals("Out of date · updated 07:30", ui.status)
        assertEquals("Service unreachable", ui.detail)
        assertTrue(ui.statusIsAlert)
    }

    @Test
    fun `no list and no network says the reason alone`() {
        val ui = ui(state(loaded = false, generatedAt = null, problem = PlanProblem.Failed("Service unreachable")))
        assertEquals("Service unreachable", ui.status)
        assertTrue(ui.statusIsAlert)
        assertNull(ui.detail)
    }

    @Test
    fun `a rejected token says it in the words of the sync settings`() {
        val ui = ui(state(stale = true, problem = PlanProblem.TokenRejected))
        assertEquals("The service did not accept the token. Check the token in Settings", ui.status)
        assertTrue(
            "the same sentence as Settings, Sync: " + SYNC_LINE,
            ui.status!!.startsWith(SYNC_LINE.substringBefore(". ")),
        )
        assertTrue(ui.statusIsAlert)
        assertEquals("Last updated 07:30", ui.detail)
        val noList = ui(state(loaded = false, generatedAt = null, problem = PlanProblem.TokenRejected))
        assertEquals(PlanUi.TOKEN_REJECTED, noList.status)
        assertNull(noList.detail)
    }

    @Test
    fun `while it asks the line says so and the refresh button knows`() {
        val ui = ui(state(refreshing = true))
        assertEquals("Updating…", ui.status)
        assertTrue(ui.refreshing)
        assertFalse(ui.statusIsAlert)
    }

    @Test
    fun `a time the service wrote badly is no time rather than a crash`() {
        assertNull(ui(state(generatedAt = "yesterday")).status)
        assertEquals("Out of date · not updated yet", ui(state(stale = true, generatedAt = "yesterday")).status)
    }

    // endregion

    private companion object {
        /** The line Settings, Sync shows for a rejected token (`SyncUi`). */
        const val SYNC_LINE = "The service did not accept the token. Check the token above"
    }
}
