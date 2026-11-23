package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.ProblemAction
import app.overdrive.companion.model.ProgressCardUi
import app.overdrive.companion.model.ProgressChartUi
import app.overdrive.companion.model.ProgressContent
import app.overdrive.companion.model.ProgressGroupUi
import app.overdrive.companion.model.ProgressSentenceUi
import app.overdrive.companion.model.ProgressUi
import app.overdrive.companion.model.ProgressWords
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.core.remote.ProgressRange
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.RangeSelector
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.StatusLine
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.ChartReadout
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.PlotChart
import app.overdrive.uikit.chart.RestPill
import app.overdrive.uikit.tokens.OverdriveColors

/** What the Progress screen can do. */
class ProgressActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onPhoneSettings: () -> Unit,
    val onRange: (ProgressRange) -> Unit,
    /** A ride in a readout was tapped. */
    val onOpenRide: (String) -> Unit,
)

/**
 * One selection per card (design/companion.md §4.16): the cards have different x steps (months, weeks, rides), so a tap or a hold in
 * one never moves another. The two plots of a stacked pair share their card's. A new range starts every card at rest.
 */
@Stable
class ProgressSelections {
    private val selections = HashMap<String, ChartSelection>()

    fun of(key: String): ChartSelection = selections.getOrPut(key) { ChartSelection() }
}

/** Progress (design/companion.md §4.16) from the container: the range is remembered, every range has its own cache. */
@Composable
fun ProgressScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val range = settings?.progressRange ?: ProgressRange.DEFAULT
    val states = ProgressRange.entries.map { container.progressFor(it).state.collectAsStateWithLifecycle().value }
    val state = states[range.ordinal]
    val othersCached = states.withIndex().any { (index, other) -> index != range.ordinal && other.value != null }
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(range) { container.screenOpened(RefreshTarget.Progress(range)) }
    val ui =
        remember(state, range, host, othersCached) { ProgressUi.of(state, range, container.words, host, othersCached) }
    ProgressView(
        ui,
        range,
        ProgressActions(
            onBack = { container.nav.back() },
            onRefresh = { container.refresh(RefreshTarget.Progress(range)) },
            onSettings = { container.nav.push(Route.Settings) },
            onPhoneSettings = { openAppSettings(context) },
            onRange = container::setProgressRange,
            onOpenRide = { container.nav.push(Route.RideDetail(it)) },
        ),
        modifier,
    )
}

/** The Progress screen for a given [ui]. */
@Composable
fun ProgressView(
    ui: ScreenUi<ProgressContent>,
    range: ProgressRange,
    actions: ProgressActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    selections: ProgressSelections = remember(range) { ProgressSelections() },
    initialTable: ValuesTable? = null,
) {
    var table by remember { mutableStateOf(initialTable) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(ProgressWords.TITLE, actions.onBack, subtitle = { Subtitle(ui) })
            LazyColumn(
                Modifier.fillMaxSize().pullToRefresh(actions.onRefresh),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ui.status?.let { problem ->
                    item(key = "status") {
                        StatusLine(
                            problem.title,
                            problem.cause,
                            actionLabel = problem.action?.label,
                            onAction = {
                                if (problem.action ==
                                    ProblemAction.PhoneSettings
                                ) {
                                    actions.onPhoneSettings()
                                } else {
                                    actions.onSettings()
                                }
                            },
                        )
                    }
                }
                item(key = "range") {
                    RangeSelector(
                        ProgressWords.labels,
                        range.ordinal,
                        { actions.onRange(ProgressRange.entries[it]) },
                    )
                }
                body(ui, actions, selections) { table = it }
            }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        BackHandler(enabled = table != null) { table = null }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.body(
    ui: ScreenUi<ProgressContent>,
    actions: ProgressActions,
    selections: ProgressSelections,
    onValues: (ValuesTable) -> Unit,
) {
    when (val body = ui.body) {
        is Body.Loading -> {
            item(key = "loading") { LoadingBlock(body.text, Modifier.height(BLOCK_HEIGHT)) }
        }

        is Body.Failed -> {
            item(key = "error") {
                ErrorBlock(
                    heading = "Could not reach your service",
                    cause = body.problem.cause,
                    hint = body.problem.hint,
                    onTryAgain = actions.onRefresh,
                    modifier = Modifier.height(BLOCK_HEIGHT),
                    settingsLabel = body.problem.action?.label,
                    onSettings = {
                        if (body.problem.action ==
                            ProblemAction.PhoneSettings
                        ) {
                            actions.onPhoneSettings()
                        } else {
                            actions.onSettings()
                        }
                    },
                )
            }
        }

        is Body.Empty -> {
            item(key = "empty") { EmptyBlock(body.heading, body.text) }
        }

        is Body.Content -> {
            val content = body.value
            if (content.notice != null) {
                item(key = "notice") {
                    OdText(
                        content.notice,
                        Modifier.padding(horizontal = 4.dp),
                        style = OdType.body,
                        color = OverdriveColors.textSecondary,
                    )
                }
            }
            for (group in content.groups) {
                item(key = "group-${group.heading}") { GroupHeading(group) }
                for (card in group.cards) {
                    item(
                        key = "card-${card.key}",
                    ) { ProgressCard(card, selections.of(card.key), actions.onOpenRide, onValues) }
                }
            }
        }
    }
}

private val BLOCK_HEIGHT = 480.dp

/** "Updating…" with a spinner while a request runs, else "Updated 07:40" (or nothing before the first answer). */
@Composable
private fun Subtitle(ui: ScreenUi<ProgressContent>) {
    if (ui.refreshing) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(diameter = 16.dp)
            OdText("Updating…", style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
        }
    } else {
        ui.subtitle?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1) }
    }
}

