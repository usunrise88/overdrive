package app.overdrive.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overdrive.companion.CompanionContainer
import app.overdrive.companion.RefreshTarget
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.NoteCardUi
import app.overdrive.companion.model.NoteUi
import app.overdrive.companion.model.NotesContent
import app.overdrive.companion.model.NotesUi
import app.overdrive.companion.model.ScreenUi
import app.overdrive.companion.model.TopicUi
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Spinner
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.plainClickable
import app.overdrive.uikit.rememberInteractionSource
import app.overdrive.uikit.surface
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/** What the Notes screen can do. */
class NotesActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onSettings: () -> Unit,
    val onMore: () -> Unit,
    val onOpenRide: (String) -> Unit,
    val onCopy: (String) -> Unit,
)

/** Notes (design/companion.md §4.2, pushed over Today) from the container. */
@Composable
fun NotesScreen(
    container: CompanionContainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by container.settings.collectAsStateWithLifecycle()
    val view by container.insights.view.collectAsStateWithLifecycle()
    val rides by container.rides.view.collectAsStateWithLifecycle()
    val host = remember(settings) { hostOf(settings?.serviceUrl.orEmpty()) }
    LaunchedEffect(Unit) { container.screenOpened(RefreshTarget.Notes) }
    val ui = remember(view, rides, host) { NotesUi.screen(view, rides, container.clock.today(), container.words, host) }
    NotesView(
        ui,
        NotesActions(
            onBack = { container.nav.back() },
            onRetry = { container.refresh(RefreshTarget.Notes) },
            onSettings = { container.nav.push(Route.Settings) },
            onMore = container::loadMoreNotes,
            onOpenRide = { container.nav.push(Route.RideDetail(it)) },
            onCopy = { copyToClipboard(context, "Question for your coach", it) },
        ),
        modifier,
    )
}

/** The Notes screen for a given [ui]: a lazy list grouped by day, one card per note, read only. */
@Composable
fun NotesView(
    ui: ScreenUi<NotesContent>,
    actions: NotesActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Notes", actions.onBack)
        when (val body = ui.body) {
            is Body.Loading -> {
                Box(Modifier.fillMaxSize()) { LoadingBlock(body.text, Modifier.fillMaxSize()) }
            }

            is Body.Failed -> {
                ErrorBlock(
                    heading = "Could not reach your service",
                    cause = body.problem.cause,
                    hint = body.problem.hint,
                    onTryAgain = actions.onRetry,
                    modifier = Modifier.fillMaxSize(),
                    settingsLabel = body.problem.action?.label,
                    onSettings = actions.onSettings,
                )
            }

            is Body.Empty -> {
                Box(Modifier.padding(horizontal = 16.dp)) { EmptyBlock(body.heading, body.text) }
            }

            is Body.Content -> {
                val content = body.value
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (group in content.groups) {
                        item(key = "day-${group.date}") {
                            OdText(
                                group.heading,
                                Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                                style = OdType.label,
                                color = OverdriveColors.textTertiary,
                            )
                        }
                        items(group.notes, key = { it.id }) { note ->
                            NoteCard(note, actions.onOpenRide, actions.onCopy)
                        }
                    }
                    item(key = "more") {
                        if (content.hasMore) {
                            // The end of the list is on screen: the next 30 days.
                            LaunchedEffect(content.groups.sumOf { it.notes.size }) { actions.onMore() }
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (content.loadingMore) Spinner()
                            }
                        }
                        content.moreProblem?.let {
                            OdText(
                                it,
                                Modifier.padding(vertical = 8.dp),
                                style = OdType.label,
                                color = OverdriveColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A note (design/companion.md §4.2): the kind and time in label text 3, the text in body, the topic of an after-ride note with its
 * opening question and a **Copy** action, and the link to the ride. Read only: nothing here changes a note.
 */
@Composable
fun NoteCard(
    note: NoteUi,
    onOpenRide: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OdCard(modifier.fillMaxWidth(), PaddingValues(16.dp), Arrangement.spacedBy(8.dp)) {
        NoteBody(note, onCopy)
        note.ride?.let { link ->
            val source = rememberInteractionSource()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .plainClickable(source, role = Role.Button) { onOpenRide(link.rideId) },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdText(link.name, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                OdIcon(OverdriveIcons.ChevronRight, size = 16.dp, tint = OverdriveColors.textSecondary)
            }
        }
    }
}

@Composable
private fun NoteBody(
    note: NoteUi,
    onCopy: (String) -> Unit,
) {
    OdText(note.label, style = OdType.label, color = OverdriveColors.textTertiary)
    OdText(note.text, style = OdType.body)
    note.topic?.let { TopicBlock(it, onCopy) }
}

/**
 * The reason to talk to the coach (spec §12.3, ADR 0025): one sentence of what stood out, and the question to open the chat with,
 * which **Copy** puts on the clipboard. The words are the service's; nothing is sent from here.
 */
@Composable
fun TopicBlock(
    topic: TopicUi,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember(topic.id) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .surface(OverdriveColors.surface2, RoundedCornerShape(OverdriveDimens.radiusMd), OverdriveColors.border)
            .padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OdText("Worth a chat with your coach", style = OdType.label, color = OverdriveColors.textTertiary)
        OdText(topic.text, Modifier.padding(end = 8.dp), style = OdType.body, color = OverdriveColors.textSecondary)
        OdText(topic.question, Modifier.padding(end = 8.dp), style = OdType.bodyStrong)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OdButton(
                if (copied) "Copied" else "Copy question",
                {
                    copied = true
                    onCopy(topic.question)
                },
                variant = ButtonVariant.Ghost,
                height = 48.dp,
                leadingIcon = if (copied) OverdriveIcons.Check else null,
            )
        }
    }
}

/**
 * Today's note card (design/companion.md §4.1): the latest note, or the sentence that it is not written yet, over a hairline and a
 * 48 dp **All notes** row.
 */
@Composable
fun TodayNoteCard(
    card: NoteCardUi,
    onAllNotes: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OdCard(modifier.fillMaxWidth(), PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val note = card.note
            if (note != null) {
                NoteBody(note, onCopy)
            } else {
                OdText("Morning note", style = OdType.label, color = OverdriveColors.textTertiary)
                OdText(card.waiting.orEmpty(), style = OdType.body, color = OverdriveColors.textSecondary)
            }
        }
        if (card.hasAll) {
            HorizontalRule()
            val source = rememberInteractionSource()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .plainClickable(source, role = Role.Button, onClick = onAllNotes)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OdText("All notes", Modifier.weight(1f), style = OdType.bodyStrong)
                OdIcon(OverdriveIcons.ChevronRight, tint = OverdriveColors.textSecondary)
            }
        }
    }
}
