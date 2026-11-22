package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.overdrive.core.powerskills.POWER_LEVEL_NAMES
import app.overdrive.core.powerskills.POWER_SKILL_DURATIONS
import app.overdrive.core.powerskills.PowerSkill
import app.overdrive.core.powerskills.PowerSkillDuration
import app.overdrive.core.powerskills.PowerSkillEffort
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.RadarLabel
import app.overdrive.uikit.chart.RadarSegment
import app.overdrive.uikit.chart.RadarSpec
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The Power skills card of the Power tab (design/companion.md §4.14, §6.11, §7.8, ADR 0042) as pure functions: the window control, the
 * radar's radii and labels, the five skill rows, the sheets of a duration and of a skill, the Custom range's picker and every state of
 * the card. The service computes the scores and the levels; the phone only places them and words them. No Compose runtime in here beyond
 * the `Immutable` mark, so each rule is a unit test.
 */

/** The words and the colours of the skills, durations and levels (design/companion.md §6.11, §7.8). */
object SkillWords {
    /** The skill colours: the zone colour its best efforts are ridden in, short to long (§6.11). */
    fun color(id: PowerSkillId): Color =
        when (id) {
            PowerSkillId.SPRINT -> OverdriveColors.z7
            PowerSkillId.ATTACK -> OverdriveColors.z6
            PowerSkillId.CLIMB -> OverdriveColors.z5
            PowerSkillId.TIME_TRIAL -> OverdriveColors.z4
            PowerSkillId.ENDURANCE -> OverdriveColors.z3
        }

    /** The duration the row's watts are read at: 15 s, 1, 5, 20 and 60 min (§4.14). */
    fun keyDuration(id: PowerSkillId): Int =
        when (id) {
            PowerSkillId.SPRINT -> 15
            PowerSkillId.ATTACK -> 60
            PowerSkillId.CLIMB -> 300
            PowerSkillId.TIME_TRIAL -> 1200
            PowerSkillId.ENDURANCE -> 3600
        }

    /** "15 s", "30 s", "1 min", "45 min", "60 min": never "1 h", the set ends at 60 min (§7.8). */
    fun duration(seconds: Int): String =
        if (seconds <
            SECONDS_PER_MINUTE
        ) {
            "$seconds s"
        } else {
            "${seconds / SECONDS_PER_MINUTE} min"
        }

    /** "15 seconds", "1 minute", "45 minutes": the TalkBack form. */
    fun spoken(seconds: Int): String =
        when {
            seconds < SECONDS_PER_MINUTE -> "$seconds seconds"
            seconds == SECONDS_PER_MINUTE -> "1 minute"
            else -> "${seconds / SECONDS_PER_MINUTE} minutes"
        }

    /** "15–30 s", "1–3 min", "45–60 min": a skill's span. */
    fun span(skill: PowerSkillId): String {
        val unit = if (skill.toSeconds < SECONDS_PER_MINUTE) "s" else "min"
        val divisor = if (skill.toSeconds < SECONDS_PER_MINUTE) 1 else SECONDS_PER_MINUTE
        return "${skill.fromSeconds / divisor}–${skill.toSeconds / divisor} $unit"
    }

    /** "5 to 10 minutes", "15 to 30 seconds": a skill's span for TalkBack. */
    fun spokenSpan(skill: PowerSkillId): String =
        if (skill.toSeconds < SECONDS_PER_MINUTE) {
            "${skill.fromSeconds} to ${skill.toSeconds} seconds"
        } else {
            "${skill.fromSeconds / SECONDS_PER_MINUTE} to ${skill.toSeconds / SECONDS_PER_MINUTE} minutes"
        }

    /** Watts with a thousands comma: "930", "1,010". */
    fun watts(value: Double): String = String.format(Locale.US, "%,d", value.roundToInt())

    /** "4.4 W/kg". */
    fun perKg(value: Double): String = "${UiFormat.decimal(value, 1)} W/kg"

    /** "Strong": the word of level [index] (1 to 9). */
    fun levelWord(index: Int): String = POWER_LEVEL_NAMES[index - 1]

    /** "Strong · level 5 of 9". */
    fun levelLine(index: Int): String = "${levelWord(index)} · level $index of ${POWER_LEVEL_NAMES.size}"

