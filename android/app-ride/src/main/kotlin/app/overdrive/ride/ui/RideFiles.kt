package app.overdrive.ride.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.overdrive.data.rides.RideFileNames
import app.overdrive.data.rides.StoredRide
import app.overdrive.ride.rides.RideLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the rider can do with a stored ride's FIT file (spec §4.8: "can be shared or saved to any folder"). The screens
 * only know this interface, so tests stand in a fake and the Android pieces (a chooser, the system file picker) stay in
 * [rememberRideFiles].
 */
interface RideFileActions {
    /** Offers the FIT file to other apps through the system share sheet. */
    fun share(ride: StoredRide)

    /** Asks where to put a copy of the FIT file (the system file picker) and writes it there. */
    fun saveToFolder(ride: StoredRide)

    companion object {
        /** Does nothing: previews, screenshots and tests that do not look at the files. */
        val None: RideFileActions =
            object : RideFileActions {
                override fun share(ride: StoredRide) = Unit

                override fun saveToFolder(ride: StoredRide) = Unit
            }
    }
}

/** What happened to the last share or save of a ride's file, in plain words, for the screen that shows that ride. */
@Immutable
data class FileMessage(
    val rideId: String,
    val text: String,
)

/** The file actions of the screens and the message the last one left. */
@Immutable
class RideFiles(
    val actions: RideFileActions,
    val message: FileMessage?,
)

/** The MIME type FIT files go by when shared or saved: there is no widely registered one, and a generic one reaches every app. */
const val FIT_MIME_TYPE = "application/octet-stream"

/** The share intent for a ride's FIT file (ADR 0008): a chooser over an `ACTION_SEND` with a read grant for one `content://` URI. */
object RideShare {
    /** The authority of the manifest's `FileProvider`. */
    fun authority(context: Context): String = "${context.packageName}.files"

    /**
     * The chooser for [file], which must be under the rides folder the provider serves. Receivers see [displayName]
     * (`2026-11-18 18-04 Sweet spot 3×10.fit`), not the ride's id, and may read only this one file, only for now.
     * @throws IllegalArgumentException if [file] is outside the folders the provider serves.
     */
    fun chooser(
        context: Context,
        file: File,
        displayName: String,
    ): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file, displayName)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = FIT_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, displayName)
                // The grant below reaches the receiver through the chooser only when the URI is also in the clip data.
                clipData = ClipData.newRawUri(displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share FIT file")
    }
}

/**
 * The share and save actions of the screens, wired to Android: **Share** opens the system chooser through the app's
 * `FileProvider`, **Save to folder** opens the Storage Access Framework's "create document" screen, suggested name
 * `YYYY-MM-DD HH-mm <title>.fit`, and copies the file to the place the rider picks. Neither needs a permission.
 * Failures become a [FileMessage] (plain words, never an exception).
 */
@Composable
fun rememberRideFiles(library: RideLibrary): RideFiles {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<FileMessage?>(null) }
    // The picker can outlive the activity (a rotation while it is open), so the ride it was opened for is saved state.
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FIT_MIME_TYPE)) { uri ->
            val id = pendingId
            pendingId = null
            if (uri != null && id != null) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { copyFit(context, library, id, uri) }
                    message = FileMessage(id, if (ok) SAVED_TO_FOLDER else NOT_SAVED_TO_FOLDER)
                }
            }
        }
    val actions =
        remember(library, context) {
            object : RideFileActions {
                override fun share(ride: StoredRide) {
                    val file = library.fitFile(ride)
                    message = null
                    if (!file.isFile) {
                        message = FileMessage(ride.id, "The FIT file is missing.")
                        return
                    }
                    try {
                        context.startActivity(RideShare.chooser(context, file, fileName(ride)))
                    } catch (e: ActivityNotFoundException) {
                        message = FileMessage(ride.id, "No app can take the file. Use Save to folder instead.")
                    } catch (e: IllegalArgumentException) {
                        message = FileMessage(ride.id, "The file could not be shared. Use Save to folder instead.")
                    }
                }

                override fun saveToFolder(ride: StoredRide) {
                    message = null
                    pendingId = ride.id
                    try {
                        picker.launch(fileName(ride))
                    } catch (e: ActivityNotFoundException) {
                        message = FileMessage(ride.id, "This tablet has no file picker. Use Share instead.")
                    }
                }
            }
        }
    return RideFiles(actions, message)
}

private const val SAVED_TO_FOLDER = "Saved to the folder you chose."
private const val NOT_SAVED_TO_FOLDER = "The file could not be saved. Try another folder."

/** The name a ride's FIT file leaves the app under. */
fun fileName(ride: StoredRide): String = RideFileNames.fit(ride.title, ride.startEpochMillis)

private fun copyFit(
    context: Context,
    library: RideLibrary,
    id: String,
    uri: Uri,
): Boolean {
    val source = library.fitFile(id)
    return try {
        val out = context.contentResolver.openOutputStream(uri, "w") ?: return false
        out.use { target -> source.inputStream().use { it.copyTo(target) } }
        true
    } catch (e: Exception) {
        false
    }
}
