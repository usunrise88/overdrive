package app.overdrive.core.plan

import app.overdrive.core.plan.PlanSamples.planned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the Ready screen lists on a given day: today, then the days up to a week on, nothing from the past. */
class PlanAgendaTest {
    private val workouts =
        listOf(
            planned("icu-0", "2026-11-17"),
            planned("icu-1", "2026-11-18"),
            planned("icu-2", "2026-11-18"),
            planned("icu-3", "2026-11-19"),
            planned("icu-4", "2026-11-22"),
            planned("icu-5", "2026-11-22"),
            planned("icu-6", "2026-11-24"),
            planned("icu-7", "2026-11-25"),
            planned("icu-8", "2026-12-01"),
        )

    @Test
    fun `today first then the next six days by date nothing earlier or later`() {
        val agenda = PlanAgenda.of(workouts, "2026-11-18")
        assertEquals(listOf("icu-1", "icu-2"), agenda.today.map { it.id })
        assertEquals(listOf("2026-11-19", "2026-11-22", "2026-11-24"), agenda.thisWeek.map { it.date })
        assertEquals(listOf("icu-4", "icu-5"), agenda.thisWeek[1].workouts.map { it.id })
        assertEquals(4, agenda.thisWeekCount)
    }

    @Test
    fun `the same cache read a few days later is read through the clock`() {
        val agenda = PlanAgenda.of(workouts, "2026-11-22")
        assertEquals(listOf("icu-4", "icu-5"), agenda.today.map { it.id })
        assertEquals(listOf("2026-11-24", "2026-11-25"), agenda.thisWeek.map { it.date })
    }

    @Test
    fun `an empty plan is an empty agenda`() {
        val agenda = PlanAgenda.of(emptyList(), "2026-11-18")
        assertTrue(agenda.today.isEmpty() && agenda.thisWeek.isEmpty())
        assertEquals(0, agenda.thisWeekCount)
    }

    @Test
    fun `the week crosses a month and a year`() {
        val plan =
            listOf(planned("icu-1", "2027-01-02"), planned("icu-2", "2026-12-31"), planned("icu-3", "2027-01-05"))
        val agenda = PlanAgenda.of(plan, "2026-12-30")
        assertEquals(listOf("2026-12-31", "2027-01-02", "2027-01-05"), agenda.thisWeek.map { it.date })
    }
}
