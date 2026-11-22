package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.effort.EffortActivity
import app.overdrive.core.effort.EffortKind
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.effort.EffortVerdict
import app.overdrive.core.effort.EffortWeek
import app.overdrive.core.outdoor.OUTDOOR_RIDE_SPORT
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.remote.ResourceState
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.chart.AxisTick
import app.overdrive.uikit.chart.ColumnSpec
import app.overdrive.uikit.chart.DayStripSpec
import app.overdrive.uikit.chart.ValuesRow
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The Effort card of the Fitness tab (design/companion.md §4.15, §6.12, §7.8, ADR 0042) as pure functions: the verdict in words, the week
 * line, the twelve columns with the usual range that moves with the selected week, the seven days, the activity rows and every state of
 * the card. The service computes every figure; the phone owns the words. Every week's view is made once, so a selection only picks one.
 */

/** Where an activity row comes from (design/companion.md §4.6): the tablet, the watch (`heart-pulse`) or an outdoor ride (`bike`). */
enum class EffortSource {
    Tablet,
    Watch,
    Outdoor,
}

/** One activity row of a week: the name over the day, the load over its share (design/companion.md §4.15 item 7). */
@Immutable
data class EffortRowUi(
    val id: String,
    val kind: EffortKind,
    val name: String,
    val source: EffortSource,
    /** "Mon 16", "Watch · Wed 11", "Outdoor · Sun 15". */
    val dayLine: String,
    /** "107", or "—" for an activity without load. */
    val load: String,
    /** "27%", "7% · approximate", or "no load". */
    val share: String,
    /** "No heart rate in this ride, so it adds no load.", for an activity without load. */
    val note: String?,
    val description: String,
)

/** The week line: "This week · load 97 so far" (the load in 600), then the band key and "Usual 298–510". */
@Immutable
data class WeekLineUi(
    val lead: String,
    val load: String,
    val tail: String,
    /** "Usual 298–510", or null while learning. */
    val usual: String?,
    /** A line that is plain text with no band key ("This week · load 0 · usual 334–511"), or null. */
    val plain: String? = null,
) {
    /** The line as TalkBack says it. */
    fun spoken(): String = plain ?: (lead + load + tail + (usual?.let { ". $it" } ?: ""))
}

/** Everything the card shows for one selected week. */
@Immutable
data class EffortWeekView(
    val weekStart: String,
    val thisWeek: Boolean,
    val verdict: String,
    val line: WeekLineUi,
    /** "Your usual range appears after 8 weeks of training. 5 so far.", while learning. */
    val learning: String?,
    /** "This week, by day" / "Week of 9 Nov, by day". */
    val subheading: String,
    val days: DayStripSpec,
    val daysDescription: String,
    val rows: List<EffortRowUi>,
    /** "No rides or activities yet this week.", when there are none. */
    val noRows: String?,
)

/** The chart and the weeks' views of a card that has load. */
@Immutable
class EffortContent(
    val spec: ColumnSpec,
    val weeks: List<EffortWeekView>,
    /** The last week of the data is the week in progress by the phone's clock: "Now" is under it and the pill says "This week". */
    val lastIsThisWeek: Boolean,
    /** The chart's text alternative without the selection (design/companion.md §8). */
    val summary: String,
    val table: ValuesTable,
) {
    val restIndex: Int get() = weeks.lastIndex

    /** The week a [ColumnSpec] selection x (the column's index) means. */
    fun viewAt(x: Double?): EffortWeekView = weeks[indexOf(x)]

    fun indexOf(x: Double?): Int = ((x ?: restIndex.toDouble()).roundToInt()).coerceIn(0, restIndex)
}

/** What the card shows (design/companion.md §4.15, States). */
sealed interface EffortBody {
    data object Loading : EffortBody

    /** Nothing cached and the last request failed: the cause is in the tab's problem line. */
    data object NotLoaded : EffortBody

    /** No load ever: heading and sentence, no chart. */
    data object NoEffort : EffortBody

