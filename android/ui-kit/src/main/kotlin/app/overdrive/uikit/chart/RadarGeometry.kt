package app.overdrive.uikit.chart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pure geometry of the Power skills radar (design/companion.md §6.11, §14): the axis angles, the plot radius that fits the
 * measured labels, the place of every label block, hit testing by angle, the ring's arcs and the runs of an outline. No Compose and
 * no Android: every rule is a unit test on a plain JVM and [RadarChart] only draws what these functions decided. Units are dp unless a
 * name says otherwise; a test passes dp and the chart passes pixels with the constants scaled by the density.
 */
object RadarGeometry {
    /** Twelve durations, 30° apart. */
    const val AXES: Int = 12
    private const val STEP_DEG = 30.0

    /** The plot radius never exceeds this on a wide card. */
    const val MAX_RADIUS: Float = 118f

    /** A point never comes closer to the centre than this, so a tiny value does not vanish. */
    const val MIN_POINT: Float = 6f

    /** The ring's centre line lies this far beyond the plot's edge; its width and the gap between its arcs. */
    const val RING_OFFSET: Float = 6f
    const val RING_WIDTH: Float = 4f
    const val RING_GAP: Float = 2f

    /** A label block begins this far beyond the plot's edge. */
    const val LABEL_OFFSET: Float = 14f

    /** A label block's centre moves by this much times the sine of its axis angle. */
    const val LABEL_SHIFT: Float = 15f

    /** The slack added to the widest label for the label column. */
    const val LABEL_SLACK: Float = 4f

    /** Axes closer to the vertical than this (|cos θ|) have a centred label. */
    private const val CENTRED_BELOW = 0.3

    /** Below this the plot is too small to read and the radar stops shrinking (it clips instead, which a test would catch). */
    private const val SMALLEST_RADIUS = 30f

    /** The angle of axis [i] in radians: 0 is three o'clock, y points down, axis 0 is the top. */
    fun angle(i: Int): Double = -PI / 2 + i * STEP_DEG * PI / 180.0

    /** The unit vector of axis [i]: x then y. */
    fun unitX(i: Int): Double = cos(angle(i))

    fun unitY(i: Int): Double = sin(angle(i))

    /** The radius of the plot that fits a card [width] wide with labels of [labelColumn]: `min(w/2 − column − 14, 118)`. */
    fun plotRadius(
        width: Float,
        labelColumn: Float,
    ): Float = min(width / 2f - labelColumn - LABEL_OFFSET, MAX_RADIUS).coerceAtLeast(SMALLEST_RADIUS)

    /** The height of the radar: `2 × (Rp + 14 + block)`. */
    fun height(
        radius: Float,
        blockHeight: Float,
    ): Float = 2f * (radius + LABEL_OFFSET + blockHeight)

    /** A rectangle in the radar's own coordinates. */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun intersects(other: Box): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    /** The label block of axis [i], [w] by [h], around a centre ([cx], [cy]) and a plot radius [radius]: placed as §6.11 says. */
    fun labelBox(
        i: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        w: Float,
        h: Float,
    ): Box {
        val c = unitX(i)
        val s = unitY(i)
        val ax = cx + ((radius + LABEL_OFFSET) * c).toFloat()
        val ay = cy + ((radius + LABEL_OFFSET) * s).toFloat()
        val left =
            when {
                abs(c) < CENTRED_BELOW -> ax - w / 2f
                c > 0 -> ax
                else -> ax - w
            }
        val centreY = ay + (LABEL_SHIFT * s).toFloat()
        return Box(left, centreY - h / 2f, left + w, centreY + h / 2f)
    }

    /** What [fit] decided: the plot radius, the canvas size and where each label block goes. */
    class Layout(
        val width: Float,
        val height: Float,
        val radius: Float,
        val cx: Float,
        val cy: Float,
        val boxes: List<Box>,
    )

    /**
     * The layout for a card [width] wide whose label blocks measure [widths] by [blockHeight]: the radius of §6.11 (the label column is the
     * widest block plus 4), then, if two blocks touch or one leaves the canvas, the smallest shrink of the radius that fixes it. The text
     * is never scaled down: a large font shrinks the plot instead (design/companion.md §6.11, 1.3× font scale).
     */
    fun fit(
        width: Float,
        widths: List<Float>,
        blockHeight: Float,
    ): Layout {
        require(widths.size == AXES) { "A radar has twelve labels" }
        val column = (widths.maxOrNull() ?: 0f) + LABEL_SLACK
        var radius = plotRadius(width, column)
        var layout = layoutOf(width, radius, widths, blockHeight)
        while (radius > SMALLEST_RADIUS && !layout.fits()) {
            radius -= 2f
            layout = layoutOf(width, radius, widths, blockHeight)
        }
        return layout
    }

    private fun layoutOf(
        width: Float,
        radius: Float,
        widths: List<Float>,
        blockHeight: Float,
    ): Layout {
        // Place the blocks around a centre at the origin, push neighbours on one side apart, then move the origin to the middle of what is needed.
        val raw = widths.mapIndexed { i, w -> labelBox(i, 0f, 0f, radius, w, blockHeight) }.toMutableList()
        separate(raw, listOf(1, 2, 3, 4, 5), blockHeight)
        separate(raw, listOf(11, 10, 9, 8, 7), blockHeight)
        val reach = raw.maxOf { max(abs(it.top), abs(it.bottom)) }
        val height = max(height(radius, blockHeight), 2f * reach)
        val cx = width / 2f
        val cy = height / 2f
        return Layout(
            width,
            height,
            radius,
            cx,
            cy,
            raw.map {
                Box(
                    it.left + cx,
                    it.top + cy,
                    it.right + cx,
                    it.bottom + cy,
                )
            },
        )
    }

