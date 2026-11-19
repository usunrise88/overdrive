package app.overdrive.companion.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.model.AccessAction
import app.overdrive.companion.model.BackgroundFix
import app.overdrive.companion.model.FitWords
import app.overdrive.companion.model.HealthSettingsUi
import app.overdrive.companion.model.HealthWords
import app.overdrive.companion.model.HeartRateRowUi
import app.overdrive.companion.model.HeartRateUi
import app.overdrive.companion.model.HeartRateWords
import app.overdrive.companion.model.ProblemWords
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.remote.ProblemKind
import app.overdrive.data.settings.AddressCheck
import app.overdrive.data.settings.ServiceRules
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTextField
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.Stepper
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.surface
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/** What the Settings screen shows (design/companion.md §4.9): the fields as typed, and the words of the last exchange. */
data class SettingsUiState(
    val address: String,
    /** The mistake in the typed address (plain words), or null. */
    val addressMistake: String?,
    /** The typed address is a plain `http://` one: "Not encrypted". */
    val notEncrypted: Boolean,
    val token: String,
    /** The service refused the token: an alert and the sentence under the field. */
    val tokenRefused: Boolean,
    val lastSyncValue: String,
    val lastSyncNote: String,
    val syncing: Boolean,
    val version: String,
    /** The Health Connect group: permission state, the last read, what was sent, the background reading. */
    val health: HealthSettingsUi? = null,
    /** The Heart rate group, or null while the service has sent no profile (nothing to edit yet). */
    val heartRate: HeartRateUi? = null,
    /** The outdoor rides row (design/companion.md §4.9, §4.11): "2 files waiting" and why, or how to add a ride. */
    val outdoorValue: String = "No files waiting",
    val outdoorNote: String = "Share a .fit file from OnelapFit to Overdrive to add an outdoor ride.",
)

class SettingsActions(
    val onBack: () -> Unit,
    val onAddress: (String) -> Unit,
    val onToken: (String) -> Unit,
    val onSyncNow: () -> Unit,
    val onLicences: () -> Unit,
    /** The coach notebook, read-only (design/companion.md §4.13). */
    val onNotebook: () -> Unit = {},
    /** **Manage**, or **Allow** while nothing is allowed. */
    val onAccess: (AccessAction) -> Unit = {},
    val onBackgroundFix: (BackgroundFix) -> Unit = {},
    val onMaxStep: (Int) -> Unit = {},
    val onThresholdStep: (Int) -> Unit = {},
    val onUseMaxEstimate: (Int) -> Unit = {},
    val onUseThresholdEstimate: (Int) -> Unit = {},
)

/** The words of Settings' "Last sync" row (design/companion.md §4.9), pure so they are tested. */
object SyncWords {
    fun lastSync(
        fetchedAtMillis: Long?,
        words: app.overdrive.companion.model.Words,
    ): String = fetchedAtMillis?.let(words::lastSync) ?: "Never"

    /**
     * The note under "Last sync": what was sent and received, or, after a failure, what is waiting ("Nothing sent since then. 312 watch
     * records are waiting.") and why. [watch] is the watch data's state, which has its own upload next to the service's answers.
     */
    fun note(
        fetchedAtMillis: Long?,
        problem: app.overdrive.core.remote.ServiceProblem?,
        waiting: Int,
        host: String,
        watch: HealthStatus = HealthStatus(),
    ): String {
        val waitingText =
            when (waiting) {
                0 -> null
                1 -> "1 answer is waiting to be sent."
                else -> "$waiting answers are waiting to be sent."
            }
        val watchProblem = watch.uploadProblem
        val watchWaiting = HealthWords.waiting(watch)
        return when {
            problem != null -> {
                listOfNotNull(
                    "The last attempt failed. ${ProblemWords.causeOf(problem, host)}",
                    waitingText,
                    watchWaiting,
                ).joinToString(" ")
            }

            watchProblem != null || watchWaiting != null -> {
                listOfNotNull(
                    "Nothing sent since then.".takeIf { watch.uploadProblem != null },
                    watchWaiting,
                    watchProblem?.let { ProblemWords.causeOf(it, host) },
                    waitingText,
                ).joinToString(" ")
            }

            watch.lastSent != null -> {
                val sent = watch.lastSent!!.total
                listOfNotNull(
                    "Sent ${HealthWords.count(
                        sent,
                    )} watch ${if (sent == 1) "record" else "records"} · received your latest figures",
                    waitingText,
                ).joinToString(". ").let { if (it.endsWith(".")) it else "$it." }
            }

