package app.overdrive.companion

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What another app's FileProvider looks like to the companion, for the intent tests: a content URI with a display name, a size and the
 * bytes. A file may report a size it does not have ([reportedSize]) or none at all, as a provider that lies or does not know.
 */
class TestFitProvider : ContentProvider() {
    class Held(
        val name: String?,
        val bytes: ByteArray,
        /** What the provider says its size is: the real one by default, another number, or null for "does not know". */
        val reportedSize: Long?,
        val type: String = "application/octet-stream",
        /** What opening the file throws, for a provider with a bug of its own. */
        val failure: RuntimeException? = null,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val held = files[uri.lastPathSegment] ?: return null
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(arrayOf<Any?>(held.name, held.reportedSize))
        return cursor
    }

    override fun getType(uri: Uri): String? = files[uri.lastPathSegment]?.type

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val held = files[uri.lastPathSegment] ?: return null
        held.failure?.let { throw it }
        val file = File.createTempFile("shared", ".fit").also { it.deleteOnExit() }
        file.writeBytes(held.bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "app.overdrive.test.fit"
        private val files = ConcurrentHashMap<String, Held>()

        fun clear() = files.clear()

        /** Puts a file and returns its content URI. */
        fun put(
            id: String,
            name: String?,
            bytes: ByteArray,
            reportedSize: Long? = bytes.size.toLong(),
            type: String = "application/octet-stream",
            failure: RuntimeException? = null,
        ): Uri {
            files[id] = Held(name, bytes, reportedSize, type, failure)
            return Uri.parse("content://$AUTHORITY/$id")
        }
    }
}
