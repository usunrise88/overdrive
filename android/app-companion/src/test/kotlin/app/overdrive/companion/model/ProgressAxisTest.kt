package app.overdrive.companion.model

import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.ProgressRange
import app.overdrive.uikit.chart.AxisTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axes and the gaps of the Progress charts (design/companion.md §6.13): the tick rules of the three ranges, the y axis at a measure's
 * steps, tick labels and which points of a line stand alone. Pure functions, so every rule is a plain test.
 */
class ProgressAxisTest {
    private val words = TestClock.words

    private fun day(date: String) = checkNotNull(PlanDates.epochDay(date))

    private fun labels(ticks: List<AxisTick>) = ticks.map { it.label }

    private fun ticks(
        range: ProgressRange,
        from: String,
        to: String,
        offset: Long = 0,
        margin: Boolean = true,
    ) = ProgressAxis.xTicks(range, day(from), day(to), words, offset, margin)

    // region ticks

    @Test
    fun `six months has a tick at every month start`() {
        val ticks = ticks(ProgressRange.SIX_MONTHS, "2026-05-18", "2026-11-18")
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), labels(ticks))
        assertEquals(day("2026-06-01").toDouble(), ticks.first().value, 0.0)
        assertEquals(day("2026-11-01").toDouble(), ticks.last().value, 0.0)
    }

    @Test
    fun `one year has a tick every second month of the calendar`() {
        val ticks = ticks(ProgressRange.ONE_YEAR, "2025-11-18", "2026-11-18")
        assertEquals(listOf("Jan", "Mar", "May", "Jul", "Sep", "Nov"), labels(ticks))
    }

    @Test
    fun `all time is every year beyond two years, written as the year`() {
        val ticks = ticks(ProgressRange.ALL_TIME, "2021-11-18", "2026-11-18")
        assertEquals(listOf("2022", "2023", "2024", "2025", "2026"), labels(ticks))
    }

    @Test
    fun `all time up to two years is every quarter and a January is its year`() {
        val ticks = ticks(ProgressRange.ALL_TIME, "2025-03-10", "2026-11-18")
        assertEquals(listOf("Apr", "Jul", "Oct", "2026", "Apr", "Jul", "Oct"), labels(ticks))
    }

    @Test
    fun `all time follows what is shown, the history being shorter than the five years`() {
        // Up to about six months every month, up to a year every second month.
        assertEquals(
            listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"),
            labels(ticks(ProgressRange.ALL_TIME, "2026-05-18", "2026-11-18")),
        )
        assertEquals(
            listOf("Jan", "Mar", "May", "Jul", "Sep", "Nov"),
            labels(ticks(ProgressRange.ALL_TIME, "2025-12-20", "2026-11-18")),
        )
        assertEquals(ProgressAxis.TickRule(1, false), ProgressAxis.rule(ProgressRange.ALL_TIME, 200))
        assertEquals(ProgressAxis.TickRule(2, false), ProgressAxis.rule(ProgressRange.ALL_TIME, 201))
        assertEquals(ProgressAxis.TickRule(3, true), ProgressAxis.rule(ProgressRange.ALL_TIME, 366))
        assertEquals(ProgressAxis.TickRule(12, true), ProgressAxis.rule(ProgressRange.ALL_TIME, 731))
    }

    @Test
    fun `a tick within three days of either end is left out`() {
        // A month start two days after the start of the axis is left out; three days is kept.
        assertEquals(listOf("Jul"), labels(ticks(ProgressRange.SIX_MONTHS, "2026-05-30", "2026-07-20")))
        assertEquals(listOf("Jun", "Jul"), labels(ticks(ProgressRange.SIX_MONTHS, "2026-05-29", "2026-07-20")))
        // And the same at the end of it.
        assertEquals(listOf("Jun"), labels(ticks(ProgressRange.SIX_MONTHS, "2026-05-20", "2026-07-03")))
        assertEquals(listOf("Jun", "Jul"), labels(ticks(ProgressRange.SIX_MONTHS, "2026-05-20", "2026-07-04")))
    }

    @Test
    fun `the months of best power are labelled under their own point, in the middle of the month`() {
        val ticks =
            ticks(
                ProgressRange.SIX_MONTHS,
                "2026-05-18",
                "2026-11-18",
                offset = ProgressAxis.MID_MONTH_OFFSET,
                margin = false,
            )
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), labels(ticks))
        assertEquals(day("2026-06-15").toDouble(), ticks.first().value, 0.0)
        assertEquals(day("2026-11-15").toDouble(), ticks.last().value, 0.0)
    }

    // region y axis

    @Test
    fun `best power is on a step of 50 watts over the three lines`() {
        val axis = ProgressAxis.fit(236.0, 326.0, listOf(50.0, 100.0, 250.0, 500.0), 5, atLeast = 0.0)
        assertEquals(200.0, axis.min, 0.0)
        assertEquals(350.0, axis.max, 0.0)
        assertEquals(50.0, axis.step, 0.0)
        assertEquals(listOf(200.0, 250.0, 300.0, 350.0), axis.ticks)
    }

    @Test
    fun `heart rate takes the finest step that needs at most four ticks`() {
        val axis = ProgressAxis.fit(138.0, 146.0, listOf(1.0, 2.0, 5.0, 10.0), 4)
        assertEquals(listOf(135.0, 140.0, 145.0, 150.0), axis.ticks)
        val resting = ProgressAxis.fit(50.4, 55.6, listOf(2.0, 4.0, 5.0, 10.0), 4)
        assertEquals(listOf(50.0, 52.0, 54.0, 56.0), resting.ticks)
    }

    @Test
    fun `one value gets the room of a step each side, and never fewer than three ticks`() {
        val one = ProgressAxis.fit(140.0, 140.0, listOf(1.0, 2.0, 5.0, 10.0), 4)
        assertEquals(listOf(139.0, 140.0, 141.0), one.ticks)
        val close = ProgressAxis.fit(139.6, 140.2, listOf(5.0, 10.0), 4)
        assertTrue(close.ticks.size >= 3)
        assertTrue(close.min <= 139.6 && close.max >= 140.2)
    }

    @Test
    fun `a watts axis never goes below zero`() {
        val axis = ProgressAxis.fit(3.0, 12.0, listOf(5.0, 10.0), 5, atLeast = 0.0)
        assertEquals(0.0, axis.min, 0.0)
        assertTrue(axis.max >= 12.0)
    }

    @Test
    fun `tick labels have the decimals their step needs`() {
        assertEquals("150", ProgressAxis.tickLabel(150.0, 5.0))
        assertEquals("3.4", ProgressAxis.tickLabel(3.4, 0.2))
        assertEquals("3.25", ProgressAxis.tickLabel(3.25, 0.05))
        assertEquals("1.30", ProgressAxis.tickLabel(1.3, 0.05))
        assertEquals("0", ProgressAxis.tickLabel(-0.0, 5.0))
        assertEquals(
            listOf("0%", "5%", "10%"),
            ProgressAxis.yTicks(app.overdrive.uikit.chart.NiceAxis(0.0, 10.0, 5.0), "%").map { it.label },
        )
    }

    // region gaps

    private val nan = Double.NaN

    @Test
    fun `a figure with a gap on both sides stands alone and is drawn as a dot`() {
        val alone = ProgressAxis.isolated(doubleArrayOf(nan, 1.0, nan, 2.0, 3.0, nan, 4.0))
        assertEquals(listOf(false, true, false, false, false, false, true), alone.map { !it.isNaN() })
        // The ends of a series count as a gap.
        assertEquals(listOf(true, false, true), ProgressAxis.isolated(doubleArrayOf(1.0, nan, 2.0)).map { !it.isNaN() })
        assertTrue(ProgressAxis.isolated(doubleArrayOf(1.0, 2.0)).all { it.isNaN() })
    }

    @Test
    fun `the gaps of a series are the places without a figure between its first and its last`() {
        assertEquals(2, ProgressAxis.gaps(doubleArrayOf(nan, 1.0, nan, nan, 3.0, nan)))
        assertEquals(0, ProgressAxis.gaps(doubleArrayOf(nan, nan)))
        assertEquals(0, ProgressAxis.gaps(doubleArrayOf(1.0, 2.0)))
    }
}
