package app.overdrive.uikit.overlay

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
            SlotValue(
                UiFormat.count(state.heartRate),
                state.heartRate == null,
                state.paused,
                OverdriveDimens.overlaySlotHeartRate,
            )
            SlotValue(
                UiFormat.count(state.cadence),
                state.cadence == null,
                state.paused,
                OverdriveDimens.overlaySlotCadence,
            )
            TimeValue(state.elapsedSeconds)
            ControlValue(state.control, state.paused)
        }
        val hold = state.hold
        if (hold != null) HoldLine(hold, state.paused)
    }
}

@Composable
private fun Label(
    text: String,
    slot: Dp,
) {
    OdText(
        text,
        Modifier.requiredWidth(slot),
        style = OdType.caption,
        color = OverdriveColors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun PowerLabel(zoneTag: Int?) {
    Row(Modifier.requiredWidth(OverdriveDimens.overlaySlotPower), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OdText("Power", style = OdType.caption, color = OverdriveColors.textTertiary, maxLines = 1, softWrap = false)
        if (zoneTag != null) {
            OdText(
                "Z$zoneTag",
                style = OdType.captionNum,
                color = OverdriveColors.textSecondary,
                maxLines = 1,
                softWrap = false,
            )
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
    Row(
        Modifier.requiredWidth(OverdriveDimens.overlaySlotGearOrTarget),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (control) {
            is OverlayControl.Gear -> {
                OdText(
                    "Gear",
                    style = OdType.caption,
                    color = OverdriveColors.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            is OverlayControl.Target -> {
                OdText(
                    "Target",
                    style = OdType.caption,
                    color = OverdriveColors.textTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
                if (control.intensityPercent != null) {
                    OdText(
                        UiFormat.percent(control.intensityPercent),
                        style = OdType.captionNum,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            // A word in primary text, not tertiary: the guard is the thing to notice (design/system.md §5.6). It may run into the
            // frame's right padding, which is 16 dp.
            is OverlayControl.Guard -> {
                OdText(
                    "Cadence guard",
                    Modifier.requiredWidth(GUARD_LABEL_WIDTH),
                    style = OdType.caption,
                    color = OverdriveColors.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

private val GUARD_LABEL_WIDTH = 96.dp

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
            is OverlayControl.Guard -> control.watts
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
            ValueWithUnit(
                UiFormat.speed(speedKmh),
                "km/h",
                OdType.numS,
                Modifier.requiredWidth(OverdriveDimens.overlaySlotPower),
                color = colour,
            )
            ValueWithUnit(
                UiFormat.distanceKm(distanceMeters),
                "km",
                OdType.numS,
                Modifier.requiredWidth(distanceSlot),
                color = colour,
            )
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
            OdText(
                interval.next,
                style = OdType.labelNum,
                color = OverdriveColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val previous = interval.previousLine
        if (previous != null) {
            OdText(
                previous,
                style = OdType.label,
                color = OverdriveColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    withStyle(
                        SpanStyle(fontWeight = FontWeight.Normal, color = OverdriveColors.textSecondary),
                    ) { append(" $repeatText") }
                }
            }
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OdText(
            title,
            Modifier.weight(1f).alignByBaseline(),
            style = OdType.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OdText(
            UiFormat.elapsed(remainingSeconds),
            Modifier.alignByBaseline(),
            style = OdType.numS,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(4.dp))
        OdText(
            "left",
            Modifier.alignByBaseline(),
            style = OdType.unit,
            color = OverdriveColors.textSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ---- The heart-rate hold line ---------------------------------------------------------------------

/** The gauge: 72 dp by 8 dp, the band in the middle half of it, the heart rate a 3 dp marker. Flat fills, no animation. */
private val gaugeWidth = 72.dp
private val gaugeHeight = 8.dp

/**
 * One line under the main row while a heart-rate step plays (spec §11.2): where the heart rate is against the band, as a position on a
 * small gauge **and** as words ("In band", "Above band", "Below band"), never colour alone; the target with what the gear buttons moved
 * it by; and what the strap is doing. The controller's power is the fifth cell's target above, so it is not repeated here.
 */
@Composable
private fun HoldLine(
    hold: OverlayHold,
    paused: Boolean,
) {
    val words = remember(hold) { holdWords(hold) }
    val colour = if (paused) OverdriveColors.textTertiary else OverdriveColors.textSecondary
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hold.place != null && hold.heartRate != null) {
            HoldGauge(hold.heartRate, hold.targetBpm, hold.band, paused)
        }
        OdText(words, style = OdType.labelNum, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The words of the line, one plain sentence (design/system.md §5.6). Public so a test can read them. */
fun holdWords(hold: OverlayHold): String {
    val target = "${hold.targetBpm} bpm" + offsetWords(hold.offsetBpm)
    return when (hold.phase) {
        HoldPhase.EasingIn -> {
            "Easing in · target $target ±${hold.band}"
        }

        HoldPhase.StrapLost -> {
            "Strap lost · holding ${UiFormat.count(hold.watts)} W"
        }

        HoldPhase.StrapDropped -> {
            "Strap lost · power at the minimum, ${UiFormat.count(hold.minimumWatts)} W"
        }

        HoldPhase.Holding -> {
            when (hold.place) {
                HoldPlace.In -> "Target $target ±${hold.band} · In band"
                HoldPlace.Above -> "Target $target ±${hold.band} · Above band"
                HoldPlace.Below -> "Target $target ±${hold.band} · Below band"
                null -> "No heart rate · target $target"
            }
        }
    }
}

private fun offsetWords(offsetBpm: Int): String =
    when {
        offsetBpm > 0 -> " (+$offsetBpm)"
        offsetBpm < 0 -> " (−${-offsetBpm})"
        else -> ""
    }

/**
 * The position of [heartRate] against the band: a track, the band in its middle half (target ± band, the track shows ± two bands), and
 * a marker clamped to the ends. Redrawn only when a value changes, like everything on the overlay.
 */
@Composable
private fun HoldGauge(
    heartRate: Int,
    targetBpm: Int,
    band: Int,
    paused: Boolean,
) {
    Canvas(Modifier.size(gaugeWidth, gaugeHeight)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(OverdriveColors.surface3, size = size, cornerRadius = radius)
        val span = 4f * band
        val bandStart = size.width * 0.25f
        drawRoundRect(
            OverdriveColors.borderStrong,
            topLeft = Offset(bandStart, 0f),
            size = Size(size.width * 0.5f, size.height),
            cornerRadius = radius,
        )
        val position = ((heartRate - targetBpm + 2f * band) / span).coerceIn(0f, 1f)
        val markerWidth = 3.dp.toPx()
        val x = (position * (size.width - markerWidth)).coerceIn(0f, size.width - markerWidth)
        drawRoundRect(
            if (paused) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
            topLeft = Offset(x, 0f),
            size = Size(markerWidth, size.height),
            cornerRadius = CornerRadius(markerWidth / 2f),
        )
    }
}
