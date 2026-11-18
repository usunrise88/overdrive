package app.overdrive.uikit.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OdType
import app.overdrive.uikit.Zones
import app.overdrive.uikit.tokens.OverdriveColors
import app.overdrive.uikit.tokens.OverdriveDimens
import kotlin.math.max
import kotlin.math.min

/**
 * One step of a workout profile with repeats expanded. Targets are fractions of FTP (1.0 = FTP): a steady step has
 * `from == to`, a ramp differs, a free-ride step has both null.
 */
@Immutable
data class ProfileStep(
    val startSeconds: Int,
    val durationSeconds: Int,
    val from: Float?,
    val to: Float?,
)

/**
 * The geometry source of a [WorkoutProfile]: steps in time order. Compared by identity, so keep one instance per
 * workout and the chart builds its paths once per size.
 */
@Immutable
class ProfileData(
    val steps: List<ProfileStep>,
) {
    /** Length of the whole workout in seconds. */
    val totalSeconds: Int = steps.lastOrNull()?.let { it.startSeconds + it.durationSeconds } ?: 0
}

/** One point of the rider's power trace: [seconds] into the workout, power as a fraction of FTP. */
@Immutable
data class TracePoint(
    val seconds: Int,
    val fraction: Float,
)

/** How a [WorkoutProfile] is dressed on a given screen (design/system.md §8.1). */
@Immutable
data class ProfileLook(
    /** The surface behind the chart: elapsed steps are blended into it, opaque, never drawn with alpha. */
    val surface: Color,
    /** Gap between steps, cut from the right of each step: 2 dp on the tablet, 1 dp on the overlay. */
    val stepGap: Dp,
    /** The FTP line at 100% with its "FTP" caption (tablet only). */
    val ftpLine: Boolean,
    /** Minute ticks under the baseline every this many minutes (tablet only), null for none. */
    val tickEveryMinutes: Int?,
) {
    companion object {
        /** The ride screen: on a card, with FTP line and ticks every 10 minutes. */
        val Ride = ProfileLook(OverdriveColors.surface1, 2.dp, ftpLine = true, tickEveryMinutes = 10)

        /** The ready screen preview: on the screen background. */
        val Preview = ProfileLook(OverdriveColors.bg, 2.dp, ftpLine = true, tickEveryMinutes = 10)

        /** The overlay: on its opaque backing colour, no axis. */
        val Overlay = ProfileLook(OverdriveColors.overlayBacking, 1.dp, ftpLine = false, tickEveryMinutes = null)

        /** A small profile on a surface-1 card (companion). */
        val Card = ProfileLook(OverdriveColors.surface1, 1.dp, ftpLine = false, tickEveryMinutes = null)
    }
}

private const val Y_MAX = 1.5f
private const val AXIS_HEIGHT_DP = 20f
private const val FREE_RIDE_LEVEL = 0.5f

/** One filled mark: a rectangle or a trapezoid of one zone colour, with its x range for the "past" split. */
private class Piece(
    val path: Path,
    val colour: Color,
    val pastColour: Color,
    val xMin: Float,
    val xMax: Float,
)

