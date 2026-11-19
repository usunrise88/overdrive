package app.overdrive.ride.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.core.ride.RideState
import app.overdrive.ride.AppContainer
import app.overdrive.ride.R
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideMapping
import app.overdrive.ride.overlay.OverlayController
import app.overdrive.ride.overlay.OverlayPolicy
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.profile.ProfileUi
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.ride.settings.SimulatedClick
import app.overdrive.ride.workouts.WorkoutFileError
import app.overdrive.ride.workouts.WorkoutFileReader
import app.overdrive.ride.workouts.WorkoutFileResult
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.IntroHost
import app.overdrive.uikit.LocalMotionEnabled
import app.overdrive.uikit.overlay.OverlayContent
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.tokens.OverdriveMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The screens of the app. A small state, no navigation library (ADR 0005). */
enum class Screen {
    Ride,
    Devices,
    Settings,

    /** **Learn buttons**, entered from Devices while the flow runs (ADR 0012). */
    Learn,
}

private enum class Page {
    Ready,
    Riding,
    Finished,
    Devices,
    Settings,
    Learn,
}

/**
 * The ride app: [RideRoot], and over it the launch film when [playIntro] is true (cold start, spec §4.6, ADR 0031). The film's
 * layer covers the whole window, so [modifier] (the insets and the background) reaches the root only, not the film. The root is
 * composed from the first frame under the film: the app's own start-up does not wait for it.
 */
@Composable
fun RideApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    overlay: OverlayController = container.overlay,
    playIntro: Boolean = false,
) {
    IntroHost(
        play = playIntro,
        rawRes = R.raw.overdrive_intro,
        fadeMillis = INTRO_FADE_MS,
        continueLabel = "Continue",
    ) {
        RideRoot(container, modifier, overlay)
    }
}

/** How long the film's layer takes to fade out into the app (design/system.md §9). */
private const val INTRO_FADE_MS = 300

/**
 * The root of the ride app: the current screen with a 200 ms crossfade, the system Back button, and the in-app preview
 * of the overlay. Reads the ride from the [AppContainer]'s host and writes settings through it; everything below is
 * stateless screens that take plain values and callbacks.
 */
