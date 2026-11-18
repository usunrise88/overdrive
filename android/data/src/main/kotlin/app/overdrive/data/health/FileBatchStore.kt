package app.overdrive.data.health

import app.overdrive.core.health.BatchInfo
import app.overdrive.core.health.BatchStore
import app.overdrive.core.health.WellnessBatch
import app.overdrive.data.files.AtomicTextFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * The batches of watch data that wait for the service, one file each in one folder (spec §6.2, ADR 0027). Health data lives here until
 * the service has accepted it, so the rules are strict:
 *
 * - **A batch is on disk when [add] returns**: written to a temporary file, forced to storage and renamed, so a kill at any instant
 *   leaves the old state or the whole new batch, never half of one.
 * - **Removed only by [remove]**, which the sync calls after the service's 2xx answer. A batch the service refuses for good is
 *   [park]ed into `parked/`, which nothing empties: the owner can still look at it, and it is counted in Settings.
 * - **Order is the file name**: a zero-padded sequence number, found again by listing the folder, so the order survives a restart.
 * - **A damaged file never poisons the queue.** The header names the number of records and the CRC-32 of the body; a file whose body
 *   does not match reads as no body, and the sync parks it.
 * - **Private.** The folder is the app's own (`filesDir`, not backed up, not shared), nothing here logs, and file names are numbers.
 *
 * File: `<seq>.batch`, first line `overdrive-batch 1 <records> <crc32 hex> <kind=count,kind=count>`, then the request body exactly.
 */
class FileBatchStore(
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BatchStore {
    private val lock = Mutex()
    private var next: Long? = null

    private val parkedDirectory: File get() = File(directory, PARKED)

    override suspend fun add(batch: WellnessBatch): BatchInfo =
        lock.withLock {
            val seq = nextSequence()
            val file = File(directory, name(seq))
            val text = header(batch) + "\n" + batch.body
            AtomicTextFile(file, dispatcher).write(text)
            next = seq + 1
            BatchInfo(seq, batch.records, text.length, batch.byType)
        }

    override suspend fun pending(): List<BatchInfo> = lock.withLock { infos(directory) }

    override suspend fun body(seq: Long): String? =
        lock.withLock {
            withContext(dispatcher) {
                try {
                    val text =
                        File(directory, name(seq)).takeIf { it.isFile }?.readText(Charsets.UTF_8)
                            ?: return@withContext null
                    val split = text.indexOf('\n')
                    if (split < 0) return@withContext null
                    val fields = text.substring(0, split).split(' ')
                    val body = text.substring(split + 1)
                    val whole = fields.size >= HEADER_FIELDS && fields[0] == MAGIC && fields[1] == HEADER_VERSION
                    if (whole) body.takeIf { crc(it) == fields[CRC_AT] } else null
                } catch (e: IOException) {
                    null
                }
            }
        }

    override suspend fun remove(seq: Long) {
        lock.withLock { withContext(dispatcher) { File(directory, name(seq)).delete() } }
    }

    override suspend fun park(seq: Long) {
        lock.withLock {
            withContext(dispatcher) {
                val file = File(directory, name(seq))
                if (file.isFile) {
                    parkedDirectory.mkdirs()
                    if (!file.renameTo(
                            File(parkedDirectory, name(seq)),
                        )
                    ) {
                        file.copyTo(File(parkedDirectory, name(seq)), overwrite = true)
                    }
                    file.delete()
                }
            }
        }
    }

    override suspend fun parked(): List<BatchInfo> = lock.withLock { infos(parkedDirectory) }

    private fun nextSequence(): Long {
        next?.let { return it }
        val highest = (sequences(directory) + sequences(parkedDirectory)).maxOrNull() ?: 0L
        return (highest + 1).also { next = it }
    }

    private suspend fun infos(folder: File): List<BatchInfo> =
        withContext(dispatcher) {
            val files = folder.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.name }
            files.mapNotNull { file ->
                val seq = file.name.removeSuffix(SUFFIX).toLongOrNull() ?: return@mapNotNull null
                try {
                    file.bufferedReader(Charsets.UTF_8).use { reader ->
                        val fields = reader.readLine().orEmpty().split(' ')
                        if (fields.size < HEADER_FIELDS || fields[0] != MAGIC || fields[1] != HEADER_VERSION) {
                            null
                        } else {
                            BatchInfo(
                                seq,
                                fields[2].toIntOrNull() ?: 0,
                                file.length().toInt(),
                                counts(
                                    fields[
                                        HEADER_FIELDS -
                                            1,
                                    ],
                                ),
                            )
                        }
                    }
                } catch (e: IOException) {
                    null
                }
            }
        }

    private fun sequences(folder: File): List<Long> =
        folder.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull {
            it.name.removeSuffix(SUFFIX).toLongOrNull()
        }

    private fun header(batch: WellnessBatch): String =
        "$MAGIC $HEADER_VERSION ${batch.records} ${crc(
            batch.body,
        )} ${batch.byType.entries.joinToString(",") { "${it.key}=${it.value}" }}"

    private fun counts(text: String): Map<String, Int> =
        text
            .split(',')
            .mapNotNull { part ->
                val kind = part.substringBefore('=', "")
                val n = part.substringAfter('=', "").toIntOrNull()
                if (kind.isEmpty() || n == null) null else kind to n
            }.toMap()

    private fun crc(body: String): String = "%08x".format(CRC32().also { it.update(body.encodeToByteArray()) }.value)

    private fun name(seq: Long): String = seq.toString().padStart(SEQ_DIGITS, '0') + SUFFIX

    private companion object {
        const val MAGIC = "overdrive-batch"
        const val HEADER_VERSION = "1"
        const val HEADER_FIELDS = 5
        const val CRC_AT = 3
        const val SEQ_DIGITS = 12
        const val SUFFIX = ".batch"
        const val PARKED = "parked"
    }
}
