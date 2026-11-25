package app.overdrive.core.live

import app.overdrive.core.live.LiveCompliance.Verdict
import app.overdrive.core.ride.RideState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live coach's snapshot and the compliance it carries (spec §12.1, docs/adr/0039), from invented rides. */
class LiveSnapshotBuilderTest {
    private fun builder(vararg rides: app.overdrive.core.ride.RideSnapshot): LiveSnapshotBuilder =
        LiveSnapshotBuilder().also { b -> rides.forEach(b::accept) }

    private fun LiveSnapshotBuilder.snapshot(muted: Boolean = false): LiveSnapshot =
        assertNotNull(build(seq = 3, clockMillis = 1_795_000_000_000, muted = muted))

    // ---- Compliance ------------------------------------------------------------------------------

    private class JudgeCase(
        val name: String,
        val verdict: Verdict,
        val power: Int? = 238,
        val target: Int? = 238,
        val step: Int = 1,
        val elapsed: Int = 30,
        val state: RideState = RideState.RIDING,
        val guard: Boolean = false,
        val sinceResume: Int? = null,
        val hold: app.overdrive.core.ride.HeartRateHoldState? = null,
    )

    private val judgeTable =
        listOf(
            JudgeCase("on target exactly", Verdict.ON_TARGET),
            JudgeCase("just under 5% above is on target", Verdict.ON_TARGET, power = 249, target = 238),
            JudgeCase("5% above and a watt over is off", Verdict.OFF_TARGET, power = 250, target = 238),
            JudgeCase("just under 5% below is on target", Verdict.ON_TARGET, power = 227, target = 238),
            JudgeCase("5% below and a watt more is off", Verdict.OFF_TARGET, power = 226, target = 238),
            JudgeCase("a stop is off target", Verdict.OFF_TARGET, power = 0),
            JudgeCase("a target of nothing needs nothing", Verdict.ON_TARGET, power = 0, target = 0),
            JudgeCase("no power reading is not judged", Verdict.NOT_JUDGED, power = null),
            JudgeCase("a paused second is not judged", Verdict.NOT_JUDGED, state = RideState.PAUSED),
            JudgeCase("before the start is not judged", Verdict.NOT_JUDGED, state = RideState.READY),
            JudgeCase("the tick that enters a step is not judged", Verdict.NOT_JUDGED, elapsed = 0),
            JudgeCase("the second second of a step is", Verdict.ON_TARGET, elapsed = 1),
            JudgeCase("just after a resume is not judged", Verdict.NOT_JUDGED, sinceResume = 4),
            JudgeCase("five seconds after a resume is", Verdict.ON_TARGET, sinceResume = 5),
            JudgeCase("with the stall guard holding is not judged", Verdict.NOT_JUDGED, power = 120, guard = true),
            JudgeCase("a free step is not judged", Verdict.NOT_JUDGED, step = 3, target = null),
            JudgeCase("heart rate in the band", Verdict.ON_TARGET, step = 4, hold = LiveRides.hold(inBand = true)),
            JudgeCase(
                "heart rate out of the band",
                Verdict.OFF_TARGET,
                step = 4,
                hold = LiveRides.hold(inBand = false),
            ),
            JudgeCase("no heart rate signal", Verdict.NOT_JUDGED, step = 4, hold = LiveRides.hold(inBand = null)),
            JudgeCase(
                "the start ramp is not judged",
                Verdict.NOT_JUDGED,
                step = 4,
                hold = LiveRides.hold(startRamp = true),
            ),
            JudgeCase(
                "a lost signal is not judged",
                Verdict.NOT_JUDGED,
                step = 4,
                hold = LiveRides.hold(signalLost = true),
            ),
            JudgeCase(
                "a lost strap is not judged",
                Verdict.NOT_JUDGED,
                step = 4,
                hold = LiveRides.hold(strapLost = true),
            ),
        )

