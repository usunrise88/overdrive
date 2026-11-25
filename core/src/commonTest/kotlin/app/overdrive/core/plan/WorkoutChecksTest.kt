package app.overdrive.core.plan

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the player can run from the plan (ADR 0017), and the intensity hint a planned row shows. */
class WorkoutChecksTest {
    private fun workout(vararg steps: app.overdrive.core.workout.Step) =
        Workout(1, "icu-1", "Test", null, steps.toList())

    // region playability

    @Test
    fun `every workout of the schema's four step types and ordinary length is runnable`() {
        assertEquals(Playability.Runnable, PlanSamples.sweetSpot().playability())
        assertEquals(Playability.Runnable, workout(FreeStep(duration = 3600)).playability())
        assertEquals(Playability.Runnable, workout(SteadyStep(duration = 1, target = 3.0)).playability())
    }

    @Test
    fun `twelve hours is the longest to the second`() {
        assertEquals(Playability.Runnable, workout(FreeStep(duration = 12 * 3600)).playability())
        val over = workout(FreeStep(duration = 12 * 3600 + 1)).playability()
        assertEquals("Longer than 12 hours", assertIs<Playability.Unrunnable>(over).reason)
        val repeated =
            workout(RepeatStep(count = 13, steps = listOf(SteadyStep(duration = 3600, target = 0.5)))).playability()
        assertIs<Playability.Unrunnable>(repeated)
    }

    @Test
    fun `two thousand steps is the most once the repeats are expanded`() {
        val round = listOf(SteadyStep(duration = 10, target = 0.9), SteadyStep(duration = 10, target = 0.5))
        assertEquals(Playability.Runnable, workout(RepeatStep(count = 1000, steps = round)).playability())
        val over = workout(RepeatStep(count = 1000, steps = round), FreeStep(duration = 10)).playability()
        assertEquals("More than 2000 steps", assertIs<Playability.Unrunnable>(over).reason)
    }

    @Test
    fun `a repeat of billions is refused by arithmetic not by trying to expand it`() {
        val huge =
            workout(
                RepeatStep(
                    count = Int.MAX_VALUE,
                    steps = List(50) { SteadyStep(duration = Int.MAX_VALUE, target = 0.5) },
                ),
                RepeatStep(count = Int.MAX_VALUE, steps = listOf(FreeStep(duration = Int.MAX_VALUE))),
            )
        assertIs<Playability.Unrunnable>(huge.playability())
        assertNull(huge.intensityFactor(), "and it is not asked for a hint either")
    }

    // endregion

    // region intensity factor

    private fun assertNear(
        expected: Double,
        actual: Double?,
        tolerance: Double = 1e-9,
    ) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    @Test
    fun `a steady workout is its own target`() {
        assertNear(0.75, workout(SteadyStep(duration = 3600, target = 0.75)).intensityFactor())
        assertNear(1.0, workout(SteadyStep(duration = 30, target = 1.0)).intensityFactor())
    }

    @Test
    fun `a ramp is its mean in the fourth-power sense above its plain mean`() {
        val factor = assertNotNull(workout(RampStep(duration = 600, from = 0.5, to = 1.0)).intensityFactor())
        assertTrue(factor > 0.75 && factor < 0.80, "got $factor")
    }

    @Test
    fun `intervals weigh more than their average and the sweet spot session reads 0_76`() {
        val factor = assertNotNull(PlanSamples.sweetSpot().intensityFactor())
        assertTrue(factor in 0.75..0.77, "got $factor")
        val sameWork = workout(SteadyStep(duration = 3600, target = 0.5 + (0.88 - 0.5) * 2 / 3))
        assertTrue(factor > assertNotNull(sameWork.intensityFactor()))
    }

    @Test
    fun `free steps prescribe nothing and are left out`() {
        val mixed = workout(SteadyStep(duration = 600, target = 0.8), FreeStep(duration = 1800))
        assertNear(0.8, mixed.intensityFactor())
    }

    @Test
    fun `the planned load is IF squared times hours times a hundred and an hour at FTP is 100`() {
        assertNear(100.0, workout(SteadyStep(duration = 3600, target = 1.0)).plannedLoad(), 0.01)
        assertNear(56.25, workout(SteadyStep(duration = 3600, target = 0.75)).plannedLoad(), 0.01)
        assertNear(25.0, workout(SteadyStep(duration = 1800, target = 0.7071067811865476)).plannedLoad(), 0.01)
        // Free steps add no time and no stress.
        val mixed = workout(SteadyStep(duration = 3600, target = 1.0), FreeStep(duration = 1800))
        assertNear(100.0, mixed.plannedLoad(), 0.01)
    }

    @Test
    fun `a workout with no power to normalise has no planned load`() {
        assertNull(workout(FreeStep(duration = 3600)).plannedLoad())
        assertNull(workout(SteadyStep(duration = 20, target = 1.0)).plannedLoad())
    }

    @Test
    fun `no hint when there is less than one window of prescribed power`() {
        assertNull(workout(FreeStep(duration = 3600)).intensityFactor())
        assertNull(workout(SteadyStep(duration = 29, target = 0.9)).intensityFactor())
        assertNear(0.9, workout(SteadyStep(duration = 30, target = 0.9)).intensityFactor())
    }

    // endregion
}
