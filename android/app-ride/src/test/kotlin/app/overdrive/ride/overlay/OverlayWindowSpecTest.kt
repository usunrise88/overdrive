package app.overdrive.ride.overlay

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The flags and parameters of the overlay window (spec §4.6, overlay-notes §2): what must and must never be set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayWindowSpecTest {
    private fun has(
        flags: Int,
        flag: Int,
    ) = flags and flag == flag

    @Test
    fun theFlagsLetTheVideoAppKeepItsKeyboardAndTheRestOfTheScreen() {
        val flags = OverlayWindowSpec.flags(blur = false)
        assertTrue(has(flags, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        assertTrue(has(flags, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL))
        assertTrue(has(flags, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN))
        assertEquals(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            flags,
        )
    }

    @Test
    fun neverSecureAndNeverUntouchable() {
        for (blur in listOf(false, true)) {
            val flags = OverlayWindowSpec.flags(blur)
            // Secure would hide the overlay from screenshots and screen recordings; not touchable would make the overlay
            // unreachable for its own taps and, on Android 12, get its pass-through touches blocked.
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

    @Test
    fun blurAddsOnlyTheBlurBehindFlag() {
        val plain = OverlayWindowSpec.flags(blur = false)
        val blurred = OverlayWindowSpec.flags(blur = true)
        assertEquals(plain or WindowManager.LayoutParams.FLAG_BLUR_BEHIND, blurred)
    }

    @Test
    fun theWindowIsAnApplicationOverlayThatWrapsItsContent() {
        val params = OverlayWindowSpec.layoutParams(x = 32, y = 48)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertEquals(Gravity.TOP or Gravity.START, params.gravity)
        assertEquals(32, params.x)
        assertEquals(48, params.y)
        assertEquals("opacity is the composable's backing, never the window alpha", 1f, params.alpha, 0f)
        assertEquals(0, params.blurBehindRadius)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    }

    @Test
    fun blurSetsTheRadiusAndTheFlagTogether() {
        val params = OverlayWindowSpec.layoutParams(x = 0, y = 0, blurRadiusPx = 48)
        assertEquals(48, params.blurBehindRadius)
        assertTrue(has(params.flags, WindowManager.LayoutParams.FLAG_BLUR_BEHIND))
    }

    @Test
    fun applyBlurTogglesBothAndKeepsTheOtherFlags() {
        val params = OverlayWindowSpec.layoutParams(x = 0, y = 0)
        OverlayWindowSpec.applyBlur(params, 48)
        assertEquals(OverlayWindowSpec.flags(blur = true), params.flags)
        assertEquals(48, params.blurBehindRadius)
        OverlayWindowSpec.applyBlur(params, 0)
        assertEquals(OverlayWindowSpec.flags(blur = false), params.flags)
        assertEquals(0, params.blurBehindRadius)
    }

    @Test
    fun theBlurRadiusIs24DpAtTheScreensDensity() {
        assertEquals(24, OverlayWindowSpec.blurRadiusPx(1f))
        assertEquals(48, OverlayWindowSpec.blurRadiusPx(2f))
    }
}
