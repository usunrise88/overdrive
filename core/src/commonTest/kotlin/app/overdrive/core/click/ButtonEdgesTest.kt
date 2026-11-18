package app.overdrive.core.click

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Press and release edges from successive bitmaps (docs/protocols/click-v2.md §4: "ignoring repeats of the same bitmap"). */
class ButtonEdgesTest {
    private val released = ClickBits.ALL_RELEASED

    private fun pressedBitmap(vararg bits: Int): Int = ClickBits.bitmapOf(bits.fold(0) { all, bit -> all or bit })

    @Test
    fun `nothing pressed is no change, however often it repeats`() {
        val edges = ButtonEdges()
        assertNull(edges.update(released, 1))
        assertNull(edges.update(released, 2))
        assertEquals(0, edges.held)
    }

    @Test
    fun `a press and its release are two changes, and a repeated frame in between is none`() {
        val edges = ButtonEdges()
        val press = edges.update(pressedBitmap(ClickBits.A), 10)
        assertEquals(ButtonChange(pressed = ClickBits.A, released = 0, held = ClickBits.A, timestampNanos = 10), press)
        assertNull(edges.update(pressedBitmap(ClickBits.A), 20), "held across frames: reported once")
        assertNull(edges.update(pressedBitmap(ClickBits.A), 30))
        val release = edges.update(released, 40)
        assertEquals(ButtonChange(pressed = 0, released = ClickBits.A, held = 0, timestampNanos = 40), release)
        assertNull(edges.update(released, 50))
    }

    @Test
    fun `all five buttons at once are one change with five bits`() {
        val edges = ButtonEdges()
        val change = edges.update(ClickBits.bitmapOf(ClickBits.RIGHT_PUCK), 5)
        assertEquals(ClickBits.RIGHT_PUCK, change?.pressed)
        assertEquals(ClickBits.RIGHT_PUCK, change?.held)
        val off = edges.update(released, 6)
        assertEquals(ClickBits.RIGHT_PUCK, off?.released)
        assertEquals(0, off?.held)
    }

    @Test
    fun `a button held while another comes and goes is reported only for the other`() {
        val edges = ButtonEdges()
        edges.update(pressedBitmap(ClickBits.Y), 1)
        val second = edges.update(pressedBitmap(ClickBits.Y, ClickBits.A), 2)
        assertEquals(ClickBits.A, second?.pressed)
        assertEquals(ClickBits.Y or ClickBits.A, second?.held)
        val third = edges.update(pressedBitmap(ClickBits.Y), 3)
        assertEquals(ClickBits.A, third?.released)
        assertEquals(ClickBits.Y, third?.held)
    }

    @Test
    fun `a frame can release one button and press another at once`() {
        val edges = ButtonEdges()
        edges.update(pressedBitmap(ClickBits.B), 1)
        val roll = edges.update(pressedBitmap(ClickBits.PLUS), 2)
        assertEquals(ClickBits.PLUS, roll?.pressed)
        assertEquals(ClickBits.B, roll?.released)
        assertEquals(ClickBits.PLUS, roll?.held)
    }

    @Test
    fun `a bit the bench shows stuck can be ignored`() {
        val edges = ButtonEdges(ignoredBits = ClickBits.PLUS_SHIFT_DOWN)
        assertNull(edges.update(pressedBitmap(ClickBits.PLUS_SHIFT_DOWN), 1), "always down: not a button")
        val change = edges.update(pressedBitmap(ClickBits.PLUS_SHIFT_DOWN, ClickBits.A), 2)
        assertEquals(ClickBits.A, change?.pressed)
        assertEquals(ClickBits.A, change?.held)
    }

    @Test
    fun `releaseAll after a drop is synthetic, once, and the next frame starts from nothing`() {
        val edges = ButtonEdges()
        assertNull(edges.releaseAll(1), "nothing was held")
        edges.update(pressedBitmap(ClickBits.A, ClickBits.Z), 10)
        val lost = edges.releaseAll(20)
        assertEquals(ButtonChange(0, ClickBits.A or ClickBits.Z, 0, 20, synthetic = true), lost)
        assertNull(edges.releaseAll(21), "a second call has nothing to release")
        // A reconnect: a frame that says A is down is a new press, not a repeat.
        val again = edges.update(pressedBitmap(ClickBits.A), 30)
        assertEquals(ClickBits.A, again?.pressed)
        assertTrue(!again!!.synthetic)
    }

    @Test
    fun `a random walk of bitmaps keeps held equal to the last bitmap and every change consistent`() {
        val random = Random(4)
        val edges = ButtonEdges()
        var held = 0
        repeat(20_000) { step ->
            val bitmap = if (random.nextInt(4) == 0) released else random.nextInt()
            val change = edges.update(bitmap, step.toLong())
            val now = ClickBits.pressedOf(bitmap)
            if (now == held) {
                assertNull(change)
            } else {
                assertEquals(now and held.inv(), change?.pressed)
                assertEquals(held and now.inv(), change?.released)
                assertEquals(now, change?.held)
            }
            held = now
            assertEquals(held, edges.held)
        }
    }
}
