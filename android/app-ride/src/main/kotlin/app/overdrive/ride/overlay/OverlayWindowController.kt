package app.overdrive.ride.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** What the window reports back to its owner: the three gestures that mean something outside the window. */
internal interface OverlayWindowCallbacks {
    /** A tap: toggle compact and detailed. */
    fun onTap()

    /** A long press: open the full app. */
    fun onLongPress()

    /** The position changed for good (drag released, or the height changed at the bottom edge): save it, as 0 to 1 fractions. */
    fun onPositionChanged(
        x: Float,
        y: Float,
    )
}

/** Where the window's content is and how big it should start, in pixels, for a screen of [density]. */
internal class OverlayWindowMetrics(
    val density: Float,
    /** The size the window has before its first layout (the content wraps and then reports its true size). */
    val initialSize: PxSize,
)

/**
 * Owns the overlay window: adds [content] to the [WindowManager] with the parameters of [OverlayWindowSpec], moves it
 * under the finger, snaps it to an edge on release and tells [callbacks] about taps, long presses and the final
 * position (ADR 0006, design/system.md §5.5).
 *
 * **Performance contract.** Dragging changes `LayoutParams.x` and `y` and calls `updateViewLayout`, nothing else: no state
 * the composition reads is touched, so the content never recomposes while it moves, and the screen's bounds are read once
 * per gesture (at the finger-down, because the metrics call is an IPC), never per move event. Position is persisted when
 * the drag ends, not while it moves. The only animation is the 180 ms slide to the edge after a release (x only), driven
 * by the choreographer for exactly its own duration and switched off when the system's animations are off.
 *
 * Everything runs on the main thread.
 */
