package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.EffortBody
import app.overdrive.companion.model.EffortCardUi
import app.overdrive.companion.model.EffortContent
import app.overdrive.companion.model.EffortRowUi
import app.overdrive.companion.model.EffortSource
import app.overdrive.companion.model.EffortWeekView
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.ColumnChart
import app.overdrive.uikit.chart.DayStrip
import app.overdrive.uikit.chart.RestPill
import app.overdrive.uikit.chart.TodayPill
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The Effort card of the Fitness tab (design/companion.md §4.15, §6.12): the verdict, the week line, twelve weekly columns over the
 * selected week's usual range, the seven days and the week's activities; or the card of a state without load. [selection] is the card's
 * own (a column's index as x): a tap or a hold selects a week and only the verdict, the line, the days and the rows follow. The chart
 * itself draws once and reads the selection while drawing.
 */
@Composable
fun EffortCard(
    ui: EffortCardUi,
    selection: ChartSelection,
    onValues: () -> Unit,
    onOpenRow: (EffortRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val body = ui.body) {
        EffortBody.NoEffort -> {
            OdCard(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OdText("Effort", style = OdType.label, color = OverdriveColors.textTertiary)
                OdText("No effort yet", style = OdType.heading)
                OdText(
                    "Weekly effort starts with your first ride on the tablet or your first activity on the watch.",
                    style = OdType.body,
                    color = OverdriveColors.textSecondary,
                )
            }
        }

        EffortBody.Loading -> {
            ChartCard("Effort", modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner()
                        OdText("Loading your effort…", style = OdType.body, color = OverdriveColors.textSecondary)
                    }
                }
            }
        }

        EffortBody.NotLoaded -> {
            ChartCard("Effort", modifier.fillMaxWidth()) {
                OdText(
                    "Not loaded yet. It loads when the service can be reached.",
                    style = OdType.body,
                    color = OverdriveColors.textSecondary,
                )
            }
        }

        is EffortBody.Content -> {
            ChartCard("Effort", modifier.fillMaxWidth(), onValues = onValues) {
                EffortChart(body.content, selection, onOpenRow)
            }
        }
    }
}

@Composable
private fun EffortChart(
    content: EffortContent,
    selection: ChartSelection,
    onOpenRow: (EffortRowUi) -> Unit,
) {
    val x = selection.x
    val view = remember(content, x) { content.viewAt(x) }
    val away = selection.isAway(content.spec.restX)
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OdText(view.verdict, style = OdType.heading)
        view.learning?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary) }
    }
    WeekLine(view, away, if (content.lastIsThisWeek) RestPill.ThisWeek else RestPill.LatestWeek, selection::clear)
    ColumnChart(
        content.spec,
        selection,
        "${content.summary} ${view.verdict}. ${view.line.spoken()}",
        previousAction = "Previous week",
        nextAction = "Next week",
    )
    OdText(view.subheading, style = OdType.label, color = OverdriveColors.textTertiary)
    DayStrip(view.days, view.daysDescription)
    Column {
        view.rows.forEach { ActivityRow(it, onOpenRow) }
        view.noRows?.let {
            HorizontalRule()
            OdText(it, Modifier.padding(vertical = 12.dp), style = OdType.body, color = OverdriveColors.textSecondary)
        }
    }
}

/** "This week · load 97 so far", the band key and "Usual 298–510", and the pill that returns to this week while another is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekLine(
    view: EffortWeekView,
    away: Boolean,
    rest: RestPill,
    onRest: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val plain = view.line.plain
            if (plain != null) {
                OdText(
                    plain,
                    Modifier.heightIn(min = 24.dp),
                    style = OdType.labelNum,
                    color = OverdriveColors.textSecondary,
                )
            } else {
                OdText(
                    buildAnnotatedString {
                        append(view.line.lead)
                        withStyle(
                            SpanStyle(color = OverdriveColors.textPrimary, fontWeight = FontWeight.SemiBold),
                        ) { append(view.line.load) }
                        append(view.line.tail)
                    },
                    Modifier.heightIn(min = 24.dp),
                    style = OdType.labelNum,
                    color = OverdriveColors.textSecondary,
                )
                view.line.usual?.let { usual ->
                    Row(
                        Modifier.heightIn(min = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(
                                    12.dp,
                                ).height(8.dp)
                                .background(OverdriveColors.surface3, RoundedCornerShape(2.dp)),
                        )
                        OdText(
                            usual,
                            style = OdType.labelNum,
                            color = OverdriveColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        if (away) TodayPill(onRest, rest)
    }
}

/** An activity of the week (design/companion.md §4.15 item 7): the name over the day, the load over its share; 56 dp, opens it. */
@Composable
private fun ActivityRow(
    row: EffortRowUi,
    onOpen: (EffortRowUi) -> Unit,
) {
    val source = rememberInteractionSource()
    HorizontalRule()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .plainClickable(source, role = Role.Button) { onOpen(row) }
            .semantics(mergeDescendants = true) { contentDescription = row.description }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(row.name, style = OdType.bodyStrong, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (row.source) {
                    EffortSource.Watch -> {
                        OdIcon(
                            OverdriveIcons.HeartPulse,
                            size = 16.dp,
                            tint = OverdriveColors.textSecondary,
                        )
                    }

                    EffortSource.Outdoor -> {
                        OdIcon(
                            OverdriveIcons.Bike,
                            size = 16.dp,
                            tint = OverdriveColors.textSecondary,
                        )
                    }

                    EffortSource.Tablet -> {
                        Unit
                    }
                }
                OdText(row.dayLine, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
            }
            row.note?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ValueWithUnit(row.load.takeIf { it != UiFormat.EMPTY }, null, OdType.numXS)
            OdText(
                row.share,
                style = OdType.captionNum,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
