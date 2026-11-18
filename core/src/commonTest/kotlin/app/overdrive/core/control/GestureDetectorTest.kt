package app.overdrive.core.control

import app.overdrive.core.control.ClickAction.GEAR_DOWN
import app.overdrive.core.control.ClickAction.GEAR_UP
import app.overdrive.core.control.ClickAction.NEXT_INTERVAL
import app.overdrive.core.control.ClickAction.PLAY_PAUSE_MEDIA
import app.overdrive.core.control.ClickAction.PREVIOUS_INTERVAL
import app.overdrive.core.control.ClickAction.TOGGLE_RIDE_PAUSE
import app.overdrive.core.control.ClickAction.VOLUME_DOWN
import app.overdrive.core.control.ClickAction.VOLUME_DOWN_REPEAT
import app.overdrive.core.control.ClickAction.VOLUME_UP
import app.overdrive.core.control.ClickAction.VOLUME_UP_REPEAT
import app.overdrive.core.control.HandlebarButton.BUTTON_2
import app.overdrive.core.control.HandlebarButton.BUTTON_3
import app.overdrive.core.control.HandlebarButton.BUTTON_4
import app.overdrive.core.control.HandlebarButton.BUTTON_5
import app.overdrive.core.control.HandlebarButton.PLUS
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gesture rules of spec §4.7 in pure virtual time: no coroutine, no clock, every call says what time it is. The rules
 * and the reasons are in `GestureDetector`'s documentation and ADR 0011.
 */
class GestureDetectorTest {
    /** Collects what a script of presses, releases and ticks fires. */
    private class Script(
        timing: GestureTiming = GestureTiming(),
    ) {
        val detector = GestureDetector(timing)
        val fired = mutableListOf<GestureEvent>()

        fun press(
            button: HandlebarButton,
            at: Long,
        ) = apply { fired += detector.press(button, at) }

        fun release(
            button: HandlebarButton,
            at: Long,
        ) = apply { fired += detector.release(button, at) }

        fun advance(at: Long) = apply { fired += detector.advance(at) }

        /** Ticks every [step] ms from the current time to [until], as a coroutine that sleeps until each deadline would. */
        fun tickEvery(
            step: Long,
            from: Long,
            until: Long,
        ) = apply {
            var t = from
            while (t <= until) {
                fired += detector.advance(t)
                t += step
            }
        }

        /** `action@time` for each fired event, in order. */
        fun log(): List<String> = fired.map { "${it.action}@${it.atMillis}" }
    }

    // region the table

    @Test
    fun `the timing constants are the spec's`() {
        assertEquals(500L, GestureTiming.HOLD_THRESHOLD_MILLIS)
        assertEquals(150L, GestureTiming.VOLUME_REPEAT_MILLIS)
        val timing = GestureTiming()
        assertEquals(500L, timing.holdThresholdMillis)
        assertEquals(150L, timing.repeatIntervalMillis)
        assertEquals(0L, timing.bounceMillis, "bounce handling is off by default")
    }

    @Test
    fun `five buttons with two gestures each are exactly the ten actions of the spec`() {
        val table = HandlebarButton.entries.flatMap { listOf(it.press, it.hold) }
        assertEquals(10, table.size)
        assertEquals(ClickAction.entries.toSet(), table.toSet(), "every action is bound once")
        assertEquals(10, ClickAction.entries.size)
        assertEquals(
            listOf(
                PLUS to (GEAR_UP to NEXT_INTERVAL),
                BUTTON_2 to (GEAR_DOWN to PREVIOUS_INTERVAL),
                BUTTON_3 to (PLAY_PAUSE_MEDIA to TOGGLE_RIDE_PAUSE),
                BUTTON_4 to (VOLUME_UP to VOLUME_UP_REPEAT),
                BUTTON_5 to (VOLUME_DOWN to VOLUME_DOWN_REPEAT),
            ),
            HandlebarButton.entries.map { it to (it.press to it.hold) },
        )
        assertEquals(listOf(false, false, false, true, true), HandlebarButton.entries.map { it.repeats })
    }

    // endregion

    // region tap or hold: Plus, Button 2, Button 3

