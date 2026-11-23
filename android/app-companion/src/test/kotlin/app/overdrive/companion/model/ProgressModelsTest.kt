package app.overdrive.companion.model

import app.overdrive.companion.ProgressSamples
import app.overdrive.companion.ProgressScreenSamples
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ProgressRange
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Progress screen as pure functions (design/companion.md §4.16, §6.13, §7.9, ADR 0043): the entry row's headline, the states, the
 * verdict sentences used as the service sent them, the numbers lines, the readouts, the plots' gaps, the tables and the TalkBack
 * descriptions. The synthetic answer is in the numbers of the design's mock-up.
 */
class ProgressModelsTest {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val range = ProgressRange.SIX_MONTHS
    private val full = ProgressScreenSamples.full()

    private fun day(date: String) = checkNotNull(PlanDates.epochDay(date)).toDouble()

    private fun content(response: ProgressResponse = full) = ProgressUi.content(response, range, words)

    private fun card(
        key: String,
        response: ProgressResponse = full,
    ): ProgressCardUi = content(response).groups.flatMap { it.cards }.first { it.key == key }

    private fun chart(
        key: String,
        response: ProgressResponse = full,
    ) = card(key, response) as ProgressChartUi

    private fun screen(
        state: ResourceState<ProgressResponse>,
        othersCached: Boolean = false,
    ) = ProgressUi.of(state, range, words, host, othersCached)

    // region the entry row

    @Test
    fun `the row says the service's headline, or the neutral sentence while no measure has a verdict`() {
        val entry = ProgressUi.entry(fresh(ProgressSamples.heartRateFalling()))
        assertEquals("Heart rate at the same power is trending down: fitter", entry.text)
        assertEquals(
            "Progress. Heart rate at the same power is trending down: fitter. Opens the progress page.",
            entry.description,
        )
        val neutral = ProgressUi.entry(fresh(ProgressSamples.empty()))
        assertEquals("How your power and heart rate change over the months", neutral.text)
        assertEquals(neutral.text, ProgressUi.entry(ResourceState()).text)
    }

    // region states

    @Test
    fun `nothing cached and nothing answered is loading`() {
        val ui = screen(ResourceState())
        assertEquals("Loading your progress…", (ui.body as Body.Loading).text)
    }

    @Test
    fun `nothing cached and a failed request is the error block, unless another range has an answer`() {
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        val failed = ResourceState<ProgressResponse>(loaded = true, asked = true, problem = offline)
        assertTrue(screen(failed).body is Body.Failed)
        val never = screen(failed, othersCached = true)
        val body = (never.body as Body.Content).value
        assertEquals("Not loaded yet. It loads when the service can be reached.", body.notice)
        assertTrue(body.groups.isEmpty())
        assertNotNull("the cause is on the status line", never.status)
        assertEquals("No network connection. Pull down to try again.", never.status?.cause)
    }

    @Test
    fun `a cached answer that could not be renewed keeps its cards and says why`() {
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        val ui = screen(fresh(full).copy(problem = offline))
        assertEquals("Offline · updated 07:40", ui.status?.title)
        assertEquals("Updated 07:40", ui.subtitle)
        assertEquals(2, (ui.body as Body.Content).value.groups.size)
    }

    @Test
    fun `no ride and no watch figure at all is the empty state`() {
        val ui = screen(fresh(ProgressSamples.empty()))
        val empty = ui.body as Body.Empty
        assertEquals("No progress to show yet", empty.heading)
        assertEquals(
            "Progress fills in from rides on the tablet and from the watch. Give it a few weeks of riding.",
            empty.text,
        )
        assertFalse(ProgressUi.nothingAtAll(full))
    }

    // region the groups

    @Test
    fun `two groups in the design's order, and the caution only under heart rate`() {
        val groups = content().groups
        assertEquals(listOf("Power", "Heart rate"), groups.map { it.heading })
        assertEquals(
            listOf("Best power", "Weight and watts per kilogram"),
            groups[0].cards.map { it.name },
        )
        assertEquals(
            listOf("Aerobic efficiency", "Efficiency factor", "Aerobic drift", "Resting heart rate"),
            groups[1].cards.map { it.name },
        )
        assertNull(groups[0].caution)
        assertEquals(
            "Heart rate also moves with heat, sleep and caffeine, so trust a trend over several weeks, not one ride.",
            groups[1].caution,
        )
    }