    /** "Fit · 4 of 9": the level in a sheet's row. */
    fun levelShort(index: Int): String = "${levelWord(index)} · $index of ${POWER_LEVEL_NAMES.size}"

    /** "72.0 kg". */
    fun kg(value: Double): String = "${UiFormat.decimal(value, 1)} kg"

    private const val SECONDS_PER_MINUTE = 60
}

/** The window control and its words (design/companion.md §4.14 items 2 to 4). */
object SkillWindows {
    val labels: List<String> = listOf("8 weeks", "All time", "Custom")

    fun index(window: PowerSkillsWindow): Int =
        when (window) {
            PowerSkillsWindow.EightWeeks -> 0
            PowerSkillsWindow.AllTime -> 1
            is PowerSkillsWindow.Custom -> 2
        }

    /** The count in the range line and the picker: "9 rides", "1 ride", "no rides". */
    fun rides(count: Int): String =
        when (count) {
            0 -> "no rides"
            1 -> "1 ride"
            else -> "$count rides"
        }

    /**
     * "2 Nov – 15 Nov": a range in a legend and a sheet's row. The year is left out when both ends are in the present year, and each end
     * carries its year otherwise, so a range of last winter is never read as this one.
     */
    fun short(
        from: String,
        to: String,
        words: Words,
    ): String {
        val year = words.thisYear()
        val same = LocalDate.parse(from).year == year && LocalDate.parse(to).year == year
        return if (same) {
            "${words.dayShort(from)} – ${words.dayShort(to)}"
        } else {
            "${words.dayShort(from)} ${from.take(YEAR)} – ${words.dayShort(to)} ${to.take(YEAR)}"
        }
    }

    /** "2 Nov – 15 Nov 2026": the range line, the year at the end (both ends in one year) or on each end. */
    fun long(
        from: String,
        to: String,
        words: Words,
    ): String =
        if (from.take(YEAR) == to.take(YEAR)) {
            "${words.dayShort(from)} – ${words.dayShort(to)} ${to.take(YEAR)}"
        } else {
            "${words.dayShort(from)} ${from.take(YEAR)} – ${words.dayShort(to)} ${to.take(YEAR)}"
        }

    /** The name of the window in the legend: "8 weeks", "All time", "2 Nov – 15 Nov". */
    fun legend(
        window: PowerSkillsWindow,
        words: Words,
    ): String =
        when (window) {
            PowerSkillsWindow.EightWeeks -> "8 weeks"
            PowerSkillsWindow.AllTime -> "All time"
            is PowerSkillsWindow.Custom -> short(window.from, window.to, words)
        }

    /** "Last 8 weeks", "All time" or the range: the window's row in a sheet. */
    fun row(
        window: PowerSkillsWindow,
        words: Words,
    ): String =
        when (window) {
            PowerSkillsWindow.EightWeeks -> "Last 8 weeks"
            PowerSkillsWindow.AllTime -> "All time"
            is PowerSkillsWindow.Custom -> short(window.from, window.to, words)
        }

    /** "8 weeks", "all time" or the range: where a sentence names the window ("Level, 8 weeks", "in 8 weeks"). */
    fun inSentence(
        window: PowerSkillsWindow,
        words: Words,
    ): String =
        when (window) {
            PowerSkillsWindow.EightWeeks -> "8 weeks"
            PowerSkillsWindow.AllTime -> "all time"
            is PowerSkillsWindow.Custom -> short(window.from, window.to, words)
        }

    /** The line that sits under the control while Custom is on: "2 Nov – 15 Nov 2026 · 9 rides", the count once the answer is here. */
    fun rangeLine(
        window: PowerSkillsWindow.Custom,
        rideCount: Int?,
        words: Words,
    ): String = long(window.from, window.to, words) + (rideCount?.let { " · ${rides(it)}" } ?: "")

    private const val YEAR = 4
}

/** A ride a value came from: its id and the line that names it, "Thu 12 Nov · VO2 5×3". */
@Immutable
data class RideRef(
    val rideId: String,
    val line: String,
)

/** One value of a duration in a window: the watts, the watts per kilogram (null without weight) and the ride. */
@Immutable
data class EffortCell(
    val watts: String,
    val perKg: String?,
    val date: String,
    val ride: RideRef,
)

/** A row of a sheet that shows a duration in one window. [cell] null is "no ride". */
@Immutable
data class AxisValueRow(
    val label: String,
    val cell: EffortCell?,
    /** "No ride of 45 min or longer in these 8 weeks", when [cell] is null. */
    val emptyText: String?,
)