    /**
     * Keeps the blocks of one side of the radar ([order] runs from the top down; the middle one, at three or nine o'clock, stays where it
     * is) at least [GAP] apart: at a large font a block is taller than the vertical distance between two neighbours (30° apart), and they
     * are left- or right-aligned on nearly the same x. The ones above the middle move up, the ones below move down.
     */
    private fun separate(
        boxes: MutableList<Box>,
        order: List<Int>,
        blockHeight: Float,
    ) {
        val middle = order.size / 2
        for (k in middle - 1 downTo 0) {
            val below = boxes[order[k + 1]]
            val box = boxes[order[k]]
            if (box.bottom >
                below.top - GAP
            ) {
                boxes[order[k]] =
                    box.copy(top = below.top - GAP - blockHeight, bottom = below.top - GAP)
            }
        }
        for (k in middle + 1 until order.size) {
            val above = boxes[order[k - 1]]
            val box = boxes[order[k]]
            if (box.top <
                above.bottom + GAP
            ) {
                boxes[order[k]] =
                    box.copy(top = above.bottom + GAP, bottom = above.bottom + GAP + blockHeight)
            }
        }
    }

    private const val GAP = 2f

    /** Whether no two blocks intersect and none leaves the canvas. */
    fun Layout.fits(): Boolean {
        val canvas = Box(0f, 0f, width, height)
        for ((i, box) in boxes.withIndex()) {
            if (box.left < canvas.left || box.top < canvas.top || box.right > canvas.right ||
                box.bottom > canvas.bottom
            ) {
                return false
            }
            for (j in i + 1 until boxes.size) if (box.intersects(boxes[j])) return false
        }
        return true
    }

    /**
     * The axis a touch at ([x], [y]) picks, anywhere in the radar's box: the nearest of the twelve by angle, so each owns a wedge of ±15° (at
     * 92 dp or more from the centre that is wider than 48 dp).
     */
    fun axisAt(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
    ): Int {
        val turns = (atan2((y - cy).toDouble(), (x - cx).toDouble()) + PI / 2) / (PI / 6)
        return ((turns.roundToInt() % AXES) + AXES) % AXES
    }

    /** The width in dp of the wedge of one axis at [distance] from the centre: the chord of ±15°. */
    fun wedgeWidth(distance: Float): Float = (2.0 * distance * sin(Math.toRadians(STEP_DEG / 2))).toFloat()

    /** A coloured arc of the ring: its start angle and sweep in degrees (0 at three o'clock, clockwise), the gap on both sides included. */
    class Arc(
        val startDegrees: Float,
        val sweepDegrees: Float,
    )

    /**
     * The arc of a skill from [firstAxis] to [lastAxis]: from 15° before the first axis to 15° after the last, less half of
     * [RING_GAP] (as an angle on the circle of [ringRadius]) at each end, so neighbouring arcs are 2 dp apart.
     */
    fun arc(
        firstAxis: Int,
        lastAxis: Int,
        ringRadius: Float,
    ): Arc {
        val half = (RING_GAP / ringRadius * 180.0 / PI / 2.0).toFloat()
        val start = (-90.0 + firstAxis * STEP_DEG - STEP_DEG / 2).toFloat() + half
        val sweep = ((lastAxis - firstAxis + 1) * STEP_DEG).toFloat() - 2f * half
        return Arc(start, sweep)
    }

    /** The radius of a point in the same unit as [radius]: [value] (0 to 1) of it, at least [minPoint] and at most [radius]. */
    fun pointRadius(
        value: Double,
        radius: Float,
        minPoint: Float = MIN_POINT,
    ): Float = max(minPoint, (value * radius).toFloat()).coerceAtMost(radius)

    /** What an outline is: runs of consecutive axes with data. [closed] is true when all twelve have data (one run, joined to its start). */
    class Outline(
        val closed: Boolean,
        val runs: List<List<Int>>,
    ) {
        /** The axes that stand alone: both neighbours have no data. They are drawn as dots. */
        val isolated: List<Int> get() = runs.filter { it.size == 1 }.map { it.first() }
    }

    /**
     * The outline of [values] (NaN is no data): a gap at an axis removes the two segments that meet there, so the line is cut into runs.
     * All twelve present is one closed run. A run of one axis is an isolated point.
     */
    fun outline(values: DoubleArray): Outline {
        require(values.size == AXES) { "A radar has twelve values" }
        val present = BooleanArray(AXES) { !values[it].isNaN() }
        if (present.all { it }) return Outline(true, listOf((0 until AXES).toList()))
        val runs = ArrayList<List<Int>>()
        for (start in 0 until AXES) {
            if (!present[start] || present[(start + AXES - 1) % AXES]) continue
            val run = ArrayList<Int>()
            var i = start
            while (present[i % AXES] && run.size < AXES) {
                run += i % AXES
                i++
            }
            runs += run
        }
        return Outline(false, runs)
    }
}
