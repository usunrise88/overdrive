package app.overdrive.core.workout

import app.overdrive.core.ride.flatSteps
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden files for the ZWO import: every ZWO file in the `valid` folder must convert to the native workout
 * (schema 1) in the `.expected.json` file beside it, and every ZWO file in the `invalid` folder must be
 * rejected. Any other implementation of the import (the web service converts ZWO files too, spec §8.1) is
 * held to the same files.
 */
class ZwoFixturesTest {
    private val root = File(System.getProperty("overdrive.contracts.dir"), "fixtures/zwo")

    private fun files(
        kind: String,
        extension: String,
    ) = File(root, kind).listFiles { f -> f.extension == extension }.orEmpty().sortedBy { it.name }

    @Test
    fun `finds fixtures`() {
        assertTrue(files("valid", "zwo").size >= 3, "no valid fixtures under $root")
        assertTrue(files("invalid", "zwo").size >= 5, "no invalid fixtures under $root")
    }

    @Test
    fun `every valid fixture converts to its expected native workout`() {
        for (file in files("valid", "zwo")) {
            val expectedFile = File(file.parentFile, file.nameWithoutExtension + ".expected.json")
            val expected = Json.decodeFromString<Workout>(expectedFile.readText())
            assertEquals(expected, WorkoutImport.fromZwo(file.readText()), file.name)
            assertEquals(expected, WorkoutImport.parse(file.readText()), "${file.name} via parse")
        }
    }

    @Test
    fun `every invalid fixture is rejected`() {
        for (file in files("invalid", "zwo")) {
            assertFailsWith<WorkoutParseException>(file.name) { WorkoutImport.fromZwo(file.readText()) }
        }
    }

    @Test
    fun `the zwo sweet spot plays exactly like the native one`() {
        val zwo = WorkoutImport.fromZwo(File(root, "valid/sweet-spot-3x10.zwo").readText())
        val native =
            Json.decodeFromString<Workout>(
                File(
                    System.getProperty("overdrive.contracts.dir"),
                    "fixtures/workouts/valid/sweet-spot-3x10.json",
                ).readText(),
            )

        fun shape(workout: Workout) =
            workout.flatSteps().map { listOf(it.startSeconds, it.durationSeconds, it.targetFrom, it.targetTo, it.mode) }
        assertEquals(shape(native), shape(zwo))
    }
}
