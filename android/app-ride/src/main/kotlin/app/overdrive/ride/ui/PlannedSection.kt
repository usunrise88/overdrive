package app.overdrive.ride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.core.workout.Workout
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.plan.PlanMark
import app.overdrive.ride.plan.PlanRow
import app.overdrive.ride.plan.PlanUi
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.DisclosureRow
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.ValueRow
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The Ready screen's **Planned** section (spec §5.7, design/system.md §6.1): today's planned workouts, then **This week**
 * collapsed to a line until it is opened. Each row is a [ValueRow], one of the same selection list as Free ride and the loaded
 * workouts: tapping it (or Enter on it, when something moves focus) chooses that workout, and the Start button rides it.
 *
 * Top to bottom, which is also the focus order: the section label with the **refresh** button, one line of state ("Updated
 * 07:30", "Out of date · updated 07:30", the token wording), today's rows (or "Nothing planned today"), the **This week** row
 * and, opened, the days under it. The section is not drawn at all when [plan] is not [PlanUi.visible].
 *
 * @param choice what is chosen on the screen; a row is selected when its workout is the chosen one.
 * @param onSelect a planned workout was chosen.
 * @param onRefresh the refresh button.
 */
@Composable
fun PlannedSection(
    plan: PlanUi,
    choice: RideChoice,
    onSelect: (Workout) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!plan.visible) return
    var weekOpen by rememberSaveable { mutableStateOf(false) }
    val chosen = (choice as? RideChoice.Planned)?.workout?.id
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Planned", Modifier.weight(1f).padding(start = 16.dp))
            OdIconButton(
                OverdriveIcons.RefreshCw,
                onRefresh,
                "Refresh planned workouts",
                variant = ButtonVariant.Ghost,
                enabled = !plan.refreshing,
            )
        }
        PlanStatus(plan)
        if (plan.today.isEmpty()) {
            OdText(
                "Nothing planned today",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
        }
        for (row in plan.today) PlanRowItem(row, chosen, onSelect)
        if (plan.week.isNotEmpty()) {
            DisclosureRow(
                "This week",
                if (plan.weekCount == 1) "1 workout" else "${plan.weekCount} workouts",
                expanded = weekOpen,
                onToggle = { weekOpen = !weekOpen },
            )
            if (weekOpen) {
                for (day in plan.week) {
                    OdText(
                        day.label,
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = OdType.label,
                        color = OverdriveColors.textTertiary,
                    )
                    for (row in day.rows) PlanRowItem(row, chosen, onSelect)
                }
            }
        }
    }
}

@Composable
private fun PlanStatus(plan: PlanUi) {
    val line = plan.status ?: return
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp).heightIn(min = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            if (plan.statusIsAlert) {
                OdIcon(
                    OverdriveIcons.TriangleAlert,
                    Modifier.padding(top = 1.dp),
                    size = InlineIconSize,
                )
            }
            OdText(
                line,
                style = OdType.label,
                color = if (plan.statusIsAlert) OverdriveColors.textPrimary else OverdriveColors.textSecondary,
            )
        }
        plan.detail?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary) }
    }
}

@Composable
private fun PlanRowItem(
    row: PlanRow,
    chosen: String?,
    onSelect: (Workout) -> Unit,
) {
    ValueRow(
        title = row.title,
        subtitle = row.subtitle,
        onClick = { onSelect(row.workout.workout) },
        value = row.duration,
        caption = row.intensity,
        selected = chosen == row.workout.id,
        enabled = row.enabled,
        subtitleIcon =
            when (row.mark) {
                PlanMark.None -> null
                PlanMark.Done -> OverdriveIcons.Check
                PlanMark.Blocked -> OverdriveIcons.TriangleAlert
            },
    )
}
