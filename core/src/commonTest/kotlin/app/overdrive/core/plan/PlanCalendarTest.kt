package app.overdrive.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The date arithmetic the plan needs, without a platform calendar. */
class PlanCalendarTest {
    @Test
    fun `adds days across months, years and leap days`() {
        assertEquals("2026-11-19", PlanCalendar.addDays("2026-11-18", 1))
        assertEquals("2026-12-01", PlanCalendar.addDays("2026-11-18", 13))
        assertEquals("2027-01-01", PlanCalendar.addDays("2026-12-31", 1))
        assertEquals("2028-02-29", PlanCalendar.addDays("2028-02-28", 1))
        assertEquals("2028-03-01", PlanCalendar.addDays("2028-02-28", 2))
        assertEquals("2027-03-01", PlanCalendar.addDays("2027-02-28", 1))
        assertEquals("2026-11-17", PlanCalendar.addDays("2026-11-18", -1))
        assertEquals("2025-12-31", PlanCalendar.addDays("2026-01-01", -1))
        assertEquals("2026-11-18", PlanCalendar.addDays("2026-11-18", 0))
    }

    @Test
    fun `names the weekday, Monday 1 to Sunday 7`() {
        assertEquals(3, PlanCalendar.dayOfWeek("2026-11-18"), "a Wednesday")
        assertEquals(1, PlanCalendar.dayOfWeek("2026-11-16"))
        assertEquals(7, PlanCalendar.dayOfWeek("2026-11-22"))
        assertEquals(4, PlanCalendar.dayOfWeek("1970-01-01"), "a Thursday")
        assertEquals(2, PlanCalendar.dayOfWeek("2000-02-29"), "a Tuesday")
    }

    @Test
    fun `counts days between`() {
        assertEquals(13, PlanCalendar.daysBetween("2026-11-18", "2026-12-01"))
        assertEquals(-1, PlanCalendar.daysBetween("2026-11-18", "2026-11-17"))
        assertEquals(366, PlanCalendar.daysBetween("2027-01-01", "2028-01-02"))
    }

    @Test
    fun `refuses what is not a date and what leaves four digits`() {
        assertFailsWith<IllegalArgumentException> { PlanCalendar.addDays("2026-02-30", 1) }
        assertFailsWith<IllegalArgumentException> { PlanCalendar.addDays("soon", 1) }
        assertFailsWith<IllegalArgumentException> { PlanCalendar.addDays("9999-12-31", 1) }
        assertFailsWith<IllegalArgumentException> { PlanCalendar.addDays("1000-01-01", -1) }
    }
}
