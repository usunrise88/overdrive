package app.overdrive.core.click.integration

import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.fake.FakeClickLock
import app.overdrive.core.ble.fake.FakeClickOptions
import app.overdrive.core.ble.integration.BleRide
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickState
import app.overdrive.core.click.UnlockSignal
import app.overdrive.core.click.addClick
import app.overdrive.core.click.click
import app.overdrive.core.control.ButtonLearner
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnState
import app.overdrive.core.device.collectAll
import app.overdrive.core.record.RideRecord
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spec §4.7 and acceptance criterion 3 in the automatic part: the real ride engine ridden over a fake trainer, a fake strap
 * and a fake Click, the Click's presses driving gear, intensity, interval, pause and the media keys through the gesture
 * layer and the router. The Click is the right puck with the default mapping: Plus gear up, B gear down, Y play and pause,
 * A volume up, Z volume down.
 *
 * What this proves is consistency with `docs/protocols/click-v2.md`, not that a real Click behaves so (bench session 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickRideTest {
    // region criterion 3

    @Test
    fun `all ten Click actions work through the whole chain, each with its effect`() =
        runTest {
            val rig = ClickRide(this)
            val ride = rig.ride
            val trainer = ride.rig.fakeTrainer
            val media = rig.media
            assertEquals(ClickState.Active, rig.click.state.value)
            ride.start()
            ride.rig.advance(2_000)
            assertEquals(RideState.RIDING, ride.now.state)
            assertEquals(TrainerMode.ERG, ride.now.mode, "step A: ERG at 100 W")
            assertEquals(100, trainer.targetPowerWatts)

            // 1. Plus, press: gear up, which in an ERG step is intensity +5 %.
            rig.tap(ClickBits.PLUS)
            assertEquals(105, ride.now.intensityPercent)
            assertEquals(105, trainer.targetPowerWatts, "the trainer is told the new target")

            // 2. Button 2 (B), press: gear down, which in an ERG step is intensity -5 %.
            rig.tap(ClickBits.B)
            assertEquals(100, ride.now.intensityPercent)
            assertEquals(100, trainer.targetPowerWatts)

            // 3. Plus, hold: next interval. Twice: from the ERG step A past ERG step B to the free step C.
            rig.hold(ClickBits.PLUS)
            assertEquals(1, ride.now.workout?.stepIndex)
            assertEquals(160, trainer.targetPowerWatts, "step B: 80 % of 200 W")
            rig.hold(ClickBits.PLUS)
            assertEquals(2, ride.now.workout?.stepIndex)
            assertEquals(TrainerMode.SIMULATION, ride.now.mode)
            assertEquals(12, ride.now.gear)

            // In a free step the same two buttons shift gears.
            rig.tap(ClickBits.PLUS)
            assertEquals(13, ride.now.gear)
            assertEquals(1.0, assertNotNull(trainer.simulation).gradePercent, "one gear is one percent of grade")
            rig.tap(ClickBits.B)
            assertEquals(12, ride.now.gear)
            assertEquals(0.0, assertNotNull(trainer.simulation).gradePercent)

            // 4. Button 2 (B), hold: previous interval (less than three seconds into step C, so back to step B).
            rig.hold(ClickBits.B)
            assertEquals(1, ride.now.workout?.stepIndex)

            // 5. Button 3 (Y), press: play or pause the media.
            rig.tap(ClickBits.Y)
            assertEquals(listOf("playPause"), media.calls)

            // 6. Button 3 (Y), hold: pause the ride, and hold again to resume it.
            rig.hold(ClickBits.Y)
            assertEquals(RideState.PAUSED, ride.now.state)
            assertEquals(PauseReason.MANUAL, ride.now.pauseReason)
            assertEquals(listOf("playPause"), media.calls, "a hold is not also a press")
            rig.hold(ClickBits.Y)
            assertEquals(RideState.RIDING, ride.now.state)

            // 7. Button 4 (A), press: volume up.
            rig.tap(ClickBits.A)
            assertEquals(listOf("playPause", "volumeUp"), media.calls)

            // 8. Button 4 (A), hold: volume up, repeating (one step at the press, then at 500, 650, 800, 950 ms).
            rig.hold(ClickBits.A, heldMs = 1_000)
            assertEquals(listOf("playPause", "volumeUp") + List(1 + 4) { "volumeUp" }, media.calls)

            // 9. Button 5 (Z), press: volume down.
            rig.tap(ClickBits.Z)
            // 10. Button 5 (Z), hold: volume down, repeating.
            rig.hold(ClickBits.Z, heldMs = 1_000)
            assertEquals(
                listOf("playPause") + List(6) { "volumeUp" } + List(1 + 1 + 4) { "volumeDown" },
                media.calls,
            )

            // All ten distinct actions were decided on, and each came from its own gesture.
            assertEquals(ClickAction.entries.toSet(), rig.actions.toSet())
            assertEquals(
                listOf(
                    ClickAction.GEAR_UP,
                    ClickAction.GEAR_DOWN,
                    ClickAction.NEXT_INTERVAL,
                    ClickAction.NEXT_INTERVAL,
                    ClickAction.GEAR_UP,
                    ClickAction.GEAR_DOWN,
                    ClickAction.PREVIOUS_INTERVAL,
                    ClickAction.PLAY_PAUSE_MEDIA,
                    ClickAction.TOGGLE_RIDE_PAUSE,
                    ClickAction.TOGGLE_RIDE_PAUSE,
                    ClickAction.VOLUME_UP,
                ),
                rig.actions.take(11),
            )
            ride.rig.assertNoViolations()
        }

    @Test
    fun `the Click changes nothing about the ride's record except what the rider asked for`() =
        runTest {
            val rig = ClickRide(this)
            rig.ride.start()
            rig.ride.rig.advance(5_000)
            rig.tap(ClickBits.A) // only the volume: the engine hears nothing
            rig.ride.rig.advance(5_000)
            val withClick = rig.ride.record
            assertTrue(withClick.samples.none { it.paused })
            assertEquals(rig.ride.now.intensityPercent, 100)
        }

    // endregion

    // region the Click goes away

    @Test
    fun `the Click drops mid-ride and returns, the ride is unaffected and the buttons work again`() =
        runTest {
            val rig = ClickRide(this)
            val ride = rig.ride
            ride.start()
            ride.rig.advance(5_000)
            rig.tap(ClickBits.PLUS)
            assertEquals(105, ride.now.intensityPercent)

            rig.fake.dropLink()
            ride.rig.advance(500)
            assertEquals(RideState.RIDING, ride.now.state, "the ride does not care")
            assertEquals(ClickState.NotFound(1, 1_000, wasConnected = true), rig.click.state.value)

            ride.rig.advance(5_000)
            assertEquals(ClickState.Active, rig.click.state.value, "back by itself")
            rig.tap(ClickBits.PLUS)
            assertEquals(110, ride.now.intensityPercent)
            assertEquals(RideState.RIDING, ride.now.state)
            assertTrue(ride.record.samples.none { it.paused })
            ride.rig.assertNoViolations()
        }

    @Test
    fun `a volume button held while the Click drops stops repeating, and nothing is left held`() =
        runTest {
            val rig = ClickRide(this)
            rig.ride.start()
            rig.ride.rig.advance(2_000)
            rig.fake.press(ClickBits.A)
            rig.ride.rig.advance(1_000)
            val beforeDrop = rig.media.calls.size
            assertTrue(beforeDrop >= 3, "the first step and some repeats: $beforeDrop")

            rig.fake.dropLink()
            rig.fake.release(ClickBits.A) // let go while the link is down: the release is lost
            rig.ride.rig.advance(10_000)
            val afterReconnect = rig.media.calls.size
            assertTrue(
                afterReconnect - beforeDrop <= 1,
                "at most the step that was already due: ${afterReconnect - beforeDrop}",
            )
            assertEquals(ClickState.Active, rig.click.state.value)

            rig.ride.rig.advance(5_000)
            assertEquals(afterReconnect, rig.media.calls.size, "no phantom repeats after the reconnect")
            rig.tap(ClickBits.Z)
            assertEquals("volumeDown", rig.media.calls.last())
        }

    @Test
    fun `a hold in progress when the link drops fires nothing afterwards`() =
        runTest {
            val rig = ClickRide(this)
            rig.ride.start()
            rig.ride.rig.advance(2_000)
            rig.fake.press(ClickBits.PLUS)
            rig.ride.rig.advance(300)
            rig.fake.dropLink() // 300 ms into a press: it would have become next interval at 500 ms
            rig.ride.rig.advance(5_000)
            rig.fake.release(ClickBits.PLUS)
            assertEquals(0, rig.ride.now.workout?.stepIndex)
            assertEquals(emptyList(), rig.actions)
        }

    // endregion

    // region a Click that never delivers

    private fun record(
        withClick: FakeClick?,
        beforeStart: (BleRide) -> Unit = {},
    ): RideRecord {
        lateinit var record: RideRecord
        runTest {
            val ride = BleRide(this, workout = clickWorkout)
            val click =
                withClick?.let {
                    ride.rig.addClick(it)
                    ride.rig.click().also { c -> c.start() }
                }
            beforeStart(ride)
            ride.rig.advance(1_000)
            ride.start()
            ride.rig.advance(60_000)
            if (click != null) assertIs<ClickState>(click.state.value)
            record = ride.record
        }
        return record
    }

    @Test
    fun `a locked Click that never delivers leaves the ride exactly as it would be without one`() {
        val without = record(null)
        for (lock in listOf(FakeClickLock.HIDDEN, FakeClickLock.SILENT)) {
            val fake = FakeClick().also { it.lock = lock }
            assertEquals(without, record(fake), "ride unaffected by a $lock Click")
        }
    }

    @Test
    fun `a locked Click shows what to do, and the on-screen controls keep working`() =
        runTest {
            val hidden = ClickRide(this, fake = FakeClick().also { it.lock = FakeClickLock.HIDDEN })
            hidden.ride.start()
            hidden.ride.rig.advance(2_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN), hidden.click.state.value)
            // The engine answers its own session commands, which is what the on-screen buttons call.
            hidden.ride.engine.gearUp()
            hidden.ride.engine.intensityUp()
            hidden.ride.rig.advance(500)
            assertEquals(105, hidden.ride.now.intensityPercent)
            hidden.ride.engine.nextInterval()
            hidden.ride.rig.advance(500)
            assertEquals(1, hidden.ride.now.workout?.stepIndex)
            assertEquals(emptyList(), hidden.actions)
        }

    @Test
    fun `a Click that is silent after the handshake is reported after thirty seconds and does nothing to the ride`() =
        runTest {
            val rig = ClickRide(this, fake = FakeClick().also { it.lock = FakeClickLock.SILENT })
            rig.ride.start()
            rig.ride.rig.advance(31_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), rig.click.state.value)
            assertEquals(RideState.RIDING, rig.ride.now.state)
            rig.fake.press(ClickBits.PLUS) // a button on a locked puck: nothing is sent
            rig.ride.rig.advance(1_000)
            rig.fake.release(ClickBits.PLUS)
            assertEquals(emptyList(), rig.actions)
            rig.ride.rig.assertNoViolations()
        }

    // endregion

    // region the mapping is a setting

    @Test
    fun `a learned mapping drives the ride, and the default one does not apply any more`() =
        runTest {
            val rig = ClickRide(this)
            val learner = ButtonLearner()
            val seen = collectAll(rig.click.buttons)
            rig.ride.start()
            rig.ride.rig.advance(2_000)

            // The Learn buttons screen: it asks for Plus, Button 2, … and the rider presses Z, Y, A, B, Plus.
            var processed = 0
            for (bit in listOf(ClickBits.Z, ClickBits.Y, ClickBits.A, ClickBits.B, ClickBits.PLUS)) {
                rig.tap(bit, afterMs = 100)
                while (processed < seen.size) learner.feed(seen[processed++].held)
            }
            val learned = assertIs<LearnState.Complete>(learner.state).mapping
            assertEquals(ClickBits.Z, learned.maskOf(HandlebarButton.PLUS))
            assertEquals(ClickBits.PLUS, learned.maskOf(HandlebarButton.BUTTON_5))
            rig.controls.close()
            rig.ride.rig.advance(1_000)

            // The setting is saved; the next ride starts with it.
            val restored = assertNotNull(ButtonMapping.parse(learned.serialize()))
            val second = ClickRide(this, mapping = restored)
            second.ride.start()
            second.ride.rig.advance(2_000)
            second.tap(ClickBits.Z) // the learned Plus: gear up, in ERG intensity up
            assertEquals(105, second.ride.now.intensityPercent)
            second.tap(ClickBits.PLUS) // the learned Button 5: volume down
            assertEquals(listOf("volumeDown"), second.media.calls)
        }

    // endregion

    // region the options that the bench flips

    @Test
    fun `a controller that needs FF 04 00 works in a ride with the default option`() =
        runTest {
            val needing = FakeClickOptions(needsPostHandshake = true)
            val works = ClickRide(this, fake = FakeClick(needing))
            works.ride.start()
            works.ride.rig.advance(2_000)
            works.tap(ClickBits.PLUS)
            assertEquals(105, works.ride.now.intensityPercent)
        }

    // endregion
}
