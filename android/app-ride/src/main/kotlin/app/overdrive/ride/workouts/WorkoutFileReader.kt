package app.overdrive.ride.workouts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads a workout the rider picked with the system file picker (Storage Access Framework). Call off the main thread. */
object WorkoutFileReader {
    fun read(
        resolver: ContentResolver,
        uri: Uri,
    ): WorkoutFileResult {
        val name = displayName(resolver, uri)
        return try {
            val bytes =
                resolver.openInputStream(uri)?.use { readAtMost(it, WorkoutFiles.MAX_BYTES) }
                    ?: return WorkoutFiles.unreadable(
                        name,
                        "The file is larger than a workout file can be. Pick a workout file in the native JSON format or a Zwift .zwo file.",
                    )
            WorkoutFiles.parse(bytes.toString(Charsets.UTF_8), name)
        } catch (e: IOException) {
            WorkoutFiles.unreadable(name, "The file could not be read. Check that it is still there and pick it again.")
        } catch (e: SecurityException) {
            WorkoutFiles.unreadable(
                name,
                "The app is not allowed to read that file. Pick it again from the file picker.",
            )
        }
    }

    /** The bytes of [input], or null when there are more than [limit] (`readNBytes` needs API 33, the minimum is 31). */
    private fun readAtMost(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return out.toByteArray()
            out.write(chunk, 0, read)
            if (out.size() > limit) return null
        }
    }

    private const val CHUNK_BYTES = 8192

    private fun displayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
}
