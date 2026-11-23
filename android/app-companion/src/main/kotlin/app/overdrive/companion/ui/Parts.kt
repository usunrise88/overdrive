package app.overdrive.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.CardPosition
import app.overdrive.companion.model.LineChartUi
import app.overdrive.companion.model.ProgressEntryUi
import app.overdrive.companion.model.TileUi
import app.overdrive.companion.model.ValuesTable
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.StatTile
import app.overdrive.uikit.TileGroup
import app.overdrive.uikit.chart.ChartCard
import app.overdrive.uikit.chart.ChartReadout
import app.overdrive.uikit.chart.ChartSelection
import app.overdrive.uikit.chart.PlotChart
import app.overdrive.uikit.chart.ValuesSheet
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors

/** A line chart card: heading with Values, the readout (the legend) and the plot, on the screen's shared [selection]. */
@Composable
fun LineChartCard(
    ui: LineChartUi,
    selection: ChartSelection,
    onValues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = selection.x
    val readout = remember(ui, x) { ui.readoutAt(x) }
    ChartCard(ui.heading, modifier, onValues = onValues, note = ui.note) {
        ChartReadout(readout.x, readout.entries, away = selection.isAway(ui.spec.restX), onToday = selection::clear)
        PlotChart(ui.spec, selection, "${ui.summary}. ${readout.spoken()}")
    }
}

/**
 * The row that ends Fitness and Power and opens Progress (design/companion.md §4.16): a card of one 64 dp row, "Progress" over the
 * service's headline (it wraps, never cut) and a chevron. It is one button to TalkBack.
 */
@Composable
fun ProgressEntryRow(
    entry: ProgressEntryUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteractionSource()
    Row(
        modifier
            .fillMaxWidth()
            .cardSlice(CardPosition.Only)
            .semantics(mergeDescendants = true) { contentDescription = entry.description }
            .plainClickable(source, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText("Progress", style = OdType.bodyStrong, maxLines = 1)
            OdText(entry.text, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textTertiary)
    }
}

/** The sheet of one chart's table; [table] null is closed. */
@Composable
fun ValuesSheetFor(
    table: ValuesTable?,
    onDismiss: () -> Unit,
) {
    // The last table stays while the sheet slides out.
    val shown = remember { arrayOfNulls<ValuesTable>(1) }
    if (table != null) shown[0] = table
    val current = shown[0]
    if (current != null) {
        ValuesSheet(table != null, current.title, current.firstColumn, current.columns, current.rows, onDismiss)
    }
}

/** Tiles in a group of [columns] columns (design/companion.md §4.3, §4.7): numS values. */
@Composable
fun Tiles(
    tiles: List<TileUi>,
    columns: Int = 3,
    modifier: Modifier = Modifier,
) {
    TileGroup(
        columns,
        tiles.map { tile ->
            @Composable {
                Column {
                    StatTile(
                        tile.label,
                        tile.value,
                        tile.unit,
                        valueStyle = OdType.numS,
                        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        spaceBetween = false,
                    )
                    if (tile.caption != null) {
                        OdText(
                            tile.caption,
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            style = OdType.label,
                            color = OverdriveColors.textSecondary,
                        )
                    }
                }
            }
        },
        modifier,
    )
}

/**
 * Draws one row of a month's card (design/companion.md §4.6): the fill and the card's outline, clipped to the row so that only
 * the sides that are the card's edge ([position]) show, and the divider above every row but the first. A lazy list of rows then
 * reads as one card per month.
 */
fun Modifier.cardSlice(position: CardPosition): Modifier =
    this.drawBehind {
        val radius = 12.dp.toPx()
        val stroke = 1.dp.toPx()
        val first = position == CardPosition.First || position == CardPosition.Only
        val last = position == CardPosition.Last || position == CardPosition.Only
        val top = if (first) 0f else -2 * radius
        val bottom = if (last) size.height else size.height + 2 * radius
        clipRect(0f, 0f, size.width, size.height) {
            drawRoundRect(
                OverdriveColors.surface1,
                Offset(0f, top),
                Size(size.width, bottom - top),
                CornerRadius(radius),
            )
            drawRoundRect(
                OverdriveColors.border,
                Offset(stroke / 2f, top + stroke / 2f),
                Size(size.width - stroke, bottom - top - stroke),
                CornerRadius(radius - stroke / 2f),
                style = Stroke(stroke),
            )
            if (!first) {
                drawLine(
                    OverdriveColors.border,
                    Offset(0f, stroke / 2f),
                    Offset(size.width, stroke / 2f),
                    stroke,
                )
            }
        }
    }
