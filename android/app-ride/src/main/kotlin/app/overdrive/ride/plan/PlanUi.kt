package app.overdrive.ride.plan

import androidx.compose.runtime.Immutable
import app.overdrive.core.plan.PlanAgenda
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanProblem
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.Playability
import app.overdrive.core.plan.intensityFactor
import app.overdrive.core.plan.playability
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.ride.totalDurationSeconds
import app.overdrive.ride.rides.RiddenWorkout
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.UiFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a planned row says besides the workout: nothing, that it was ridden, or that it cannot be ridden here. */
enum class PlanMark { None, Done, Blocked }

/** One row of the Ready screen's "Planned" section (design/system.md §7.5, §6.1), made from a planned workout. */
@Immutable
data class PlanRow(
    val workout: PlannedWorkout,
    val title: String,
    /** The one line under the title: "Done 18:04 · …", the description, or the reason the row cannot be ridden. */
    val subtitle: String,
    val mark: PlanMark,
    /** "65 min", or null for a workout whose length is not worth computing (one the player cannot run). */
    val duration: String?,
    /** "IF 0.76", or null when the workout prescribes too little power to say. */
    val intensity: String?,
) {
    /** The player can run it: the row is a button. */
    val enabled: Boolean get() = mark != PlanMark.Blocked
}

/** The planned rows of one day after today. */
@Immutable
data class PlanDayUi(
    /** "Tomorrow", "Fri 20 Nov". */
    val label: String,
    val rows: List<PlanRow>,
)

/**
 * What the Ready screen's "Planned" section shows (spec §5.7, ADR 0017), made by [of] from the repository's [PlanState], the
 * rides already ridden and the clock. Pure: the screen only draws it, and every word on it is tested here.
 *
 * @property visible false when nothing is set up (no address and token, or the service has no plan source): the section is not
 * drawn and nothing is said about it.
 * @property status the one line under the section's label ("Updated 07:30", "Out of date · updated 07:30", the token wording), or
 * null; [statusIsAlert] puts the alert icon before it. [detail] is the reason on a second line.
 * @property today the workouts planned for today; the screen says "Nothing planned today" when there are none.
 * @property weekCount the workouts of the days after today (up to a week on), which the screen keeps collapsed until asked.
 */
