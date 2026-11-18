package app.overdrive.ride.overlay

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.util.concurrent.TimeUnit

/**
 * The window controller against Robolectric's window manager: the tablet at 2x (1280 x 800 dp is 2560 x 1600 px), the
 * overlay 464 x 84 dp (928 x 168 px). Drags are real `MotionEvent`s delivered to the window's root view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class OverlayWindowControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayViewOwner().also { it.start() }
    private val margin = 32

    private class Recording : OverlayWindowCallbacks {
        val calls = mutableListOf<String>()
        val positions = mutableListOf<Pair<Float, Float>>()

        override fun onTap() {
            calls += "tap"
        }

        override fun onLongPress() {
            calls += "long"
        }

        override fun onPositionChanged(
            x: Float,
            y: Float,
        ) {
            calls += "position"
            positions += x to y
        }
    }

    private val recording = Recording()

    private fun controller(animations: Boolean = false) =
        OverlayWindowController(
            windowManager,
            OverlayWindowMetrics(density = 2f, initialSize = PxSize(928, 168)),
            recording,
            owner,
            animationsEnabled = { animations },
        )

    /** A view of exactly [height] pixels at the overlay's 928 px width, like the Compose content that wraps its size. */
    private class FixedView(
        context: Context,
        var wantedHeight: Int,
    ) : View(context) {
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) = setMeasuredDimension(928, wantedHeight)
    }

    private fun content(height: Int = 168): View = FixedView(context, height)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun views(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(windowManager).views

    private fun params(controller: OverlayWindowController) =
        controller.rootView!!.layoutParams as WindowManager.LayoutParams

    private class Finger(
        private val target: View,
    ) {
        private var downTime = 0L

        fun down(
            x: Float,
            y: Float,
        ) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y)
        }

        fun move(
            x: Float,
            y: Float,
        ) = send(MotionEvent.ACTION_MOVE, x, y)

        fun up(
            x: Float,
            y: Float,
        ) = send(MotionEvent.ACTION_UP, x, y)

        private fun send(
            action: Int,
            x: Float,
            y: Float,
        ) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun addsOneWindowWithTheOverlayFlagsAtTheDefaultCorner() {
        val c = controller()
        assertTrue(c.show(content(), x = null, y = null, blurRadiusPx = 0))
        idle()
        assertEquals(1, views().size)
        val p = params(c)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, p.type)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, p.width)
        assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, p.height)
        // Top right, 16 dp (32 px) from both edges.
        assertEquals(PxPoint(2560 - margin - 928, margin), c.position)
        c.hide()
        assertEquals(0, views().size)
    }

    @Test
    fun showingTwiceKeepsOneWindowAndHidingTwiceIsHarmless() {
        val c = controller()
        c.show(content(), null, null, 0)
        c.show(content(), null, null, 0)
        idle()
        assertEquals(1, views().size)
        c.hide()
        c.hide()
        assertEquals(0, views().size)
    }

    @Test
    fun startsAtTheSavedPositionSnappedToAnEdge() {
        val c = controller()
        c.show(content(), x = 0.01f, y = 0.5f, blurRadiusPx = 0)
        idle()
        assertEquals(PxPoint(margin, 800), c.position)
        c.hide()
    }

    @Test
    fun dragFollowsTheFingerAndReleaseSnapsToTheNearerEdgeAndSavesThePositionOnce() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        val start = c.position!!
        val updatesBefore = c.layoutUpdates

        finger.down(1800f, 100f)
        finger.move(1790f, 104f) // inside the 16 px slop: nothing moves
        assertEquals(start, c.position)
        finger.move(1700f, 300f)
        assertEquals(PxPoint(start.x - 100, start.y + 200), c.position)
        finger.move(1500f, 500f)
        assertEquals(PxPoint(start.x - 300, start.y + 400), c.position)
        assertEquals("position is saved when the drag ends, not while it moves", 0, recording.positions.size)
        finger.move(900f, 800f)
        finger.up(900f, 800f)

        // Dropped left of the middle: snapped to the left edge with the 16 dp margin, height kept.
        assertEquals(PxPoint(margin, start.y + 700), c.position)
        assertEquals(1, recording.positions.size)
        assertEquals(margin / 2560f, recording.positions.single().first, 1e-4f)
        assertEquals((start.y + 700) / 1600f, recording.positions.single().second, 1e-4f)
        assertTrue("a drag is not a tap", "tap" !in recording.calls)
        // One window update per distinct position: three moves and the snap, never one per touch event.
        assertEquals(4, c.layoutUpdates - updatesBefore)
        c.hide()
    }

    @Test
    fun theWindowNeverLeavesTheScreenWhileDragging() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        finger.down(2000f, 100f)
        finger.move(9000f, 9000f)
        assertEquals(PxPoint(2560 - 928, 1600 - 168), c.position)
        finger.move(-9000f, -9000f)
        assertEquals(PxPoint(0, 0), c.position)
        finger.up(-9000f, -9000f)
        assertEquals(PxPoint(margin, margin), c.position)
        c.hide()
    }

    @Test
    fun aTapIsReportedOnceAndMovesNothing() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        val start = c.position
        finger.down(1800f, 100f)
        finger.up(1802f, 101f)
        assertEquals(listOf("tap"), recording.calls)
        assertEquals(start, c.position)
        c.hide()
    }

    @Test
    fun holdingPastHalfASecondIsALongPressReportedWhileTheFingerIsStillDown() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        finger.down(1800f, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(499, TimeUnit.MILLISECONDS)
        assertEquals(emptyList<String>(), recording.calls)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf("long"), recording.calls)
        finger.up(1800f, 100f)
        assertEquals(listOf("long"), recording.calls)
        c.hide()
    }

    @Test
    fun movingCancelsTheLongPressTimer() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        finger.down(1800f, 100f)
        finger.move(1600f, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS)
        finger.up(1600f, 100f)
        assertEquals(listOf("position"), recording.calls)
        c.hide()
    }

    @Test
    fun theSlideToTheEdgeRunsAndEndsOnTheSnappedPosition() {
        val c = controller(animations = true)
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        finger.down(1800f, 100f)
        finger.move(900f, 100f)
        finger.up(900f, 100f)
        // Released left of the middle at x = 700: the slide has not run yet.
        assertEquals(700, c.position!!.x)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(margin, c.position!!.x)
        c.hide()
    }

    @Test
    fun hidingInTheMiddleOfTheSlideStopsIt() {
        val c = controller(animations = true)
        c.show(content(), null, null, 0)
        idle()
        val finger = Finger(c.rootView!!)
        finger.down(1800f, 100f)
        finger.move(900f, 100f)
        finger.up(900f, 100f)
        c.hide()
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(0, views().size)
    }

    @Test
    fun aScreenChangePutsTheWindowBackInsideTheNewScreen() {
        val c = controller()
        c.show(content(), x = 0.625f, y = 0.1f, blurRadiusPx = 0)
        idle()
        assertEquals(2560 - margin - 928, c.position!!.x)
        // A narrower screen: 800 x 1280 dp in portrait.
        org.robolectric.RuntimeEnvironment.setQualifiers("w800dp-h1280dp-xhdpi")
        c.onScreenChanged()
        assertEquals(1600 - margin - 928, c.position!!.x)
        c.hide()
    }

    @Test
    fun aWindowAtTheBottomGrowsUpwardWhenTheContentGetsTaller() {
        val view = FixedView(context, 168)
        val c = controller()
        c.show(view, x = 0.9f, y = 1f, blurRadiusPx = 0)
        idle()
        assertEquals(1600 - margin - 168, c.position!!.y)

        view.wantedHeight = 564 // compact to detailed
        view.requestLayout()
        idle()
        assertEquals(1600 - margin - 564, c.position!!.y)
        assertEquals("the moved position is saved", 1, recording.positions.size)

        view.wantedHeight = 168 // and back
        view.requestLayout()
        idle()
        assertEquals(1600 - margin - 168, c.position!!.y)
        c.hide()
    }

    @Test
    fun aWindowInTheMiddleKeepsItsTopWhenTheContentGetsTaller() {
        val view = FixedView(context, 168)
        val c = controller()
        c.show(view, x = 0.9f, y = 0.3f, blurRadiusPx = 0)
        idle()
        val top = c.position!!.y
        view.wantedHeight = 564
        view.requestLayout()
        idle()
        assertEquals(top, c.position!!.y)
        assertEquals("nothing moved, nothing to save", 0, recording.positions.size)
        c.hide()
    }

    @Test
    fun aSavedPositionTooLowForTheRealHeightIsPulledUpWithoutBeingSaved() {
        val c = controller()
        c.show(FixedView(context, 564), x = 0.9f, y = 1f, blurRadiusPx = 0) // the window starts as if 168 px high
        idle()
        assertEquals(1600 - margin - 564, c.position!!.y)
        assertEquals(0, recording.positions.size)
        c.hide()
    }

    @Test
    fun blurIsSwitchedOnAndOffThroughTheWindowParameters() {
        val c = controller()
        c.show(content(), null, null, blurRadiusPx = 48)
        idle()
        assertTrue(params(c).flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
        assertEquals(48, params(c).blurBehindRadius)
        c.setBlur(0)
        assertEquals(0, params(c).flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        assertEquals(0, params(c).blurBehindRadius)
        c.hide()
    }

    @Test
    fun theRootIsATouchRegionOfItsOwn() {
        val c = controller()
        c.show(content(), null, null, 0)
        idle()
        assertNotNull(c.rootView)
        c.hide()
    }
}
