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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.ActivityUi
import app.overdrive.companion.model.ActivityWords
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.CardPosition
import app.overdrive.companion.model.LapUi
import app.overdrive.companion.model.RideDetailContent
import app.overdrive.companion.model.RideDetailUi
import app.overdrive.companion.model.RideRowUi
import app.overdrive.companion.model.RidesContent
import app.overdrive.companion.model.RidesEntry
import app.overdrive.companion.model.RidesUi
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.core.metrics.RideKind
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.ScreenHeader
import app.overdrive.uikit.StatusLine
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.ChartReadout
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.RideChart
import app.overdrive.uikit.chart.ZoneTimeBars
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** What the Rides list can do. */
class RidesActions(
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onPhoneSettings: () -> Unit,
    val onOpen: (RideRowUi) -> Unit,
    val onNearEnd: () -> Unit,
)

/** Rides (design/companion.md §4.6) from the container. */
@Composable
fun RidesScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val view by container.rides.view.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(Unit) { container.screenOpened(RefreshTarget.Rides) }
    val ui = remember(view, host) { RidesUi.of(view, container.words, host) }
    val listState = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Rides, listState)
    RidesView(
        ui,
        RidesActions(
            onRefresh = { container.refresh(RefreshTarget.Rides) },
            onSettings = { container.nav.push(Route.Settings) },
            onPhoneSettings = { openAppSettings(context) },
            onOpen = {
                container.nav.push(
                    if (it.kind ==
                        RideKind.ACTIVITY
                    ) {
                        Route.ActivityDetail(it.id)
                    } else {
                        Route.RideDetail(it.id)
                    },
                )
            },
            onNearEnd = container::loadMoreRides,
        ),
        modifier,
        listState,
    )
}

/** The Rides list for a given [ui]: months in a lazy list, a card of rows per month, the next page when the end is near. */
@Composable
fun RidesView(
    ui: ScreenUi<RidesContent>,
    actions: RidesActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val content = (ui.body as? Body.Content)?.value
    val nearEnd by remember(listState, content) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            content != null && last >= listState.layoutInfo.totalItemsCount - NEAR_END_ITEMS
        }
    }
    LaunchedEffect(nearEnd, content?.hasMore, content?.entries?.size) {
        if (nearEnd && content?.hasMore == true && !content.loadingMore &&
            content.moreProblem == null
        ) {
            actions.onNearEnd()
        }
    }
    LazyColumn(
        modifier.fillMaxSize().pullToRefresh(actions.onRefresh),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "header") { ScreenHeader("Rides", ui.subtitle, ui.refreshing, Modifier.padding(bottom = 16.dp)) }
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
                rideItems(body.value, actions)
            }
        }
    }
}

private fun LazyListScope.rideItems(
    content: RidesContent,
    actions: RidesActions,
) {
    for (entry in content.entries) {
        when (entry) {
            is RidesEntry.Month -> {
                item(key = entry.key, contentType = "month") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                OverdriveColors.bg,
                            ).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        OdText(entry.month, style = OdType.label, color = OverdriveColors.textTertiary)
                        OdText(entry.total, style = OdType.labelNum, color = OverdriveColors.textTertiary)
                    }
                }
            }

            is RidesEntry.Row -> {
                item(key = entry.key, contentType = "row") {
                    RideRow(entry.row, Modifier.padding(bottom = if (entry.row.position.endsCard()) 8.dp else 0.dp)) {
                        actions.onOpen(entry.row)
                    }
                }
            }
        }
    }
    if (content.loadingMore) item(key = "more") { LoadingBlock("Loading more rides…", Modifier.heightIn(max = 64.dp)) }
    content.moreProblem?.let { problem ->
        item(key = "more-problem") {
            OdText(problem.cause, Modifier.padding(8.dp), style = OdType.label, color = OverdriveColors.textSecondary)
        }
    }
}

private fun CardPosition.endsCard() = this == CardPosition.Last || this == CardPosition.Only

@Composable
private fun RideRow(
    row: RideRowUi,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = rememberInteractionSource()
    Row(
        modifier
            .fillMaxWidth()
            .cardSlice(row.position)
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(row.title, style = OdType.bodyStrong, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.watch) {
                    OdIcon(
                        OverdriveIcons.HeartPulse,
                        size = InlineIconSize,
                        tint = OverdriveColors.textSecondary,
                    )
                }
                OdText(row.subtitle, style = OdType.labelNum, color = OverdriveColors.textSecondary, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            OdText(row.load, style = OdType.numXS, maxLines = 1, softWrap = false)
            OdText(
                row.caption,
                style = OdType.captionNum,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private const val NEAR_END_ITEMS = 8

// region ride detail

/** What the ride detail can do. */
class RideDetailActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
)

/** The ride detail (design/companion.md §4.7) from the container. */
@Composable
fun RideDetailScreen(
    container: CompanionContainer,
    id: String,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val resource = remember(id) { container.rideDetail(id) }
    val state by resource.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(id) { container.screenOpened(RefreshTarget.RideDetail(id)) }
    val ui = remember(state, host) { RideDetailUi.of(state, container.words, host) }
    RideDetailView(
        ui,
        RideDetailActions(
            onBack = { container.nav.back() },
            onRefresh = { container.refresh(RefreshTarget.RideDetail(id)) },
            onSettings = { container.nav.push(Route.Settings) },
        ),
        modifier,
    )
}

/** The ride detail for a given [ui]. */
@Composable
fun RideDetailView(
    ui: ScreenUi<RideDetailContent>,
    actions: RideDetailActions,
    modifier: Modifier = Modifier,
    selection: ChartSelection = remember { ChartSelection() },
) {
    val content = (ui.body as? Body.Content)?.value
    // One selection across the three plots (design/companion.md §3.5).
    var table by remember { mutableStateOf<ValuesTable?>(null) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            RideTopBar(content, actions.onBack)
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
                        rideDetailItems(body.value, selection) { table = it }
                    }
                }
            }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        BackHandler(enabled = table != null) { table = null }
    }
}