    @Test
    fun `a short press fires on release, not on press`() {
        val script = Script()
        script.press(PLUS, 1_000)
        assertEquals(emptyList(), script.log(), "nothing yet: it might be a hold")
        script.release(PLUS, 1_120)
        assertEquals(listOf("GEAR_UP@1120"), script.log())
    }

    @Test
    fun `each of the three tap buttons gives its press action on a short press and its hold action on a hold`() {
        for ((button, actions) in listOf(
            PLUS to (GEAR_UP to NEXT_INTERVAL),
            BUTTON_2 to (GEAR_DOWN to PREVIOUS_INTERVAL),
            BUTTON_3 to (PLAY_PAUSE_MEDIA to TOGGLE_RIDE_PAUSE),
        )) {
            val tap = Script().press(button, 0).release(button, 200)
            assertEquals(listOf("${actions.first}@200"), tap.log(), "$button tap")
            val hold = Script().press(button, 0).advance(500).release(button, 900)
            assertEquals(listOf("${actions.second}@500"), hold.log(), "$button hold")
        }
    }

    @Test
    fun `just under the threshold is a press and exactly the threshold is a hold`() {
        assertEquals(listOf("GEAR_UP@499"), Script().press(PLUS, 0).release(PLUS, 499).log())
        assertEquals(listOf("NEXT_INTERVAL@500"), Script().press(PLUS, 0).release(PLUS, 500).log())
        assertEquals(listOf("NEXT_INTERVAL@500"), Script().press(PLUS, 0).release(PLUS, 501).log())
    }

    @Test
    fun `a hold fires once at the threshold while the button is still down, nothing before and nothing after`() {
        val script = Script().press(PLUS, 100)
        assertEquals(emptyList(), script.advance(599).log())
        assertEquals(listOf("NEXT_INTERVAL@600"), script.advance(600).log())
        script.advance(5_000).advance(10_000)
        assertEquals(listOf("NEXT_INTERVAL@600"), script.log(), "once")
        script.release(PLUS, 10_500)
        assertEquals(listOf("NEXT_INTERVAL@600"), script.log(), "nothing on release after a hold")
        assertFalse(script.detector.anyDown)
    }

    @Test
    fun `a very long hold is still one action`() {
        val script = Script().press(BUTTON_3, 0).tickEvery(50, 0, 600_000).release(BUTTON_3, 600_000)
        assertEquals(listOf("TOGGLE_RIDE_PAUSE@500"), script.log())
    }

    @Test
    fun `a stalled caller fires a hold once, at the time it was due, however late it wakes`() {
        val script = Script().press(PLUS, 0)
        assertEquals(listOf("NEXT_INTERVAL@500"), script.advance(1_000_000).log())
    }

    @Test
    fun `rapid taps are each one press at their own release`() {
        val script = Script()
        var t = 0L
        repeat(10) {
            script.press(PLUS, t).release(PLUS, t + 60)
            t += 100
        }
        assertEquals((0 until 10).map { "GEAR_UP@${it * 100 + 60}" }, script.log())
    }

    @Test
    fun `taps of different buttons that overlap, as a rolling thumb does, both fire at their own release`() {
        val script = Script()
        script.press(PLUS, 0).press(BUTTON_2, 50).release(PLUS, 100).release(BUTTON_2, 130)
        assertEquals(listOf("GEAR_UP@100", "GEAR_DOWN@130"), script.log())
    }

    // endregion

    // region volume: Button 4 and 5

    @Test
    fun `a volume press fires at once and a short press fires nothing more`() {
        val script = Script().press(BUTTON_4, 10)
        assertEquals(listOf("VOLUME_UP@10"), script.log())
        script.release(BUTTON_4, 300)
        assertEquals(listOf("VOLUME_UP@10"), script.log())
        assertEquals(listOf("VOLUME_UP@10", "VOLUME_DOWN@400"), script.press(BUTTON_5, 400).log())
    }

