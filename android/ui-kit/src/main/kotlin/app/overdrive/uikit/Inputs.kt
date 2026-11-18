package app.overdrive.uikit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion
import kotlin.math.roundToInt

private val md = RoundedCornerShape(OverdriveDimens.radiusMd)

@Composable
private fun motionSpecColor() =
    if (LocalMotionEnabled.current) tween<Color>(OverdriveMotion.durationFast, easing = OverdriveMotion.easingStandard) else snap()

/**
 * A segmented control (design/system.md §7.8): surface 2 with a hairline and 2 dp inner padding, 40 dp segments
 * in the label style. The selected segment is surface 3 with a strong inset ring and primary text.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .surface(OverdriveColors.surface2, md, OverdriveColors.border)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(if (selected) OverdriveColors.surface3 else Color.Transparent, motionSpecColor(), label = "segment")
            val source = rememberInteractionSource()
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(fill)
                    .then(if (selected) Modifier.border(OverdriveDimens.strokeHairline, OverdriveColors.borderStrong, RoundedCornerShape(6.dp)) else Modifier)
                    .semantics { this.selected = selected }
                    .plainClickable(source, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                OdText(
                    label,
                    style = OdType.label,
                    color = if (selected) OverdriveColors.textPrimary else OverdriveColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A stepper (design/system.md §7.8): 48 dp, surface 2, hairline, radius 8; minus and plus are 48 dp icon buttons
 * that repeat every 150 ms while held; the value is tabular in the numXS style, at least 96 dp wide, centred.
 */
@Composable
fun Stepper(
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    label: String = "value",
) {
    Row(
        modifier.height(48.dp).surface(OverdriveColors.surface2, md, OverdriveColors.border),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdIconButton(
            OverdriveIcons.Minus,
            onDecrement,
            "Decrease $label",
            Modifier.padding(vertical = 0.dp),
            size = 46.dp,
            enabled = canDecrement,
            repeating = true,
            tint = if (canDecrement) OverdriveColors.textSecondary else OverdriveColors.textTertiary,
        )
        OdText(valueText, Modifier.widthIn(min = 96.dp), style = OdType.numXS, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        OdIconButton(
            OverdriveIcons.Plus,
            onIncrement,
            "Increase $label",
            size = 46.dp,
            enabled = canIncrement,
            repeating = true,
            tint = if (canIncrement) OverdriveColors.textSecondary else OverdriveColors.textTertiary,
        )
    }
}

/** A switch (design/system.md §7.8): 44 × 26, full radius, an 18 dp thumb that moves in 150 ms. */
@Composable
fun OdSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val track by animateColorAsState(if (checked) OverdriveColors.textPrimary else OverdriveColors.surface3, motionSpecColor(), label = "track")
    val thumbColour by animateColorAsState(if (checked) OverdriveColors.textOnPrimary else OverdriveColors.textTertiary, motionSpecColor(), label = "thumb")
    val offset by animateDpAsState(
        if (checked) 21.dp else 3.dp,
        if (LocalMotionEnabled.current) tween(OverdriveMotion.durationFast, easing = OverdriveMotion.easingStandard) else snap(),
        label = "offset",
    )
    val source = rememberInteractionSource()
    Box(
        modifier
            .size(44.dp, 26.dp)
            .background(track, CircleShape)
            .border(OverdriveDimens.strokeHairline, if (checked) OverdriveColors.textPrimary else OverdriveColors.borderStrong, CircleShape)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .plainClickable(source, role = Role.Switch) { onCheckedChange(!checked) },
    ) {
        // 1 dp border + 3 dp inset puts the thumb 4 dp from the edge, as in the mock-up (3 dp inside the border).
        Box(Modifier.offset(x = offset - 1.dp, y = 2.dp).size(18.dp).background(thumbColour, CircleShape))
    }
}

/**
 * A discrete slider (design/system.md §7.8): 4 dp track in surface 3, filled part in primary, a 20 dp primary
 * thumb. The value is shown by the caller as numXS at the right, not in a bubble. A touch or drag anywhere on the
 * 48 dp high touch area picks the nearest step.
 */
@Composable
fun OdSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    step: Int,
    modifier: Modifier = Modifier,
    contentDescription: String = "Slider",
) {
    val latest by rememberUpdatedState(onValueChange)
    val widthPx = remember { mutableIntStateOf(0) }
    val thumbPx = with(LocalDensity.current) { SLIDER_THUMB.toPx() }
    val fraction = (value - range.first).toFloat() / (range.last - range.first)
    fun pick(x: Float) {
        val usable = (widthPx.intValue - thumbPx).coerceAtLeast(1f)
        val raw = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
        val steps = ((range.last - range.first) / step.toFloat() * raw).roundToInt()
        latest((range.first + steps * step).coerceIn(range))
    }
    Canvas(
        modifier
            .height(48.dp)
            .onSizeChanged { widthPx.intValue = it.width }
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), (range.last - range.first) / step - 1)
                stateDescription = value.toString()
                setProgress { target ->
                    latest((((target - range.first) / step).roundToInt() * step + range.first).coerceIn(range))
                    true
                }
            }.pointerInput(range, step) { detectTapGestures { offset: Offset -> pick(offset.x) } }
            .pointerInput(range, step) { detectHorizontalDragGestures { change, _ -> pick(change.position.x) } },
    ) {
        val trackHeight = 4.dp.toPx()
        val centreY = size.height / 2f
        val thumbCentre = thumbPx / 2f + (size.width - thumbPx) * fraction
        val radius = CornerRadius(trackHeight / 2f)
        drawRoundRect(OverdriveColors.surface3, Offset(0f, centreY - trackHeight / 2f), Size(size.width, trackHeight), radius)
        drawRoundRect(OverdriveColors.textPrimary, Offset(0f, centreY - trackHeight / 2f), Size(thumbCentre, trackHeight), radius)
        drawCircle(OverdriveColors.textPrimary, thumbPx / 2f, Offset(thumbCentre, centreY))
    }
}

private val SLIDER_THUMB = 20.dp

/**
 * A settings row (design/system.md §6.6): at least 64 dp, the title in body-strong and an optional one-line
 * explanation in the label style, secondary, with the control at the right. [first] omits the top rule.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    first: Boolean = false,
    titleWidth: Dp? = null,
    control: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!first) HorizontalRule()
        Row(
            Modifier.fillMaxWidth().heightIn(min = OverdriveDimens.sizeListRowTwoLine).padding(PaddingValues(horizontal = 20.dp, vertical = 10.dp)),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(if (titleWidth != null) Modifier.width(titleWidth) else Modifier.weight(1f)) {
                OdText(title, style = OdType.bodyStrong)
                if (description != null) OdText(description, style = OdType.label, color = OverdriveColors.textSecondary)
            }
            control()
        }
    }
}
