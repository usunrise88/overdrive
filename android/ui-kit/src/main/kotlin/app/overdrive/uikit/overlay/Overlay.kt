package app.overdrive.uikit.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.Zones
import app.overdrive.uikit.chart.ProfileLook
import app.overdrive.uikit.chart.WorkoutProfile
import app.overdrive.uikit.surface
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

// The overlay (design/system.md §5): 464 dp wide in both modes, one row of five label-over-value cells with
// fixed-width slots so no digit change moves anything. Every cell is its own skippable composable taking only
// primitives, so a heart-rate change recomposes the heart-rate cell and nothing else. There is no animation and
// no window code here: the WindowManager window and its lifecycle are the app's (stage M1-B).

private val frameShape = RoundedCornerShape(OverdriveDimens.overlayRadius)
private val gap = OverdriveDimens.overlayCellGap

/** The overlay in whichever mode [OverlaySettings.mode] says. */
@Composable
fun OverlayContent(
    state: OverlayUiState,
    settings: OverlaySettings,
    modifier: Modifier = Modifier,
) {
    when (settings.mode) {
        OverlayMode.Compact -> OverlayCompact(state, settings, modifier)
        OverlayMode.Detailed -> OverlayDetailed(state, settings, modifier)
    }
}

/** The compact overlay: 84 dp high, the five-cell main row only. */
@Composable
fun OverlayCompact(
    state: OverlayUiState,
    settings: OverlaySettings,
    modifier: Modifier = Modifier,
) {
    OverlayFrame(settings.opacity, modifier) {
        MainRow(state)
    }
}

/**
 * The detailed overlay: the main row, then speed and distance, then (while a workout plays) the interval, what is
 * next and the miniature profile. 282 dp high in a workout, 159 dp in a free ride; the same 464 dp width.
 */
@Composable
fun OverlayDetailed(
    state: OverlayUiState,
    settings: OverlaySettings,
    modifier: Modifier = Modifier,
) {
    OverlayFrame(settings.opacity, modifier) {
        MainRow(state)
        HorizontalRule()
        SpeedRow(state.speedKmh, state.distanceMeters, state.paused)
        val interval = state.interval
        if (interval != null) {
            HorizontalRule()
            IntervalSection(interval)
        }
    }
}

