package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.RidesMode
import app.overdrive.companion.model.AttemptRowUi
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.CardPosition
import app.overdrive.companion.model.RepeatChartUi
import app.overdrive.companion.model.RepeatDetailContent
import app.overdrive.companion.model.RepeatDetailUi
import app.overdrive.companion.model.RepeatRowUi
import app.overdrive.companion.model.RepeatsContent
import app.overdrive.companion.model.RepeatsUi
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.StepTableUi
import app.overdrive.companion.model.TrendUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.PartialNote
import app.overdrive.uikit.ScreenHeader
import app.overdrive.uikit.StatusLine
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.ChartReadout
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.PlotChart
import app.overdrive.uikit.chart.RestPill
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

// region the list in the Rides tab

/** What the list of repeated sessions can do. */
class RepeatsActions(
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onOpen: (RepeatRowUi) -> Unit,
    val onMode: (RidesMode) -> Unit,
)

/** The Rides tab's second view (design/companion.md §4.12): the sessions ridden more than once, from the container. */
@Composable
fun RepeatsList(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by container.repeats.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(Unit) { container.screenOpened(RefreshTarget.Repeats) }
    val ui = remember(state, host) { RepeatsUi.of(state, container.words, host) }
    val listState = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Rides, listState)
    RepeatsView(
        ui,
        RepeatsActions(
            onRefresh = { container.refresh(RefreshTarget.Repeats) },
            onSettings = { container.nav.push(Route.Settings) },
            onOpen = { container.nav.push(Route.RepeatDetail(it.hash)) },
            onMode = container::setRidesMode,
        ),
        modifier,
        listState,
    )
}

/** The list of repeated sessions for a given [ui]: one card of rows, each a session. */
@Composable
fun RepeatsView(
    ui: ScreenUi<RepeatsContent>,
    actions: RepeatsActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        modifier.fillMaxSize().pullToRefresh(actions.onRefresh),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "header") { ScreenHeader("Rides", ui.subtitle, ui.refreshing, Modifier.padding(bottom = 16.dp)) }
        item(key = "mode") { ModeSelector(RidesMode.Repeats, actions.onMode) }
        ui.status?.let { problem ->
            item(key = "status") {
                StatusLine(
                    problem.title,
                    problem.cause,
                    Modifier.padding(bottom = 16.dp),
                    actionLabel = problem.action?.label,
                    onAction = { actions.onSettings() },
                )
            }
        }
        when (val body = ui.body) {
            is Body.Loading -> {
                item(key = "loading") { LoadingBlock(body.text, Modifier.heightIn(min = 480.dp)) }
            }

            is Body.Failed -> {
                item(key = "error") {
                    ErrorBlock(
                        "Could not reach your service",
                        body.problem.cause,
                        body.problem.hint,
                        actions.onRefresh,
                        Modifier.heightIn(min = 480.dp),
                        settingsLabel = body.problem.action?.label,
                        onSettings = actions.onSettings,
                    )
                }
            }

            is Body.Empty -> {
                item(key = "empty") { EmptyBlock(body.heading, body.text) }
            }

            is Body.Content -> {
                for (row in body.value.rows) {
                    item(key = row.hash, contentType = "repeat") { RepeatRow(row) { actions.onOpen(row) } }
                }
            }
        }
    }
}

/** A repeated session: its name, how often and when it was last ridden, and the latest attempt against the one before, in one line. */
@Composable
private fun RepeatRow(
    row: RepeatRowUi,
    onClick: () -> Unit,
) {
    val source = rememberInteractionSource()
    val endsCard = row.position == CardPosition.Last || row.position == CardPosition.Only
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (endsCard) 8.dp else 0.dp)
            .cardSlice(row.position)
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .heightIn(min = 88.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(row.title, style = OdType.bodyStrong, maxLines = 1)
            OdText(row.subtitle, style = OdType.labelNum, color = OverdriveColors.textSecondary, maxLines = 1)
            OdText(row.line, style = OdType.label, maxLines = 2)
        }
        OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textSecondary)
    }
}

// region the page of one session

/** What the page of a repeated session can do. */
class RepeatDetailActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    /** An attempt was tapped: the ride's own detail opens. */
    val onOpenRide: (String) -> Unit,
)