/** The level row of the duration sheet: "Level, 8 weeks", "Approximate, at 72.0 kg", "Fit · 4 of 9". */
@Immutable
data class LevelRow(
    val label: String,
    val note: String,
    val value: String,
)

/** The sheet of one axis (design/companion.md §4.14, "Detail of a duration"). */
@Immutable
data class AxisDetail(
    val axis: Int,
    val title: String,
    val skill: String,
    val rows: List<AxisValueRow>,
    val level: LevelRow?,
)

/** One value in the skill sheet's table: the watts over their date, and the ride a tap opens. */
@Immutable
data class SkillCell(
    val watts: String,
    val date: String,
    val rideId: String,
    val description: String,
)

/** One duration of a skill in its sheet: the window's value and all time's. */
@Immutable
data class SkillDetailRow(
    val duration: String,
    val window: SkillCell?,
    val allTime: SkillCell?,
)

/** The sheet of one skill (design/companion.md §4.14, "Detail of a skill"). */
@Immutable
data class SkillDetail(
    val id: PowerSkillId,
    val title: String,
    val subtitle: String,
    val windowColumn: String,
    /** Null when All time is the window: one column. */
    val allTimeColumn: String?,
    val rows: List<SkillDetailRow>,
    /** "4.4 W/kg for 5 min and 4.0 W/kg for 10 min at 72.0 kg.", or null without weight. */
    val perKgSentence: String?,
    val hint: String,
)

/** One skill row (design/companion.md §4.14 item 7). */
@Immutable
data class SkillRowUi(
    val id: PowerSkillId,
    val color: Color,
    val name: String,
    val span: String,
    /** "Strong · level 5 of 9", or null (no weight, or no data). */
    val levelLine: String?,
    /** 1 to 9 for the bar, 0 for no bar. */
    val level: Int,
    /** "930", or null for "—" (no data). */
    val watts: String?,
    /** "12.9 W/kg · 15 s", "15 s" without weight. */
    val caption: String?,
    /** "No ride of 45 min or longer", when there is no data for the skill. */
    val noData: String?,
    val description: String,
)

/** What the legend's keys say: the window's name (text 1) and, unless All time is the window, "All time" (text 3). */
@Immutable
data class LegendKey(
    val name: String,
    val primary: Boolean,
)

/** The radar, the rows and the sheets of a window that has data. */
@Immutable
class PowerSkillsContent(
    val spec: RadarSpec,
    val legend: List<LegendKey>,
    val note: String,
    val rows: List<SkillRowUi>,
    val closing: String,
    /** TalkBack's text alternative of the radar (design/companion.md §8). */
    val description: String,
    val axes: List<AxisDetail>,
    val skills: List<SkillDetail>,
    val table: ValuesTable,
    val hasWeight: Boolean,
)

/** One duration in the list that stands in for a radar with fewer than three of them: the duration, the watts and the date, opening the ride. */
@Immutable
data class DurationRow(
    val duration: String,
    val watts: String,
    val date: String,
    val rideId: String,
    val description: String,
)

/** What the card shows below its control (design/companion.md §4.14, States). */
sealed interface PowerSkillsBody {
    /** Nothing cached and nothing answered yet. */
    data object Loading : PowerSkillsBody

    /** Nothing cached and the last request failed: the cause is in the tab's problem line. */
    data object NotLoaded : PowerSkillsBody

    /** No ride with power was ever uploaded: heading and sentence, no control, no frame. */
    data object NoRidesYet : PowerSkillsBody

    /** The window holds no ride. */
    data class EmptyWindow(
        val text: String,
    ) : PowerSkillsBody

    /** Fewer than three durations have data: a list in place of the radar. */
    data class Few(
        val text: String,
        val rows: List<DurationRow>,
        val table: ValuesTable,
    ) : PowerSkillsBody

    data class Radar(
        val content: PowerSkillsContent,
    ) : PowerSkillsBody
}

/** The whole card: the control's state, the range line and the body. */
@Immutable
data class PowerSkillsCardUi(
    val window: PowerSkillsWindow,
    val selected: Int,
    val rangeLine: String?,
    val body: PowerSkillsBody,
) {
    /** The Values table, when the body has one. */
    val table: ValuesTable?
        get() =
            when (body) {
                is PowerSkillsBody.Radar -> body.content.table
                is PowerSkillsBody.Few -> body.table
                else -> null
            }
}

