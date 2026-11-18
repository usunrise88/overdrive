package app.overdrive.ride.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.core.workout.Workout
import app.overdrive.data.rides.StoredRide
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.overlay.OverlayAccess
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.ride.rides.ride
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.workouts.StepRow
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.ride.workouts.WorkoutDescription
import app.overdrive.ride.workouts.WorkoutFileError
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.ConfirmSheet
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.SelectableRow
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.VerticalRule
import app.overdrive.uikit.ZoneChip
import app.overdrive.uikit.chart.ProfileLook
import app.overdrive.uikit.chart.WorkoutProfile
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/** What the rider can do on the Ready screen. */
class ReadyActions(
    val onSelect: (RideChoice) -> Unit,
    val onStart: () -> Unit,
    val onOpenFile: () -> Unit,
    val onDismissError: () -> Unit,
    /** The overlay row's **Allow**: asks for what the overlay needs (ADR 0006). */
    val onAllowOverlay: () -> Unit = {},
    /** The recovery notice's **Save** (and **Retry** after a failed save): keep that interrupted ride (ADR 0008). */
    val onRecoverySave: (StoredRide) -> Unit = {},
    /** The recovery notice's **Discard** for that ride, called once the rider has confirmed. */
    val onRecoveryDiscard: (StoredRide) -> Unit = {},
)

/**
 * The Ready screen (design/system.md §6.1): a selectable list of Free ride and the loaded workouts on the left, and on
 * the right the chosen workout's title, stats, profile and step table with the **Start** button.
 *
 * @param startEnabled false until the trainer is connected (the engine is `IDLE`); the button then says why.
 * @param overlayAccess what the overlay may do; while "Display over other apps" is missing a row at the top asks for it.
 * @param recovery an interrupted ride found on disk (ADR 0008): a notice at the top asks to save or discard it.
 * @param lastRide the ride saved last, with **Share** and **Save to folder** so its FIT file is reachable after the summary.
 * @param files share and save of a FIT file; the screen only calls them. [fileMessage] is what the last one said.
 */
@Composable
fun ReadyScreen(
    choice: RideChoice,
    workouts: List<Workout>,
    settings: AppSettings,
    devices: DevicesUi,
    startEnabled: Boolean,
    openError: WorkoutFileError?,
    topBar: TopBarActions,
    actions: ReadyActions,
    modifier: Modifier = Modifier,
    overlayAccess: OverlayAccess = OverlayAccess.Granted,
    recovery: RecoveryState = RecoveryState.None,
    lastRide: StoredRide? = null,
    files: RideFileActions = RideFileActions.None,
    fileMessage: String? = null,
    forceDiscardConfirm: Boolean = false,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            RideTopBar(
                "Overdrive",
                TopBarChip.Calm(if (startEnabled) "Ready" else "Waiting for trainer"),
                devices,
                topBar,
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                WorkoutList(choice, workouts, settings, actions, Modifier.width(360.dp).fillMaxHeight())
                VerticalRule()
                Detail(
                    choice,
                    settings,
                    startEnabled,
                    openError,
                    overlayAccess,
                    actions,
                    Modifier.weight(1f).fillMaxHeight(),
                    recovery = recovery,
                    onAskDiscard = { confirmDiscard = true },
                    lastRide = lastRide,
                    files = files,
                    fileMessage = fileMessage,
                )
            }
        }
        // Throwing a ride away cannot be undone, so it asks once (the same sheet as finishing a ride).
        ConfirmSheet(
            visible = confirmDiscard || forceDiscardConfirm,
            title = "Discard this ride?",
            confirmLabel = "Discard",
            dismissLabel = "Keep it",
            onConfirm = {
                confirmDiscard = false
                recovery.ride?.let(actions.onRecoveryDiscard)
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun WorkoutList(
    choice: RideChoice,
    workouts: List<Workout>,
    settings: AppSettings,
    actions: ReadyActions,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Ride", Modifier.padding(start = 16.dp, bottom = 4.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SelectableRow(
                title = "Free ride",
                subtitle = "Simulation on the flat · gears",
                selected = choice is RideChoice.Free,
                onClick = { actions.onSelect(RideChoice.Free) },
            )
            for (workout in workouts) {
                val description = remember(workout, settings.ftp) { WorkoutDescriber.describe(workout, settings.ftp) }
                SelectableRow(
                    title = workout.name,
                    subtitle = description.meta,
                    selected = (choice as? RideChoice.Planned)?.workout?.id == workout.id,
                    onClick = { actions.onSelect(RideChoice.Planned(workout)) },
                )
            }
        }
        OdButton(
            "Open workout file",
            actions.onOpenFile,
            Modifier.padding(horizontal = 16.dp),
            variant = ButtonVariant.Ghost,
            leadingIcon = OverdriveIcons.FolderOpen,
            horizontalPadding = 0.dp,
        )
    }
}

