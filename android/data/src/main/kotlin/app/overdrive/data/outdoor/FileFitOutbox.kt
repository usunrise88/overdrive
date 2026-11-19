package app.overdrive.data.outdoor

import app.overdrive.core.outdoor.FitFileInfo
import app.overdrive.core.outdoor.FitOutbox
import app.overdrive.core.outdoor.FitRefusal
import app.overdrive.data.files.AtomicBytesFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * The FIT files that wait for the service, one file each in one folder (spec §11.8, ADR 0028). A FIT file is the rider's ride (heart
 * rate and position), so the rules are strict, the same as the watch batches' (`FileBatchStore`, ADR 0027):
 *
 * - **A file is on disk when [add] returns**: written to a temporary file, forced to storage and renamed, so a kill at any instant leaves
 *   nothing or the whole file, never half of one.
 * - **Removed only by [remove]**, which the importer calls after the service's 2xx answer. A file the service refuses for good is
 *   [park]ed into `parked/` with the reason, and only [discard] empties that.
 * - **Order is the file name**: a zero-padded sequence number, found again by listing the folder, so the order survives a restart.
 * - **A damaged file never poisons the queue.** The header names the size and the CRC-32 of the bytes; a file whose bytes do not match
 *   reads as no bytes, and the importer parks it.
 * - **Private.** The folder is the app's own (`filesDir`, not backed up, never a shared or world-readable place), the bytes are not
 *   copied anywhere else, nothing here logs, and the name the sharing app gave the file is not kept: the names are numbers.
 *
 * File: `<seq>.fit`, first line `overdrive-fit 1 <size> <crc32 hex> <reason or ->`, a line feed, then the file's bytes exactly.
 */
