package app.overdrive.core.control

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live coach's mute on the Click (spec §12.1, docs/adr/0039): an eleventh action that is on **no** button by default, so the default map, its text and the
 * five roles are what they were, and that the rider may give a sixth button of the Click.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachMuteTest {
    // ---- The mapping --------------------------------------------------------------------------------------

    @Test
    fun `the default mapping has no button for the coach and is written exactly as before`() {
        assertEquals(0, ButtonMapping.DEFAULT.coachMuteMask)
        assertFalse(ButtonMapping.DEFAULT.coachMuteDown(ClickBits.RIGHT_PUCK or ClickBits.MINUS))
        val text = ButtonMapping.DEFAULT.serialize()
        assertFalse(text.contains("COACH"), text)
        assertEquals(HandlebarButton.entries.size, text.split(';').size, "five fields and no more")
        assertEquals(ButtonMapping.DEFAULT, ButtonMapping.parse(text))
        assertEquals(ClickBits.RIGHT_PUCK, ButtonMapping.DEFAULT.allBits)
    }

    @Test
    fun `a button for the coach is any bit no role has, and it is written and read back`() {
        val mapping = assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
        assertEquals(ClickBits.MINUS, mapping.coachMuteMask)
        assertTrue(mapping.coachMuteDown(ClickBits.MINUS))
        assertTrue(mapping.coachMuteDown(ClickBits.MINUS or ClickBits.A))
        assertFalse(mapping.coachMuteDown(ClickBits.A))
        // The five roles are as they were: the coach's button is not one of them.
        assertEquals(emptySet(), mapping.rolesDown(ClickBits.MINUS))
        assertEquals(ClickBits.RIGHT_PUCK or ClickBits.MINUS, mapping.allBits)
        val text = mapping.serialize()
        assertTrue(text.endsWith(";COACH_MUTE=" + ClickBits.MINUS.toUInt().toString(16).padStart(8, '0')), text)
        assertEquals(mapping, ButtonMapping.parse(text))
        assertNotEquals(ButtonMapping.DEFAULT, mapping)
        assertNotEquals(ButtonMapping.DEFAULT.hashCode(), mapping.hashCode())
        assertEquals(ButtonMapping.DEFAULT, mapping.withCoachMute(0), "clearing it gives the default back")
    }

    @Test
    fun `a button a role has cannot be the coach's, and a role cannot take the coach's`() {
        assertNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.A))
        assertNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.PLUS or ClickBits.MINUS))
        val mapping = assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
        assertNull(mapping.with(HandlebarButton.PLUS, ClickBits.MINUS))
        // A role moved elsewhere leaves the coach's button where it was.
        val moved = assertNotNull(mapping.with(HandlebarButton.PLUS, ClickBits.PLUS or ClickBits.PLUS_SHIFT_DOWN))
        assertEquals(ClickBits.MINUS, moved.coachMuteMask)
    }

    @Test
    fun `text with the coach's field is read strictly`() {
        val base = ButtonMapping.DEFAULT.serialize()
        val good = "$base;COACH_MUTE=00000200"
        assertEquals(0x200, assertNotNull(ButtonMapping.parse(good)).coachMuteMask)
        assertNull(ButtonMapping.parse("$good;COACH_MUTE=00000400"), "twice")
        assertNull(ButtonMapping.parse("$base;COACH_MUTE=00000000"), "an empty mask is left out, not written")
        assertNull(ButtonMapping.parse("$base;COACH_MUTE=200"), "not eight digits")
        assertNull(ButtonMapping.parse("$base;COACH_MUTE=0000020g"), "not hex")
        assertNull(
            ButtonMapping.parse(
                "$base;COACH_MUTE=" + ButtonMapping.DEFAULT.maskOf(HandlebarButton.PLUS).toString(16).padStart(8, '0'),
            ),
            "a role's bit",
        )
        assertNull(ButtonMapping.parse("$base;COACH=00000200"), "another name")
    }

    // ---- Learning it --------------------------------------------------------------------------------------

    @Test
    fun `learning the coach's button the way the roles are learned`() {
        val learner = CoachButtonLearner(ButtonMapping.DEFAULT)
        assertEquals(CoachLearnState.Asking, learner.state)
        assertEquals(CoachLearnState.Asking, learner.feed(ClickBits.MINUS))
        val done = assertIs<CoachLearnState.Complete>(learner.feed(0))
        assertEquals(ClickBits.MINUS, done.mapping.coachMuteMask)
        assertEquals(ButtonMapping.DEFAULT.maskOf(HandlebarButton.PLUS), done.mapping.maskOf(HandlebarButton.PLUS))
        // Done: more presses change nothing.
        assertEquals(done, learner.feed(ClickBits.NAV_UP))
    }

    @Test
    fun `two buttons are a combination and a role's button is taken, and the same question is asked again`() {
        val learner = CoachButtonLearner(ButtonMapping.DEFAULT)
        learner.feed(ClickBits.MINUS or ClickBits.NAV_UP)
        assertEquals(CoachLearnState.Rejected(CoachLearnRejection.COMBO, null), learner.feed(0))
        // A second button joining the first.
        learner.feed(ClickBits.MINUS)
        learner.feed(ClickBits.MINUS or ClickBits.NAV_UP)
        assertEquals(CoachLearnState.Rejected(CoachLearnRejection.COMBO, null), learner.feed(0))
        learner.feed(ClickBits.A)
        assertEquals(
            CoachLearnState.Rejected(CoachLearnRejection.TAKEN, HandlebarButton.BUTTON_4),
            learner.feed(0),
        )
        // A new press starts a new try.
        assertEquals(CoachLearnState.Asking, learner.feed(ClickBits.MINUS))
        assertIs<CoachLearnState.Complete>(learner.feed(0))
    }

    @Test
    fun `a bit that reads as pressed all the time is never looked at`() {
        val learner = CoachButtonLearner(ButtonMapping.DEFAULT, ignoredBits = ClickBits.NAV_UP)
        learner.feed(ClickBits.NAV_UP)
        learner.feed(ClickBits.NAV_UP or ClickBits.MINUS)
        val done = assertIs<CoachLearnState.Complete>(learner.feed(ClickBits.NAV_UP))
        assertEquals(ClickBits.MINUS, done.mapping.coachMuteMask)
    }

    @Test
    fun `learning the five roles again keeps the coach's button unless a role takes it`() {
        val base = assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
        var learner = ButtonLearner(base = base)
        val order = listOf(ClickBits.A, ClickBits.B, ClickBits.Y, ClickBits.Z, ClickBits.PLUS)
        for (bit in order) {
            learner.feed(bit)
            learner.feed(0)
        }
        assertEquals(ClickBits.MINUS, assertIs<LearnState.Complete>(learner.state).mapping.coachMuteMask)

        learner = ButtonLearner(base = base)
        for (bit in listOf(ClickBits.MINUS, ClickBits.B, ClickBits.Y, ClickBits.Z, ClickBits.PLUS)) {
            learner.feed(bit)
            learner.feed(0)
        }
        val mapping = assertIs<LearnState.Complete>(learner.state).mapping
        assertEquals(0, mapping.coachMuteMask, "a role was given the button, so the coach has none")
        assertEquals(ClickBits.MINUS, mapping.maskOf(HandlebarButton.PLUS))
    }

    // ---- Pressing it --------------------------------------------------------------------------------------

    private class Rig(
        val scope: TestScope,
        var mapping: ButtonMapping,
    ) {
        val changes = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 64)
        val fired = mutableListOf<ClickAction>()
        private var held = 0

        init {
            HandlebarControls(
                scope = scope.backgroundScope,
                changes = changes,
                mapping = { mapping },
                onAction = { fired += it },
                nowMillis = { scope.testScheduler.currentTime },
            ).start()
            scope.runCurrent()
        }

        fun frame(newHeld: Int) {
            val pressed = newHeld and held.inv()
            val released = held and newHeld.inv()
            held = newHeld
            changes.tryEmit(ButtonChange(pressed, released, held, timestampNanos = 0))
            scope.runCurrent()
        }
    }

    @Test
    fun `the coach's button mutes it on the press, once, with nothing on the release or the hold`() =
        runTest {
            val rig = Rig(this, assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS)))
            rig.frame(ClickBits.MINUS)
            assertEquals(listOf(ClickAction.MUTE_COACH), rig.fired)
            testScheduler.advanceTimeBy(5_000)
            testScheduler.runCurrent()
            rig.frame(0)
            assertEquals(listOf(ClickAction.MUTE_COACH), rig.fired, "no hold, and nothing on the release")
            rig.frame(ClickBits.MINUS)
            assertEquals(2, rig.fired.size)
        }

    @Test
    fun `a button that is nobody's does nothing, and the five roles are as they were`() =
        runTest {
            val rig = Rig(this, ButtonMapping.DEFAULT)
            rig.frame(ClickBits.MINUS)
            rig.frame(0)
            assertEquals(emptyList(), rig.fired)
            rig.frame(ClickBits.PLUS)
            rig.frame(0)
            assertEquals(listOf(ClickAction.GEAR_UP), rig.fired)
            // With the coach's button given too, the roles still fire what they fired.
            rig.mapping = assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
            rig.frame(ClickBits.PLUS)
            rig.frame(0)
            assertEquals(listOf(ClickAction.GEAR_UP, ClickAction.GEAR_UP), rig.fired)
        }

    @Test
    fun `holding the coach's button while pressing a role fires both, each by its own rule`() =
        runTest {
            val rig = Rig(this, assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS)))
            rig.frame(ClickBits.MINUS)
            rig.frame(ClickBits.MINUS or ClickBits.A)
            assertEquals(listOf(ClickAction.MUTE_COACH, ClickAction.VOLUME_UP), rig.fired)
        }

    @Test
    fun `a failing mute is dropped and the next press still works`() =
        runTest {
            val changes = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 8)
            var calls = 0
            HandlebarControls(
                scope = backgroundScope,
                changes = changes,
                mapping = { assertNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS)) },
                onAction = {
                    calls++
                    if (calls == 1) error("no coach")
                },
                nowMillis = { testScheduler.currentTime },
            ).start()
            runCurrent()
            changes.tryEmit(ButtonChange(ClickBits.MINUS, 0, ClickBits.MINUS, 0))
            runCurrent()
            changes.tryEmit(ButtonChange(0, ClickBits.MINUS, 0, 0))
            runCurrent()
            changes.tryEmit(ButtonChange(ClickBits.MINUS, 0, ClickBits.MINUS, 0))
            runCurrent()
            assertEquals(2, calls)
        }

    // ---- The router ---------------------------------------------------------------------------------------

    @Test
    fun `the router mutes the coach, with a ride or without one, and does nothing with no coach`() {
        var muted = 0
        val router = ActionRouter({ null }, NoMedia, CoachControl { muted++ })
        router.dispatch(ClickAction.MUTE_COACH)
        router.dispatch(ClickAction.MUTE_COACH)
        assertEquals(2, muted)
        ActionRouter({ null }, NoMedia).dispatch(ClickAction.MUTE_COACH)
        assertEquals(2, muted, "a router with no coach does nothing")
    }

    private object NoMedia : MediaKeys {
        override fun playPause() = Unit

        override fun volumeUp() = Unit

        override fun volumeDown() = Unit
    }
}
