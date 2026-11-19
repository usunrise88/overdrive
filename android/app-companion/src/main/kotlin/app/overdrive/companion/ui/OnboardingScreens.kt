package app.overdrive.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.model.HealthWords
import app.overdrive.companion.model.LostUi
import app.overdrive.companion.model.PermissionRowUi
import app.overdrive.companion.settings.OnboardingStep
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthStatus
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.ProgressBar
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import kotlinx.coroutines.launch

private const val BACKGROUND_PARAGRAPH =
    "Once a day, around 06:00, Overdrive reads new watch data so this morning's figures and note " +
        "are ready when you wake. Xiaomi phones stop this unless both settings below are on."

private const val UNAVAILABLE_PARAGRAPH =
    "Overdrive reads your resting heart rate, sleep and the other signals your watch records from Health Connect."

/** What step 2 says before the dialog: what is read, where it goes, and that nothing is written (design/companion.md §4.10). */
fun asksParagraph(host: String): String {
    val to = host.ifEmpty { "your service" }
    return "Overdrive reads these from Health Connect and sends them only to $to. It never writes to Health Connect."
}

/** The steps of the first run that are numbered: connect, Health Connect, background reading (design/companion.md §4.10). */
const val NUMBERED_STEPS = 3

/**
 * The frame of a first-run step (design/companion.md §4.10): full screen without tabs, padding 24 × 20, the step label, the title,
 * one paragraph, the content, and the buttons at the bottom, full width, the main one over a ghost one. No illustration.
 */
@Composable
private fun StepFrame(
    step: Int?,
    title: String,
    paragraph: String?,
    modifier: Modifier = Modifier,
    buttons: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (step !=
                null
            ) {
                OdText("Step $step of $NUMBERED_STEPS", style = OdType.label, color = OverdriveColors.textTertiary)
            }
            OdText(title, Modifier.padding(top = 8.dp), style = OdType.title)
            if (paragraph != null) OdText(paragraph, style = OdType.body, color = OverdriveColors.textSecondary)
            content()
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { buttons() }
    }
}

// region step 2: Health Connect

/** What the permission step shows (design/companion.md §4.10, step 2). */
data class HealthStepState(
    val host: String,
    val rows: List<PermissionRowUi>,
    /** The rider has answered the system dialog. */
    val asked: Boolean,
    val sentence: String?,
    val lost: LostUi?,
    val unavailable: LostUi?,
    /** "Allow sleep": asks again once; null when nothing is off. */
    val askAgain: String?,
)

/** Step 2 for a given state: the list of what is asked and why, or, after the dialog, what was allowed and what is lost. */
@Composable
fun HealthStepView(
    state: HealthStepState,
    onContinue: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unavailable = state.unavailable
    StepFrame(
        step = 2,
        title = "Read your watch data",
        paragraph =
            when {
                unavailable != null -> UNAVAILABLE_PARAGRAPH
                state.asked -> state.sentence
                else -> asksParagraph(state.host)
            },
        modifier = modifier,
        buttons = {
            when {
                unavailable != null -> {
                    OdButton("Continue", onContinue, Modifier.fillMaxWidth(), variant = ButtonVariant.Primary)
                    OdButton("Check again", onSecondary, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
                }

                state.asked -> {
                    OdButton("Continue", onContinue, Modifier.fillMaxWidth(), variant = ButtonVariant.Primary)
                    state.askAgain?.let {
                        OdButton(
                            it,
                            onSecondary,
                            Modifier.fillMaxWidth(),
                            variant = ButtonVariant.Ghost,
                        )
                    }
                }

                else -> {
                    OdButton("Continue", onContinue, Modifier.fillMaxWidth(), variant = ButtonVariant.Primary)
                    OdButton("Not now", onSecondary, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
                }
            }
        },
    ) {
        if (unavailable != null) {
            Notice(OverdriveIcons.Info, unavailable.title, unavailable.text, Modifier.padding(top = 8.dp))
        } else {
            HorizontalRule(Modifier.padding(top = 8.dp))
            for (row in state.rows) {
                PermissionRow(row, state.asked)
                HorizontalRule()
            }
            state.lost?.let { Notice(OverdriveIcons.Info, it.title, it.text, Modifier.padding(top = 16.dp)) }
        }
    }
}

@Composable
private fun PermissionRow(
    row: PermissionRowUi,
    asked: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (asked) 48.dp else 64.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(row.name + if (row.optional && !asked) " · optional" else "", style = OdType.bodyStrong)
            if (!asked) OdText(row.reason, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        if (asked) {
            val allowed = row.allowed == true
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OdIcon(
                    if (allowed) OverdriveIcons.Check else OverdriveIcons.TriangleAlert,
                    size = InlineIconSize,
                    tint = if (allowed) OverdriveColors.textSecondary else OverdriveColors.textPrimary,
                )
                OdText(
                    if (allowed) "Allowed" else "Off",
                    style = OdType.label,
                    color = if (allowed) OverdriveColors.textSecondary else OverdriveColors.textPrimary,
                )
            }
        }
    }
}

/** Opens the system dialog for the watch permissions (one request), and tells the container what the rider answered. */
@Composable
fun rememberHealthAccessRequest(container: CompanionContainer): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            container.healthAccessChanged()
        }
    return remember(container, launcher) {
        {
            scope.launch {
                val permissions = container.permissionsToRequest()
                if (permissions.isEmpty()) container.refreshHealthAccess() else launcher.launch(permissions)
            }
        }
    }
}