@Composable
private fun RideTopBar(
    content: RideDetailContent?,
    onBack: () -> Unit,
) {
    BackTopBar(content?.name ?: "Ride", onBack)
}

private fun LazyListScope.rideDetailItems(
    ride: RideDetailContent,
    selection: ChartSelection,
    onValues: (ValuesTable) -> Unit,
) {
    item(key = "when") {
        OdText(
            ride.whenLine,
            Modifier.padding(horizontal = 4.dp),
            style = OdType.label,
            color = OverdriveColors.textSecondary,
        )
    }
    item(key = "tiles") { Tiles(ride.tiles) }
    ride.plan?.let { plan ->
        item(key = "plan") {
            OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OdText("Planned workout", style = OdType.label, color = OverdriveColors.textTertiary)
                OdText(plan.title, style = OdType.bodyStrong)
                if (plan.efforts !=
                    null
                ) {
                    OdText(plan.efforts, style = OdType.label, color = OverdriveColors.textSecondary)
                }
            }
        }
    }
    item(key = "chart") {
        val chart = ride.chart
        val x = selection.x
        val readout = remember(chart, x) { chart.readoutAt(x) }
        ChartCard(chart.heading, onValues = { onValues(chart.table) }) {
            ChartReadout(
                readout.x,
                readout.entries,
                away = selection.isAway(chart.spec.restX),
                onToday = selection::clear,
            )
            RideChart(chart.spec, selection, "${chart.summary}. ${readout.spoken()}")
            if (chart.noHeartRate) {
                OdText(
                    "No heart rate in this ride.",
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                )
            }
            if (chart.noWBalance) {
                OdText(
                    "W′ balance needs an FTP.",
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                )
            }
        }
    }
    item(key = "zones") {
        OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OdText("Time in power zones", style = OdType.label, color = OverdriveColors.textTertiary)
            ZoneTimeBars(ride.zones)
        }
    }
    ride.laps?.let { laps -> item(key = "laps") { LapsCard(laps) } }
}

@Composable
private fun LapsCard(laps: List<LapUi>) {
    OdCard(Modifier.fillMaxWidth()) {
        OdText("Laps", Modifier.padding(bottom = 8.dp), style = OdType.label, color = OverdriveColors.textTertiary)
        LapHeader()
        for (lap in laps) {
            HorizontalRule()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdText(
                    lap.index.toString(),
                    Modifier.padding(end = 4.dp),
                    style = OdType.labelNum,
                    color = OverdriveColors.textTertiary,
                )
                OdText(lap.name, Modifier.weight(1f), style = OdType.label, maxLines = 1)
                OdText(
                    lap.time,
                    Modifier.padding(end = 4.dp),
                    style = OdType.labelNum,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.End) {
                    OdText(lap.watts, style = OdType.labelNum, maxLines = 1)
                    if (lap.target !=
                        null
                    ) {
                        OdText(
                            lap.target,
                            style = OdType.captionNum,
                            color = OverdriveColors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
                OdText(
                    lap.heart,
                    Modifier.width(48.dp),
                    style = OdType.labelNum,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LapHeader() {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OdText("#", Modifier.padding(end = 4.dp), style = OdType.caption, color = OverdriveColors.textTertiary)
        OdText("Step", Modifier.weight(1f), style = OdType.caption, color = OverdriveColors.textTertiary)
        OdText("Time", style = OdType.caption, color = OverdriveColors.textTertiary)
        OdText(
            "Watts",
            Modifier.width(48.dp),
            style = OdType.caption,
            color = OverdriveColors.textTertiary,
            textAlign = TextAlign.End,
        )
        OdText(
            "Heart",
            Modifier.width(48.dp),
            style = OdType.caption,
            color = OverdriveColors.textTertiary,
            textAlign = TextAlign.End,
        )
    }
}

// region activity detail

/** The watch activity of the list (design/companion.md §4.8): what the list says, until the watch data stage brings the rest. */
@Composable
fun ActivityScreen(
    container: CompanionContainer,
    id: String,
    modifier: Modifier = Modifier,
) {
    val view by container.rides.view.collectAsStateWithLifecycle()
    val item = view.list.items.firstOrNull { it.kind == RideKind.ACTIVITY && it.id == id }
    ActivityView(item?.let { ActivityWords.of(it, container.words) }, { container.nav.back() }, modifier)
}

/** The activity screen for a given [ui]. */
@Composable
fun ActivityView(
    ui: ActivityUi?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar(ui?.name ?: "Activity", onBack)
        if (ui != null) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    OdText(
                        ui.whenLine,
                        Modifier.padding(horizontal = 4.dp),
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                    )
                }
                item { Tiles(ui.tiles, columns = 3) }
                item { Tiles(listOf(ui.load), columns = 1) }
                item {
                    OdText(
                        ui.countsLine,
                        Modifier.padding(horizontal = 4.dp),
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                    )
                }
            }
        }
    }
}
