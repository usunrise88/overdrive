package app.overdrive.ride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.overdrive.ride.routes.RouteChoiceUi
import app.overdrive.uikit.BottomSheet
import app.overdrive.uikit.DisclosureRow
import app.overdrive.uikit.InlineIconSize
import app.overdrive.uikit.OdIcon
import app.overdrive.uikit.OdText
import app.overdrive.uikit.OdType
import app.overdrive.uikit.SelectableRow
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * The route row of a free ride on the Ready screen (spec §11.4, design/system.md §6.1): one disclosure row, "Route" and what is chosen
 * ("Flat road" or the route's name), with the facts of the route under it, or **"Route not downloaded yet"** with an alert when its points
 * are not on the tablet (the ride would be flat). Pressing it opens [RouteSheet]; like every row it takes focus and the key that activates it.
 */
@Composable
internal fun RouteRow(
    choice: RouteChoiceUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DisclosureRow("Route", choice.title, expanded = false, onToggle = onClick)
        val detail = choice.detail
        if (detail != null) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val problem = detail == RouteChoiceUi.NOT_DOWNLOADED
                if (problem) OdIcon(OverdriveIcons.TriangleAlert, Modifier.padding(top = 1.dp), size = InlineIconSize)
                OdText(
                    detail,
                    style = OdType.label,
                    color = if (problem) OverdriveColors.textPrimary else OverdriveColors.textSecondary,
                )
            }
        }
    }
}

/**
 * The short list of routes (spec §11.4): the flat road first, then every route the tablet has the list of, each with its distance, its
 * ascent and a word for whether it has a map or is a profile only. A bottom sheet like the other choices of the app; one tap chooses and
 * closes it, and a tap outside closes it without choosing.
 */
@Composable
internal fun RouteSheet(
    visible: Boolean,
    choice: RouteChoiceUi?,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomSheet(visible, onDismiss, modifier) {
        OdText("Route", Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp), style = OdType.heading)
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (option in choice?.options.orEmpty()) {
                SelectableRow(
                    title = option.title,
                    subtitle = option.detail,
                    selected = option.selected,
                    onClick = { onChoose(option.id) },
                )
            }
        }
    }
}
