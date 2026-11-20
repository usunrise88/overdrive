package app.overdrive.ride.overlay

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ride.RideState
import app.overdrive.ride.host.RideMapping
import app.overdrive.uikit.overlay.OverlayCoach
import app.overdrive.uikit.overlay.OverlayCoachControl
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live coach under the overlay, in a real (Robolectric) window with the real Compose content (docs/adr/0039, design/system.md §5.7): a tap that goes down on the mute control mutes
 * and does not toggle the mode, a tap anywhere else toggles it as before, a drag or a long press that begins on the control is still a drag or a long press; and the performance rules
 * hold with a coach on: the ride's numbers at rest recompose nothing, a cue or a mute recomposes the window once, and the same coach again recomposes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class OverlayCoachWindowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayViewOwner().also { it.start() }

    private val model = FakeOverlayModel()
    private val coach = MutableStateFlow(OverlayCoach.None)
    private val compositions = AtomicInteger()
    private var control: Rect? = null

    private class Recording : OverlayWindowCallbacks {
        val calls = mutableListOf<String>()
        val positions = mutableListOf<Pair<Float, Float>>()

        override fun onTap() {
            calls += "tap"
        }

        override fun onMuteTap() {
            calls += "mute"
        }

        override fun onLongPress() {
            calls += "long"
        }

        override fun onPositionChanged(
            x: Float,
            y: Float,
        ) {
            positions += x to y
        }
    }

    private val recording = Recording()

    private fun controller() =
        OverlayWindowController(
            windowManager,
            OverlayWindowMetrics(2f, PxSize(928, 168)),
            recording,
            owner,
            animationsEnabled = { false },
        )

    private fun overlayView(controller: OverlayWindowController): View {
        val ride = model.rides.value
        val settings = model.settings.value
        return ComposeView(context).apply {
            setContent {
                OverlayWindowContent(
                    states = overlayStates(model.rides),
                    initial = RideMapping.overlayState(ride.snapshot, ride.profile),
                    look = kotlinx.coroutines.flow.flowOf(settings.toOverlaySettings()),
                    initialLook = settings.toOverlaySettings(),
                    coach = coach,
                    initialCoach = coach.value,
                    onControlBounds = {
                        control = it
                        controller.setControlBounds(it)
                    },
                    onRecompose = { compositions.incrementAndGet() },
                )
            }
        }
    }

    private fun settle(millis: Long = 300) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun awaitCompositions(count: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (compositions.get() >= count) return
            Thread.sleep(10)
        }
        fail("Compositions: ${compositions.get()}, expected $count")
    }

    private fun awaitControl(): Rect {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            control?.let { return it }
            Thread.sleep(10)
        }
        fail("the control was never laid out")
        error("unreachable")
    }

    private fun touch(
        c: OverlayWindowController,
        x: Float,
        y: Float,
        holdMillis: Long = 0,
    ) {
        val down = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            at: Long,
        ) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0)
            c.rootView!!.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, down)
        send(MotionEvent.ACTION_UP, down + holdMillis)
    }

    /** The screen point of the middle of the control: the window's corner plus the control's own place. */
    private fun controlCentre(c: OverlayWindowController): Pair<Float, Float> {
        val corner = checkNotNull(c.position)
        val bounds = checkNotNull(control)
        return (corner.x + bounds.center.x) to (corner.y + bounds.center.y)
    }

    private fun show(): OverlayWindowController {
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        val c = controller()
        assertTrue(c.show(overlayView(c), null, null, 0))
        awaitCompositions(1)
        return c
    }

    // ---- Touch ----------------------------------------------------------------------------------------

    @Test
    fun aTapThatGoesDownOnTheControlMutesAndDoesNotToggleTheMode() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On)
        val c = show()
        awaitControl()
        val (x, y) = controlCentre(c)
        touch(c, x, y)
        assertEquals(listOf("mute"), recording.calls)
        c.hide()
    }

    @Test
    fun aTapOnThePanelOrBelowTheControlTogglesTheModeAsBefore() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On, plate = "Hold it steady.")
        val c = show()
        val bounds = awaitControl()
        val corner = checkNotNull(c.position)
        // On the panel: its middle.
        touch(c, corner.x + 400f, corner.y + 60f)
        // On the plate, which is under the control: not the control.
        touch(c, corner.x + 400f, corner.y + bounds.bottom + 30f)
        assertEquals(listOf("tap", "tap"), recording.calls)
        c.hide()
    }

    @Test
    fun withNoSessionTheWholeOverlayIsOnePanelAndATapIsAlwaysTheModes() {
        val c = show()
        val corner = checkNotNull(c.position)
        touch(c, corner.x + 40f, corner.y + 100f)
        assertEquals(listOf("tap"), recording.calls)
        // The control leaves while a session ends: where it was is the panel's again.
        coach.value = OverlayCoach(control = OverlayCoachControl.On)
        val bounds = awaitControl()
        val centre = controlCentre(c)
        coach.value = OverlayCoach.None
        settle()
        assertEquals(null, control)
        touch(c, centre.first, centre.second)
        assertEquals(listOf("tap", "tap"), recording.calls)
        assertTrue(bounds.width > 0f)
        c.hide()
    }

    @Test
    fun aLongPressThatBeginsOnTheControlStillOpensTheApp() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On)
        val c = show()
        awaitControl()
        val (x, y) = controlCentre(c)
        val down = SystemClock.uptimeMillis()
        c.rootView!!.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        c.rootView!!.dispatchTouchEvent(MotionEvent.obtain(down, down + 700, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals(listOf("long"), recording.calls)
        c.hide()
    }

    // ---- Performance -----------------------------------------------------------------------------------

    @Test
    fun theRidesNumbersAtRestRecomposeNothingWithTheCoachOn() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On)
        val c = show()
        awaitControl()
        settle()
        val before = compositions.get()
        repeat(100) { i -> model.rides.value = FakeOverlayModel.ride(RideState.RIDING, averagePower = 100 + i) }
        repeat(100) { model.rides.value = FakeOverlayModel.ride(RideState.RIDING, power = 200, averagePower = 7) }
        settle()
        assertEquals("nothing the overlay shows changed", before, compositions.get())
        c.hide()
    }

    @Test
    fun aCueAndAMuteEachRecomposeOnceAndTheSameCoachAgainRecomposesNothing() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On)
        val c = show()
        awaitControl()
        settle()
        val start = compositions.get()
        coach.value = OverlayCoach(control = OverlayCoachControl.On, plate = "Ten minutes at 247 watts.")
        awaitCompositions(start + 1)
        settle()
        assertEquals("the plate comes: one composition", start + 1, compositions.get())
        coach.value = OverlayCoach(control = OverlayCoachControl.On, plate = "Ten minutes at 247 watts.")
        settle()
        assertEquals("the same plate again: none", start + 1, compositions.get())
        coach.value = OverlayCoach(control = OverlayCoachControl.Muted, plate = "Ten minutes at 247 watts.")
        awaitCompositions(start + 2)
        settle()
        assertEquals("the mute: one", start + 2, compositions.get())
        coach.value = OverlayCoach(control = OverlayCoachControl.Muted)
        awaitCompositions(start + 3)
        settle()
        assertEquals("the plate goes: one", start + 3, compositions.get())
        assertNotNull(control)
        c.hide()
    }

    @Test
    fun draggingTheOverlayWithACoachOnMovesTheWindowAndNeverTouchesTheContent() {
        coach.value = OverlayCoach(control = OverlayCoachControl.On, plate = "Hold it steady.")
        val c = show()
        awaitControl()
        settle()
        val before = compositions.get()
        val updates = c.layoutUpdates
        val down = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            x: Float,
            y: Float,
        ) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            c.rootView!!.dispatchTouchEvent(event)
            event.recycle()
        }
        // A drag that begins on the control.
        val (x, y) = controlCentre(c)
        send(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..40) send(MotionEvent.ACTION_MOVE, x - step * 10f, y + step * 6f)
        assertEquals("moving only moves the window", before, compositions.get())
        assertTrue(c.layoutUpdates - updates >= 30)
        send(MotionEvent.ACTION_UP, x - 400f, y + 240f)
        settle()
        assertEquals(before, compositions.get())
        assertEquals("a drag is not a tap on the control", emptyList<String>(), recording.calls)
        assertEquals(1, recording.positions.size)
        c.hide()
    }
}
