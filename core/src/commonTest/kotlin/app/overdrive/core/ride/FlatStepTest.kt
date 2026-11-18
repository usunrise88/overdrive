package app.overdrive.core.ride

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlatStepTest {
    private fun assertClose(
        expected: Double,
        actual: Double?,
    ) = assertEquals(expected, checkNotNull(actual), 1e-12)

    private fun workout(vararg steps: app.overdrive.core.workout.Step) =
        Workout(schema = 1, id = "w", name = "W", steps = steps.toList())

    @Test
    fun `leaf steps keep their targets and get default labels`() {
        val flat =
            workout(
                SteadyStep(duration = 100, target = 0.6),
                RampStep(duration = 200, from = 0.5, to = 0.9, label = "Build"),
                FreeStep(duration = 300),
            ).flatSteps()

        assertEquals(
            listOf(
                FlatStep("Steady", 100, 0.6, 0.6, TrainerMode.ERG, startSeconds = 0),
                FlatStep("Build", 200, 0.5, 0.9, TrainerMode.ERG, startSeconds = 100),
                FlatStep("Free ride", 300, null, null, TrainerMode.SIMULATION, startSeconds = 300),
            ),
            flat,
        )
        assertEquals(600, flat.totalDurationSeconds())
    }

    @Test
    fun `repeat sets are expanded in order with iteration numbers`() {
        val flat =
            workout(
                RepeatStep(
                    count = 3,
                    steps =
                        listOf(
                            SteadyStep(duration = 10, target = 0.9, label = "On"),
                            SteadyStep(duration = 5, target = 0.5, label = "Off"),
                        ),
                ),
            ).flatSteps()

        assertEquals(listOf("On", "Off", "On", "Off", "On", "Off"), flat.map { it.label })
        assertEquals(listOf(0, 10, 15, 25, 30, 40), flat.map { it.startSeconds })
        assertEquals(listOf(1, 1, 2, 2, 3, 3), flat.map { it.repeatIteration })
        assertEquals(List(6) { 3 }, flat.map { it.repeatCount })
        assertEquals(45, flat.totalDurationSeconds())
    }

    @Test
    fun `a leaf without a label takes the label of its repeat`() {
        val flat =
            workout(
                RepeatStep(
                    count = 2,
                    label = "Builds",
                    steps = listOf(SteadyStep(duration = 10, target = 0.9), FreeStep(duration = 5, label = "Easy")),
                ),
            ).flatSteps()

        assertEquals(listOf("Builds", "Easy", "Builds", "Easy"), flat.map { it.label })
    }

    @Test
    fun `steps outside repeats have no iteration`() {
        val flat = workout(SteadyStep(duration = 10, target = 0.5)).flatSteps()
        assertNull(flat.single().repeatIteration)
        assertNull(flat.single().repeatCount)
    }

    @Test
    fun `cadence targets are carried over`() {
        val flat = workout(SteadyStep(duration = 10, target = 0.5, cadence = 95.0), FreeStep(duration = 10)).flatSteps()
        assertEquals(listOf(95.0, null), flat.map { it.cadence })
    }

    @Test
    fun `steady and ramp steps run in ERG and free steps in simulation`() {
        val modes =
            workout(
                SteadyStep(duration = 10, target = 0.5),
                RampStep(duration = 10, from = 0.5, to = 0.6),
                FreeStep(duration = 10),
            ).flatSteps()
                .map { it.mode }
        assertEquals(listOf(TrainerMode.ERG, TrainerMode.ERG, TrainerMode.SIMULATION), modes)
    }

    @Test
    fun `an empty list has no duration`() {
        assertEquals(0, emptyList<FlatStep>().totalDurationSeconds())
    }

    @Test
    fun `a steady step holds its target`() {
        val step = FlatStep("S", 60, 0.8, 0.8, TrainerMode.ERG)
        assertEquals(0.8, step.targetFractionAt(0))
        assertEquals(0.8, step.targetFractionAt(59))
    }

    @Test
    fun `a ramp is linear from its start target towards its end target`() {
        val step = FlatStep("R", 100, 0.4, 0.8, TrainerMode.ERG)
        assertEquals(0.4, step.targetFractionAt(0))
        assertClose(0.6, step.targetFractionAt(50))
        assertClose(0.796, step.targetFractionAt(99))
        assertClose(0.8, step.targetFractionAt(100))
        assertClose(0.4, step.targetFractionAt(-3))
        assertClose(0.8, step.targetFractionAt(500))
    }

    @Test
    fun `a descending ramp works the same way`() {
        val step = FlatStep("Cool-down", 300, 0.55, 0.40, TrainerMode.ERG)
        assertClose(0.475, step.targetFractionAt(150))
    }

    @Test
    fun `a free step has no target`() {
        assertNull(FlatStep("F", 60, null, null, TrainerMode.SIMULATION).targetFractionAt(10))
    }

    @Test
    fun `end seconds add the duration to the start`() {
        assertEquals(160, FlatStep("S", 60, 0.8, 0.8, TrainerMode.ERG, startSeconds = 100).endSeconds)
    }

    @Test
    fun `invalid steps are rejected`() {
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 0, 0.8, 0.8, TrainerMode.ERG) }
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 10, 0.8, 0.8, TrainerMode.ERG, startSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 10, 0.8, null, TrainerMode.ERG) }
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 10, null, null, TrainerMode.ERG) }
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 10, 0.8, 0.8, TrainerMode.SIMULATION) }
        assertFailsWith<IllegalArgumentException> { FlatStep("S", 10, 0.8, 0.8, TrainerMode.ERG, repeatIteration = 1) }
        assertFailsWith<IllegalArgumentException> {
            FlatStep("S", 10, 0.8, 0.8, TrainerMode.ERG, repeatIteration = 4, repeatCount = 3)
        }
    }
}