/** The page of one repeated session (design/companion.md §4.12) from the container. */
@Composable
fun RepeatDetailScreen(
    container: CompanionContainer,
    hash: String,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val resource = remember(hash) { container.repeatDetail(hash) }
    val state by resource.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(hash) { container.screenOpened(RefreshTarget.RepeatDetail(hash)) }
    val ui = remember(state, host) { RepeatDetailUi.of(state, container.words, host) }
    RepeatDetailView(
        ui,
        RepeatDetailActions(
            onBack = { container.nav.back() },
            onRefresh = { container.refresh(RefreshTarget.RepeatDetail(hash)) },
            onSettings = { container.nav.push(Route.Settings) },
            onOpenRide = { container.nav.push(Route.RideDetail(it)) },
        ),
        modifier,
    )
}

/** The page of a repeated session for a given [ui]: the attempts side by side, each measure a small chart over them. */
@Composable
fun RepeatDetailView(
    ui: ScreenUi<RepeatDetailContent>,
    actions: RepeatDetailActions,
    modifier: Modifier = Modifier,
    selection: ChartSelection = remember { ChartSelection() },
) {
    val content = (ui.body as? Body.Content)?.value
    var table by remember { mutableStateOf<ValuesTable?>(null) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(content?.name ?: "Repeated session", actions.onBack)
            LazyColumn(
                Modifier.fillMaxSize().pullToRefresh(actions.onRefresh),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ui.status?.let { problem ->
                    item(key = "status") {
                        StatusLine(
                            problem.title,
                            problem.cause,
                            actionLabel = problem.action?.label,
                            onAction = actions.onSettings,
                        )
                    }
                }
                when (val body = ui.body) {
                    is Body.Loading -> {
                        item(key = "loading") { LoadingBlock(body.text, Modifier.heightIn(min = 480.dp)) }
                    }

                    is Body.Failed -> {
                        item(key = "error") {
                            ErrorBlock(
                                "Could not reach your service",
                                body.problem.cause,
                                body.problem.hint,
                                actions.onRefresh,
                                Modifier.heightIn(min = 480.dp),
                                settingsLabel = body.problem.action?.label,
                                onSettings = actions.onSettings,
                            )
                        }
                    }

                    is Body.Empty -> {
                        item(key = "empty") { EmptyBlock(body.heading, body.text) }
                    }

                    is Body.Content -> {
                        repeatItems(body.value, selection, actions.onOpenRide) { table = it }
                    }
                }
            }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        BackHandler(enabled = table != null) { table = null }
    }
}

private fun LazyListScope.repeatItems(
    content: RepeatDetailContent,
    selection: ChartSelection,
    onOpenRide: (String) -> Unit,
    onValues: (ValuesTable) -> Unit,
) {
    item(key = "when") {
        OdText(
            content.whenLine,
            Modifier.padding(horizontal = 4.dp),
            style = OdType.label,
            color = OverdriveColors.textSecondary,
        )
    }
    item(key = "heart-rate") { AttemptChartCard(content.heartRate, selection, onValues) }
    item(key = "attempts") { AttemptsCard(content.attempts, onOpenRide) }
    content.steps?.let { steps -> item(key = "steps") { StepsCard(steps) } }
    item(key = "drift") { AttemptChartCard(content.drift, selection, onValues) }
    item(key = "compliance") { AttemptChartCard(content.compliance, selection, onValues) }
    item(key = "power") { AttemptChartCard(content.power, selection, onValues) }
    item(key = "duration") { AttemptChartCard(content.duration, selection, onValues) }
}

/**
 * One measure over the attempts (design/companion.md §6.10): the trend in words, the readout (the legend, with the **Latest** pill while a
 * past attempt is selected), the plot and the note under it. The selection is the page's: one attempt across every chart. With no figure
 * in any attempt the card says so in one sentence and draws no frame.
 */