@Composable
private fun Detail(
    choice: RideChoice,
    settings: AppSettings,
    startEnabled: Boolean,
    openError: WorkoutFileError?,
    overlayAccess: OverlayAccess,
    actions: ReadyActions,
    modifier: Modifier,
    recovery: RecoveryState,
    onAskDiscard: () -> Unit,
    lastRide: StoredRide?,
    files: RideFileActions,
    fileMessage: String?,
) {
    Column(modifier.padding(horizontal = 40.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RecoveryNotice(recovery, onSave = { recovery.ride?.let(actions.onRecoverySave) }, onDiscard = onAskDiscard)
        if (!overlayAccess.canDrawOverlays) OverlayPermissionNotice(actions.onAllowOverlay)
        if (openError != null) {
            Notice(
                icon = OverdriveIcons.TriangleAlert,
                title = openError.title,
                text = openError.text,
                actionLabel = "Dismiss",
                onAction = actions.onDismissError,
            )
        }
        if (lastRide != null) LastRideRow(lastRide, files, fileMessage)
        when (choice) {
            RideChoice.Free -> FreeRideDetail(settings, Modifier.weight(1f))
            is RideChoice.Planned -> WorkoutDetail(choice.workout, settings, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            OdButton(
                "Start",
                actions.onStart,
                variant = ButtonVariant.Primary,
                leadingIcon = OverdriveIcons.Play,
                height = 64.dp,
                minWidth = 240.dp,
                enabled = startEnabled,
            )
            if (!startEnabled) {
                OdText(
                    "Waiting for the trainer to connect.",
                    style = OdType.body,
                    color = OverdriveColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    unit: String? = null,
) {
    Column {
        OdText(label, style = OdType.label, color = OverdriveColors.textTertiary)
        ValueWithUnit(value, unit, OdType.numS)
    }
}

@Composable
private fun FreeRideDetail(
    settings: AppSettings,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            OdText("Free ride", style = OdType.title)
            OdText(
                "Simulation on the flat with virtual gears. Ride as long as you like, then finish.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StatItem("FTP", settings.ftp.toString(), "W")
            StatItem("Gears", settings.gearCount.toString())
            StatItem("Flat gear", settings.neutralGear.toString())
            StatItem("Mode", "Gears")
        }
    }
}

@Composable
private fun WorkoutDetail(
    workout: Workout,
    settings: AppSettings,
    modifier: Modifier,
) {
    val description = remember(workout, settings.ftp) { WorkoutDescriber.describe(workout, settings.ftp) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            OdText(description.title, style = OdType.title, maxLines = 1)
            if (description.description != null) {
                OdText(
                    description.description,
                    style = OdType.body,
                    color = OverdriveColors.textSecondary,
                    maxLines = 2,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StatItem("Duration", UiFormat.elapsed(description.durationSeconds))
            StatItem("FTP", settings.ftp.toString(), "W")
            if (description.hardestWatts != null) {
                StatItem("Hardest step", description.hardestWatts.toString(), "W · Z${description.hardestZone}")
            }
            StatItem("Mode", description.mode)
        }
        WorkoutProfile(description.profile, ProfileLook.Preview, Modifier.fillMaxWidth().height(168.dp))
        StepTable(description, Modifier.weight(1f))
    }
}

@Composable
private fun StepTable(
    description: WorkoutDescription,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        for (row in description.rows) StepTableRow(row)
    }
}

@Composable
private fun StepTableRow(row: StepRow) {
    HorizontalRule()
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp)) { ZoneChip(row.zone) }
        OdText(row.name, Modifier.weight(1f), style = OdType.bodyStrong, maxLines = 1)
        OdText(
            UiFormat.elapsed(row.durationSeconds),
            Modifier.width(80.dp),
            style = OdType.labelNum,
            color = OverdriveColors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        OdText(
            row.percentText,
            Modifier.width(96.dp),
            style = OdType.labelNum,
            color = OverdriveColors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        OdText(row.wattsText, Modifier.width(112.dp), style = OdType.labelNum, textAlign = TextAlign.End, maxLines = 1)
    }
}
