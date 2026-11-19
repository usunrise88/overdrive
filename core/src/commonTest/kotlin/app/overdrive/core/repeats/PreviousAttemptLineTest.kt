package app.overdrive.core.repeats

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.HeartRateStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live line against the previous attempt (spec §11.7, ADR 0026, docs/adr/0034): which seconds count, and when it speaks. */
class PreviousAttemptLineTest {
    // Warm-up 300 s at 50%, main 600 s at 90% (180 W at an FTP of 200), recovery 300 s at 50%.
    private val workout =
        Workout(
            schema = 1,
            id = "w",
            name = "Test",
            steps = listOf(SteadyStep(300, 0.5), SteadyStep(600, 0.9), SteadyStep(300, 0.5)),
        )

    private fun step(
        number: Int,
        start: Int,
        end: Int,
        target: Double?,
        heartRate: Double?,
    ) = RepeatPreviousStep(number, "Step $number", start, end, target, heartRate)

    private fun attempt(
        vararg steps: RepeatPreviousStep,
        ftp: Int = 200,
    ) = RepeatPreviousAttempt(
        rideId = "ride-1",
        date = "2026-11-12",
        startTime = "2026-11-12T08:00:00Z",
        durationSeconds = 1200,
        ftp = ftp,
        attempt = 1,
        heartRateAtPower = 150.0,
        steps = steps.toList(),
    )

    /** Last time: 100 W at 120 bpm in the warm-up and 180 W at 150 bpm in the main step. */
    private val lastTime =
        attempt(step(1, 0, 300, 100.0, 120.0), step(2, 300, 900, 180.0, 150.0), step(3, 900, 1200, 100.0, null))

    /** A ride under way: [second] puts the rider on the workout second by second. */
    private inner class Ride(
        val comparison: PreviousAttemptComparison,
        workout: Workout = this@PreviousAttemptLineTest.workout,
        private val ftp: Int = 200,
    ) {
        val player = WorkoutPlayer(workout.flatSteps(), ftp)
        private var elapsed = 0

        fun snapshot(
            state: RideState,
            power: Int?,
            heartRate: Int?,
        ) = RideSnapshot(
            state = state,
            ftp = ftp,
            elapsedSeconds = elapsed,
            power = power,
            heartRate = heartRate,
            workout = player.progress("Test"),
        )

        /** One second of riding: the snapshot of the tick, then [extra] more snapshots of the same second (sensor updates). */
        fun second(
            power: Int? = 180,
            heartRate: Int? = 147,
            extra: Int = 0,
        ) {
            player.advance(1)
            elapsed++
            repeat(1 + extra) { comparison.onSnapshot(snapshot(RideState.RIDING, power, heartRate)) }
        }

        fun seconds(
            count: Int,
            power: Int? = 180,
            heartRate: Int? = 147,
        ) = repeat(count) { second(power, heartRate) }

        fun pause() = comparison.onSnapshot(snapshot(RideState.PAUSED, null, null))

        /** Rides up to the second **before** step [index] (0-based) begins, so that the next [second] is its first. */
        fun toStep(index: Int) {
            while (player.positionSeconds < player.steps[index].startSeconds - 1) second(power = 100, heartRate = 120)
        }
    }

    /** The warm-up ridden, then the main step: the ride is at second 0 of step 2 (index 1). */
    private fun rideAtTheMainStep(previous: RepeatPreviousAttempt? = lastTime): Ride {
        val ride = Ride(PreviousAttemptComparison(previous))
        ride.toStep(1)
        return ride
    }

    @Test
    fun `says nothing without a previous attempt`() {
        val ride = rideAtTheMainStep(previous = null)
        ride.seconds(500)
        assertNull(ride.comparison.line())
        assertFalse(ride.comparison.hasPrevious)
    }

    @Test
    fun `says nothing until enough has counted, then gives the difference in plain words`() {
        val ride = rideAtTheMainStep()
        // The step is 600 s: the first 60 s do not count, and 270 counted seconds (half of the 540 the last attempt had) are needed.
        ride.seconds(60)
        assertNull(ride.comparison.line())
        ride.seconds(269)
        assertNull(ride.comparison.line(), "269 counted seconds")
        ride.second()
        assertEquals(PreviousLine("Heart rate 3 below last time", -3), ride.comparison.line())
    }

