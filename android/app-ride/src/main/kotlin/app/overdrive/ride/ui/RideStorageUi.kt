package app.overdrive.ride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.data.rides.StoredRide
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The words of the recovery notice about one ride: when it started and how long it ran, then what to do. "Wednesday 18
 * November · 18:04 · 32:17 ridden. Save it to keep the ride, or discard it."
 */
internal fun recoveryText(
    ride: StoredRide,
    more: Int,
): String {
    val what = "${FinishedFormat.dateLine(
        ride.startEpochMillis,
        null,
    )} · ${UiFormat.elapsed(ride.durationSeconds)} ridden."
    return if (more > 0) "$what $more more after this one." else "$what Save it to keep the ride, or discard it."
}

/**
 * The Ready screen's notice about a ride the app found cut off (spec §4.2 "an interrupted ride is recovered on the next
 * launch", ADR 0008): **Save** recovers and stores it like a finished ride, **Discard** (after a confirmation the screen
 * asks for through [onDiscard]) deletes it. While it is stored the notice says so; if that fails it says why and offers
 * **Retry**. Nothing is shown when there is no such ride.
 */
@Composable
fun RecoveryNotice(
    state: RecoveryState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        RecoveryState.None -> {
            Unit
        }

        is RecoveryState.Found -> {
            Notice(
                icon = OverdriveIcons.TriangleAlert,
                title = "An interrupted ride was found",
                text = recoveryText(state.ride, state.more),
                modifier = modifier,
                actionLabel = "Save",
                onAction = onSave,
                secondaryLabel = "Discard",
                onSecondary = onDiscard,
            )
        }

        is RecoveryState.Saving -> {
            Notice(
                icon = OverdriveIcons.Info,
                title = "Saving the ride…",
                text = recoveryText(state.ride, state.more),
                modifier = modifier,
            )
        }

        is RecoveryState.Failed -> {
            Notice(
                icon = OverdriveIcons.TriangleAlert,
                title = "The ride could not be saved",
                text =
                    if (state.retryable) {
                        "Free up some space on the tablet and try again."
                    } else {
                        "The ride's file is damaged. Discard it."
                    },
                modifier = modifier,
                actionLabel = if (state.retryable) "Retry" else "Discard",
                onAction = if (state.retryable) onSave else onDiscard,
                secondaryLabel = if (state.retryable) "Discard" else null,
                onSecondary = onDiscard,
            )
        }
    }
}

/**
 * "Last ride: Sweet spot 3×10 · 1:05:00" with **Share** and **Save to folder**, so the FIT file stays reachable after the
 * summary has been left (spec §4.8). A small row, not a history: there is no list of rides in Phase 1.
 *
 * @param message what the last share or save of this ride said, if anything.
 */
@Composable
fun LastRideRow(
    ride: StoredRide,
    files: RideFileActions,
    message: String?,
    modifier: Modifier = Modifier,
) {
    OdCard(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OdText("Last ride", style = OdType.caption, color = OverdriveColors.textTertiary)
                OdText(
                    "${ride.title} · ${UiFormat.elapsed(ride.durationSeconds)}",
                    style = OdType.bodyStrong,
                    maxLines = 1,
                )
                if (message !=
                    null
                ) {
                    OdText(message, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                }
            }
            OdButton("Share", { files.share(ride) }, leadingIcon = OverdriveIcons.Share2, height = 40.dp)
            OdButton(
                "Save to folder",
                { files.saveToFolder(ride) },
                variant = ButtonVariant.Secondary,
                leadingIcon = OverdriveIcons.FolderOpen,
                height = 40.dp,
            )
        }
    }
}

/**
 * The one notice of the riding screen about storage: the ride is not being written to disk (ADR 0008). It says what to
 * expect, not why; the cause is in the log. The ride itself is unaffected and is stored from memory when it ends.
 */
@Composable
fun NotSavedNotice(modifier: Modifier = Modifier) {
    Notice(
        icon = OverdriveIcons.TriangleAlert,
        title = "This ride is not being saved",
        text = "Keep riding. Overdrive tries to save it again when you finish.",
        modifier = modifier,
    )
}
