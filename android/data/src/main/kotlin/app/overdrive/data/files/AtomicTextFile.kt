package app.overdrive.data.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One small text file that is **replaced whole**: what the plan's cache and the profile's cache are kept in (ADR 0017, ADR 0021).
 *
 * - **Written atomically.** The text goes to a temporary file next to it, is forced to storage and renamed over the file (an
 *   atomic move), so a reader, or a restart after a crash at any instant, sees the old file or the whole new one.
 * - **Read as text or not at all.** A missing file, an unreadable one and a folder where the file should be are all `null`; what
 *   the text means, and whether it is whole, is its format's to say.
 *
 * Calls run on [dispatcher], not on the caller's thread. A write that fails throws [IOException].
 *
 * @param file where the text is kept.
 */
internal class AtomicTextFile(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The text, or null when there is none or it cannot be read. */
    suspend fun read(): String? =
        withContext(dispatcher) {
            try {
                file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

    /** Replaces the file with [text]; the folder is made on the way. */
    suspend fun write(text: String) {
        val bytes = text.encodeToByteArray()
        withContext(dispatcher) {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val temporary = File(parent, file.name + TMP)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                temporary.delete()
                throw e
            }
        }
    }

    private companion object {
        const val TMP = ".tmp"
    }
}