internal class OverlayWindowController(
    private val windowManager: WindowManager,
    private val metrics: OverlayWindowMetrics,
    private val callbacks: OverlayWindowCallbacks,
    private val viewOwner: OverlayViewOwner,
    private val animationsEnabled: () -> Boolean = { true },
) {
    private var root: OverlayRootLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var size = metrics.initialSize
    private var fractions: Pair<Float, Float>? = null
    private var blurRadiusPx = 0
    private var dragOrigin = PxPoint(0, 0)
    private var dragArea: PxRect? = null
    private var snapFrame: Choreographer.FrameCallback? = null
    private var pendingLongPress: Runnable? = null

    /** How many times the window was moved or resized through the window manager (for tests and measuring). */
    var layoutUpdates: Int = 0
        private set

    val isShown: Boolean get() = root != null

    /** The window's root view, for tests that need to deliver touches to it. */
    internal val rootView: View? get() = root

    /** The window's top-left corner in screen pixels, or null while it is not shown. */
    val position: PxPoint? get() = params?.let { PxPoint(it.x, it.y) }

    private val margin: Int get() = (OverlayPlacement.marginDp * metrics.density).roundToInt()
    private val bottomTolerance: Int get() = (OverlayPlacement.BOTTOM_TOLERANCE_DP * metrics.density).roundToInt()

    private val detector =
        OverlayGestureDetector(
            GestureConfig.forDensity(metrics.density),
            object : OverlayGestureDetector.Output {
                override fun onDrag(
                    totalDx: Float,
                    totalDy: Float,
                ) = drag(totalDx, totalDy)

                override fun onDragEnd() = release()

                override fun onTap() = callbacks.onTap()

                override fun onLongPress() = callbacks.onLongPress()
            },
        )

    /**
     * Adds [content] at the saved position ([x], [y] as fractions of the screen, or null for the default corner) and
     * returns true, or false when the system refuses the window (the permission was revoked).
     */
    fun show(
        content: View,
        x: Float?,
        y: Float?,
        blurRadiusPx: Int,
    ): Boolean {
        if (root != null) return true
        this.blurRadiusPx = blurRadiusPx
        fractions = if (x != null && y != null) Pair(x, y) else null
        size = metrics.initialSize
        val start = OverlayPlacement.fromFractions(x, y, size, screenArea(), margin)
        val layout = OverlayRootLayout(content.context, TouchTarget())
        viewOwner.attachTo(layout)
        layout.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        layout.addOnLayoutChangeListener { _, left, top, right, bottom, _, oldTop, _, oldBottom ->
            onLaidOut(PxSize(right - left, bottom - top), oldHeight = oldBottom - oldTop)
        }
        val layoutParams = OverlayWindowSpec.layoutParams(start.x, start.y, blurRadiusPx)
        try {
            windowManager.addView(layout, layoutParams)
        } catch (error: RuntimeException) {
            // BadTokenException or SecurityException: the permission is gone. The ride goes on without the overlay.
            return false
        }
        root = layout
        params = layoutParams
        return true
    }

    /** Removes the window. Safe to call when it is not shown. */
    fun hide() {
        cancelSnap()
        cancelLongPress()
        val layout = root ?: return
        root = null
        params = null
        dragArea = null
        try {
            windowManager.removeView(layout)
        } catch (error: IllegalArgumentException) {
            // Already gone (the system removed it with the app's permission).
        }
    }

    /** Switches blur behind the window on ([radiusPx] above 0) or off while it is shown. */
    fun setBlur(radiusPx: Int) {
        blurRadiusPx = radiusPx
        val current = params ?: return
        OverlayWindowSpec.applyBlur(current, radiusPx)
        update()
    }

    /** The screen changed (rotation, another size): put the window back where the saved position says, inside the screen. */
    fun onScreenChanged() {
        val current = params ?: return
        cancelSnap()
        val saved = fractions
        val target =
            if (saved != null) {
                OverlayPlacement.fromFractions(saved.first, saved.second, size, screenArea(), margin)
            } else {
                OverlayPlacement.defaultPosition(size, screenArea(), margin)
            }
        if (target.x != current.x || target.y != current.y) moveTo(target)
    }

    // ---- Layout -------------------------------------------------------------------------------------

    /** The window has been laid out at [newSize]: keep it on screen, and at the bottom edge if it was there. */
    private fun onLaidOut(
        newSize: PxSize,
        oldHeight: Int,
    ) {
        val current = params ?: return
        val area = screenArea()
        val before = PxPoint(current.x, current.y)
        size = newSize
        if (oldHeight == 0) {
            // First layout: the true height is known now. Nothing moves except to fit the window on screen.
            val fitted = OverlayPlacement.clamp(before, newSize, area, margin)
            if (fitted != before) moveTo(fitted)
            return
        }
        if (newSize.height == oldHeight) return
        val resized = OverlayPlacement.keepEdgeOnResize(before, oldHeight, newSize, area, margin, bottomTolerance)
        if (resized != before) {
            moveTo(resized)
            persist(resized, area)
        }
    }

    // ---- Drag and snap --------------------------------------------------------------------------------

    private fun drag(
        totalDx: Float,
        totalDy: Float,
    ) {
        cancelLongPress()
        val area = dragArea ?: screenArea().also { dragArea = it }
        val wanted = PxPoint(dragOrigin.x + totalDx.roundToInt(), dragOrigin.y + totalDy.roundToInt())
        val next = OverlayPlacement.clamp(wanted, size, area, margin = 0)
        val current = params ?: return
        if (next.x != current.x || next.y != current.y) moveTo(next)
    }

    private fun release() {
        val current = params ?: return
        val area = dragArea ?: screenArea()
        dragArea = null
        val here = PxPoint(current.x, current.y)
        val target = OverlayPlacement.snap(here, size, area, margin)
        persist(target, area)
        if (target.y != here.y) moveTo(PxPoint(here.x, target.y))
        if (target.x == here.x) return
        if (animationsEnabled()) slideTo(target.x) else moveTo(PxPoint(target.x, target.y))
    }

    /** Keeps [position] as the one to restore after a screen change and reports it for saving. */
    private fun persist(
        position: PxPoint,
        area: PxRect,
    ) {
        val saved = OverlayPlacement.toFractions(position, area)
        fractions = saved
        callbacks.onPositionChanged(saved.first, saved.second)
    }

    private fun slideTo(targetX: Int) {
        cancelSnap()
        val animation = SnapAnimation(fromX = params?.x ?: return, toX = targetX)
        val choreographer = Choreographer.getInstance()
        var startMillis = -1L
        val callback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val frameMillis = frameTimeNanos / NANOS_PER_MILLI
                    if (startMillis < 0) startMillis = frameMillis
                    val elapsed = frameMillis - startMillis
                    val y = params?.y ?: return
                    moveTo(PxPoint(animation.xAt(elapsed), y))
                    if (animation.isFinished(elapsed)) snapFrame = null else choreographer.postFrameCallback(this)
                }
            }
        snapFrame = callback
        choreographer.postFrameCallback(callback)
    }

    private fun cancelSnap() {
        snapFrame?.let { Choreographer.getInstance().removeFrameCallback(it) }
        snapFrame = null
    }

    private fun cancelLongPress() {
        pendingLongPress?.let { root?.removeCallbacks(it) }
        pendingLongPress = null
    }

    private fun moveTo(position: PxPoint) {
        val current = params ?: return
        current.x = position.x
        current.y = position.y
        update()
    }

    private fun update() {
        val layout = root ?: return
        val current = params ?: return
        try {
            windowManager.updateViewLayout(layout, current)
            layoutUpdates++
        } catch (error: IllegalArgumentException) {
            // The window was removed by the system between the check and the call.
        }
    }

    private fun screenArea(): PxRect {
        val bounds = windowManager.currentWindowMetrics.bounds
        return PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    // ---- Touches --------------------------------------------------------------------------------------

    private inner class TouchTarget : OverlayTouchTarget {
        override fun onDown(
            rawX: Float,
            rawY: Float,
            timeMillis: Long,
        ) {
            cancelSnap()
            cancelLongPress()
            val current = params ?: return
            dragOrigin = PxPoint(current.x, current.y)
            dragArea = null
            detector.down(rawX, rawY, timeMillis)
            val timer = Runnable { detector.timeout(SystemClock.uptimeMillis()) }
            pendingLongPress = timer
            root?.postDelayed(timer, GestureConfig.forDensity(metrics.density).longPressMillis)
        }

        override fun onMove(
            rawX: Float,
            rawY: Float,
            timeMillis: Long,
        ) = detector.move(rawX, rawY, timeMillis)

        override fun onUp(
            rawX: Float,
            rawY: Float,
            timeMillis: Long,
        ) {
            cancelLongPress()
            detector.up(rawX, rawY, timeMillis)
        }

        override fun onCancel() {
            cancelLongPress()
            detector.cancel()
        }

        // Posted: the window context's own configuration may update a moment after the view's, and the metrics come from it.
        override fun onConfigurationChanged() {
            root?.post { onScreenChanged() }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** What the window's root view reports; implemented by the controller. */
internal interface OverlayTouchTarget {
    fun onDown(
        rawX: Float,
        rawY: Float,
        timeMillis: Long,
    )

    fun onMove(
        rawX: Float,
        rawY: Float,
        timeMillis: Long,
    )

    fun onUp(
        rawX: Float,
        rawY: Float,
        timeMillis: Long,
    )

    fun onCancel()

    fun onConfigurationChanged()
}

/**
 * The window's root: takes every touch inside the window for itself (the composable has no gestures and the overlay's
 * bounds are its only touch region) and passes the primary pointer's screen coordinates on. A plain `FrameLayout`, so the
 * content below it is whatever view the service gives it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class OverlayRootLayout(
    context: Context,
    private val target: OverlayTouchTarget,
) : FrameLayout(context) {
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> target.onDown(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_MOVE -> target.onMove(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_UP -> target.onUp(event.rawX, event.rawY, event.eventTime)
            MotionEvent.ACTION_CANCEL -> target.onCancel()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        target.onConfigurationChanged()
    }
}
