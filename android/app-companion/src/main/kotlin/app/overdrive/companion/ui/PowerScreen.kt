package app.overdrive.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.BestEffortUi
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.FtpFacts
import app.overdrive.companion.model.FtpUi
import app.overdrive.companion.model.PowerContent
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.RangeWords
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.core.remote.MetricsRange
import app.overdrive.uikit.BottomSheet
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.RangeSelector
import app.overdrive.uikit.Stepper
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** What the Power screen can do. */
class PowerActions(
    val range: RangeActions,
    val onUseFtp: (Int) -> Unit,
    val onKeepFtp: (Int) -> Unit,
    val onOpenRide: (String) -> Unit,
)

/** Power (design/companion.md §4.4) from the container. */
@Composable
fun PowerScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val range = settings?.powerRange ?: MetricsRange.DEFAULT
    val state by container.metricsFor(range).state.collectAsStateWithLifecycle()
    val profile by container.profile.state.collectAsStateWithLifecycle()
    val answers by container.ftpAnswers.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(range) { container.screenOpened(RefreshTarget.Power) }
    val ui =
        remember(
            state,
            profile,
            answers,
            range,
            host,
        ) { PowerUi.of(state, profile, answers, range, container.words, host) }
    val listState = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Power, listState)
    PowerView(
        ui,
        range,
        PowerActions(
            RangeActions(
                onRefresh = { container.refresh(RefreshTarget.Power) },
                onSettings = { container.nav.push(Route.Settings) },
                onPhoneSettings = { openAppSettings(context) },
                onRange = container::setPowerRange,
            ),
            onUseFtp = container::useFtp,
            onKeepFtp = container::keepFtp,
            onOpenRide = { container.nav.push(Route.RideDetail(it)) },
        ),
        modifier,
        listState,
    )
}

/** The Power screen for a given [ui]. */
@Composable
fun PowerView(
    ui: ScreenUi<PowerContent>,
    range: MetricsRange,
    actions: PowerActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    curve: ChartSelection = remember(range) { ChartSelection() },
) {
    // Power's charts have different x axes: each selects on its own (design/companion.md §3.5).
    val history = remember(range) { ChartSelection() }
    val drift = remember(range) { ChartSelection() }
    var table by remember { mutableStateOf<ValuesTable?>(null) }
    var setting by remember { mutableStateOf(false) }
    Box(modifier) {
        TabScreen(
            title = "Power",
            ui = ui,
            onRefresh = actions.range.onRefresh,
            onSettings = actions.range.onSettings,
            onPhoneSettings = actions.range.onPhoneSettings,
            state = listState,
            controls = {
                item(key = "range") {
                    RangeSelector(
                        RangeWords.labels,
                        MetricsRange.entries.indexOf(range),
                        { actions.range.onRange(MetricsRange.entries[it]) },
                    )
                }
            },
        ) { content ->
            item(key = "ftp") { FtpCard(content.ftp, actions, onSetFtp = { setting = true }) }
            content.curve?.let { chart ->
                item(key = "curve") { LineChartCard(chart, curve, onValues = { table = chart.table }) }
            }
            if (content.bestEfforts.isNotEmpty()) {
                item(key = "best") { BestEffortsCard(content.rangeWords, content.bestEfforts, actions.onOpenRide) }
            }
            item(key = "history") {
                LineChartCard(content.ftpHistory, history, onValues = { table = content.ftpHistory.table })
            }
            item(key = "drift") {
                val chart = content.drift
                if (chart != null) {
                    LineChartCard(chart, drift, onValues = { table = chart.table })
                } else {
                    OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OdText("Aerobic drift", style = OdType.label, color = OverdriveColors.textTertiary)
                        OdText(
                            "No steady rides yet. Drift is measured on steady rides of 40 minutes or more with heart rate.",
                            style = OdType.body,
                            color = OverdriveColors.textSecondary,
                        )
                    }
                }
            }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        SetFtpSheet(
            setting,
            (ui.body as? Body.Content)?.value?.ftp?.estimate,
            {
                setting = false
            },
        ) {
            setting = false
            actions.onUseFtp(it)
        }
        BackHandler(enabled = table != null || setting) {
            table = null
            setting = false
        }
    }
}

