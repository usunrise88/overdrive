package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

// The pieces of the phone's chrome (design/companion.md §3, system.md §13) that are not charts.

/** One tab of the [BottomNav]. */
@Immutable
class NavItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * Bottom navigation (design/system.md §7.9): 80 dp with a 16 dp gesture inset (more where the system's bar is taller), a top
 * hairline, equal items of a 24 dp icon over a caption. Active text 1, inactive text 3: no pill, no indicator bar. Tapping the
 * active tab scrolls its screen to the top ([onReselect]). Each tab says "Today, tab, 1 of 5" to TalkBack.
 */
@Composable
fun BottomNav(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val inset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    Column(modifier.fillMaxWidth().background(OverdriveColors.bg)) {
        HorizontalRule()
        Row(Modifier.fillMaxWidth().height(OverdriveDimens.sizeBottomNav)) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                val source = rememberInteractionSource()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            this.selected = active
                            contentDescription = "${item.label}, tab, ${index + 1} of ${items.size}"
                        }.plainClickable(
                            source,
                            role = Role.Tab,
                        ) { if (active) onReselect(index) else onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OdIcon(
                        item.icon,
                        size = OverdriveDimens.sizeIconLarge,
                        tint = if (active) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
                    )
                    OdText(
                        item.label,
                        Modifier.padding(top = 4.dp),
                        style = OdType.caption,
                        color = if (active) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(maxOf(GESTURE_INSET, inset)))
    }
}

private val GESTURE_INSET = 16.dp

/**
 * The header of a top-level screen (design/companion.md §3.1): the title and one subtitle line with a 4 dp extra side inset,
 * and an optional trailing control (Today's 48 dp gear). While a refresh runs the subtitle reads **Updating…** with a 16 dp
 * spinner ring before it, and the content under it stays as it is.
 *
 * @param subtitle "Updated 07:40", "Wednesday 18 November · updated 07:40", or null for none (a status line then says why).
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            OdText(title, Modifier.semantics { heading() }, style = OdType.title, maxLines = 1)
            if (refreshing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spinner(diameter = 16.dp)
                    OdText("Updating…", style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
                }
            } else if (subtitle != null) {
                OdText(subtitle, style = OdType.label, color = OverdriveColors.textSecondary, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/**
 * The problem line under a header (design/companion.md §3.2): surface 2, radius 8, padding 12 × 16, a 20 dp alert, a first line in
 * body-strong ("Offline · updated 06:10"), the cause in label text 2 and optionally one 40 dp secondary button at the right.
 */
@Composable
fun StatusLine(
    title: String,
    cause: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                OverdriveColors.surface2,
                RoundedCornerShape(OverdriveDimens.radiusMd),
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OdIcon(OverdriveIcons.TriangleAlert, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(cause, style = OdType.label, color = OverdriveColors.textSecondary)
        }
        if (actionLabel != null) OdButton(actionLabel, onAction, height = 40.dp)
    }
}

/** A notice with its buttons **under** the text, for a phone's width (the FTP suggestion, design/companion.md §4.1). */
@Composable
fun StackedNotice(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                OverdriveColors.surface2,
                RoundedCornerShape(OverdriveDimens.radiusMd),
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OdIcon(icon, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OdText(title, style = OdType.bodyStrong)
            OdText(text, style = OdType.label, color = OverdriveColors.textSecondary)
            if (primaryLabel != null) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OdButton(primaryLabel, onPrimary)
                    if (secondaryLabel != null) OdButton(secondaryLabel, onSecondary, variant = ButtonVariant.Ghost)
                }
            }
        }
    }
}

/** Fitness' and Power's range selector (design/companion.md §3.4): a full-width segmented control of equal 44 dp segments. */
@Composable
fun RangeSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedControl(
        options,
        selectedIndex,
        onSelect,
        modifier.fillMaxWidth(),
        segmentHeight = 44.dp,
        equalWidth = true,
    )
}

/** A determinate progress bar (design/system.md §13): a 4 dp track in surface 3, the fill in text 1, radius 2. */
@Composable
fun ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(2.dp)
    Box(modifier.fillMaxWidth().height(4.dp).background(OverdriveColors.surface3, shape)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(
                    fraction.coerceIn(0f, 1f),
                ).background(OverdriveColors.textPrimary, shape),
        )
    }
}

/** The loading state of a screen with nothing cached (design/companion.md §5): a spinner ring and a line at 40% of the height. */
@Composable
fun LoadingBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().fillMaxHeight()) {
        Spacer(Modifier.weight(0.4f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner()
            OdText(text, style = OdType.body, color = OverdriveColors.textSecondary)
        }
        Spacer(Modifier.weight(0.6f))
    }
}

/**
 * The error state of a screen with nothing cached (design/companion.md §5): the alert, "Could not reach your service", the
 * cause, the technical hint in brackets, **Try again** and, when the rider has to change something, **Open settings**.
 */
@Composable
fun ErrorBlock(
    heading: String,
    cause: String,
    hint: String?,
    onTryAgain: () -> Unit,
    modifier: Modifier = Modifier,
    settingsLabel: String? = null,
    onSettings: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp)) {
        Spacer(Modifier.weight(0.4f))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OdIcon(OverdriveIcons.TriangleAlert, size = OverdriveDimens.sizeIconLarge)
            OdText(heading, style = OdType.heading)
            OdText(cause, style = OdType.body, color = OverdriveColors.textSecondary)
            if (hint != null) OdText("($hint)", style = OdType.label, color = OverdriveColors.textTertiary)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OdButton("Try again", onTryAgain, leadingIcon = OverdriveIcons.RefreshCw)
                if (settingsLabel != null) OdButton(settingsLabel, onSettings, variant = ButtonVariant.Ghost)
            }
        }
        Spacer(Modifier.weight(0.6f))
    }
}

/** An empty screen (design/companion.md §5, §7.6): a heading and one sentence saying what fills it and where from. No illustration. */
@Composable
fun EmptyBlock(
    heading: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OdText(heading, style = OdType.heading)
        OdText(text, style = OdType.body, color = OverdriveColors.textSecondary)
        if (actionLabel !=
            null
        ) {
            OdButton(actionLabel, onAction, Modifier.padding(top = 8.dp), variant = ButtonVariant.Primary)
        }
    }
}

/** A sentence in a card where a part has no data (design/companion.md §5, "Partial"). */
@Composable
fun PartialNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    OdText(text, modifier, style = OdType.body, color = OverdriveColors.textSecondary)
}
