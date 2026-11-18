package app.overdrive.data.sync

import app.overdrive.core.sync.Outbox
import app.overdrive.core.sync.OutboxEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/** The steps of [FileOutbox]'s writes, in order. A test throws from [FileOutbox]'s `afterStep` to stop the process between two. */
enum class OutboxStep {
    /** One line was appended to the journal and forced to storage. */
    Appended,

    /** A compacted copy of the journal is written, forced to storage and not yet renamed over the journal. */
    TempWritten,

    /** The compacted copy replaced the journal. */
    Renamed,
}

/**
 * The persistent [Outbox] of the ride app (spec §4.9, ADR 0012): finished rides waiting to be uploaded, on the tablet's disk,
 * in one small file that **survives the app being killed at any instant, never loses an entry and tolerates a damaged tail**.
 *
 * **Format: an append-only journal.** One line per fact, `<crc32 of the rest, 8 hex digits><TAB><record>`, newline-ended:
 *
 * ```
 * # overdrive-outbox 1
 * 3F2A9C10 E <ride id> <enqueued at> <started at> <duration s> <title or ->
 * 91B0447E D <ride id>
 * ```
 *
 * `E` queues a ride, `D` says it was uploaded (fields are tab-separated; text is escaped so a tab or a newline in a title
 * cannot break a line). The state is the journal replayed: an `E` adds a ride unless it is already queued or was done, a `D`
 * removes it for good. Every append is **forced to storage before the call returns** (`fsync`), so a ride that `enqueue`
 * returned for is on disk; a process that dies *during* an append leaves a partial last line, which the checksum rejects
 * on the next start, and that is all it can do, because nothing earlier in the file is rewritten by an append.
 *
 * **Damage.** A last line without its newline, a line whose checksum or shape is wrong, an unreadable file: the good lines
 * are kept and the rest ignored, never an exception. When anything was ignored, or the journal has grown a lot of
 * superseded lines, the file is **compacted**: the live state is written to a temporary file, forced to storage and renamed
 * over the journal (an atomic move), so a death at any point leaves the old file or the whole new one. A leftover temporary
 * file is deleted at the next start. A failed append marks the journal for compaction before the next write, so a half
 * written line can never be followed by a good one that would be glued to it.
 *
 * **A ride is queued once, ever.** The journal remembers the ids it has been told were uploaded, so a ride marked done is
 * not accepted again (the library offers every stored ride to the outbox at each start, to catch one whose entry was lost
 * between its last rename and the append: an id the outbox has seen is a no-op). This is stricter than the in-memory
 * outbox of `core`, which forgets a ride once it is done.
 *
 * **Growth.** An entry is about 100 bytes and a done id about 30, so a ride a day is some 50 KB a year; compaction drops
 * the entries of rides that are done and keeps only their ids. Nothing is ever trimmed otherwise: a ride that was not
 * uploaded must not be forgotten. Nothing about a ride's numbers, the FIT file or any secret is in it.
 *
 * Calls are safe from any coroutine (one lock) and run on [dispatcher], not on the caller's thread; they may throw
 * [IOException] for a disk that fails, which `SyncCoordinator` reports as a status and a later retry.
 *
 * @param afterStep called after each step of a write; a test throws from it to stop the "process" between steps.
 */