@Composable
private fun FtpCard(
    ftp: FtpFacts,
    actions: PowerActions,
    onSetFtp: () -> Unit,
) {
    val answer = ftp.answer as? FtpUi.Suggestion
    OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ftp.current == null) {
            OdText("FTP", style = OdType.label, color = OverdriveColors.textTertiary)
            OdText("No FTP set", style = OdType.heading)
            OdText(
                "Rides use the tablet's own FTP until you set one here.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
            OdButton("Set FTP", onSetFtp, Modifier.padding(top = 4.dp))
            return@OdCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OdText("FTP", style = OdType.label, color = OverdriveColors.textTertiary)
                ValueWithUnit(ftp.current.toString(), "W", OdType.numM)
                OdText(
                    listOfNotNull(
                        ftp.setLine,
                        if (answer == null) ftp.estimate?.let { "estimated $it W" } else null,
                    ).joinToString(" · "),
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                )
            }
            if (answer != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OdText("Estimated", style = OdType.label, color = OverdriveColors.textTertiary)
                    ValueWithUnit(answer.watts.toString(), "W", OdType.numM)
                    OdText(answer.basis, style = OdType.label, color = OverdriveColors.textSecondary)
                }
            }
        }
        if (answer != null) {
            OdText(
                "Your best efforts point to a higher FTP. Zones change only when you confirm." +
                    if (answer.waiting) " Will be sent when the service can be reached." else "",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OdButton("Use ${answer.watts} W", { actions.onUseFtp(answer.watts) })
                OdButton("Keep ${ftp.current} W", { actions.onKeepFtp(answer.id) }, variant = ButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun BestEffortsCard(
    rangeWords: String,
    efforts: List<BestEffortUi>,
    onOpenRide: (String) -> Unit,
) {
    OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        OdText(
            "Best efforts · $rangeWords",
            Modifier.padding(bottom = 8.dp),
            style = OdType.label,
            color = OverdriveColors.textTertiary,
        )
        efforts.forEachIndexed { index, effort ->
            if (index > 0) HorizontalRule()
            val source = rememberInteractionSource()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .plainClickable(source, role = Role.Button) { onOpenRide(effort.rideId) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdText(effort.duration, Modifier.weight(1f), style = OdType.body)
                ValueWithUnit(effort.watts.toString(), "W", OdType.numXS)
                OdText(effort.date, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                OdIcon(OverdriveIcons.ChevronRight, size = 16.dp, tint = OverdriveColors.textSecondary)
            }
        }
    }
}

/** The sheet that sets the first FTP: a stepper in whole watts, starting from the estimate when there is one. */
@Composable
private fun SetFtpSheet(
    visible: Boolean,
    estimate: Int?,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var watts by remember(estimate) { mutableIntStateOf(estimate ?: DEFAULT_FTP) }
    BottomSheet(visible, onDismiss) {
        Column(Modifier.padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OdText("Set FTP", style = OdType.title)
            OdText(
                "The watts you can hold for an hour. Zones follow it.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
            Stepper(
                "$watts W",
                { watts = (watts - 1).coerceAtLeast(MIN_FTP) },
                { watts = (watts + 1).coerceAtMost(MAX_FTP) },
                Modifier.align(Alignment.CenterHorizontally),
                canDecrement = watts > MIN_FTP,
                canIncrement = watts < MAX_FTP,
                label = "FTP",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OdButton("Set FTP", { onSet(watts) }, Modifier.weight(1f), variant = ButtonVariant.Primary)
                OdButton("Cancel", onDismiss, Modifier.weight(1f))
            }
        }
    }
}

private const val DEFAULT_FTP = 200
private const val MIN_FTP = 30
private const val MAX_FTP = 2000
