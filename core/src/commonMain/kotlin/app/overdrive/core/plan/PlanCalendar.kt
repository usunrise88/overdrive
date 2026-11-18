package app.overdrive.core.plan

/**
 * Date arithmetic on the plan API's dates (`YYYY-MM-DD`, in the rider's local time) without a platform calendar: core has
 * none, and the tablet only needs "the day after", "N days on" and the day of the week. The reading direction
 * ([PlanDates.epochDay]) is in `PlanApi.kt`; this is the other way and the arithmetic built on both.
 */
public object PlanCalendar {
    /** The date [days] after [date] (before it when negative). @throws IllegalArgumentException if [date] is not a real date. */
    public fun addDays(
        date: String,
        days: Int,
    ): String = ofEpochDay(epochDayOf(date) + days)

    /** The date [epochDay] days after 1970-01-01, written `YYYY-MM-DD`. @throws IllegalArgumentException outside years 1000 to 9999. */
    public fun ofEpochDay(epochDay: Long): String {
        // Civil date from days, after Howard Hinnant's algorithm (the inverse of PlanDates.epochDay).
        val z = epochDay + DAYS_FROM_YEAR_0_TO_EPOCH
        val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
        val dayOfEra = z - era * DAYS_PER_ERA
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shiftedMonth = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
        val month = if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9
        val year = yearOfEra + era * YEARS_PER_ERA + if (month <= 2) 1 else 0
        require(year in MIN_YEAR..MAX_YEAR) { "Year $year is outside what the plan API writes" }
        return "${year.toString().padStart(
            4,
            '0',
        )}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /** 1 for Monday to 7 for Sunday. @throws IllegalArgumentException if [date] is not a real date. */
    public fun dayOfWeek(date: String): Int = floorMod7(epochDayOf(date) + THURSDAY_OFFSET) + 1

    /** Days from [from] to [to]: positive when [to] is later. */
    public fun daysBetween(
        from: String,
        to: String,
    ): Long = epochDayOf(to) - epochDayOf(from)

    private fun epochDayOf(date: String): Long =
        requireNotNull(PlanDates.epochDay(date)) { "Not a calendar date: '$date'" }

    private fun floorMod7(value: Long): Int = (((value % WEEK) + WEEK) % WEEK).toInt()

    private const val MIN_YEAR = 1000L
    private const val MAX_YEAR = 9999L
    private const val YEARS_PER_ERA = 400L
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_FROM_YEAR_0_TO_EPOCH = 719_468L

    /** 1970-01-01 was a Thursday, the fourth day of a week that starts on Monday: day 0 plus 3 is index 3 (0 = Monday). */
    private const val THURSDAY_OFFSET = 3L
    private const val WEEK = 7L
}
