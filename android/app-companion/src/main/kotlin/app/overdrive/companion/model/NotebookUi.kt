package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.notebook.NotebookResponse
import app.overdrive.core.notebook.NotebookSection
import app.overdrive.core.notebook.NotebookSectionView
import app.overdrive.core.remote.ResourceState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * The coach notebook page (spec §11.6, design/companion.md §4.13): read-only. Every word here says what the service sent: each section's text
 * as it was written, its version and when it changed. The phone computes nothing about the notebook and changes nothing in it.
 */

/** One section as its block draws it. [entries] is set only for the log, which is drawn as a list of entries and not as text. */
@Immutable
data class NotebookSectionUi(
    val title: String,
    /** "Current plan" on the methodology, else null: a change to the plan is announced to the rider (spec §12.4). */
    val marker: String?,
    /** "changed 3 days ago, version 4", or "Not written yet". */
    val changed: String,
    /** The text as written; empty for a section never written, and for the log. */
    val text: String,
    /** The reason given for the methodology's current text, or null. */
    val reason: String?,
    /** The log's entries, one per line, in the order written; null for every other section. */
    val entries: List<String>?,
)

@Immutable
class NotebookContent(
    /** The sections in the order the contract fixes: the methodology is marked, not moved. */
    val sections: List<NotebookSectionUi>,
)

/** The log as drawn: the entries to show, how many are left out, and the control's words ("Show all", "Show latest"), or null for none. */
@Immutable
data class LogView(
    val visible: List<String>,
    val hidden: Int,
    val toggle: String?,
)

/** The notebook page as pure functions, so every state and every word is a unit test (design/companion.md §5, §7). */
object NotebookUi {
    const val READ_ONLY = "Read-only. To change something, tell the coach or send a note in Telegram."
    const val EMPTY_HEADING = "No notebook yet"
    const val EMPTY_TEXT = "The coach writes its notebook as it plans your sessions, and it appears here."
    const val NOT_WRITTEN = "Not written yet"
    const val MARKER = "Current plan"
    const val SHOW_ALL = "Show all"
    const val SHOW_LATEST = "Show latest"

    /** The log shows this many of its latest entries until the rider asks for all of them. */
    const val LOG_LATEST = 5

    /** "changed 3 days ago" up to this many days; from the next day on, the date. */
    const val DAYS_IN_WORDS = 6L

    fun of(
        state: ResourceState<NotebookResponse>,
        words: Words,
        host: String,
    ): ScreenUi<NotebookContent> =
        screenOf(
            state,
            words,
            host,
            loading = "Loading the coach notebook…",
            empty = EMPTY_HEADING to EMPTY_TEXT,
            isEmpty = NotebookResponse::isEmpty,
        ) { content(it, words) }

    fun content(
        response: NotebookResponse,
        words: Words,
    ): NotebookContent = NotebookContent(response.sections.map { section(it, words) })

    private fun section(
        view: NotebookSectionView,
        words: Words,
    ): NotebookSectionUi {
        val isLog = view.key == NotebookSection.LOG
        return NotebookSectionUi(
            title = view.title,
            marker = if (view.key == NotebookSection.METHODOLOGY) MARKER else null,
            changed = changed(view, words),
            text = if (isLog) "" else view.text,
            reason = if (view.key == NotebookSection.METHODOLOGY) view.reason else null,
            entries = if (isLog) entriesOf(view.text) else null,
        )
    }

    /** The log's lines: one entry each, blank lines left out, in the order they were written. */
    fun entriesOf(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** "changed 3 days ago, version 4"; "changed today"; "changed on Tue 10 Nov, version 2"; "Not written yet" for version 0. */
    fun changed(
        view: NotebookSectionView,
        words: Words,
    ): String {
        if (view.version == 0) return NOT_WRITTEN
        val version = "version ${view.version}"
        val day = view.updatedAt?.let(words::localDate) ?: return version
        val days = ChronoUnit.DAYS.between(LocalDate.parse(day), LocalDate.parse(words.today())).coerceAtLeast(0L)
        val since =
            when {
                days == 0L -> "changed today"
                days == 1L -> "changed yesterday"
                days <= DAYS_IN_WORDS -> "changed $days days ago"
                else -> "changed on ${words.day(day)}"
            }
        return "$since, $version"
    }

    /** The log collapsed to its latest entries unless [showAll]; a short log has no control. */
    fun logView(
        entries: List<String>,
        showAll: Boolean,
    ): LogView =
        when {
            entries.size <= LOG_LATEST -> LogView(entries, 0, null)
            showAll -> LogView(entries, 0, SHOW_LATEST)
            else -> LogView(entries.takeLast(LOG_LATEST), entries.size - LOG_LATEST, SHOW_ALL)
        }
}
