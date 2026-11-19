package app.overdrive.companion.model

import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.notebook.NotebookLimits
import app.overdrive.core.notebook.NotebookResponse
import app.overdrive.core.notebook.NotebookSection
import app.overdrive.core.notebook.NotebookSectionView
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coach notebook page as pure functions (spec §11.6, design/companion.md §4.13): the sections in their fixed order, the methodology
 * marked, each section's words for when it changed, the log collapsed to its latest entries, and every state (loading, empty, loaded,
 * stale or offline with the cause, an error with nothing cached). Synthetic text, invented for the tests.
 */
class NotebookModelsTest {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")

    private fun section(
        key: NotebookSection,
        text: String = "",
        version: Int = 0,
        updatedAt: String? = null,
        reason: String? = null,
    ) = NotebookSectionView(key, key.title, text, version, updatedAt, reason)

    private fun notebook(vararg overrides: NotebookSectionView): NotebookResponse {
        val byKey = overrides.associateBy { it.key }
        val sections = NotebookSection.entries.map { byKey[it] ?: section(it) }
        return NotebookResponse(
            scope = "coach",
            generatedAt = "2026-11-18T07:30:00Z",
            limits = NotebookLimits(maxChars = 12_000, logMaxChars = 3_000),
            totalChars = sections.sumOf { it.text.length },
            sections = sections,
        )
    }

    private fun loaded(): NotebookResponse =
        notebook(
            section(
                NotebookSection.PROFILE,
                "Works full-time. Rides mornings.",
                3,
                "2026-11-12T19:02:11Z",
                "Friday is no longer free.",
            ),
            section(NotebookSection.WHAT_WORKS, "Two quality sessions a week.", 2, "2026-11-16T06:50:00Z"),
            section(
                NotebookSection.METHODOLOGY,
                "Base block, then sweet spot.",
                2,
                "2026-11-16T18:00:00Z",
                "The FTP test came in lower.",
            ),
            section(
                NotebookSection.LOG,
                "2026-11-10 Moved a session.\n2026-11-16 FTP lowered to 240 W.",
                4,
                "2026-11-16T18:05:00Z",
            ),
        )

    private fun content(state: ResourceState<NotebookResponse>) = NotebookUi.of(state, words, host)

    // region states

    @Test
    fun `nothing cached and still asking is loading, not empty`() {
        assertEquals(
            Body.Loading("Loading the coach notebook…"),
            content(ResourceState(loaded = true)).body,
        )
    }

    @Test
    fun `nothing cached and the request failed is the error block with its cause`() {
        val ui = content(ResourceState(loaded = true, problem = offline, asked = true))
        assertTrue(ui.body is Body.Failed)
        assertNull(ui.subtitle)
    }

    @Test
    fun `a notebook no section of which was ever written is empty, with one sentence`() {
        val ui = content(fresh(notebook()))
        val empty = ui.body as Body.Empty
        assertEquals(NotebookUi.EMPTY_HEADING, empty.heading)
        assertEquals(NotebookUi.EMPTY_TEXT, empty.text)
        assertEquals("Updated 07:40", ui.subtitle)
    }

    @Test
    fun `a loaded notebook shows the seven sections in their fixed order, with their titles`() {
        val ui = content(fresh(loaded()))
        val content = (ui.body as Body.Content).value
        assertEquals(NotebookSection.entries.map { it.title }, content.sections.map { it.title })
        assertEquals("Rider profile and constraints", content.sections.first().title)
        assertEquals("Decisions log", content.sections.last().title)
    }

    @Test
    fun `the stale or offline notebook keeps its text and says why, in the screens' words`() {
        val ui = content(fresh(loaded()).copy(problem = offline))
        assertTrue(ui.body is Body.Content)
        assertEquals("Offline · updated 07:40", ui.status?.title)
        assertEquals("Updated 07:40", ui.subtitle)
        val profile = (ui.body as Body.Content).value.sections.first()
        assertEquals("Works full-time. Rides mornings.", profile.text)
    }

    @Test
    fun `while a refresh runs the subtitle says so and the content stays`() {
        val ui = content(fresh(loaded()).copy(refreshing = true))
        assertTrue(ui.refreshing)
        assertTrue(ui.body is Body.Content)
    }

    // region the sections

