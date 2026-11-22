package app.overdrive.uikit.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.BottomSheet
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.surface
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion

/** The key in front of a name in a [ChartReadout]: the mark's own shape and colour, so the readout is the legend. */
enum class ReadoutKey {
    /** A 12 × 2 dp line. */
    Line,

    /** A 12 × 1.5 dp line, the second series. */
    ThinLine,

    /** An 8 dp dot. */
    Dot,

    /** A 12 × 8 dp band. */
    Band,

    /** No key: a plain fact ("Whole ride"). */
    None,
}

/** One entry of a [ChartReadout]: [name] in text 2 and [value] in text 1, 600, after a key in the mark's [color]. */
@Immutable
class ReadoutEntry(
    val key: ReadoutKey,
    val color: Color,
    val name: String?,
    val value: String,
)

private val rowShape = RoundedCornerShape(OverdriveDimens.radiusMd)

/**
 * The row between a chart's heading and its plot (design/companion.md §3.5): the selected x in text 1, then one entry per
 * series, wrapping. It is the chart's legend: there is no other. While a past point is selected a 24 dp **Today** pill (surface 2,
 * a 48 dp hit area) sits at the right and returns the chart to rest. The row is a polite live region, so TalkBack says the new
 * point when the selection moves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartReadout(
    x: String,
    entries: List<ReadoutEntry>,
    away: Boolean,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
    rest: RestPill = RestPill.Today,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The live region is the text only: the pill is a control of its own and stays a separate node for TalkBack.
        FlowRow(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OdText(x, Modifier.heightIn(min = 24.dp), style = OdType.labelNum, maxLines = 1, softWrap = false)
            for (entry in entries) {
                Row(
                    Modifier.heightIn(min = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReadoutKeyMark(entry.key, entry.color)
                    if (entry.name != null) {
                        OdText(
                            entry.name,
                            style = OdType.label,
                            color = OverdriveColors.textSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    OdText(
                        entry.value,
                        style = OdType.labelNum.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (away) TodayPill(onToday, rest)
    }
}

/**
 * What the pill at the right of a readout says while a past point is selected, and what it is called to TalkBack: **Today** on the charts
 * whose x is a day, **Latest** on the repeat page, whose x is an attempt (design/companion.md §4.12).
 */
enum class RestPill(
    val label: String,
    val description: String,
) {
    Today("Today", "Back to today"),
    Latest("Latest", "Back to the latest attempt"),
    ThisWeek("This week", "Back to this week"),
    LatestWeek("Latest", "Back to the latest week"),
}

@Composable
private fun ReadoutKeyMark(
    key: ReadoutKey,
    color: Color,
) {
    when (key) {
        ReadoutKey.None -> {
            Unit
        }

        ReadoutKey.Line, ReadoutKey.ThinLine -> {
            Box(Modifier.width(12.dp).height(if (key == ReadoutKey.Line) 2.dp else 1.5.dp).background(color))
        }

        ReadoutKey.Dot -> {
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        }

        ReadoutKey.Band -> {
            Box(Modifier.width(12.dp).height(8.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

/**
 * The line under Wellness' range selector while a past day is selected (design/companion.md §3.5): "Showing Sun 15 Nov" in text 1
 * and the **Today** pill at the right, which returns every chart on the screen to rest.
 */
@Composable
fun ShowingLine(
    text: String,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OdText(
            text,
            Modifier.weight(1f).heightIn(min = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = OdType.labelNum,
            maxLines = 1,
        )
        TodayPill(onToday)
    }
}

/** A 24 dp pill with a 48 dp hit area: the visual is small, the touch target is not. */
@Composable
fun TodayPill(
    onClick: () -> Unit,
    rest: RestPill = RestPill.Today,
) {
    val source = rememberInteractionSource()
    Box(
        Modifier
            .reportHeight(24.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = rest.description }
            .plainClickable(source, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(
                    24.dp,
                ).background(
                    OverdriveColors.surface2,
                    RoundedCornerShape(OverdriveDimens.radiusFull),
                ).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            OdText(
                rest.label,
                style = OdType.label,
                color = OverdriveColors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * A chart card (design/companion.md §3.7, system.md §13): a card with padding 16 × 16 × 12 and 8 dp between its parts: the
 * [heading] (label, text 3) with an optional ghost **Values** (label, text 2, a 48 dp hit area), the readout, the plot and an
 * optional [note] (label, text 2), only where the chart's meaning is not obvious from its title. [spacing] is 8 dp, 12 dp on the Power skills
 * card, whose window control needs more air (design/companion.md §4.14).
 */
@Composable
fun ChartCard(
    heading: String,
    modifier: Modifier = Modifier,
    onValues: (() -> Unit)? = null,
    note: String? = null,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    OdCard(
        modifier,
        PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
        Arrangement.spacedBy(spacing),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            OdText(
                heading,
                Modifier.weight(1f),
                style = OdType.label,
                color = OverdriveColors.textTertiary,
                maxLines = 1,
            )
            if (onValues != null) {
                val source = rememberInteractionSource()
                Box(
                    Modifier
                        .requiredHeight(48.dp)
                        .widthIn(min = 48.dp)
                        .plainClickable(source, role = Role.Button, onClick = onValues)
                        .padding(start = 12.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    OdText(
                        "Values",
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        content()
        if (note != null) OdText(note, style = OdType.label, color = OverdriveColors.textSecondary)
    }
}

/** One row of a [ValuesSheet]: its [key] (stable), the first cell [label] and the number cells, right-aligned. */
@Immutable
class ValuesRow(
    val key: String,
    val label: String,
    val cells: List<String>,
)

/**
 * The table twin of a chart (design/companion.md §3.6): a bottom sheet over a 70% scrim with the chart's data newest first,
 * numbers right-aligned and tabular, 40 dp rows, a lazy list. It is the accessible equivalent of the chart and the way to read exact
 * values without scrubbing. In 200 ms decelerating, out 150 ms accelerating; instant when the system removes animations. Tapping the
 * scrim or the close button dismisses it (the caller also closes it on Back).
 *
 * @param columns the headers of the number columns; the first column's header is [firstColumn].
 */
@Composable
fun ValuesSheet(
    visible: Boolean,
    title: String,
    firstColumn: String,
    columns: List<String>,
    rows: List<ValuesRow>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomSheet(visible, onDismiss, modifier, heightFraction = SHEET_HEIGHT) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OdText(title, Modifier.weight(1f), style = OdType.title, maxLines = 1)
            OdIconButton(OverdriveIcons.X, onDismiss, "Close")
        }
        TableRow(firstColumn, columns, header = true)
        HorizontalRule()
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.key }) { row ->
                TableRow(row.label, row.cells, header = false)
                HorizontalRule()
            }
        }
    }
}

@Composable
private fun TableRow(
    first: String,
    cells: List<String>,
    header: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        OdText(
            first,
            Modifier.weight(1f),
            style = if (header) OdType.caption else OdType.bodyNum,
            color = if (header) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
            maxLines = 1,
        )
        for (cell in cells) {
            OdText(
                cell,
                Modifier.widthIn(min = CELL_WIDTH).width(CELL_WIDTH),
                style = if (header) OdType.caption else OdType.bodyNum,
                color = if (header) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Reports [reported] as its height to its parent while the content (a 48 dp hit area around a 24 dp visual) overflows evenly. */
private fun Modifier.reportHeight(reported: Dp): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = reported.roundToPx().coerceAtLeast(constraints.minHeight)
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }

private val CELL_WIDTH = 76.dp
private const val SHEET_HEIGHT = 0.62f
