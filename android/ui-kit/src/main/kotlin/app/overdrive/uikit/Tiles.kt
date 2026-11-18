package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

private val cardShape = RoundedCornerShape(OverdriveDimens.radiusLg)

/**
 * A card (design/system.md §7.4): surface 1, 1 dp border, radius 12. Use only to group several related
 * elements; a single value is a tile, not a card.
 */
@Composable
fun OdCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.surface(OverdriveColors.surface1, cardShape, OverdriveColors.border).padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** A hairline rule, the divider of lists, tile groups and the overlay. */
@Composable
fun HorizontalRule(
    modifier: Modifier = Modifier,
    color: Color = OverdriveColors.border,
) {
    Box(modifier.fillMaxWidth().height(OverdriveDimens.strokeHairline).background(color))
}

/** A vertical hairline. */
@Composable
fun VerticalRule(
    modifier: Modifier = Modifier,
    color: Color = OverdriveColors.border,
) {
    Box(modifier.fillMaxHeight().width(OverdriveDimens.strokeHairline).background(color))
}

/**
 * One stat tile (design/system.md §7.3): a label in the label style, tertiary, over a value with its unit.
 * [labelEnd] is an optional right-aligned note in the label row ("No signal", "Z4"). The tile has no border or
 * fill of its own; put it in a [TileGroup].
 *
 * @param spaceBetween the label sits at the top and the value at the bottom (ride screen); otherwise they stack with 4 dp.
 */
@Composable
fun StatTile(
    label: String,
    value: String?,
    unit: String?,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = OdType.numL,
    valueColor: Color = OverdriveColors.textPrimary,
    labelEnd: String? = null,
    padding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
    spaceBetween: Boolean = true,
) {
    Column(
        modifier.padding(padding),
        verticalArrangement = if (spaceBetween) Arrangement.SpaceBetween else Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OdText(label, style = OdType.label, color = OverdriveColors.textTertiary, maxLines = 1)
            if (labelEnd != null) OdText(labelEnd, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
        }
        ValueWithUnit(value, unit, valueStyle, color = valueColor)
    }
}

/**
 * One `surface1` container split by 1 dp `border` lines into a grid of [columns] columns (design/system.md §7.3).
 * Tiles are given as composables so each can be a [StatTile] or something richer.
 *
 * @param fillHeight rows share the height of the group equally (ride screen); otherwise each row is as tall as its tallest tile.
 */
@Composable
fun TileGroup(
    columns: Int,
    tiles: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val rows = tiles.chunked(columns)
    Column(modifier.surface(OverdriveColors.surface1, cardShape, OverdriveColors.border).clip(cardShape)) {
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HorizontalRule()
            Row(
                if (fillHeight) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                for (column in 0 until columns) {
                    if (column > 0) VerticalRule()
                    Box(Modifier.weight(1f).fillMaxHeight()) { row.getOrNull(column)?.invoke() }
                }
            }
        }
    }
}
