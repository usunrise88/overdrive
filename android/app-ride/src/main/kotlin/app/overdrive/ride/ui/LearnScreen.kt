package app.overdrive.ride.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnRejection
import app.overdrive.ride.handlebar.LearnUi
import app.overdrive.uikit.BackTopBar
import app.overdrive.uikit.ButtonVariant
import app.overdrive.uikit.HorizontalRule
import app.overdrive.uikit.Notice
import app.overdrive.uikit.OdButton
import app.overdrive.uikit.OdCard
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/** What the rider can do in the *Learn buttons* flow. */
class LearnActions(
    /** Leave the flow, saving nothing (or, once all five are set, **Done**). */
    val onCancel: () -> Unit,
    /** Put the default mapping back and leave. */
    val onReset: () -> Unit,
)

/**
 * **Learn buttons** (design/system.md §6.5, ADR 0012): a guided flow that asks for the five roles in turn ("Press the button
 * you want as Plus"). Left, what to do now and, if the last press was refused, why in plain words; right, the five roles with
 * the Click's own name for the button each one has so far. The Click does nothing meanwhile, so a press made to be learned
 * never shifts a gear. **Cancel** leaves with nothing saved, **Reset to default** puts the default back, and when all five
 * are set the mapping is already saved and the one button left is **Done**.
 *
 * @param clickReady false when the Click has dropped meanwhile: the prompt says to reconnect it, and the flow waits.
 */
@Composable
fun LearnScreen(
    ui: LearnUi,
    actions: LearnActions,
    modifier: Modifier = Modifier,
    clickReady: Boolean = true,
) {
    Column(modifier.fillMaxSize().background(OverdriveColors.bg)) {
        BackTopBar("Learn buttons", actions.onCancel)
        Row(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Prompt(ui, clickReady)
                ui.rejection?.let { Refusal(ui, it) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ui.done) {
                        OdButton("Done", actions.onCancel, variant = ButtonVariant.Primary)
                    } else {
                        OdButton("Cancel", actions.onCancel)
                    }
                    OdButton("Reset to default", actions.onReset, variant = ButtonVariant.Ghost)
                }
            }
            Column(Modifier.weight(1f)) { Roles(ui) }
        }
    }
}

@Composable
private fun Prompt(
    ui: LearnUi,
    clickReady: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.done) {
            OdText("All five set", style = OdType.label, color = OverdriveColors.textTertiary)
            OdText("The Click has its new buttons", style = OdType.title)
            OdText(
                "They are saved. The Click does nothing until you press Done, so a press cannot move the ride.",
                style = OdType.label,
                color = OverdriveColors.textSecondary,
            )
        } else {
            OdText("Step ${ui.step} of ${ui.total}", style = OdType.label, color = OverdriveColors.textTertiary)
            OdText("Press the button you want as ${ui.role.label}", style = OdType.title)
            OdText(
                roleSummary(ui.role),
                style = OdType.label,
                color = OverdriveColors.textSecondary,
            )
            OdText(
                if (clickReady) {
                    "Press and release one button, then wait."
                } else {
                    "The Click is not connected. Reconnect it to go on."
                },
                style = OdType.label,
                color = if (clickReady) OverdriveColors.textTertiary else OverdriveColors.textPrimary,
            )
        }
    }
}

/** Why the last press was not taken, in plain words, and what to do instead. */
@Composable
private fun Refusal(
    ui: LearnUi,
    reason: LearnRejection,
) {
    when (reason) {
        LearnRejection.COMBO -> {
            Notice(
                icon = OverdriveIcons.Info,
                title = "One button at a time",
                text = "Two buttons were pressed together. Press just the one you want as ${ui.role.label}.",
            )
        }

        LearnRejection.DUPLICATE -> {
            Notice(
                icon = OverdriveIcons.Info,
                title = "That button is taken",
                text =
                    "It is already ${ui.clashesWith?.label ?: "another button"}. " +
                        "Press a different one for ${ui.role.label}.",
            )
        }
    }
}

/** The five roles, the Click's names for what each has so far, and the one being asked for marked. */
@Composable
private fun Roles(ui: LearnUi) {
    OdCard(padding = PaddingValues(8.dp)) {
        HandlebarButton.entries.forEachIndexed { index, role ->
            if (index > 0) HorizontalRule(Modifier.padding(horizontal = 8.dp))
            val learned = ui.learned[role]?.joinToString(" + ")
            val asking = !ui.done && role == ui.role
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = OverdriveDimens.sizeListRowTwoLine)
                    .background(if (asking) OverdriveColors.surface2 else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Marker(done = learned != null, asking = asking)
                Column(Modifier.weight(1f)) {
                    OdText(role.label, style = OdType.bodyStrong, maxLines = 1)
                    OdText(roleSummary(role), style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                }
                OdText(
                    when {
                        learned != null -> learned
                        asking -> "Press now"
                        else -> "—"
                    },
                    style = OdType.bodyStrong,
                    color =
                        if (learned != null || asking) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A check for a role that has its button, a filled dot for the one being asked, an empty ring for the rest. */
@Composable
private fun Marker(
    done: Boolean,
    asking: Boolean,
) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when {
            done -> OdIcon(OverdriveIcons.Check)
            asking -> Box(Modifier.size(10.dp).background(OverdriveColors.textPrimary, CircleShape))
            else -> Box(Modifier.size(10.dp).background(OverdriveColors.surface3, CircleShape))
        }
    }
}

/** What a role does, in one line: the press, and the hold or its repeat. */
fun roleSummary(role: HandlebarButton): String =
    if (role.repeats) {
        "${role.press.label} · repeats while held"
    } else {
        "${role.press.label} · hold for ${role.hold.label.lowercase()}"
    }