/** Step 2 on the phone: asks once for everything, shows what was allowed, asks again once for what is off, then Health Connect's own screen. */
@Composable
fun HealthStep(
    container: CompanionContainer,
    host: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status by container.healthStatus.collectAsStateWithLifecycle()
    var asked by remember { mutableStateOf(false) }
    var asks by remember { mutableIntStateOf(0) }
    val request = rememberHealthAccessRequest(container)
    LifecycleResumeEffect(Unit) {
        container.refreshHealthAccess()
        onPauseOrDispose {}
    }
    val access = status.access
    val everything =
        status.checked && status.availability == HealthAvailability.AVAILABLE &&
            access.granted.size == HealthDataType.entries.size
    // Everything allowed: nothing to explain, on to the next step.
    LaunchedEffect(everything, asked) {
        if (everything && asked) container.setOnboarding(OnboardingStep.Background)
    }
    HealthStepView(
        state = healthStepState(status, host, asked),
        onContinue = {
            when {
                status.availability != HealthAvailability.AVAILABLE -> {
                    next(container, status)
                }

                asked -> {
                    next(container, status)
                }

                else -> {
                    asked = true
                    asks++
                    request()
                }
            }
        },
        onSecondary = {
            when {
                status.availability != HealthAvailability.AVAILABLE -> {
                    container.refreshHealthAccess()
                }

                asked -> {
                    if (asks < 2) {
                        asks++
                        request()
                    } else {
                        openHealthConnect(context)
                    }
                }

                else -> {
                    next(container, status.copy(access = access.copy(granted = emptySet())))
                }
            }
        },
        modifier = modifier,
    )
}

private fun next(
    container: CompanionContainer,
    status: HealthStatus,
) {
    // Nothing allowed: there is nothing to read in the background and nothing for a first read to do.
    container.setOnboarding(if (status.access.granted.isEmpty()) OnboardingStep.Done else OnboardingStep.Background)
}

/** The state of step 2 from what Health Connect says; pure, so every wording is a unit test. */
fun healthStepState(
    status: HealthStatus,
    host: String,
    asked: Boolean,
): HealthStepState {
    val access = status.access
    val off = HealthWords.firstOff(access)
    return HealthStepState(
        host = host,
        rows = HealthWords.rows(access, asked),
        asked = asked,
        sentence = if (asked) HealthWords.allowedSentence(access) else null,
        lost = if (asked) HealthWords.lost(access) else null,
        unavailable = HealthWords.unavailable(status.availability),
        askAgain = off?.let { "Allow ${it.label.lowercase()}" },
    )
}

// region step 3: background

/** What the background step shows: the two settings of Xiaomi phones and their state (design/companion.md §4.10, step 3). */
data class BackgroundStepState(
    /** The phone's battery saving leaves Overdrive alone. */
    val batteryUnrestricted: Boolean,
    /** Health Connect's own background permission is missing where the platform has one. */
    val healthConnectOff: Boolean,
)

