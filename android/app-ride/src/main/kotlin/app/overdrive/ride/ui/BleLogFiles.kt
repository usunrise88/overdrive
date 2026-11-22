package app.overdrive.ride.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.overdrive.ble.log.BleLogArchive
import app.overdrive.ble.log.BleLogArchiveResult
import app.overdrive.ble.log.BleLogStore
import app.overdrive.ble.log.BleLogSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the rider can do with the raw Bluetooth logs (ADR 0010): hand them to another app **as one zip**, and nothing else.
 * They hold the rider's heart rate, so they leave the tablet only through this one action and are never uploaded. The
 * Settings screen only knows this interface, so tests stand in a fake and the Android pieces stay in
 * [rememberBleLogFiles].
 */
interface BleLogActions {
    /** Packs the log files into one zip and offers it to other apps through the system share sheet. */
    fun share()

    companion object {
        /** Does nothing: previews, screenshots and tests that do not look at the logs. */
        val None: BleLogActions =
            object : BleLogActions {
                override fun share() = Unit
            }
    }
}

/** The Bluetooth logs as the Settings screen shows them: what is on disk, and what the last share said. */
@Immutable
class BleLogFiles(
    val actions: BleLogActions,
    val summary: BleLogSummary,
    /** What the last share could not do, in plain words; null when it went well or nothing was tried. */
    val message: String?,
) {
    companion object {
        /** No logs and no actions: previews, screenshots and tests that do not look at them. */
        val None = BleLogFiles(BleLogActions.None, BleLogSummary.None, null)
    }
}

/**
 * The share of the Bluetooth logs (ADR 0010): **one zip**, `overdrive-ble-logs-<yyyymmdd-hhmm>.zip`, made in the app's cache
 * folder from the log files (newest sessions first, capped, [BleLogArchive]) and handed over through the `FileProvider`
 * with a sentence that says what is in it and that the logs hold heart-rate values.
 */
object BleLogShare {
    /** The type the archive goes by. */
    const val MIME_TYPE = "application/zip"

    /** Under the cache folder, and the only thing of it the provider serves (`file_paths.xml`). */
    const val ARCHIVE_FOLDER = "ble-log-archives"

    /** Said in the share text, always. */
    const val PRIVACY =
        "These logs hold heart-rate values. They are never uploaded; they leave the tablet only when you send them."

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

    /** The archive's name for [now]: `overdrive-ble-logs-20261121-0745.zip`. */
    fun archiveName(now: LocalDateTime): String = "overdrive-ble-logs-${STAMP.format(now)}.zip"

    /** Where archives are made: a folder of the cache, which Android may also clear by itself. */
    fun archiveFolder(context: Context): File = File(context.cacheDir, ARCHIVE_FOLDER)

    /** Deletes every archive left by an earlier share: at the next launch and before a new one is made. */
    fun clearArchives(context: Context) {
        archiveFolder(context).listFiles()?.forEach { it.delete() }
    }

    /** What the share message says: how many sessions went, how many were left out or unreadable, and the privacy sentence. */
    fun shareText(result: BleLogArchiveResult): String =
        buildString {
            append("Overdrive Bluetooth logs: ${count(result.included, "session")}")
            if (result.leftOut > 0) {
                append(". ${count(result.leftOut, "older session")} left out to keep the file small")
            }
            if (result.skipped > 0) append(". ${count(result.skipped, "file")} could not be read")
            append(".\n")
            append(PRIVACY)
        }

    private fun count(
        n: Int,
        noun: String,
    ) = "$n $noun${if (n == 1) "" else "s"}"

    /**
     * The chooser for [archive], which must be under the cache folder the provider serves. Receivers may read this file
     * only, only for now, and see its name: `overdrive-ble-logs-<date>-<time>.zip`.
     * @throws IllegalArgumentException if [archive] is outside the folders the provider serves.
     */
    fun chooser(
        context: Context,
        archive: File,
        text: String,
    ): Intent {
        val uri = FileProvider.getUriForFile(context, RideShare.authority(context), archive)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Overdrive Bluetooth logs")
                putExtra(Intent.EXTRA_TEXT, text)
                // The grant below reaches the receiver through the chooser only when the URI is also in the clip data.
                clipData = ClipData.newRawUri("Bluetooth logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share Bluetooth logs")
    }
}

