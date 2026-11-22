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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.AxisDetail
import app.overdrive.companion.model.AxisValueRow
import app.overdrive.companion.model.DurationRow
import app.overdrive.companion.model.PowerSkillsBody
import app.overdrive.companion.model.PowerSkillsCardUi
import app.overdrive.companion.model.PowerSkillsContent
import app.overdrive.companion.model.SkillCell
import app.overdrive.companion.model.SkillDetail
import app.overdrive.companion.model.SkillRowUi
import app.overdrive.companion.model.SkillWindows
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.uikit.BottomSheet
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.RangeSelector
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.LevelBar
import app.overdrive.uikit.chart.RadarChart
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** What the Power skills card and its sheets can do (design/companion.md §4.14). */
class PowerSkillsActions(
    /** A segment of the window control: 0 is 8 weeks, 1 All time, 2 Custom (the picker). */
    val onSegment: (Int) -> Unit = {},
    val onChange: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onValues: () -> Unit = {},
    val onAxis: (Int) -> Unit = {},
    val onSkill: (PowerSkillId) -> Unit = {},
    val onOpenRide: (String) -> Unit = {},
)

/**
 * The Power skills card (design/companion.md §4.14): the heading with Values, the window control, the range line while Custom is on, the
 * legend, the radar, the note, the five skill rows and the closing note; or the card of a state without data. [selectedAxis] is read while
 * the radar draws, so opening a sheet marks its axis without recomposing the card.
 */
@Composable
fun PowerSkillsCard(
    ui: PowerSkillsCardUi,
    selectedAxis: () -> Int?,
    actions: PowerSkillsActions,
    modifier: Modifier = Modifier,
) {
    val body = ui.body
    if (body is PowerSkillsBody.NoRidesYet) {
        OdCard(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OdText("Power skills", style = OdType.label, color = OverdriveColors.textTertiary)
            OdText("No rides yet", style = OdType.heading)
            OdText(
                "Power skills fill in from rides on the tablet. They appear here after the tablet uploads its first ride.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
        }
        return
    }
    ChartCard(
        "Power skills",
        modifier.fillMaxWidth(),
        onValues = ui.table?.let { actions.onValues },
        spacing = 12.dp,
    ) {
        RangeSelector(SkillWindows.labels, ui.selected, actions.onSegment)
        ui.rangeLine?.let { RangeLine(it, actions) }
        when (body) {
            PowerSkillsBody.Loading -> {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner()
                        OdText("Loading your power skills…", style = OdType.body, color = OverdriveColors.textSecondary)
                    }
                }
            }

            PowerSkillsBody.NotLoaded -> {
                OdText(
                    "Not loaded yet. It loads when the service can be reached.",
                    style = OdType.body,
                    color = OverdriveColors.textSecondary,
                )
            }

            is PowerSkillsBody.EmptyWindow -> {
                OdText(body.text, style = OdType.body, color = OverdriveColors.textSecondary)
            }

            is PowerSkillsBody.Few -> {
                OdText(body.text, style = OdType.body, color = OverdriveColors.textSecondary)
                Column {
                    body.rows.forEach { DurationListRow(it, actions.onOpenRide) }
                }
            }

            is PowerSkillsBody.Radar -> {
                RadarBody(body.content, selectedAxis, actions)
            }

            PowerSkillsBody.NoRidesYet -> {
                Unit
            }
        }
    }
}

/** "2 Nov – 15 Nov 2026 · 9 rides", **Change** and the `x` that clears the range. */
@Composable
private fun RangeLine(
    line: String,
    actions: PowerSkillsActions,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        OdText(line, Modifier.weight(1f), style = OdType.labelNum)
        OdButton("Change", actions.onChange, variant = ButtonVariant.Ghost, height = 48.dp, horizontalPadding = 12.dp)
        OdIconButton(OverdriveIcons.X, actions.onClear, "Clear custom range")
    }
}

@Composable
private fun RadarBody(
    content: PowerSkillsContent,
    selectedAxis: () -> Int?,
    actions: PowerSkillsActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (key in content.legend) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(12.dp)
                        .height(if (key.primary) 2.dp else 1.5.dp)
                        .background(if (key.primary) OverdriveColors.textPrimary else OverdriveColors.textTertiary),
                )
                OdText(key.name, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
            }
        }
    }
    RadarChart(content.spec, selectedAxis, actions.onAxis, content.description)
    OdText(content.note, style = OdType.label, color = OverdriveColors.textSecondary)
    Column {
        content.rows.forEach { SkillRow(it, actions.onSkill) }
    }
    OdText(content.closing, style = OdType.label, color = OverdriveColors.textSecondary)
}

/** A skill row (design/companion.md §4.14 item 7): one target, 72 dp at least, a hairline above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillRow(
    row: SkillRowUi,
    onSkill: (PowerSkillId) -> Unit,
) {
    val source = rememberInteractionSource()
    HorizontalRule()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .plainClickable(source, role = Role.Button) { onSkill(row.id) }
            .semantics(mergeDescendants = true) { contentDescription = row.description }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The span follows the name and wraps under it at a large font instead of being cut.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(12.dp).height(4.dp).background(row.color, RoundedCornerShape(2.dp)))
                    OdText(row.name, style = OdType.bodyStrong, maxLines = 1)
                    OdText(row.span, style = OdType.labelNum, color = OverdriveColors.textTertiary, maxLines = 1)
                }
                (row.levelLine ?: row.noData)?.let {
                    OdText(
                        it,
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ValueWithUnit(row.watts, "W", OdType.numXS)
                row.caption?.let {
                    OdText(
                        it,
                        style = OdType.captionNum,
                        color = OverdriveColors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        if (row.level > 0) {
            Box(Modifier.padding(top = 4.dp).semantics { contentDescription = "" }) { LevelBar(row.level, row.color) }
        }
    }
}

/** A row of the list that replaces the radar: duration, watts and date, opening the ride. */
@Composable
private fun DurationListRow(
    row: DurationRow,
    onOpenRide: (String) -> Unit,
) {
    val source = rememberInteractionSource()
    HorizontalRule()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .plainClickable(source, role = Role.Button) { onOpenRide(row.rideId) }
            .semantics(mergeDescendants = true) { contentDescription = row.description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdText(row.duration, Modifier.weight(1f), style = OdType.body)
        ValueWithUnit(row.watts, "W", OdType.numXS)
        OdText(row.date, style = OdType.labelNum, color = OverdriveColors.textSecondary, maxLines = 1)
    }
}

