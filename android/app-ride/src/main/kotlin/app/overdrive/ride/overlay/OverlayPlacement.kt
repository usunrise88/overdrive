package app.overdrive.ride.overlay

import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point in screen pixels. */
data class PxPoint(
    val x: Int,
    val y: Int,
)

/** A size in pixels. */
data class PxSize(
    val width: Int,
    val height: Int,
)

/** The screen's bounds in pixels (the window metrics of the display the overlay is on). */
data class PxRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where the overlay window sits (design/system.md §5.5), as pure integer maths so every rule is unit-tested without a
 * window. Positions are the window's top-left corner in screen pixels; what is persisted is that corner as a fraction
 * of the screen (`overlay_x`, `overlay_y` of the settings), so a rotation or a different screen size keeps the overlay
 * where the rider put it, give or take a re-snap.
 *
 * The rules:
 * - **Drag:** the window follows the finger but never leaves the screen ([clamp] with no margin).
 * - **Release:** [snap] moves it horizontally to the nearer side edge, the snap margin (16 dp) from the edge, and clamps it
 *   vertically with the same margin. Letterbox bars are allowed, so the margin counts from the screen, not from the video.
 * - **Resize** (compact to detailed and back): [keepEdgeOnResize] keeps a window that sits at the bottom limit at the
 *   bottom, so it grows upward; anything else keeps its top.
 */
object OverlayPlacement {
    /** Distance kept from the screen edges after a release, in dp (token `overlay.edgeSnapMargin`). */
    val marginDp: Float get() = OverdriveDimens.overlayEdgeSnapMargin.value

    /** A window within this distance of the bottom limit counts as snapped to the bottom, in dp. */
    const val BOTTOM_TOLERANCE_DP: Float = 8f

    /** [position] moved the least needed to put the whole window inside [area] with [margin] to spare. */
    fun clamp(
        position: PxPoint,
        size: PxSize,
        area: PxRect,
        margin: Int = 0,
    ): PxPoint =
        PxPoint(
            x = position.x.coerceInto(area.left + margin, area.right - margin - size.width),
            y = position.y.coerceInto(area.top + margin, area.bottom - margin - size.height),
        )

    /** The release position: the nearer side edge with [margin], and the height clamped inside the margins. */
    fun snap(
        position: PxPoint,
        size: PxSize,
        area: PxRect,
        margin: Int,
    ): PxPoint {
        val centre = position.x + size.width / 2
        val x =
            if (centre < (area.left + area.right) / 2) {
                area.left + margin
            } else {
                area.right - margin - size.width
            }
        return clamp(PxPoint(x, position.y), size, area, margin)
    }

    /** The corner a window without a saved position starts in: top right, [margin] from both edges. */
    fun defaultPosition(
        size: PxSize,
        area: PxRect,
        margin: Int,
    ): PxPoint = clamp(PxPoint(area.right - margin - size.width, area.top + margin), size, area, margin)

    /**
     * The position for the saved fractions, snapped to an edge: without a saved position (null) the default corner.
     * Fractions outside 0 to 1 or not finite are treated as missing.
     */
    fun fromFractions(
        x: Float?,
        y: Float?,
        size: PxSize,
        area: PxRect,
        margin: Int,
    ): PxPoint {
        val fx = x?.takeIf { it.isFinite() }
        val fy = y?.takeIf { it.isFinite() }
        if (fx == null || fy == null) return defaultPosition(size, area, margin)
        val corner =
            PxPoint(
                area.left + (fx.coerceIn(0f, 1f) * area.width).roundToInt(),
                area.top + (fy.coerceIn(0f, 1f) * area.height).roundToInt(),
            )
        return snap(corner, size, area, margin)
    }

    /** The window's top-left corner as fractions of the screen, 0 to 1 (what the settings keep). */
    fun toFractions(
        position: PxPoint,
        area: PxRect,
    ): Pair<Float, Float> =
        Pair(
            ((position.x - area.left).toFloat() / max(1, area.width)).coerceIn(0f, 1f),
            ((position.y - area.top).toFloat() / max(1, area.height)).coerceIn(0f, 1f),
        )

    /**
     * The position after the window's height changed from [oldHeight] to [newHeight]: a window at the bottom limit
     * (within [tolerance]) stays at the bottom, anything else keeps its top, and the result is clamped inside the margins.
     */
    fun keepEdgeOnResize(
        position: PxPoint,
        oldHeight: Int,
        newSize: PxSize,
        area: PxRect,
        margin: Int,
        tolerance: Int,
    ): PxPoint {
        val bottomLimitBefore = area.bottom - margin - oldHeight
        val y = if (position.y >= bottomLimitBefore - tolerance) area.bottom - margin - newSize.height else position.y
        return clamp(PxPoint(position.x, y), newSize, area, margin)
    }

    /** `coerceIn` that does not throw when the window is larger than the room: the lower bound wins. */
    private fun Int.coerceInto(
        low: Int,
        high: Int,
    ): Int = max(low, min(this, high))
}

/**
 * The short slide to the snapped edge: [durationMillis] long, decelerating, x only (design/system.md §5.5). It runs for
 * its own duration and stops; the overlay has no continuous animation.
 */
class SnapAnimation(
    val fromX: Int,
    val toX: Int,
    val durationMillis: Long = OverdriveMotion.overlayEdgeSnapDurationMs.toLong(),
) {
    /** The x after [elapsedMillis]: eased out, exactly [toX] at the end. */
    fun xAt(elapsedMillis: Long): Int {
        if (durationMillis <= 0 || elapsedMillis >= durationMillis) return toX
        val t = (elapsedMillis.coerceAtLeast(0).toFloat() / durationMillis)
        val eased = 1f - (1f - t) * (1f - t)
        return fromX + ((toX - fromX) * eased).roundToInt()
    }

    fun isFinished(elapsedMillis: Long): Boolean = elapsedMillis >= durationMillis
}
