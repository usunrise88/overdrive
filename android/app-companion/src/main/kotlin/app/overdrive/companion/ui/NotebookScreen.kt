package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.NotebookContent
import app.overdrive.companion.model.NotebookSectionUi
import app.overdrive.companion.model.NotebookUi
import app.overdrive.companion.model.ScreenUi
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.PartialNote
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.StatusLine
import app.overdrive.uikit.tokens.OverdriveColors

/** What the notebook page can do. It can only be read: there is no control that changes the notebook. */
class NotebookActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSettings: () -> Unit,
)

/** The coach notebook, read-only, pushed from Settings (design/companion.md §4.13), from the container's cached resource. */
@Composable
fun NotebookScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val state by container.notebook.state.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(Unit) { container.screenOpened(RefreshTarget.Notebook) }
    val ui = remember(state, host) { NotebookUi.of(state, container.words, host) }
    NotebookView(
        ui,
        NotebookActions(
            onBack = { container.nav.back() },
            onRefresh = { container.refresh(RefreshTarget.Notebook) },
            onSettings = { container.nav.push(Route.Settings) },
        ),
        modifier,
    )
}

/** The notebook page for a given [ui]: the read-only line, then each section in its fixed order. */
@Composable
fun NotebookView(
    ui: ScreenUi<NotebookContent>,
    actions: NotebookActions,
    modifier: Modifier = Modifier,
) {
    // The log is collapsed to its latest entries until the rider asks for all of them (design/companion.md §4.13).
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Coach notebook", actions.onBack)
        LazyColumn(
            Modifier.fillMaxSize().pullToRefresh(actions.onRefresh),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "subtitle") { Subtitle(ui) }
            ui.status?.let { problem ->
                item(key = "status") {
                    StatusLine(
                        problem.title,
                        problem.cause,
                        actionLabel = problem.action?.label,
                        onAction = actions.onSettings,
                    )
                }
            }
            when (val body = ui.body) {
                is Body.Loading -> {
                    item(key = "loading") { LoadingBlock(body.text, Modifier.heightIn(min = 480.dp)) }
                }

                is Body.Failed -> {
                    item(key = "error") {
                        ErrorBlock(
                            "Could not reach your service",
                            body.problem.cause,
                            body.problem.hint,
                            actions.onRefresh,
                            Modifier.heightIn(min = 480.dp),
                            settingsLabel = body.problem.action?.label,
                            onSettings = actions.onSettings,
                        )
                    }
                }

                is Body.Empty -> {
                    item(key = "empty") { EmptyBlock(body.heading, body.text) }
                }

                is Body.Content -> {
                    item(key = "read-only") { PartialNote(NotebookUi.READ_ONLY) }
                    items(body.value.sections, key = { it.title }) { section ->
                        SectionCard(section, showAll) { showAll = !showAll }
                    }
                }
            }
        }
    }
}

/** "Updating…" with a spinner while a request runs, else "Updated 07:40" (or nothing before the first answer). */
@Composable
private fun Subtitle(ui: ScreenUi<NotebookContent>) {
    if (ui.refreshing) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(diameter = 16.dp)
            OdText("Updating…", style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
        }
    } else {
        ui.subtitle?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1) }
    }
}

/** One section: its marker, title, when it changed in words, and its text (selectable and wrapped). The log is a list of entries. */
@Composable
private fun SectionCard(
    section: NotebookSectionUi,
    showAll: Boolean,
    onToggleLog: () -> Unit,
) {
    OdCard(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        section.marker?.let { OdText(it, style = OdType.label, color = OverdriveColors.textSecondary) }
        OdText(section.title, Modifier.semantics { heading() }, style = OdType.heading)
        OdText(section.changed, style = OdType.label, color = OverdriveColors.textSecondary)
        if (section.text.isNotEmpty()) {
            SelectionContainer { OdText(section.text, style = OdType.body) }
        }
        section.reason?.let { reason ->
            SelectionContainer { OdText("Why: $reason", style = OdType.label, color = OverdriveColors.textSecondary) }
        }
        section.entries?.let { entries -> LogEntries(entries, showAll, onToggleLog) }
    }
}

@Composable
private fun LogEntries(
    entries: List<String>,
    showAll: Boolean,
    onToggle: () -> Unit,
) {
    val view = NotebookUi.logView(entries, showAll)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (entry in view.visible) {
            SelectionContainer { OdText(entry, style = OdType.body) }
        }
        view.toggle?.let { label ->
            OdButton(label, onToggle, variant = ButtonVariant.Secondary, height = 40.dp)
        }
    }
}