    @Test
    fun `a short step needs the repeat rule's 120 counted seconds`() {
        val short = Workout(schema = 1, id = "w", name = "Test", steps = listOf(SteadyStep(240, 0.9)))
        val last = attempt(step(1, 0, 240, 180.0, 150.0))
        val ride = Ride(PreviousAttemptComparison(last), short)
        ride.seconds(60 + 119)
        assertNull(ride.comparison.line())
        ride.second()
        assertEquals("Heart rate 3 below last time", ride.comparison.line()?.text)
    }

    @Test
    fun `the minimum is the repeat rule's 120 s or half of what the last attempt counted, whichever is more`() {
        assertEquals(120, PreviousAttemptComparison.minimumCountedSeconds(180))
        assertEquals(120, PreviousAttemptComparison.minimumCountedSeconds(300))
        assertEquals(120, PreviousAttemptComparison.minimumCountedSeconds(60))
        assertEquals(120, PreviousAttemptComparison.minimumCountedSeconds(0))
        assertEquals(150, PreviousAttemptComparison.minimumCountedSeconds(360))
        assertEquals(270, PreviousAttemptComparison.minimumCountedSeconds(600))
        assertEquals(570, PreviousAttemptComparison.minimumCountedSeconds(1200))
    }

    @Test
    fun `above, below and the same, in whole bpm with one bpm counting as the same`() {
        fun lineAt(heartRate: Int): PreviousLine? {
            val ride = rideAtTheMainStep()
            ride.seconds(60 + 270, heartRate = heartRate)
            return ride.comparison.line()
        }
        assertEquals("Heart rate 3 below last time", lineAt(147)?.text)
        assertEquals("Heart rate 2 below last time", lineAt(148)?.text)
        assertEquals("Heart rate same as last time", lineAt(149)?.text)
        assertEquals("Heart rate same as last time", lineAt(150)?.text)
        assertEquals("Heart rate same as last time", lineAt(151)?.text)
        assertEquals("Heart rate 2 above last time", lineAt(152)?.text)
        assertEquals("Heart rate 9 above last time", lineAt(159)?.text)
        assertEquals(9, lineAt(159)?.differenceBpm)
    }

    @Test
    fun `the words never say fitter or any verdict`() {
        for (hr in listOf(130, 147, 150, 153, 170)) {
            val ride = rideAtTheMainStep()
            ride.seconds(330, heartRate = hr)
            val text = ride.comparison.line()!!.text.lowercase()
            assertFalse("fitter" in text || "better" in text || "worse" in text || "fit" in text.split(" "), text)
        }
    }

    @Test
    fun `seconds at another power do not count, and the line waits for the rest`() {
        val ride = rideAtTheMainStep()
        ride.seconds(60)
        ride.seconds(100, power = 160) // 11% under the 180 W target
        ride.seconds(100, power = 192) // 6.7% over
        ride.seconds(269)
        assertNull(ride.comparison.line())
        ride.second()
        assertNotNull(ride.comparison.line())
    }

    @Test
    fun `the power tolerance is 3 percent of the target, at both ends`() {
        fun counted(power: Int): Boolean {
            val ride = rideAtTheMainStep()
            ride.seconds(60)
            ride.seconds(270, power = power)
            return ride.comparison.line() != null
        }
        // 3% of 180 W is 5.4 W: 175 to 185 count, 174 and 186 do not.
        assertTrue(counted(175))
        assertTrue(counted(185))
        assertFalse(counted(174))
        assertFalse(counted(186))
    }

    @Test
    fun `seconds without a heart rate or without power do not count`() {
        val ride = rideAtTheMainStep()
        ride.seconds(60)
        ride.seconds(100, heartRate = null)
        ride.seconds(50, power = null)
        ride.seconds(269)
        assertNull(ride.comparison.line())
        ride.second()
        assertNotNull(ride.comparison.line())
    }

    @Test
    fun `several snapshots in one second count once`() {
        val ride = rideAtTheMainStep()
        repeat(60) { ride.second() }
        repeat(269) { ride.second(extra = 3) }
        assertNull(ride.comparison.line())
        ride.second(extra = 3)
        assertNotNull(ride.comparison.line())
    }

