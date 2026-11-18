package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.companion.wellness.RecoveryFactData
import app.overdrive.companion.wellness.RecoveryLevel
import app.overdrive.companion.wellness.RecoveryState
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.Playability
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.plan.intensityFactor
import app.overdrive.core.plan.plannedLoad
import app.overdrive.core.plan.playability
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesView
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.ride.totalDurationSeconds
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.uikit.RecoveryLook
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.chart.ProfileStep

/** The recovery card as the screen draws it (design/companion.md §4.1). */
@Immutable
data class RecoveryUi(
    val label: String,
    val look: RecoveryLook,
    val heading: String?,
    val reason: String?,
    val facts: List<RecoveryFactData>,
    val progress: Float?,
)

/** One planned workout of today: the name, "1:05:00 · load 73 · IF 0.76", and the mini profile. */
@Immutable
data class PlannedRowUi(
    val id: String,
    val name: String,
    val subtitle: String,
    /** "Done 18:04" when the workout was ridden today; null otherwise. */
    val done: String?,
    /** The ride to open when it was ridden. */
    val rideId: String?,
    val profile: ProfileData?,
)

@Immutable
class TodayContent(
    /** Null while the rating has not been fetched and Health Connect is allowed: the card appears when it has something to say. */
    val recovery: RecoveryUi?,
    /** Null when there is no plan source: the card is not drawn. An empty list is "Nothing planned today". */
    val planned: List<PlannedRowUi>?,
    val ftp: FtpUi?,
    /** Today's note, or the sentence that it is not written yet; null when there is nothing to say (the card is not drawn). */
    val note: NoteCardUi? = null,
)

/**
 * Today (design/companion.md §4.1) made from what the phone has: the recovery state, the planned workout, the FTP suggestion.
 * Notes come with their stage and are not drawn. Pure, like the other models.
 */
object TodayUi {
    const val NIGHTS_NEEDED = 14

    fun of(
        recovery: ResourceState<RecoveryResponse>,
        metrics: ResourceState<MetricsResponse>,
        workouts: ResourceState<WorkoutsResponse>,
        rides: RidesView,
        profile: ProfileState,
        answers: FtpAnswers,
        justUsed: Int?,
        today: String,
        words: Words,
        host: String,
        insights: InsightsView = InsightsView(),
        watch: HealthStatus = HealthStatus(),
    ): ScreenUi<TodayContent> {
        val states = listOf(metrics, workouts)
        val fetched = states.mapNotNull { it.fetchedAtMillis }.maxOrNull()
        val updated = fetched?.let(words::updated)
        val problem = states.firstNotNullOfOrNull { it.problem }
        val refreshing = states.any { it.refreshing }
        val hasData = states.any { it.value != null }
        val problemUi =
            problem?.let { ProblemWords.of(it, host, updated) } ?: HealthWords.todayLine(watch, words.nowMillis())
        val date = words.todayLine()
        val watchConnected = watch.access.granted.isNotEmpty()
        val card =
            recovery.value?.let { RecoveryWords.state(it, today) }
                ?: if (watch.checked && !watchConnected) RecoveryState.AccessNeeded else null
        return when {
            !hasData && problemUi != null -> {
                ScreenUi(date, refreshing, null, Body.Failed(problemUi))
            }

            !hasData && states.any { it.isLoading } -> {
                ScreenUi(date, refreshing, null, Body.Loading("Loading your day…"))
            }

            else -> {
                ScreenUi(
                    subtitle = if (updated != null && problem == null) "$date · updated $updated" else date,
                    refreshing = refreshing,
                    status = problemUi,
                    body =
                        Body.Content(
                            TodayContent(
                                recovery = card?.let(::recoveryUi),
                                planned = planned(workouts.value, rides, today, words),
                                ftp = metrics.value?.let { FtpWords.of(it, profile, answers, justUsed, words).answer },
                                note = NotesUi.todayCard(insights, rides, today, words, watchConnected),
                            ),
                        ),
                )
            }
        }
    }

    private fun planned(
        response: WorkoutsResponse?,
        rides: RidesView,
        today: String,
        words: Words,
    ): List<PlannedRowUi>? {
        if (response == null || !response.configured) return null
        val ridden = rides.list.items.filter { it.kind == RideKind.RIDE && it.date == today }
        return response.workouts.filter { it.date == today }.map { row(it, ridden, words) }
    }

    private fun row(
        planned: PlannedWorkout,
        ridden: List<app.overdrive.core.metrics.RideItem>,
        words: Words,
    ): PlannedRowUi {
        val workout = planned.workout
        val runnable = workout.playability() == Playability.Runnable
        val flat = if (runnable) workout.flatSteps() else emptyList()
        val parts =
            buildList {
                if (runnable) add(UiFormat.elapsed(flat.totalDurationSeconds()))
                workout.plannedLoad()?.let { add("load ${words.load(it)}") }
                workout.intensityFactor()?.let { add("IF ${UiFormat.decimal(it, 2)}") }
            }
        val ride = ridden.firstOrNull { it.name.equals(planned.name, ignoreCase = true) }
        val doneAt = ride?.let { words.clockTimeAfter(it.start, it.durationSeconds) }
        return PlannedRowUi(
            id = planned.id,
            name = planned.name,
            subtitle = parts.joinToString(" · "),
            done = ride?.let { "Done ${doneAt ?: ""}".trim() },
            rideId = ride?.id,
            profile =
                flat.takeIf { it.isNotEmpty() }?.let { steps ->
                    ProfileData(
                        steps.map {
                            ProfileStep(
                                it.startSeconds,
                                it.durationSeconds,
                                it.targetFrom?.toFloat(),
                                it.targetTo?.toFloat(),
                            )
                        },
                    )
                },
        )
    }

    fun recoveryUi(state: RecoveryState): RecoveryUi =
        when (state) {
            RecoveryState.AccessNeeded -> {
                RecoveryUi(
                    "Recovery",
                    RecoveryLook.Learning,
                    "Learning your usual range",
                    "Overdrive rates recovery after $NIGHTS_NEEDED nights with the watch. Allow Health Connect in Settings to rate recovery.",
                    emptyList(),
                    0f,
                )
            }

            is RecoveryState.Learning -> {
                RecoveryUi(
                    "Recovery",
                    RecoveryLook.Learning,
                    "Learning your usual range",
                    "Overdrive rates recovery after ${state.needed} nights with the watch. ${state.nights} nights so far.",
                    state.facts,
                    state.nights.toFloat() / state.needed,
                )
            }

            is RecoveryState.Rated -> {
                RecoveryUi(
                    if (state.thisMorning) "Recovery · this morning" else "Recovery · yesterday",
                    when (state.level) {
                        RecoveryLevel.Ready -> RecoveryLook.Ready
                        RecoveryLevel.Caution -> RecoveryLook.Caution
                        RecoveryLevel.Rest -> RecoveryLook.Rest
                    },
                    null,
                    state.reason,
                    state.facts,
                    null,
                )
            }

            RecoveryState.NoReading -> {
                RecoveryUi(
                    "Recovery",
                    RecoveryLook.NoData,
                    "No reading from last night",
                    "Wear the watch to sleep, then open Overdrive in the morning.",
                    emptyList(),
                    null,
                )
            }
        }
}