// region sheets

/** The last value a sheet showed, so it keeps its content while it slides out. */
@Composable
internal fun <T : Any> rememberRetained(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value

    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
}

@Composable
private fun SheetTitle(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OdText(title, style = OdType.heading)
            OdText(subtitle, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        OdIconButton(OverdriveIcons.X, onClose, "Close")
    }
}

/** The sheet of one duration (design/companion.md §4.14, "Detail of a duration"). */
@Composable
fun AxisSheet(
    detail: AxisDetail?,
    onDismiss: () -> Unit,
    onOpenRide: (String) -> Unit,
) {
    val shown = rememberRetained(detail)
    BottomSheet(detail != null, onDismiss) {
        if (shown != null) {
            SheetTitle(shown.title, shown.skill, onDismiss)
            Column(Modifier.padding(top = 12.dp, bottom = 24.dp)) {
                shown.rows.forEach { AxisRow(it, onOpenRide) }
                shown.level?.let { level ->
                    HorizontalRule()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            OdText(level.label, style = OdType.bodyStrong)
                            OdText(level.note, style = OdType.label, color = OverdriveColors.textSecondary)
                        }
                        OdText(level.value, style = OdType.bodyStrong)
                    }
                }
            }
        }
    }
}

@Composable
private fun AxisRow(
    row: AxisValueRow,
    onOpenRide: (String) -> Unit,
) {
    HorizontalRule()
    val cell = row.cell
    val source = rememberInteractionSource()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (cell != null) {
                    Modifier
                        .plainClickable(source, role = Role.Button) { onOpenRide(cell.ride.rideId) }
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                "${row.label}, ${cell.ride.line}, opens the ride"
                        }
                } else {
                    Modifier
                },
            ).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(row.label, style = OdType.bodyStrong)
            if (cell != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OdText(
                        cell.ride.line,
                        Modifier.weight(1f, fill = false),
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                    )
                    OdIcon(OverdriveIcons.ChevronRight, size = 16.dp, tint = OverdriveColors.textSecondary)
                }
            } else {
                OdText(row.emptyText.orEmpty(), style = OdType.label, color = OverdriveColors.textSecondary)
            }
        }
        if (cell != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ValueWithUnit(cell.watts, "W", OdType.numXS)
                cell.perKg?.let {
                    OdText(
                        it,
                        style = OdType.captionNum,
                        color = OverdriveColors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The sheet of one skill (design/companion.md §4.14, "Detail of a skill"). */
@Composable
fun SkillSheet(
    detail: SkillDetail?,
    onDismiss: () -> Unit,
    onOpenRide: (String) -> Unit,
) {
    val shown = rememberRetained(detail)
    BottomSheet(detail != null, onDismiss) {
        if (shown != null) {
            SheetTitle(shown.title, shown.subtitle, onDismiss)
            Column(Modifier.padding(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                        OdText(
                            "Duration",
                            Modifier.weight(1f),
                            style = OdType.caption,
                            color = OverdriveColors.textTertiary,
                        )
                        OdText(
                            shown.windowColumn,
                            Modifier.width(VALUE_COLUMN),
                            style = OdType.caption,
                            color = OverdriveColors.textTertiary,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                        shown.allTimeColumn?.let {
                            OdText(
                                it,
                                Modifier.width(VALUE_COLUMN),
                                style = OdType.caption,
                                color = OverdriveColors.textTertiary,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                    shown.rows.forEach { row ->
                        HorizontalRule()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OdText(row.duration, Modifier.weight(1f), style = OdType.body)
                            SkillValue(row.window, primary = true, onOpenRide)
                            if (shown.allTimeColumn != null) SkillValue(row.allTime, primary = false, onOpenRide)
                        }
                    }
                }
                shown.perKgSentence?.let { OdText(it, style = OdType.body, color = OverdriveColors.textSecondary) }
                OdText(shown.hint, style = OdType.label, color = OverdriveColors.textSecondary)
            }
        }
    }
}

@Composable
private fun SkillValue(
    cell: SkillCell?,
    primary: Boolean,
    onOpenRide: (String) -> Unit,
) {
    val source = rememberInteractionSource()
    val tint = if (primary) OverdriveColors.textPrimary else OverdriveColors.textSecondary
    Column(
        Modifier
            .width(VALUE_COLUMN)
            .heightIn(min = 48.dp)
            .then(
                if (cell != null) {
                    Modifier
                        .plainClickable(source, role = Role.Button) { onOpenRide(cell.rideId) }
                        .semantics(mergeDescendants = true) { contentDescription = cell.description }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
    ) {
        if (cell == null) {
            OdText("—", style = OdType.numXS, color = OverdriveColors.textTertiary)
        } else {
            ValueWithUnit(cell.watts, "W", OdType.numXS, color = tint)
            OdText(
                cell.date,
                style = OdType.captionNum,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val VALUE_COLUMN = 92.dp