@Composable
fun AttemptChartCard(
    ui: RepeatChartUi,
    selection: ChartSelection,
    onValues: (ValuesTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chart = ui.chart
    if (chart == null) {
        OdCard(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OdText(ui.heading, style = OdType.label, color = OverdriveColors.textTertiary)
            PartialNote(ui.emptyText.orEmpty())
        }
        return
    }
    val x = selection.x
    val readout = remember(chart, x) { chart.readoutAt(x) }
    ChartCard(chart.heading, modifier, onValues = { onValues(chart.table) }, note = chart.note) {
        ui.trend?.let { TrendLine(it) }
        ChartReadout(
            readout.x,
            readout.entries,
            away = selection.isAway(chart.spec.restX),
            onToday = selection::clear,
            rest = RestPill.Latest,
        )
        PlotChart(chart.spec, selection, "${chart.summary}. ${ui.trend?.text.orEmpty()}. ${readout.spoken()}")
    }
}

/** The service's trend in words: the sentence (bodyStrong) and how much it moved or how many attempts it lacks (label, text 2). */
@Composable
private fun TrendLine(trend: TrendUi) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OdText(trend.text, style = OdType.bodyStrong)
        OdText(trend.detail, style = OdType.labelNum, color = OverdriveColors.textSecondary)
    }
}

/** Every attempt newest first, with what it did; a tap opens the ride (design/companion.md §4.12). */
@Composable
private fun AttemptsCard(
    attempts: List<AttemptRowUi>,
    onOpenRide: (String) -> Unit,
) {
    OdCard(Modifier.fillMaxWidth(), PaddingValues(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 4.dp)) {
        OdText("Attempts", Modifier.padding(bottom = 8.dp), style = OdType.label, color = OverdriveColors.textTertiary)
        for ((index, attempt) in attempts.withIndex()) {
            if (index > 0) HorizontalRule()
            AttemptRow(attempt) { onOpenRide(attempt.rideId) }
        }
    }
}

@Composable
private fun AttemptRow(
    attempt: AttemptRowUi,
    onClick: () -> Unit,
) {
    val source = rememberInteractionSource()
    Row(
        Modifier
            .fillMaxWidth()
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = attempt.description }
            .heightIn(min = 64.dp)
            .padding(end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(attempt.title, style = OdType.bodyStrong, maxLines = 1)
            OdText(attempt.subtitle, style = OdType.labelNum, color = OverdriveColors.textSecondary, maxLines = 1)
            attempt.reason?.let {
                OdText(
                    it,
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                    maxLines = 3,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            OdText(attempt.value, style = OdType.numXS, maxLines = 1, softWrap = false)
            OdText(
                attempt.caption,
                style = OdType.captionNum,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
        OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textSecondary)
    }
}

/**
 * The average heart rate of each compared step in every attempt, side by side (design/companion.md §4.12): the step on the left stays, the
 * attempts scroll sideways (the latest at the right, in view when the card opens). The table is its own twin, so it has no Values button.
 */
@Composable
private fun StepsCard(table: StepTableUi) {
    val scroll = rememberScrollState()
    LaunchedEffect(table.headers.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    OdCard(Modifier.fillMaxWidth(), PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp)) {
        OdText(
            table.heading,
            Modifier.padding(bottom = 8.dp),
            style = OdType.label,
            color = OverdriveColors.textTertiary,
        )
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(STEP_NAME_WIDTH)) {
                Box(Modifier.heightIn(min = HEADER_HEIGHT).fillMaxWidth())
                for (row in table.rows) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        OdText(row.label, style = OdType.label, maxLines = 1)
                        OdText(
                            row.caption,
                            style = OdType.captionNum,
                            color = OverdriveColors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.horizontalScroll(scroll)) {
                    Row(Modifier.heightIn(min = HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                        for (header in table.headers) {
                            OdText(
                                header,
                                Modifier.width(CELL_WIDTH),
                                style = OdType.captionNum,
                                color = OverdriveColors.textTertiary,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                    for (row in table.rows) {
                        Row(
                            Modifier
                                .heightIn(min = ROW_HEIGHT)
                                .semantics(mergeDescendants = true) { contentDescription = row.description },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for ((index, cell) in row.cells.withIndex()) {
                                val latest = index == row.cells.lastIndex
                                OdText(
                                    cell,
                                    Modifier.width(CELL_WIDTH),
                                    style =
                                        if (latest) {
                                            OdType.labelNum.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        } else {
                                            OdType.labelNum
                                        },
                                    color = if (latest) OverdriveColors.textPrimary else OverdriveColors.textSecondary,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        table.note?.let {
            OdText(it, Modifier.padding(top = 8.dp), style = OdType.label, color = OverdriveColors.textSecondary)
        }
    }
}

private val STEP_NAME_WIDTH = 96.dp
private val CELL_WIDTH = 48.dp
private val ROW_HEIGHT = 48.dp
private val HEADER_HEIGHT = 24.dp
