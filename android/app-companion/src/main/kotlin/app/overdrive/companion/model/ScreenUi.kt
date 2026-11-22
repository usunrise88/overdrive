package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.chart.PlotSpec
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.RideChartSpec
import app.overdrive.uikit.chart.ValuesRow

/** What is in the body of a screen (design/companion.md §5): the states every screen has, and the content in the one that has data. */
sealed interface Body<out T> {
    /** Nothing cached and nothing answered yet: a spinner and "Loading your fitness…". */
    data class Loading(
        val text: String,
    ) : Body<Nothing>

    /** Nothing cached and the last request failed: the error block. */
    data class Failed(
        val problem: ProblemUi,
    ) : Body<Nothing>

    /** Connected and nothing of this kind yet: a heading and one sentence. */
    data class Empty(
        val heading: String,
        val text: String,
        /** A button under the sentence ("Allow"), or null for none. */
        val action: String? = null,
    ) : Body<Nothing>

    data class Content<T>(
        val value: T,
    ) : Body<T>
}

/**
 * One screen as the design describes it: the header's subtitle ("Updated 07:40", or "Updating…" while [refreshing]), the
 * problem line when the last refresh failed and there is an answer to show, and the [body].
 */
@Immutable
data class ScreenUi<out T>(
    val subtitle: String?,
    val refreshing: Boolean,
    val status: ProblemUi?,
    val body: Body<T>,
)

/**
 * Turns the state of one resource into a [ScreenUi]: loading with nothing cached, the error block when nothing is cached and the
 * request failed, the empty state, and otherwise the content with the stale line when the last refresh failed (design/companion.md §5).
 *
 * @param host the address's host, for the DNS sentence.
 * @param loading "Loading your fitness…".
 * @param empty the heading and sentence of the empty state, and what makes the answer empty.
 */
fun <R : Any, T> screenOf(
    state: ResourceState<R>,
    words: Words,
    host: String,
    loading: String,
    empty: Pair<String, String>?,
    isEmpty: (R) -> Boolean = { false },
    content: (R) -> T,
): ScreenUi<T> {
    val value = state.value
    val updated = state.fetchedAtMillis?.let(words::updated)
    val problem = state.problem?.let { ProblemWords.of(it, host, updated) }
    return when {
        value == null && problem != null -> {
            ScreenUi(null, state.refreshing, null, Body.Failed(problem))
        }

        value == null -> {
            ScreenUi(null, state.refreshing, null, Body.Loading(loading))
        }

        empty != null && isEmpty(value) -> {
            ScreenUi(updated?.let { "Updated $it" }, state.refreshing, problem, Body.Empty(empty.first, empty.second))
        }

        else -> {
            ScreenUi(updated?.let { "Updated $it" }, state.refreshing, problem, Body.Content(content(value)))
        }
    }
}

/** What a chart says for one selected x (design/companion.md §3.5): the x in text 1 and one entry per series. */
@Immutable
class Readout(
    val x: String,
    val entries: List<ReadoutEntry>,
) {
    /** The readout as TalkBack says it: "Wed 18 Nov, Fitness 54, Fatigue 43". */
    fun spoken(): String =
        (
            listOf(x) +
                entries.map { entry -> listOfNotNull(entry.name, entry.value).joinToString(" ") }
        ).joinToString(", ")
}

/** The table twin of a chart (design/companion.md §3.6), newest first. */
@Immutable
class ValuesTable(
    val title: String,
    val firstColumn: String,
    val columns: List<String>,
    val rows: List<ValuesRow>,
)

/** A line chart with everything around it: the heading, the readout for any x, the note and the table. */
@Immutable
class LineChartUi(
    val heading: String,
    val spec: PlotSpec,
    /** The readout at [x], or at rest for null. */
    val readoutAt: (Double?) -> Readout,
    val table: ValuesTable,
    val summary: String,
    val note: String? = null,
)

/** The ride chart with everything around it. */
@Immutable
class RideChartUi(
    val heading: String,
    val spec: RideChartSpec,
    val readoutAt: (Double?) -> Readout,
    val table: ValuesTable,
    val summary: String,
    val noHeartRate: Boolean,
    val noWBalance: Boolean,
)
