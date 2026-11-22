package app.overdrive.uikit.chart

import app.overdrive.uikit.chart.RadarGeometry.fits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The radar's geometry as pure functions (design/companion.md §6.11, §14): angles, the plot radius, label places, hit testing, arcs, outlines. */
class RadarGeometryTest {
    private fun widths(w: Float) = List(RadarGeometry.AXES) { w }

    // region the axes

    @Test
    fun `axis 0 is at the top and the axes run clockwise 30 degrees apart`() {
        assertEquals(0.0, RadarGeometry.unitX(0), 1e-9)
        assertEquals(-1.0, RadarGeometry.unitY(0), 1e-9)
        // Three o'clock is the 2 min axis (index 3), the bottom is the 10 min axis (index 6), nine o'clock is 30 min (index 9).
        assertEquals(1.0, RadarGeometry.unitX(3), 1e-9)
        assertEquals(0.0, RadarGeometry.unitY(3), 1e-9)
        assertEquals(1.0, RadarGeometry.unitY(6), 1e-9)
        assertEquals(-1.0, RadarGeometry.unitX(9), 1e-9)
    }

    @Test
    fun `the plot radius is the card's half width less the label column and its gap, at most 118`() {
        // 412 dp phone: 346 dp inner width, label column of 54 dp: 105 dp.
        assertEquals(105f, RadarGeometry.plotRadius(346f, 54f), 1e-3f)
        // 360 dp phone: 296 dp inner width: 80 dp.
        assertEquals(80f, RadarGeometry.plotRadius(296f, 54f), 1e-3f)
        // A wide card stops at 118.
        assertEquals(118f, RadarGeometry.plotRadius(700f, 54f), 1e-3f)
        // The radar is 306 dp tall at 412 dp (block 34 dp) and 256 at 360 dp.
        assertEquals(306f, RadarGeometry.height(105f, 34f), 1e-3f)
        assertEquals(256f, RadarGeometry.height(80f, 34f), 1e-3f)
    }

    // region the labels

    @Test
    fun `the top and bottom labels are centred and wholly outside the ring, the sides sit against it`() {
        val rp = 105f
        val cx = 173f
        val cy = 153f
        val top = RadarGeometry.labelBox(0, cx, cy, rp, 50f, 34f)
        assertEquals(cx, (top.left + top.right) / 2f, 1e-3f)
        assertTrue("the 15 s label is above the ring", top.bottom < cy - rp - RadarGeometry.RING_OFFSET)
        val bottom = RadarGeometry.labelBox(6, cx, cy, rp, 50f, 34f)
        assertEquals(cx, (bottom.left + bottom.right) / 2f, 1e-3f)
        assertTrue("the 10 min label is below the ring", bottom.top > cy + rp + RadarGeometry.RING_OFFSET)
        val right = RadarGeometry.labelBox(3, cx, cy, rp, 50f, 34f)
        assertEquals(cx + rp + RadarGeometry.LABEL_OFFSET, right.left, 1e-3f)
        assertEquals(cy, (right.top + right.bottom) / 2f, 1e-3f)
        val left = RadarGeometry.labelBox(9, cx, cy, rp, 50f, 34f)
        assertEquals(cx - rp - RadarGeometry.LABEL_OFFSET, left.right, 1e-3f)
        // An upper right label is left-aligned on its anchor and lifted by 15 dp times the sine.
        val upperRight = RadarGeometry.labelBox(1, cx, cy, rp, 50f, 34f)
        assertEquals(cx + (rp + RadarGeometry.LABEL_OFFSET) * 0.5f, upperRight.left, 1e-2f)
        val anchorY = cy - (rp + RadarGeometry.LABEL_OFFSET) * 0.866025f
        assertEquals(anchorY - 15f * 0.866025f, (upperRight.top + upperRight.bottom) / 2f, 1e-2f)
    }

    private fun assertFits(
        width: Float,
        labelWidth: Float,
        blockHeight: Float,
    ): RadarGeometry.Layout {
        val layout = RadarGeometry.fit(width, widths(labelWidth), blockHeight)
        assertTrue("no two labels intersect and none leaves the canvas", layout.fits())
        for ((i, a) in layout.boxes.withIndex()) {
            for (j in i + 1 until layout.boxes.size) assertFalse("$i and $j touch", a.intersects(layout.boxes[j]))
            assertTrue(a.left >= 0f && a.right <= layout.width && a.top >= 0f && a.bottom <= layout.height)
        }
        return layout
    }

    @Test
    fun `at 412 dp and at 360 dp the labels do not touch, stay inside the canvas, and the plot keeps its radius`() {
        assertEquals(105f, assertFits(346f, 50f, 34f).radius, 1e-3f)
        assertEquals(80f, assertFits(296f, 50f, 34f).radius, 1e-3f)
    }

    @Test
    fun `at 1_3 times the font the plot shrinks to fit the measured labels and nothing is clipped`() {
        // Labels and blocks grow by 1.3: 65 dp wide, 44 dp tall.
        val phone = assertFits(346f, 65f, 44f)
        val small = assertFits(296f, 65f, 44f)
        assertTrue("the plot is smaller than at 1x", phone.radius < 105f && small.radius < 80f)
        // Design: about 90 dp at 412 and 66 at 360 (the label column is measured, not fixed).
        assertTrue(abs(phone.radius - 90f) < 8f)
        assertTrue(abs(small.radius - 66f) < 8f)
    }

    @Test
    fun `a wider label and a taller block still give a layout without overlaps`() {
        assertFits(296f, 70f, 48f)
    }

    // region touch

