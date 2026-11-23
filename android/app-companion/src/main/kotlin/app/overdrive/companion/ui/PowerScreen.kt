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
import app.overdrive.companion.model.PickerState
import app.overdrive.companion.model.PowerContent
import app.overdrive.companion.model.PowerSkillsBody
import app.overdrive.companion.model.PowerSkillsCardUi
import app.overdrive.companion.model.PowerSkillsUi
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.ProgressEntryUi
import app.overdrive.companion.model.ProgressUi
import app.overdrive.companion.model.RangePicker
import app.overdrive.companion.model.RangeWords
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.companion.model.Words
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ProgressRange
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
import java.time.LocalDate

/** A sheet over the Power tab: one duration or skill of the Power skills card, or the Custom range's date picker. */
sealed interface PowerSheet {
    data class Axis(
        val index: Int,
    ) : PowerSheet

    data class Skill(
        val id: PowerSkillId,
    ) : PowerSheet

    data class Picker(
        val state: PickerState,
    ) : PowerSheet
}

/**
 * What the Power skills card needs besides its own state: the days of the tablet rides (for the picker's dots), the phone's today and the
 * words, and what choosing a window does.
 */
class PowerSkillsHost(
    val ui: PowerSkillsCardUi,
    val rideDays: List<LocalDate>,
    val today: LocalDate,
    val words: Words,
    val onWindow: (PowerSkillsWindow) -> Unit,
)

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
    val window by container.powerSkillsWindow.collectAsStateWithLifecycle()
    val skillsResource = remember(window) { container.powerSkillsFor(window) }
    val skillsState by skillsResource.state.collectAsStateWithLifecycle()
    val rides by container.rides.view.collectAsStateWithLifecycle()
    // The row at the end of the page reads the default range's headline (ADR 0043).
    val progressState by container.progressFor(ProgressRange.DEFAULT).state.collectAsStateWithLifecycle()
    val progress = remember(progressState) { ProgressUi.entry(progressState) }
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(range) { container.screenOpened(RefreshTarget.Power) }
    LaunchedEffect(window) { container.screenOpened(RefreshTarget.PowerSkills(window)) }
    val metricsUi =
        remember(
            state,
            profile,
            answers,
            range,
            host,
        ) { PowerUi.of(state, profile, answers, range, container.words, host) }
    // The Power skills card has its own resource: when only it failed, its cause is the tab's problem line (design/companion.md §3.2).
    val ui =
        remember(metricsUi, skillsState, host) {
            metricsUi.copy(status = metricsUi.status ?: problemOf(skillsState, host, container.words))
        }
    val skills =
        PowerSkillsHost(
            ui = remember(skillsState, window) { PowerSkillsUi.of(skillsState, window, container.words) },
            rideDays =
                remember(rides) {
                    rides.list.items.filter { it.kind == RideKind.RIDE }.mapNotNull {
                        runCatching {
                            LocalDate.parse(
                                it.date,
                            )
                        }.getOrNull()
                    }
                },
            today = LocalDate.parse(container.words.today()),
            words = container.words,
            onWindow = container::setPowerSkillsWindow,
        )
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
        skills = skills,
        progress = progress,
        onProgress = { container.nav.push(Route.Progress) },
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
    skills: PowerSkillsHost? = null,
    initialSheet: PowerSheet? = null,
    initialTable: ValuesTable? = null,
    progress: ProgressEntryUi? = null,
    onProgress: () -> Unit = {},
) {
    // Power's charts have different x axes: each selects on its own (design/companion.md §3.5).
    val history = remember(range) { ChartSelection() }
    var table by remember { mutableStateOf(initialTable) }
    var setting by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(initialSheet) }
    val radar = (skills?.ui?.body as? PowerSkillsBody.Radar)?.content
    Box(modifier) {
        TabScreen(
            title = "Power",
            ui = ui,
            onRefresh = actions.range.onRefresh,
            onSettings = actions.range.onSettings,
            onPhoneSettings = actions.range.onPhoneSettings,
            state = listState,
            controls = {
                // In every state but the content the selector sits where it always did; with content it moves under the Power skills card,
                // which has its own window (design/companion.md §4.4).
                if (ui.body !is Body.Content) item(key = "range") { RangeSelectorRow(range, actions) }
            },
        ) { content ->
            item(key = "ftp") { FtpCard(content.ftp, actions, onSetFtp = { setting = true }) }
            if (skills != null) {
                item(key = "skills") {
                    PowerSkillsCard(
                        skills.ui,
                        selectedAxis = { (sheet as? PowerSheet.Axis)?.index },
                        actions =
                            PowerSkillsActions(
                                onSegment = { segment ->
                                    when (segment) {
                                        0 -> skills.onWindow(PowerSkillsWindow.EightWeeks)
                                        1 -> skills.onWindow(PowerSkillsWindow.AllTime)
                                        else -> sheet = PowerSheet.Picker(openPicker(skills))
                                    }
                                },
                                onChange = { sheet = PowerSheet.Picker(openPicker(skills)) },
                                onClear = { skills.onWindow(PowerSkillsWindow.EightWeeks) },
                                onValues = { table = skills.ui.table },
                                onAxis = { sheet = PowerSheet.Axis(it) },
                                onSkill = { sheet = PowerSheet.Skill(it) },
                                onOpenRide = actions.onOpenRide,
                            ),
                    )
                }
            }
            item(key = "range") { RangeSelectorRow(range, actions) }
            content.curve?.let { chart ->
                item(key = "curve") { LineChartCard(chart, curve, onValues = { table = chart.table }) }
            }
            if (content.bestEfforts.isNotEmpty()) {
                item(key = "best") { BestEffortsCard(content.rangeWords, content.bestEfforts, actions.onOpenRide) }
            }
            item(key = "history") {
                LineChartCard(content.ftpHistory, history, onValues = { table = content.ftpHistory.table })
            }
            if (progress != null) item(key = "progress") { ProgressEntryRow(progress, onProgress) }
        }
        ValuesSheetFor(table, onDismiss = { table = null })
        val openRide: (String) -> Unit = {
            sheet = null
            actions.onOpenRide(it)
        }
        AxisSheet(radar?.axes?.getOrNull((sheet as? PowerSheet.Axis)?.index ?: -1), { sheet = null }, openRide)
        SkillSheet(
            (sheet as? PowerSheet.Skill)?.let { open -> radar?.skills?.firstOrNull { it.id == open.id } },
            { sheet = null },
            openRide,
        )
        if (skills != null) {
            RangePickerSheet(
                (sheet as? PowerSheet.Picker)?.state,
                skills.today,
                skills.rideDays,
                skills.words,
                onChange = { sheet = PowerSheet.Picker(it) },
                onShow = { first, last ->
                    sheet = null
                    skills.onWindow(PowerSkillsWindow.Custom(first.toString(), last.toString()))
                },
                onDismiss = { sheet = null },
            )
        }
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
        BackHandler(enabled = table != null || setting || sheet != null) {
            // The sheet over the card closes first and changes nothing (design/companion.md §4.14).
            if (sheet != null) {
                sheet = null
            } else {
                table = null
                setting = false
            }
        }
    }
}

@Composable
private fun RangeSelectorRow(
    range: MetricsRange,
    actions: PowerActions,
) {
    RangeSelector(
        RangeWords.labels,
        MetricsRange.entries.indexOf(range),
        { actions.range.onRange(MetricsRange.entries[it]) },
    )
}

/** The picker opens on the month of the current custom end, else this month; it starts with nothing chosen. */
private fun openPicker(skills: PowerSkillsHost): PickerState {
    val current = (skills.ui.window as? PowerSkillsWindow.Custom)?.to?.let(LocalDate::parse)
    return RangePicker.open(skills.today, current)
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
