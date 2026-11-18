package app.overdrive.data.remote

import app.overdrive.core.remote.TextStore
import app.overdrive.data.files.AtomicTextFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException

/**
 * One cached answer of the service (or the list of FTP answers) in one small file on the phone's disk (spec §6.1, ADR 0024).
 *
 * - **Written atomically.** The text goes to a temporary file next to it, is forced to storage and renamed over the file, so a
 *   reader, or a restart after a crash at any instant, sees the old file or the whole new one ([AtomicTextFile]).
 * - **Read as text or not at all.** What the text means, and whether it is whole, is its format's to say
 *   (`CacheText`: a header with a checksum, strict). A missing file, an unreadable one and a folder where the file should be
 *   all read as nothing.
 * - **Nothing secret.** The files hold the service's answers (the rider's figures) and a checksum of the address, never the token.
 *
 * Calls run on [dispatcher], not on the caller's thread. A write that fails throws [IOException].
 */
class FileTextStore(
    file: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TextStore {
    private val text = AtomicTextFile(file, dispatcher)

    override suspend fun read(): String? = text.read()

    override suspend fun write(text: String) = this.text.write(text)
}

/**
 * The files of one kind of cached answer in one folder, **at most [maxFiles]** of them: the details of rides the rider has opened
 * (`filesDir/cache/rides/<id>`). After a write the oldest files beyond the limit are deleted, so the folder cannot grow without
 * end however many rides are opened. [store] is the [TextStore] of one name; names are file names made of letters, digits, dots,
 * dashes and underscores, which is what a ride id is.
 */
class FileTextStores(
    private val directory: File,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(maxFiles >= 1) { "At least one file is kept" }
    }

    /** The store of [name]. @throws IllegalArgumentException if [name] is not a plain file name. */
    fun store(name: String): TextStore {
        require(NAME.matches(name)) { "Not a plain file name" }
        val inner = FileTextStore(File(directory, name), dispatcher)
        return object : TextStore {
            override suspend fun read(): String? = inner.read()

            override suspend fun write(text: String) {
                inner.write(text)
                prune(keep = name)
            }
        }
    }

    private fun prune(keep: String) {
        val files = directory.listFiles { file -> file.isFile && !file.name.endsWith(TMP) } ?: return
        if (files.size <= maxFiles) return
        files
            .filter { it.name != keep }
            .sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { it.delete() }
    }

    companion object {
        const val DEFAULT_MAX_FILES = 24
        private const val TMP = ".tmp"
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
