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
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.ride.rides.ride
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.workouts.StepRow
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.ride.workouts.WorkoutDescription
import app.overdrive.ride.workouts.WorkoutFileError
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.ConfirmSheet
import app.overdrive.uikit.DeviceStatus
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
    /** The "no trainer" line's **Pair a trainer**: opens the Devices screen (ADR 0010). */
    val onOpenDevices: () -> Unit = {},
    /** The Planned section's refresh button: asks the service for the plan now (spec §5.7, ADR 0017). */
    val onRefreshPlan: () -> Unit = {},
)

/**
 * The Ready screen (design/system.md §6.1): a selectable list of Free ride and the loaded workouts on the left, and on
 * the right the chosen workout's title, stats, profile and step table with the **Start** button.
 *
 * @param startEnabled false until the trainer is connected (the engine is `IDLE`); the button then says why: no trainer is
 * paired (with a way to the Devices screen), Bluetooth cannot be used (with the one action that fixes it), or the trainer is
 * still connecting. A ride never starts over a simulated trainer by itself.
 * @param bluetooth the action of the Bluetooth notice shown while [DevicesUi.radio] is set.
 * @param overlayAccess what the overlay may do; while "Display over other apps" is missing a row at the top asks for it.
 * @param recovery an interrupted ride found on disk (ADR 0008): a notice at the top asks to save or discard it.
 * @param lastRide the ride saved last, with **Share** and **Save to folder** so its FIT file is reachable after the summary.
 * @param files share and save of a FIT file; the screen only calls them. [fileMessage] is what the last one said.
 * @param plan the planned workouts from the service (spec §5.7): a **Planned** section above the list, drawn only when there is
 * a plan to show ([PlanUi.visible]). Choosing one of its rows selects it like any workout; the ride's `workoutId` is its id.
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
    bluetooth: BluetoothFix = BluetoothFix.None,
    plan: PlanUi = PlanUi.Hidden,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            RideTopBar(
                "Overdrive",
                TopBarChip.Calm(
                    when {
                        startEnabled -> "Ready"
                        !devices.simulated && devices.trainer.status == DeviceStatus.NotPaired -> "No trainer"
                        else -> "Waiting for trainer"
                    },
                ),
                devices,
                topBar,
                mark = true,
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                WorkoutList(choice, workouts, settings, plan, actions, Modifier.width(360.dp).fillMaxHeight())
                VerticalRule()
                Detail(
                    choice,
                    settings,
                    startEnabled,
                    openError,
                    overlayAccess,
                    actions,
                    Modifier.weight(1f).fillMaxHeight(),
                    devices = devices,
                    bluetooth = bluetooth,
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
    plan: PlanUi,
    actions: ReadyActions,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PlannedSection(
                plan,
                choice,
                onSelect = { actions.onSelect(RideChoice.Planned(it)) },
                onRefresh = actions.onRefreshPlan,
            )
            SectionLabel("Ride", Modifier.padding(start = 16.dp, bottom = 12.dp))
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
    devices: DevicesUi,
    bluetooth: BluetoothFix,
    recovery: RecoveryState,
    onAskDiscard: () -> Unit,
    lastRide: StoredRide?,
    files: RideFileActions,
    fileMessage: String?,
) {
    Column(modifier.padding(horizontal = 40.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RecoveryNotice(recovery, onSave = { recovery.ride?.let(actions.onRecoverySave) }, onDiscard = onAskDiscard)
        val radio = devices.radio
        if (radio != null && !devices.simulated) BluetoothNotice(radio, bluetooth)
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
            if (!startEnabled) StartHint(devices, actions.onOpenDevices)
        }
    }
}

/** Why **Start** is off, in one sentence, and the one thing to do about it when there is something to do. */
@Composable
private fun StartHint(
    devices: DevicesUi,
    onOpenDevices: () -> Unit,
) {
    OdText(startHint(devices), style = OdType.body, color = OverdriveColors.textSecondary)
    if (!devices.simulated && devices.trainer.status == DeviceStatus.NotPaired) {
        OdButton("Pair a trainer", onOpenDevices, leadingIcon = OverdriveIcons.Bluetooth)
    }
}

/** The sentence beside a disabled **Start**. Never mentions a simulated trainer: nothing simulated starts by itself. */
internal fun startHint(devices: DevicesUi): String =
    when {
        devices.simulated -> "Waiting for the trainer to connect."
        devices.trainer.status == DeviceStatus.NotPaired -> "No trainer is paired."
        devices.radio != null -> "Waiting for Bluetooth."
        else -> "Waiting for the trainer to connect."
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
