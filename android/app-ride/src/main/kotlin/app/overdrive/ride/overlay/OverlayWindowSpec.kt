package app.overdrive.ride.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import app.overdrive.uikit.tokens.OverdriveDimens
import kotlin.math.roundToInt

/**
 * The parameters of the overlay window (spec §4.6, design/system.md §5, docs/android/overlay-notes.md §2). Kept in one
 * place so the flags that matter are asserted by a test:
 *
 * - `TYPE_APPLICATION_OVERLAY`, which needs the "Display over other apps" permission.
 * - `FLAG_NOT_FOCUSABLE`: the window never takes key focus, so the video app keeps its keyboard and Back button.
 * - `FLAG_NOT_TOUCH_MODAL`: touches outside the window's own bounds go to the app behind it. The window is
 *   `WRAP_CONTENT`, so its bounds are the overlay itself and the rest of the screen stays usable.
 * - `FLAG_LAYOUT_IN_SCREEN`: x and y are screen coordinates, whatever the system bars do.
 * - Never `FLAG_SECURE` (the rider may screenshot or screen-record the numbers) and never `FLAG_NOT_TOUCHABLE` (the
 *   overlay takes its own taps and drags, and Android 12 blocks touches through overlay windows that use it).
 * - Translucent pixel format; the opacity is the composable's backing colour, not the window's alpha.
 * - Optional blur behind the window: `FLAG_BLUR_BEHIND` and a blur radius, only when asked for and only where the
 *   system allows cross-window blur.
 */
object OverlayWindowSpec {
    const val TYPE: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** The blur radius behind the window in pixels (token `overlay.blurRadius`, 24 dp). */
    fun blurRadiusPx(density: Float): Int = (OverdriveDimens.overlayBlurRadius.value * density).roundToInt()

    /** The window flags, with blur behind when [blur] is true. */
    fun flags(blur: Boolean): Int {
        val base =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return if (blur) base or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else base
    }

    /** Layout parameters for the window at [x], [y] (screen pixels, top-left), with [blurRadiusPx] blur or 0 for none. */
    fun layoutParams(
        x: Int,
        y: Int,
        blurRadiusPx: Int = 0,
    ): WindowManager.LayoutParams =
        WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                TYPE,
                flags(blur = blurRadiusPx > 0),
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
                title = "Overdrive overlay"
                if (blurRadiusPx > 0) blurBehindRadius = blurRadiusPx
            }

    /** The same parameters with blur switched on or off, keeping everything else. */
    fun applyBlur(
        params: WindowManager.LayoutParams,
        blurRadiusPx: Int,
    ) {
        params.flags = flags(blur = blurRadiusPx > 0)
        params.blurBehindRadius = blurRadiusPx.coerceAtLeast(0)
    }
}