/** The Power skills card from one resource's state and the window it is for. */
object PowerSkillsUi {
    private const val MIN_DURATIONS = 3
    private const val LEVELS = 9.0
    private const val MIN_SHARE = 0.5

    /**
     * @param state the resource of [window].
     * @param words the phone's words and today.
     */
    fun of(
        state: ResourceState<PowerSkillsResponse>,
        window: PowerSkillsWindow,
        words: Words,
    ): PowerSkillsCardUi {
        val response = state.value
        val custom = window as? PowerSkillsWindow.Custom
        val line = custom?.let { SkillWindows.rangeLine(it, response?.window?.rideCount, words) }
        val body =
            when {
                response == null && state.problem != null -> PowerSkillsBody.NotLoaded
                response == null -> PowerSkillsBody.Loading
                else -> bodyOf(response, window, words)
            }
        return PowerSkillsCardUi(window, SkillWindows.index(window), line, body)
    }

    private fun bodyOf(
        response: PowerSkillsResponse,
        window: PowerSkillsWindow,
        words: Words,
    ): PowerSkillsBody {
        val withData = response.durations.filter { it.window != null }
        return when {
            window !is PowerSkillsWindow.Custom &&
                response.durations.all {
                    it.allTime == null
                }
            -> PowerSkillsBody.NoRidesYet

            withData.isEmpty() -> PowerSkillsBody.EmptyWindow(emptySentence(window, response, words))

            withData.size < MIN_DURATIONS -> few(withData, response, window, words)

            else -> PowerSkillsBody.Radar(content(response, window, words))
        }
    }

    /** "No rides on the tablet between 2 Aug and 9 Aug. Choose another range." and its 8-weeks and All-time forms. */
    fun emptySentence(
        window: PowerSkillsWindow,
        response: PowerSkillsResponse,
        words: Words,
    ): String =
        when (window) {
            is PowerSkillsWindow.Custom -> {
                "No rides on the tablet between ${words.dayShort(window.from)} and ${words.dayShort(window.to)}. " +
                    "Choose another range."
            }

            PowerSkillsWindow.EightWeeks -> {
                "No rides on the tablet in the last 8 weeks. Choose All time or a custom range."
            }

            PowerSkillsWindow.AllTime -> {
                "No rides on the tablet since ${words.dayShort(response.window.from)}. Choose another range."
            }
        }

    private fun few(
        withData: List<PowerSkillDuration>,
        response: PowerSkillsResponse,
        window: PowerSkillsWindow,
        words: Words,
    ): PowerSkillsBody.Few {
        val rows =
            withData.map { row ->
                val effort = checkNotNull(row.window)
                DurationRow(
                    SkillWords.duration(row.seconds),
                    SkillWords.watts(effort.watts),
                    words.day(effort.date),
                    effort.rideId,
                    "${SkillWords.spoken(row.seconds)}, ${SkillWords.watts(effort.watts)} watts, " +
                        "${words.day(effort.date)}, " +
                        "${effort.rideName}, opens the ride",
                )
            }
        return PowerSkillsBody.Few(
            "Ride longer to see the shape: the radar needs at least three durations.",
            rows,
            valuesTable(response, window, words),
        )
    }

    // region the radar

    /** The radius, 0 to 1, of [effort] on the level scale (with weight), or NaN for no data. */
    fun levelRadius(effort: PowerSkillEffort?): Double =
        effort?.score?.let { (it / LEVELS).coerceIn(0.0, 1.0) } ?: Double.NaN

    /** The radius of the window's [effort] as a share of the best ever (without weight): 50% is the centre, 100% the edge. */
    fun shareRadius(
        effort: PowerSkillEffort?,
        best: PowerSkillEffort?,
    ): Double {
        if (effort == null || best == null || best.watts <= 0.0) return Double.NaN
        return ((effort.watts / best.watts - MIN_SHARE) / MIN_SHARE).coerceIn(0.0, 1.0)
    }

    /** The share of the best ever as a whole percentage, for TalkBack: 93. */
    fun sharePercent(
        effort: PowerSkillEffort,
        best: PowerSkillEffort,
    ): Int = (effort.watts / best.watts * PERCENT).roundToInt()