    @Test
    fun `a held volume button repeats from the threshold, every 150 ms, and a two second hold gives twelve steps`() {
        val script = Script().press(BUTTON_4, 0).tickEvery(1, 0, 2_000).release(BUTTON_4, 2_000)
        val expected = listOf("VOLUME_UP@0") + (0..10).map { "VOLUME_UP_REPEAT@${500 + 150 * it}" }
        assertEquals(expected, script.log())
        assertEquals(12, script.fired.size, "one on press and eleven repeats in two seconds (bench 5.7)")
    }

    @Test
    fun `volume down repeats with its own action`() {
        val script = Script().press(BUTTON_5, 0).tickEvery(10, 0, 1_000)
        assertEquals(
            listOf(
                "VOLUME_DOWN@0",
                "VOLUME_DOWN_REPEAT@500",
                "VOLUME_DOWN_REPEAT@650",
                "VOLUME_DOWN_REPEAT@800",
                "VOLUME_DOWN_REPEAT@950",
            ),
            script.log(),
        )
    }

    @Test
    fun `release stops the repeat at once`() {
        val script = Script().press(BUTTON_4, 0).tickEvery(10, 0, 700).release(BUTTON_4, 700)
        script.tickEvery(10, 710, 3_000)
        assertEquals(listOf("VOLUME_UP@0", "VOLUME_UP_REPEAT@500", "VOLUME_UP_REPEAT@650"), script.log())
    }

    @Test
    fun `a very long volume hold keeps its cadence for a minute`() {
        val script = Script().press(BUTTON_4, 0).tickEvery(10, 0, 60_000)
        val repeats = script.fired.filter { it.action == VOLUME_UP_REPEAT }
        assertEquals(397, repeats.size, "500, 650, … up to 60000 ms: (60000 − 500) / 150 + 1, rounded down")
        assertTrue(repeats.zipWithNext().all { (a, b) -> b.atMillis - a.atMillis == 150L })
    }

    @Test
    fun `a stalled caller gets one repeated step and the cadence resumes from the present, never a burst`() {
        val script = Script().press(BUTTON_4, 0)
        script.advance(10_000)
        assertEquals(listOf("VOLUME_UP@0", "VOLUME_UP_REPEAT@500"), script.log(), "one step, not sixty")
        assertEquals(10_150L, script.detector.nextDeadlineMillis)
        assertEquals(emptyList(), script.advance(10_149).fired.drop(2))
        assertEquals("VOLUME_UP_REPEAT@10150", script.advance(10_150).log().last())
    }

    // endregion

    // region two buttons

    @Test
    fun `two tap buttons held together fire no hold, and nothing on release`() {
        val script = Script().press(PLUS, 0).press(BUTTON_2, 0).tickEvery(50, 0, 2_000)
        script.release(PLUS, 2_000).release(BUTTON_2, 2_000)
        assertEquals(emptyList(), script.log(), "a palm over the puck skips no interval and pauses nothing")
    }

    @Test
    fun `a hold needs the button alone at the threshold, another button that was tapped and gone does not matter`() {
        val script = Script().press(PLUS, 0).press(BUTTON_2, 100).release(BUTTON_2, 200).tickEvery(50, 200, 700)
        assertEquals(listOf("GEAR_DOWN@200", "NEXT_INTERVAL@500"), script.log())
    }

    @Test
    fun `a hold that came due while another button was down is gone for that press, the other one may still fire`() {
        val script = Script().press(PLUS, 0).press(BUTTON_2, 400).tickEvery(50, 400, 700)
        assertEquals(emptyList(), script.log(), "Plus's hold due at 500 is skipped: Button 2 is down")
        script.release(PLUS, 700)
        script.tickEvery(50, 700, 1_200)
        assertEquals(listOf("PREVIOUS_INTERVAL@900"), script.log(), "Button 2 is alone at its own threshold")
        script.release(BUTTON_2, 1_300)
        assertEquals(listOf("PREVIOUS_INTERVAL@900"), script.log())
    }

    @Test
    fun `a skipped repeated step is only missed, the next one is tried an interval later`() {
        val script = Script().press(BUTTON_4, 0).press(PLUS, 600).release(PLUS, 700)
        script.tickEvery(10, 0, 1_000)
        // PLUS was down from 600 to 700: the step due at 650 is skipped; the ones at 500, 800 and 950 fire.
        assertEquals(
            listOf(
                "VOLUME_UP@0",
                "VOLUME_UP_REPEAT@500",
                "GEAR_UP@700",
                "VOLUME_UP_REPEAT@800",
                "VOLUME_UP_REPEAT@950",
            ),
            script.log(),
        )
    }