/** The paths of one profile at one size, built once and reused while only the now marker moves. */
private class ProfileGeometry(
    val data: ProfileData,
    val look: ProfileLook,
) {
    private var width = -1f
    private var plotHeight = -1f
    private var gap = -1f
    var pieces: List<Piece> = emptyList()
        private set
    var tickLabels: List<Pair<Int, TextLayoutResult>> = emptyList()
        private set
    var ftpLabel: TextLayoutResult? = null
        private set
    private var measured = false

    fun ensure(
        widthPx: Float,
        plotHeightPx: Float,
        gapPx: Float,
        onePx: Float,
        measurer: TextMeasurer,
        axisStyle: TextStyle,
    ) {
        if (!measured) {
            measured = true
            if (look.ftpLine) ftpLabel = measurer.measure("FTP", axisStyle)
            val every = look.tickEveryMinutes
            if (every != null) {
                tickLabels =
                    buildList {
                        var minute = 0
                        while (minute * SECONDS <= data.totalSeconds) {
                            add(minute to measurer.measure(if (minute == 0) "0" else minute.toString(), axisStyle))
                            minute += every
                        }
                    }
            }
        }
        if (widthPx == width && plotHeightPx == plotHeight && gapPx == gap) return
        width = widthPx
        plotHeight = plotHeightPx
        gap = gapPx
        pieces = build(widthPx, plotHeightPx, gapPx, onePx)
    }

    fun xOf(
        seconds: Float,
        widthPx: Float,
    ): Float = if (data.totalSeconds == 0) 0f else seconds / data.totalSeconds * widthPx

    private fun build(
        widthPx: Float,
        plotHeightPx: Float,
        gapPx: Float,
        onePx: Float,
    ): List<Piece> {
        val total = data.totalSeconds.toFloat()
        if (total <= 0f) return emptyList()

        fun y(fraction: Float) = plotHeightPx - min(fraction, Y_MAX) / Y_MAX * plotHeightPx
        val out = ArrayList<Piece>()
        for (step in data.steps) {
            val x0 = step.startSeconds / total * widthPx
            val x1 = max(x0 + onePx, (step.startSeconds + step.durationSeconds) / total * widthPx - gapPx)
            val from = step.from
            val to = step.to
            if (from == null || to == null) {
                val path = Path().apply { addRect(Rect(x0, y(FREE_RIDE_LEVEL), x1, plotHeightPx)) }
                val colour = OverdriveColors.surface3
                out += Piece(path, colour, Zones.blend(look.surface, colour), x0, x1)
                continue
            }
            for (piece in zonePieces(from.toDouble(), to.toDouble())) {
                val px0 = x0 + (x1 - x0) * piece.start
                val px1 = x0 + (x1 - x0) * piece.end
                val fAt = { px: Float -> from + (to - from) * ((px - x0) / (x1 - x0)) }
                val path =
                    Path().apply {
                        moveTo(px0, plotHeightPx)
                        lineTo(px0, y(fAt(px0)))
                        lineTo(px1, y(fAt(px1)))
                        lineTo(px1, plotHeightPx)
                        close()
                    }
                val colour = Zones.color(piece.zone)
                out += Piece(path, colour, Zones.blend(look.surface, colour), px0, px1)
            }
        }
        return out
    }

    private class ZonePiece(
        val start: Float,
        val end: Float,
        val zone: Int,
    )

    /** Splits a ramp from [a] to [b] at zone boundaries so every piece has exactly one zone colour. */
    private fun zonePieces(
        a: Double,
        b: Double,
    ): List<ZonePiece> {
        val cuts = mutableListOf(0.0, 1.0)
        if (a != b) {
            for (edge in Zones.upperBoundFractions) {
                if ((a - edge) * (b - edge) < 0) cuts += (edge - a) / (b - a)
            }
        }
        cuts.sort()
        return (0 until cuts.size - 1).map { i ->
            val u = cuts[i]
            val v = cuts[i + 1]
            ZonePiece(u.toFloat(), v.toFloat(), Zones.ofFraction(a + (b - a) * (u + v) / 2.0))
        }
    }

    private companion object {
        const val SECONDS = 60
    }
}

/**
 * The workout profile (design/system.md §8.1, §8.2): time on x with the whole workout fitting the width, % FTP on
 * y from 0 to 150%, a rectangle per steady step and a trapezoid per ramp in zone colours (a ramp is split at zone
 * boundaries), free-ride steps as a surface 3 rectangle to 50%, a gap between steps, no border on marks.
 *
 * **Elapsed steps are drawn in solid pre-blended colours, not with transparency** ([Zones.blend] into
 * [ProfileLook.surface]): on the translucent overlay alpha would let the video show through. The step the rider is
 * in is split at the now marker. [nowSeconds] null draws no marker and no elapsed part (a preview).
 *
 * [trace] is the rider's actual power (ride screen only): a 1.5 dp polyline in primary text, one point per 10 s.
 *
 * The paths are built once per size and kept; a redraw while riding only re-fills them and moves the marker, so
 * the cost per second is a few dozen path fills.
 */
