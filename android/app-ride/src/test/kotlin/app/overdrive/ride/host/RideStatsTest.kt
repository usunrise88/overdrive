package app.overdrive.ride.host

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RideStatsTest {
    private val steps =
        listOf(
            FlatStep("A", 100, 0.5, 0.5, TrainerMode.ERG, startSeconds = 0),
            FlatStep("B", 100, 1.0, 1.0, TrainerMode.ERG, startSeconds = 100),
        )

    private fun snapshot(
        second: Int,
        power: Int?,
        stepIndex: Int = 0,
        state: RideState = RideState.RIDING,
    ): RideSnapshot {
        val step = steps[stepIndex]
        return RideSnapshot(
            state = state,
            ftp = 200,
            elapsedSeconds = second,
            power = power,
            mode = TrainerMode.ERG,
            workout =
                WorkoutProgress(
                    "W",
                    steps,
                    stepIndex,
                    stepElapsedSeconds = second - step.startSeconds,
                    stepRemainingSeconds = step.endSeconds - second,
                    workoutElapsedSeconds = second,
                    workoutRemainingSeconds = 200 - second,
                ),
        )
    }

    @Test
    fun averagesCountEachRideSecondOnceAndSkipMissingPower() {
        val stats = RideStatsAccumulator(ftp = 200)
        stats.accept(snapshot(1, 100))
        stats.accept(snapshot(1, 999)) // the same second again, e.g. a repeated snapshot after a command
        stats.accept(snapshot(2, null)) // a gap
        val result = stats.accept(snapshot(3, 200))
        assertEquals(150, result.averagePower)
    }

    @Test
    fun pausedSecondsAreNotCounted() {
        val stats = RideStatsAccumulator(ftp = 200)
        stats.accept(snapshot(1, 100))
        val result = stats.accept(snapshot(2, 0, state = RideState.PAUSED))
        assertEquals(100, result.averagePower)
    }

    @Test
    fun lapAverageRestartsWhenTheStepChanges() {
        val stats = RideStatsAccumulator(ftp = 200)
        stats.accept(snapshot(98, 100))
        stats.accept(snapshot(99, 100))
        val inLapB = stats.accept(snapshot(101, 300, stepIndex = 1))
        assertEquals(300, inLapB.lapAveragePower)
        assertEquals(167, inLapB.averagePower)
        // Skipping back to step A starts a new lap there.
        val back = stats.accept(snapshot(102, 50, stepIndex = 0))
        assertEquals(50, back.lapAveragePower)
    }

    @Test
    fun noPowerYetMeansNoAverages() {
        val stats = RideStatsAccumulator(ftp = 200)
        val result = stats.accept(snapshot(1, null))
        assertNull(result.averagePower)
        assertNull(result.lapAveragePower)
    }

    @Test
    fun traceGetsAPointPerCompletedTenSecondBucketOnly() {
        val stats = RideStatsAccumulator(ftp = 200)
        var result = RideStats()
        // Workout seconds 1..10 fall into buckets 0 and 1.
        for (second in 0..9) result = stats.accept(snapshot(second + 1, 100))
        // Bucket 0 (seconds 1..9) completed when second 10 started bucket 1.
        assertEquals(1, result.trace.size)
        assertEquals(5, result.trace[0].seconds)
        assertEquals(0.5f, result.trace[0].fraction, 1e-6f)

        val before = result.trace
        result = stats.accept(snapshot(11, 100))
        assertSame("no new bucket completed, the same list is published", before, result.trace)
    }

    @Test
    fun theTraceFollowsASkippedPosition() {
        val stats = RideStatsAccumulator(ftp = 200)
        stats.accept(snapshot(1, 100))
        // The rider skips ahead: the workout position jumps while the ride clock moves by one.
        val skipped =
            snapshot(
                2,
                200,
            ).let { it.copy(workout = it.workout!!.copy(workoutElapsedSeconds = 105, stepIndex = 1)) }
        val result = stats.accept(skipped)
        assertEquals(listOf(5), result.trace.map { it.seconds })
    }
}
