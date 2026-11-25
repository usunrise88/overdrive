package app.overdrive.core.control

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The coroutine around the detector, on virtual time: the Click's changes in, actions out at the right moments. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandlebarControlsTest {
    private class Rig(
        val scope: TestScope,
        var mapping: ButtonMapping = ButtonMapping.DEFAULT,
        timing: GestureTiming = GestureTiming(),
        throwOn: Set<ClickAction> = emptySet(),
    ) {
        val changes = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 64)
        val fired = mutableListOf<String>()
        private var held = 0
        val controls =
            HandlebarControls(
                scope = scope.backgroundScope,
                changes = changes,
                mapping = { mapping },
                onAction = {
                    fired += "$it@${scope.testScheduler.currentTime}"
                    if (it in throwOn) error("the action failed")
                },
                timing = timing,
                nowMillis = { scope.testScheduler.currentTime },
            )

        init {
            controls.start()
            scope.runCurrent()
        }

        fun press(mask: Int) {
            held = held or mask
            changes.tryEmit(ButtonChange(pressed = mask, released = 0, held = held, timestampNanos = 0))
            scope.runCurrent()
        }

        fun release(mask: Int) {
            held = held and mask.inv()
            changes.tryEmit(ButtonChange(pressed = 0, released = mask, held = held, timestampNanos = 0))
            scope.runCurrent()
        }

        fun frame(newHeld: Int) {
            val pressed = newHeld and held.inv()
            val released = held and newHeld.inv()
            held = newHeld
            changes.tryEmit(ButtonChange(pressed, released, held, timestampNanos = 0))
            scope.runCurrent()
        }

        fun lostLink() {
            val released = held
            held = 0
            changes.tryEmit(ButtonChange(0, released, 0, 0, synthetic = true))
            scope.runCurrent()
        }

        fun pass(millis: Long) {
            scope.advanceTimeBy(millis)
            scope.runCurrent()
        }
    }

    @Test
    fun `a tap on Plus is gear up at the release`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.pass(120)
            assertEquals(emptyList(), rig.fired)
            rig.release(ClickBits.PLUS)
            assertEquals(listOf("GEAR_UP@120"), rig.fired)
        }

    @Test
    fun `a hold on Plus is next interval once at the threshold with nothing on release`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.pass(499)
            assertEquals(emptyList(), rig.fired)
            rig.pass(1)
            assertEquals(listOf("NEXT_INTERVAL@500"), rig.fired)
            rig.pass(10_000)
            rig.release(ClickBits.PLUS)
            assertEquals(listOf("NEXT_INTERVAL@500"), rig.fired)
        }

    @Test
    fun `a held volume button steps at once then at 500 ms and every 150 ms`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.A)
            rig.pass(1_000)
            rig.release(ClickBits.A)
            rig.pass(5_000)
            assertEquals(
                listOf(
                    "VOLUME_UP@0",
                    "VOLUME_UP_REPEAT@500",
                    "VOLUME_UP_REPEAT@650",
                    "VOLUME_UP_REPEAT@800",
                    "VOLUME_UP_REPEAT@950",
                ),
                rig.fired,
            )
        }

    @Test
    fun `every action is also published as a flow for feedback`() =
        runTest {
            val rig = Rig(this)
            val seen = mutableListOf<GestureEvent>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    rig.controls.actions.collect { seen += it }
                }
            rig.press(ClickBits.Z)
            rig.release(ClickBits.Z)
            rig.press(ClickBits.Y)
            rig.release(ClickBits.Y)
            assertEquals(listOf(ClickAction.VOLUME_DOWN, ClickAction.PLAY_PAUSE_MEDIA), seen.map { it.action })
            job.cancel()
        }

    @Test
    fun `the mapping is read at every change another physical button now does the job`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.release(ClickBits.PLUS)
            assertEquals(listOf("GEAR_UP@0"), rig.fired)
            rig.mapping = checkNotNull(ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.MINUS))
            rig.press(ClickBits.MINUS)
            rig.release(ClickBits.MINUS)
            rig.press(ClickBits.PLUS)
            rig.release(ClickBits.PLUS)
            assertEquals(listOf("GEAR_UP@0", "GEAR_UP@0"), rig.fired, "Minus is Plus now; the old bit is nobody's")
        }

    @Test
    fun `a changed mapping forgets the presses in progress`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.pass(100)
            rig.mapping = checkNotNull(ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.MINUS))
            rig.press(ClickBits.A) // the next change: the old press is dropped
            rig.pass(2_000)
            assertEquals(listOf("VOLUME_UP@100", "VOLUME_UP_REPEAT@600"), rig.fired.take(2))
            assertTrue(rig.fired.none { it.startsWith("NEXT_INTERVAL") }, "the dropped press never became a hold")
        }

    @Test
    fun `a lost link forgets what was held and fires nothing for it`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.press(ClickBits.A)
            rig.pass(200)
            rig.lostLink()
            rig.pass(5_000)
            assertEquals(listOf("VOLUME_UP@0"), rig.fired, "no tap, no hold, no repeat after the drop")
            // And the next frames work as new presses.
            rig.press(ClickBits.PLUS)
            rig.pass(50)
            rig.release(ClickBits.PLUS)
            assertEquals("GEAR_UP@5250", rig.fired.last())
        }

    @Test
    fun `bits that are nobody's are ignored`() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.MINUS or ClickBits.NAV_UP or 0x4000)
            rig.pass(2_000)
            rig.release(ClickBits.MINUS or ClickBits.NAV_UP or 0x4000)
            assertEquals(emptyList(), rig.fired)
        }

    @Test
    fun `a frame that moves the thumb releases one button and presses the next`() =
        runTest {
            val rig = Rig(this)
            rig.frame(ClickBits.B)
            rig.pass(60)
            rig.frame(ClickBits.PLUS)
            rig.pass(60)
            rig.frame(0)
            assertEquals(listOf("GEAR_DOWN@60", "GEAR_UP@120"), rig.fired)
        }

    @Test
    fun `simultaneous presses in one frame are pressed in role order`() =
        runTest {
            val rig = Rig(this)
            rig.frame(ClickBits.Y or ClickBits.PLUS or ClickBits.B)
            rig.pass(80)
            rig.frame(0)
            assertEquals(listOf("GEAR_UP@80", "GEAR_DOWN@80", "PLAY_PAUSE_MEDIA@80"), rig.fired)
        }

    @Test
    fun `an action that throws is dropped and the next gesture still runs`() =
        runTest {
            val rig = Rig(this, throwOn = setOf(ClickAction.GEAR_UP))
            rig.press(ClickBits.PLUS)
            rig.pass(50)
            rig.release(ClickBits.PLUS)
            rig.press(ClickBits.B)
            rig.pass(50)
            rig.release(ClickBits.B)
            assertEquals(listOf("GEAR_UP@50", "GEAR_DOWN@100"), rig.fired)
        }

    @Test
    fun `after close nothing fires and start is idempotent`() =
        runTest {
            val rig = Rig(this)
            rig.controls.start()
            rig.controls.start()
            rig.press(ClickBits.A)
            rig.controls.close()
            rig.pass(2_000)
            rig.release(ClickBits.A)
            rig.press(ClickBits.PLUS)
            rig.pass(50)
            rig.release(ClickBits.PLUS)
            assertEquals(listOf("VOLUME_UP@0"), rig.fired, "one step, no repeats after the close")
        }

    @Test
    fun `a different timing is honoured`() =
        runTest {
            val rig = Rig(this, timing = GestureTiming(holdThresholdMillis = 300, repeatIntervalMillis = 100))
            rig.press(ClickBits.A)
            rig.pass(600)
            rig.release(ClickBits.A)
            assertEquals(
                listOf(
                    "VOLUME_UP@0",
                    "VOLUME_UP_REPEAT@300",
                    "VOLUME_UP_REPEAT@400",
                    "VOLUME_UP_REPEAT@500",
                    "VOLUME_UP_REPEAT@600",
                ),
                rig.fired,
            )
        }
}
