package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.outdoor.ACTIVITY_FIT_MAX_BYTES
import app.overdrive.core.outdoor.ActivityImportReason
import app.overdrive.core.outdoor.ActivityImportSummary
import app.overdrive.core.outdoor.FitImporter
import app.overdrive.core.outdoor.FitOutcome
import app.overdrive.core.outdoor.FitRefusal
import app.overdrive.core.outdoor.FitStatus
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.uikit.UiFormat
import kotlin.math.roundToInt

/** What the import screen has to show (design/companion.md §4.11): nothing shared, a file being read and sent, or what came of it. */
sealed interface FitShown {
    /** The screen was opened with nothing to import. */
    data object Idle : FitShown

    /** The file is being read from the app that shared it and sent. */
    data object Importing : FitShown

    /** The app that shared the file would not give its bytes. */
    data object Unreadable : FitShown

    data class Done(
        val outcome: FitOutcome,
    ) : FitShown
}

/** How the import screen's block is drawn: a spinner, a check, or an alert. */
enum class ImportMark { Working, Done, Alert }

/** A button of the import screen. */
enum class ImportAction(
    val label: String,
) {
    /** Opens the app on its Settings screen (the token, the address). */
    Settings("Settings"),

    /** Opens the app on its Rides tab. */
    Rides("Open Overdrive"),
}

/** The import screen in words (design/companion.md §4.11). */
@Immutable
data class FitImportUi(
    val mark: ImportMark,
    /** "Importing…", "Imported", "Already imported", "Saved for later", "Could not import". */
    val heading: String,
    /** The sentence under it, or null. */
    val text: String?,
    /** A second line in label text 3: the ride's facts under "Already imported", the technical hint. */
    val detail: String?,
    /** A button besides **Done**, or null. */
    val action: ImportAction?,
)

/**
 * Every word about an outdoor ride from a FIT file that is not in a chart (spec §11.8, design/companion.md §4.11, §7.5): the import
 * screen for each thing that can come of a shared file, and Settings' row for the files that wait. Pure, so every sentence is a unit
 * test. Nothing here takes a measured value from anywhere but the service's own summary of the ride the rider just shared.
 */
object FitWords {
    private const val MEGABYTES = ACTIVITY_FIT_MAX_BYTES / (1024 * 1024)
    private const val SAME_START = "A ride with the same start and length is already in your history."
    private const val SAVED = "The file is saved on this phone and goes to your service when it can be reached."

    fun importScreen(
        shown: FitShown,
        words: Words,
        host: String,
    ): FitImportUi =
        when (shown) {
            FitShown.Idle -> {
                FitImportUi(
                    ImportMark.Alert,
                    "Nothing to import",
                    "Share a .fit file from OnelapFit to Overdrive.",
                    null,
                    null,
                )
            }

            FitShown.Importing -> {
                FitImportUi(ImportMark.Working, "Importing…", null, null, null)
            }

            FitShown.Unreadable -> {
                FitImportUi(
                    ImportMark.Alert,
                    "Could not read the file",
                    "The app that shared it did not let Overdrive read it. Share it again, or save it and share it from Files.",
                    null,
                    null,
                )
            }

            is FitShown.Done -> {
                outcome(shown.outcome, words, host)
            }
        }

    private fun outcome(
        outcome: FitOutcome,
        words: Words,
        host: String,
    ): FitImportUi =
        when (outcome) {
            is FitOutcome.Imported -> {
                FitImportUi(ImportMark.Done, "Imported", facts(outcome.summary, words) + ".", null, ImportAction.Rides)
            }

            is FitOutcome.Duplicate -> {
                FitImportUi(
                    ImportMark.Done,
                    "Already imported",
                    duplicateReason(outcome.reason),
                    facts(outcome.summary, words),
                    ImportAction.Rides,
                )
            }

            is FitOutcome.Refused -> {
                FitImportUi(ImportMark.Alert, "Could not import", refusal(outcome.refusal, outcome.status), null, null)
            }

            is FitOutcome.Waiting -> {
                waiting(outcome.problem, host)
            }

            FitOutcome.NotKept -> {
                FitImportUi(
                    ImportMark.Alert,
                    "Could not save the file",
                    "The phone could not keep the file (its storage may be full). Nothing was sent.",
                    null,
                    null,
                )
            }
        }

    /** "Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm, load 74": what the service read from the file. */
    fun facts(
        summary: ActivityImportSummary,
        words: Words,
    ): String =
        listOfNotNull(
            words.day(summary.startDate),
            words.duration(summary.durationSeconds),
            summary.distanceMeters?.let { "${UiFormat.decimal(it / METERS_PER_KM, 1)} km" },
            summary.averageHeartRate?.let { "average heart rate ${it.roundToInt()} bpm" },
            summary.load?.let { "load ${it.roundToInt()}" + if (summary.loadApproximate) " (approximate)" else "" },
        ).joinToString(", ")