    @Test
    fun `a second is judged as the table says`() {
        for (case in judgeTable) {
            val ride =
                LiveRides.ride(
                    second = 100,
                    step = case.step,
                    elapsedInStep = case.elapsed,
                    state = case.state,
                    power = case.power,
                    target = case.target,
                    guard = case.guard,
                    sinceResume = case.sinceResume,
                    hold = case.hold,
                )
            assertEquals(case.verdict, LiveCompliance.judge(ride), case.name)
        }
    }

    @Test
    fun `the share is a whole percent halves up and none before the first judged second`() {
        val table =
            listOf(
                Triple(0, 0, null),
                Triple(0, 7, 0),
                Triple(7, 7, 100),
                Triple(1, 3, 33),
                Triple(2, 3, 67),
                Triple(1, 8, 13),
                Triple(1, 2, 50),
                Triple(3, 4, 75),
                Triple(599, 600, 100),
                Triple(299, 600, 50),
            )
        for ((on, judged, expected) in table) {
            assertEquals(
                expected,
                LiveCompliance.percent(on, judged),
                "$on of $judged",
            )
        }
    }

    @Test
    fun `the share counts the judged seconds of this step and starts over with the next`() {
        val b = LiveSnapshotBuilder()
        // Step 1: 10 judged seconds, 8 of them on target.
        for (i in 1..10) {
            b.accept(
                LiveRides.ride(
                    second = i,
                    step = 1,
                    elapsedInStep = i,
                    power =
                        if (i <=
                            8
                        ) {
                            238
                        } else {
                            200
                        },
                ),
            )
        }
        assertEquals(80, b.snapshot().compliancePercent)
        // A second without a reading is not judged and moves nothing; a paused one neither.
        b.accept(LiveRides.ride(second = 11, step = 1, elapsedInStep = 11, power = null))
        b.accept(LiveRides.ride(second = 12, step = 1, elapsedInStep = 11, state = RideState.PAUSED))
        assertEquals(80, b.snapshot().compliancePercent)
        // Step 2 starts over: nothing yet, then the first judged second.
        b.accept(LiveRides.ride(second = 13, step = 2, elapsedInStep = 0, target = 125, power = 125))
        assertNull(b.snapshot().compliancePercent)
        b.accept(LiveRides.ride(second = 14, step = 2, elapsedInStep = 1, target = 125, power = 125))
        assertEquals(100, b.snapshot().compliancePercent)
    }

    @Test
    fun `restarting a step with Previous starts its share over`() {
        val b = LiveSnapshotBuilder()
        for (i in 1..20) b.accept(LiveRides.ride(second = i, step = 1, elapsedInStep = 100 + i, power = 150))
        assertEquals(0, b.snapshot().compliancePercent)
        b.accept(LiveRides.ride(second = 21, step = 1, elapsedInStep = 1))
        assertEquals(100, b.snapshot().compliancePercent)
    }

