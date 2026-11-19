package app.overdrive.core.workout

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model must agree with `contracts/schema/workout.schema.json`
 * on every shared fixture. The TypeScript side runs the same fixtures against the schema itself.
 */
class WorkoutContractTest {
    private val json = Json // defaults: unknown keys are rejected, like `additionalProperties: false`
    private val fixtures = File(System.getProperty("overdrive.contracts.dir"), "fixtures/workouts")

    private fun fixtures(kind: String): List<File> =
        File(fixtures, kind).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun `finds fixtures`() {
        assertTrue(fixtures("valid").isNotEmpty(), "no valid fixtures under $fixtures")
        assertTrue(fixtures("invalid").isNotEmpty(), "no invalid fixtures under $fixtures")
    }

    @Test
    fun `decodes every valid fixture and round-trips it`() {
        for (file in fixtures("valid")) {
            val workout = json.decodeFromString<Workout>(file.readText())
            assertEquals(workout, json.decodeFromString<Workout>(json.encodeToString(workout)), file.name)
        }
    }

    @Test
    fun `rejects every invalid fixture`() {
        for (file in fixtures("invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) {
                json.decodeFromString<Workout>(file.readText())
            }
        }
    }

    @Test
    fun `sweet spot fixture has the expected shape`() {
        val workout = json.decodeFromString<Workout>(File(fixtures, "valid/sweet-spot-3x10.json").readText())
        assertEquals(listOf("ramp", "repeat", "free", "ramp"), workout.steps.map(::kind))
        val repeat = assertIs<RepeatStep>(workout.steps[1])
        assertEquals(3, repeat.count)
        assertEquals(SteadyStep(duration = 600, target = 0.88, label = "Sweet spot"), repeat.steps[0])
    }

    private fun kind(step: Step) =
        when (step) {
            is SteadyStep -> "steady"
            is RampStep -> "ramp"
            is FreeStep -> "free"
            is HeartRateStep -> "hr"
            is RepeatStep -> "repeat"
        }

    @Test
    fun `heart-rate fixtures have the expected shape`() {
        val hold = json.decodeFromString<Workout>(File(fixtures, "valid/heart-rate-hold.json").readText())
        assertEquals(
            HeartRateStep(
                duration = 2400,
                targetBpm = 135,
                band = 4,
                powerMin = 80,
                powerMax = 220,
                label = "Zone 2 by heart rate",
            ),
            hold.steps[0],
        )
        val fraction =
            json.decodeFromString<Workout>(File(fixtures, "valid/heart-rate-threshold-fraction.json").readText())
        val repeat = assertIs<RepeatStep>(fraction.steps[1])
        val step = assertIs<HeartRateStep>(repeat.steps[0])
        assertEquals(0.88, step.targetLthr)
        assertEquals(null, step.targetBpm)
        assertEquals(HeartRateStep.DEFAULT_BAND_BPM, step.band, "the band is 5 when the file does not say")
        assertEquals(90.0, step.cadence)
    }
}