@Composable
private fun OverlayFrame(
    opacity: Float,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backing = remember(opacity) { OverdriveColors.overlayBacking.copy(alpha = opacity) }
    Column(
        modifier
            .width(OverdriveDimens.overlayCompactWidth)
            .surface(backing, frameShape, OverdriveColors.overlayBorder)
            .padding(OverdriveDimens.strokeHairline)
            .padding(vertical = OverdriveDimens.overlayPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(OverdriveDimens.overlayDetailedSectionGap),
        content = content,
    )
}

// ---- Main row ---------------------------------------------------------------------------------

@Composable
private fun MainRow(state: OverlayUiState) {
    Column(
        Modifier.padding(horizontal = OverdriveDimens.overlayPaddingHorizontal),
        verticalArrangement = Arrangement.spacedBy(OverdriveDimens.overlayLabelValueGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            PowerLabel(zoneTag = if (state.power != null && !state.paused) state.zone else null)
            Label("Heart", OverdriveDimens.overlaySlotHeartRate)
            Label("Cadence", OverdriveDimens.overlaySlotCadence)
            TimeLabel(state.paused)
            ControlLabel(state.control)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            PowerValue(state.power, state.zone, state.paused)
            SlotValue(UiFormat.count(state.heartRate), state.heartRate == null, state.paused, OverdriveDimens.overlaySlotHeartRate)
            SlotValue(UiFormat.count(state.cadence), state.cadence == null, state.paused, OverdriveDimens.overlaySlotCadence)
            TimeValue(state.elapsedSeconds)
            ControlValue(state.control, state.paused)
        }
    }
}

@Composable
private fun Label(
    text: String,
    slot: Dp,
) {
    OdText(text, Modifier.requiredWidth(slot), style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
}

@Composable
private fun PowerLabel(zoneTag: Int?) {
    Row(Modifier.requiredWidth(OverdriveDimens.overlaySlotPower), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OdText("Power", style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1, softWrap = false)
        if (zoneTag != null) {
            OdText("Z$zoneTag", style = OdType.captionNum, color = OverdriveColors.textSecondary, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun TimeLabel(paused: Boolean) {
    OdText(
        if (paused) "Paused" else "Time",
        Modifier.requiredWidth(OverdriveDimens.overlaySlotElapsed),
        style = OdType.caption,
        color = if (paused) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun ControlLabel(control: OverlayControl) {
    Row(Modifier.requiredWidth(OverdriveDimens.overlaySlotGearOrTarget), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (control) {
            is OverlayControl.Gear -> OdText("Gear", style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1, softWrap = false)
            is OverlayControl.Target -> {
                OdText("Target", style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1, softWrap = false)
                OdText(UiFormat.percent(control.intensityPercent), style = OdType.captionNum, color = OverdriveColors.textSecondary, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Live power in the zone colour, 36 sp; paused or without power it is tertiary text. */
@Composable
private fun RowScope.PowerValue(
    power: Int?,
    zone: Int?,
    paused: Boolean,
) {
    val colour =
        when {
            power == null || paused -> OverdriveColors.textTertiary
            else -> Zones.color(zone)
        }
    OdText(
        UiFormat.count(power),
        Modifier.requiredWidth(OverdriveDimens.overlaySlotPower).alignByBaseline(),
        style = OdType.numM,
        color = colour,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun RowScope.SlotValue(
    text: String,
    empty: Boolean,
    dimmed: Boolean,
    slot: Dp,
) {
    OdText(
        text,
        Modifier.requiredWidth(slot).alignByBaseline(),
        style = OdType.numS,
        color = if (empty || dimmed) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

/** Elapsed time is the one value that keeps its colour while paused. */
@Composable
private fun RowScope.TimeValue(seconds: Int) {
    OdText(
        UiFormat.elapsed(seconds),
        Modifier.requiredWidth(OverdriveDimens.overlaySlotElapsed).alignByBaseline(),
        style = OdType.numS,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun RowScope.ControlValue(
    control: OverlayControl,
    paused: Boolean,
) {
    val value =
        when (control) {
            is OverlayControl.Gear -> control.gear
            is OverlayControl.Target -> control.watts
        }
    SlotValue(UiFormat.count(value), value == null, paused, OverdriveDimens.overlaySlotGearOrTarget)
}

// ---- Detailed additions -----------------------------------------------------------------------

@Composable
private fun SpeedRow(
    speedKmh: Double,
    distanceMeters: Double,
    paused: Boolean,
) {
    val colour = if (paused) OverdriveColors.textTertiary else OverdriveColors.textPrimary
    val distanceSlot = OverdriveDimens.overlaySlotHeartRate + gap + OverdriveDimens.overlaySlotCadence
    Column(
        Modifier.padding(horizontal = OverdriveDimens.overlayPaddingHorizontal),
        verticalArrangement = Arrangement.spacedBy(OverdriveDimens.overlayLabelValueGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Label("Speed", OverdriveDimens.overlaySlotPower)
            Label("Distance", distanceSlot)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            ValueWithUnit(UiFormat.speed(speedKmh), "km/h", OdType.numS, Modifier.requiredWidth(OverdriveDimens.overlaySlotPower), color = colour)
            ValueWithUnit(UiFormat.distanceKm(distanceMeters), "km", OdType.numS, Modifier.requiredWidth(distanceSlot), color = colour)
        }
    }
}

@Composable
private fun IntervalSection(interval: OverlayInterval) {
    Column(
        Modifier.padding(horizontal = OverdriveDimens.overlayPaddingHorizontal),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IntervalHead(interval.name, interval.repeatText, interval.remainingSeconds)
        Row {
            OdText("Next", style = OdType.label, color = OverdriveColors.textTertiary, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            OdText(interval.next, style = OdType.labelNum, color = OverdriveColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WorkoutProfile(
            data = interval.profile,
            look = ProfileLook.Overlay,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(OverdriveDimens.overlayDetailedProfileHeight),
            nowSeconds = interval.nowSeconds,
        )
    }
}

@Composable
private fun IntervalHead(
    name: String,
    repeatText: String?,
    remainingSeconds: Int,
) {
    val title =
        remember(name, repeatText) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name) }
                if (repeatText != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = OverdriveColors.textSecondary)) { append(" $repeatText") }
                }
            }
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OdText(title, Modifier.weight(1f).alignByBaseline(), style = OdType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OdText(UiFormat.elapsed(remainingSeconds), Modifier.alignByBaseline(), style = OdType.numS, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(4.dp))
        OdText("left", Modifier.alignByBaseline(), style = OdType.unit, color = OverdriveColors.textSecondary, maxLines = 1, softWrap = false)
    }
}
