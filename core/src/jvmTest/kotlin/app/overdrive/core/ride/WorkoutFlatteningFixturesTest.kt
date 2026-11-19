package app.overdrive.core.ride

import app.overdrive.core.workout.LeafStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Flattening against the shared fixtures in `contracts/fixtures/workouts/valid`. */
class WorkoutFlatteningFixturesTest {
    private fun Workout.expectedSeconds(): Int =
        steps.sumOf { step ->
            when (step) {
                is LeafStep -> step.duration
                is RepeatStep -> step.count * step.steps.sumOf { it.duration }
            }
        }

    @Test
    fun `there are fixtures to test`() {
        assertTrue(validWorkoutFixtures().size >= 3)
    }

    @Test
    fun `every valid fixture flattens to contiguous steps that add up to its duration`() {
        for ((name, workout) in validWorkoutFixtures()) {
            val flat = workout.flatSteps()
            assertTrue(flat.isNotEmpty(), name)
            assertEquals(0, flat.first().startSeconds, name)
            for ((previous, next) in flat.zipWithNext()) {
                assertEquals(previous.endSeconds, next.startSeconds, "$name: steps are back to back")
            }
            assertEquals(workout.expectedSeconds(), flat.totalDurationSeconds(), name)
            for (step in flat) {
                assertTrue(step.label.isNotEmpty(), name)
                // A heart-rate step (spec §11.2) is ERG without a fraction of FTP; one whose target is a fraction of the
                // threshold has no heart rate to hold without it and flattens to a free step.
                val noTarget = step.targetFrom == null && step.heartRate == null
                assertEquals(noTarget, step.mode == TrainerMode.SIMULATION, name)
            }
        }
    }

    @Test
    fun `sweet spot flattens to nine steps in order`() {
        val flat = workoutFixture("sweet-spot-3x10.json").flatSteps()

        val summary = flat.map { Triple(it.label, it.durationSeconds, it.mode) }
        assertEquals(
            listOf(
                Triple("Warm-up", 600, TrainerMode.ERG),
                Triple("Sweet spot", 600, TrainerMode.ERG),
                Triple("Recover", 300, TrainerMode.ERG),
                Triple("Sweet spot", 600, TrainerMode.ERG),
                Triple("Recover", 300, TrainerMode.ERG),
                Triple("Sweet spot", 600, TrainerMode.ERG),
                Triple("Recover", 300, TrainerMode.ERG),
                Triple("Free ride", 300, TrainerMode.SIMULATION),
                Triple("Cool-down", 300, TrainerMode.ERG),
            ),
            summary,
        )
        assertEquals(listOf(0, 600, 1200, 1500, 2100, 2400, 3000, 3300, 3600), flat.map { it.startSeconds })
        assertEquals(3900, flat.totalDurationSeconds())

        // Ramps keep from and to, steady steps have from = to, the free ride has neither.
        assertEquals(0.45 to 0.70, flat[0].targetFrom to flat[0].targetTo)
        assertEquals(0.88 to 0.88, flat[1].targetFrom to flat[1].targetTo)
        assertEquals(0.50 to 0.50, flat[2].targetFrom to flat[2].targetTo)
        assertEquals(null to null, flat[7].targetFrom to flat[7].targetTo)
        assertEquals(0.55 to 0.40, flat[8].targetFrom to flat[8].targetTo)

        assertEquals(listOf(null, 1, 1, 2, 2, 3, 3, null, null), flat.map { it.repeatIteration })
        assertEquals(listOf(null, 3, 3, 3, 3, 3, 3, null, null), flat.map { it.repeatCount })
    }

    @Test
    fun `cadence fixture keeps labels, cadence targets and the repeat label`() {
        val flat = workoutFixture("cadence-and-labels.json").flatSteps()

        assertEquals(1 + 4 * 2 + 1, flat.size)
        assertEquals("Easy spin", flat.first().label)
        assertEquals(90.0, flat.first().cadence)
        assertEquals("Sprint", flat.last().label)
        assertEquals(1.5 to 1.5, flat.last().targetFrom to flat.last().targetTo)

        val builds = flat.subList(1, 9)
        assertTrue(builds.all { it.label == "Builds" }, "leaves without a label take the repeat's label")
        assertEquals(listOf(100.0, null, 100.0, null, 100.0, null, 100.0, null), builds.map { it.cadence })
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), builds.map { it.repeatIteration })
        assertEquals(0.60 to 0.95, builds[0].targetFrom to builds[0].targetTo)
        assertEquals(0.50 to 0.50, builds[1].targetFrom to builds[1].targetTo)
        assertEquals(300 + 4 * 240 + 20, flat.totalDurationSeconds())
    }

    @Test
    fun `free ride fixture is a single simulation step`() {
        val flat = workoutFixture("minimal-free-ride.json").flatSteps()
        assertEquals(
            listOf(FlatStep("Free ride", 3600, null, null, TrainerMode.SIMULATION)),
            flat,
        )
    }
}
