package app.overdrive.uikit.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OdType
import app.overdrive.uikit.tokens.OverdriveColors

/** The text at one axis of a radar: the duration over the watts. A null [watts] is a duration without data and prints "—". */
@Immutable
class RadarLabel(
    val duration: String,
    val watts: String?,
    val unit: String? = null,
)

/** One arc of the ring around the plot: the axes [firstAxis] to [lastAxis] in [color] (a skill). */
@Immutable
class RadarSegment(
    val firstAxis: Int,
    val lastAxis: Int,
    val color: Color,
)

/**
 * Everything the radar draws, as plain numbers (design/companion.md §6.11, §14): built once per (data, window) by the screen's model and
 * compared by identity, so the chart builds its paths and text once per size. The model, not the chart, turns watts into radii, so the
 * chart knows nothing about power.
 *
 * @property main the outline of the chosen window, 0 to 1 of the plot's radius per axis, NaN for an axis without data.
 * @property reference the all-time outline drawn beneath it, or null when there is none (All time is the window, or no weight).
 * @property rings the fractions of the radius that get a hairline circle.
 * @property wash whether the main outline is filled: only when all twelve axes have data.
 */
@Immutable
class RadarSpec(
    val labels: List<RadarLabel>,
    val main: DoubleArray,
    val reference: DoubleArray?,
    val rings: List<Float>,
    val segments: List<RadarSegment>,
    val wash: Boolean,
) {
    init {
        require(labels.size == RadarGeometry.AXES && main.size == RadarGeometry.AXES) { "A radar has twelve axes" }
        require(reference == null || reference.size == RadarGeometry.AXES) { "A radar has twelve axes" }
    }
}

/**
 * The Power skills radar: twelve axes clockwise from the top, a split ring around it, two outlines (the window's in text 1, all time's
 * beneath it in text 3) and a duration over its watts at each axis. Drawn **once** per (spec, size): no animation, the paths, the arcs and
 * every text layout live in `drawWithCache`, and the selected axis (an `Int?`, read in the draw phase) only redraws. At a large font the
 * label column grows and the plot shrinks instead of clipping (the text is never scaled down); a tap picks the axis by angle anywhere in
 * the box. One node for TalkBack, described by [description].
 *
 * @param selectedAxis the axis whose sheet is open, or null; read while drawing.
 */
@Composable
fun RadarChart(
    spec: RadarSpec,
    selectedAxis: () -> Int?,
    onAxis: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tap by rememberUpdatedState(onAxis)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val texts = remember(spec, measurer, density) { RadarTexts.of(spec, measurer) }
        val layout =
            remember(spec, widthPx, texts) {
                RadarGeometry.fit(widthPx / density.density, texts.widthsDp(density), texts.heightPx / density.density)
            }
        val build: CacheDrawScope.() -> DrawResult =
            remember(spec, layout, texts, selectedAxis) {
                {
                    val painter = RadarPainter(spec, layout, texts, this)
                    onDrawWithContent { painter.draw(this, selectedAxis()) }
                }
            }
        Box(
            Modifier
                .fillMaxWidth()
                .height(layout.height.dp)
                .semantics { contentDescription = description }
                .pointerInput(layout) {
                    detectTapGestures { offset ->
                        val scale = density.density
                        tap(RadarGeometry.axisAt(offset.x / scale, offset.y / scale, layout.cx, layout.cy))
                    }
                }.drawWithCache(build),
        )
    }
}

