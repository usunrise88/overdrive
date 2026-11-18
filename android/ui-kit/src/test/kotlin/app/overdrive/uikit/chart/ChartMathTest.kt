package app.overdrive.uikit.chart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chart geometry as pure functions (design/companion.md §6): no Android, no Compose. */
class ChartMathTest {
    private fun assertTicks(
        axis: NiceAxis,
        vararg expected: Double,
    ) = assertEquals(expected.toList(), axis.ticks.map { Math.round(it * 1000) / 1000.0 })

    // region clean ticks

    @Test
    fun `steps are 1, 2, 2_5 and 5 times a power of ten`() {
        assertEquals(1.0, ChartMath.niceStepAtLeast(0.9), 1e-12)
        assertEquals(2.0, ChartMath.niceStepAtLeast(1.1), 1e-12)
        assertEquals(2.5, ChartMath.niceStepAtLeast(2.2), 1e-12)
        assertEquals(5.0, ChartMath.niceStepAtLeast(2.6), 1e-12)
        assertEquals(10.0, ChartMath.niceStepAtLeast(5.1), 1e-12)
        assertEquals(250.0, ChartMath.niceStepAtLeast(201.0), 1e-9)
        assertEquals(0.5, ChartMath.niceStepAtLeast(0.4), 1e-12)
        assertEquals(20.0, ChartMath.nextStep(10.0), 1e-12)
        assertEquals(25.0, ChartMath.nextStep(20.0), 1e-12)
        assertEquals(50.0, ChartMath.nextStep(25.0), 1e-12)
        assertEquals(100.0, ChartMath.nextStep(50.0), 1e-12)
    }

    @Test
    fun `an axis around two series has clean ticks that enclose the data`() {
        val axis = ChartMath.niceAxis(30.0, 84.0, maxTicks = 5)
        assertTicks(axis, 20.0, 40.0, 60.0, 80.0, 100.0)
        assertTrue(axis.min <= 30.0 && axis.max >= 84.0)
    }

    @Test
    fun `an axis never has more ticks than asked for`() {
        for (hi in listOf(7.0, 33.0, 101.0, 249.0, 1250.0, 0.9)) {
            for (maxTicks in 3..6) {
                val axis = ChartMath.niceAxis(0.0, hi, maxTicks, includeZero = true)
                assertTrue("$hi / $maxTicks: ${axis.ticks}", axis.ticks.size <= maxTicks)
                assertTrue(axis.min <= 0.0 && axis.max >= hi)
            }
        }
    }

    @Test
    fun `zero is included when the data is above it and a negative lower bound keeps its tick`() {
        assertEquals(0.0, ChartMath.niceAxis(35.0, 90.0, includeZero = true).min, 0.0)
        assertTicks(ChartMath.niceAxis(-32.0, 14.0, maxTicks = 5), -40.0, -20.0, 0.0, 20.0)
        assertTicks(ChartMath.niceAxis(-32.0, 14.0, maxTicks = 8), -40.0, -30.0, -20.0, -10.0, 0.0, 10.0, 20.0)
    }

    @Test
    fun `a single value is centred with the usual tick step`() {
        val axis = ChartMath.niceAxis(54.0, 54.0, maxTicks = 4)
        assertTrue(axis.min < 54.0 && axis.max > 54.0)
        assertEquals((axis.min + axis.max) / 2, 54.0, axis.step)
        // A zero single value does not collapse.
        val zero = ChartMath.niceAxis(0.0, 0.0)
        assertTrue(zero.max > zero.min)
    }

    @Test
    fun `rounding to a unit goes up and down without floating point surprises`() {
        assertEquals(300.0, ChartMath.ceilTo(251.0, 50.0), 0.0)
        assertEquals(250.0, ChartMath.ceilTo(250.0, 50.0), 0.0)
        assertEquals(250.0, ChartMath.floorTo(299.9, 50.0), 0.0)
        assertEquals(0.3, ChartMath.ceilTo(0.3, 0.1), 1e-12)
    }

    // region nearest point

