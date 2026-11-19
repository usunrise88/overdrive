package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.ConnectResult
import app.overdrive.data.settings.AddressCheck
import app.overdrive.data.settings.ServiceRules
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdTextField
import app.overdrive.uikit.OdType
import app.overdrive.uikit.OverdriveMark
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import kotlinx.coroutines.launch

/**
 * The numbered steps of the first run (design/companion.md §4.10): connect, Health Connect, background reading. The first read of the
 * last 60 days that follows has no number.
 */
const val ONBOARDING_STEPS = 3

/** The first run (design/companion.md §4.10, the connect step): the address, the token and **Connect**. */
@Composable
fun ConnectScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ConnectResult?>(null) }
    var connecting by remember { mutableStateOf(false) }
    ConnectView(
        address,
        token,
        result,
        connecting,
        onAddress = {
            address = it
            result = null
        },
        onToken = {
            token = it
            result = null
        },
        onConnect = {
            connecting = true
            scope.launch {
                result = container.connect(address, token)
                connecting = false
            }
        },
        modifier,
    )
}

/** The connect step for a given state; a failure stays here with the cause under the field it concerns. */
@Composable
fun ConnectView(
    address: String,
    token: String,
    result: ConnectResult?,
    connecting: Boolean,
    onAddress: (String) -> Unit,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    tokenShown: Boolean = false,
) {
    var shown by remember { mutableStateOf(tokenShown) }
    Column(
        modifier
            .fillMaxSize()
            .background(
                OverdriveColors.bg,
            ).imePadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverdriveMark(size = 56.dp)
            if (ONBOARDING_STEPS > 1) {
                OdText("Step 1 of $ONBOARDING_STEPS", style = OdType.label, color = OverdriveColors.textTertiary)
            }
            OdText("Connect to your service", Modifier.padding(top = 8.dp), style = OdType.title)
            OdText(
                "Overdrive keeps your rides and watch data on your own service. Enter its address and the token made for this phone.",
                style = OdType.body,
                color = OverdriveColors.textSecondary,
            )
            Spacer(Modifier.padding(top = 8.dp))
            OdText("Service address", style = OdType.bodyStrong)
            OdTextField(
                address,
                onAddress,
                "Service address",
                Modifier.fillMaxWidth(),
                placeholder = "overdrive.example.net",
                keyboardType = KeyboardType.Uri,
            )
            (result as? ConnectResult.AddressProblem)?.let { Cause(it.reason, it.hint) }
            val typed = ServiceRules.checkAddress(address)
            if (typed is AddressCheck.Valid && !typed.isEncrypted) {
                Cause("Not encrypted: the token is sent as plain text", null)
            }
            Spacer(Modifier.padding(top = 8.dp))
            OdText("Token", style = OdType.bodyStrong)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OdTextField(token, onToken, "Token", Modifier.weight(1f), secret = !shown)
                OdButton(
                    if (shown) "Hide" else "Show",
                    { shown = !shown },
                    variant = ButtonVariant.Ghost,
                    height = 48.dp,
                )
            }
            (result as? ConnectResult.TokenProblem)?.let { Cause(it.reason, null) }
        }
        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OdButton(
                if (connecting) "Connecting…" else "Connect",
                onConnect,
                Modifier.weight(1f),
                variant = ButtonVariant.Primary,
                enabled = !connecting && address.isNotBlank() && token.isNotBlank(),
            )
            if (connecting) Spinner()
        }
    }
}

@Composable
private fun Cause(
    text: String,
    hint: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            OdIcon(OverdriveIcons.TriangleAlert, Modifier.padding(top = 1.dp), size = InlineIconSize)
            OdText(text, style = OdType.label)
        }
        if (hint !=
            null
        ) {
            OdText(
                "($hint)",
                Modifier.padding(start = 22.dp),
                style = OdType.label,
                color = OverdriveColors.textTertiary,
            )
        }
    }
}