    @Test
    fun `a snapshot repeated by a command is not a second second`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 1, elapsedInStep = 1, power = 238))
        repeat(20) { b.accept(LiveRides.ride(second = 1, elapsedInStep = 1, power = 100)) }
        val snapshot = b.snapshot()
        assertEquals(238, snapshot.power, "only the first of the tick counts")
        assertEquals(100, snapshot.compliancePercent)
    }

    @Test
    fun `a free step has no share whatever the ride did before`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 1, step = 1, elapsedInStep = 10))
        b.accept(LiveRides.ride(second = 2, step = 3, elapsedInStep = 5, target = null))
        val snapshot = b.snapshot()
        assertEquals(LiveStepType.FREE, snapshot.step.type)
        assertNull(snapshot.compliancePercent)
        assertNull(snapshot.step.targetWatts)
        assertNull(snapshot.step.targetBpm)
    }

    // ---- The means ---------------------------------------------------------------------------------

    @Test
    fun `power heart rate and cadence are the means of the last five seconds in whole numbers`() {
        val b = LiveSnapshotBuilder()
        // Seconds 1 to 8: the window at second 8 is 4 to 8.
        val power = listOf(100, 110, 120, 200, 210, 220, 230, 240)
        val hr = listOf(100, 101, 102, 140, 141, 142, 143, 144)
        val cadence = listOf(60, 61, 62, 80, 81, 82, 83, 84)
        for (i in 0 until 8) {
            b.accept(
                LiveRides.ride(second = i + 1, power = power[i], heartRate = hr[i], cadence = cadence[i]),
            )
        }
        val snapshot = b.snapshot()
        assertEquals((200 + 210 + 220 + 230 + 240) / 5, snapshot.power)
        assertEquals(142, snapshot.heartRate)
        assertEquals(82, snapshot.cadence)
    }

    @Test
    fun `the mean rounds to the nearest and ignores a second without a reading`() {
        val b = LiveSnapshotBuilder()
        val power = listOf(100, null, 101, 101, null)
        for (i in power.indices) b.accept(LiveRides.ride(second = i + 1, power = power[i], heartRate = null))
        val snapshot = b.snapshot()
        assertEquals(101, snapshot.power, "(100 + 101 + 101) / 3 = 100.67")
        assertNull(snapshot.heartRate, "no reading at all is absent, never zero")
    }

    @Test
    fun `a heart rate that is not one is absent`() {
        val b = LiveSnapshotBuilder()
        for (i in 1..5) b.accept(LiveRides.ride(second = i, heartRate = 7))
        assertNull(b.snapshot().heartRate)
    }

    @Test
    fun `before the first tick there is a snapshot of nothing measured`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 0, power = 300, state = RideState.RIDING))
        val snapshot = b.snapshot()
        assertEquals(0, snapshot.elapsedSeconds)
        assertNull(snapshot.power)
    }

    // ---- The step ----------------------------------------------------------------------------------

    @Test
    fun `the step is the one the record flattens with its time left and its target`() {
        val b = builder(LiveRides.ride(second = 205, step = 1, elapsedInStep = 205, target = 238))
        val snapshot = b.snapshot()
        assertEquals(LiveSnapshotStep(1, LiveStepType.STEADY, "Threshold", 600, 395, 238, null), snapshot.step)
        assertEquals(LiveRides.steps.size, snapshot.stepCount, "exactly the flattened workout's steps")
        assertEquals(205, snapshot.elapsedSeconds)
        assertEquals(3, snapshot.seq)
        assertEquals(1_795_000_000_000, snapshot.clock)
    }

    @Test
    fun `a ramp is a ramp a heart-rate step holds a heart rate and neither holds both`() {
        val ramp = builder(LiveRides.ride(second = 10, step = 0, elapsedInStep = 10, target = 130)).snapshot()
        assertEquals(LiveStepType.RAMP, ramp.step.type)
        assertEquals(130, ramp.step.targetWatts)
        assertNull(ramp.step.targetBpm)

        val holding = LiveRides.hold(target = 137)
        val heart =
            builder(
                LiveRides.ride(second = 10, step = 4, elapsedInStep = 10, target = 140, hold = holding),
            ).snapshot()
        assertEquals(LiveStepType.HEART_RATE, heart.step.type)
        assertEquals(
            137,
            heart.step.targetBpm,
            "the target the controller holds, which the gear buttons may have moved",
        )
        assertNull(heart.step.targetWatts, "the controller's own power is not the step's target")
    }

    @Test
    fun `the target is the step's and not the stall guard's`() {
        val guarded = builder(LiveRides.ride(second = 40, power = 150, target = 238, guard = true)).snapshot()
        assertEquals(238, guarded.step.targetWatts)
        assertEquals(true, guarded.stallGuardActive)
    }

    @Test
    fun `the time left is the step's and never more than its length`() {
        val last = builder(LiveRides.ride(second = 600, step = 5, elapsedInStep = 300)).snapshot()
        assertEquals(0, last.step.timeLeftSeconds)
        assertEquals(5, last.step.index)
    }

    @Test
    fun `paused and muted are what they are and a free ride has no snapshot`() {
        val paused = builder(LiveRides.ride(second = 50, state = RideState.PAUSED)).snapshot(muted = true)
        assertTrue(paused.paused)
        assertTrue(paused.muted)
        val running = builder(LiveRides.ride(second = 50)).snapshot()
        assertEquals(false, running.paused)
        assertEquals(false, running.muted)
        assertNull(LiveSnapshotBuilder().build(0, 1, false), "nothing was seen yet")
        assertNull(
            builder(
                app.overdrive.core.ride.RideSnapshot(RideState.RIDING, ftp = 250, rideSeconds = 4),
            ).build(0, 1, false),
            "a ride with no workout has no step",
        )
    }

    @Test
    fun `every snapshot the builder makes is the contract's`() {
        val b = LiveSnapshotBuilder()
        for (second in 1..40) {
            b.accept(
                LiveRides.ride(
                    second = second,
                    step = (second / 8) % LiveRides.steps.size,
                    elapsedInStep =
                        second % 8 + 1,
                ),
            )
            val text = LiveCoachJson.encodeSnapshot(b.snapshot())
            LiveCoachJson.decodeSnapshot(text)
        }
    }

    // ---- Events ------------------------------------------------------------------------------------

    @Test
    fun `the stall guard a pause and a resume are events newest first with how long ago`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 100))
        b.accept(LiveRides.ride(second = 110, guard = true, power = 150))
        b.accept(LiveRides.ride(second = 115, guard = true, power = 150))
        b.accept(LiveRides.ride(second = 120, state = RideState.PAUSED))
        b.accept(LiveRides.ride(second = 130, state = RideState.PAUSED))
        b.accept(LiveRides.ride(second = 135))
        b.accept(LiveRides.ride(second = 140))
        val events = b.snapshot().events
        assertEquals(
            listOf(
                LiveSnapshotEvent(LiveEventType.RESUMED, 5),
                LiveSnapshotEvent(LiveEventType.PAUSED, 20),
                LiveSnapshotEvent(LiveEventType.STALL_GUARD, 30),
            ),
            events,
        )
    }

    @Test
    fun `an event of more than ten minutes ago is forgotten and no more than eight are kept`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 10))
        b.accept(LiveRides.ride(second = 20, guard = true))
        b.accept(LiveRides.ride(second = 25, guard = false))
        assertEquals(1, b.snapshot().events?.size)
        b.accept(LiveRides.ride(second = 20 + 601))
        assertEquals(emptyList(), b.snapshot().events)
        // Ten pauses and resumes.
        var second = 700
        repeat(10) {
            b.accept(LiveRides.ride(second = second++, state = RideState.PAUSED))
            b.accept(LiveRides.ride(second = second++))
        }
        val events = assertNotNull(b.snapshot().events)
        assertEquals(8, events.size)
        assertEquals(LiveEventType.RESUMED, events.first().type)
        assertEquals(listOf(0), events.take(1).map { it.secondsAgo })
    }

    @Test
    fun `a strap that was there and is gone for fifteen seconds is lost once and a strap never seen is not`() {
        val b = LiveSnapshotBuilder()
        b.accept(LiveRides.ride(second = 1))
        for (i in 2..15) b.accept(LiveRides.ride(second = i, heartRate = null))
        assertEquals(emptyList(), b.snapshot().events, "fourteen seconds are not yet fifteen")
        b.accept(LiveRides.ride(second = 16, heartRate = null))
        assertEquals(listOf(LiveSnapshotEvent(LiveEventType.STRAP_LOST, 0)), b.snapshot().events)
        // Still gone: not reported again; back and gone again: reported again.
        for (i in 17..40) b.accept(LiveRides.ride(second = i, heartRate = null))
        assertEquals(1, b.snapshot().events?.count { it.type == LiveEventType.STRAP_LOST })
        b.accept(LiveRides.ride(second = 41))
        for (i in 42..60) b.accept(LiveRides.ride(second = i, heartRate = null))
        assertEquals(2, b.snapshot().events?.count { it.type == LiveEventType.STRAP_LOST })

        val never = LiveSnapshotBuilder()
        for (i in 1..60) never.accept(LiveRides.ride(second = i, heartRate = null))
        assertEquals(emptyList(), never.snapshot().events)
    }
}
