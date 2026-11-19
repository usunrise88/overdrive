package app.overdrive.core.ble.integration

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.fake.FakeHeartRateStrapOptions
import app.overdrive.core.ble.fake.FakeTrainerCommand
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real [app.overdrive.core.ride.RideEngine], unchanged, over the real adapters over the simulated Bluetooth (spec
 * §7.5): what the tablet will run, minus the Android transport. Time is virtual, so a ride of minutes takes milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleRideTest {
    // region a full workout in ERG

    @Test
    fun `a workout in ERG, the trainer is told each target and its power follows`() =
        runTest {
            val ride = BleRide(this, workout = bleWorkout)
            assertEquals(RideState.READY, ride.now.state, "connected, set up, control taken")
            ride.start()
            ride.rig.advance(95_000)
            assertEquals(RideState.FINISHED, ride.now.state)

            val samples = ride.record.samples
            assertEquals(90, samples.size)
            assertTrue(samples.take(30).all { it.power == 100 && it.targetPower == 100 }, "step A holds 100 W")
            assertTrue(samples.drop(30).take(30).all { it.power == 160 && it.targetPower == 160 }, "step B holds 160 W")
            assertTrue(
                samples.drop(60).all { it.power == 110 && it.targetPower == null && it.gear == 12 },
                "step C is a free ride",
            )
            assertTrue(samples.none { it.paused })
            assertTrue(samples.all { it.cadence == 90 && it.heartRate != null })
            assertEquals(listOf("RC", "START", "TARGET 0", "TARGET 100", "TARGET 160", "SIM 0.0"), ride.commands())
            ride.rig.assertNoViolations()
        }

    @Test
    fun `the commands the engine sends are the encoded commands the trainer decodes`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(20, 0.8))
            ride.start()
            ride.rig.advance(5_000)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            assertEquals(160, ride.now.power)
            assertTrue(ride.rig.fakeTrainer.hasControl)
            assertTrue(ride.rig.fakeTrainer.started)
        }

    @Test
    fun `a free ride shifts gears through the grade the trainer is told`() =
        runTest {
            val ride = BleRide(this)
            ride.start()
            repeat(3) { ride.engine.gearUp() }
            ride.rig.advance(3_000)
            assertEquals(15, ride.now.gear)
            val sim = assertNotNull(ride.rig.fakeTrainer.simulation)
            assertEquals(3.0, sim.gradePercent)
            assertEquals(40, sim.crrRaw)
            ride.engine.finish()
            ride.rig.advance(1_000)
            assertEquals(RideState.FINISHED, ride.now.state)
            assertEquals(
                0.0,
                assertNotNull(ride.rig.fakeTrainer.simulation).gradePercent,
                "left flat, not holding a gear",
            )
        }

    @Test
    fun `a paused ERG step is held at zero watts and the target ramps back in`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(60, 0.8))
            ride.start()
            ride.rig.advance(10_000)
            ride.engine.pause()
            ride.rig.advance(3_000)
            assertEquals(0, ride.rig.fakeTrainer.targetPowerWatts)
            ride.engine.resume()
            ride.rig.advance(5_000)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            val targets = ride.rig.fakeTrainer.commands.filterIsInstance<FakeTrainerCommand.TargetPower>()
            assertTrue(
                targets
                    .map {
                        it.watts
                    }.containsAll(listOf(0, 53, 107, 160)),
                "0 W, then the 3 s ramp: ${targets.map { it.watts }}",
            )
        }

    @Test
    fun `an auto-paused ERG ride holds a little resistance on the wire and resumes on the first stroke`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(120, 0.8))
            ride.start()
            ride.rig.advance(10_000)
            ride.rig.fakeTrainer.cadenceRpm = 0 // the rider stops
            ride.rig.advance(6_000)
            assertEquals(PauseReason.AUTO, ride.now.pauseReason)
            assertEquals(25, ride.rig.fakeTrainer.targetPowerWatts, "a hold of 25 W, not 0")

            ride.rig.fakeTrainer.cadenceRpm = 90 // the first stroke; the trainer notifies once a second
            ride.rig.advance(1_000)
            assertEquals(RideState.RIDING, ride.now.state)
            ride.rig.advance(4_000)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            assertEquals(
                1,
                ride.commands().count { it == "TARGET 0" },
                "0 W only before the start of the ride: the pause never asked for it",
            )
            ride.rig.assertNoViolations()
        }

    // endregion

    // region dropouts (spec §4.1, §4.2)

    @Test
    fun `the trainer's link drops mid-ride, the ride pauses, records the seconds as gaps, and resumes by itself`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(40, 0.8))
            ride.rig.fakeTrainer.absentFailsAfter = 2.seconds
            ride.start()
            ride.rig.advance(15_000)
            assertEquals(RideState.RIDING, ride.now.state)

            ride.rig.fakeTrainer.powerOff() // the plug is pulled
            ride.rig.settle()
            assertEquals(RideState.PAUSED, ride.now.state)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, ride.now.pauseReason, "at once, not at the next tick")

            ride.rig.advance(10_000)
            assertEquals(RideState.PAUSED, ride.now.state)
            ride.rig.fakeTrainer.powerOn()
            ride.rig.advance(15_000)
            assertEquals(RideState.RIDING, ride.now.state, "the reconnect resumed it")
            ride.rig.advance(60_000)
            assertEquals(RideState.FINISHED, ride.now.state)

            val samples = ride.record.samples
            assertEquals((1..samples.size).toList(), samples.map { it.timeOffsetSeconds }, "every second is recorded")
            val paused = samples.filter { it.paused }
            assertTrue(paused.size in 10..25, "the outage: ${paused.size} s")
            assertTrue(paused.all { it.power == null && it.cadence == null }, "gaps, not zeros")
            assertTrue(paused.all { it.heartRate != null }, "the strap kept riding")
            assertEquals(
                paused.map { it.timeOffsetSeconds },
                (paused.first().timeOffsetSeconds..paused.last().timeOffsetSeconds).toList(),
            )
            // Right after the resume the trainer has not sent its first notification yet: at most a second or two of
            // "riding" samples without a reading, and only there.
            val lastPaused = paused.last().timeOffsetSeconds
            val unread = samples.filter { !it.paused && (it.power == null || it.cadence == null) }
            assertTrue(unread.all { it.timeOffsetSeconds in (lastPaused + 1)..(lastPaused + 2) }, "$unread")
            assertEquals(40, ride.record.summary.movingSeconds, "the workout lost nothing")
            // The trainer forgot everything with the link, and was told again: control, then the target.
            assertEquals("TARGET 160", ride.commands().last { it.startsWith("TARGET") }, "told the target again")
            assertTrue(
                ride.commands().count {
                    it == "RC"
                } >= 2,
                "control was requested again after the reconnect: ${ride.commands()}",
            )
            ride.rig.assertNoViolations()
        }

    @Test
    fun `a status 133 on the way back is retried with a fresh GATT object each time`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(60, 0.8))
            ride.start()
            ride.rig.advance(10_000)
            ride.rig.fakeTrainer.faults.failNextConnects(2, GattStatus.GATT_ERROR)
            ride.rig.fakeTrainer.dropLink(status = GattStatus.GATT_ERROR)
            ride.rig.settle()
            assertEquals(RideState.PAUSED, ride.now.state)
            ride.rig.advance(20_000)
            assertEquals(RideState.RIDING, ride.now.state)

            val transports = ride.rig.ble.transports.filter { it.device == TRAINER_ID }
            assertEquals(4, transports.size, "the first, the dropped one's two failed retries, and the one that worked")
            assertTrue(transports.dropLast(1).all { it.isClosed }, "every failed or dropped GATT object was closed")
            assertEquals(transports.size, transports.toSet().size, "none was reused")
            ride.rig.assertNoViolations()
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
        }

    @Test
    fun `the strap drops mid-ride, a gap in the heart rate and nothing else`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(60, 0.8))
            ride.rig.fakeStrap.absentFailsAfter = 2.seconds
            ride.start()
            ride.rig.advance(20_000)
            ride.rig.fakeStrap.powerOff()
            ride.rig.advance(15_000)
            ride.rig.fakeStrap.powerOn()
            ride.rig.advance(60_000)
            assertEquals(RideState.FINISHED, ride.now.state)

            assertTrue(RideState.PAUSED !in ride.states(), "a strap never pauses the ride")
            val samples = ride.record.samples
            assertEquals(60, samples.size)
            assertTrue(samples.all { it.power != null && !it.paused }, "the trainer carried on")
            val gap = samples.filter { it.heartRate == null }
            assertTrue(gap.size in 12..30, "the strap's absence: ${gap.size} s")
            assertTrue(samples.first().heartRate != null && samples.last().heartRate != null, "before and after")
            assertEquals(60, ride.record.summary.movingSeconds)
        }

    @Test
    fun `a strap taken off the chest is a gap, put back on it returns`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(40, 0.8))
            ride.start()
            ride.rig.advance(10_000)
            ride.rig.fakeStrap.onChest = false
            ride.rig.advance(10_000)
            ride.rig.fakeStrap.onChest = true
            ride.rig.advance(30_000)
            val samples = ride.record.samples
            val gap = samples.filter { it.heartRate == null }.map { it.timeOffsetSeconds }
            assertTrue(gap.size in 8..11, "about ten seconds: $gap")
            assertEquals(gap, (gap.first()..gap.last()).toList())
            assertNotNull(samples.last().heartRate)
        }

    // endregion

    // region the Control Point under stress (docs/protocols/ftms-hr.md §4.4)

    @Test
    fun `operation failed answers delay a target but do not stop the ride`() =
        runTest {
            val ride = BleRide(this, workout = bleWorkout)
            ride.start()
            ride.rig.advance(28_000)
            ride.rig.fakeTrainer.faults.controlPointResult(result = 0x04, count = 2, opcode = 0x05)
            ride.rig.advance(10_000)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            assertEquals(RideState.RIDING, ride.now.state)
            val samples = ride.record.samples
            assertEquals(160, samples[34].power, "after the retries the step is held")
            assertTrue(ride.rig.fakeTrainer.commands.count { it is FakeTrainerCommand.TargetPower } >= 5)
        }

    @Test
    fun `a trainer that rejects every target is retried every second and the ride goes on`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(30, 0.8))
            ride.rig.fakeTrainer.faults.controlPointResult(result = 0x03, count = 1_000, opcode = 0x05)
            ride.start()
            ride.rig.advance(31_000)
            assertEquals(RideState.FINISHED, ride.now.state, "an unusable target never ends the ride")
            val attempts = ride.commands().count { it == "TARGET 160" }
            assertTrue(attempts in 20..35, "about one try a second: $attempts")
            assertTrue(ride.record.samples.none { it.paused })
            assertEquals(30, ride.record.samples.size)
        }

    @Test
    fun `a silent Control Point times out and the engine's retry takes control again and sets the target`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(30, 0.8))
            ride.rig.fakeTrainer.faults.noControlPointResponse(count = 1, opcode = 0x05)
            ride.start()
            ride.rig.advance(2_000)
            assertTrue(ride.rig.fakeTrainer.targetPowerWatts != 160, "not yet: the first target got no answer")
            ride.rig.advance(5_000)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            val tail = ride.commands().dropWhile { it != "TARGET 160" }
            assertEquals(
                listOf("TARGET 160", "RC", "START", "TARGET 160"),
                tail,
                "timeout, start over, then the target",
            )
            assertEquals(RideState.RIDING, ride.now.state)
        }

    @Test
    fun `control taken away by another party is taken back and the target re-sent`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(60, 0.8))
            ride.start()
            ride.rig.advance(10_000)
            ride.rig.fakeTrainer.revokeControl()
            ride.rig.advance(5_000)
            assertTrue(ride.rig.fakeTrainer.hasControl)
            assertEquals(160, ride.rig.fakeTrainer.targetPowerWatts)
            assertEquals(RideState.RIDING, ride.now.state)
        }

    // endregion

    // region RR intervals (spec §4.1, §4.8)

    @Test
    fun `RR intervals from the strap reach the ride record in milliseconds, with the domain rounding`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(30, 0.8))
            ride.start()
            ride.rig.advance(5_000)
            ride.rig.fakeStrap.nextRrRaw = listOf(900, 1024)
            ride.rig.advance(2_000)
            val rr = ride.record.rrIntervals
            val special = rr.filter { it.intervalMs == 879 }
            assertEquals(1, special.size, "900/1024 s is 878.9 ms, rounded to 879: ${rr.map { it.intervalMs }}")
            assertEquals(
                1000,
                rr
                    .first {
                        it.timeOffsetSeconds == special.single().timeOffsetSeconds &&
                            it.intervalMs == 1000
                    }.intervalMs,
            )
        }

    @Test
    fun `a whole ride of RR is about the beats of the ride, none lost and none invented`() =
        runTest {
            val ride = BleRide(this, workout = steadyWorkout(60, 0.8))
            ride.rig.fakeStrap.bpm = 120
            ride.start()
            ride.rig.advance(65_000)
            val rr = ride.record.rrIntervals
            assertTrue(rr.size in 118..122, "two beats a second for a minute: ${rr.size}")
            assertTrue(rr.all { it.intervalMs in 480..520 }, "120 bpm is 500 ms a beat")
            assertEquals(rr.map { it.timeOffsetSeconds }.sorted(), rr.map { it.timeOffsetSeconds })
        }

    @Test
    fun `a strap that sends no RR gives a ride without HRV, not an error`() =
        runTest {
            val rig = BleRig(this, strapOptions = FakeHeartRateStrapOptions(sendsRr = false))
            val ride = BleRide(this, rig = rig, workout = steadyWorkout(20, 0.8))
            ride.start()
            ride.rig.advance(25_000)
            assertEquals(RideState.FINISHED, ride.now.state)
            assertTrue(ride.record.rrIntervals.isEmpty())
            assertTrue(ride.record.samples.all { it.heartRate != null })
        }

    // endregion
}
