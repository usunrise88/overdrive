package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.DayCell
import app.overdrive.companion.model.PickerState
import app.overdrive.companion.model.RangePicker
import app.overdrive.companion.model.Words
import app.overdrive.uikit.BottomSheet
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveColors
import java.time.LocalDate

/**
 * The Custom range's date sheet (design/companion.md §4.14, "Custom range"): one month at a time, a dot under a day with a ride, future
 * days off, the first and last day inverted with a band between. It draws [state] and nothing else; **Show** applies the range, and
 * **Cancel**, Back, the scrim and a swipe down (all [onDismiss]) change nothing. [state] null is closed.
 *
 * @param rideDays the local days of the tablet rides in the cached list, for the dots and the count.
 */
@Composable
fun RangePickerSheet(
    state: PickerState?,
    today: LocalDate,
    rideDays: List<LocalDate>,
    words: Words,
    onChange: (PickerState) -> Unit,
    onShow: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = rememberRetained(state)
    BottomSheet(state != null, onDismiss) {
        if (shown != null) {
            PickerContent(shown, today, rideDays, words, onChange, onShow, onDismiss)
        }
    }
}

@Composable
private fun PickerContent(
    state: PickerState,
    today: LocalDate,
    rideDays: List<LocalDate>,
    words: Words,
    onChange: (PickerState) -> Unit,
    onShow: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val rideSet = remember(rideDays) { rideDays.toSet() }
    val grid = remember(state, today, rideSet) { RangePicker.grid(state, today, rideSet) }
    val names = remember(words) { words.weekdayNames() }
    Column(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText("Custom range", style = OdType.heading)
            OdText(
                RangePicker.choice(state, RangePicker.ridesIn(state, rideDays), words),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = OdType.labelNum,
                color = OverdriveColors.textSecondary,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OdIconButton(OverdriveIcons.ChevronLeft, { onChange(RangePicker.previous(state)) }, "Previous month")
            OdText(
                words.monthTitle(state.month.atDay(1).toString()),
                Modifier.weight(1f),
                style = OdType.bodyStrong,
                textAlign = TextAlign.Center,
            )
            val canNext = RangePicker.canGoNext(state, today)
            OdIconButton(
                OverdriveIcons.ChevronRight,
                { onChange(RangePicker.next(state, today)) },
                "Next month",
                modifier = Modifier.alpha(if (canNext) 1f else 0.35f),
                enabled = canNext,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            for (name in names) {
                OdText(
                    name,
                    Modifier.weight(1f),
                    style = OdType.caption,
                    color = OverdriveColors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (week in grid) {
                Row(Modifier.fillMaxWidth()) {
                    for (cell in week) {
                        if (cell == null) {
                            Spacer(Modifier.weight(1f).height(DAY_HEIGHT))
                        } else {
                            DayButton(
                                cell,
                                state.complete,
                                words,
                                Modifier.weight(1f),
                            ) { onChange(RangePicker.tap(state, cell.date, today)) }
                        }
                    }
                }
            }
        }
        OdText(
            "Tap the first day, then the last. A dot marks a day with a ride.",
            style = OdType.label,
            color = OverdriveColors.textTertiary,
        )
        val first = state.first
        val last = state.last
        OdButton(
            RangePicker.showLabel(state, words),
            { if (first != null && last != null) onShow(first, last) },
            Modifier.fillMaxWidth(),
            variant = ButtonVariant.Primary,
            enabled = first != null && last != null,
        )
        OdButton("Cancel", onDismiss, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
    }
}

/** One day: 44 dp high, the band between the ends, the ends inverted, a dot for a ride, a ring for today. */
@Composable
private fun DayButton(
    cell: DayCell,
    complete: Boolean,
    words: Words,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = rememberInteractionSource()
    val end = cell.isFirst || cell.isLast
    val band = OverdriveColors.surface3
    val dot =
        when {
            !cell.hasRide -> Color.Transparent
            end -> OverdriveColors.textOnPrimary
            else -> OverdriveColors.textSecondary
        }
    Box(
        modifier
            .height(DAY_HEIGHT)
            .drawBehind {
                when {
                    cell.inBetween -> {
                        drawRect(band)
                    }

                    complete && cell.isFirst && !cell.isLast -> {
                        drawRect(
                            band,
                            Offset(size.width / 2f, 0f),
                            Size(size.width / 2f, size.height),
                        )
                    }

                    complete && cell.isLast && !cell.isFirst -> {
                        drawRect(band, Offset.Zero, Size(size.width / 2f, size.height))
                    }
                }
            }.semantics(mergeDescendants = true) {
                contentDescription = cell.description(words)
                if (cell.future) disabled()
            }.plainClickable(source, enabled = !cell.future, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (end) Modifier.background(OverdriveColors.textPrimary, RoundedCornerShape(8.dp)) else Modifier)
                .then(
                    if (cell.isToday &&
                        !end
                    ) {
                        Modifier.border(1.dp, OverdriveColors.borderStrong, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OdText(
                    cell.date.dayOfMonth.toString(),
                    style = if (end) OdType.bodyNum.copy(fontWeight = FontWeight.SemiBold) else OdType.bodyNum,
                    color =
                        when {
                            end -> OverdriveColors.textOnPrimary
                            cell.future -> OverdriveColors.textTertiary.copy(alpha = 0.55f)
                            else -> OverdriveColors.textPrimary
                        },
                )
                Box(
                    Modifier
                        .size(4.dp)
                        .background(dot, CircleShape),
                )
            }
        }
    }
}

private val DAY_HEIGHT = 44.dp
