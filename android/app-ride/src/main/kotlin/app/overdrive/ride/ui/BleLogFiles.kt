package app.overdrive.ride.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import app.overdrive.ble.log.BleLogStore
import app.overdrive.ble.log.BleLogSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What the rider can do with the raw Bluetooth logs (ADR 0010): hand them to another app, **and nothing else**. They hold
 * the rider's heart rate, so they leave the tablet only through this one action and are never uploaded. The Settings
 * screen only knows this interface, so tests stand in a fake and the Android pieces stay in [rememberBleLogFiles].
 */
interface BleLogActions {
    /** Offers every log file to other apps through the system share sheet. */
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

/** The share intent for the Bluetooth log files: one chooser over one `ACTION_SEND` or an `ACTION_SEND_MULTIPLE`. */
object BleLogShare {
    /** The type the files go by: they are text, and every mail, chat and cloud-drive app takes it. */
    const val MIME_TYPE = "text/plain"

    /**
     * The chooser for [files], which must be under the folder the provider serves (`ble-logs/`). Receivers may read these
     * files only, only for now, and see their names (`ble-20261118T180412Z-trainer.txt`): a date and a role, nothing else.
     * @throws IllegalArgumentException if there are no files, or one is outside the folders the provider serves.
     */
    fun chooser(
        context: Context,
        files: List<File>,
    ): Intent {
        require(files.isNotEmpty()) { "Nothing to share" }
        val uris = files.map { FileProvider.getUriForFile(context, RideShare.authority(context), it) }
        val send =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(
                    Intent.ACTION_SEND_MULTIPLE,
                ).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        send.apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, "Overdrive Bluetooth logs")
            // The grant below reaches the receiver through the chooser only when the URIs are also in the clip data.
            clipData =
                ClipData.newRawUri("Bluetooth logs", uris.first()).also { clip ->
                    for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
                }
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
                        val files = withContext(Dispatchers.IO) { store.files().filter { it.length() > 0 } }
                        message = shareOrExplain(context, files)
                    }
                }
            }
        }
    return BleLogFiles(actions, summary, message)
}

/** Opens the chooser for [files]; returns what to tell the rider when it could not, or null. */
internal fun shareOrExplain(
    context: Context,
    files: List<File>,
): String? {
    if (files.isEmpty()) return "There are no logs yet. Ride once with the trainer connected."
    return try {
        context.startActivity(BleLogShare.chooser(context, files))
        null
    } catch (_: ActivityNotFoundException) {
        "No app can take the files."
    } catch (_: IllegalArgumentException) {
        "The files could not be shared."
    }
}