@Immutable
data class PlanUi(
    val visible: Boolean = false,
    val today: List<PlanRow> = emptyList(),
    val week: List<PlanDayUi> = emptyList(),
    val status: String? = null,
    val statusIsAlert: Boolean = false,
    val detail: String? = null,
    val refreshing: Boolean = false,
) {
    val weekCount: Int get() = week.sumOf { it.rows.size }

    companion object {
        val Hidden = PlanUi()

        const val TOKEN_REJECTED = "The service did not accept the token. Check the token in Settings"

        /**
         * @param today the tablet's local date, `YYYY-MM-DD`.
         * @param nowMillis the wall clock, for "Updated 07:30" against "Updated 18 Nov 07:30".
         */
        fun of(
            state: PlanState,
            ridden: List<RiddenWorkout>,
            today: String,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
        ): PlanUi {
            if (!state.visible) return Hidden
            val agenda = PlanAgenda.of(state.workouts, today)
            val last = ridden.groupBy { it.workoutId }.mapValues { (_, rides) -> rides.maxOf { it.startEpochMillis } }

            fun row(workout: PlannedWorkout) = rowOf(workout, last[workout.id], zone, locale)
            val tomorrow = PlanCalendar.addDays(today, 1)
            val (status, alert, detail) = statusOf(state, nowMillis, zone, locale)
            return PlanUi(
                visible = true,
                today = agenda.today.map(::row),
                week =
                    agenda.thisWeek.map { day ->
                        PlanDayUi(dayLabel(day.date, tomorrow, locale), day.workouts.map(::row))
                    },
                status = status,
                statusIsAlert = alert,
                detail = detail,
                refreshing = state.refreshing,
            )
        }

        private fun rowOf(
            planned: PlannedWorkout,
            lastRideMillis: Long?,
            zone: ZoneId,
            locale: Locale,
        ): PlanRow {
            val workout = planned.workout
            val description = describe(planned)
            return when (val playability = workout.playability()) {
                is Playability.Unrunnable -> {
                    PlanRow(planned, planned.name, playability.reason, PlanMark.Blocked, null, null)
                }

                Playability.Runnable -> {
                    val done = lastRideMillis?.let { doneText(it, planned.date, zone, locale) }
                    PlanRow(
                        workout = planned,
                        title = planned.name,
                        subtitle = if (done == null) description else "$done · $description",
                        mark = if (done == null) PlanMark.None else PlanMark.Done,
                        duration = UiFormat.minutes(workout.flatSteps().totalDurationSeconds()),
                        intensity = workout.intensityFactor()?.let { "IF " + UiFormat.decimal(it, 2) },
                    )
                }
            }
        }

        /** The workout's own description (its first line), else the kinds of step it has: one plain line. */
        private fun describe(planned: PlannedWorkout): String {
            val line =
                planned.workout.description
                    ?.lineSequence()
                    ?.map { it.trim().replace(WHITESPACE, " ") }
                    ?.firstOrNull { it.isNotEmpty() }
            if (line != null) return line
            val kinds = WorkoutDescriber.kinds(planned.workout)
            return if (kinds == "free") "Free ride, gears" else kinds.replaceFirstChar { it.uppercase() }
        }

        /** "Done 18:04" when it was ridden on the day it is planned for, else with the date: "Done 17 Nov 18:04". */
        private fun doneText(
            startMillis: Long,
            plannedDate: String,
            zone: ZoneId,
            locale: Locale,
        ): String {
            val at = Instant.ofEpochMilli(startMillis).atZone(zone)
            val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(at)
            return if (at.toLocalDate().toString() == plannedDate) {
                "Done $clock"
            } else {
                "Done ${DateTimeFormatter.ofPattern("d MMM", locale).format(at)} $clock"
            }
        }

        private fun dayLabel(
            date: String,
            tomorrow: String,
            locale: Locale,
        ): String =
            if (date == tomorrow) {
                "Tomorrow"
            } else {
                DateTimeFormatter.ofPattern("EEE d MMM", locale).format(LocalDate.parse(date))
            }

        private data class Status(
            val line: String?,
            val alert: Boolean,
            val detail: String?,
        )

        private fun statusOf(
            state: PlanState,
            nowMillis: Long,
            zone: ZoneId,
            locale: Locale,
        ): Status {
            val updated = state.generatedAt?.let { updatedText(it, nowMillis, zone, locale) }
            val problem = state.problem
            return when {
                state.refreshing -> {
                    Status("Updating…", false, null)
                }

                problem == PlanProblem.TokenRejected -> {
                    Status(TOKEN_REJECTED, true, updated?.let { "Last updated $it" })
                }

                problem is PlanProblem.Failed -> {
                    if (state.loaded) {
                        Status("Out of date" + (updated?.let { " · updated $it" } ?: ""), true, problem.reason)
                    } else {
                        Status(problem.reason, true, null)
                    }
                }

                state.loaded && state.stale -> {
                    Status("Out of date · " + (updated?.let { "updated $it" } ?: "not updated yet"), true, null)
                }

                state.loaded && updated != null -> {
                    Status("Updated $updated", false, null)
                }

                else -> {
                    Status(null, false, null)
                }
            }
        }

        /** "07:30" for a time today, "18 Nov 07:30" for another day; [generatedAt] is the service's UTC instant. */
        private fun updatedText(
            generatedAt: String,
            nowMillis: Long,
            zone: ZoneId,
            locale: Locale,
        ): String? {
            val then =
                try {
                    Instant.parse(generatedAt).atZone(zone)
                } catch (e: java.time.format.DateTimeParseException) {
                    return null
                }
            val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(then)
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            return if (then.toLocalDate() == today) {
                clock
            } else {
                "${DateTimeFormatter.ofPattern("d MMM", locale).format(then)} $clock"
            }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