    data class Content(
        val content: EffortContent,
    ) : EffortBody
}

@Immutable
data class EffortCardUi(
    val body: EffortBody,
)

object EffortUi {
    /** The card draws the last 12 weeks; Values draws all the service sends. */
    const val CARD_WEEKS = 12

    private const val LABEL_WEEKS = 6
    private const val SHARE_PERCENT = 100.0
    private const val BIG_WEEK = 400.0
    private const val STEP = 100.0
    private const val BIG_STEP = 200.0

    fun of(
        state: ResourceState<EffortResponse>,
        words: Words,
    ): EffortCardUi {
        val response = state.value
        return EffortCardUi(
            when {
                response == null && state.problem != null -> EffortBody.NotLoaded
                response == null -> EffortBody.Loading
                response.weeks.all { it.activities.isEmpty() } -> EffortBody.NoEffort
                else -> EffortBody.Content(content(response, words))
            },
        )
    }

    /** The Monday of the week that holds [date], a local day. */
    fun mondayOf(date: String): String = PlanCalendar.addDays(date, -(PlanCalendar.dayOfWeek(date) - 1))

    /** The verdict in words (design/companion.md §7.8): the service sends a key, the phone owns the words. */
    fun verdictWords(
        verdict: EffortVerdict,
        thisWeek: Boolean,
    ): String =
        when (verdict) {
            EffortVerdict.ABOVE -> "Above your usual range"
            EffortVerdict.WITHIN -> "Within your usual range"
            EffortVerdict.BELOW -> if (thisWeek) "Below your usual range so far" else "Below your usual range"
            EffortVerdict.NONE -> if (thisWeek) "Nothing yet this week" else "Nothing recorded that week"
            EffortVerdict.LEARNING -> "Learning your usual range"
        }

    /** The table's short word: "Above", "Within", "Below", "—". */
    fun verdictShort(verdict: EffortVerdict): String =
        when (verdict) {
            EffortVerdict.ABOVE -> "Above"
            EffortVerdict.WITHIN -> "Within"
            EffortVerdict.BELOW -> "Below"
            EffortVerdict.NONE, EffortVerdict.LEARNING -> UiFormat.EMPTY
        }

    /** "298–510": the usual range, whole numbers. */
    fun usualText(week: EffortWeek): String? {
        val low = week.usualLow ?: return null
        val high = week.usualHigh ?: return null
        return UiFormat.range(low.roundToInt(), high.roundToInt())
    }

    /** The y axis: 0 to a clean maximum over [maxLoad], a step of 100, or 200 above 400 (design/companion.md §6.12). Returns the max and the step. */
    fun axis(maxLoad: Double): Pair<Double, Double> {
        val step = if (maxLoad > BIG_WEEK) BIG_STEP else STEP
        val top = max(step, kotlin.math.ceil(maxLoad / step) * step)
        return top to step
    }

    private fun content(
        response: EffortResponse,
        words: Words,
    ): EffortContent {
        val all = response.weeks
        val shown = all.takeLast(CARD_WEEKS)
        val thisMonday = mondayOf(words.today())
        val lastIsThisWeek = shown.last().current && shown.last().weekStart == thisMonday
        val views =
            shown.mapIndexed {
                index,
                week,
                ->
                view(week, thisWeek = lastIsThisWeek && index == shown.lastIndex, today = words.today(), words = words)
            }
        val loads = DoubleArray(shown.size) { shown[it].load }
        val highs = shown.mapNotNull { it.usualHigh }
        val (yMax, step) = axis(max(loads.max(), highs.maxOrNull() ?: 0.0))
        val ticks =
            generateSequence(0.0) {
                it + step
            }.takeWhile { it <= yMax + 1e-9 }.map { AxisTick(it, UiFormat.signed(it.roundToInt())) }.toList()
        val xTicks =
            listOf(0, LABEL_WEEKS)
                .filter {
                    it < shown.size - 1
                }.map { AxisTick(it.toDouble(), words.dayShort(shown[it].weekStart)) }
        val spec =
            ColumnSpec(
                starts = DoubleArray(shown.size) { it.toDouble() },
                values = loads,
                selectXs = DoubleArray(shown.size) { it.toDouble() },
                xTicks = xTicks,
                yMax = yMax,
                yTicks = ticks,
                restX = shown.lastIndex.toDouble(),
                heightDp = CHART_HEIGHT,
                valueLabel = { UiFormat.signed(it.roundToInt()) },
                bandLows = DoubleArray(shown.size) { shown[it].usualLow ?: Double.NaN },
                bandHighs = DoubleArray(shown.size) { shown[it].usualHigh ?: Double.NaN },
                nowLabel = if (lastIsThisWeek) "Now" else null,
                labelHighest = false,
            )
        return EffortContent(
            spec,
            views,
            lastIsThisWeek,
            summary(shown, lastIsThisWeek, words),
            valuesTable(all, lastIsThisWeek, words),
        )
    }

