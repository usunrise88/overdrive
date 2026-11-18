package app.overdrive.ride.overlay

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ride.RideState
import app.overdrive.ride.host.RideMapping
import app.overdrive.ride.settings.AppSettings
import org.junit.Assert.assertEquals
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
 * The performance rules of spec §4.6 and §7.3 that can be proven on the JVM, with the real Compose content in a real
 * (Robolectric) window: identical updates do not recompose, and a drag moves the window without touching the content or
 * the state it reads. What only the tablet can measure (frame time while dragging, battery) is in
 * `docs/bench-checklist.md`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class OverlayRecompositionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayViewOwner().also { it.start() }

    private val model = FakeOverlayModel()
    private val compositions = AtomicInteger()

    private val callbacks =
        object : OverlayWindowCallbacks {
            val positions = mutableListOf<Pair<Float, Float>>()

            override fun onTap() = Unit

            override fun onLongPress() = Unit

            override fun onPositionChanged(
                x: Float,
                y: Float,
            ) {
                positions += x to y
            }
        }

    private fun controller() =
        OverlayWindowController(
            windowManager,
            OverlayWindowMetrics(2f, PxSize(928, 168)),
            callbacks,
            owner,
            animationsEnabled = { false },
        )

    private fun overlayView(): View {
        val ride = model.rides.value
        val settings = model.settings.value
        return ComposeView(context).apply {
            setContent {
                OverlayWindowContent(
                    states = overlayStates(model.rides),
                    initial = RideMapping.overlayState(ride.snapshot, ride.profile),
                    look = kotlinx.coroutines.flow.flowOf(settings.toOverlaySettings()),
                    initialLook = settings.toOverlaySettings(),
                    onRecompose = { compositions.incrementAndGet() },
                )
            }
        }
    }

    private fun settle(millis: Long = 300) {
        // Real time for the background collector, then the main looper for what it handed over.
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
    }

    private fun awaitComposition(count: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (compositions.get() >= count) return
            Thread.sleep(10)
        }
        fail("Compositions: ${compositions.get()}, expected $count")
    }

    @Test
    fun identicalUpdatesComposeOnceAndARealChangeComposesAgain() {
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        val c = controller()
        assertTrue(c.show(overlayView(), null, null, 0))
        awaitComposition(1)
        settle()
        val afterFirst = compositions.get()
        assertEquals("the first frame composes once", 1, afterFirst)

        // Fifty snapshots that differ only in what the overlay does not show.
        repeat(50) { i -> model.rides.value = FakeOverlayModel.ride(RideState.RIDING, averagePower = 100 + i) }
        settle()
        assertEquals("nothing the overlay shows changed", afterFirst, compositions.get())

        // The same numbers again, many times.
        repeat(50) { model.rides.value = FakeOverlayModel.ride(RideState.RIDING, power = 200, averagePower = 7) }
        settle()
        assertEquals(afterFirst, compositions.get())

        // One real change: one more composition.
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING, power = 215)
        awaitComposition(afterFirst + 1)
        settle()
        assertEquals(afterFirst + 1, compositions.get())
        c.hide()
    }

    @Test
    fun draggingMovesTheWindowAndNeverTouchesTheContentOrItsState() {
        model.rides.value = FakeOverlayModel.ride(RideState.RIDING)
        val c = controller()
        c.show(overlayView(), null, null, 0)
        awaitComposition(1)
        settle()
        val before = compositions.get()
        val settingsBefore = model.settings.value
        val updatesBefore = c.layoutUpdates

        val downTime = SystemClock.uptimeMillis()

        fun send(
            action: Int,
            x: Float,
            y: Float,
        ) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            c.rootView!!.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1800f, 100f)
        for (step in 1..60) send(MotionEvent.ACTION_MOVE, 1800f - step * 15f, 100f + step * 8f)
        assertEquals("moving only moves the window", before, compositions.get())
        assertTrue("the window moved through the window manager", c.layoutUpdates - updatesBefore >= 50)
        assertEquals("nothing is saved while it moves", 0, callbacks.positions.size)
        send(MotionEvent.ACTION_UP, 900f, 580f)
        settle()

        assertEquals("the drag did not recompose the content", before, compositions.get())
        assertEquals("one save when it ends", 1, callbacks.positions.size)
        assertEquals("the settings flow itself is the caller's to write", settingsBefore, model.settings.value)
        c.hide()
    }

    @Test
    fun onlyTheOpacityAndTheModeOfTheSettingsReachTheContent() {
        // The service feeds the content `settings.map { it.toOverlaySettings() }.distinctUntilChanged()`: a position
        // write at the end of a drag, or a change of FTP, is the same value again and recomposes nothing.
        val base = AppSettings().toOverlaySettings()
        assertEquals(base, AppSettings(overlayX = 0.1f, overlayY = 0.9f).toOverlaySettings())
        assertEquals(base, AppSettings(ftp = 300, overlayBlur = true).toOverlaySettings())
        assertEquals(0.85f, AppSettings(overlayOpacityPercent = 85).toOverlaySettings().opacity, 1e-6f)
        assertEquals(
            app.overdrive.uikit.overlay.OverlayMode.Detailed,
            AppSettings(overlayMode = app.overdrive.ride.settings.OverlayStartMode.Detailed).toOverlaySettings().mode,
        )
    }
}