    @Test
    fun `every verdict is the service's sentence as it was sent, and the design's words agree`() {
        assertEquals("Best power is trending up: better", chart("best").verdict)
        assertEquals("Watts per kilogram are trending up: better", chart("weight").verdict)
        assertEquals("Heart rate at the same power is trending down: fitter", chart("efficiency").verdict)
        assertEquals("Efficiency factor is trending up: fitter", chart("factor").verdict)
        assertEquals("Aerobic drift is trending down: better", chart("drift").verdict)
        assertEquals("Resting heart rate is trending down: better", chart("resting").verdict)
        assertEquals(full.bestPower.trend.verdictText, chart("best").verdict)
        assertEquals(full.aerobicEfficiency.trend.verdictText, chart("efficiency").verdict)
    }

    // region the numbers lines

    @Test
    fun `the numbers lines put now against the start of the range in the design's words`() {
        assertEquals("5 min +25 W · 20 min +22 W · 60 min +14 W since June", chart("best").numbers)
        assertEquals("3.48 W/kg now, 3.25 in May · weight 71.8 kg, 1.4 kg less", chart("weight").numbers)
        assertEquals("138 bpm at 180 W, 7 lower than in May · 186 W at 140 bpm, 13 more", chart("efficiency").numbers)
        assertEquals("1.40 over the last 5 steady rides, 1.33 in May", chart("factor").numbers)
        assertEquals("5.0% over the last 5 long rides, 6.4% in May", chart("drift").numbers)
        assertEquals("51 bpm this week, 54 in May", chart("resting").numbers)
    }

