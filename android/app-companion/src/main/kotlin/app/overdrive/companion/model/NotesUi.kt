package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.insights.Insight
import app.overdrive.core.insights.InsightKind
import app.overdrive.core.insights.TopicStatus
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.RidesView

/** A discussion topic of an after-ride note (spec §12.3, ADR 0025): what stood out, and the question to open the chat with. */
@Immutable
data class TopicUi(
    val id: Long,
    val text: String,
    val question: String,
)

/** The link under an after-ride note to its ride ("Threshold 2×20 ›"). */
@Immutable
data class RideLinkUi(
    val rideId: String,
    val name: String,
)

/** One written note as a card draws it (design/companion.md §4.1, §4.2): the kind and time, the text, the topic and the ride. */
@Immutable
data class NoteUi(
    val id: Long,
    /** "Morning note · 07:30", "After your ride · 19:42", "Heads-up · 07:10". */
    val label: String,
    val text: String,
    /** A heads-up from the service about a pattern or the plan (spec §6.6): the same card, its own label. */
    val alert: Boolean,
    val topic: TopicUi?,
    val ride: RideLinkUi?,
)

@Immutable
data class NoteGroupUi(
    val date: String,
    val heading: String,
    val notes: List<NoteUi>,
)

@Immutable
data class NotesContent(
    val groups: List<NoteGroupUi>,
    val hasMore: Boolean,
    val loadingMore: Boolean,
    /** The words under the list when the last older window could not be fetched. */
    val moreProblem: String?,
)

/**
 * What Today's note card says (design/companion.md §4.1): the latest note with its time, or the sentence that says it is not written
 * yet, and whether there is an **All notes** row. [note] is null for the sentence.
 */
@Immutable
data class NoteCardUi(
    val note: NoteUi?,
    val waiting: String?,
    val hasAll: Boolean,
)

/**
 * The notes of `GET /v1/insights` as the cards say them (design/companion.md §4.1, §4.2, §7, ADR 0025). The text is the service's and
 * is shown as it is, **read only**: the phone neither edits a note nor writes one. Kinds: the morning note, the note after a ride,
 * the weekly review, and the two alerts (a metric outside its own range for days, the plan drifting). Pure.
 */
object NotesUi {
    const val NOT_YET = "No note yet today. It is written after this morning's watch data reaches the service."
    private const val MORNING_HOUR = 10

    fun kindLabel(kind: InsightKind): String =
        when (kind) {
            InsightKind.MORNING -> "Morning note"
            InsightKind.RIDE -> "After your ride"
            InsightKind.WEEK -> "Weekly review"
            InsightKind.DEVIATION -> "Heads-up"
            InsightKind.DRIFT -> "Plan check"
        }

    /** The card of one note. [label] has the time: "07:30" on the day itself, the day before the time otherwise. */
    fun note(
        insight: Insight,
        rides: RidesView,
        words: Words,
        withDay: Boolean,
    ): NoteUi {
        val written = words.written(insight.createdAt)
        val time = words.clockTime(insight.createdAt)
        val at = if (withDay) written else time
        val rideName = insight.rideId?.let { id -> rides.list.items.firstOrNull { it.id == id }?.name }
        return NoteUi(
            id = insight.id,
            label = listOfNotNull(kindLabel(insight.kind), at).joinToString(" · "),
            text = insight.text,
            alert = insight.kind == InsightKind.DEVIATION || insight.kind == InsightKind.DRIFT,
            topic =
                insight.topic?.takeIf { it.status == TopicStatus.OPEN }?.let {
                    TopicUi(
                        it.id,
                        it.text,
                        it.openingQuestion,
                    )
                },
            ride = insight.rideId?.let { RideLinkUi(it, rideName ?: "Open the ride") },
        )
    }

    /** The Notes screen, newest first and grouped by the local day each note was written. */
    fun screen(
        view: InsightsView,
        rides: RidesView,
        today: String,
        words: Words,
        host: String,
    ): ScreenUi<NotesContent> {
        val first = view.first
        val updated = first.fetchedAtMillis?.let(words::updated)
        val problem = first.problem?.let { ProblemWords.of(it, host, updated) }
        val head = first.value
        return when {
            head == null && problem != null -> {
                ScreenUi(null, first.refreshing, null, Body.Failed(problem))
            }

            head == null -> {
                ScreenUi(null, first.refreshing, null, Body.Loading("Loading your notes…"))
            }

            view.list.items.isEmpty() -> {
                ScreenUi(
                    updated?.let { "Updated $it" },
                    first.refreshing,
                    problem,
                    Body.Empty(
                        "No notes yet",
                        "Notes are written after your morning's watch data and after each ride.",
                    ),
                )
            }

            else -> {
                val groups =
                    view.list.items
                        .groupBy { words.localDate(it.createdAt) ?: today }
                        .map { (date, notes) ->
                            NoteGroupUi(
                                date,
                                words.dayHeading(date, today),
                                notes.map { note(it, rides, words, withDay = false) },
                            )
                        }
                ScreenUi(
                    updated?.let { "Updated $it" },
                    first.refreshing,
                    problem,
                    Body.Content(
                        NotesContent(
                            groups,
                            view.list.hasMore,
                            view.list.loadingMore,
                            view.list.moreProblem?.let { ProblemWords.causeOf(it, host) },
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Today's card. The latest note, with its day when it is not today's; **before 10:00 with no note yet today** (and the watch
     * connected, so one is coming) the sentence that says it is written after the morning's watch data; never a note, no card.
     */
    fun todayCard(
        view: InsightsView,
        rides: RidesView,
        today: String,
        words: Words,
        watchConnected: Boolean,
    ): NoteCardUi? {
        val latest = view.list.items.firstOrNull()
        val hasAll = view.list.items.isNotEmpty()
        val writtenToday = latest != null && words.localDate(latest.createdAt) == today
        return when {
            latest != null && writtenToday -> {
                NoteCardUi(note(latest, rides, words, withDay = false), null, hasAll)
            }

            watchConnected && words.hourNow() < MORNING_HOUR && view.first.value != null -> {
                NoteCardUi(
                    null,
                    NOT_YET,
                    hasAll,
                )
            }

            latest != null -> {
                NoteCardUi(note(latest, rides, words, withDay = true), null, hasAll)
            }

            else -> {
                null
            }
        }
    }
}
