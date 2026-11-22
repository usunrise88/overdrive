package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.powerskills.POWER_SKILLS_MAX_DAYS
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** One day of the picker's grid (design/companion.md §4.14, "Custom range"). */
@Immutable
data class DayCell(
    val date: LocalDate,
    val hasRide: Boolean,
    val isToday: Boolean,
    /** After today: text 3 at 55%, cannot be chosen. */
    val future: Boolean,
    val isFirst: Boolean,
    val isLast: Boolean,
    /** Between the two ends: the surface-3 band. */
    val inBetween: Boolean,
) {
    /** "Monday 2 November, has a ride, first day" (design/companion.md §8). */
    fun description(words: Words): String =
        listOfNotNull(
            words.dayLong(date.toString()),
            "has a ride".takeIf { hasRide },
            "today".takeIf { isToday },
            "first day".takeIf { isFirst && !isLast },
            "last day".takeIf { isLast && !isFirst },
            "first and last day".takeIf { isFirst && isLast },
            "not available".takeIf { future },
        ).joinToString(", ")
}

/** What the picker holds while it is open: the month shown and the ends chosen so far. The window changes only when **Show** applies them. */
@Immutable
data class PickerState(
    val month: YearMonth,
    val first: LocalDate? = null,
    val last: LocalDate? = null,
) {
    val complete: Boolean get() = first != null && last != null
}

/**
 * The Custom range's date picker as pure functions (design/companion.md §4.14): tap rules, the month grid with its ride dots, the choice
 * line and the Show label. The sheet only draws a [PickerState]; **Cancel**, Back, the scrim and a swipe drop it without applying.
 */
object RangePicker {
    /** The sheet opens on the month of the current custom end, else this month. */
    fun open(
        today: LocalDate,
        currentEnd: LocalDate?,
    ): PickerState = PickerState(YearMonth.from(currentEnd ?: today))

    /**
     * A tap on [day]: the first tap is the start; the second, on or after the start, the end (start and end may be the same day); a tap
     * before the start makes that day the new start; a tap after a complete range starts again. Days after [today] cannot be chosen, and
     * a range longer than the service serves (three years) starts again from the tap.
     */
    fun tap(
        state: PickerState,
        day: LocalDate,
        today: LocalDate,
    ): PickerState {
        if (day.isAfter(today)) return state
        val first = state.first
        return when {
            first == null || state.last != null -> state.copy(first = day, last = null)
            day.isBefore(first) -> state.copy(first = day)
            ChronoUnit.DAYS.between(first, day) + 1 > POWER_SKILLS_MAX_DAYS -> state.copy(first = day, last = null)
            else -> state.copy(last = day)
        }
    }

    fun previous(state: PickerState): PickerState = state.copy(month = state.month.minusMonths(1))

    /** The right chevron is off on the current month. */
    fun canGoNext(
        state: PickerState,
        today: LocalDate,
    ): Boolean = state.month.isBefore(YearMonth.from(today))

    fun next(
        state: PickerState,
        today: LocalDate,
    ): PickerState = if (canGoNext(state, today)) state.copy(month = state.month.plusMonths(1)) else state

    /** The month as rows of seven days Monday to Sunday; null pads the first and the last row. */
    fun grid(
        state: PickerState,
        today: LocalDate,
        rideDays: Set<LocalDate>,
    ): List<List<DayCell?>> {
        val first = state.month.atDay(1)
        val lead = first.dayOfWeek.value - DayOfWeek.MONDAY.value
        val cells = ArrayList<DayCell?>()
        repeat(lead) { cells += null }
        for (n in 1..state.month.lengthOfMonth()) {
            val day = state.month.atDay(n)
            val a = state.first
            val b = state.last
            cells +=
                DayCell(
                    date = day,
                    hasRide = day in rideDays,
                    isToday = day == today,
                    future = day.isAfter(today),
                    isFirst = day == a,
                    isLast = day == b,
                    inBetween = a != null && b != null && day.isAfter(a) && day.isBefore(b),
                )
        }
        while (cells.size % DAYS != 0) cells += null
        return cells.chunked(DAYS)
    }

    /** The first line under the title (a polite live region): the choice so far. */
    fun choice(
        state: PickerState,
        rides: Int,
        words: Words,
    ): String {
        val a = state.first ?: return "Choose the first day"
        val b = state.last ?: return "From ${words.dayShort(a.toString())} · choose the last day"
        val days = ChronoUnit.DAYS.between(a, b) + 1
        return "${words.dayShort(
            a.toString(),
        )} – ${words.dayShort(
            b.toString(),
        )} · $days ${if (days == 1L) "day" else "days"} · ${SkillWindows.rides(rides)}"
    }

    /** "Show 2 Nov – 15 Nov", or "Show" while the range is not complete (the button is then off). */
    fun showLabel(
        state: PickerState,
        words: Words,
    ): String {
        val a = state.first
        val b = state.last
        return if (a == null ||
            b == null
        ) {
            "Show"
        } else {
            "Show ${words.dayShort(a.toString())} – ${words.dayShort(b.toString())}"
        }
    }

    /** The tablet rides in the range, counted from the cached list of rides (their local days). */
    fun ridesIn(
        state: PickerState,
        rideDates: List<LocalDate>,
    ): Int {
        val a = state.first ?: return 0
        val b = state.last ?: a
        return rideDates.count { !it.isBefore(a) && !it.isAfter(b) }
    }

    private const val DAYS = 7
}
