package app.overdrive.ride.handlebar

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnRejection
import app.overdrive.ride.devices.InMemoryPreferences
import app.overdrive.ride.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Learn buttons* (ADR 0012, spec §4.7) over a hot flow of button changes: the five roles in turn, the refusals in plain
 * terms, cancel, reset, the saved mapping, and the gesture layer kept silent while the lesson runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ButtonLearningTest {
    private class Rig(
        val test: TestScope,
        canLearn: () -> Boolean = { true },
    ) {
        val buttons = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 64)
        val repository = SettingsRepository(InMemoryPreferences())
        val saved = mutableListOf<ButtonMapping>()
        private var down = 0

        val learning =
            ButtonLearning(
                scope = test.backgroundScope,
                buttons = buttons,
                current = { ButtonMapping.DEFAULT },
                save = {
                    saved += it
                    repository.update { settings -> settings.copy(buttonMapping = it) }
                },
                canLearn = canLearn,
            )

        suspend fun press(mask: Int) {
            down = down or mask
            buttons.emit(ButtonChange(pressed = mask, released = 0, held = down, timestampNanos = 0L))
            test.runCurrent()
        }

        suspend fun release(mask: Int) {
            down = down and mask.inv()
            buttons.emit(ButtonChange(pressed = 0, released = mask, held = down, timestampNanos = 0L))
            test.runCurrent()
        }

        /** One press and its release of the buttons of [mask]. */
        suspend fun answer(mask: Int) {
            press(mask)
            release(mask)
        }
    }

    // A set of five distinct buttons that is not the default: Y, Z, A, B, Plus in role order.
    private val answers = listOf(ClickBits.Y, ClickBits.Z, ClickBits.A, ClickBits.B, ClickBits.PLUS)

    @Test
    fun itAsksForTheFiveRolesInTurnAndSavesTheMappingOnTheLastPress() =
        runTest {
            val rig = Rig(this)
            assertNull(rig.learning.ui.value)
            assertFalse(rig.learning.active)

            rig.learning.begin()
            val first = checkNotNull(rig.learning.ui.value)
            assertEquals(HandlebarButton.PLUS, first.role)
            assertEquals(1, first.step)
            assertEquals(5, first.total)
            assertTrue(rig.learning.active)

            for ((index, mask) in answers.withIndex()) {
                val ui = checkNotNull(rig.learning.ui.value)
                assertEquals(HandlebarButton.entries[index], ui.role)
                assertEquals(index + 1, ui.step)
                assertEquals("only what was answered is shown as learned", index, ui.learned.size)
                assertTrue("nothing is saved before the last role", rig.saved.isEmpty())
                rig.answer(mask)
            }

            val done = checkNotNull(rig.learning.ui.value)
            assertTrue(done.done)
            assertEquals(5, done.step)
            assertNull(done.rejection)
            assertEquals(listOf("Y"), done.learned.getValue(HandlebarButton.PLUS))
            assertEquals(listOf("PLUS"), done.learned.getValue(HandlebarButton.BUTTON_5))

            val expected =
                checkNotNull(ButtonMapping.of(HandlebarButton.entries.zip(answers).toMap()))
            assertEquals(listOf(expected), rig.saved)
            assertEquals("it is persisted, not only reported", expected, rig.repository.settings.first().buttonMapping)
            assertTrue("the gestures stay suspended until Done", rig.learning.active)

            // Nothing more is listened to after the last role, so a stray press cannot change the saved mapping.
            rig.answer(ClickBits.A)
            assertEquals(1, rig.saved.size)
            rig.learning.cancel()
            assertNull(rig.learning.ui.value)
            assertFalse(rig.learning.active)
        }

    @Test
    fun twoButtonsTogetherAreRefusedAsAComboAndTheSameRoleIsAskedAgain() =
        runTest {
            val rig = Rig(this)
            rig.learning.begin()
            rig.press(ClickBits.A)
            rig.press(ClickBits.B)
            rig.release(ClickBits.A or ClickBits.B)
            val refused = checkNotNull(rig.learning.ui.value)
            assertEquals(LearnRejection.COMBO, refused.rejection)
            assertEquals(HandlebarButton.PLUS, refused.role)
            assertEquals(1, refused.step)
            assertTrue(refused.learned.isEmpty())

            // The next press is a new try: the refusal is gone and the role is taken.
            rig.answer(ClickBits.Y)
            val next = checkNotNull(rig.learning.ui.value)
            assertNull(next.rejection)
            assertEquals(HandlebarButton.BUTTON_2, next.role)
            assertEquals(listOf("Y"), next.learned.getValue(HandlebarButton.PLUS))
            assertEquals(emptyList<ButtonMapping>(), rig.saved)
        }

    @Test
    fun aButtonThatAnotherRoleHasIsRefusedAsADuplicateNamingTheRole() =
        runTest {
            val rig = Rig(this)
            rig.learning.begin()
            rig.answer(ClickBits.Y)
            rig.answer(ClickBits.Y)
            val refused = checkNotNull(rig.learning.ui.value)
            assertEquals(LearnRejection.DUPLICATE, refused.rejection)
            assertEquals(HandlebarButton.PLUS, refused.clashesWith)
            assertEquals("the same role is asked again", HandlebarButton.BUTTON_2, refused.role)
            assertEquals(2, refused.step)

            rig.answer(ClickBits.Z)
            val next = checkNotNull(rig.learning.ui.value)
            assertNull(next.rejection)
            assertNull(next.clashesWith)
            assertEquals(HandlebarButton.BUTTON_3, next.role)
        }

    @Test
    fun cancelLeavesWithNothingSavedAndLaterPressesStartNothing() =
        runTest {
            val rig = Rig(this)
            rig.learning.begin()
            rig.answer(ClickBits.Y)
            rig.answer(ClickBits.Z)
            rig.learning.cancel()
            assertNull(rig.learning.ui.value)
            assertFalse(rig.learning.active)
            rig.answer(ClickBits.A)
            assertNull(rig.learning.ui.value)
            assertEquals(emptyList<ButtonMapping>(), rig.saved)
            assertEquals(ButtonMapping.DEFAULT, rig.repository.settings.first().buttonMapping)

            // And it can be started again from the first role.
            rig.learning.begin()
            assertEquals(1, checkNotNull(rig.learning.ui.value).step)
        }

    @Test
    fun resetToDefaultSavesTheDefaultAndLeaves() =
        runTest {
            val rig = Rig(this)
            val learned = checkNotNull(ButtonMapping.of(HandlebarButton.entries.zip(answers).toMap()))
            rig.repository.update { it.copy(buttonMapping = learned) }
            assertEquals(learned, rig.repository.settings.first().buttonMapping)

            rig.learning.begin()
            rig.learning.resetToDefault()
            runCurrent()
            assertNull(rig.learning.ui.value)
            assertFalse(rig.learning.active)
            assertEquals(listOf(ButtonMapping.DEFAULT), rig.saved)
            assertEquals(ButtonMapping.DEFAULT, rig.repository.settings.first().buttonMapping)
        }

    @Test
    fun itIsNotOfferedWhileARideIsLiveAndBeginningTwiceKeepsTheFirstLesson() =
        runTest {
            var live = true
            val rig = Rig(this, canLearn = { !live })
            rig.learning.begin()
            assertNull("no lesson during a ride", rig.learning.ui.value)
            assertFalse(rig.learning.active)

            live = false
            rig.learning.begin()
            rig.answer(ClickBits.Y)
            val before = rig.learning.ui.value
            rig.learning.begin()
            assertEquals("the lesson was not restarted", before, rig.learning.ui.value)
        }

    @Test
    fun aButtonThatWasAlreadyDownWhenTheLessonBeganIsNotCounted() =
        runTest {
            val rig = Rig(this)
            rig.learning.begin()
            // Its release is the first thing heard: not a press, and nothing is learned from it.
            rig.buttons.emit(ButtonChange(pressed = 0, released = ClickBits.PLUS, held = 0, timestampNanos = 0L))
            rig.test.runCurrent()
            val ui = checkNotNull(rig.learning.ui.value)
            assertEquals(1, ui.step)
            assertTrue(ui.learned.isEmpty())
            assertNull(ui.rejection)
        }

    @Test
    fun theGesturesAreSuspendedWhileLearningAndNothingMovesTheRide() =
        runTest {
            val rig = Rig(this)
            val session = RecordingRideSession(snapshotOf())
            val media = RecordingMediaKeys()
            val service =
                HandlebarService(
                    scope = backgroundScope,
                    buttons = rig.buttons,
                    mapping = { ButtonMapping.DEFAULT },
                    timing = flowOf(GestureTiming()),
                    router = ActionRouter({ session }, media),
                    nowMillis = { testScheduler.currentTime },
                    suspended = { rig.learning.active },
                )
            service.start()
            runCurrent()

            rig.learning.begin()
            // A press made to be learned is a gear up by the default mapping, and must not shift one.
            rig.press(ClickBits.PLUS)
            advanceTimeBy(80)
            rig.release(ClickBits.PLUS)
            // A hold would pause the ride, a volume press would move the volume.
            rig.press(ClickBits.Y)
            advanceTimeBy(700)
            rig.release(ClickBits.Y)
            rig.press(ClickBits.A)
            advanceTimeBy(20)
            rig.release(ClickBits.A)
            advanceTimeBy(100)
            assertEquals(emptyList<String>(), session.calls)
            assertEquals(emptyList<String>(), media.calls)
            assertNotNull(
                "the lesson did hear them",
                rig.learning.ui.value
                    ?.learned
                    ?.get(HandlebarButton.PLUS),
            )

            // Done (or cancelled): the handlebar is the ride's again.
            rig.learning.cancel()
            rig.press(ClickBits.PLUS)
            advanceTimeBy(80)
            rig.release(ClickBits.PLUS)
            advanceTimeBy(50)
            assertEquals(listOf("gearUp"), session.calls)
        }
}