    private const val PERCENT = 100.0

    private fun content(
        response: PowerSkillsResponse,
        window: PowerSkillsWindow,
        words: Words,
    ): PowerSkillsContent {
        val hasWeight = response.riderWeight != null
        val allTimeIsWindow = window == PowerSkillsWindow.AllTime
        val main =
            DoubleArray(POWER_SKILL_DURATIONS.size) { i ->
                val row = response.durations[i]
                if (hasWeight) levelRadius(row.window) else shareRadius(row.window, row.allTime)
            }
        // Without weight the all-time outline is the edge itself and is not drawn twice (design/companion.md §6.11).
        val reference =
            if (allTimeIsWindow || !hasWeight) {
                null
            } else {
                DoubleArray(POWER_SKILL_DURATIONS.size) { levelRadius(response.durations[it].allTime) }
            }
        val labels =
            response.durations.map { row ->
                RadarLabel(SkillWords.duration(row.seconds), row.window?.let { SkillWords.watts(it.watts) }, "W")
            }
        val segments =
            PowerSkillId.entries.map { id ->
                val axes = id.durations.map { POWER_SKILL_DURATIONS.indexOf(it) }
                RadarSegment(axes.min(), axes.max(), SkillWords.color(id))
            }
        val spec =
            RadarSpec(
                labels,
                main,
                reference,
                if (hasWeight) listOf(RING_THIRD, RING_TWO_THIRDS) else listOf(RING_HALF),
                segments,
                wash = main.none { it.isNaN() },
            )
        val legend =
            buildList {
                add(LegendKey(SkillWindows.legend(window, words), true))
                if (!allTimeIsWindow) add(LegendKey("All time", false))
            }
        return PowerSkillsContent(
            spec = spec,
            legend = legend,
            note =
                if (hasWeight) {
                    "Rings mark levels 3, 6 and 9. Tap a duration or a skill for its rides."
                } else {
                    "Each axis is a share of your best ever: the ring is your best, the inner ring 75%, the centre 50%."
                },
            rows = PowerSkillId.entries.map { row(response, response.skill(it), words) },
            closing = closing(response),
            description = description(response, window, words),
            axes = response.durations.indices.map { axisDetail(response, it, window, words) },
            skills = PowerSkillId.entries.map { skillDetail(response, response.skill(it), window, words) },
            table = valuesTable(response, window, words),
            hasWeight = hasWeight,
        )
    }

    private const val RING_THIRD = 1f / 3f
    private const val RING_TWO_THIRDS = 2f / 3f
    private const val RING_HALF = 0.5f

    /** The two sentences under the rows: the approximation or the missing weight, then the outdoor rule (design/companion.md §4.14 item 8). */
    fun closing(response: PowerSkillsResponse): String {
        val weight = response.riderWeight
        val outdoor = "Outdoor rides have no power, so only rides from the tablet count."
        return if (weight != null) {
            "Levels are approximate: your watts per kilogram (${SkillWords.kg(weight.kg)}, 7-day average) " +
                "against published " +
                "power-profile tables. $outdoor"
        } else {
            "Levels need your weight. Weigh yourself with a scale that writes to Health Connect and they appear " +
                "here. $outdoor"
        }
    }

    /** The key duration of [skill]: the design's, else the next one of the skill that has data in the window; null when none has. */
    fun rowDuration(
        response: PowerSkillsResponse,
        skill: PowerSkillId,
    ): PowerSkillDuration? {
        val order =
            listOf(SkillWords.keyDuration(skill)) + skill.durations.filter { it != SkillWords.keyDuration(skill) }
        return order.map { response.duration(it) }.firstOrNull { it.window != null }
    }