    @Test
    fun `the nearest index is the nearest value, the earlier of two equals, and clamps at the ends`() {
        val xs = doubleArrayOf(10.0, 20.0, 30.0, 50.0)
        assertEquals(0, ChartMath.nearestIndex(xs, -5.0))
        assertEquals(0, ChartMath.nearestIndex(xs, 14.9))
        assertEquals(0, ChartMath.nearestIndex(xs, 15.0))
        assertEquals(1, ChartMath.nearestIndex(xs, 15.1))
        assertEquals(2, ChartMath.nearestIndex(xs, 39.0))
        assertEquals(3, ChartMath.nearestIndex(xs, 41.0))
        assertEquals(3, ChartMath.nearestIndex(xs, 5000.0))
        assertEquals(-1, ChartMath.nearestIndex(DoubleArray(0), 1.0))
        assertEquals(0, ChartMath.nearestIndex(doubleArrayOf(7.0), 100.0))
    }

    // region decimation

    @Test
    fun `a series that fits is left alone`() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3), ChartMath.decimate(doubleArrayOf(1.0, 2.0, 3.0, 4.0), buckets = 2))
    }

    @Test
    fun `decimation keeps the ends, the spike and the dip of every bucket, in order, with far fewer points`() {
        val ys = DoubleArray(10_000) { 100.0 + (it % 7) }
        ys[5_003] = 900.0
        ys[7_001] = -50.0
        val kept = ChartMath.decimate(ys, buckets = 300)
        assertTrue(kept.size < 1_300)
        assertEquals(0, kept.first())
        assertEquals(9_999, kept.last())
        assertTrue(5_003 in kept.toList() && 7_001 in kept.toList())
        for (i in 1 until kept.size) assertTrue(kept[i] > kept[i - 1])
    }

    @Test
    fun `a gap stays a gap after decimation`() {
        val ys = DoubleArray(2_000) { 100.0 }
        for (i in 1_000..1_050) ys[i] = Double.NaN
        val kept = ChartMath.decimate(ys, buckets = 100)
        assertTrue(kept.any { ys[it].isNaN() })
    }

    // region scales

    @Test
    fun `a linear scale maps both ends and the middle, and inverts`() {
        val scale = AxisScale(0.0, 100.0, 40f, 340f)
        assertEquals(40f, scale.toPx(0.0), 1e-4f)
        assertEquals(340f, scale.toPx(100.0), 1e-4f)
        assertEquals(190f, scale.toPx(50.0), 1e-4f)
        assertEquals(50.0, scale.fromPx(190f), 1e-6)
        // A y axis runs upwards: the bottom is where the minimum lands.
        val y = AxisScale(0.0, 250.0, 180f, 10f)
        assertEquals(180f, y.toPx(0.0), 1e-4f)
        assertEquals(10f, y.toPx(250.0), 1e-4f)
    }

    @Test
    fun `a logarithmic scale puts equal ratios at equal distances`() {
        val scale = AxisScale(1.0, 7200.0, 0f, 1000f, logarithmic = true)
        assertEquals(0f, scale.toPx(1.0), 1e-3f)
        assertEquals(1000f, scale.toPx(7200.0), 1e-3f)
        assertEquals(scale.toPx(10.0) - scale.toPx(1.0), scale.toPx(100.0) - scale.toPx(10.0), 1e-3f)
        assertEquals(1200.0, scale.fromPx(scale.toPx(1200.0)), 0.01)
    }

    @Test
    fun `column width is the slot less the gap, at most 20, at least 3`() {
        assertEquals(20f, ChartMath.columnWidth(slot = 50f, maxWidth = 20f, gap = 2f, minWidth = 3f), 0f)
        assertEquals(5.5f, ChartMath.columnWidth(slot = 7.5f, maxWidth = 20f, gap = 2f, minWidth = 3f), 0f)
        assertEquals(3f, ChartMath.columnWidth(slot = 4f, maxWidth = 20f, gap = 2f, minWidth = 3f), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an axis range that is backwards is refused`() {
        ChartMath.niceAxis(5.0, 1.0)
    }
}
