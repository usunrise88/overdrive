package app.overdrive.companion.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import app.overdrive.core.outdoor.ACTIVITY_FIT_MAX_BYTES
import app.overdrive.core.outdoor.FitFileCheck
import app.overdrive.core.outdoor.FitRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/*
 * The FIT file another app hands to the companion (spec §11.8 path A, ADR 0028): an `ACTION_SEND` from the share sheet of OnelapFit or an
 * `ACTION_VIEW` of a file in a file manager. The bytes are read through the [ContentResolver] into memory, at most 16 MiB, and go from
 * there into the app's private outbox: nothing is copied to a shared or world-readable place, and the name the other app gave the file is
 * used for one check and kept nowhere.
 */

/** What another app handed over: where the bytes are and what the intent says they are. */
data class SharedFile(
    val uri: Uri,
    val mimeType: String?,
)

/** What reading a [SharedFile] came to. */
sealed interface SharedRead {
    /** The whole file, at most the size the service takes. */
    class Bytes(
        val bytes: ByteArray,
    ) : SharedRead

    /** The file is certainly not one the service can use: its name and type say it is not FIT, or it is too large. */
    data class Refused(
        val refusal: FitRefusal,
    ) : SharedRead

    /** The other app would not give the bytes (a link that is gone, a grant that ended, a scheme that is not `content`). */
    data object Unreadable : SharedRead
}

/** Reads a shared file; the real one asks the [ContentResolver], a test passes its own. */
interface SharedFileReader {
    suspend fun read(file: SharedFile): SharedRead
}

/**
 * [SharedFileReader] over the [ContentResolver].
 *
 * - **Only `content://`.** A `file://` or any other scheme is not read: a file that another app names by path is not a file that app
 *   shares (and it could be one of this app's own private files).
 * - **The name decides for the generic types.** An intent that says `application/vnd.ant.fit` or `application/x-fit` is a FIT file whatever
 *   its name; for `application/octet-stream` and every other type the display name must end in `.fit` ([FitFileCheck.looksLikeFit]).
 *   What passes is checked again by its header by the importer.
 * - **At most [maxBytes].** A size that the provider reports above the cap is refused without reading; otherwise the read stops one byte
 *   past the cap, so a provider that lies about its size costs no more memory than the cap.
 */
class ContentResolverReader(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = ACTIVITY_FIT_MAX_BYTES,
) : SharedFileReader {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun read(file: SharedFile): SharedRead =
        withContext(dispatcher) {
            if (file.uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext SharedRead.Unreadable
            try {
                val (name, size) = describe(file.uri)
                val resolverType = typeOf(file.uri)
                if (!FitFileCheck.looksLikeFit(name, file.mimeType) && !FitFileCheck.looksLikeFit(name, resolverType)) {
                    return@withContext SharedRead.Refused(FitRefusal.NOT_A_FIT_FILE)
                }
                if (size != null && size > maxBytes) return@withContext SharedRead.Refused(FitRefusal.TOO_LARGE)
                val input = resolver.openInputStream(file.uri) ?: return@withContext SharedRead.Unreadable
                input.use { stream ->
                    val out =
                        ByteArrayOutputStream(minOf(size ?: DEFAULT_BUFFER, maxBytes.toLong()).toInt().coerceAtLeast(1))
                    val buffer = ByteArray(CHUNK)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) return@withContext SharedRead.Refused(FitRefusal.TOO_LARGE)
                        out.write(buffer, 0, count)
                    }
                    SharedRead.Bytes(out.toByteArray())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Another app's provider decides what it throws (a link that is gone, a grant that ended, a bug of its own): whatever it
                // is, the file cannot be read, and nothing of it is kept or said.
                SharedRead.Unreadable
            }
        }

    /** The display name and the size the provider reports, either of which may be missing. */
    private fun describe(uri: Uri): Pair<String?, Long?> {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameAt >= 0 && !cursor.isNull(nameAt)) cursor.getString(nameAt) else null
                val size = if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else null
                name to size
            } ?: (null to null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to null
        }
    }

    private fun typeOf(uri: Uri): String? =
        try {
            resolver.getType(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val CHUNK = 64 * 1024
        const val DEFAULT_BUFFER = 256L * 1024
    }
}

/** The intents the companion accepts a file from (design/companion.md §4.11). */
object ShareIntents {
    /**
     * The file an `ACTION_SEND` (`EXTRA_STREAM`, or the clip when a sender put it there) or an `ACTION_VIEW` (the data) carries; null
     * for any other intent, or one with no file in it.
     */
    fun sharedFileOf(intent: Intent?): SharedFile? {
        intent ?: return null
        val uri: Uri? =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                }

                Intent.ACTION_VIEW -> {
                    intent.data
                }

                else -> {
                    null
                }
            }
        return uri?.let { SharedFile(it, intent.type) }
    }
}