    private fun row(
        response: PowerSkillsResponse,
        skill: PowerSkill,
        words: Words,
    ): SkillRowUi {
        val id = skill.id
        val key = rowDuration(response, id)
        val effort = key?.window
        val name = id.title
        val span = SkillWords.span(id)
        val color = SkillWords.color(id)
        if (key == null || effort == null) {
            val noData = "No ride of ${SkillWords.duration(id.fromSeconds)} or longer"
            return SkillRowUi(
                id,
                color,
                name,
                span,
                levelLine = null,
                level = 0,
                watts = null,
                caption = SkillWords.duration(SkillWords.keyDuration(id)),
                noData = noData,
                description = "$name, ${SkillWords.spokenSpan(id)}. $noData. Opens details.",
            )
        }
        val level = skill.level?.index
        val perKg = effort.wattsPerKg
        val caption = listOfNotNull(perKg?.let(SkillWords::perKg), SkillWords.duration(key.seconds)).joinToString(" · ")
        val spokenBest =
            "Best ${SkillWords.watts(effort.watts)} watts" +
                (perKg?.let { ", ${UiFormat.decimal(it, 1)} watts per kilogram" } ?: "") +
                ", at ${SkillWords.spoken(key.seconds)}"
        return SkillRowUi(
            id = id,
            color = color,
            name = name,
            span = span,
            levelLine = level?.let(SkillWords::levelLine),
            level = level ?: 0,
            watts = SkillWords.watts(effort.watts),
            caption = caption,
            noData = null,
            description =
                listOfNotNull(
                    "$name, ${SkillWords.spokenSpan(id)}.",
                    level?.let { "${SkillWords.levelLine(it)}." },
                    "$spokenBest. Opens details.",
                ).joinToString(" "),
        )
    }

    /** TalkBack's reading of the radar: every duration in axis order with its watts, and what the rings mean (design/companion.md §8). */
    fun description(
        response: PowerSkillsResponse,
        window: PowerSkillsWindow,
        words: Words,
    ): String {
        val hasWeight = response.riderWeight != null
        val allTimeIsWindow = window == PowerSkillsWindow.AllTime
        val head =
            when (window) {
                PowerSkillsWindow.EightWeeks -> {
                    "Power skills, last 8 weeks against all time."
                }

                PowerSkillsWindow.AllTime -> {
                    "Power skills, all time."
                }

                is PowerSkillsWindow.Custom -> {
                    "Power skills, ${SkillWindows.short(window.from, window.to, words)} against all time."
                }
            }
        val parts =
            response.durations.map { row ->
                val effort = row.window
                val best = row.allTime
                val name = SkillWords.spoken(row.seconds)
                when {
                    effort == null -> {
                        "$name, no ride."
                    }

                    allTimeIsWindow -> {
                        "$name ${SkillWords.watts(effort.watts)} watts."
                    }

                    !hasWeight && best != null -> {
                        "$name ${SkillWords.watts(effort.watts)} watts, ${sharePercent(effort, best)}% of your best."
                    }

                    else -> {
                        "$name ${SkillWords.watts(effort.watts)} watts, " +
                            "all time ${row.allTime?.let { SkillWords.watts(it.watts) }}."
                    }
                }
            }
        val tail =
            if (hasWeight) {
                "Rings mark levels 3, 6 and 9."
            } else {
                "The ring is your best ever, the inner ring 75%, the centre 50%."
            }
        return (listOf(head) + parts + tail).joinToString(" ")
    }

    // region sheets

    private fun cellOf(
        effort: PowerSkillEffort,
        words: Words,
    ) = EffortCell(
        SkillWords.watts(effort.watts),
        effort.wattsPerKg?.let(SkillWords::perKg),
        words.day(effort.date),
        RideRef(effort.rideId, "${words.day(effort.date)} · ${effort.rideName}"),
    )

    private fun skillOf(seconds: Int): PowerSkillId = PowerSkillId.entries.first { seconds in it.durations }

    private fun axisDetail(
        response: PowerSkillsResponse,
        axis: Int,
        window: PowerSkillsWindow,
        words: Words,
    ): AxisDetail {
        val row = response.durations[axis]
        val skillId = skillOf(row.seconds)
        val duration = SkillWords.duration(row.seconds)
        val rows = ArrayList<AxisValueRow>()
        val windowEffort = row.window
        val inWindow =
            when (window) {
                PowerSkillsWindow.EightWeeks -> "in these 8 weeks"
                PowerSkillsWindow.AllTime -> "yet"
                is PowerSkillsWindow.Custom -> "in this range"
            }
        rows +=
            AxisValueRow(
                SkillWindows.row(window, words),
                windowEffort?.let { cellOf(it, words) },
                if (windowEffort == null) "No ride of $duration or longer $inWindow" else null,
            )
        val best = row.allTime
        if (window != PowerSkillsWindow.AllTime && best != null) {
            rows += AxisValueRow("All time", cellOf(best, words), null)
        }
        val level = response.skill(skillId).level
        val weight = response.riderWeight
        return AxisDetail(
            axis,
            duration,
            skillId.title,
            rows,
            if (level != null && weight != null) {
                LevelRow(
                    "Level, ${SkillWindows.inSentence(window, words)}",
                    "Approximate, at ${SkillWords.kg(weight.kg)}",
                    SkillWords.levelShort(level.index),
                )
            } else {
                null
            },
        )
    }

