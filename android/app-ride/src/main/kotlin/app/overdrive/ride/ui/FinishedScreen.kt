package app.overdrive.ride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.data.rides.StoredRide
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.FinishedRide
import app.overdrive.ride.host.LapRow
import app.overdrive.ride.rides.SaveState
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.ConfirmSheet
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.StatTile
import app.overdrive.uikit.TileGroup
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.ZoneTimeBars
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Dates and times of the finished ride, as text. */
object FinishedFormat {
    /** A file's size for a person: "312 kB", "1.4 MB". Decimal units, like the system's file screens. */
    fun fileSize(bytes: Long): String =
        when {
            bytes < 1_000 -> "$bytes B"
            bytes < 1_000_000 -> "${(bytes + 500) / 1_000} kB"
            else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        }

    /** "Wednesday 18 November · 18:04–19:09", or without the end when the ride has none. */
    fun dateLine(
        startEpochMillis: Long,
        endEpochMillis: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
        val clock = DateTimeFormatter.ofPattern("HH:mm", locale)
        val day = DateTimeFormatter.ofPattern("EEEE d MMMM", locale).format(start)
        val end = endEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return if (end ==
            null
        ) {
            "$day · ${clock.format(start)}"
        } else {
            "$day · ${clock.format(start)}–${clock.format(end)}"
        }
    }
}

/**
 * The summary after a ride (design/system.md §6.4): the headline numbers, the time in each power zone and the laps, and
 * at the bottom what became of the ride on disk (ADR 0008): "Saving…" while the record and the FIT file are written, then
 * the saved file with **Share FIT file** and **Save to folder**, or, if nothing could be stored, what happened and **Retry**.
 *
 * @param files share and save of the FIT file; the screen only calls them.
 * @param fileMessage what the last share or save said, for this ride, or null.
 * @param onRetrySave tries to store the ride again after a failed save.
 */
@Composable
fun FinishedScreen(
    ride: FinishedRide?,
    devices: DevicesUi,
    topBar: TopBarActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    files: RideFileActions = RideFileActions.None,
    fileMessage: String? = null,
    onRetrySave: () -> Unit = {},
    forceLeaveConfirm: Boolean = false,
) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    // Leaving with a ride that is not stored may lose it, so that one question is asked; every other Done is immediate.
    val leave: () -> Unit = { if (ride?.save is SaveState.Failed) confirmLeave = true else onDone() }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            RideTopBar(
                if (ride?.save is SaveState.Saved) "Ride saved" else "Ride finished",
                TopBarChip.None,
                devices,
                topBar,
            )
            if (ride == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OdButton("Done", onDone, variant = ButtonVariant.Primary, height = 64.dp, minWidth = 160.dp)
                }
            } else {
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column {
                            OdText(ride.title, style = OdType.title, maxLines = 1)
                            OdText(
                                FinishedFormat.dateLine(ride.startEpochMillis, ride.endEpochMillis),
                                style = OdType.body,
                                color = OverdriveColors.textSecondary,
                            )
                        }
                        SummaryTiles(ride)
                        OdCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                            OdText(
                                "Time in power zones",
                                Modifier.padding(bottom = 8.dp),
                                style = OdType.label,
                                color = OverdriveColors.textTertiary,
                            )
                            ZoneTimeBars(ride.zoneSeconds)
                        }
                        Box(Modifier.weight(1f))
                        SaveBar(ride.save, fileMessage, files, onRetrySave, leave)
                    }
                    LapsCard(ride.laps, Modifier.width(440.dp).fillMaxHeight())
                }
            }
        }
        ConfirmSheet(
            visible = confirmLeave || forceLeaveConfirm,
            title = "Leave without saving this ride?",
            confirmLabel = "Leave",
            dismissLabel = "Stay",
            onConfirm = {
                confirmLeave = false
                onDone()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** The bottom of the finished screen: where the ride stands on disk, its file actions, and **Done**. */
@Composable
private fun SaveBar(
    save: SaveState,
    fileMessage: String?,
    files: RideFileActions,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SaveStatus(save, fileMessage)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (save) {
                is SaveState.Saved -> {
                    OdButton(
                        "Share FIT file",
                        { files.share(save.ride) },
                        leadingIcon = OverdriveIcons.Share2,
                        height = 64.dp,
                    )
                    OdButton(
                        "Save to folder",
                        { files.saveToFolder(save.ride) },
                        leadingIcon = OverdriveIcons.FolderOpen,
                        height = 64.dp,
                    )
                }

                SaveState.Saving -> {
                    OdButton("Share FIT file", {}, leadingIcon = OverdriveIcons.Share2, height = 64.dp, enabled = false)
                    OdButton(
                        "Save to folder",
                        {},
                        leadingIcon = OverdriveIcons.FolderOpen,
                        height = 64.dp,
                        enabled = false,
                    )
                }

                is SaveState.Failed -> {
                    if (save.retryable) {
                        OdButton(
                            "Retry",
                            onRetry,
                            leadingIcon = OverdriveIcons.RefreshCw,
                            height = 64.dp,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f))
            OdButton("Done", onDone, variant = ButtonVariant.Primary, height = 64.dp, minWidth = 160.dp)
        }
    }
}

@Composable
private fun SaveStatus(
    save: SaveState,
    fileMessage: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (save) {
            SaveState.Saving -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner()
                    OdText("Saving the ride…", style = OdType.label, color = OverdriveColors.textSecondary)
                }
            }

            is SaveState.Saved -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OdIcon(OverdriveIcons.Check, size = 16.dp, tint = OverdriveColors.textSecondary)
                    OdText(
                        savedLine(save.ride),
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }

            is SaveState.Failed -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OdIcon(OverdriveIcons.TriangleAlert, size = 16.dp)
                    OdText(save.message, style = OdType.label)
                }
            }
        }
        if (fileMessage != null) OdText(fileMessage, style = OdType.label, color = OverdriveColors.textPrimary)
    }
}