            fetchedAtMillis == null -> {
                "Nothing received yet."
            }

            else -> {
                listOfNotNull("Received your latest figures.", waitingText).joinToString(" ")
            }
        }
    }
}

/** Settings (design/companion.md §4.9) from the container. */
@Composable
fun SettingsScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val overview by container.syncOverview.collectAsStateWithLifecycle()
    val watch by container.healthStatus.collectAsStateWithLifecycle()
    val fits by container.fitStatus.collectAsStateWithLifecycle()
    val profile by container.profile.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(settings?.serviceUrl.orEmpty()) }
    var token by remember { mutableStateOf(settings?.token?.value.orEmpty()) }
    var battery by remember { mutableStateOf(batteryUnrestricted(context)) }
    // Coming back from Health Connect's or the phone's own settings: what is allowed now.
    LifecycleResumeEffect(Unit) {
        battery = batteryUnrestricted(context)
        container.refreshHealthAccess()
        onPauseOrDispose {}
    }
    val request = rememberHealthAccessRequest(container)
    val check = ServiceRules.checkAddress(address)
    val host = hostOf(settings?.serviceUrl.orEmpty())
    val lastMillis = listOfNotNull(overview.lastFetchedMillis, watch.lastSentMillis).maxOrNull()
    val state =
        SettingsUiState(
            address = address,
            addressMistake = (check as? AddressCheck.Invalid)?.reason,
            notEncrypted = check is AddressCheck.Valid && !check.isEncrypted,
            token = token,
            tokenRefused =
                overview.problem?.kind == ProblemKind.TOKEN_REJECTED ||
                    watch.uploadProblem?.kind == ProblemKind.TOKEN_REJECTED,
            lastSyncValue = SyncWords.lastSync(lastMillis, container.words),
            lastSyncNote = SyncWords.note(overview.lastFetchedMillis, overview.problem, overview.waiting, host, watch),
            syncing = overview.syncing || watch.running,
            version = versionOf(context),
            health = HealthWords.settings(watch, battery, container.words),
            heartRate = profile.profile?.let(HeartRateWords::of),
            outdoorValue = FitWords.settingsValue(fits),
            outdoorNote = FitWords.settingsNote(fits, host),
        )
    SettingsView(
        state,
        SettingsActions(
            onBack = { container.nav.back() },
            onAddress = {
                address = it
                // Only a valid address is stored; one that is not keeps what was typed and says why (design/companion.md §4.9).
                when (val result = ServiceRules.checkAddress(it)) {
                    AddressCheck.Empty -> container.updateService(url = "")
                    is AddressCheck.Valid -> container.updateService(url = result.url)
                    is AddressCheck.Invalid -> Unit
                }
            },
            onToken = {
                token = it
                container.updateService(token = ServiceRules.cleanToken(it))
            },
            onSyncNow = container::syncNow,
            onLicences = { container.nav.push(Route.Licences) },
            onNotebook = { container.nav.push(Route.Notebook) },
            onAccess = { action -> if (action == AccessAction.Allow) request() else openHealthConnect(context) },
            onBackgroundFix = { fix ->
                when (fix) {
                    BackgroundFix.BatterySaving -> openBatterySettings(context)
                    BackgroundFix.HealthConnect -> openHealthConnect(context)
                }
            },
            onMaxStep = { by ->
                profile.profile?.let { p -> HeartRateWords.stepMax(p, by)?.let(container::setMaxHeartRate) }
            },
            onThresholdStep = { by ->
                profile.profile?.let { p ->
                    HeartRateWords.stepThreshold(p, by)?.let(container::setThresholdHeartRate)
                }
            },
            onUseMaxEstimate = container::setMaxHeartRate,
            onUseThresholdEstimate = container::setThresholdHeartRate,
        ),
        modifier,
    )
}

