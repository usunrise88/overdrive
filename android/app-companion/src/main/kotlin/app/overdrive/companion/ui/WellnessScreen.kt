package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.overdrive.companion.model.CardHead
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.companion.model.WatchAccess
import app.overdrive.companion.model.WellnessCardUi
import app.overdrive.companion.model.WellnessContent
import app.overdrive.companion.model.WellnessRangeWords
import app.overdrive.companion.model.WellnessUi
import app.overdrive.core.remote.WellnessRange
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.RangeSelector
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.PlotChart
import app.overdrive.uikit.chart.ShowingLine
import app.overdrive.uikit.chart.SleepTimingChart
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** What Wellness can do. */
class WellnessActions(
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onPhoneSettings: () -> Unit,
    val onRange: (WellnessRange) -> Unit,
    val onAllow: () -> Unit,
)

/** Wellness (design/companion.md §4.5) from the container; the range is remembered, the selection is one for all the cards. */
@Composable
fun WellnessScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val range = settings?.wellnessRange ?: WellnessRange.DEFAULT
    val state by container.wellnessFor(range).state.collectAsStateWithLifecycle()
    val health by container.healthStatus.collectAsStateWithLifecycle()
    val focus by container.wellnessFocus.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(range) { container.screenOpened(RefreshTarget.Wellness) }
    val access =
        when {
            !health.checked -> WatchAccess.Unknown
            health.access.granted.isEmpty() -> WatchAccess.None
            else -> WatchAccess.Some
        }
    val ui = remember(state, range, access, host) { WellnessUi.of(state, range, access, container.words, host) }
    val listState = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Wellness, listState)
    val request = rememberHealthAccessRequest(container)
    WellnessView(
        ui,
        range,
        WellnessActions(
            onRefresh = { container.refresh(RefreshTarget.Wellness) },
            onSettings = { container.nav.push(Route.Settings) },
            onPhoneSettings = { openAppSettings(context) },
            onRange = container::setWellnessRange,
            onAllow = request,
        ),
        modifier,
        listState,
        focus = focus,
        onFocused = { container.focusWellness(null) },
    )
}

/** The Wellness screen for a given [ui]. */
@Composable
fun WellnessView(
    ui: ScreenUi<WellnessContent>,
    range: WellnessRange,
    actions: WellnessActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    initialSheet: ValuesTable? = null,
    selection: ChartSelection = remember(range) { ChartSelection() },
    focus: String? = null,
    onFocused: () -> Unit = {},
) {
    var table by remember { mutableStateOf(initialSheet) }
    val content = (ui.body as? app.overdrive.companion.model.Body.Content)?.value
    // The recovery card's fact row opened this tab at one metric: its card comes to the top once, then the request is forgotten.
    LaunchedEffect(focus, content) {
        if (focus != null && content != null) {
            val card = content.cards.indexOfFirst { it.key == focus }
            if (card >= 0) listState.scrollToItem(CARDS_FROM + (if (ui.status != null) 1 else 0) + card)
            onFocused()
        }
    }
    Box(modifier) {
        TabScreen(
            title = "Wellness",
            ui = ui,
            onRefresh = actions.onRefresh,
            onSettings = actions.onSettings,
            onPhoneSettings = actions.onPhoneSettings,
            state = listState,
            onEmptyAction = actions.onAllow,
            controls = {
                // The range selector and, under it, the line that says which day the cards show: in one item, so selecting a day
                // never recomposes the list.
                item(key = "range") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RangeSelector(
                            WellnessRangeWords.labels,
                            WellnessRange.entries.indexOf(range),
                            { actions.onRange(WellnessRange.entries[it]) },
                        )
                        if (content != null && selection.isAway(content.lastDay)) {
                            ShowingLine("Showing ${content.dayAt(selection.x ?: content.lastDay)}", selection::clear)
                        }
                    }
                }
            },
        ) { shown ->
            for (card in shown.cards) {
                item(key = card.key) { WellnessCard(card, selection, onValues = { table = card.table }) }
            }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        BackHandler(enabled = table != null) { table = null }
    }
}

/** Items before the first card of a Wellness list: the header and the range selector. */
private const val CARDS_FROM = 2

/** One small multiple (design/companion.md §4.5): the name and usual range, the figure and the verdict, then the chart. */
@Composable
fun WellnessCard(
    card: WellnessCardUi,
    selection: ChartSelection,
    onValues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = selection.x
    val head = remember(card, x) { card.headAt(x) }
    OdCard(
        modifier.fillMaxWidth(),
        PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
        Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            OdText(
                card.title,
                Modifier.weight(1f),
                style = OdType.label,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
            )
            if (card.empty == null) {
                OdText(
                    head.usual,
                    Modifier.weight(1.6f),
                    style = OdType.labelNum,
                    color = OverdriveColors.textTertiary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                )
                ValuesButton(onValues)
            }
        }
        val empty = card.empty
        if (empty != null) {
            OdText(empty, style = OdType.body, color = OverdriveColors.textSecondary)
            return@OdCard
        }
        ValueLine(head)
        val readout = card.summary + ". " + readoutWords(head)
        card.plot?.let { PlotChart(it, selection, readout) }
        card.sleep?.let { SleepTimingChart(it, selection, readout) }
    }
}

/** The figure and the verdict, for TalkBack too: "Resting heart rate, 4 weeks. 52 bpm. In your usual range". */
private fun readoutWords(head: CardHead): String =
    listOfNotNull(
        head.value?.let {
            it + (
                head.unit?.let { u ->
                    " $u"
                } ?: ""
            )
        } ?: "No reading",
        head.verdict,
    ).joinToString(". ")

@Composable
private fun ValuesButton(onClick: () -> Unit) {
    val source = rememberInteractionSource()
    Box(
        Modifier
            .requiredHeight(48.dp)
            .widthIn(min = 48.dp)
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        OdText("Values", style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1, softWrap = false)
    }
}

/** The selected day's figure (numS and its unit) with the verdict at the right (design/companion.md §6.7, §7.4). */
@Composable
private fun ValueLine(head: CardHead) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { ValueWithUnit(head.value, head.unit, OdType.numS) }
        val verdict = head.verdict
        if (verdict != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (head.alert) OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize)
                OdText(
                    verdict,
                    style = OdType.label,
                    color =
                        when {
                            head.alert -> OverdriveColors.textPrimary
                            head.value == null -> OverdriveColors.textTertiary
                            else -> OverdriveColors.textSecondary
                        },
                    maxLines = 2,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
