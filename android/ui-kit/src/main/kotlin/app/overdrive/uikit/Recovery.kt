package app.overdrive.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.icons.OverdriveIcons
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens

/** What the recovery card says (design/companion.md §4.1, §7.2). Icon, word and meter carry it: there is no colour. */
enum class RecoveryLook {
    /** `check`, Ready, meter 3. */
    Ready,

    /** `triangle-alert`, Caution, meter 2. */
    Caution,

    /** `pause`, Rest, meter 1. */
    Rest,

    /** No state yet: "Learning your usual range" with a progress bar. */
    Learning,

    /** No reading from last night. */
    NoData,
}

/**
 * The recovery meter (design/system.md §13): 96 × 6 dp, three segments 4 dp apart, radius 3, filled in text 1 from the left
 * ([level] 3 Ready, 2 Caution, 1 Rest), the rest surface 3. Hidden from accessibility: the word says it.
 */
@Composable
fun RecoveryMeter(
    level: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..3) {
            Box(
                Modifier
                    .width(28.dp)
                    .height(6.dp)
                    .background(
                        if (i <=
                            level
                        ) {
                            OverdriveColors.textPrimary
                        } else {
                            OverdriveColors.surface3
                        },
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

/**
 * One fact of the recovery card (design/companion.md §4.1): the signal's [name], its [value] with [unit] and a [caption]
 * ("usual 50–56", "last night", "no reading"). Outside the usual range ([outside]) the caption is text 1 and a 16 dp
 * `triangle-alert` leads the name. A null [value] shows the dash. [onClick] opens Wellness at that metric.
 */
@Immutable
class FactUi(
    val key: String,
    val name: String,
    val value: String?,
    val unit: String?,
    val caption: String,
    val outside: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/** One row of facts, 56 dp at least, a hairline above (the caller draws it between rows). */
@Composable
fun FactRow(
    fact: FactUi,
    modifier: Modifier = Modifier,
) {
    val click = fact.onClick
    val source = rememberInteractionSource()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = OverdriveDimens.sizeListRow)
            .let { if (click != null) it.plainClickable(source, onClick = click) else it }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fact.outside) OdIcon(OverdriveIcons.TriangleAlert, size = InlineIconSize)
            OdText(fact.name, style = OdType.body, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            ValueWithUnit(fact.value, fact.unit, OdType.numXS)
            OdText(
                fact.caption,
                style = OdType.captionNum,
                color = if (fact.outside) OverdriveColors.textPrimary else OverdriveColors.textTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * The recovery card (design/companion.md §4.1): a head line ([label]: "Recovery · this morning") with the meter, the state line
 * (24 dp icon and the word in the numM style), the [reason] in body text 2 and the fact rows under hairlines. [Learning][RecoveryLook.Learning]
 * and [NoData][RecoveryLook.NoData] have no state word: a [heading] and the reason instead, and while learning a progress bar
 * ([progress] 0 to 1) between the reason and the rows.
 */
@Composable
fun RecoveryCard(
    label: String,
    look: RecoveryLook,
    reason: String?,
    facts: List<FactUi>,
    modifier: Modifier = Modifier,
    heading: String? = null,
    progress: Float? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .surface(
                OverdriveColors.surface1,
                RoundedCornerShape(OverdriveDimens.radiusLg),
                OverdriveColors.border,
            ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OdText(label, style = OdType.label, color = OverdriveColors.textTertiary, maxLines = 1)
            val level =
                when (look) {
                    RecoveryLook.Ready -> 3
                    RecoveryLook.Caution -> 2
                    RecoveryLook.Rest -> 1
                    else -> 0
                }
            if (level > 0) RecoveryMeter(level)
        }
        when (look) {
            RecoveryLook.Ready, RecoveryLook.Caution, RecoveryLook.Rest -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OdIcon(
                        when (look) {
                            RecoveryLook.Ready -> OverdriveIcons.Check
                            RecoveryLook.Caution -> OverdriveIcons.TriangleAlert
                            else -> OverdriveIcons.Pause
                        },
                        size = OverdriveDimens.sizeIconLarge,
                    )
                    OdText(
                        when (look) {
                            RecoveryLook.Ready -> "Ready"
                            RecoveryLook.Caution -> "Caution"
                            else -> "Rest"
                        },
                        style = OdType.numM,
                    )
                }
            }

            RecoveryLook.Learning, RecoveryLook.NoData -> {
                if (heading != null) OdText(heading, style = OdType.heading)
            }
        }
        if (reason != null) OdText(reason, style = OdType.body, color = OverdriveColors.textSecondary)
        if (progress != null) ProgressBar(progress)
        for (fact in facts) {
            HorizontalRule()
            FactRow(fact)
        }
    }
}
