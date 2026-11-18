package app.overdrive.ride.workouts

import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.core.workout.WorkoutParseException

/** Why a workout file could not be used, in words for the rider (design/system.md §11: what happened, what to do). */
class WorkoutFileError(
    val title: String,
    val text: String,
)

/** The result of reading a workout file. */
sealed interface WorkoutFileResult {
    data class Loaded(
        val workout: Workout,
    ) : WorkoutFileResult

    data class Failed(
        val error: WorkoutFileError,
    ) : WorkoutFileResult
}

/**
 * Turns the text of a picked file into a workout: native JSON or a Zwift ZWO file, told apart by their first
 * character (a ZWO file is XML). Core does the parsing and the validation; this only chooses the reader and phrases a
 * failure. Pure, so it is tested without Android.
 */
object WorkoutFiles {
    /** Larger files are not workouts; a picker can hand over anything. */
    const val MAX_BYTES: Int = 1_000_000

    fun parse(
        text: String,
        fileName: String? = null,
    ): WorkoutFileResult {
        val body = text.removePrefix("﻿").trimStart()
        if (body.isEmpty()) return failed(fileName, "The file is empty. Pick a workout file in the native JSON format or a Zwift .zwo file.")
        return try {
            val workout = if (body.startsWith("<")) WorkoutImport.fromZwo(body) else WorkoutImport.fromJson(body)
            WorkoutFileResult.Loaded(workout)
        } catch (e: WorkoutParseException) {
            failed(fileName, "${firstLine(e.message)} Fix the file or pick another one.")
        }
    }

    /** A file the app could not even read: too large, or the picker's stream failed. */
    fun unreadable(
        fileName: String?,
        reason: String,
    ): WorkoutFileResult = failed(fileName, reason)

    private fun failed(
        fileName: String?,
        text: String,
    ): WorkoutFileResult =
        WorkoutFileResult.Failed(WorkoutFileError(if (fileName.isNullOrBlank()) "That file is not a workout" else "$fileName is not a workout", text))

    /** The first line of [message], shortened, with a closing full stop. */
    private fun firstLine(message: String?): String {
        val line = (message ?: "The file could not be read.").lineSequence().first().trim()
        val shortened = if (line.length > MAX_MESSAGE) line.take(MAX_MESSAGE).trimEnd() + "…" else line
        return if (shortened.endsWith(".") || shortened.endsWith("…")) shortened else "$shortened."
    }

    private const val MAX_MESSAGE = 160
}