    private fun skillDetail(
        response: PowerSkillsResponse,
        skill: PowerSkill,
        window: PowerSkillsWindow,
        words: Words,
    ): SkillDetail {
        val id = skill.id
        val weight = response.riderWeight
        val allTimeIsWindow = window == PowerSkillsWindow.AllTime
        val level = skill.level
        val span = SkillWords.span(id)
        val inWindow = SkillWindows.inSentence(window, words)
        val subtitle =
            if (level == null) {
                span
            } else {
                "$span · ${SkillWords.levelWord(level.index)}, level ${level.index} of ${POWER_LEVEL_NAMES.size} " +
                    "in $inWindow"
            }

        fun cell(
            effort: PowerSkillEffort?,
            seconds: Int,
        ): SkillCell? =
            effort?.let {
                SkillCell(
                    SkillWords.watts(it.watts),
                    words.dayShort(it.date),
                    it.rideId,
                    "${SkillWords.spoken(seconds)}, ${SkillWords.watts(it.watts)} watts, ${words.day(it.date)}, " +
                        "${it.rideName}, opens the ride",
                )
            }
        val rows =
            id.durations.map { seconds ->
                val duration = response.duration(seconds)
                SkillDetailRow(
                    SkillWords.duration(seconds),
                    cell(duration.window, seconds),
                    if (allTimeIsWindow) null else cell(duration.allTime, seconds),
                )
            }
        val perKgParts =
            id.durations.mapNotNull { seconds ->
                response
                    .duration(
                        seconds,
                    ).window
                    ?.wattsPerKg
                    ?.let { "${SkillWords.perKg(it)} for ${SkillWords.duration(seconds)}" }
            }
        val sentence =
            if (weight == null || perKgParts.isEmpty()) {
                null
            } else {
                "${perKgParts.joinToString(", ").let { joined ->
                    if (perKgParts.size > 1) joined.replaceLast(", ", " and ") else joined
                }} at ${SkillWords.kg(weight.kg)}."
            }
        return SkillDetail(
            id,
            id.title,
            subtitle,
            if (window is PowerSkillsWindow.Custom) "Range" else SkillWindows.legend(window, words),
            if (allTimeIsWindow) null else "All time",
            rows,
            sentence,
            "Tap a value to open its ride.",
        )
    }

    private fun String.replaceLast(
        old: String,
        new: String,
    ): String {
        val at = lastIndexOf(old)
        return if (at < 0) this else substring(0, at) + new + substring(at + old.length)
    }

    /** The Values sheet: "Duration", the window, "All time" and "W/kg" with the weight; twelve rows in axis order, "—" for no data. */
    fun valuesTable(
        response: PowerSkillsResponse,
        window: PowerSkillsWindow,
        words: Words,
    ): ValuesTable {
        val hasWeight = response.riderWeight != null
        val allTimeIsWindow = window == PowerSkillsWindow.AllTime
        val columns =
            buildList {
                add(
                    when (window) {
                        PowerSkillsWindow.EightWeeks -> "8 weeks"
                        PowerSkillsWindow.AllTime -> "All time"
                        is PowerSkillsWindow.Custom -> "Range"
                    },
                )
                if (!allTimeIsWindow) add("All time")
                if (hasWeight) add("W/kg")
            }
        val rows =
            response.durations.map { row ->
                ValuesRow(
                    row.seconds.toString(),
                    SkillWords.duration(row.seconds),
                    buildList {
                        add(row.window?.let { SkillWords.watts(it.watts) } ?: UiFormat.EMPTY)
                        if (!allTimeIsWindow) add(row.allTime?.let { SkillWords.watts(it.watts) } ?: UiFormat.EMPTY)
                        if (hasWeight) add(row.window?.wattsPerKg?.let { UiFormat.decimal(it, 1) } ?: UiFormat.EMPTY)
                    },
                )
            }
        return ValuesTable("Power skills", "Duration", columns, rows)
    }
}
