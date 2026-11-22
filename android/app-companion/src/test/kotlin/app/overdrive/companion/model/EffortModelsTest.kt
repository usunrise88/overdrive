package app.overdrive.companion.model

import app.overdrive.companion.ChartScreenSamples
import app.overdrive.companion.ChartScreenSamples.Act
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.effort.EffortKind
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.effort.EffortVerdict
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Effort card as pure functions (design/companion.md §4.15, §6.12, §7.8, §8): the verdict words, the week line, the columns with the
 * usual range that moves with the selected week, the seven days, the activity rows and every state, from the synthetic answers. The test
 * clock is Wednesday 18 November 2026, so the week in progress begins on Monday 16 November.
 */
class EffortModelsTest {
    private val words = TestClock.words

    private fun body(
        response: EffortResponse,
        words: Words = this.words,
    ) = EffortUi.of(fresh(response), words).body

    private fun content(response: EffortResponse = ChartScreenSamples.effort()) =
        (body(response) as EffortBody.Content).content

    // region the verdict

    @Test
    fun `the verdict is worded by the phone, and only the week in progress says so far`() {
        assertEquals("Above your usual range", EffortUi.verdictWords(EffortVerdict.ABOVE, true))
        assertEquals("Above your usual range", EffortUi.verdictWords(EffortVerdict.ABOVE, false))
        assertEquals("Within your usual range", EffortUi.verdictWords(EffortVerdict.WITHIN, true))
        assertEquals("Within your usual range", EffortUi.verdictWords(EffortVerdict.WITHIN, false))
        assertEquals("Below your usual range so far", EffortUi.verdictWords(EffortVerdict.BELOW, true))
        assertEquals("Below your usual range", EffortUi.verdictWords(EffortVerdict.BELOW, false))
        assertEquals("Nothing yet this week", EffortUi.verdictWords(EffortVerdict.NONE, true))
        assertEquals("Learning your usual range", EffortUi.verdictWords(EffortVerdict.LEARNING, true))
        assertEquals("Learning your usual range", EffortUi.verdictWords(EffortVerdict.LEARNING, false))
        assertEquals(listOf("Above", "Within", "Below", "—", "—"), EffortVerdict.entries.map(EffortUi::verdictShort))
        // The words never judge.
        val all =
            EffortVerdict.entries.flatMap {
                listOf(
                    EffortUi.verdictWords(it, true),
                    EffortUi.verdictWords(it, false),
                )
            }
        for (word in listOf("good", "bad", "overtraining", "too much")) {
            assertTrue(
                all.none {
                    it.contains(word, ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun `at rest the card shows this week with its verdict, the week line and the usual range`() {
        val c = content()
        val view = c.viewAt(null)
        assertEquals("Below your usual range so far", view.verdict)
        assertEquals(WeekLineUi("This week · load ", "97", " so far", "Usual 334–511"), view.line)
        assertTrue(view.thisWeek)
        assertTrue(c.lastIsThisWeek)
        assertEquals(11, c.restIndex)
        assertEquals("This week, by day", view.subheading)
    }

    @Test
    fun `a selected past week shows its own verdict, line, days and rows, and approximate marks`() {
        val c = content()
        val view = c.viewAt(10.0)
        assertEquals("Within your usual range", view.verdict)
        assertEquals(WeekLineUi("Week of 9 Nov · load ", "395", " (approximate)", "Usual 298–510"), view.line)
        assertEquals("Week of 9 Nov, by day", view.subheading)
        assertFalse(view.thisWeek)
        assertEquals(
            listOf(
                "Threshold 2×20",
                "Endurance 90",
                "Beat Saber",
                "VO2 5×3",
                "Free ride",
                "Beat Saber",
            ),
            view.rows.map {
                it.name
            },
        )
        assertEquals(listOf("107", "74", "27", "68", "81", "38"), view.rows.map { it.load })
        assertEquals(
            listOf("27%", "19%", "7% · approximate", "17%", "21%", "10% · approximate"),
            view.rows.map { it.share },
        )
        assertEquals(
            listOf("Mon 9", "Tue 10", "Watch · Wed 11", "Thu 12", "Sat 14", "Watch · Sun 15"),
            view.rows.map { it.dayLine },
        )
        assertEquals(
            listOf(
                EffortSource.Tablet,
                EffortSource.Tablet,
                EffortSource.Watch,
                EffortSource.Tablet,
                EffortSource.Tablet,
                EffortSource.Watch,
            ),
            view.rows.map { it.source },
        )
        // The days: Monday to Sunday, in the week's own loads, none of them today.
        assertEquals(listOf(107.0, 74.0, 27.0, 68.0, 0.0, 81.0, 38.0), view.days.values.toList())
        assertEquals(-1, view.days.today)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), view.days.labels)
    }

    @Test
    fun `the week in progress marks today in the strip and the days after it are not counted as past`() {
        val view = content().viewAt(null)
        assertEquals(2, view.days.today)
        assertEquals(97.0, view.days.values[0], 0.0)
        val spoken = "Monday 97, Tuesday 0, Wednesday, today, Thursday, Friday, Saturday, Sunday"
        assertTrue(view.daysDescription, view.daysDescription.startsWith(spoken))
    }

    @Test
    fun `an outdoor ride is told from a watch activity and an activity without heart rate has no load and no share`() {
        val response = ChartScreenSamples.withoutHeartRate()
        val rows = content(response).viewAt(null).rows
        assertEquals(2, rows.size)
        val outdoor = rows[1]
        assertEquals(EffortSource.Outdoor, outdoor.source)
        assertEquals("Outdoor · Tue 17", outdoor.dayLine)
        assertEquals("—", outdoor.load)
        assertEquals("no load", outdoor.share)
        assertEquals("No heart rate in this ride, so it adds no load.", outdoor.note)
        assertEquals(EffortKind.ACTIVITY, outdoor.kind)
        // The ride keeps the whole week: the other is left out of the shares.
        assertEquals("100%", rows[0].share)
        assertNull(rows[0].note)
        assertEquals("Outdoor ride, Tuesday 17, no load, opens the activity", outdoor.description)
    }

    @Test
    fun `a row reads as one button with its load and its share of the week`() {
        val row = content().viewAt(null).rows.single()
        assertEquals("Threshold 2×20, Monday 16, load 97, 100% of the week, opens the ride", row.description)
        assertTrue(row.id.startsWith("ride-2026-11-16"))
        val approximate = content().viewAt(10.0).rows[2]
        assertEquals(
            "Beat Saber, Wednesday 11, load 27, 7% of the week, approximate, opens the activity",
            approximate.description,
        )
    }

    // region the chart

    @Test
    fun `the chart has twelve columns of the weeks' loads and the band of each week, none while learning`() {
        val spec = content().spec
        assertEquals(12, spec.values.size)
        assertEquals(ChartScreenSamples.mockLoads + listOf(395.0, 97.0), spec.values.toList())
        assertEquals(334.0, spec.bandLows!![11], 0.0)
        assertEquals(511.0, spec.bandHighs!![11], 0.0)
        assertEquals(298.0, spec.bandLows!![10], 0.0)
        assertEquals(510.0, spec.bandHighs!![10], 0.0)
        // The first eight weeks of the sample have no eight weeks before them: no band for those.
        assertTrue(spec.bandLows!![0].isNaN() && spec.bandHighs!![7].isNaN())
        assertEquals(11.0, spec.restX, 0.0)
        assertEquals(11, spec.columnOf(spec.restX))
        assertEquals((0..11).map { it.toDouble() }, spec.selectXs.toList())
        assertFalse(spec.labelHighest)
    }

    @Test
    fun `the axis is 0 to a clean maximum, in steps of 100 or of 200 above 400`() {
        assertEquals(600.0 to 200.0, EffortUi.axis(579.0))
        assertEquals(400.0 to 100.0, EffortUi.axis(395.0))
        assertEquals(400.0 to 100.0, EffortUi.axis(400.0))
        assertEquals(600.0 to 200.0, EffortUi.axis(401.0))
        assertEquals(100.0 to 100.0, EffortUi.axis(0.0))
        assertEquals(1200.0 to 200.0, EffortUi.axis(1130.0))
        val spec = content().spec
        assertEquals(600.0, spec.yMax, 0.0)
        assertEquals(listOf("0", "200", "400", "600"), spec.yTicks.map { it.label })
        // A very large week grows the axis, and the band's high edge counts too.
        val big = content(ChartScreenSamples.effort(thisWeek = listOf(Act("Long ride", 0, 900.0))))
        assertEquals(1000.0, big.spec.yMax, 0.0)
    }

    @Test
    fun `the ticks are the first Monday, the seventh and Now under the week in progress`() {
        val spec = content().spec
        assertEquals(listOf("31 Aug", "12 Oct"), spec.xTicks.map { it.label })
        assertEquals(listOf(0.0, 6.0), spec.xTicks.map { it.value })
        assertEquals("Now", spec.nowLabel)
    }

    @Test
    fun `a cached answer from an earlier week is never relabelled as this week`() {
        // The answer is from the week of 9 November: on Wednesday 18 November its "current" week is last week's.
        val stale = TestClock.words
        val earlier = ChartScreenSamples.effort()
        val old =
            EffortResponse(
                earlier.generatedAt,
                8,
                earlier.weeks[10].weeksLogged,
                earlier.weeks.take(11).map {
                    it.copy(
                        current =
                            it === earlier.weeks[10],
                    )
                },
            )
        val c = (EffortUi.of(fresh(old), stale).body as EffortBody.Content).content
        assertFalse(c.lastIsThisWeek)
        assertNull(c.spec.nowLabel)
        val view = c.viewAt(null)
        assertFalse(view.thisWeek)
        assertEquals("Within your usual range", view.verdict)
        assertEquals("Week of 9 Nov · load ", view.line.lead)
        assertEquals("Week of 9 Nov, by day", view.subheading)
        assertEquals(-1, view.days.today)
    }

    @Test
    fun `selecting beyond the columns holds the first and the last`() {
        val c = content()
        assertEquals(0, c.indexOf(-3.0))
        assertEquals(11, c.indexOf(40.0))
        assertEquals(3, c.indexOf(3.2))
        assertEquals(11, c.indexOf(null))
    }

    @Test
    fun `the card draws the last 12 weeks and Values all of them, newest first`() {
        val c = content(ChartScreenSamples.effort(weeks = 30))
        assertEquals(12, c.spec.values.size)
        assertEquals(12, c.weeks.size)
        assertEquals(30, c.table.rows.size)
        assertEquals(listOf("Week", "Load", "Usual", "Verdict"), listOf(c.table.firstColumn) + c.table.columns)
        assertEquals("16 Nov · so far", c.table.rows[0].label)
        assertEquals(listOf("97", "334–511", "Below"), c.table.rows[0].cells)
        assertEquals("9 Nov", c.table.rows[1].label)
        assertEquals(listOf("395", "298–510", "Within"), c.table.rows[1].cells)
        // A week before the usual range exists has a dash for it and for its verdict.
        assertEquals(
            listOf("—", "—"),
            c.table.rows
                .last()
                .cells
                .drop(1),
        )
        assertEquals(
            30,
            c.table.rows
                .map { it.key }
                .toSet()
                .size,
        )
    }

    // region the summary

    @Test
    fun `the chart's text alternative reads this week, last week and the highest`() {
        assertEquals(
            "Effort, last 12 weeks. This week so far 97, below your usual range of 334 to 511. " +
                "Last week 395, within. " +
                "Highest 579, week of 12 Oct.",
            content().summary,
        )
    }

    // region states

    @Test
    fun `loading and a failure with nothing cached are their own card`() {
        assertEquals(EffortBody.Loading, EffortUi.of(ResourceState(loaded = true, asked = true), words).body)
        assertEquals(
            EffortBody.NotLoaded,
            EffortUi
                .of(
                    ResourceState(loaded = true, asked = true, problem = ServiceProblem(ProblemKind.NO_NETWORK, "x")),
                    words,
                ).body,
        )
    }

    @Test
    fun `offline leaves the cached card as it was`() {
        val online = content()
        val state = fresh(ChartScreenSamples.effort()).copy(problem = ServiceProblem(ProblemKind.NO_NETWORK, "x"))
        val offline = (EffortUi.of(state, words).body as EffortBody.Content).content
        assertEquals(online.weeks.map { it.verdict to it.line }, offline.weeks.map { it.verdict to it.line })
        assertEquals(online.summary, offline.summary)
    }

    @Test
    fun `no load ever is the empty card`() {
        assertEquals(EffortBody.NoEffort, body(ChartScreenSamples.noEffort()))
    }

    @Test
    fun `fewer than eight weeks of training is learning, with the count so far and no band`() {
        val c = content(ChartScreenSamples.effort(learning = true))
        val view = c.viewAt(null)
        assertEquals("Learning your usual range", view.verdict)
        assertEquals("Your usual range appears after 8 weeks of training. 5 so far.", view.learning)
        assertNull(view.line.usual)
        assertEquals("This week · load ", view.line.lead)
        assertTrue(c.spec.bandLows!!.all { it.isNaN() })
        // The weeks before the first load are empty slots.
        assertEquals(0.0, c.spec.values[0], 0.0)
    }

    @Test
    fun `nothing yet this week waits for the verdict and still shows the usual range`() {
        val view = content(ChartScreenSamples.nothingYetThisWeek()).viewAt(null)
        assertEquals("Nothing yet this week", view.verdict)
        assertEquals("This week · load 0 · usual 334–511", view.line.plain)
        assertEquals("No rides or activities yet this week.", view.noRows)
        assertTrue(view.rows.isEmpty())
        // The current slot draws nothing: a zero week.
        assertEquals(0.0, content(ChartScreenSamples.nothingYetThisWeek()).spec.values.last(), 0.0)
    }

    @Test
    fun `a past week with nothing in it is an empty slot with its own sentence`() {
        val c = content(ChartScreenSamples.effort(learning = true))
        val view = c.viewAt(0.0)
        assertEquals("No rides or activities that week.", view.noRows)
        assertEquals("Week of 31 Aug, by day", view.subheading)
        assertEquals(0.0, c.spec.values[0], 0.0)
        assertNull(content().viewAt(3.0).noRows)
    }

    @Test
    fun `the week line is spoken in full`() {
        assertEquals("This week · load 97 so far. Usual 334–511", content().viewAt(null).line.spoken())
        assertEquals("Week of 9 Nov · load 395 (approximate). Usual 298–510", content().viewAt(10.0).line.spoken())
    }
}