/** What is on disk, as the Settings row says it: "4 files · 120 KB", or "No logs yet". */
fun BleLogSummary.describe(): String =
    when {
        files == 0 -> "No logs yet"
        else -> "$files ${if (files == 1) "file" else "files"} · ${sizeText(bytes)}"
    }

private fun sizeText(bytes: Long): String =
    when {
        bytes < KILOBYTE -> "$bytes B"
        bytes < MEGABYTE -> "${bytes / KILOBYTE} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / MEGABYTE)
    }

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L

/**
 * The share action of the Settings screen, wired to Android: reads the folder (off the main thread) for the summary and
 * on **Share** opens the system chooser through the app's `FileProvider`. A failure becomes a plain message, never an
 * exception. Nothing here sends a byte anywhere by itself.
 */
@Composable
fun rememberBleLogFiles(store: BleLogStore): BleLogFiles {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember(store) { mutableStateOf(BleLogSummary.None) }
    var message by remember(store) { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { summary = withContext(Dispatchers.IO) { store.summary() } }
    val actions =
        remember(store, context) {
            object : BleLogActions {
                override fun share() {
                    message = null
                    scope.launch {
                        // Reading and compressing the files is off the main thread; the chooser opens on it.
                        val prepared =
                            withContext(
                                Dispatchers.IO,
                            ) { prepareShare(context, store.files().filter { it.length() > 0 }) }
                        message = open(context, prepared)
                    }
                }
            }
        }
    return BleLogFiles(actions, summary, message)
}

/** What [prepareShare] made: a chooser to open, or the plain words for why there is none. */
internal sealed interface PreparedShare {
    class Ready(
        val chooser: Intent,
    ) : PreparedShare

    class Nothing(
        val message: String,
    ) : PreparedShare
}

/**
 * Packs [files] into the one zip (off the main thread) and makes its chooser. Older archives are deleted first. No files,
 * or none that can be read, share nothing and say so.
 */
internal fun prepareShare(
    context: Context,
    files: List<File>,
    now: LocalDateTime = LocalDateTime.now(),
): PreparedShare {
    if (files.isEmpty()) return PreparedShare.Nothing("There are no logs yet. Ride once with the trainer connected.")
    BleLogShare.clearArchives(context)
    val archive = File(BleLogShare.archiveFolder(context), BleLogShare.archiveName(now))
    val result =
        try {
            BleLogArchive.build(files, archive)
        } catch (_: IOException) {
            archive.delete()
            return PreparedShare.Nothing("The logs could not be packed into one file.")
        }
    if (result.included == 0) {
        archive.delete()
        return PreparedShare.Nothing("The log files could not be read.")
    }
    return try {
        PreparedShare.Ready(BleLogShare.chooser(context, archive, BleLogShare.shareText(result)))
    } catch (_: IllegalArgumentException) {
        archive.delete()
        PreparedShare.Nothing("The files could not be shared.")
    }
}

/** Opens a prepared chooser; returns what to tell the rider when it could not, or null. */
internal fun open(
    context: Context,
    prepared: PreparedShare,
): String? =
    when (prepared) {
        is PreparedShare.Nothing -> {
            prepared.message
        }

        is PreparedShare.Ready -> {
            try {
                context.startActivity(prepared.chooser)
                null
            } catch (_: ActivityNotFoundException) {
                "No app can take the file."
            }
        }
    }

/** [prepareShare] and [open] in one step, for tests: the zip, then the chooser. Returns what to tell the rider, or null. */
internal fun shareOrExplain(
    context: Context,
    files: List<File>,
    now: LocalDateTime = LocalDateTime.now(),
): String? = open(context, prepareShare(context, files, now))