    @Test
    fun `a pause ends the stretch, and the minute after the resume is left out too`() {
        val ride = rideAtTheMainStep()
        ride.seconds(60 + 200)
        assertNull(ride.comparison.line())
        ride.pause()
        ride.seconds(60) // the minute after the resume: not counted
        ride.seconds(69)
        assertNull(ride.comparison.line(), "200 + 69 counted")
        ride.second()
        assertNotNull(ride.comparison.line(), "200 + 70 counted")
    }

    @Test
    fun `a new step starts the count over, and only a steady step is judged`() {
        val ride = rideAtTheMainStep()
        ride.seconds(60 + 270)
        assertNotNull(ride.comparison.line())
        ride.toStep(2) // recovery: the last attempt has no heart rate for it
        ride.seconds(250, power = 100, heartRate = 120)
        assertNull(ride.comparison.line())
        assertTrue(ride.comparison.hasPrevious)
    }

    @Test
    fun `a step with no figure last time has no line, and the figure of another step is not used`() {
        val onlyTheWarmUp = attempt(step(1, 0, 300, 100.0, 120.0))
        val ride = rideAtTheMainStep(previous = onlyTheWarmUp)
        ride.seconds(60 + 400)
        assertNull(ride.comparison.line())
    }

    @Test
    fun `an attempt at another FTP is not comparable`() {
        // Last time the main step was 150 W (an FTP of 167); now it is 180 W: 17% apart.
        val other = attempt(step(2, 300, 900, 150.0, 140.0), ftp = 167)
        val ride = rideAtTheMainStep(previous = other)
        ride.seconds(60 + 400)
        assertNull(ride.comparison.line())
        // Within 3% it is: 176 W against 180 W.
        val near = attempt(step(2, 300, 900, 176.0, 140.0), ftp = 196)
        val again = rideAtTheMainStep(previous = near)
        again.seconds(60 + 400)
        assertEquals("Heart rate 7 above last time", again.comparison.line()?.text)
    }

    @Test
    fun `a ramp, a free step and a heart-rate step have no fixed power and no line`() {
        val mixed =
            Workout(
                schema = 1,
                id = "w",
                name = "Test",
                steps =
                    listOf(
                        RampStep(600, 0.5, 0.9),
                        FreeStep(600),
                        HeartRateStep(600, targetBpm = 135, powerMin = 80, powerMax = 200),
                    ),
            )
        val last =
            RepeatPreviousAttempt(
                "ride-1",
                "2026-11-12",
                "2026-11-12T08:00:00Z",
                1800,
                200,
                1,
                null,
                listOf(
                    step(1, 0, 600, 140.0, 140.0),
                    step(2, 600, 1200, 140.0, 140.0),
                    step(3, 1200, 1800, 140.0, 140.0),
                ),
            )
        val ride = Ride(PreviousAttemptComparison(last), mixed)
        repeat(1800) {
            ride.second(power = 140, heartRate = 140)
            assertNull(ride.comparison.line(), "second ${it + 1}")
        }
    }

    @Test
    fun `a previous attempt that arrives late speaks at once if enough has counted`() {
        val ride = rideAtTheMainStep(previous = null)
        ride.seconds(400)
        assertNull(ride.comparison.line())
        ride.comparison.setPrevious(lastTime)
        assertEquals("Heart rate 3 below last time", ride.comparison.line()?.text)
        ride.comparison.setPrevious(null)
        assertNull(ride.comparison.line())
    }

    @Test
    fun `reads nothing from a snapshot that is not riding, and forgets a free ride`() {
        val comparison = PreviousAttemptComparison(lastTime)
        val ride = Ride(comparison)
        ride.toStep(1)
        ride.seconds(330)
        assertNotNull(comparison.line())
        // A finished ride and a snapshot without a workout change nothing that was counted... except that a free ride forgets the step.
        comparison.onSnapshot(RideSnapshot(RideState.FINISHED, ftp = 200, workout = ride.player.progress("Test")))
        assertNotNull(comparison.line())
        comparison.onSnapshot(RideSnapshot(RideState.RIDING, ftp = 200))
        assertNull(comparison.line())
    }
}