private fun versionOf(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        ""
    }

/** The Settings screen for a given [state]. */
@Composable
fun SettingsView(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    tokenShown: Boolean = false,
) {
    var shown by remember { mutableStateOf(tokenShown) }
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Settings", actions.onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GroupLabel("Service") }
            item {
                SettingsCard {
                    FieldRow("Service address", "Where your rides and watch data are kept") {
                        OdTextField(
                            state.address,
                            actions.onAddress,
                            "Service address",
                            Modifier.fillMaxWidth(),
                            placeholder = "overdrive.example.net",
                            keyboardType = KeyboardType.Uri,
                        )
                        state.addressMistake?.let { Message(it, alert = false) }
                        if (state.notEncrypted) Message("Not encrypted: the token is sent as plain text", alert = true)
                    }
                    HorizontalRule()
                    FieldRow("Token", "Sent only to this address") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OdTextField(state.token, actions.onToken, "Token", Modifier.weight(1f), secret = !shown)
                            OdButton(
                                if (shown) "Hide" else "Show",
                                { shown = !shown },
                                variant = ButtonVariant.Ghost,
                                height = 48.dp,
                            )
                        }
                        if (state.tokenRefused) Message("The service did not accept this token (401).", alert = true)
                    }
                    HorizontalRule()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                OdText("Last sync", style = OdType.bodyStrong)
                                OdText(state.lastSyncNote, style = OdType.label, color = OverdriveColors.textSecondary)
                            }
                            OdText(state.lastSyncValue, style = OdType.numXS, maxLines = 1, softWrap = false)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OdButton(
                                if (state.syncing) "Syncing…" else "Sync now",
                                actions.onSyncNow,
                                height = 40.dp,
                                leadingIcon = if (state.syncing) null else OverdriveIcons.RefreshCw,
                                enabled = !state.syncing,
                            )
                            if (state.syncing) Spinner(diameter = 16.dp)
                        }
                    }
                }
            }
            state.health?.let { health ->
                item { GroupLabel("Health Connect") }
                val unavailable = health.unavailable
                if (unavailable != null) {
                    item { Notice(OverdriveIcons.Info, unavailable.title, unavailable.text) }
                } else {
                    item { HealthGroup(health, actions) }
                }
            }
            state.heartRate?.let { heartRate ->
                item { GroupLabel("Heart rate") }
                item { HeartRateGroup(heartRate, actions) }
            }
            item { GroupLabel("Outdoor rides") }
            item {
                SettingsCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            OdText("FIT files from OnelapFit", style = OdType.bodyStrong)
                            OdText(state.outdoorNote, style = OdType.label, color = OverdriveColors.textSecondary)
                        }
                        OdText(state.outdoorValue, style = OdType.numXS, maxLines = 2)
                    }
                }
            }
            item { GroupLabel("Coach") }
            item {
                SettingsCard {
                    val source = rememberInteractionSource()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClickable(source, role = Role.Button, onClick = actions.onNotebook)
                            .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            OdText("Coach notebook", style = OdType.bodyStrong)
                            OdText(
                                "What the coach keeps about you. Read-only.",
                                style = OdType.label,
                                color = OverdriveColors.textSecondary,
                            )
                        }
                        OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textSecondary)
                    }
                }
            }
            item { GroupLabel("About") }
            item {
                SettingsCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = OverdriveDimens.sizeListRowTwoLine,
                            ).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            OdText("Version", style = OdType.bodyStrong)
                            OdText("Overdrive companion", style = OdType.label, color = OverdriveColors.textSecondary)
                        }
                        OdText(state.version, style = OdType.numXS)
                    }
                    HorizontalRule()
                    val source = rememberInteractionSource()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .plainClickable(source, role = Role.Button, onClick = actions.onLicences)
                            .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            OdText("Licences", style = OdType.bodyStrong)
                            OdText(
                                "Geist (SIL OFL 1.1), Lucide (ISC)",
                                style = OdType.label,
                                color = OverdriveColors.textSecondary,
                            )
                        }
                        OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textSecondary)
                    }
                }
            }
        }
    }
}

