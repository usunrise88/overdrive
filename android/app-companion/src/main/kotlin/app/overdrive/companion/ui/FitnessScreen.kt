package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.EffortBody
import app.overdrive.companion.model.EffortCardUi
import app.overdrive.companion.model.EffortRowUi
import app.overdrive.companion.model.EffortUi
import app.overdrive.companion.model.FitnessContent
import app.overdrive.companion.model.FitnessUi
import app.overdrive.companion.model.ProblemUi
import app.overdrive.companion.model.ProblemWords
import app.overdrive.companion.model.ProgressEntryUi
import app.overdrive.companion.model.ProgressUi
import app.overdrive.companion.model.RangeWords
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.companion.model.Words
import app.overdrive.core.effort.EffortKind
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProgressRange
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.RangeSelector
import app.overdrive.uikit.chart.ChartSelection

/** What the range-bearing screens can do. */
class RangeActions(
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onPhoneSettings: () -> Unit,
    val onRange: (MetricsRange) -> Unit,
)

/** Fitness (design/companion.md §4.3) from the container; the range is remembered. */
@Composable
fun FitnessScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val range = settings?.fitnessRange ?: MetricsRange.DEFAULT
    val state by container.metricsFor(range).state.collectAsStateWithLifecycle()
    val effortState by container.effort.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(range) { container.screenOpened(RefreshTarget.Fitness) }
    val metricsUi = remember(state, range, host) { FitnessUi.of(state, range, container.words, host) }
    // The Effort card has its own resource: when only it failed, its cause is the tab's problem line (design/companion.md §3.2).
    val ui =
        remember(metricsUi, effortState, host) {
            metricsUi.copy(status = metricsUi.status ?: problemOf(effortState, host, container.words))
        }
    val effort = remember(effortState) { EffortUi.of(effortState, container.words) }
    // The row at the end of the page reads the default range's headline (ADR 0043).
    val progressState by container.progressFor(ProgressRange.DEFAULT).state.collectAsStateWithLifecycle()
    val progress = remember(progressState) { ProgressUi.entry(progressState) }
    val listState = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Fitness, listState)
    FitnessView(
        ui,
        range,
        RangeActions(
            onRefresh = { container.refresh(RefreshTarget.Fitness) },
            onSettings = { container.nav.push(Route.Settings) },
            onPhoneSettings = { openAppSettings(context) },
            onRange = container::setFitnessRange,
        ),
        modifier,
        listState,
        effort = effort,
        progress = progress,
        onProgress = { container.nav.push(Route.Progress) },
        onOpenRow = { row ->
            container.nav.push(
                if (row.kind ==
                    EffortKind.RIDE
                ) {
                    Route.RideDetail(row.id)
                } else {
                    Route.ActivityDetail(row.id)
                },
            )
        },
    )
}

/**
 * The problem line of a resource that is not the tab's own (design/companion.md §3.2): its cause with the time of its last good answer,
 * or null when its last request was fine.
 */
internal fun problemOf(
    state: ResourceState<*>,
    host: String,
    words: Words,
): ProblemUi? = state.problem?.let { ProblemWords.of(it, host, state.fetchedAtMillis?.let(words::updated)) }

/** The Fitness screen for a given [ui]. */
@Composable
fun FitnessView(
    ui: ScreenUi<FitnessContent>,
    range: MetricsRange,
    actions: RangeActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    initialSheet: ValuesTable? = null,
    selection: ChartSelection = remember(range) { ChartSelection() },
    effort: EffortCardUi? = null,
    effortSelection: ChartSelection = remember { ChartSelection() },
    onOpenRow: (EffortRowUi) -> Unit = {},
    progress: ProgressEntryUi? = null,
    onProgress: () -> Unit = {},
) {
    // One selection for the two line charts (design/companion.md §3.5); Effort's is its own and leaving the tab returns it to rest.
    var table by remember { mutableStateOf(initialSheet) }
    val effortTable = (effort?.body as? EffortBody.Content)?.content?.table
    Box(modifier) {
        TabScreen(
            title = "Fitness",
            ui = ui,
            onRefresh = actions.onRefresh,
            onSettings = actions.onSettings,
            onPhoneSettings = actions.onPhoneSettings,
            state = listState,
            controls = {
                // In every state but the content the selector sits where it always did; with content it moves under the cards of "now"
                // (design/companion.md §4.3): everything above it is today, everything under it follows it.
                if (ui.body !is Body.Content) item(key = "range") { RangeSelectorRow(range, actions) }
            },
        ) { content ->
            item(key = "tiles") { Tiles(content.tiles) }
            if (effort != null) {
                item(key = "effort") {
                    EffortCard(effort, effortSelection, onValues = { table = effortTable }, onOpenRow = onOpenRow)
                }
            }
            item(key = "range") { RangeSelectorRow(range, actions) }
            item(
                key = "fitness",
            ) { LineChartCard(content.fitness, selection, onValues = { table = content.fitness.table }) }
            item(key = "form") { LineChartCard(content.form, selection, onValues = { table = content.form.table }) }
            if (progress != null) item(key = "progress") { ProgressEntryRow(progress, onProgress) }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        BackHandler(enabled = table != null) { table = null }
    }
}

@Composable
private fun RangeSelectorRow(
    range: MetricsRange,
    actions: RangeActions,
) {
    RangeSelector(RangeWords.labels, MetricsRange.entries.indexOf(range), { actions.onRange(MetricsRange.entries[it]) })
}