/** The measured text of every label, normal and selected, made once per (spec, density). */
private class RadarTexts(
    val normal: List<Pair<TextLayoutResult, TextLayoutResult>>,
    val selected: List<Pair<TextLayoutResult, TextLayoutResult>>,
) {
    /** The block height in pixels: the two lines of the tallest label. */
    val heightPx: Float = normal.maxOf { (a, b) -> (a.size.height + b.size.height).toFloat() }

    fun widthsDp(density: Density): List<Float> =
        normal.indices.map { i ->
            maxOf(
                normal[i].first.size.width,
                normal[i].second.size.width,
                selected[i].first.size.width,
                selected[i].second.size.width,
            ) / density.density
        }

    companion object {
        fun of(
            spec: RadarSpec,
            measurer: TextMeasurer,
        ): RadarTexts {
            fun duration(
                label: RadarLabel,
                color: Color,
            ) = measurer.measure(
                AnnotatedString(label.duration),
                OdType.captionNum.copy(color = color),
                maxLines = 1,
                softWrap = false,
            )

            fun watts(
                label: RadarLabel,
                weight: FontWeight,
            ) = measurer.measure(
                wattsText(label, weight),
                OdType.labelNum.copy(color = OverdriveColors.textTertiary),
                maxLines = 1,
                softWrap = false,
            )
            return RadarTexts(
                spec.labels.map { duration(it, OverdriveColors.textTertiary) to watts(it, FontWeight.SemiBold) },
                spec.labels.map { duration(it, OverdriveColors.textPrimary) to watts(it, FontWeight.Bold) },
            )
        }

        /** "930 W": the figure in 600 text 1 and the unit in 500 text 2, or "—" in text 3. */
        fun wattsText(
            label: RadarLabel,
            weight: FontWeight,
        ): AnnotatedString =
            buildAnnotatedString {
                if (label.watts == null) {
                    withStyle(
                        SpanStyle(color = OverdriveColors.textTertiary, fontWeight = FontWeight.Medium),
                    ) { append("—") }
                } else {
                    withStyle(
                        SpanStyle(color = OverdriveColors.textPrimary, fontWeight = weight),
                    ) { append(label.watts) }
                    if (label.unit != null) {
                        withStyle(SpanStyle(color = OverdriveColors.textSecondary, fontWeight = FontWeight.Medium)) {
                            append(" ${label.unit}")
                        }
                    }
                }
            }
    }
}

