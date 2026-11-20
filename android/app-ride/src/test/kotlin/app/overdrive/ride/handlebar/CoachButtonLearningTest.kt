package app.overdrive.ride.handlebar

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.CoachControl
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnRejection
import app.overdrive.core.control.MediaKeys
import app.overdrive.ride.devices.InMemoryPreferences
import app.overdrive.ride.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live coach's sixth button (spec §12.1, docs/adr/0039): learned with one press, refused when it is a role's or a combination, kept in the settings, cleared, and carried
 * from the Click through the gesture layer and the router to the coach, on the press, with the five roles untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachButtonLearningTest {
    private class Rig(
        val test: TestScope,
        start: ButtonMapping = ButtonMapping.DEFAULT,
        canLearn: () -> Boolean = { true },
    ) {
        val buttons = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 64)
        val repository = SettingsRepository(InMemoryPreferences())
        val saved = mutableListOf<ButtonMapping>()
        var current = start
        private var down = 0

        val learning =
            ButtonLearning(
                scope = test.backgroundScope,
                buttons = buttons,
                current = { current },
                save = {
                    saved += it
                    current = it
                    repository.update { settings -> settings.copy(buttonMapping = it) }
                },
                canLearn = canLearn,
            )

        suspend fun answer(mask: Int) {
            down = down or mask
            buttons.emit(ButtonChange(pressed = mask, released = 0, held = down, timestampNanos = 0L))
            test.runCurrent()
            down = down and mask.inv()
            buttons.emit(ButtonChange(pressed = 0, released = mask, held = down, timestampNanos = 0L))
            test.runCurrent()
        }
    }

    @Test
    fun oneAnswerGivesTheCoachItsButtonAndSavesItWithTheFiveRolesAsTheyWere() =
        runTest {
            val rig = Rig(this)
            rig.learning.beginCoach()
            val asking = checkNotNull(rig.learning.ui.value)
            assertTrue(asking.coach)
            assertEquals(1, asking.total)
            assertFalse(asking.done)
            assertTrue(rig.learning.active)

            rig.answer(ClickBits.MINUS)
            val done = checkNotNull(rig.learning.ui.value)
            assertTrue(done.done)
            assertTrue(done.coach)
            assertEquals(listOf("MINUS"), done.learned.values.single())
            val mapping = rig.saved.single()
            assertEquals(ClickBits.MINUS, mapping.coachMuteMask)
            for (role in HandlebarButton.entries) assertEquals(ButtonMapping.DEFAULT.maskOf(role), mapping.maskOf(role))
            assertEquals("persisted, not only reported", mapping, rig.repository.settings.first().buttonMapping)
            assertTrue("the gestures stay suspended until Done", rig.learning.active)
            // Nothing more is listened to.
            rig.answer(ClickBits.NAV_UP)
            assertEquals(1, rig.saved.size)
            rig.learning.cancel()
            assertFalse(rig.learning.active)
        }

    @Test
    fun aRolesButtonAndTwoTogetherAreRefusedWithAReasonAndTheSameQuestionIsAskedAgain() =
        runTest {
            val rig = Rig(this)
            rig.learning.beginCoach()
            rig.answer(ClickBits.A)
            val taken = checkNotNull(rig.learning.ui.value)
            assertEquals(LearnRejection.DUPLICATE, taken.rejection)
            assertEquals(HandlebarButton.BUTTON_4, taken.clashesWith)
            assertTrue(taken.coach)
            assertTrue(rig.saved.isEmpty())
            rig.answer(ClickBits.MINUS or ClickBits.NAV_UP)
            assertEquals(LearnRejection.COMBO, rig.learning.ui.value?.rejection)
            rig.answer(ClickBits.MINUS)
            assertTrue(checkNotNull(rig.learning.ui.value).done)
            assertEquals(1, rig.saved.size)
        }

    @Test
    fun cancellingSavesNothingAndANewButtonReplacesTheOldOne() =
        runTest {
            val rig = Rig(this, checkNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS)))
            rig.learning.beginCoach()
            rig.learning.cancel()
            assertTrue(rig.saved.isEmpty())
            assertNull(rig.learning.ui.value)
            rig.learning.beginCoach()
            rig.answer(ClickBits.NAV_UP)
            assertEquals(ClickBits.NAV_UP, rig.saved.single().coachMuteMask)
        }

    @Test
    fun clearGivesTheCoachNoButtonAndKeepsTheFiveRoles() =
        runTest {
            val learned = checkNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
            val rig = Rig(this, learned)
            rig.learning.clearCoachButton()
            runCurrent()
            assertEquals(ButtonMapping.DEFAULT, rig.saved.single())
            assertEquals(0, rig.current.coachMuteMask)
        }

    @Test
    fun itIsNotOfferedDuringARideAndCannotStartTwice() =
        runTest {
            val busy = Rig(this, canLearn = { false })
            busy.learning.beginCoach()
            assertNull(busy.learning.ui.value)
            val rig = Rig(this)
            rig.learning.beginCoach()
            val first = rig.learning.ui.value
            rig.learning.beginCoach()
            assertEquals(first, rig.learning.ui.value)
        }

    @Test
    fun theClickMutesTheCoachThroughTheHandlebarAndTheRouterOnThePress() =
        runTest {
            val muted = mutableListOf<Long>()
            val changes = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 16)
            val mapping = checkNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
            val service =
                HandlebarService(
                    scope = backgroundScope,
                    buttons = changes,
                    mapping = { mapping },
                    timing = flowOf(GestureTiming()),
                    router = ActionRouter({ null }, NoMedia, CoachControl { muted += testScheduler.currentTime }),
                    nowMillis = { testScheduler.currentTime },
                )
            service.start()
            runCurrent()
            changes.emit(ButtonChange(ClickBits.MINUS, 0, ClickBits.MINUS, 0))
            runCurrent()
            assertEquals("on the press, before the release", 1, muted.size)
            changes.emit(ButtonChange(0, ClickBits.MINUS, 0, 0))
            runCurrent()
            assertEquals(1, muted.size)
        }

    private object NoMedia : MediaKeys {
        override fun playPause() = Unit

        override fun volumeUp() = Unit

        override fun volumeDown() = Unit
    }
}