    @Test
    fun `a trend without a verdict says why in the service's words and what adds a point`() {
        val few = ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(efficiencyWeeks = 3))
        val card = chart("efficiency", few)
        assertEquals("Not enough data yet", card.verdict)
        assertEquals("3 of 4 weeks have a figure. Ride steady for 5 minutes or more to add one.", card.numbers)
        assertEquals("the points that exist are drawn", 2, card.plots.size)
        val few5 = ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(factorRides = false))
        assertTrue(card("factor", few5) is ProgressSentenceUi)
    }

    @Test
    fun `a weight that moved is reported and never judged, a steady one is unchanged`() {
        assertTrue(chart("weight").numbers.endsWith("1.4 kg less"))
        val heavier =
            full.copy(
                weight =
                    full.weight.copy(
                        weightChange =
                            full.weight.weightChange!!.copy(
                                latest = 75.0,
                                difference =
                                    75.0 - full.weight.weightChange!!.from,
                            ),
                    ),
            )
        assertTrue(chart("weight", heavier).numbers.contains("kg more"))
        val same =
            full.copy(
                weight =
                    full.weight.copy(
                        weightChange =
                            full.weight.weightChange!!.copy(
                                latest = full.weight.weightChange!!.from,
                                difference = 0.0,
                            ),
                    ),
            )
        assertTrue(chart("weight", same).numbers.endsWith("unchanged"))
    }

    // region no data in the range

    @Test
    fun `a measure with nothing in the range is one sentence`() {
        val noWatch = card("resting", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(resting = false)))
        assertEquals(
            ProgressSentenceUi(
                "resting",
                "Resting heart rate",
                "No resting heart rate yet. It comes from the watch through Health Connect.",
            ),
            noWatch,
        )
        val noWeight = card("weight", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(weight = false)))
        assertEquals(
            "No weight yet. Weigh yourself with a scale that writes to Health Connect.",
            (noWeight as ProgressSentenceUi).text,
        )
        val noRides = card("best", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(bestPower = false)))
        assertEquals("No rides with power in this range.", (noRides as ProgressSentenceUi).text)
        val noStretch =
            card("efficiency", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(efficiencyWeeks = 0)))
        assertEquals(
            "No steady stretch of 5 minutes or more with heart rate in this range.",
            (noStretch as ProgressSentenceUi).text,
        )
        val noFactor =
            card("factor", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(factorRides = false)))
        assertEquals("No steady ride with heart rate in this range.", (noFactor as ProgressSentenceUi).text)
        val noDrift = card("drift", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(driftRides = false)))
        assertEquals(
            "No steady rides yet. Drift is measured on steady rides of 40 minutes or more with heart rate.",
            (noDrift as ProgressSentenceUi).text,
        )
    }

    @Test
    fun `with no FTP to set a reference from, the sentence says that and not that no stretch was steady`() {
        val empty = ProgressSamples.empty()
        val ui = ProgressUi.aerobicEfficiency(empty, range, words) as ProgressSentenceUi
        assertEquals("There is no FTP to set a reference power from.", ui.text)
    }

    // region best power

    @Test
    fun `best power has three zone-coloured lines with a dot on every month and the month so far last`() {
        val best = chart("best")
        val spec = best.plots.single().spec
        assertEquals(3, spec.lines.size)
        assertEquals(3, spec.dots.size)
        assertTrue(spec.dots.all { it.drawn })
        assertEquals(
            listOf(
                app.overdrive.uikit.tokens.OverdriveColors.z5,
                app.overdrive.uikit.tokens.OverdriveColors.z4,
                app.overdrive.uikit.tokens.OverdriveColors.z3,
            ),
            spec.lines.map {
                it.color
            },
        )
        // Seven months of the range; the 60 minute line has no August ride: a gap, not a zero.
        val sixty = spec.lines[2].ys
        assertEquals(7, sixty.size)
        assertTrue(sixty[3].isNaN())
        assertEquals("mid-month", day("2026-06-15"), spec.lines[0].xs[1], 0.0)
        assertTrue("the month so far is at most today", spec.restX <= day("2026-11-18"))
        assertEquals(200.0, spec.yMin, 0.0)
        assertEquals(350.0, spec.yMax, 0.0)
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), spec.xTicks.map { it.label })
        assertEquals(132f, spec.heightDp)
    }

    @Test
    fun `the readout of best power names the month so far at rest and each duration's watts`() {
        val best = chart("best")
        val rest = best.readoutAt(null)
        assertEquals("Nov · so far", rest.x)
        assertEquals(listOf("5 min", "20 min", "60 min"), rest.entries.map { it.name })
        assertEquals(listOf("326 W", "281 W", "247 W"), rest.entries.map { it.value })
        val august = best.readoutAt(day("2026-08-15"))
        assertEquals("Aug", august.x)
        assertEquals(listOf("318 W", "270 W", "—"), august.entries.map { it.value })
        assertEquals("Aug 2025", words.monthShortYear("2025-08-01"))
    }

    @Test
    fun `the note of best power names the watts per kilogram when the weight is known`() {
        assertEquals("Your best of each month. 3.9 W/kg at 20 min with 72.0 kg.", chart("best").note)
        val noWeight = ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(bestPowerWeightKnown = false))
        assertEquals("Your best of each month.", chart("best", noWeight).note)
    }

    @Test
    fun `best power's table is the months newest first with a dash for a missing duration`() {
        val table = chart("best").table
        assertEquals(listOf("5 min", "20 min", "60 min"), table.columns)
        assertEquals("Nov · so far", table.rows.first().label)
        assertEquals(listOf("326 W", "281 W", "247 W"), table.rows.first().cells)
        assertEquals(listOf("318 W", "270 W", "—"), table.rows.first { it.label == "Aug" }.cells)
        assertEquals(7, table.rows.size)
    }

    @Test
    fun `the description of best power reads the range in numbers and names the missing month`() {
        val summary = chart("best").summary
        assertTrue(
            summary,
            summary.startsWith(
                "Best power by month, last 6 months. 5 minutes from 305 to 326 watts, 20 minutes from 262 to 281 watts",
            ),
        )
        assertTrue(summary, summary.contains("no 60-minute ride in August"))
        assertTrue(summary.endsWith("Best power is trending up: better."))
    }

    // region weekly cards

    @Test
    fun `the two plots of a pair share the x range, the ticks and the weeks that can be selected`() {
        for (key in listOf("efficiency", "weight")) {
            val plots = chart(key).plots
            assertEquals(2, plots.size)
            assertEquals(plots[0].spec.xMin, plots[1].spec.xMin, 0.0)
            assertEquals(plots[0].spec.xMax, plots[1].spec.xMax, 0.0)
            assertEquals(plots[0].spec.xTicks.map { it.label }, plots[1].spec.xTicks.map { it.label })
            assertEquals(plots[0].spec.snapXs.toList(), plots[1].spec.snapXs.toList())
            assertEquals(plots[0].spec.restX, plots[1].spec.restX, 0.0)
            assertEquals(96f, plots[0].spec.heightDp)
        }
        assertEquals(
            listOf("Heart rate at 180 W, bpm", "Power at 140 bpm, W"),
            chart("efficiency").plots.map { it.caption },
        )
        assertEquals(listOf("Watts per kilogram (FTP)", "Weight, kg"), chart("weight").plots.map { it.caption })
    }

    @Test
    fun `a week without a figure is a gap in the line, never a zero, and a week that stands alone has a dot`() {
        val spec = chart("efficiency").plots[0].spec
        val ys = spec.lines.single().ys
        // The fifth week of the sample has no stretch: NaN, and the line is broken there.
        assertTrue(ys[4].isNaN())
        assertTrue(ys.none { it == 0.0 })
        val resting = chart("resting").plots.single().spec
        val line = resting.lines.single().ys
        val alone = resting.dots.single().ys
        assertEquals(line.size, alone.size)
        for (i in line.indices) {
            val neighbours = (i > 0 && !line[i - 1].isNaN()) || (i < line.lastIndex && !line[i + 1].isNaN())
            assertEquals("week $i", !line[i].isNaN() && !neighbours, !alone[i].isNaN())
        }
    }

    @Test
    fun `the readout of aerobic efficiency names the references and the week, and a week without a figure says so`() {
        val card = chart("efficiency")
        val rest = card.readoutAt(null)
        assertEquals("Week of 16 Nov", rest.x)
        assertEquals(listOf("At 180 W", "At 140 bpm"), rest.entries.map { it.name })
        assertEquals(listOf("138 bpm", "186 W"), rest.entries.map { it.value })
        val gap = card.readoutAt(day("2026-06-08"))
        assertEquals("Week of 8 Jun", gap.x)
        assertEquals("the nearest week that has a figure", listOf("Week of 8 Jun"), listOf(gap.x))
        assertTrue(card.restX == day("2026-11-16"))
    }

    @Test
    fun `the note of aerobic efficiency says where the two references come from`() {
        val note = chart("efficiency").note!!
        assertTrue(
            note.startsWith("From steady stretches of 5 minutes or more in every ride, the median of each week."),
        )
        assertTrue(note, note.contains("180 W is about 70% of your FTP at the start of the range"))
        assertTrue(note, note.contains("140 bpm about 80% of your threshold heart rate."))
        val max =
            full.copy(
                aerobicEfficiency =
                    full.aerobicEfficiency.copy(
                        referenceHeartRateBasis = app.overdrive.core.progress.ProgressHeartRateBasis.MAXIMUM,
                    ),
            )
        assertTrue(chart("efficiency", max).note!!.contains("80% of your maximum heart rate"))
    }

    @Test
    fun `the weight card reads watts per kilogram to two decimals and the weight to one`() {
        val rest = chart("weight").readoutAt(null)
        assertEquals("Week of 16 Nov", rest.x)
        assertEquals(listOf("FTP per kg", "Weight"), rest.entries.map { it.name })
        assertEquals(listOf("3.48 W/kg", "71.8 kg"), rest.entries.map { it.value })
    }

    @Test
    fun `resting heart rate is one weekly line and its readout is the weekly mean`() {
        val card = chart("resting")
        assertEquals(1, card.plots.size)
        assertEquals(112f, card.plots[0].spec.heightDp)
        assertEquals(listOf("Weekly mean"), card.readoutAt(null).entries.map { it.name })
        assertEquals(listOf("51 bpm"), card.readoutAt(null).entries.map { it.value })
        assertEquals("From the watch, the mean of each week's nights.", card.note)
    }

    // region the ride measures

    @Test
    fun `efficiency factor is a dot per ride and the mean of the last five, from the fifth ride on`() {
        val card = chart("factor")
        val spec = card.plots.single().spec
        assertEquals(full.efficiencyFactor.rides.size, spec.dots.single().ys.size)
        val rolling = spec.lines.single().ys
        assertTrue("no mean before the fifth ride", rolling.take(4).all { it.isNaN() })
        assertFalse(rolling[4].isNaN())
        val first = card.readoutAt(spec.snapXs.first())
        assertEquals("—", first.entries[1].value)
        val rest = card.readoutAt(null)
        assertEquals("Mon 16 Nov · Endurance 90", rest.x)
        assertEquals(listOf("Ride", "Last 5 rides"), rest.entries.map { it.name })
        assertEquals(listOf("1.40", "1.40"), rest.entries.map { it.value })
        assertEquals("the readout opens the ride", full.efficiencyFactor.rides.last().rideId, card.rideAt(null))
        assertEquals(112f, spec.heightDp)
    }

    @Test
    fun `drift keeps its chart from the Power tab with the steady band and 0 to 10 percent`() {
        val spec = chart("drift").plots.single().spec
        assertEquals("Steady", spec.bands.single().label)
        assertEquals(0.0, spec.bands.single().from, 0.0)
        assertEquals(5.0, spec.bands.single().to, 0.0)
        assertEquals(0.0, spec.yMin, 0.0)
        assertTrue(spec.yMax >= 10.0)
        assertEquals(listOf("0%", "5%", "10%"), spec.yTicks.map { it.label })
        val rest = chart("drift").readoutAt(null)
        assertEquals("Sat 14 Nov · Endurance 90", rest.x)
        assertEquals(listOf("4.7%", "5.0%"), rest.entries.map { it.value })
        assertEquals(
            "How much heart rate rose against power on steady rides of 40 minutes or more. Lower is better.",
            chart("drift").note,
        )
    }

    @Test
    fun `drift is not on the Power tab any more`() {
        val fields = PowerContent::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("drift"))
    }

    // region the other ranges

    @Test
    fun `a range of five years starts its axes at the first point and writes its ticks as years`() {
        val all = ProgressScreenSamples.full("2021-11-18", "2026-11-18")
        val card = ProgressUi.resting(all, ProgressRange.ALL_TIME, words) as ProgressChartUi
        val spec = card.plots.single().spec
        assertEquals(listOf("2022", "2023", "2024", "2025", "2026"), spec.xTicks.map { it.label })
        assertEquals(day("2021-11-15"), spec.xMin, 7.0)
        val short =
            ProgressScreenSamples.full(
                "2021-11-18",
                "2026-11-18",
                ProgressScreenSamples.Gaps(efficiencyWeeks = 20),
            )
        val shortSpec =
            (
                ProgressUi.aerobicEfficiency(
                    short,
                    ProgressRange.ALL_TIME,
                    words,
                ) as ProgressChartUi
            ).plots[0].spec
        assertEquals(
            "the axis starts where the data does, not five years back",
            shortSpec.snapXs.first(),
            shortSpec.xMin,
            0.0,
        )
        assertEquals(
            "five months of data, every month",
            listOf("Aug", "Sep", "Oct", "Nov"),
            shortSpec.xTicks.map { it.label },
        )
    }

    @Test
    fun `one year is every second month`() {
        val year = ProgressScreenSamples.full("2025-11-18", "2026-11-18")
        val card = ProgressUi.resting(year, ProgressRange.ONE_YEAR, words) as ProgressChartUi
        assertEquals(
            listOf("Jan", "Mar", "May", "Jul", "Sep", "Nov"),
            card.plots
                .single()
                .spec.xTicks
                .map { it.label },
        )
    }

    // region tables and descriptions

    @Test
    fun `every table is newest first and gives every figure of its card, a dash for none`() {
        val efficiency = chart("efficiency").table
        assertEquals("Aerobic efficiency", efficiency.title)
        assertEquals(listOf("At 180 W", "At 140 bpm"), efficiency.columns)
        assertEquals("16 Nov", efficiency.rows.first().label)
        assertEquals(listOf("138 bpm", "186 W"), efficiency.rows.first().cells)
        assertEquals(listOf("—", "—"), efficiency.rows.reversed()[4].cells)
        assertEquals(full.aerobicEfficiency.weeks.size, efficiency.rows.size)
        val weight = chart("weight").table
        assertEquals(listOf("W/kg", "Weight"), weight.columns)
        assertEquals(listOf("3.48", "71.8 kg"), weight.rows.first().cells)
        val factor = chart("factor").table
        assertEquals("Mon 16 Nov · Endurance 90", factor.rows.first().label)
        assertEquals(listOf("1.40", "1.40"), factor.rows.first().cells)
        assertEquals("—", factor.rows.last().cells[1])
        assertEquals(listOf("Mean", "Nights"), chart("resting").table.columns)
    }

    @Test
    fun `the descriptions for TalkBack read the range and the verdict`() {
        val spoken = chart("efficiency").summary
        assertTrue(spoken, spoken.startsWith("Heart rate at 180 watts, last 6 months, weekly. "))
        assertTrue(spoken, spoken.contains("in the week of 18 May"))
        assertTrue(spoken, spoken.contains("weeks without a figure"))
        assertTrue(spoken, spoken.contains("Power at 140 beats per minute, last 6 months, weekly."))
        assertTrue(spoken, spoken.endsWith("Heart rate at the same power is trending down: fitter."))
        assertTrue(chart("factor").summary.contains("the mean of the last 5 rides 1.40"))
        assertTrue(chart("resting").summary.startsWith("Resting heart rate, last 6 months, weekly."))
    }

    @Test
    fun `the words of the range`() {
        assertEquals(listOf("6 months", "1 year", "All time"), ProgressWords.labels)
        assertEquals("last 6 months", ProgressWords.spoken(ProgressRange.SIX_MONTHS))
        assertEquals("last year", ProgressWords.spoken(ProgressRange.ONE_YEAR))
        assertEquals("all time", ProgressWords.spoken(ProgressRange.ALL_TIME))
        assertEquals("16 Nov", words.dayShortYear("2026-11-16"))
        assertEquals("16 Nov 2025", words.dayShortYear("2025-11-16"))
        assertEquals("16 November", words.dayMonthLong("2026-11-16"))
    }
}