@Composable
fun WorkoutProfile(
    data: ProfileData,
    look: ProfileLook,
    modifier: Modifier = Modifier,
    nowSeconds: Int? = null,
    trace: List<TracePoint> = emptyList(),
) {
    val geometry = remember(data, look) { ProfileGeometry(data, look) }
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val onePx = 1.dp.toPx()
        val axis = if (look.tickEveryMinutes != null) AXIS_HEIGHT_DP.dp.toPx() else 0f
        val plotHeight = size.height - axis
        val widthPx = size.width
        geometry.ensure(widthPx, plotHeight, look.stepGap.toPx(), onePx, measurer, AxisStyle)
        val nowX = nowSeconds?.let { geometry.xOf(it.toFloat(), widthPx) }

        if (look.ftpLine) {
            val ftpY = plotHeight - 1f / Y_MAX * plotHeight
            drawRect(OverdriveColors.border, Offset(0f, ftpY), Size(widthPx, onePx))
        }
        for (piece in geometry.pieces) {
            when {
                nowX == null || piece.xMin >= nowX -> {
                    drawPath(piece.path, piece.colour)
                }

                piece.xMax <= nowX -> {
                    drawPath(piece.path, piece.pastColour)
                }

                else -> {
                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = nowX,
                        bottom = plotHeight,
                    ) { drawPath(piece.path, piece.pastColour) }
                    clipRect(
                        left = nowX,
                        top = 0f,
                        right = widthPx,
                        bottom = plotHeight,
                    ) { drawPath(piece.path, piece.colour) }
                }
            }
        }
        if (trace.size > 1) drawTrace(trace, geometry, widthPx, plotHeight)
        if (look.ftpLine) {
            val label = geometry.ftpLabel
            if (label != null) {
                val ftpY = plotHeight - 1f / Y_MAX * plotHeight
                drawText(
                    label,
                    OverdriveColors.textTertiary,
                    Offset(
                        widthPx - label.size.width - 2.dp.toPx(),
                        ftpY - 4.dp.toPx() - label.firstBaseline,
                    ),
                )
            }
        }
        if (nowX != null) {
            val markerWidth = OverdriveDimens.chartProfileNowMarkerWidth.toPx()
            drawRect(OverdriveColors.textPrimary, Offset(nowX - markerWidth / 2f, 0f), Size(markerWidth, plotHeight))
        }
        if (look.tickEveryMinutes != null) {
            for ((minute, layout) in geometry.tickLabels) {
                val x = geometry.xOf(minute * 60f, widthPx)
                val left =
                    when {
                        minute == 0 -> x
                        x > widthPx - 20.dp.toPx() -> x - layout.size.width
                        else -> x - layout.size.width / 2f
                    }
                drawText(
                    layout,
                    OverdriveColors.textTertiary,
                    Offset(
                        left,
                        size.height - 4.dp.toPx() - layout.firstBaseline,
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawTrace(
    trace: List<TracePoint>,
    geometry: ProfileGeometry,
    widthPx: Float,
    plotHeight: Float,
) {
    val path = Path()
    trace.forEachIndexed { index, point ->
        val x = geometry.xOf(point.seconds.toFloat(), widthPx)
        val y = plotHeight - min(point.fraction, Y_MAX) / Y_MAX * plotHeight
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path,
        OverdriveColors.textPrimary,
        style = Stroke(OverdriveDimens.strokeChartLineSecondary.toPx(), join = StrokeJoin.Round),
    )
}

/** Caption style, tabular, for axis ticks and the FTP label. */
private val AxisStyle: TextStyle = OdType.captionNum