    @Test
    fun `the methodology is marked and the other sections are not`() {
        val sections = (content(fresh(loaded())).body as Body.Content).value.sections
        val marked = sections.filter { it.marker != null }
        assertEquals(listOf(NotebookSection.METHODOLOGY.title), marked.map { it.title })
        assertEquals(NotebookUi.MARKER, marked.single().marker)
        assertEquals("the reason is given for the methodology", "The FTP test came in lower.", marked.single().reason)
        assertTrue(sections.filter { it.title != NotebookSection.METHODOLOGY.title }.all { it.reason == null })
    }

    @Test
    fun `when a section changed is said in words, with its version`() {
        val sections = (content(fresh(loaded())).body as Body.Content).value.sections.associateBy { it.title }
        assertEquals("changed 6 days ago, version 3", sections.getValue("Rider profile and constraints").changed)
        assertEquals("changed 2 days ago, version 2", sections.getValue("Methodology").changed)
        assertEquals("Not written yet", sections.getValue("Open questions").changed)
    }

    @Test
    fun `the words for a change are today, yesterday, the days, and then the date`() {
        fun changedAt(
            updatedAt: String,
            version: Int = 1,
        ) = NotebookUi.changed(section(NotebookSection.PROFILE, "x", version, updatedAt), words)
        assertEquals("changed today, version 1", changedAt("2026-11-18T06:00:00Z"))
        assertEquals("changed yesterday, version 1", changedAt("2026-11-17T21:00:00Z"))
        assertEquals("changed 3 days ago, version 4", changedAt("2026-11-15T09:00:00Z", version = 4))
        assertEquals("changed on Mon 9 Nov, version 2", changedAt("2026-11-09T09:00:00Z", version = 2))
        assertEquals("Not written yet", NotebookUi.changed(section(NotebookSection.PROFILE), words))
    }

    @Test
    fun `a section's text is shown as written, and the log is a list of entries, not text`() {
        val sections = (content(fresh(loaded())).body as Body.Content).value.sections
        val log = sections.last()
        assertEquals("", log.text)
        assertEquals(listOf("2026-11-10 Moved a session.", "2026-11-16 FTP lowered to 240 W."), log.entries)
        assertEquals("Works full-time. Rides mornings.", sections.first().text)
        assertNull(sections.first().entries)
    }

    @Test
    fun `the log's lines are one entry each, blanks left out, in the order written`() {
        assertEquals(
            listOf("2026-11-10 A.", "2026-11-11 B."),
            NotebookUi.entriesOf("  2026-11-10 A.  \n\n2026-11-11 B.\n   \n"),
        )
        assertTrue(NotebookUi.entriesOf("").isEmpty())
    }

    // region the long log

    private fun entries(count: Int): List<String> = (1..count).map { "2026-11-%02d Entry %d.".format(it, it) }

    @Test
    fun `a short log is shown whole and has no control`() {
        val view = NotebookUi.logView(entries(NotebookUi.LOG_LATEST), showAll = false)
        assertEquals(entries(NotebookUi.LOG_LATEST), view.visible)
        assertEquals(0, view.hidden)
        assertNull(view.toggle)
    }

    @Test
    fun `a long log is collapsed to its latest entries, with Show all for the rest`() {
        val all = entries(8)
        val collapsed = NotebookUi.logView(all, showAll = false)
        assertEquals("the latest entries are the ones shown", all.takeLast(NotebookUi.LOG_LATEST), collapsed.visible)
        assertEquals(3, collapsed.hidden)
        assertEquals("Show all", collapsed.toggle)
    }

    @Test
    fun `Show all draws every entry in the order written, and Show latest collapses again`() {
        val all = entries(8)
        val expanded = NotebookUi.logView(all, showAll = true)
        assertEquals(all, expanded.visible)
        assertEquals(0, expanded.hidden)
        assertEquals("Show latest", expanded.toggle)
    }

    @Test
    fun `a long log in the page is collapsed, and its entries are the notebook's`() {
        val longLog = (1..9).joinToString("\n") { "2026-11-%02d Entry %d.".format(it, it) }
        val ui = content(fresh(notebook(section(NotebookSection.LOG, longLog, 5, "2026-11-18T07:00:00Z"))))
        val log = (ui.body as Body.Content).value.sections.last()
        assertEquals(9, log.entries?.size)
        assertEquals(5, NotebookUi.logView(log.entries.orEmpty(), showAll = false).visible.size)
        assertFalse(log.text.isNotEmpty())
    }

    @Test
    fun `the read-only line is the one the rider reads`() {
        assertEquals("Read-only. To change something, tell the coach or send a note in Telegram.", NotebookUi.READ_ONLY)
    }
}
