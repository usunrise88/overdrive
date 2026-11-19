package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.model.FitImportUi
import app.overdrive.companion.model.FitWords
import app.overdrive.companion.model.ImportAction
import app.overdrive.companion.model.ImportMark
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/** The import screen (design/companion.md §4.11) from the container: what came of the file the rider shared. */
@Composable
fun FitImportScreen(
    container: CompanionContainer,
    onDone: () -> Unit,
    onAction: (ImportAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown by container.fitShown.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    val ui = remember(shown, host) { FitWords.importScreen(shown, container.words, host) }
    FitImportView(ui, onDone, onAction, modifier)
}

/**
 * The small screen a shared FIT file lands on: "Importing…", then one block with what came of it, and the buttons at the bottom, full width
 * (primary over ghost), like the first run's steps. No illustration. The block is a polite live region, so TalkBack says the result when
 * it arrives.
 */
@Composable
fun FitImportView(
    ui: FitImportUi,
    onDone: () -> Unit,
    onAction: (ImportAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(OverdriveColors.bg).padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        OdText("Outdoor ride", style = OdType.title)
        Spacer(Modifier.weight(0.4f))
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (ui.mark) {
                ImportMark.Working -> Spinner(diameter = 24.dp)
                ImportMark.Done -> OdIcon(OverdriveIcons.Check, size = OverdriveDimens.sizeIconLarge)
                ImportMark.Alert -> OdIcon(OverdriveIcons.TriangleAlert, size = OverdriveDimens.sizeIconLarge)
            }
            OdText(ui.heading, style = OdType.heading)
            ui.text?.let { OdText(it, style = OdType.body, color = OverdriveColors.textSecondary) }
            ui.detail?.let { OdText(it, style = OdType.labelNum, color = OverdriveColors.textTertiary) }
        }
        Spacer(Modifier.weight(0.6f))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val action = ui.action
            if (ui.mark != ImportMark.Working) {
                if (action != null) {
                    OdButton(
                        action.label,
                        { onAction(action) },
                        Modifier.fillMaxWidth(),
                        variant = ButtonVariant.Primary,
                    )
                }
                OdButton(
                    "Done",
                    onDone,
                    Modifier.fillMaxWidth(),
                    variant = if (action == null) ButtonVariant.Primary else ButtonVariant.Ghost,
                )
            }
        }
    }
}
