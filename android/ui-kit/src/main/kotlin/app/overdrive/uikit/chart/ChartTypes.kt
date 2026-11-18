package app.overdrive.uikit.chart

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * The selected x of the charts that share a time axis (design/companion.md §3.5): Fitness' two line charts and its week
 * columns, the three plots of a ride. `null` is **rest**, which means the latest point. It is read in the draw phase by the
 * charts (so scrubbing redraws but never recomposes them) and in composition only by the readout row.
 */
@Stable
class ChartSelection {
    var x: Double? by mutableStateOf(null)
        private set

    fun select(value: Double) {
        x = value
    }

    fun clear() {
        x = null
    }

    /** Whether the selection is at a point other than [restX]: the crosshair, the dots on every series and the Today pill. */
    fun isAway(restX: Double): Boolean = x?.let { it != restX } == true
}

/** A polyline: [ys] with NaN is a gap, never interpolated. [stepped] draws horizontally to the next x, then vertically. */
@Immutable
class LineLayer(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val color: Color,
    val widthDp: Float,
    val stepped: Boolean = false,
    /** Whether the selected (or resting) point of this line carries the 8 dp dot with its ring. */
    val marked: Boolean = true,
) {
    init {
        require(xs.size == ys.size) { "A line has one y per x" }
    }
}

/** Separate dots (days outside a band, FTP estimates, drift points): 6 dp with a 1.5 dp surface ring. */
@Immutable
class DotLayer(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val color: Color,
    /** Whether the selected point, if it is one of these, is drawn larger like a selection dot. */
    val marked: Boolean = false,
) {
    init {
        require(xs.size == ys.size) { "A dot layer has one y per x" }
    }
}

/** A flat horizontal band from [from] to [to] on the y axis, named in words inside its lower-left corner. */
@Immutable
class BandLayer(
    val from: Double,
    val to: Double,
    val color: Color,
    val label: String? = null,
)

/** A reference line (1 dp `borderStrong`) with its caption 5 dp above its right end ("FTP 250"). */
@Immutable
class RefLine(
    val y: Double,
    val label: String,
)

/**
 * Everything a line chart draws (design/companion.md §6), as plain numbers: built once per (data, range) by the screen and
 * compared by identity, so the chart builds its paths once per size.
 *
 * @property snapXs the x values a touch can select, ascending (days, durations, rides).
 * @property restX where the selection rests when nothing was touched: the latest point.
 * @property gutterDp the width left of the plot for the y labels: 32, or 40 for four-digit labels.
 */
@Immutable
class PlotSpec(
    val xMin: Double,
    val xMax: Double,
    val xTicks: List<AxisTick>,
    val yMin: Double,
    val yMax: Double,
    val yTicks: List<AxisTick>,
    val lines: List<LineLayer>,
    val snapXs: DoubleArray,
    val restX: Double,
    val heightDp: Float,
    val xLogarithmic: Boolean = false,
    val dots: List<DotLayer> = emptyList(),
    val bands: List<BandLayer> = emptyList(),
    val refLines: List<RefLine> = emptyList(),
    val gutterDp: Float = DEFAULT_GUTTER_DP,
) {
    init {
        require(xMax > xMin && yMax > yMin) { "A chart has a range on both axes" }
        require(snapXs.isNotEmpty()) { "A chart has something to select" }
    }

    companion object {
        const val DEFAULT_GUTTER_DP = 32f
    }
}

/** One column of a [ColumnSpec]: the x ([selectX]) a touch on it selects, and its value. */
@Immutable
class ColumnSpec(
    /** The first x of each column's span, ascending (a week's Monday as a day number). */
    val starts: DoubleArray,
    val values: DoubleArray,
    /** The x a touch on each column selects (the week's last day that is in the range). */
    val selectXs: DoubleArray,
    val xTicks: List<AxisTick>,
    val yMax: Double,
    val yTicks: List<AxisTick>,
    val restX: Double,
    val heightDp: Float,
    /** The label above a column that carries one (the selected and the highest). */
    val valueLabel: (Double) -> String,
) {
    init {
        require(starts.size == values.size && values.size == selectXs.size && values.isNotEmpty()) {
            "A column chart has one value and one selection x per column, and at least one column"
        }
    }

    /** The column whose span holds [x]: the last one that starts at or before it. */
    fun columnOf(x: Double): Int {
        var found = 0
        for (i in starts.indices) if (starts[i] <= x) found = i else break
        return found
    }
}