@Composable
private fun RideRoot(
    container: AppContainer,
    modifier: Modifier = Modifier,
    overlay: OverlayController = container.overlay,
) {
    val host = container.host
    val ui by host.ui.collectAsStateWithLifecycle()
    val devices by host.devices.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    // What the rider rides with: the tablet's settings with the profile from the service on top (ADR 0021). The Ready screen
    // and the Settings rows show this; `settings` stays what is stored on the tablet.
    val rider by container.effectiveSettings.collectAsStateWithLifecycle()
    val profileState by container.profile.state.collectAsStateWithLifecycle()
    val workouts by container.library.workouts.collectAsStateWithLifecycle()
    val choice by host.choice.collectAsStateWithLifecycle()
    val recovery by container.rides.recovery.collectAsStateWithLifecycle()
    val lastRide by container.rides.lastSaved.collectAsStateWithLifecycle()
    val rideFiles = rememberRideFiles(container.rides)
    val pairing by container.pairing.state.collectAsStateWithLifecycle()
    val learn by container.learning.ui.collectAsStateWithLifecycle()
    val planState by container.plan.state.collectAsStateWithLifecycle()
    val ridden by container.rides.ridden.collectAsStateWithLifecycle()
    val syncStatus by container.sync.status.collectAsStateWithLifecycle()
    val syncReport by container.sync.report.collectAsStateWithLifecycle()
    // One plain notice with one action for Bluetooth that cannot be used, re-read on every resume (ADR 0010).
    val bluetooth = rememberBluetoothFix(devices.radio.takeIf { !devices.simulated }, container.bluetooth)

    var screen by rememberSaveable { mutableStateOf(Screen.Ride) }
    var overlayShown by rememberSaveable { mutableStateOf(false) }
    var overlayDetailed by rememberSaveable { mutableStateOf(settings.overlayMode == OverlayStartMode.Detailed) }
    var openError by remember { mutableStateOf<WorkoutFileError?>(null) }

    // The Learn buttons screen follows the flow: it opens when learning starts and closes when it ends (cancelled, done,
    // or a ride went live), and a screen restored after the process died with no flow running is not stranded on it.
    LaunchedEffect(learn != null) {
        if (learn != null) {
            screen = Screen.Learn
        } else if (screen == Screen.Learn) {
            screen = Screen.Devices
        }
    }

    val state = ui.snapshot.state
    val live = OverlayPolicy.isLive(state)
    KeepScreenOn(live)
    BackHandler(enabled = screen != Screen.Ride) {
        when (screen) {
            Screen.Learn -> container.learning.cancel()
            else -> screen = Screen.Ride
        }
    }

    val access = rememberOverlayAccess(overlay)
    EnsureOverlayService(live, overlay)
    // Android 13 and later ask for notifications at runtime. The answer does not matter to the overlay, so the system
    // screen for the overlay permission opens either way, right after the question.
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { overlay.openOverlaySettings() }
    val allowOverlay: () -> Unit = {
        when (allowStep(access)) {
            AllowStep.AskNotifications -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            AllowStep.OpenOverlaySettings -> overlay.openOverlaySettings()
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    when (
                        val result =
                            withContext(
                                Dispatchers.IO,
                            ) { WorkoutFileReader.read(context.contentResolver, uri) }
                    ) {
                        is WorkoutFileResult.Loaded -> {
                            openError = null
                            container.library.add(result.workout)
                            host.select(RideChoice.Planned(result.workout))
                        }

                        is WorkoutFileResult.Failed -> {
                            openError = result.error
                        }
                    }
                }
            }
        }

    val topBar =
        TopBarActions(
            onDevices = { screen = Screen.Devices },
            onOverlay = { overlayShown = !overlayShown },
            onSettings = { screen = Screen.Settings },
            overlayShown = overlayShown,
        )
    val page =
        when (screen) {
            Screen.Devices -> {
                Page.Devices
            }

            Screen.Settings -> {
                Page.Settings
            }

            Screen.Learn -> {
                Page.Learn
            }

            Screen.Ride -> {
                when (state) {
                    RideState.IDLE, RideState.READY -> Page.Ready
                    RideState.RIDING, RideState.PAUSED -> Page.Riding
                    RideState.FINISHED -> Page.Finished
                }
            }
        }
    val motion = LocalMotionEnabled.current

    Box(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (motion) {
                    ContentTransform(
                        fadeIn(tween(OverdriveMotion.durationStandard, easing = OverdriveMotion.easingStandard)),
                        fadeOut(tween(OverdriveMotion.durationStandard, easing = OverdriveMotion.easingStandard)),
                    )
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "screen",
        ) { target ->
            when (target) {
                Page.Ready -> {
                    // The Ready screen coming on screen asks for the plan (the repository holds it to once a minute), and the
                    // list is made again from the clock whenever the plan or the rides change, never more often.
                    LaunchedEffect(Unit) { container.planScreenOpened() }
                    val planUi =
                        remember(planState, ridden) {
                            PlanUi.of(planState, ridden, LocalDate.now().toString(), System.currentTimeMillis())
                        }
                    ReadyScreen(
                        choice = choice,
                        workouts = workouts,
                        settings = rider,
                        devices = devices,
                        startEnabled = state == RideState.READY,
                        openError = openError,
                        topBar = topBar,
                        actions =
                            ReadyActions(
                                onSelect = {
                                    openError = null
                                    host.select(it)
                                },
                                onStart = host.actions::start,
                                onOpenFile = { picker.launch(arrayOf("*/*")) },
                                onDismissError = { openError = null },
                                onAllowOverlay = allowOverlay,
                                onRecoverySave = { container.rides.saveRecovered(it.id) },
                                onRecoveryDiscard = { container.rides.discardRecovered(it.id) },
                                onOpenDevices = { screen = Screen.Devices },
                                onRefreshPlan = container::refreshPlan,
                            ),
                        plan = planUi,
                        overlayAccess = access,
                        recovery = recovery,
                        lastRide = lastRide,
                        files = rideFiles.actions,
                        fileMessage = rideFiles.message?.takeIf { it.rideId == lastRide?.id }?.text,
                        bluetooth = bluetooth,
                    )
                }

                Page.Riding -> {
                    RidingScreen(ui, devices, topBar, host.actions, container.media)
                }

                Page.Finished -> {
                    val saved = (ui.finished?.save as? SaveState.Saved)?.ride
                    FinishedScreen(
                        ui.finished,
                        devices,
                        topBar,
                        onDone = host::reset,
                        files = rideFiles.actions,
                        fileMessage = rideFiles.message?.takeIf { it.rideId == saved?.id }?.text,
                        onRetrySave = host::retrySave,
                    )
                }

                Page.Devices -> {
                    // A scan runs only while this screen is on: leaving it ends the scan.
                    DisposableEffect(container) { onDispose { container.pairing.stop() } }
                    DevicesScreen(
                        devices = devices,
                        actions =
                            DevicesActions(
                                onBack = { screen = Screen.Ride },
                                onRetryTrainer = host::retryTrainer,
                                onRetryHeartRate = host::retryHeartRate,
                                // With simulated devices the Click's buttons are the simulated Click's; otherwise **Pair**
                                // is a scan (the screen routes it) and **Check again** starts the real Click over.
                                onPairClick = {
                                    container.updateSettings {
                                        it.copy(
                                            simulatedClick = SimulatedClick.Connected,
                                        )
                                    }
                                },
                                onCheckClick = {
                                    if (devices.simulated) {
                                        container.updateSettings { it.copy(simulatedClick = SimulatedClick.Connected) }
                                    } else {
                                        container.retryClick()
                                    }
                                },
                                onScan = container.pairing::scan,
                                onStopScan = container.pairing::stop,
                                onPick = container.pairing::pair,
                                onForgetTrainer = { container.pairing.forget(DeviceRole.Trainer) },
                                onForgetHeartRate = { container.pairing.forget(DeviceRole.Strap) },
                                onRetryClick = container::retryClick,
                                onForgetClick = { container.pairing.forget(DeviceRole.Click) },
                                onLearnButtons = container.learning::begin,
                                onResetButtons = container.learning::resetToDefault,
                            ),
                        pairing = pairing,
                        bluetooth = bluetooth,
                        mapping = settings.buttonMapping,
                    )
                }

                Page.Learn -> {
                    // The flow is the container's; this screen only shows it. Leaving by any route cancels it.
                    learn?.let { flow ->
                        LearnScreen(
                            ui = flow,
                            actions =
                                LearnActions(
                                    onCancel = container.learning::cancel,
                                    onReset = container.learning::resetToDefault,
                                ),
                            clickReady = devices.click.status == DeviceStatus.Connected,
                        )
                    }
                }

                Page.Settings -> {
                    val bleLogs = rememberBleLogFiles(container.bleLogs)
                    val profileUi = remember(profileState) { ProfileUi.of(profileState) }
                    SettingsScreen(
                        settings = rider,
                        actions =
                            SettingsActions(
                                onBack = { screen = Screen.Ride },
                                onChange = container::updateSettings,
                                onRiderChange = container::changeRider,
                                onResetOverlayPosition = container::resetOverlayPosition,
                                onOpenOverlaySettings = overlay::openOverlaySettings,
                                onOpenNotificationSettings = overlay::openNotificationSettings,
                                onSendNow = container::sendNow,
                            ),
                        overlayAccess = access,
                        bleLogs = bleLogs,
                        sync = SyncUi.of(syncStatus, syncReport, System.currentTimeMillis()),
                        profile = profileUi,
                    )
                }
            }
        }
        if (overlayShown && screen == Screen.Ride) {
            OverlayPreview(
                state = RideMapping.overlayState(ui.snapshot, ui.profile),
                settings =
                    OverlaySettings(
                        settings.overlayOpacityPercent / 100f,
                        if (overlayDetailed) OverlayMode.Detailed else OverlayMode.Compact,
                    ),
                onToggleMode = { overlayDetailed = !overlayDetailed },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 16.dp),
            )
        }
    }
}

/**
 * The overlay drawn inside the app, top right under the top bar: tap it to switch compact and detailed, tap the overlay
 * button again to hide it. It is the same composable the overlay window of `OverlayService` hosts, fed by the same
 * ride, and it works without the "Display over other apps" permission. Dragging and snapping belong to the window.
 */
@Composable
private fun OverlayPreview(
    state: app.overdrive.uikit.overlay.OverlayUiState,
    settings: OverlaySettings,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteractionSource()
    Box(modifier.plainClickable(source, onClick = onToggleMode)) { OverlayContent(state, settings) }
}

/** Keeps the screen on while a ride is under way, and only then. */
@Composable
private fun KeepScreenOn(keepOn: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
