package app.overdrive.core.ride

import app.overdrive.core.workout.Workout
import kotlinx.serialization.json.Json
import java.io.File

private val validFixtures = File(System.getProperty("overdrive.contracts.dir"), "fixtures/workouts/valid")

/** Every valid workout fixture of the shared contracts, by file name. */
internal fun validWorkoutFixtures(): Map<String, Workout> =
    validFixtures
        .listFiles { f -> f.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }
        .associate { it.name to Json.decodeFromString<Workout>(it.readText()) }

internal fun workoutFixture(name: String): Workout = checkNotNull(validWorkoutFixtures()[name]) { "no fixture $name" }
