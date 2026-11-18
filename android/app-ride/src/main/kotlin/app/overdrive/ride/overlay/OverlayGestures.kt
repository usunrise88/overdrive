package app.overdrive.ride.overlay

import app.overdrive.uikit.tokens.OverdriveDimens
import app.overdrive.uikit.tokens.OverdriveMotion

/**
 * The touch thresholds of the overlay (design/system.md §5.5): a drag starts after [slopPx] of movement, a press that
 * stays within the slop for [longPressMillis] is a long press, a shorter one is a tap.
 */
class GestureConfig(
    val slopPx: Float,
    val longPressMillis: Long = OverdriveMotion.overlayLongPressMs.toLong(),
) {
    companion object {
        /** The design's thresholds (8 dp slop, 500 ms) for a screen of the given [density] (pixels per dp). */
        fun forDensity(density: Float): GestureConfig = GestureConfig(OverdriveDimens.overlayDragSlop.value * density)
    }
}

/**
 * Tells a tap, a long press and a drag apart on the overlay window, without any Android type (so it is unit-tested with
 * plain numbers). The window moves under the finger, so coordinates must be **screen** coordinates (`rawX`, `rawY`),
 * and every drag callback carries the total movement since the finger went down.
 *
 * - Down starts a press. Moving farther than the slop makes it a **drag** for good: [Output.onDrag] with the total
 *   offset on every move, then [Output.onDragEnd] on release or cancel. A drag never becomes a tap or a long press.
 * - A press that stays within the slop is a **long press** once the finger has been down for the timeout, whether the
 *   caller's timer reports it ([timeout]) or the next event does; the release after it does nothing more.
 * - A release before the timeout within the slop is a **tap**.
 *
 * Only the primary pointer matters; the caller ignores the others.
 */
class OverlayGestureDetector(
    private val config: GestureConfig,
    private val output: Output,
) {
    interface Output {
        /** The finger is [totalDx], [totalDy] pixels from where it went down (first call: just past the slop). */
        fun onDrag(
            totalDx: Float,
            totalDy: Float,
        )

        /** The drag is over (finger up or gesture cancelled). */
        fun onDragEnd()

        fun onTap()

        fun onLongPress()
    }

    private enum class Phase { Idle, Pressed, Dragging, LongPressed }

    private var phase = Phase.Idle
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun down(
        x: Float,
        y: Float,
        timeMillis: Long,
    ) {
        phase = Phase.Pressed
        downX = x
        downY = y
        downTime = timeMillis
    }

    fun move(
        x: Float,
        y: Float,
        timeMillis: Long,
    ) {
        val dx = x - downX
        val dy = y - downY
        when (phase) {
            Phase.Pressed -> {
                if (dx * dx + dy * dy > config.slopPx * config.slopPx) {
                    phase = Phase.Dragging
                    output.onDrag(dx, dy)
                } else {
                    timeout(timeMillis)
                }
            }

            Phase.Dragging -> {
                output.onDrag(dx, dy)
            }

            // Nothing to do before a press, and nothing after a long press has been reported.
            Phase.Idle, Phase.LongPressed -> {}
        }
    }

    /** The caller's long-press timer fired at [timeMillis] (same clock as the events). Ignored unless the press is still held. */
    fun timeout(timeMillis: Long) {
        if (phase == Phase.Pressed && timeMillis - downTime >= config.longPressMillis) {
            phase = Phase.LongPressed
            output.onLongPress()
        }
    }

    fun up(
        x: Float,
        y: Float,
        timeMillis: Long,
    ) {
        move(x, y, timeMillis)
        when (phase) {
            Phase.Pressed -> output.onTap()
            Phase.Dragging -> output.onDragEnd()
            Phase.Idle, Phase.LongPressed -> Unit
        }
        phase = Phase.Idle
    }

    /** The system took the gesture away: a drag ends where it is, a press is forgotten. */
    fun cancel() {
        if (phase == Phase.Dragging) output.onDragEnd()
        phase = Phase.Idle
    }
}