    @Test
    fun `a touch picks the axis by angle anywhere in the box`() {
        val cx = 100f
        val cy = 100f
        assertEquals(0, RadarGeometry.axisAt(100f, 10f, cx, cy))
        assertEquals(0, RadarGeometry.axisAt(108f, 10f, cx, cy))
        assertEquals(3, RadarGeometry.axisAt(190f, 100f, cx, cy))
        assertEquals(6, RadarGeometry.axisAt(100f, 190f, cx, cy))
        assertEquals(9, RadarGeometry.axisAt(10f, 100f, cx, cy))
        // Just either side of the 15 degree boundary between axes 0 and 1.
        assertEquals(0, RadarGeometry.axisAt(100f + 26f, 100f - 100f, cx, cy))
        assertEquals(1, RadarGeometry.axisAt(100f + 28f, 100f - 100f, cx, cy))
        // The far corner of the box belongs to an axis too: up and to the left is 10 or 11.
        assertTrue(RadarGeometry.axisAt(0f, 0f, cx, cy) in 10..11)
        // Every axis is hit when touching along its own direction.
        for (i in 0 until RadarGeometry.AXES) {
            val x = cx + (RadarGeometry.unitX(i) * 70).toFloat()
            val y = cy + (RadarGeometry.unitY(i) * 70).toFloat()
            assertEquals(i, RadarGeometry.axisAt(x, y, cx, cy))
        }
    }

    @Test
    fun `a wedge is at least 48 dp wide from 92 dp out`() {
        assertTrue(RadarGeometry.wedgeWidth(92f) >= 47.5f)
        assertTrue(RadarGeometry.wedgeWidth(60f) < 48f)
    }

    // region the ring

    @Test
    fun `an arc runs from 15 degrees before its first axis to 15 after its last, less half a gap at each end`() {
        val ring = 111f
        val half = (2f / ring * 180.0 / Math.PI / 2.0).toFloat()
        // Sprint: axes 0 and 1, from -105 to -45 degrees.
        val sprint = RadarGeometry.arc(0, 1, ring)
        assertEquals(-105f + half, sprint.startDegrees, 1e-3f)
        assertEquals(60f - 2 * half, sprint.sweepDegrees, 1e-3f)
        // Endurance: axes 10 and 11, the same sweep.
        val endurance = RadarGeometry.arc(10, 11, ring)
        assertEquals(60f - 2 * half, endurance.sweepDegrees, 1e-3f)
        // Neighbouring arcs leave a 2 dp gap on the circle: the angle between the end of one and the start of the next.
        val attack = RadarGeometry.arc(2, 4, ring)
        val gapDegrees = attack.startDegrees - (sprint.startDegrees + sprint.sweepDegrees)
        assertEquals(2f / ring * 180.0 / Math.PI, gapDegrees.toDouble(), 1e-3)
        // The five arcs together cover the circle minus five gaps.
        val arcs = listOf(0 to 1, 2 to 4, 5 to 6, 7 to 9, 10 to 11).map { RadarGeometry.arc(it.first, it.second, ring) }
        assertEquals(360.0 - 5 * 2.0 * 180.0 / Math.PI / ring, arcs.sumOf { it.sweepDegrees.toDouble() }, 1e-2)
    }

    // region the points and the outline

    @Test
    fun `a point keeps at least 6 dp from the centre and stops at the edge`() {
        assertEquals(6f, RadarGeometry.pointRadius(0.0, 100f), 1e-6f)
        assertEquals(6f, RadarGeometry.pointRadius(0.01, 100f), 1e-6f)
        assertEquals(50f, RadarGeometry.pointRadius(0.5, 100f), 1e-6f)
        assertEquals(100f, RadarGeometry.pointRadius(1.0, 100f), 1e-6f)
        assertEquals(100f, RadarGeometry.pointRadius(3.0, 100f), 1e-6f)
    }

    @Test
    fun `twelve values are one closed outline, a gap cuts it into runs and an isolated axis is a dot`() {
        val all = DoubleArray(12) { 0.5 }
        val full = RadarGeometry.outline(all)
        assertTrue(full.closed)
        assertEquals(listOf((0..11).toList()), full.runs)

        // 45 min and 60 min missing (axes 10 and 11): one open run from 0 to 9, the two segments that meet at the gaps are not there.
        val twoGaps =
            all.copyOf().also {
                it[10] = Double.NaN
                it[11] = Double.NaN
            }
        val cut = RadarGeometry.outline(twoGaps)
        assertFalse(cut.closed)
        assertEquals(listOf((0..9).toList()), cut.runs)

        // A gap in the middle: the run wraps across the top.
        val middle = all.copyOf().also { it[5] = Double.NaN }
        val wrapped = RadarGeometry.outline(middle)
        assertEquals(listOf(listOf(6, 7, 8, 9, 10, 11, 0, 1, 2, 3, 4)), wrapped.runs)

        // Both neighbours missing: a lone point.
        val lone = DoubleArray(12) { Double.NaN }.also { it[4] = 0.4 }
        val dot = RadarGeometry.outline(lone)
        assertEquals(listOf(4), dot.isolated)
        assertEquals(listOf(listOf(4)), dot.runs)

        // Two runs.
        val split =
            DoubleArray(12) { Double.NaN }.also {
                it[0] = 1.0
                it[1] = 1.0
                it[6] = 1.0
            }
        val two = RadarGeometry.outline(split)
        assertEquals(listOf(listOf(0, 1), listOf(6)), two.runs)
        assertEquals(listOf(6), two.isolated)

        assertTrue(RadarGeometry.outline(DoubleArray(12) { Double.NaN }).runs.isEmpty())
    }
}
