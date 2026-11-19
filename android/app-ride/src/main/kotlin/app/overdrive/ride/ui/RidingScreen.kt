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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.MediaController
import app.overdrive.ride.host.RideActions
import app.overdrive.ride.host.RideMapping
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.ConfirmSheet
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdIconButton
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.StatTile
import app.overdrive.uikit.TileGroup
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.ValueWithUnit
import app.overdrive.uikit.ZoneChip
import app.overdrive.uikit.Zones
import app.overdrive.uikit.chart.ProfileLook
import app.overdrive.uikit.chart.WorkoutProfile
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.overlay.HoldPhase
import app.overdrive.uikit.overlay.holdWords
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.roundToInt

/**
 * The riding and paused screens (design/system.md §6.2, §6.3): one layout, two states. Every Click action has a button
 * here and the state the Click changes (gear or intensity, interval, paused) is always on screen.
 */
@Composable
fun RidingScreen(
    ui: RideUi,
    devices: DevicesUi,
    topBar: TopBarActions,
    actions: RideActions,
    media: MediaController,
    modifier: Modifier = Modifier,
    forceConfirm: Boolean = false,
) {
    val snapshot = ui.snapshot
    val paused = snapshot.state == RideState.PAUSED
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            RideTopBar(
                title = ui.choice.title,
                chip = if (paused) TopBarChip.Attention("Paused") else TopBarChip.Calm("Riding"),
                devices = devices,
                actions = topBar,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (paused) PausedBanner(snapshot)
                strapNotice(snapshot)?.let { StrapBanner(it) }
                if (ui.logFailed) NotSavedNotice()
                Row(Modifier.fillMaxWidth().height(312.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PowerCard(ui, paused, Modifier.width(424.dp).fillMaxHeight())
                    LiveTiles(ui, paused, Modifier.weight(1f).fillMaxHeight())
                }
                WorkoutCard(ui, Modifier.weight(1f, fill = ui.profile != null).fillMaxWidth())
                if (ui.profile == null) Box(Modifier.weight(1f))
                ControlBar(snapshot, paused, actions, media, onFinish = { confirmFinish = true })
            }
        }
        ConfirmSheet(
            visible = confirmFinish || forceConfirm,
            title = "Finish this ride?",
            confirmLabel = "Finish",
            dismissLabel = "Keep riding",
            onConfirm = {
                confirmFinish = false
                actions.finish()
            },
            onDismiss = { confirmFinish = false },
        )
    }
}

// ---- Paused banner -----------------------------------------------------------------------------

