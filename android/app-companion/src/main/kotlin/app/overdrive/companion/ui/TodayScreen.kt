package app.overdrive.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.FtpUi
import app.overdrive.companion.model.PlannedRowUi
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.TodayContent
import app.overdrive.companion.model.TodayUi
import app.overdrive.core.remote.MetricsRange
import app.overdrive.uikit.FactUi
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.RecoveryCard
import app.overdrive.uikit.StackedNotice
import app.overdrive.uikit.chart.ProfileLook
import app.overdrive.uikit.chart.WorkoutProfile
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** What the Today screen can do. */
class TodayActions(
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
    val onPhoneSettings: () -> Unit,
    val onUseFtp: (Int) -> Unit,
    val onKeepFtp: (Int) -> Unit,
    val onOpenRide: (String) -> Unit,
    /** A fact row of the recovery card was tapped: Wellness opens at that metric. */
    val onOpenWellness: (String) -> Unit,
    val onOpenNotes: () -> Unit,
    val onCopy: (String) -> Unit,
)

/** Today (design/companion.md §4.1) from the container. */
@Composable
fun TodayScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val metrics by container.metricsFor(MetricsRange.DEFAULT).state.collectAsStateWithLifecycle()
    val workouts by container.workouts.state.collectAsStateWithLifecycle()
    val rides by container.rides.view.collectAsStateWithLifecycle()
    val profile by container.profile.state.collectAsStateWithLifecycle()
    val answers by container.ftpAnswers.state.collectAsStateWithLifecycle()
    val justUsed by container.justUsed.collectAsStateWithLifecycle()
    val recovery by container.recovery.state.collectAsStateWithLifecycle()
    val insights by container.insights.view.collectAsStateWithLifecycle()
    val watch by container.healthStatus.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(Unit) { container.screenOpened(RefreshTarget.Today) }
    val ui =
        remember(recovery, metrics, workouts, rides, profile, answers, justUsed, host, insights, watch) {
            TodayUi.of(
                recovery,
                metrics,
                workouts,
                rides,
                profile,
                answers,
                justUsed,
                today = container.clock.today(),
                words = container.words,
                host = host,
                insights = insights,
                watch = watch,
            )
        }
    val state = rememberLazyListState()
    ScrollToTopOn(container.nav, Tab.Today, state)
    TodayView(
        ui,
        TodayActions(
            onRefresh = { container.refresh(RefreshTarget.Today) },
            onSettings = { container.nav.push(Route.Settings) },
            onPhoneSettings = { openAppSettings(context) },
            onUseFtp = container::useFtp,
            onKeepFtp = container::keepFtp,
            onOpenRide = { container.nav.push(Route.RideDetail(it)) },
            onOpenWellness = container::focusWellness,
            onOpenNotes = { container.nav.push(Route.Notes) },
            onCopy = { copyToClipboard(context, "Question for your coach", it) },
        ),
        modifier,
        state,
    )
}

/** The Today screen for a given [ui]: stateless, so every state can be drawn from sample data. */
@Composable
fun TodayView(
    ui: ScreenUi<TodayContent>,
    actions: TodayActions,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    TabScreen(
        title = "Today",
        ui = ui,
        onRefresh = actions.onRefresh,
        onSettings = actions.onSettings,
        onPhoneSettings = actions.onPhoneSettings,
        modifier = modifier,
        state = state,
        trailing = { OdIconButton(OverdriveIcons.Settings, actions.onSettings, "Settings") },
    ) { content ->
        content.recovery?.let { recovery ->
            item(key = "recovery") {
                RecoveryCard(
                    label = recovery.label,
                    look = recovery.look,
                    reason = recovery.reason,
                    facts =
                        recovery.facts.map { fact ->
                            FactUi(fact.key, fact.name, fact.value, fact.unit, fact.caption, fact.outside) {
                                actions.onOpenWellness(fact.key)
                            }
                        },
                    heading = recovery.heading,
                    progress = recovery.progress,
                )
            }
        }
        content.planned?.let { planned -> item(key = "planned") { PlannedCard(planned, actions.onOpenRide) } }
        content.ftp?.let { ftp -> item(key = "ftp") { FtpNotice(ftp, actions) } }
        content.note?.let { note -> item(key = "note") { TodayNoteCard(note, actions.onOpenNotes, actions.onCopy) } }
    }
}

@Composable
private fun PlannedCard(
    rows: List<PlannedRowUi>,
    onOpenRide: (String) -> Unit,
) {
    OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OdText("Planned today", style = OdType.label, color = OverdriveColors.textTertiary)
        if (rows.isEmpty()) {
            OdText("Nothing planned today", style = OdType.body, color = OverdriveColors.textSecondary)
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) app.overdrive.uikit.HorizontalRule(Modifier.padding(vertical = 4.dp))
            val rideId = row.rideId
            val source = rememberInteractionSource()
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (rideId != null) Modifier.plainClickable(source) { onOpenRide(rideId) } else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OdText(row.name, style = OdType.heading)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.done != null) {
                        OdIcon(OverdriveIcons.Check, size = InlineIconSize, tint = OverdriveColors.textSecondary)
                    }
                    OdText(
                        listOfNotNull(row.done, row.subtitle.takeIf { it.isNotEmpty() }).joinToString(" · "),
                        style = OdType.labelNum,
                        color = OverdriveColors.textSecondary,
                    )
                }
                row.profile?.let { profile ->
                    val chart = Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp)
                    WorkoutProfile(profile, ProfileLook.Card, chart)
                }
            }
        }
    }
}

@Composable
private fun FtpNotice(
    ftp: FtpUi,
    actions: TodayActions,
) {
    when (ftp) {
        is FtpUi.Suggestion -> {
            StackedNotice(
                icon = OverdriveIcons.Zap,
                title = "Your FTP may be ${ftp.watts} W",
                text =
                    buildString {
                        append("Your best efforts of the last ${ftp.windowWeeks} weeks point to ${ftp.watts} W")
                        if (ftp.current != null) append("; it is ${ftp.current} W now")
                        append(". Zones change only when you confirm.")
                        if (ftp.waiting) append(" $QUEUED")
                    },
                primaryLabel = "Use ${ftp.watts} W",
                onPrimary = { actions.onUseFtp(ftp.watts) },
                secondaryLabel = ftp.current?.let { "Keep $it W" } ?: "Not now",
                onSecondary = { actions.onKeepFtp(ftp.id) },
            )
        }

        is FtpUi.Used -> {
            Notice(
                OverdriveIcons.Check,
                "FTP is now ${ftp.watts} W",
                if (ftp.waiting) QUEUED else "The tablet uses it from its next sync.",
            )
        }

        FtpUi.Queued -> {
            Notice(OverdriveIcons.Info, "Answer saved", QUEUED)
        }
    }
}

private const val QUEUED = "Will be sent when the service can be reached."
