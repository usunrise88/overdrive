package app.overdrive.uikit.chart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * A y axis with clean ticks: [min] and [max] are multiples of [step], so the plot's top and bottom lines carry a tick.
 */
data class NiceAxis(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    /** The tick values from [min] to [max] inclusive. */
    val ticks: List<Double>
        get() {
            val count = round((max - min) / step).toInt()
            return (0..count).map { min + it * step }
        }
}

/** One labelled mark on an axis: where ([value], in the axis' own unit) and what it says. */
data class AxisTick(
    val value: Double,
    val label: String,
)

/**
 * The pure geometry of the companion's charts (design/companion.md §6): scales, clean ticks, nearest-point selection and
 * decimation. No Compose and no Android in here, so every rule is a unit test on a plain JVM and the charts only draw what
 * these functions decided.
 */
object ChartMath {
    /** The steps a tick may have, times a power of ten: 1, 2, 2.5, 5 (design/companion.md §6). */
    private val STEPS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)
    private const val EPSILON = 1e-9
    private const val SINGLE_VALUE_SPREAD = 0.1

    /** The smallest clean step, at least [raw]. */
    fun niceStepAtLeast(raw: Double): Double {
        require(raw > 0.0 && raw.isFinite()) { "A step is positive, got $raw" }
        val base = 10.0.pow(floor(log10(raw)))
        return STEPS.map { it * base }.first { it >= raw * (1 - EPSILON) }
    }

    /** The clean step after [step]: 1 to 2, 2 to 2.5, 2.5 to 5, 5 to 10 (times a power of ten). */
    fun nextStep(step: Double): Double {
        val base = 10.0.pow(floor(log10(step) + EPSILON))
        return STEPS.map { it * base }.first { it > step * (1 + EPSILON) }
    }

    /**
     * An axis around [lo] to [hi] with at most [maxTicks] ticks at clean steps. With [includeZero] the axis starts at 0 (or
     * at the lowest tick below a negative [lo]). A single value ([lo] == [hi]) is centred with a spread of a tenth of it, at
     * least one unit, which is how a one-point chart gets its range (design/companion.md §5).
     */
    fun niceAxis(
        lo: Double,
        hi: Double,
        maxTicks: Int = 5,
        includeZero: Boolean = false,
    ): NiceAxis {
        require(lo.isFinite() && hi.isFinite() && lo <= hi) { "An axis needs a finite, ordered range: $lo to $hi" }
        require(maxTicks >= 2) { "An axis has at least two ticks" }
        var low = if (includeZero) min(lo, 0.0) else lo
        var high = if (includeZero) max(hi, 0.0) else hi
        if (high - low < EPSILON) {
            val spread = max(abs(low) * SINGLE_VALUE_SPREAD, 1.0)
            low -= spread
            high += spread
            if (includeZero && lo >= 0.0) low = max(low, 0.0)
        }
        var step = niceStepAtLeast((high - low) / (maxTicks - 1))
        while (true) {
            val axisMin = floor(low / step + EPSILON) * step
            val axisMax = ceil(high / step - EPSILON) * step
            if (round((axisMax - axisMin) / step).toInt() + 1 <= maxTicks) return NiceAxis(axisMin, axisMax, step)
            step = nextStep(step)
        }
    }

    /** [value] rounded up to a multiple of [unit]. */
    fun ceilTo(
        value: Double,
        unit: Double,
    ): Double = ceil(value / unit - EPSILON) * unit

    /** [value] rounded down to a multiple of [unit]. */
    fun floorTo(
        value: Double,
        unit: Double,
    ): Double = floor(value / unit + EPSILON) * unit

    /**
     * The index in the ascending [sorted] values that is nearest to [x]; the earlier of two equally near ones. -1 when there
     * are none. NaN entries are not allowed.
     */
    fun nearestIndex(
        sorted: DoubleArray,
        x: Double,
    ): Int {
        if (sorted.isEmpty()) return -1
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < x) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && x - sorted[lo - 1] <= sorted[lo] - x) lo -= 1
        return lo
    }

    /**
     * The indices to draw of a series of [ys] (NaN is a gap) when there are far more points than pixels: the first and last
     * point of every bucket of equal size, and the lowest and highest in it, in order, so a spike is never lost and a gap
     * stays a gap. Returns every index when there are at most `2 * buckets` points.
     */
    fun decimate(
        ys: DoubleArray,
        buckets: Int,
    ): IntArray {
        require(buckets >= 1) { "At least one bucket" }
        if (ys.size <= 2 * buckets) return IntArray(ys.size) { it }
        val kept = ArrayList<Int>(4 * buckets)
        for (bucket in 0 until buckets) {
            val from = (bucket.toLong() * ys.size / buckets).toInt()
            val to = ((bucket + 1).toLong() * ys.size / buckets).toInt()
            if (from >= to) continue
            var lowest = -1
            var highest = -1
            var gap = -1
            for (i in from until to) {
                val y = ys[i]
                if (y.isNaN()) {
                    if (gap < 0) gap = i
                    continue
                }
                if (lowest < 0 || y < ys[lowest]) lowest = i
                if (highest < 0 || y > ys[highest]) highest = i
            }
            val picks = sortedSetOf(from, to - 1)
            if (lowest >= 0) picks += lowest
            if (highest >= 0) picks += highest
            if (gap >= 0) picks += gap
            kept += picks
        }
        return kept.toIntArray()
    }

    /** The width of one column and the gap that follows: [slot] wide, at most [maxWidth], at least [minWidth] (design §6.3). */
    fun columnWidth(
        slot: Float,
        maxWidth: Float,
        gap: Float,
        minWidth: Float,
    ): Float = max(minWidth, min(maxWidth, slot - gap))
}

/**
 * A mapping from an axis' values to pixels: linear, or logarithmic (the power curve's duration axis). [startPx] is where [min]
 * lands and [endPx] where [max] does, so a y axis passes the bottom first.
 */
class AxisScale(
    val min: Double,
    val max: Double,
    val startPx: Float,
    val endPx: Float,
    val logarithmic: Boolean = false,
) {
    init {
        require(max > min) { "An axis has a range: $min to $max" }
        require(!logarithmic || min > 0.0) { "A logarithmic axis starts above zero" }
    }

    private fun position(value: Double): Double =
        if (logarithmic) (log10(value) - log10(min)) / (log10(max) - log10(min)) else (value - min) / (max - min)

    fun toPx(value: Double): Float = (startPx + position(value) * (endPx - startPx)).toFloat()

    /** The value at [px]; the inverse of [toPx]. */
    fun fromPx(px: Float): Double {
        val t = (px - startPx).toDouble() / (endPx - startPx)
        return if (logarithmic) 10.0.pow(log10(min) + t * (log10(max) - log10(min))) else min + t * (max - min)
    }
}