@Composable
private fun PausedBanner(snapshot: RideSnapshot) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(
                48.dp,
            ).background(OverdriveColors.surface2, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdIcon(OverdriveIcons.Pause)
        OdText("Paused at ${UiFormat.elapsed(snapshot.elapsedSeconds)}", style = OdType.bodyStrong, maxLines = 1)
        OdText(
            pausedAdvice(snapshot),
            style = OdType.body,
            color = OverdriveColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What to say when the heart-rate strap is lost during a heart-rate step (spec §11.2), or null: "Strap lost · holding 140 W" after 15 s, and
 * "Strap lost · power at the minimum, 80 W" after 60 s. The overlay says the same in its own line.
 */
fun strapNotice(snapshot: RideSnapshot): String? =
    RideMapping
        .hold(snapshot)
        ?.takeIf { it.phase == HoldPhase.StrapLost || it.phase == HoldPhase.StrapDropped }
        ?.let(::holdWords)

@Composable
private fun StrapBanner(words: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(OverdriveColors.surface2, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OdIcon(OverdriveIcons.TriangleAlert)
        OdText(words, style = OdType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What to do about a pause, by reason (design/system.md §11: say what happened and what to do). */
fun pausedAdvice(snapshot: RideSnapshot): String {
    val ramp = if (snapshot.mode == TrainerMode.ERG) " Target power ramps back in over 3 s." else ""
    return when (snapshot.pauseReason) {
        PauseReason.TRAINER_DISCONNECTED -> "The trainer connection was lost. The ride continues when it is back."
        PauseReason.AUTO -> "You stopped pedalling. Pedal or press Resume to continue.$ramp"
        PauseReason.MANUAL, null -> "Press Resume to continue.$ramp"
    }
}

// ---- Power card and tiles ----------------------------------------------------------------------

private val heroUnit: TextStyle = OdType.unit.copy(fontSize = 22.sp, lineHeight = 22.sp)

@Composable
private fun PowerCard(
    ui: RideUi,
    paused: Boolean,
    modifier: Modifier,
) {
    val snapshot = ui.snapshot
    val zone = snapshot.zone?.number
    val dim = if (paused) OverdriveColors.textTertiary else OverdriveColors.textPrimary
    OdCard(
        modifier,
        padding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            OdText("Power", Modifier.weight(1f), style = OdType.label, color = OverdriveColors.textTertiary)
            if (!paused && zone != null && snapshot.power != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZoneChip(zone)
                    OdText(
                        "${Zones.name(zone)} · ${UiFormat.percent(percentOfFtp(snapshot.power!!, snapshot.ftp))}",
                        style = OdType.label,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        ValueWithUnit(
            value = snapshot.power?.toString(),
            unit = "W",
            style = OdType.numHero,
            color = if (paused) OverdriveColors.textTertiary else Zones.color(zone),
            unitStyle = heroUnit,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (snapshot.mode == TrainerMode.ERG) {
                val hold = snapshot.heartRateHold
                if (snapshot.stallGuardActive && snapshot.stallGuardWatts != null) {
                    // The cadence guard holds the target down (spec §11.1): the word and what the trainer is held at.
                    SmallStat("Cadence guard", snapshot.stallGuardWatts.toString(), "W", dim)
                } else {
                    SmallStat("Target", snapshot.targetPower?.toString(), "W", dim)
                }
                if (hold != null) {
                    SmallStat("Heart rate target", hold.targetBpm.toString(), "bpm", dim)
                } else {
                    SmallStat("Intensity", UiFormat.percent(snapshot.intensityPercent), null, dim)
                }
            } else if (snapshot.workout != null) {
                SmallStat("Gear", snapshot.gear?.toString(), null, dim)
            }
            if (snapshot.workout != null) SmallStat("Lap average", ui.lapAveragePower?.toString(), "W", dim)
        }
    }
}

@Composable
private fun SmallStat(
    label: String,
    value: String?,
    unit: String?,
    colour: Color,
) {
    Column {
        OdText(label, style = OdType.label, color = OverdriveColors.textTertiary)
        ValueWithUnit(value, unit, OdType.numS, color = colour)
    }
}

/** "12 / 24" with the total in secondary text. */
@Composable
private fun gearText(
    gear: Int?,
    count: Int,
): AnnotatedString =
    remember(gear, count) {
        buildAnnotatedString {
            append(gear?.toString() ?: UiFormat.EMPTY)
            withStyle(
                SpanStyle(color = OverdriveColors.textSecondary, fontWeight = FontWeight.Normal),
            ) { append(" / $count") }
        }
    }

/** Percent of FTP the way the zones are defined: whole percent, halves up. */
fun percentOfFtp(
    watts: Int,
    ftp: Int,
): Int = (100.0 * watts / ftp).roundToInt()

@Composable
private fun LiveTiles(
    ui: RideUi,
    paused: Boolean,
    modifier: Modifier,
) {
    val snapshot = ui.snapshot
    val dim = if (paused) OverdriveColors.textTertiary else OverdriveColors.textPrimary
    val padding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)
    val tile: @Composable (String, String?, String?, Color) -> Unit = { label, value, unit, colour ->
        StatTile(label, value, unit, Modifier.fillMaxSize(), valueColor = colour, padding = padding)
    }
    TileGroup(
        columns = 3,
        modifier = modifier,
        fillHeight = true,
        tiles =
            listOf(
                { tile("Heart rate", snapshot.heartRate?.toString(), "bpm", dim) },
                { tile("Cadence", snapshot.cadence?.toString(), "rpm", dim) },
                { tile("Time", UiFormat.elapsed(snapshot.elapsedSeconds), null, OverdriveColors.textPrimary) },
                { tile("Speed", UiFormat.speed(snapshot.speedKmh), "km/h", dim) },
                { tile("Distance", UiFormat.distanceKm(snapshot.distanceMeters), "km", dim) },
                { tile("Average power", ui.averagePower?.toString(), "W", dim) },
            ),
    )
}

// ---- Workout card ------------------------------------------------------------------------------

@Composable
private fun WorkoutCard(
    ui: RideUi,
    modifier: Modifier,
) {
    val snapshot = ui.snapshot
    val workout = snapshot.workout
    val profile = ui.profile
    OdCard(
        modifier,
        padding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (workout == null || profile == null) {
            // A free ride has no intervals: the card shows the gear, the thing the rider changes (design/system.md §6.2).
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OdText("Gear", style = OdType.label, color = OverdriveColors.textTertiary)
                OdText(gearText(snapshot.gear, snapshot.gearCount), style = OdType.numL, maxLines = 1, softWrap = false)
                OdText(
                    "Gear ${snapshot.neutralGear} rides the flat.",
                    style = OdType.label,
                    color = OverdriveColors.textSecondary,
                )
            }
        } else {
            val next = workout.nextStep
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    OdText("Interval", style = OdType.label, color = OverdriveColors.textTertiary)
                    OdText(
                        rememberTitleWithNote(workout.stepLabel, RideMapping.repeatText(snapshot)),
                        style = OdType.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    OdText("Next", style = OdType.label, color = OverdriveColors.textTertiary)
                    OdText(
                        if (next ==
                            null
                        ) {
                            "Finish"
                        } else {
                            WorkoutDescriber.nextLine(next, snapshot.ftp, snapshot.intensityPercent)
                        },
                        style = OdType.bodyStrong,
                        color = OverdriveColors.textSecondary,
                        maxLines = 1,
                    )
                }
                Column(Modifier.width(156.dp), horizontalAlignment = Alignment.End) {
                    OdText("Left in interval", style = OdType.label, color = OverdriveColors.textTertiary)
                    OdText(
                        UiFormat.elapsed(workout.stepRemainingSeconds),
                        style = OdType.numL,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                    )
                }
            }
            WorkoutProfile(
                data = profile,
                look = ProfileLook.Ride,
                modifier = Modifier.fillMaxWidth().weight(1f),
                nowSeconds = workout.workoutElapsedSeconds,
                trace = ui.trace,
            )
        }
    }
}

// ---- Control bar -------------------------------------------------------------------------------

@Composable
private fun ControlBar(
    snapshot: RideSnapshot,
    paused: Boolean,
    actions: RideActions,
    media: MediaController,
    onFinish: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(96.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(OverdriveColors.border))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (paused) {
                OdButton("Finish ride", onFinish, leadingIcon = OverdriveIcons.Flag, height = 64.dp)
                Box(Modifier.weight(1f))
                OdButton(
                    "Resume",
                    actions::resume,
                    variant = ButtonVariant.Primary,
                    leadingIcon = OverdriveIcons.Play,
                    height = 64.dp,
                    minWidth = 200.dp,
                )
            } else {
                RideControls(snapshot, actions)
                Box(Modifier.weight(1f))
                MediaGroup(media)
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(40.dp).background(OverdriveColors.border))
                OdButton("Pause", actions::pause, leadingIcon = OverdriveIcons.Pause, height = 64.dp)
            }
        }
    }
}

@Composable
private fun RideControls(
    snapshot: RideSnapshot,
    actions: RideActions,
) {
    val erg = snapshot.mode == TrainerMode.ERG

    // In a heart-rate step the two buttons move the target heart rate by 2 bpm (spec §11.2), not the intensity.
    val hold = snapshot.heartRateHold
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (snapshot.workout != null) {
            OdButton("Previous", actions::previousInterval, leadingIcon = OverdriveIcons.SkipBack, height = 64.dp)
        }
        OdIconButton(
            OverdriveIcons.Minus,
            actions::levelDown,
            if (hold != null) {
                "Target heart rate down"
            } else if (erg) {
                "Intensity down"
            } else {
                "Gear down"
            },
            variant = ButtonVariant.Secondary,
            size = 64.dp,
            repeating = true,
        )
        Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OdText(
                when {
                    hold != null -> "${hold.targetBpm} bpm"
                    erg -> UiFormat.percent(snapshot.intensityPercent)
                    else -> UiFormat.count(snapshot.gear)
                },
                style = OdType.numS,
                maxLines = 1,
                softWrap = false,
            )
            OdText(
                when {
                    hold != null -> "Target heart rate"
                    erg -> "Intensity"
                    else -> "Gear"
                },
                style = OdType.caption,
                color = OverdriveColors.textTertiary,
            )
        }
        OdIconButton(
            OverdriveIcons.Plus,
            actions::levelUp,
            if (hold != null) {
                "Target heart rate up"
            } else if (erg) {
                "Intensity up"
            } else {
                "Gear up"
            },
            variant = ButtonVariant.Secondary,
            size = 64.dp,
            repeating = true,
        )
        if (snapshot.workout != null) {
            OdButton("Next", actions::nextInterval, trailingIcon = OverdriveIcons.SkipForward, height = 64.dp)
        }
    }
}

@Composable
private fun MediaGroup(media: MediaController) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            OdIconButton(OverdriveIcons.Play, media::playPause, "Play or pause media", size = 56.dp)
            OdIconButton(OverdriveIcons.Volume1, media::volumeDown, "Volume down", size = 56.dp, repeating = true)
            OdIconButton(OverdriveIcons.Volume2, media::volumeUp, "Volume up", size = 56.dp, repeating = true)
        }
        OdText("Media", style = OdType.caption, color = OverdriveColors.textTertiary)
    }
}