/** The group's heading, 8 dp above and with a 4 dp side inset, and the one plain caution under it. */
@Composable
private fun GroupHeading(group: ProgressGroupUi) {
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OdText(group.heading, Modifier.semantics { heading() }, style = OdType.heading)
        group.caution?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary) }
    }
}

/** One card: a sentence when the measure has nothing in the range, else the verdict, the numbers, the readout and the plots. */
@Composable
fun ProgressCard(
    card: ProgressCardUi,
    selection: ChartSelection,
    onOpenRide: (String) -> Unit,
    onValues: (ValuesTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (card) {
        is ProgressSentenceUi -> {
            OdCard(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OdText(card.name, style = OdType.label, color = OverdriveColors.textTertiary)
                OdText(card.text, style = OdType.body, color = OverdriveColors.textSecondary)
            }
        }

        is ProgressChartUi -> {
            ChartedCard(card, selection, onOpenRide, onValues, modifier)
        }
    }
}

@Composable
private fun ChartedCard(
    card: ProgressChartUi,
    selection: ChartSelection,
    onOpenRide: (String) -> Unit,
    onValues: (ValuesTable) -> Unit,
    modifier: Modifier,
) {
    val x = selection.x
    val readout = remember(card, x) { card.readoutAt(x) }
    val rideId = remember(card, x) { card.rideAt(x) }
    ChartCard(card.name, modifier.fillMaxWidth(), onValues = { onValues(card.table) }, note = card.note) {
        // The verdict and the numbers are about the range: they never follow the selection.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(card.verdict, style = OdType.bodyStrong)
            if (card.numbers.isNotEmpty()) {
                OdText(card.numbers, style = OdType.labelNum, color = OverdriveColors.textSecondary)
            }
        }
        ChartReadout(
            readout.x,
            readout.entries,
            away = selection.isAway(card.restX),
            onToday = selection::clear,
            rest = RestPill.LatestPoint,
            onOpen = rideId?.let { id -> { onOpenRide(id) } },
        )
        card.plots.forEachIndexed { index, plot ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                plot.caption?.let {
                    OdText(
                        it,
                        Modifier.heightIn(min = 16.dp),
                        style = OdType.caption,
                        color = OverdriveColors.textTertiary,
                    )
                }
                // A stacked pair is one node for TalkBack with both series in its description; the second plot still takes touch.
                Box(if (index == 0) Modifier else Modifier.clearAndSetSemantics {}) {
                    PlotChart(plot.spec, selection, if (index == 0) "${card.summary} ${readout.spoken()}" else "")
                }
            }
        }
    }
}