@Composable
fun BackgroundStepView(
    state: BackgroundStepState,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepFrame(
        step = 3,
        title = "Read in the background",
        paragraph = BACKGROUND_PARAGRAPH,
        modifier = modifier,
        buttons = {
            OdButton(
                if (state.batteryUnrestricted && !state.healthConnectOff) "Continue" else "Open settings",
                if (state.batteryUnrestricted && !state.healthConnectOff) onSkip else onOpenSettings,
                Modifier.fillMaxWidth(),
                variant = ButtonVariant.Primary,
            )
            OdButton(
                if (state.batteryUnrestricted && !state.healthConnectOff) "Open settings" else "Skip",
                if (state.batteryUnrestricted && !state.healthConnectOff) onOpenSettings else onSkip,
                Modifier.fillMaxWidth(),
                variant = ButtonVariant.Ghost,
            )
        },
    ) {
        HorizontalRule(Modifier.padding(top = 8.dp))
        SettingState(
            "No battery restrictions",
            "Battery saver · Overdrive · No restrictions",
            if (state.batteryUnrestricted) true else false,
        )
        HorizontalRule()
        SettingState("Autostart", "Apps · Autostart · Overdrive", null)
        if (state.healthConnectOff) {
            HorizontalRule()
            SettingState(
                "Background access in Health Connect",
                "Health Connect · Overdrive · Allow background access",
                false,
            )
        }
        HorizontalRule()
    }
}

/** A row with a state at the right: **On** with a check, **Off** with a warning, or **Not checked** for what the phone does not let an app read. */
@Composable
private fun SettingState(
    title: String,
    path: String,
    on: Boolean?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(path, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (on) {
                true -> {
                    OdIcon(OverdriveIcons.Check, size = InlineIconSize, tint = OverdriveColors.textSecondary)
                    OdText("On", style = OdType.label, color = OverdriveColors.textSecondary)
                }

                false -> {
                    OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize)
                    OdText("Off", style = OdType.label)
                }

                null -> {
                    OdText("Not checked", style = OdType.label, color = OverdriveColors.textTertiary)
                }
            }
        }
    }
}

@Composable
fun BackgroundStep(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status by container.healthStatus.collectAsStateWithLifecycle()
    var battery by remember { mutableStateOf(batteryUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        battery = batteryUnrestricted(context)
        container.refreshHealthAccess()
        onPauseOrDispose {}
    }
    val healthConnectOff = !status.access.backgroundOk
    BackgroundStepView(
        BackgroundStepState(battery, healthConnectOff),
        onOpenSettings = {
            when {
                healthConnectOff -> openHealthConnect(context)
                !battery -> openBatterySettings(context)
                else -> openAutostart(context)
            }
        },
        onSkip = { container.setOnboarding(OnboardingStep.Reading) },
        modifier = modifier,
    )
}

// region step 4: the first read

/** The first read of the last 60 days (design/companion.md §4.10, step 4): a bar, the count, and the promise that it goes on. */
@Composable
fun ReadingStepView(
    fraction: Float,
    progressText: String,
    problem: String?,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepFrame(
        step = null,
        title = "Reading the last 60 days",
        paragraph = "Your watch data goes to the service once. After that only new records are sent.",
        modifier = modifier,
        buttons = {
            OdText(
                "You can leave the app; reading continues.",
                style = OdType.label,
                color = OverdriveColors.textSecondary,
            )
            OdButton("Go to Today", onToday, Modifier.fillMaxWidth(), variant = ButtonVariant.Ghost)
        },
    ) {
        Box(Modifier.padding(top = 8.dp)) { ProgressBar(fraction) }
        OdText(progressText, style = OdType.labelNum, color = OverdriveColors.textSecondary)
        if (problem != null) OdText(problem, Modifier.padding(top = 8.dp), style = OdType.label)
    }
}

@Composable
fun ReadingStep(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val status by container.healthStatus.collectAsStateWithLifecycle()
    // Today opens when the read ends, or when the rider leaves; the read goes on without the screen.
    LaunchedEffect(status.firstReadDone, status.running, status.progress) {
        if (status.firstReadDone && status.progress == null &&
            !status.running
        ) {
            container.setOnboarding(OnboardingStep.Done)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { container.setOnboarding(OnboardingStep.Done) }
    ReadingStepView(
        fraction = HealthWords.fraction(status.progress),
        progressText = HealthWords.progress(status.progress),
        problem = HealthWords.problemOf(status),
        onToday = { container.setOnboarding(OnboardingStep.Done) },
        modifier = modifier,
    )
}
