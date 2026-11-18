package app.overdrive.core.plan

/** The workouts planned for one day, in the service's order (by id). */
public data class PlanDay(
    /** `YYYY-MM-DD`. */
    val date: String,
    val workouts: List<PlannedWorkout>,
)

/**
 * What the Ready screen lists from the plan on a given day (spec §5.7): [today]'s workouts, then [thisWeek], the days after
 * today up to a week from it, each day that has a workout in date order. What is planned for days already gone, or for
 * later than that, is not shown: the tablet fetches a fortnight ahead (spec §5.1) so that a tablet that stays offline for
 * a few days still has the coming week in its cache, and this is where the cache is read through the clock.
 */
public data class PlanAgenda(
    val today: List<PlannedWorkout>,
    val thisWeek: List<PlanDay>,
) {
    /** The workouts of [thisWeek], all days together. */
    public val thisWeekCount: Int get() = thisWeek.sumOf { it.workouts.size }

    public companion object {
        /** Days after today that "this week" covers: tomorrow to six days on. */
        public const val WEEK_DAYS: Int = 6

        /** @param today `YYYY-MM-DD`, the tablet's local date. */
        public fun of(
            workouts: List<PlannedWorkout>,
            today: String,
        ): PlanAgenda {
            val last = PlanCalendar.addDays(today, WEEK_DAYS)
            val upcoming =
                workouts
                    .filter { it.date > today && it.date <= last }
                    .groupBy { it.date }
                    .entries
                    .sortedBy { it.key }
                    .map { (date, ofDay) -> PlanDay(date, ofDay) }
            return PlanAgenda(workouts.filter { it.date == today }, upcoming)
        }
    }
}
