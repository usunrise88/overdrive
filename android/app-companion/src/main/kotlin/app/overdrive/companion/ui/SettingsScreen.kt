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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.model.ProblemWords
import app.overdrive.core.remote.ProblemKind
import app.overdrive.data.settings.AddressCheck
import app.overdrive.data.settings.ServiceRules
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTextField
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
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
)

class SettingsActions(
    val onBack: () -> Unit,
    val onAddress: (String) -> Unit,
    val onToken: (String) -> Unit,
    val onSyncNow: () -> Unit,
    val onLicences: () -> Unit,
)

/** The words of Settings' "Last sync" row (design/companion.md §4.9), pure so they are tested. */
object SyncWords {
    fun lastSync(
        fetchedAtMillis: Long?,
        words: app.overdrive.companion.model.Words,
    ): String = fetchedAtMillis?.let(words::lastSync) ?: "Never"

    fun note(
        fetchedAtMillis: Long?,
        problem: app.overdrive.core.remote.ServiceProblem?,
        waiting: Int,
        host: String,
    ): String {
        val waitingText =
            when (waiting) {
                0 -> null
                1 -> "1 answer is waiting to be sent."
                else -> "$waiting answers are waiting to be sent."
            }
        return when {
            problem != null -> {
                listOfNotNull(
                    "The last attempt failed. ${ProblemWords.causeOf(problem, host)}",
                    waitingText,
                ).joinToString(" ")
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
    var address by remember { mutableStateOf(settings?.serviceUrl.orEmpty()) }
    var token by remember { mutableStateOf(settings?.token?.value.orEmpty()) }
    val check = ServiceRules.checkAddress(address)
    val host = hostOf(settings?.serviceUrl.orEmpty())
    val state =
        SettingsUiState(
            address = address,
            addressMistake = (check as? AddressCheck.Invalid)?.reason,
            notEncrypted = check is AddressCheck.Valid && !check.isEncrypted,
            token = token,
            tokenRefused = overview.problem?.kind == ProblemKind.TOKEN_REJECTED,
            lastSyncValue = SyncWords.lastSync(overview.lastFetchedMillis, container.words),
            lastSyncNote = SyncWords.note(overview.lastFetchedMillis, overview.problem, overview.waiting, host),
            syncing = overview.syncing,
            version = versionOf(context),
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