    fun duplicateReason(reason: ActivityImportReason): String =
        when (reason) {
            ActivityImportReason.SAME_FILE -> "You shared this file before, so it counts once."
            ActivityImportReason.SAME_START_AND_DURATION -> SAME_START
            ActivityImportReason.OVERLAP -> "It overlaps a ride already in your history, so it counts once."
        }

    private fun waiting(
        problem: ServiceProblem?,
        host: String,
    ): FitImportUi {
        if (problem == null) {
            return FitImportUi(
                ImportMark.Alert,
                "Saved for later",
                "Overdrive is not connected to your service yet. Connect it in the app; the file is saved and goes then.",
                null,
                ImportAction.Settings,
            )
        }
        val refused = problem.kind == ProblemKind.TOKEN_REJECTED
        return FitImportUi(
            ImportMark.Alert,
            if (refused) "Saved, not sent" else "Saved for later",
            "${cause(problem, host)} $SAVED",
            ProblemWords.hintOf(problem)?.let { "($it)" },
            if (problem.needsSettings) ImportAction.Settings else null,
        )
    }

    /** The cause of a failed send, in the design's sentences (design/companion.md §7.5): the share screen has no "pull down". */
    fun cause(
        problem: ServiceProblem,
        host: String,
    ): String = if (problem.isOffline) "There is no network connection." else ProblemWords.causeOf(problem, host)

    /** What is wrong with a file, and what to do. */
    fun refusal(
        refusal: FitRefusal,
        status: Int? = null,
    ): String =
        when (refusal) {
            FitRefusal.EMPTY_FILE -> {
                "The file is empty."
            }

            FitRefusal.NOT_A_FIT_FILE -> {
                "This is not a valid FIT file. In OnelapFit, export the ride as a FIT file and share that."
            }

            FitRefusal.ARCHIVE -> {
                "This is a zip archive, not a FIT file. Open it and share the .fit file inside."
            }

            FitRefusal.TOO_LARGE -> {
                "The file is larger than $MEGABYTES MB, which is more than your service takes."
            }

            FitRefusal.NOT_CYCLING -> {
                "This FIT file is not a cycling ride, so it was not imported."
            }

            FitRefusal.NO_RIDE -> {
                "This FIT file has no ride in it."
            }

            FitRefusal.WRONG_TYPE -> {
                "The service did not accept this kind of file. Update the service and this app, then share it again."
            }

            FitRefusal.QUEUE_FULL -> {
                "${FitImporter.MAX_WAITING} files are already waiting for your service. Connect to it, then share this one again."
            }

            FitRefusal.DAMAGED -> {
                "The saved copy of this file was damaged, so it was set aside. Share the file again."
            }

            FitRefusal.SERVICE_REFUSED -> {
                "The service refused the file${status?.let { " ($it)" } ?: ""}."
            }
        }

    /** Why a refused file was not imported, in a few words for Settings. */
    private fun short(refusal: FitRefusal): String =
        when (refusal) {
            FitRefusal.EMPTY_FILE -> "it was empty"
            FitRefusal.NOT_A_FIT_FILE -> "not a valid FIT file"
            FitRefusal.ARCHIVE -> "a zip archive"
            FitRefusal.TOO_LARGE -> "too large"
            FitRefusal.NOT_CYCLING -> "not a cycling ride"
            FitRefusal.NO_RIDE -> "no ride in it"
            FitRefusal.WRONG_TYPE -> "the service did not accept the file type"
            FitRefusal.QUEUE_FULL -> "too many were waiting"
            FitRefusal.DAMAGED -> "damaged on the phone"
            FitRefusal.SERVICE_REFUSED -> "the service refused it"
        }

    // region Settings

    /** "2 files waiting", "No files waiting": the value of Settings' row. */
    fun settingsValue(status: FitStatus): String =
        when (status.waiting) {
            0 -> "No files waiting"
            1 -> "1 file waiting"
            else -> "${status.waiting} files waiting"
        }

    /**
     * The note under the row: what to do when nothing waits; why the files wait and that they go by themselves when something does; and
     * what the service refused.
     */
    fun settingsNote(
        status: FitStatus,
        host: String,
    ): String {
        val problem = status.problem
        val waiting =
            when {
                status.waiting == 0 -> null
                problem != null -> "${cause(problem, host)} ${status.waitingGoes()}"
                else -> status.waitingGoes()
            }
        val refused =
            status.parked.takeIf { it.isNotEmpty() }?.let { parked ->
                val files = if (parked.size == 1) "1 file was" else "${parked.size} files were"
                "$files not imported: ${parked.distinct().joinToString("; ") { short(it) }}."
            }
        if (waiting == null &&
            refused == null
        ) {
            return "Share a .fit file from OnelapFit to Overdrive to add an outdoor ride."
        }
        return listOfNotNull(waiting, refused).joinToString(" ")
    }

    private fun FitStatus.waitingGoes(): String =
        if (waiting ==
            1
        ) {
            "It goes to your service when it can be reached."
        } else {
            "They go to your service when it can be reached."
        }

    private const val METERS_PER_KM = 1000.0
}
