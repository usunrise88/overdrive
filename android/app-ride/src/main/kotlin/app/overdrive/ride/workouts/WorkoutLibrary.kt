package app.overdrive.ride.workouts

import android.content.res.AssetManager
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The workouts the rider can pick: the ones bundled in the app and the ones opened from files during this run.
 * Opened workouts are kept in memory only; persisting them belongs to the data layer (M2b).
 *
 * "Free ride" is not a workout and is not in the list.
 */
class WorkoutLibrary(
    bundled: List<Workout>,
) {
    private val mutable = MutableStateFlow(bundled)

    /** The bundled workouts first, then the opened ones in the order they were added. */
    val workouts: StateFlow<List<Workout>> = mutable.asStateFlow()

    /** Adds [workout], replacing one with the same id (opening the same file again). */
    fun add(workout: Workout) {
        mutable.update { list ->
            val index = list.indexOfFirst { it.id == workout.id }
            if (index >= 0) list.toMutableList().also { it[index] = workout } else list + workout
        }
    }

    companion object {
        /** The files in `assets/workouts`, in this order. A bundled file that does not parse is a build error, not a runtime one. */
        val BUNDLED_FILES: List<String> = listOf("sweet-spot-3x10.json", "test-10-minutes.json", "all-elements.zwo")

        fun fromAssets(assets: AssetManager): WorkoutLibrary =
            WorkoutLibrary(
                BUNDLED_FILES.map { name ->
                    val text = assets.open("workouts/$name").bufferedReader().use { it.readText() }
                    when (val result = WorkoutFiles.parse(text, name)) {
                        is WorkoutFileResult.Loaded -> result.workout
                        is WorkoutFileResult.Failed -> error("Bundled workout $name is invalid: ${result.error.text}")
                    }
                },
            )
    }
}
