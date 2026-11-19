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
 * One file of bytes that is **replaced whole**, like [AtomicTextFile] but binary and in parts (a header, then the bytes): a FIT file waits
 * for the service in one (ADR 0028).
 *
 * - **Written atomically.** The parts go to a temporary file next to it, are forced to storage and renamed over the file (an atomic
 *   move), so a reader, or a restart after a crash at any instant, sees the old file or the whole new one. A failed write removes its
 *   temporary file.
 * - **Never copied to a shared place.** The folder is the app's own (`filesDir`), which no other app can read.
 *
 * Calls run on [dispatcher], not on the caller's thread. A write that fails throws [IOException].
 */
internal class AtomicBytesFile(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Replaces the file with the parts one after the other; the folder is made on the way. */
    suspend fun write(vararg parts: ByteArray) {
        withContext(dispatcher) {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val temporary = File(parent, file.name + TMP)
            try {
                FileOutputStream(temporary).use { stream ->
                    for (part in parts) stream.write(part)
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