class FileOutbox(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val afterStep: (OutboxStep) -> Unit = {},
) : Outbox {
    private class State(
        val pending: LinkedHashMap<String, OutboxEntry> = LinkedHashMap(),
        val done: MutableSet<String> = HashSet(),
        /** Lines in the file that no longer say anything: entries of done rides, repeats and lines that did not parse. */
        var superseded: Int = 0,
    )

    private val lock = Mutex()
    private var state: State? = null
    private var needsCompaction = false

    private fun temporary(): File = File(file.parentFile, file.name + TMP)

    override suspend fun enqueue(entry: OutboxEntry) {
        withContext(dispatcher) {
            lock.withLock {
                val current = loaded()
                if (entry.rideId in current.pending || entry.rideId in current.done) return@withLock
                append(encode(entry))
                current.pending[entry.rideId] = entry
            }
        }
    }

    override suspend fun pending(): List<OutboxEntry> =
        withContext(dispatcher) {
            lock.withLock { loaded().pending.values.sortedBy { it.enqueuedAtMillis } }
        }

    override suspend fun markDone(rideId: String) {
        withContext(dispatcher) {
            lock.withLock {
                val current = loaded()
                if (rideId !in current.pending) return@withLock
                append(encodeDone(rideId))
                current.pending.remove(rideId)
                current.done += rideId
                current.superseded++
                if (current.superseded > COMPACT_AFTER) needsCompaction = true
            }
        }
    }

    /** How many rides have been uploaded, ever. For the tests and the settings' "n rides waiting" line. */
    suspend fun doneCount(): Int = withContext(dispatcher) { lock.withLock { loaded().done.size } }

    // region reading

    /** The state, replayed from the file on first use (and repaired if it was damaged). */
    private fun loaded(): State {
        state?.let { return it }
        val read = read()
        state = read
        // A repair that fails is tried again before the next write; reading what is there must not fail because of it.
        if (needsCompaction) {
            try {
                compact(read)
            } catch (e: IOException) {
                needsCompaction = true
            }
        }
        return read
    }

    private fun read(): State {
        val result = State()
        runCatching { temporary().delete() }
        val bytes =
            try {
                if (file.isFile) file.readBytes() else return result
            } catch (e: IOException) {
                // Unreadable now: start from nothing and compact over it at the first write, which fails loudly if the disk is gone.
                needsCompaction = true
                return result
            }
        var start = 0
        while (start < bytes.size) {
            val end = bytes.indexOf(NEWLINE, start)
            if (end < 0) {
                // A last line without its newline: the process died while appending it.
                needsCompaction = true
                break
            }
            val line = String(bytes, start, end - start, Charsets.UTF_8)
            start = end + 1
            if (line.startsWith(COMMENT)) continue
            val record = decodeLine(line)
            if (record == null) {
                result.superseded++
                needsCompaction = true
            } else {
                apply(result, record)
            }
        }
        if (result.superseded > COMPACT_AFTER) needsCompaction = true
        return result
    }

    private fun apply(
        into: State,
        record: Record,
    ) {
        when (record) {
            is Record.Queued -> {
                val id = record.entry.rideId
                if (id in into.done || id in into.pending) {
                    into.superseded++
                } else {
                    into.pending[id] = record.entry
                }
            }

            is Record.Done -> {
                // The queued line of a ride that is done says nothing any more; the `D` line is what compaction keeps.
                val removed = into.pending.remove(record.rideId) != null
                val repeated = !into.done.add(record.rideId)
                if (removed || repeated) into.superseded++
            }
        }
    }

    // endregion

    // region writing

    private fun append(record: String) {
        if (needsCompaction) compact(loaded())
        val bytes = (line(record) + "\n").toByteArray(Charsets.UTF_8)
        try {
            file.parentFile?.mkdirs()
            val fresh = !file.isFile || file.length() == 0L
            FileOutputStream(file, true).use { stream ->
                if (fresh) stream.write(HEADER_LINE)
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            syncDirectoryIfNew(fresh)
        } catch (e: IOException) {
            // A line may be half written: never append after it, rewrite the file first.
            needsCompaction = true
            throw e
        }
        afterStep(OutboxStep.Appended)
    }

    /** Writes the live [from] as a whole new journal, atomically. */
    private fun compact(from: State) {
        val temporary = temporary()
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(temporary).use { stream ->
                stream.write(HEADER_LINE)
                for (entry in from.pending.values) stream.write((line(encode(entry)) + "\n").toByteArray())
                for (id in from.done) stream.write((line(encodeDone(id)) + "\n").toByteArray())
                stream.flush()
                stream.fd.sync()
            }
            afterStep(OutboxStep.TempWritten)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectoryIfNew(true)
            afterStep(OutboxStep.Renamed)
            from.superseded = 0
            needsCompaction = false
        } catch (e: IOException) {
            runCatching { temporary.delete() }
            throw e
        }
    }

    /** Makes a created file or a rename durable too, where the platform lets a directory be opened for it; best effort. */
    private fun syncDirectoryIfNew(changed: Boolean) {
        if (!changed) return
        try {
            FileChannel.open(file.parentFile?.toPath() ?: return, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Not every file system allows it; the file's own data was forced to storage before.
        }
    }

    // endregion

    private sealed interface Record {
        data class Queued(
            val entry: OutboxEntry,
        ) : Record

        data class Done(
            val rideId: String,
        ) : Record
    }

    private companion object {
        const val TMP = ".tmp"
        const val NEWLINE = '\n'.code.toByte()
        const val COMMENT = "#"
        const val TAB = '\t'
        const val NO_TITLE = "-"
        const val TITLE_PREFIX = "T"
        const val COMPACT_AFTER = 200
        const val QUEUED = "E"
        const val DONE = "D"
        const val CRC_DIGITS = 8
        const val HEX = 16
        const val FIELDS_QUEUED = 6
        const val FIELDS_DONE = 2
        val HEADER_LINE = "# overdrive-outbox 1\n".toByteArray(Charsets.UTF_8)

        fun ByteArray.indexOf(
            value: Byte,
            from: Int,
        ): Int {
            for (index in from until size) if (this[index] == value) return index
            return -1
        }

        fun crc(text: String): String {
            val crc = CRC32().also { it.update(text.toByteArray(Charsets.UTF_8)) }.value
            return crc.toString(HEX).padStart(CRC_DIGITS, '0')
        }

        fun line(record: String): String = "${crc(record)}$TAB$record"

        fun encode(entry: OutboxEntry): String =
            listOf(
                QUEUED,
                escape(entry.rideId),
                entry.enqueuedAtMillis.toString(),
                entry.startedAtMillis.toString(),
                entry.durationSeconds.toString(),
                entry.title?.let { TITLE_PREFIX + escape(it) } ?: NO_TITLE,
            ).joinToString(TAB.toString())

        fun encodeDone(rideId: String): String = listOf(DONE, escape(rideId)).joinToString(TAB.toString())

        /** The record of a journal [line], or null when its checksum or its shape is wrong. Never throws. */
        fun decodeLine(line: String): Record? {
            val split = line.indexOf(TAB)
            if (split != CRC_DIGITS) return null
            val record = line.substring(split + 1)
            if (!line.regionMatches(0, crc(record), 0, CRC_DIGITS, ignoreCase = true)) return null
            val fields = record.split(TAB)
            return try {
                when {
                    fields.firstOrNull() == QUEUED && fields.size == FIELDS_QUEUED -> {
                        val title =
                            fields[5].let {
                                if (it ==
                                    NO_TITLE
                                ) {
                                    null
                                } else if (it.startsWith(TITLE_PREFIX)) {
                                    unescape(it.substring(1))
                                } else {
                                    return null
                                }
                            }
                        Record.Queued(
                            OutboxEntry(
                                rideId = unescape(fields[1]),
                                enqueuedAtMillis = fields[2].toLong(),
                                startedAtMillis = fields[3].toLong(),
                                durationSeconds = fields[4].toInt(),
                                title = title,
                            ),
                        )
                    }

                    fields.firstOrNull() == DONE && fields.size == FIELDS_DONE -> {
                        unescape(fields[1]).takeIf { it.isNotEmpty() }?.let { Record.Done(it) }
                    }

                    else -> {
                        null
                    }
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** `\` `\t` `\n` `\r` escaped, so a field never contains a tab or a line break. */
        fun escape(text: String): String =
            buildString {
                for (char in text) {
                    when (char) {
                        '\\' -> append("\\\\")
                        '\t' -> append("\\t")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> append(char)
                    }
                }
            }

        /** The inverse of [escape]; a backslash that starts no known escape is malformed. */
        fun unescape(text: String): String {
            val out = StringBuilder()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char != '\\') {
                    out.append(char)
                    index++
                    continue
                }
                require(index + 1 < text.length) { "A dangling escape" }
                when (text[index + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    else -> throw IllegalArgumentException("Unknown escape")
                }
                index += 2
            }
            return out.toString()
        }
    }
}