/** Draws a radar from what [RadarGeometry] decided; the paths and arcs are made once in the constructor. */
private class RadarPainter(
    private val spec: RadarSpec,
    private val layout: RadarGeometry.Layout,
    private val texts: RadarTexts,
    density: Density,
) {
    private val d = density.density
    private val cx = layout.cx * d
    private val cy = layout.cy * d
    private val radius = layout.radius * d
    private val px1 = d
    private val ringRadius = (layout.radius + RadarGeometry.RING_OFFSET) * d

    private fun at(
        axis: Int,
        value: Double,
    ): Offset {
        val r = RadarGeometry.pointRadius(value, radius, RadarGeometry.MIN_POINT * d)
        return Offset(
            cx + (RadarGeometry.unitX(axis) * r).toFloat(),
            cy + (RadarGeometry.unitY(axis) * r).toFloat(),
        )
    }

    private class Shape(
        val path: Path,
        val dots: List<Offset>,
    )

    private fun shapeOf(values: DoubleArray): Shape {
        val outline = RadarGeometry.outline(values)
        val path = Path()
        for (run in outline.runs) {
            if (run.size == 1) continue
            for ((n, axis) in run.withIndex()) {
                val p = at(axis, values[axis])
                if (n == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            if (outline.closed) path.close()
        }
        return Shape(path, outline.isolated.map { at(it, values[it]) })
    }

    private val main = shapeOf(spec.main)
    private val reference = spec.reference?.let(::shapeOf)

    /** The arcs of the ring: Butt caps so a round cap does not eat the gap. */
    private val arcs: List<Triple<RadarSegment, Float, Float>> =
        spec.segments.map {
            val arc = RadarGeometry.arc(it.firstAxis, it.lastAxis, ringRadius / d)
            Triple(it, arc.startDegrees, arc.sweepDegrees)
        }

    fun draw(
        scope: DrawScope,
        selected: Int?,
    ) = with(scope) {
        for (axis in 0 until RadarGeometry.AXES) {
            val to =
                Offset(
                    cx + (RadarGeometry.unitX(axis) * radius).toFloat(),
                    cy + (RadarGeometry.unitY(axis) * radius).toFloat(),
                )
            drawLine(
                if (axis == selected) OverdriveColors.textSecondary else OverdriveColors.border,
                Offset(cx, cy),
                to,
                strokeWidth = px1,
            )
        }
        for (fraction in spec.rings) {
            drawCircle(
                OverdriveColors.border,
                radius * fraction,
                Offset(cx, cy),
                style = Stroke(px1),
            )
        }
        drawCircle(OverdriveColors.borderStrong, radius, Offset(cx, cy), style = Stroke(px1))
        for ((segment, start, sweep) in arcs) {
            drawArc(
                segment.color,
                start,
                sweep,
                useCenter = false,
                topLeft = Offset(cx - ringRadius, cy - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(RadarGeometry.RING_WIDTH * d),
            )
        }
        if (spec.wash) drawPath(main.path, OverdriveColors.textPrimary.copy(alpha = WASH_ALPHA), style = Fill)
        reference?.let { outline(it, OverdriveColors.textTertiary, 1.5f * d) }
        outline(main, OverdriveColors.textPrimary, 2f * d)
        if (selected != null) {
            selectedDot(spec.reference?.getOrNull(selected), selected, OverdriveColors.textTertiary)
            selectedDot(spec.main[selected], selected, OverdriveColors.textPrimary)
        }
        labels(selected)
    }

    private fun DrawScope.outline(
        shape: Shape,
        color: Color,
        width: Float,
    ) {
        drawPath(shape.path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (dot in shape.dots) {
            drawCircle(OverdriveColors.surface1, 3f * d + 1.5f * d, dot)
            drawCircle(color, 3f * d, dot)
        }
    }

    private fun DrawScope.selectedDot(
        value: Double?,
        axis: Int,
        color: Color,
    ) {
        if (value == null || value.isNaN()) return
        val centre = at(axis, value)
        drawCircle(OverdriveColors.surface1, 4f * d + 2f * d, centre)
        drawCircle(color, 4f * d, centre)
    }

    private fun DrawScope.labels(selected: Int?) {
        for (axis in 0 until RadarGeometry.AXES) {
            val (top, bottom) = if (axis == selected) texts.selected[axis] else texts.normal[axis]
            val box = layout.boxes[axis]
            val c = RadarGeometry.unitX(axis)
            val blockWidth = (box.right - box.left) * d

            fun xOf(line: TextLayoutResult): Float =
                when {
                    kotlin.math.abs(c) < CENTRED_BELOW -> box.left * d + (blockWidth - line.size.width) / 2f
                    c > 0 -> box.left * d
                    else -> box.right * d - line.size.width
                }
            val y = box.top * d
            drawLabel(top, Color.Unspecified, Offset(xOf(top), y))
            drawLabel(bottom, Color.Unspecified, Offset(xOf(bottom), y + top.size.height))
        }
    }

    private companion object {
        const val WASH_ALPHA = 0.10f
        const val CENTRED_BELOW = 0.3
    }
}

/**
 * The level bar of a skill row (design/companion.md §6.11): nine segments across the full width, 6 dp high with 2 dp gaps, the first
 * rounded on the left and the last on the right (3 dp). Segments 1 to [level] take [color], the rest surface 3. Hidden from TalkBack:
 * the row's words say the level.
 */
@Composable
fun LevelBar(
    level: Int,
    color: Color,
    modifier: Modifier = Modifier,
    segments: Int = LEVELS,
) {
    Row(
        modifier.fillMaxWidth().height(6.dp),
        horizontalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(2.dp),
    ) {
        for (i in 1..segments) {
            val shape =
                RoundedCornerShape(
                    topStart = if (i == 1) 3.dp else 0.dp,
                    bottomStart = if (i == 1) 3.dp else 0.dp,
                    topEnd = if (i == segments) 3.dp else 0.dp,
                    bottomEnd = if (i == segments) 3.dp else 0.dp,
                )
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(if (i <= level) color else OverdriveColors.surface3, shape),
            )
        }
    }
}

private const val LEVELS = 9