/** The Health Connect card (design/companion.md §4.9): data access, last read, and background reading. */
@Composable
private fun HealthGroup(
    health: HealthSettingsUi,
    actions: SettingsActions,
) {
    SettingsCard {
        ActionRow("Data access", health.dataAccess, health.accessAction.label) { actions.onAccess(health.accessAction) }
        HorizontalRule()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    OdText("Last read", style = OdType.bodyStrong)
                    OdText(health.lastReadNote, style = OdType.label, color = OverdriveColors.textSecondary)
                }
                OdText(health.lastRead, style = OdType.numXS, maxLines = 1, softWrap = false)
            }
            health.sentByType?.let {
                OdText("Sent: $it", style = OdType.label, color = OverdriveColors.textSecondary)
            }
            health.waiting?.let { OdText(it, style = OdType.label, color = OverdriveColors.textPrimary) }
            health.readProblem?.let { Message(it, alert = true) }
        }
        HorizontalRule()
        val background = health.background
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = OverdriveDimens.sizeListRowTwoLine,
                ).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                OdText("Background reading", style = OdType.bodyStrong)
                OdText(
                    background.text,
                    style = OdType.label,
                    color = if (background.allowed) OverdriveColors.textSecondary else OverdriveColors.textPrimary,
                )
            }
            val action = background.action
            val fix = background.fix
            if (background.allowed) {
                OdIcon(OverdriveIcons.Check, tint = OverdriveColors.textSecondary)
            } else if (action != null && fix != null) {
                OdButton(action, { actions.onBackgroundFix(fix) }, height = 40.dp)
            }
        }
    }
}

/** The Heart rate card (design/companion.md §4.9): the maximum and the threshold as steppers, the estimates from the rides beside them. */
@Composable
private fun HeartRateGroup(
    heartRate: HeartRateUi,
    actions: SettingsActions,
) {
    SettingsCard {
        HeartRateRow("Maximum", heartRate.maximum, actions.onMaxStep, actions.onUseMaxEstimate)
        HorizontalRule()
        HeartRateRow("Threshold", heartRate.threshold, actions.onThresholdStep, actions.onUseThresholdEstimate)
    }
}

@Composable
private fun HeartRateRow(
    title: String,
    row: HeartRateRowUi,
    onStep: (Int) -> Unit,
    onUseEstimate: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = OverdriveDimens.sizeListRowTwoLine,
            ).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(row.note, style = OdType.label, color = OverdriveColors.textSecondary)
            row.estimate?.let { estimate ->
                OdButton("Use $estimate bpm", {
                    onUseEstimate(estimate)
                }, variant = ButtonVariant.Ghost, height = 48.dp, horizontalPadding = 0.dp)
            }
        }
        Stepper(
            row.valueText,
            onDecrement = { onStep(-1) },
            onIncrement = { onStep(1) },
            canDecrement = row.canDecrease,
            canIncrement = row.canIncrease,
            label = title.lowercase(),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    note: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = OverdriveDimens.sizeListRowTwoLine,
            ).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(note, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        OdButton(action, onClick, height = 40.dp)
    }
}

@Composable
private fun GroupLabel(text: String) {
    OdText(
        text,
        Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        style = OdType.label,
        color = OverdriveColors.textTertiary,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(OverdriveDimens.radiusLg)
    Column(Modifier.fillMaxWidth().surface(OverdriveColors.surface1, shape, OverdriveColors.border)) { content() }
}

@Composable
private fun FieldRow(
    title: String,
    note: String,
    field: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            OdText(title, style = OdType.bodyStrong)
            OdText(note, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        field()
    }
}

@Composable
private fun Message(
    text: String,
    alert: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (alert) OdIcon(OverdriveIcons.TriangleAlert, Modifier.padding(top = 1.dp), size = InlineIconSize)
        OdText(
            text,
            style = OdType.label,
            color = if (alert) OverdriveColors.textPrimary else OverdriveColors.textSecondary,
        )
    }
}