class FileFitOutbox(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FitOutbox {
    private val lock = Mutex()
    private var next: Long? = null

    private val parkedDirectory: File get() = File(directory, PARKED)

    override suspend fun add(bytes: ByteArray): FitFileInfo =
        lock.withLock {
            val seq = nextSequence()
            val info = FitFileInfo(seq, bytes.size, checksum(bytes))
            AtomicBytesFile(File(directory, name(seq)), dispatcher).write(header(info, null), bytes)
            next = seq + 1
            info
        }

    override suspend fun pending(): List<FitFileInfo> = lock.withLock { infos(directory) }

    override suspend fun bytes(seq: Long): ByteArray? =
        lock.withLock {
            withContext(dispatcher) {
                val file = File(directory, name(seq))
                if (file.isFile) readWhole(file) else null
            }
        }

    override suspend fun remove(seq: Long) {
        lock.withLock { withContext(dispatcher) { File(directory, name(seq)).delete() } }
    }

    override suspend fun park(
        seq: Long,
        refusal: FitRefusal,
    ) {
        lock.withLock {
            val source = File(directory, name(seq))
            if (withContext(dispatcher) { source.isFile }) {
                val kept = withContext(dispatcher) { readWhole(source) }
                // Rewritten with the reason in its header, whole or not at all; then the queue's copy goes. Bytes that fail their
                // checksum are not worth keeping: the entry stays, as a header with no bytes, so the count and the reason are kept.
                val bytes = kept ?: ByteArray(0)
                val info =
                    FitFileInfo(
                        seq,
                        bytes.size,
                        checksum(bytes),
                        if (kept ==
                            null
                        ) {
                            FitRefusal.DAMAGED
                        } else {
                            refusal
                        },
                    )
                AtomicBytesFile(File(parkedDirectory, name(seq)), dispatcher).write(header(info, info.refusal), bytes)
                withContext(dispatcher) { source.delete() }
            }
        }
    }

    override suspend fun parked(): List<FitFileInfo> = lock.withLock { infos(parkedDirectory) }

    override suspend fun discard(seq: Long) {
        lock.withLock { withContext(dispatcher) { File(parkedDirectory, name(seq)).delete() } }
    }

    private suspend fun nextSequence(): Long {
        next?.let { return it }
        return withContext(dispatcher) {
            // A temporary file a crash left behind is not a file of the queue: sweep it.
            for (folder in listOf(directory, parkedDirectory)) {
                folder.listFiles { f -> f.isFile && f.name.endsWith(TMP) }.orEmpty().forEach { it.delete() }
            }
            val highest = (sequences(directory) + sequences(parkedDirectory)).maxOrNull() ?: 0L
            (highest + 1).also { next = it }
        }
    }

    private suspend fun infos(folder: File): List<FitFileInfo> =
        withContext(dispatcher) {
            val files = folder.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.name }
            files.mapNotNull { file ->
                val seq = file.name.removeSuffix(SUFFIX).toLongOrNull() ?: return@mapNotNull null
                try {
                    readHeader(file)?.let { head ->
                        FitFileInfo(seq, head.size, head.checksum, head.refusal)
                    }
                } catch (e: IOException) {
                    null
                }
            }
        }

    private class Head(
        val size: Int,
        val checksum: String,
        val refusal: FitRefusal?,
        /** Where the bytes start. */
        val bodyAt: Int,
    )

    /**
     * The header of [file], read from its first bytes only (a file can be 16 MiB); null for anything that is not ours. It says nothing of
     * whether the bytes after it are whole: [readWhole] checks that, so a damaged file is still listed and gets parked.
     */
    private fun readHeader(file: File): Head? =
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(minOf(MAX_HEADER_BYTES.toLong(), input.length()).toInt())
            input.readFully(buffer)
            val end = buffer.indexOf('\n'.code.toByte())
            if (end < 0) return@use null
            val fields = String(buffer, 0, end, Charsets.US_ASCII).split(' ')
            if (fields.size != HEADER_FIELDS || fields[0] != MAGIC || fields[1] != VERSION) return@use null
            val size = fields[2].toIntOrNull() ?: return@use null
            val refusal =
                fields[REASON_AT].takeIf { it != NONE }?.let { name ->
                    FitRefusal.entries.firstOrNull {
                        it.name ==
                            name
                    }
                }
            Head(size, fields[CRC_AT], refusal, end + 1)
        }

    /** The bytes of [file], or null when its header is not ours or the bytes fail their size and checksum. */
    private fun readWhole(file: File): ByteArray? =
        try {
            val head = readHeader(file) ?: return null
            // Whole when its length is the header and the bytes the header says: a short or a long one is damaged.
            if (file.length() != head.bodyAt.toLong() + head.size) return null
            val bytes = ByteArray(head.size)
            RandomAccessFile(file, "r").use {
                it.seek(head.bodyAt.toLong())
                it.readFully(bytes)
            }
            bytes.takeIf { checksum(it) == head.checksum }
        } catch (e: IOException) {
            null
        }

    private fun sequences(folder: File): List<Long> =
        folder.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull {
            it.name.removeSuffix(SUFFIX).toLongOrNull()
        }

    private fun header(
        info: FitFileInfo,
        refusal: FitRefusal?,
    ): ByteArray = "$MAGIC $VERSION ${info.bytes} ${info.checksum} ${refusal?.name ?: NONE}\n".encodeToByteArray()

    private fun checksum(bytes: ByteArray): String = "%08x".format(CRC32().also { it.update(bytes) }.value)

    private fun name(seq: Long): String = seq.toString().padStart(SEQ_DIGITS, '0') + SUFFIX

    private companion object {
        const val MAGIC = "overdrive-fit"
        const val VERSION = "1"
        const val NONE = "-"
        const val HEADER_FIELDS = 5
        const val CRC_AT = 3
        const val REASON_AT = 4
        const val MAX_HEADER_BYTES = 128
        const val SEQ_DIGITS = 12
        const val SUFFIX = ".fit"
        const val TMP = ".tmp"
        const val PARKED = "parked"
    }
}
