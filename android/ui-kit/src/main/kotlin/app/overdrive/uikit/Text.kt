package app.overdrive.uikit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveTypography

private val exactLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun TextStyle.exact(): TextStyle = copy(lineHeightStyle = exactLines)

private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * The type scale of design/system.md §3 with exact line heights (`LineHeightStyle(Center, Trim.None)`),
 * so the sizes in the spec (the overlay's 84 dp, the 40 dp table rows) come out right. Use these, not the raw
 * generated styles. The `...Num` variants add tabular figures for values that change.
 */
object OdType {
    val numHero: TextStyle = OverdriveTypography.numHero.exact()
    val numL: TextStyle = OverdriveTypography.numL.exact()
    val numM: TextStyle = OverdriveTypography.numM.exact()
    val numS: TextStyle = OverdriveTypography.numS.exact()
    val numXS: TextStyle = OverdriveTypography.numXS.exact()
    val title: TextStyle = OverdriveTypography.title.exact()
    val heading: TextStyle = OverdriveTypography.heading.exact()
    val body: TextStyle = OverdriveTypography.body.exact()
    val bodyStrong: TextStyle = OverdriveTypography.bodyStrong.exact()
    val label: TextStyle = OverdriveTypography.label.exact()
    val caption: TextStyle = OverdriveTypography.caption.exact()

    val bodyNum: TextStyle = body.tabular()
    val bodyStrongNum: TextStyle = bodyStrong.tabular()
    val labelNum: TextStyle = label.tabular()
    val captionNum: TextStyle = caption.tabular()

    /** The zone tag of a chip: caption size, semi-bold, tabular. */
    val zoneTag: TextStyle = captionNum.copy(fontWeight = FontWeight.SemiBold)

    /** The style of a unit that follows a value: label size, no tracking, `lineHeight = 1em` so it never grows the line. */
    val unit: TextStyle =
        OverdriveTypography.label
            .copy(lineHeight = OverdriveTypography.label.fontSize, letterSpacing = TextStyle.Default.letterSpacing)
            .exact()
}

/**
 * Gives a single line of text exactly the line height of its style, as CSS does (design/system.md §3, §12).
 *
 * Compose will not shrink a line below the font's natural height (Geist's is 1.29 em), so a 36 sp number with a
 * 40 sp line would measure 47 dp and the overlay would come out 7 dp too tall. This clamps such a line to the
 * nominal height, centring the glyph box in it like half-leading, and keeps the baseline. Text whose natural height
 * already fits, and text of two or more lines (which is not this modifier's business), is left alone.
 */
private fun Modifier.nominalLine(lineHeight: TextUnit): Modifier =
    if (lineHeight == TextUnit.Unspecified) {
        this
    } else {
        this.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val nominal = lineHeight.roundToPx()
            if (placeable.height <= nominal || placeable.height >= nominal * 3 / 2) {
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                val shift = (nominal - placeable.height) / 2
                val baseline = placeable[FirstBaseline]
                val lines =
                    if (baseline == AlignmentLine.Unspecified) {
                        emptyMap<AlignmentLine, Int>()
                    } else {
                        mapOf<AlignmentLine, Int>(FirstBaseline to baseline + shift, LastBaseline to baseline + shift)
                    }
                layout(placeable.width, nominal, lines) { placeable.place(0, shift) }
            }
        }
    }

/** Text with a colour from the theme. Never wraps its colour in a style copy: [color] is read lazily. */
@Composable
fun OdText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = color.orElse(LocalContentColor.current)
    BasicText(
        text = text,
        modifier = modifier.nominalLine(style.lineHeight),
        style = if (textAlign == TextAlign.Unspecified) style else style.copy(textAlign = textAlign),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        color = { resolved },
    )
}

/** Text with spans, for running text that mixes weights or colours ("Sweet spot 2 of 3"). */
@Composable
fun OdText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = color.orElse(LocalContentColor.current)
    BasicText(
        text = text,
        modifier = modifier.nominalLine(style.lineHeight),
        style = style.copy(color = resolved),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
    )
}

/**
 * A value followed by its unit on the same baseline: the value in [style] and [color], the unit in the label
 * style, secondary text, 4 dp after it (design/system.md §3). A null [value] shows the empty dash in tertiary
 * text and no unit, in the same place, so a lost signal never moves anything.
 *
 * Does not set a width: put it in a fixed-width slot when the digits must not move neighbours.
 */
@Composable
fun ValueWithUnit(
    value: String?,
    unit: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = OverdriveColors.textPrimary,
    unitStyle: TextStyle = OdType.unit,
    unitColor: Color = OverdriveColors.textSecondary,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        ValueWithUnitContent(value, unit, style, color, unitStyle, unitColor)
    }
}

/** The pieces of [ValueWithUnit] for use inside a row that aligns by baseline already. */
@Composable
fun RowScope.ValueWithUnitContent(
    value: String?,
    unit: String?,
    style: TextStyle,
    color: Color = OverdriveColors.textPrimary,
    unitStyle: TextStyle = OdType.unit,
    unitColor: Color = OverdriveColors.textSecondary,
) {
    if (value == null) {
        OdText(UiFormat.EMPTY, Modifier.alignByBaseline(), style, OverdriveColors.textTertiary, softWrap = false)
    } else {
        OdText(value, Modifier.alignByBaseline(), style, color, softWrap = false, overflow = TextOverflow.Visible)
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            OdText(
                unit,
                Modifier.alignByBaseline(),
                unitStyle,
                unitColor,
                softWrap = false,
                overflow = TextOverflow.Visible,
            )
        }
    }
}
