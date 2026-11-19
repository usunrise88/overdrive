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
import app.overdrive.core.route.RouteLibrary
import app.overdrive.core.route.RouteStatus
import app.overdrive.core.workout.Step
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.wantsLiveCoach
import app.overdrive.ride.rides.RiddenWorkout
import app.overdrive.ride.routes.RouteChoiceUi
import app.overdrive.ride.routes.RoutesView
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
    /** The easier version the agent wrote, offered right under this row: one tap chooses it (spec §11.5). Null when there is none. */
    val variant: VariantRow? = null,
    /** "Recovery: caution", beside the choice, on a row of today that has an easier version; else null (spec §11.5). */
    val recovery: String? = null,
) {
    /** The player can run it: the row is a button. */
    val enabled: Boolean get() = mark != PlanMark.Blocked
}

/**
 * The easier version of a planned workout as a row under it (spec §11.5): the planned workout with the variant's steps, so the ride keeps
 * the planned workout's id; its name is "Easier version" and the **note** the agent wrote with it is the line under the name, else what kinds of
 * step it has.
 */
@Immutable
data class VariantRow(
    /** What the ride plays: the planned workout's id and name, the variant's steps. */
    val workout: Workout,
    val title: String,
    val subtitle: String,
    val mark: PlanMark,
    val duration: String?,
    val intensity: String?,
    /** The agent's note, kept for the detail beside the Start button. */
    val note: String?,
    /**
     * The service's hash of the structure of the primary with the variant's steps: a variant ride is a session of its own (ADR 0036), so
     * this, and not the planned workout's, is what the previous attempt of the ride is asked for (spec §11.7, docs/adr/0034).
     */
    val structureHash: String? = null,
) {
    val enabled: Boolean get() = mark != PlanMark.Blocked

    companion object {
        const val TITLE = "Easier version"
    }
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
            routes: RoutesView = RoutesView.None,
            recovery: String? = null,
            liveCoach: Boolean = true,
        ): PlanUi {
            if (!state.visible) return Hidden
            val agenda = PlanAgenda.of(state.workouts, today)
            val last = ridden.groupBy { it.workoutId }.mapValues { (_, rides) -> rides.maxOf { it.startEpochMillis } }

            // The last refresh could not reach the service: the tablet is offline as far as the live coach is concerned (docs/adr/0039).
            val offline = state.problem is PlanProblem.Failed

            fun row(workout: PlannedWorkout) =
                rowOf(
                    workout,
                    last[workout.id],
                    zone,
                    locale,
                    routes,
                    recovery.takeIf { workout.date == today },
                    LiveCoachNote(liveCoach, offline),
                )
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
            routes: RoutesView,
            recovery: String?,
            coach: LiveCoachNote,
        ): PlanRow {
            val workout = planned.workout
            val description = describe(planned)
            val variant = variantOf(planned, routes, coach)
            return when (val playability = workout.playability()) {
                is Playability.Unrunnable -> {
                    PlanRow(
                        planned,
                        planned.name,
                        playability.reason,
                        PlanMark.Blocked,
                        null,
                        null,
                        variant,
                        recovery.takeIf {
                            variant !=
                                null
                        },
                    )
                }

                Playability.Runnable -> {
                    val done = lastRideMillis?.let { doneText(it, planned.date, zone, locale) }
                    PlanRow(
                        workout = planned,
                        title = planned.name,
                        subtitle =
                            listOfNotNull(
                                done,
                                description,
                                routeText(workout.steps, routes),
                                coach.textFor(workout),
                            ).joinToString(" · "),
                        mark = if (done == null) PlanMark.None else PlanMark.Done,
                        duration = UiFormat.minutes(workout.flatSteps().totalDurationSeconds()),
                        intensity = workout.intensityFactor()?.let { "IF " + UiFormat.decimal(it, 2) },
                        variant = variant,
                        recovery = recovery.takeIf { variant != null },
                    )
                }
            }
        }

        /** The easier version as a row, or null when the planned workout has none. */
        private fun variantOf(
            planned: PlannedWorkout,
            routes: RoutesView,
            coach: LiveCoachNote,
        ): VariantRow? {
            val variant = planned.variant ?: return null
            val workout = planned.variantWorkout() ?: return null
            val note = variant.note
            val kinds = WorkoutDescriber.kinds(workout).replaceFirstChar { it.uppercase() }
            val subtitle =
                listOfNotNull(
                    note ?: kinds,
                    routeText(variant.steps, routes),
                    coach.textFor(workout),
                ).joinToString(" · ")
            return when (val playability = workout.playability()) {
                is Playability.Unrunnable -> {
                    VariantRow(
                        workout,
                        VariantRow.TITLE,
                        playability.reason,
                        PlanMark.Blocked,
                        null,
                        null,
                        note,
                        variant.structureHash,
                    )
                }

                Playability.Runnable -> {
                    VariantRow(
                        workout = workout,
                        title = VariantRow.TITLE,
                        subtitle = subtitle,
                        mark = PlanMark.None,
                        duration = UiFormat.minutes(workout.flatSteps().totalDurationSeconds()),
                        intensity = workout.intensityFactor()?.let { "IF " + UiFormat.decimal(it, 2) },
                        note = note,
                        structureHash = variant.structureHash,
                    )
                }
            }
        }

        /**
         * The route a workout's free steps name, in plain words (spec §11.4): "Route: North road" when the points are on the tablet (a ride
         * on them needs no network), **"Route not downloaded yet"** when one is not (the ride would be flat there), nothing for a workout
         * that names none. A workout that names two or more says "Routes: A, B".
         */
        fun routeText(
            steps: List<Step>,
            routes: RoutesView,
        ): String? {
            val ids = RouteLibrary.routeIdsOf(steps)
            if (ids.isEmpty()) return null
            if (ids.any { routes.statusOf(it) != RouteStatus.READY }) return RouteChoiceUi.NOT_DOWNLOADED
            val names = ids.mapNotNull(routes::nameOf)
            return (if (names.size == 1) "Route: " else "Routes: ") + names.joinToString(", ")
        }

        /**
         * What a row says of the live coach (spec §12.1, docs/adr/0039): nothing for a workout that does not ask for it, **"Live coach"** for one that does,
         * with **"needs the connection"** when the tablet could not reach the service at its last refresh, and **"Live coach off"** when the rider switched it
         * off in Settings (then nothing about the connection: it is not going to be asked). One short part of the subtitle; the `coach_brief` and the lines the
         * coach is to say are never shown.
         */
        class LiveCoachNote(
            private val on: Boolean,
            private val offline: Boolean,
        ) {
            fun textFor(workout: Workout): String? =
                when {
                    !workout.wantsLiveCoach -> null
                    !on -> "Live coach off"
                    offline -> "Live coach · needs the connection"
                    else -> "Live coach"
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