    @Test
    fun `both volume buttons down fire their first steps and then no repeats`() {
        val script = Script().press(BUTTON_4, 0).press(BUTTON_5, 0).tickEvery(10, 0, 2_000)
        assertEquals(listOf("VOLUME_UP@0", "VOLUME_DOWN@0"), script.log())
        script.release(BUTTON_5, 2_000).tickEvery(10, 2_010, 2_400)
        assertEquals(
            listOf("VOLUME_UP@0", "VOLUME_DOWN@0", "VOLUME_UP_REPEAT@2150"),
            script.log().take(3),
            "the one still down resumes its steps once it is alone",
        )
    }

    @Test
    fun `all five buttons at once, the volume steps fire at the press, no hold fires, the release is silent`() {
        val script = Script()
        HandlebarButton.entries.forEach { script.press(it, 0) }
        assertEquals(listOf("VOLUME_UP@0", "VOLUME_DOWN@0"), script.log())
        script.tickEvery(25, 0, 1_500)
        HandlebarButton.entries.forEach { script.release(it, 1_500) }
        assertEquals(listOf("VOLUME_UP@0", "VOLUME_DOWN@0"), script.log())
        assertFalse(script.detector.anyDown)
        assertNull(script.detector.nextDeadlineMillis)
    }

    @Test
    fun `all five buttons pressed and released quickly are independent taps`() {
        val script = Script()
        HandlebarButton.entries.forEach { script.press(it, 0) }
        HandlebarButton.entries.forEach { script.release(it, 100) }
        assertEquals(
            listOf("VOLUME_UP@0", "VOLUME_DOWN@0", "GEAR_UP@100", "GEAR_DOWN@100", "PLAY_PAUSE_MEDIA@100"),
            script.log(),
            "documented: a short chord is not guarded, only holds are",
        )
    }

    // endregion

    // region lost releases, duplicates, clocks

    @Test
    fun `duplicate presses and releases are ignored`() {
        val script = Script().press(PLUS, 0).press(PLUS, 10).release(PLUS, 100).release(PLUS, 110)
        assertEquals(listOf("GEAR_UP@100"), script.log())
        assertEquals(emptyList(), Script().release(PLUS, 5).log(), "a release of a button that is up")
    }

    @Test
    fun `a lost release forgets the press without firing anything`() {
        val script = Script().press(PLUS, 0).press(BUTTON_4, 0)
        assertEquals(listOf("VOLUME_UP@0"), script.log())
        script.advance(300)
        script.detector.cancelAll()
        script.tickEvery(50, 300, 5_000)
        script.release(PLUS, 5_000).release(BUTTON_4, 5_000)
        assertEquals(listOf("VOLUME_UP@0"), script.log(), "no tap, no hold, no repeat")
        assertFalse(script.detector.anyDown)
        assertNull(script.detector.nextDeadlineMillis)
        // And the buttons work again afterwards.
        script.press(PLUS, 6_000).release(PLUS, 6_050)
        assertEquals("GEAR_UP@6050", script.log().last())
    }

    @Test
    fun `a clock that steps backwards fires nothing early and does not throw`() {
        val script = Script().press(PLUS, 1_000)
        assertEquals(emptyList(), script.advance(200).log(), "the past is the present")
        assertEquals(emptyList(), script.advance(1_499).log())
        assertEquals(listOf("NEXT_INTERVAL@1500"), script.advance(1_500).log())
        // A release stamped before the press is a release now: a zero-length tap.
        val other = Script().press(BUTTON_2, 1_000).release(BUTTON_2, 900)
        assertEquals(listOf("GEAR_DOWN@1000"), other.log())
    }

    @Test
    fun `a clock that jumps forward in the middle of a tap makes it a hold`() {
        val script = Script().press(PLUS, 0)
        script.release(PLUS, 3_000)
        assertEquals(listOf("NEXT_INTERVAL@500"), script.log())
    }

