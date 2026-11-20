package app.overdrive.ride.handlebar

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.GestureEvent
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handlebar of the container (spec §4.7, ADR 0012) in virtual time, over a recording session and recording media keys:
 * the mapping, the gesture layer and the router together, and nothing of Android in the way. That the chain also runs with
 * no activity at all is proved with the real container in [app.overdrive.ride.AppContainerClickTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandlebarServiceTest {
    private class Rig(
        val test: TestScope,
        ride: RecordingRideSession? = RecordingRideSession(snapshotOf()),
    ) {
        val buttons = MutableSharedFlow<ButtonChange>(extraBufferCapacity = 64)
        val session: RecordingRideSession? = ride
        val media = RecordingMediaKeys()
        val mapping = MutableStateFlow(ButtonMapping.DEFAULT)
        val timing = MutableStateFlow(GestureTiming())
        var suspended = false
        val performed = mutableListOf<GestureEvent>()
        private var down = 0

        val service =
            HandlebarService(
                scope = test.backgroundScope,
                buttons = buttons,
                mapping = { mapping.value },
                timing = timing,
                router = ActionRouter({ session }, media),
                nowMillis = { test.testScheduler.currentTime },
                suspended = { suspended },
            )

        init {
            service.start()
            test.backgroundScope.launch { service.actions.collect { performed += it } }
            test.runCurrent()
        }

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

        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        suspend fun tap(mask: Int) {
            press(mask)
            advance(80)
            release(mask)
            advance(10)
        }

        suspend fun hold(
            mask: Int,
            millis: Long = 700,
        ) {
            press(mask)
            advance(millis)
            release(mask)
            advance(10)
        }
    }

    @Test
    fun allTenActionsFireFromTheirGesturesAndReachTheRideAndTheMedia() =
        runTest {
            val rig = Rig(this)
            // The default mapping: Plus, B, Y, A and Z are the five roles in order.
            rig.tap(ClickBits.PLUS)
            rig.hold(ClickBits.PLUS)
            rig.tap(ClickBits.B)
            rig.hold(ClickBits.B)
            rig.tap(ClickBits.Y)
            rig.hold(ClickBits.Y)
            // The volume buttons step at once and repeat from the hold threshold.
            rig.hold(ClickBits.A, millis = 900)
            rig.hold(ClickBits.Z, millis = 900)

            assertEquals(
                "every one of the ten actions was performed",
                ClickAction.entries.toSet() - ClickAction.MUTE_COACH,
                rig.performed.map { it.action }.toSet(),
            )
            val session = checkNotNull(rig.session)
            assertEquals(
                listOf("gearUp", "nextInterval", "gearDown", "previousInterval", "pause"),
                session.calls,
            )
            val media = rig.media.calls
            assertEquals("playPause", media.first())
            assertTrue(media.toString(), media.count { it == "volumeUp" } >= 2)
            assertTrue(media.toString(), media.count { it == "volumeDown" } >= 2)
            assertEquals(1, media.count { it == "playPause" })
        }

    @Test
    fun aTapFiresOnReleaseAndAHoldFiresAtTheThresholdWithoutWaitingForTheRelease() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.PLUS)
            rig.advance(100)
            assertEquals("nothing yet: it may still be a hold", emptyList<String>(), rig.session?.calls)
            rig.advance(450)
            assertEquals("the hold fired while the button is still down", listOf("nextInterval"), rig.session?.calls)
            rig.release(ClickBits.PLUS)
            rig.advance(50)
            assertEquals("the release after a hold adds nothing", listOf("nextInterval"), rig.session?.calls)
        }

    @Test
    fun withNoRideTheRideButtonsDoNothingAndTheMediaStillWorks() =
        runTest {
            val rig = Rig(this, ride = null)
            rig.tap(ClickBits.PLUS)
            rig.hold(ClickBits.Y)
            rig.tap(ClickBits.Y)
            rig.hold(ClickBits.A, millis = 600)
            assertEquals("playPause", rig.media.calls.first())
            assertTrue(rig.media.calls.contains("volumeUp"))
        }

    @Test
    fun whileSuspendedNothingIsCarriedOutOrAnnouncedAndItWorksAgainAfterwards() =
        runTest {
            val rig = Rig(this)
            rig.suspended = true
            rig.tap(ClickBits.PLUS)
            rig.hold(ClickBits.Y)
            rig.hold(ClickBits.A, millis = 900)
            assertEquals(emptyList<String>(), rig.session?.calls)
            assertEquals(emptyList<String>(), rig.media.calls)
            assertEquals(emptyList<GestureEvent>(), rig.performed)

            rig.suspended = false
            rig.tap(ClickBits.PLUS)
            assertEquals(listOf("gearUp"), rig.session?.calls)
            assertEquals(listOf(ClickAction.GEAR_UP), rig.performed.map { it.action })
        }

    @Test
    fun aLearnedMappingAppliesAtOnceAndTheOldButtonsStopWorking() =
        runTest {
            val rig = Rig(this)
            // Z is now gear up and Plus is volume down: the two swap roles.
            rig.mapping.value =
                checkNotNull(
                    ButtonMapping.of(
                        mapOf(
                            HandlebarButton.PLUS to ClickBits.Z,
                            HandlebarButton.BUTTON_2 to ClickBits.B,
                            HandlebarButton.BUTTON_3 to ClickBits.Y,
                            HandlebarButton.BUTTON_4 to ClickBits.A,
                            HandlebarButton.BUTTON_5 to ClickBits.PLUS,
                        ),
                    ),
                )
            rig.tap(ClickBits.Z)
            assertEquals(listOf("gearUp"), rig.session?.calls)
            rig.tap(ClickBits.PLUS)
            assertEquals("Plus is the volume-down role now", listOf("volumeDown"), rig.media.calls)
            assertEquals(listOf("gearUp"), rig.session?.calls)
        }

    @Test
    fun aChangedTimingReplacesTheGestureLayerAndAPressInProgressIsForgotten() =
        runTest {
            val rig = Rig(this)
            // With the default 500 ms a press held 600 ms is a hold.
            rig.hold(ClickBits.PLUS, millis = 600)
            assertEquals(listOf("nextInterval"), rig.session?.calls)

            rig.timing.value = GestureTiming(holdThresholdMillis = 800, repeatIntervalMillis = 150)
            rig.advance(10)
            // The same 600 ms is now a tap.
            rig.hold(ClickBits.PLUS, millis = 600)
            assertEquals(listOf("nextInterval", "gearUp"), rig.session?.calls)

            // A press in progress when the timing changes is forgotten: its release fires nothing.
            rig.press(ClickBits.B)
            rig.timing.value = GestureTiming(holdThresholdMillis = 300, repeatIntervalMillis = 150)
            rig.advance(10)
            rig.release(ClickBits.B)
            rig.advance(1_000)
            assertEquals(listOf("nextInterval", "gearUp"), rig.session?.calls)
        }

    @Test
    fun aVolumeRepeatFollowsTheRepeatTiming() =
        runTest {
            val rig = Rig(this)
            rig.timing.value = GestureTiming(holdThresholdMillis = 500, repeatIntervalMillis = 100)
            rig.advance(10)
            rig.press(ClickBits.A)
            rig.advance(500 + 100 * 5)
            rig.release(ClickBits.A)
            // One step on the press, then one at the threshold and one every 100 ms for the 500 ms after it.
            assertTrue(rig.media.calls.toString(), rig.media.calls.size in 6..8)
            assertTrue(rig.media.calls.all { it == "volumeUp" })
        }

    @Test
    fun aSyntheticReleaseForgetsTheHeldButtonAndFiresNothingForIt() =
        runTest {
            val rig = Rig(this)
            rig.press(ClickBits.Y)
            rig.advance(100)
            // The link dropped with the button down: the adapter releases it itself.
            rig.buttons.emit(
                ButtonChange(pressed = 0, released = ClickBits.Y, held = 0, timestampNanos = 0L, synthetic = true),
            )
            rig.test.runCurrent()
            rig.advance(2_000)
            assertEquals(emptyList<String>(), rig.session?.calls)
            assertEquals(emptyList<String>(), rig.media.calls)
        }

    @Test
    fun startingTwiceListensOnceAndClosingStopsListening() =
        runTest {
            val rig = Rig(this)
            rig.service.start()
            rig.test.runCurrent()
            rig.tap(ClickBits.PLUS)
            assertEquals("not doubled", listOf("gearUp"), rig.session?.calls)
            rig.service.close()
            rig.test.runCurrent()
            rig.tap(ClickBits.PLUS)
            assertEquals(listOf("gearUp"), rig.session?.calls)
        }
}