    private const val CHART_HEIGHT = 160f

    private fun view(
        week: EffortWeek,
        thisWeek: Boolean,
        today: String,
        words: Words,
    ): EffortWeekView {
        val usual = usualText(week)
        val load = week.load.roundToInt().toString()
        val approximate = if (week.approximate) " (approximate)" else ""
        val line =
            when {
                thisWeek && week.verdict == EffortVerdict.NONE && usual != null -> {
                    WeekLineUi("", "", "", null, plain = "This week · load 0 · usual $usual")
                }

                thisWeek -> {
                    WeekLineUi("This week · load ", load, " so far$approximate", usual?.let { "Usual $it" })
                }

                else -> {
                    WeekLineUi(
                        "Week of ${words.dayShort(week.weekStart)} · load ",
                        load,
                        approximate,
                        usual?.let { "Usual $it" },
                    )
                }
            }
        val todayIndex = if (thisWeek) week.days.indexOfFirst { it.date == today } else -1
        val names = words.weekdayNames()
        val days = DayStripSpec(names, DoubleArray(DayStripSpec.DAYS) { week.days[it].load }, todayIndex)
        val rows = week.activities.map { row(it, words) }
        return EffortWeekView(
            weekStart = week.weekStart,
            thisWeek = thisWeek,
            verdict = verdictWords(week.verdict, thisWeek),
            line = line,
            learning =
                if (week.verdict == EffortVerdict.LEARNING) {
                    "Your usual range appears after 8 weeks of training. ${week.weeksLogged} so far."
                } else {
                    null
                },
            subheading = if (thisWeek) "This week, by day" else "Week of ${words.dayShort(week.weekStart)}, by day",
            days = days,
            daysDescription = daysDescription(week, todayIndex, words),
            rows = rows,
            noRows =
                if (rows.isEmpty()) {
                    if (thisWeek) "No rides or activities yet this week." else "No rides or activities that week."
                } else {
                    null
                },
        )
    }

    /** "Monday 97, Tuesday 0, Wednesday, today, …": the days as one node (design/companion.md §8). */
    fun daysDescription(
        week: EffortWeek,
        todayIndex: Int,
        words: Words,
    ): String =
        week.days.withIndex().joinToString(", ") { (i, day) ->
            val name = words.dayLong(day.date).substringBefore(' ')
            when {
                i == todayIndex -> "$name, today"
                todayIndex in 0 until i -> name
                else -> "$name ${day.load.roundToInt()}"
            }
        }