    @Test
    fun `the next deadline follows the state`() {
        val script = Script()
        assertNull(script.detector.nextDeadlineMillis)
        script.press(PLUS, 100)
        assertEquals(600L, script.detector.nextDeadlineMillis)
        script.advance(600)
        assertNull(script.detector.nextDeadlineMillis, "a hold that fired leaves nothing to wait for")
        script.release(PLUS, 700)
        script.press(BUTTON_4, 800)
        assertEquals(1_300L, script.detector.nextDeadlineMillis)
        script.advance(1_300)
        assertEquals(1_450L, script.detector.nextDeadlineMillis)
        assertTrue(script.detector.isDown(BUTTON_4))
        assertFalse(script.detector.isDown(PLUS))
    }

    // endregion

    // region bounce

    @Test
    fun `without bounce handling a release and a quick press of the same button are two presses`() {
        val script = Script().press(PLUS, 0).release(PLUS, 100).press(PLUS, 110).release(PLUS, 150)
        assertEquals(listOf("GEAR_UP@100", "GEAR_UP@150"), script.log())
    }

    @Test
    fun `with bounce handling the quick press cancels the release and the press goes on`() {
        val script = Script(GestureTiming(bounceMillis = 30))
        script.press(PLUS, 0).release(PLUS, 100).press(PLUS, 110)
        assertEquals(emptyList(), script.log(), "the release was a bounce")
        script.release(PLUS, 600)
        assertEquals(listOf("NEXT_INTERVAL@500"), script.log(), "still one press, now long enough to be a hold")
    }

    @Test
    fun `with bounce handling a real tap fires once the bounce time has passed, stamped with its release`() {
        val script = Script(GestureTiming(bounceMillis = 30))
        script.press(PLUS, 0).release(PLUS, 100)
        assertEquals(130L, script.detector.nextDeadlineMillis)
        assertEquals(emptyList(), script.advance(129).log())
        assertEquals(listOf("GEAR_UP@100"), script.advance(130).log())
        assertNull(script.detector.nextDeadlineMillis)
    }

    @Test
    fun `with bounce handling a tap released just before the threshold is still a tap`() {
        val script = Script(GestureTiming(bounceMillis = 30)).press(PLUS, 0).release(PLUS, 490).advance(2_000)
        assertEquals(listOf("GEAR_UP@490"), script.log(), "the hold due at 500 waits for the release to be confirmed")
    }

    @Test
    fun `with bounce handling a volume bounce does not fire a second first step`() {
        val script = Script(GestureTiming(bounceMillis = 30))
        script.press(BUTTON_4, 0).release(BUTTON_4, 100).press(BUTTON_4, 110)
        assertEquals(listOf("VOLUME_UP@0"), script.log())
    }

    // endregion

    @Test
    fun `random presses, releases and ticks never throw, never fire more than the rules allow and always settle`() {
        val random = Random(2026)
        repeat(300) { round ->
            val timing = GestureTiming(bounceMillis = if (round % 3 == 0) 20 else 0)
            val script = Script(timing)
            var t = 0L
            val presses = IntArray(HandlebarButton.entries.size)
            repeat(200) {
                t += random.nextLong(0, 400)
                val button = HandlebarButton.entries[random.nextInt(HandlebarButton.entries.size)]
                when (random.nextInt(3)) {
                    0 -> {
                        if (!script.detector.isDown(button)) presses[button.ordinal]++
                        script.press(button, t)
                    }

                    1 -> {
                        script.release(button, t)
                    }

                    else -> {
                        script.advance(t)
                    }
                }
            }
            HandlebarButton.entries.forEach { script.release(it, t + 1) }
            script.advance(t + 100_000)
            assertFalse(script.detector.anyDown, "round $round")
            assertNull(script.detector.nextDeadlineMillis, "round $round")
            for (button in HandlebarButton.entries.filter { !it.repeats }) {
                val fired = script.fired.count { it.button == button }
                assertTrue(
                    fired <= presses[button.ordinal],
                    "$button fired $fired times for ${presses[button.ordinal]} presses (round $round)",
                )
            }
        }
    }
}