/** "Saved on this tablet · 2026-11-18 18-04 Sweet spot 3×10.fit · 312 kB". */
private fun savedLine(ride: StoredRide): String =
    "Saved on this tablet · ${fileName(ride)}" + (ride.fitBytes?.let { " · ${FinishedFormat.fileSize(it)}" } ?: "")

@Composable
private fun SummaryTiles(ride: FinishedRide) {
    val s = ride.summary
    val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
    val tile: @Composable (String, String?, String?) -> Unit = { label, value, unit ->
        StatTile(
            label,
            value,
            unit,
            Modifier.fillMaxWidth(),
            valueStyle = OdType.numS,
            padding = padding,
            spaceBetween = false,
        )
    }
    TileGroup(
        columns = 4,
        tiles =
            listOf(
                { tile("Duration", UiFormat.elapsed(s.durationSeconds), null) },
                { tile("Moving time", UiFormat.elapsed(s.movingSeconds), null) },
                { tile("Average power", s.averagePower?.roundToInt()?.toString(), "W") },
                { tile("Normalised power", s.normalizedPower?.roundToInt()?.toString(), "W") },
                { tile("Average heart rate", s.averageHeartRate?.roundToInt()?.toString(), "bpm") },
                { tile("Max heart rate", s.maxHeartRate?.toString(), "bpm") },
                { tile("Work", s.workKilojoules.roundToInt().toString(), "kJ") },
                { tile("Load", s.trainingStressScore?.roundToInt()?.toString(), null) },
            ),
    )
}

@Composable
private fun LapsCard(
    laps: List<LapRow>,
    modifier: Modifier,
) {
    Column(modifier) {
        OdCard(Modifier.weight(1f, fill = false), padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            LapLine("Lap", "", "Time", "Watts", "HR", header = true)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (lap in laps) {
                    HorizontalRule()
                    LapLine(
                        lap.number.toString(),
                        lap.name,
                        UiFormat.elapsed(lap.durationSeconds),
                        UiFormat.count(lap.averagePower),
                        UiFormat.count(lap.averageHeartRate),
                        header = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun LapLine(
    number: String,
    name: String,
    time: String,
    watts: String,
    heartRate: String,
    header: Boolean,
) {
    val labelStyle = if (header) OdType.caption else OdType.labelNum
    Row(
        Modifier.fillMaxWidth().height(if (header) 24.dp else 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdText(number, Modifier.width(28.dp), style = labelStyle, color = OverdriveColors.textTertiary, maxLines = 1)
        OdText(name, Modifier.weight(1f), style = OdType.label, maxLines = 1)
        OdText(
            time,
            Modifier.width(64.dp),
            style = labelStyle,
            color = if (header) OverdriveColors.textTertiary else OverdriveColors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        OdText(
            watts,
            Modifier.width(64.dp),
            style = labelStyle,
            color = if (header) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        OdText(
            heartRate,
            Modifier.width(56.dp),
            style = labelStyle,
            color = if (header) OverdriveColors.textTertiary else OverdriveColors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