    private fun row(
        activity: EffortActivity,
        words: Words,
    ): EffortRowUi {
        val source =
            when {
                activity.kind == EffortKind.RIDE -> EffortSource.Tablet
                activity.sport == OUTDOOR_RIDE_SPORT -> EffortSource.Outdoor
                else -> EffortSource.Watch
            }
        val day = words.dayOfMonth(activity.date)
        val dayLine =
            when (source) {
                EffortSource.Tablet -> day
                EffortSource.Watch -> "Watch · $day"
                EffortSource.Outdoor -> "Outdoor · $day"
            }
        val load = activity.load
        val share = activity.share
        val shareText =
            if (load == null || share == null) {
                "no load"
            } else {
                "${(share * SHARE_PERCENT).roundToInt()}%" + if (activity.approximate) " · approximate" else ""
            }
        val spoken =
            buildString {
                append(activity.name).append(", ").append(words.dayLong(activity.date).substringBeforeLast(' '))
                if (load == null || share == null) {
                    append(", no load")
                } else {
                    append(
                        ", load ",
                    ).append(
                        load.roundToInt(),
                    ).append(", ")
                        .append((share * SHARE_PERCENT).roundToInt())
                        .append("% of the week")
                    if (activity.approximate) append(", approximate")
                }
                append(", opens the ").append(if (activity.kind == EffortKind.RIDE) "ride" else "activity")
            }
        return EffortRowUi(
            id = activity.id,
            kind = activity.kind,
            name = activity.name,
            source = source,
            dayLine = dayLine,
            load = if (load == null) UiFormat.EMPTY else words.load(load),
            share = shareText,
            note = if (load == null) "No heart rate in this ride, so it adds no load." else null,
            description = spoken,
        )
    }

    /** The chart's text alternative: "Effort, last 12 weeks. This week so far 97, below your usual range of 334 to 511. Last week 395, within. Highest 579, week of 12 Oct." */
    fun summary(
        shown: List<EffortWeek>,
        lastIsThisWeek: Boolean,
        words: Words,
    ): String {
        val last = shown.last()
        val before = shown.getOrNull(shown.lastIndex - 1)
        val highest = shown.maxBy { it.load }
        val range = if (last.usualLow != null && last.usualHigh != null) usualWords(last) else null
        val first =
            if (lastIsThisWeek) {
                val where =
                    when (last.verdict) {
                        EffortVerdict.BELOW -> "below $range".takeIf { range != null }
                        EffortVerdict.WITHIN -> "within $range".takeIf { range != null }
                        EffortVerdict.ABOVE -> "above $range".takeIf { range != null }
                        EffortVerdict.NONE -> "nothing yet".takeIf { range != null }
                        EffortVerdict.LEARNING -> "learning your usual range"
                    }
                "This week so far ${last.load.roundToInt()}" + (where?.let { ", $it" } ?: "") + "."
            } else {
                "Week of ${words.dayShort(
                    last.weekStart,
                )} ${last.load.roundToInt()}, ${verdictWords(last.verdict, false).lowercase()}."
            }
        val second =
            before?.let {
                val word =
                    when (it.verdict) {
                        EffortVerdict.ABOVE -> ", above"
                        EffortVerdict.WITHIN -> ", within"
                        EffortVerdict.BELOW -> ", below"
                        else -> ""
                    }
                " Last week ${it.load.roundToInt()}$word."
            } ?: ""
        val top =
            if (highest.load >
                0.0
            ) {
                " Highest ${highest.load.roundToInt()}, week of ${words.dayShort(highest.weekStart)}."
            } else {
                ""
            }
        return "Effort, last ${shown.size} weeks. $first$second$top"
    }

    private fun usualWords(week: EffortWeek): String =
        "your usual range of ${week.usualLow!!.roundToInt()} to ${week.usualHigh!!.roundToInt()}"

    /** The Values sheet: every week the service sent, newest first ("16 Nov · so far"). */
    fun valuesTable(
        weeks: List<EffortWeek>,
        lastIsThisWeek: Boolean,
        words: Words,
    ): ValuesTable =
        ValuesTable(
            "Effort",
            "Week",
            listOf("Load", "Usual", "Verdict"),
            weeks.reversed().mapIndexed { n, week ->
                val soFar = n == 0 && lastIsThisWeek
                ValuesRow(
                    week.weekStart,
                    words.dayShort(week.weekStart) + if (soFar) " · so far" else "",
                    listOf(
                        week.load.roundToInt().toString(),
                        usualText(week) ?: UiFormat.EMPTY,
                        verdictShort(week.verdict),
                    ),
                )
            },
        )
}
