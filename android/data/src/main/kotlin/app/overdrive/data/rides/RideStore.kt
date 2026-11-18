package app.overdrive.data.rides

import app.overdrive.core.fit.FitOptions
import app.overdrive.core.fit.FitWriter
import app.overdrive.core.record.ByteSink
import app.overdrive.core.record.FileByteSink
import app.overdrive.core.record.FileByteSource
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideLog
import app.overdrive.core.record.RideLogStatus
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordJson
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** The steps of [RideStore.finalize], in order. Tests use them to stop the process between two of them. */
enum class FinalizeStep {
    /** The small file with the title, start and duration. */
    Meta,

    /** The ride record as JSON (the wire format of spec §8.2). */
    Record,

    /** The FIT file. */
    Fit,

    /** The ride log, which is only deleted once the two files above are safely on disk. */
    DeleteLog,
}

/**
 * The rides on disk (spec §4.2, §4.8, ADR 0008): one directory, and for each ride, under its id,
 *
 * - `<id>.ridelog`, the crash-safe log of ADR 0007, while the ride runs and until it is stored;
 * - `<id>.json`, the finished ride record in the wire format, and `<id>.fit`, the FIT file, once it is stored;
 * - `<id>.meta`, a few lines with the title, the start and the duration, so that a list does not have to read rides.
 *
 * A ride is **complete** when its `.json` and `.fit` exist. [finalize] writes the two (and the meta file) each through a
 * temporary file that is flushed and renamed, so neither is ever partly there, and deletes the log last, after both:
 * whatever instant the process dies at, the ride is either still in its log or fully stored.
 *
 * Plain `java.io`, no Android: the directory is the app's private `filesDir/rides` on the tablet and a temporary
 * directory in a test. Every call that touches the disk returns a [StoreResult] instead of throwing, so a full disk or a
 * missing folder becomes a state the screen can show.
 *
 * Thread-safety: the calls may be made from any thread, but two calls about the same ride must not overlap; the app
 * runs them all on one serial dispatcher.
 *
 * @param openSink how a log file is opened for writing; a test passes a slow or failing one.
 * @param afterStep called after each step of [finalize]; a test throws from it to stop the process between steps.
 */
class RideStore(
    val directory: File,
    private val openSink: (File) -> ByteSink = { FileByteSink(it) },
    private val afterStep: (FinalizeStep) -> Unit = {},
) {
    /** Ids of the rides this process is writing, or has finished and not yet stored: never offered for recovery. */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun logFile(id: String): File = File(directory, "$id$LOG")

    fun recordFile(id: String): File = File(directory, "$id$RECORD")

    fun fitFile(id: String): File = File(directory, "$id$FIT")

    private fun metaFile(id: String): File = File(directory, "$id$META")

    /**
     * Opens the log of a new ride and notes its title. Called when the ride starts, so a ride that is prepared and
     * never started leaves no file. Fails (and writes nothing) if a ride of that id is already stored.
     */
    fun openLog(
        header: RideHeader,
        title: String,
    ): StoreResult<ByteSink> {
        val id = idFor(header.id)
        return attempt {
            directory.mkdirs()
            if (logFile(id).exists() ||
                recordFile(id).exists()
            ) {
                throw IOException("A ride with this id is already stored")
            }
            active.add(id)
            try {
                // The title is a convenience: a ride without it is still a ride.
                runCatching { writeAtomically(metaFile(id), Meta(title, header.startEpochMillis, null).toBytes()) }
                openSink(logFile(id))
            } catch (e: Exception) {
                active.remove(id)
                throw e
            }
        }
    }

    /**
     * Stores a finished (or recovered) ride: the meta file, the record as JSON and the FIT file, each atomically, then
     * deletes the log. [utcOffsetSeconds] is the device's offset from UTC at the ride's start, for the FIT file's local
     * time (ADR 0007). A ride without samples keeps nothing and fails as not [StoreResult.Failed.retryable].
     */
    fun finalize(
        record: RideRecord,
        title: String,
        utcOffsetSeconds: Int,
    ): StoreResult<StoredRide> {
        val id = idFor(record.ride.id)
        if (record.samples.isEmpty()) {
            deleteFiles(id)
            return StoreResult.Failed(IllegalArgumentException("The ride has no samples"), retryable = false)
        }
        val files =
            try {
                Stored(
                    meta = Meta(title, record.ride.startEpochMillis, record.samples.size).toBytes(),
                    json = RideRecordJson.encode(record).encodeToByteArray(),
                    fit = FitWriter(FitOptions(utcOffsetSeconds = utcOffsetSeconds)).write(record),
                )
            } catch (e: Exception) {
                // The record cannot be written in a format (a start the FIT format cannot date): retrying changes nothing.
                return StoreResult.Failed(e, retryable = false)
            }
        return attempt {
            directory.mkdirs()
            writeAtomically(metaFile(id), files.meta)
            afterStep(FinalizeStep.Meta)
            writeAtomically(recordFile(id), files.json)
            afterStep(FinalizeStep.Record)
            writeAtomically(fitFile(id), files.fit)
            afterStep(FinalizeStep.Fit)
            syncDirectory()
            logFile(id).delete()
            afterStep(FinalizeStep.DeleteLog)
            active.remove(id)
            checkNotNull(inspect(id)) { "The ride vanished after it was stored" }
        }
    }

    /**
     * Completes a ride found on disk: an interrupted one the rider chose to keep, or a finished one whose export was cut
     * off. It is rebuilt from its log (the intact part of it) or, when there is no log, from its stored record, and then
     * stored like any finished ride, which also writes a missing FIT file again. [utcOffsetSeconds] is asked for the
     * ride's start, which is only known once it is read.
     */
    fun finalizeStored(
        id: String,
        utcOffsetSeconds: (startEpochMillis: Long) -> Int,
    ): StoreResult<StoredRide> {
        val title = readMeta(id)?.title
        val record =
            try {
                recordOf(id)
                    ?: return StoreResult.Failed(IOException("Nothing to recover for this ride"), retryable = false)
            } catch (e: IOException) {
                return StoreResult.Failed(e)
            } catch (e: IllegalArgumentException) {
                return StoreResult.Failed(e, retryable = false)
            }
        return finalize(
            record,
            title ?: titleFallback(record.ride.workoutId),
            utcOffsetSeconds(record.ride.startEpochMillis),
        )
    }

    /** One ride, or null if there is nothing of that id. Reads the log of an unfinished ride, which is small. */
    fun inspect(id: String): StoredRide? {
        val meta = readMeta(id)
        val hasRecord = recordFile(id).isFile
        val fit = fitFile(id).takeIf { it.isFile }
        if (hasRecord && fit != null) {
            val facts = meta?.takeIf { it.durationSeconds != null } ?: runCatching { metaOfRecord(id) }.getOrNull()
            facts ?: return null
            return StoredRide(
                id = id,
                title = facts.title,
                startEpochMillis = facts.startEpochMillis,
                durationSeconds = facts.durationSeconds ?: 0,
                status = StoredRideStatus.Saved,
                fitBytes = fit.length(),
            )
        }
        val log = logFile(id).takeIf { it.isFile }
        if (log != null) {
            val recovered = runCatching { RideLog.recover(FileByteSource(log)) }.getOrNull() ?: return null
            val status =
                when {
                    id in active -> StoredRideStatus.Recording
                    recovered.status == RideLogStatus.FINISHED -> StoredRideStatus.Unsaved
                    else -> StoredRideStatus.Interrupted
                }
            return StoredRide(
                id = id,
                title = meta?.title ?: titleFallback(recovered.header.workoutId),
                startEpochMillis = recovered.header.startEpochMillis,
                durationSeconds = recovered.samples.size,
                status = status,
                fitBytes = fit?.length(),
            )
        }
        if (hasRecord) {
            val facts =
                meta?.takeIf { it.durationSeconds != null } ?: runCatching { metaOfRecord(id) }.getOrNull()
                    ?: return null
            return StoredRide(
                id = id,
                title = facts.title,
                startEpochMillis = facts.startEpochMillis,
                durationSeconds = facts.durationSeconds ?: 0,
                status = if (id in active) StoredRideStatus.Recording else StoredRideStatus.Unsaved,
                fitBytes = null,
            )
        }
        return null
    }

    /** Every ride on disk, newest first. Reads, never changes anything. */
    fun list(): List<StoredRide> = ids().mapNotNull(::inspect).sortedByDescending { it.startEpochMillis }

    /**
     * Tidies the directory, then lists it: leftover temporary files go, a log that holds no valid header (the ride never
     * got going) goes, and a log whose ride is already complete goes. Never touches a ride this process is writing.
     * Called once when the app starts.
     */
    fun scan(): List<StoredRide> {
        directory.listFiles()?.filter { it.name.endsWith(TMP) }?.forEach { it.delete() }
        for (id in ids()) {
            if (id in active) continue
            val log = logFile(id)
            if (log.isFile) {
                val complete = recordFile(id).isFile && fitFile(id).isFile
                val headerless = !complete && runCatching { RideLog.recover(FileByteSource(log)) }.getOrNull() == null
                if (complete || headerless) log.delete()
            }
            if (!logFile(id).exists() && !recordFile(id).exists()) deleteFiles(id)
        }
        return list()
    }

    /** Removes everything stored for [id]. A ride this process is writing is left alone. Returns whether it is gone. */
    fun delete(id: String): Boolean {
        if (id in active) return false
        deleteFiles(id)
        return !logFile(id).exists() && !recordFile(id).exists() && !fitFile(id).exists()
    }

    private fun deleteFiles(id: String) {
        for (file in listOf(logFile(id), recordFile(id), fitFile(id), metaFile(id))) {
            file.delete()
            File(directory, file.name + TMP).delete()
        }
        active.remove(id)
    }

    private fun ids(): Set<String> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && EXTENSIONS.any { extension -> it.name.endsWith(extension) } }
            .map { it.name.substringBefore('.') }
            .toSortedSet()

    /** The ride of [id] as a record: from its log if there is a usable one, else from the stored JSON. */
    private fun recordOf(id: String): RideRecord? {
        val log = logFile(id)
        if (log.isFile) RideLog.recover(FileByteSource(log))?.let { return it.toRecord() }
        val json = recordFile(id)
        return if (json.isFile) RideRecordJson.decode(json.readText()) else null
    }

    private fun metaOfRecord(id: String): Meta {
        val record = RideRecordJson.decode(recordFile(id).readText())
        return Meta(titleFallback(record.ride.workoutId), record.ride.startEpochMillis, record.samples.size)
    }

    private fun titleFallback(workoutId: String?): String = if (workoutId == null) "Free ride" else "Workout"

    private fun readMeta(id: String): Meta? {
        val file = metaFile(id)
        if (!file.isFile) return null
        return try {
            val properties = Properties()
            InputStreamReader(FileInputStream(file), Charsets.UTF_8).use(properties::load)
            Meta(
                title = properties.getProperty("title")?.takeIf { it.isNotBlank() } ?: return null,
                startEpochMillis = properties.getProperty("startEpochMillis")?.toLongOrNull() ?: return null,
                durationSeconds = properties.getProperty("durationSeconds")?.toIntOrNull(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private class Stored(
        val meta: ByteArray,
        val json: ByteArray,
        val fit: ByteArray,
    )

    private class Meta(
        val title: String,
        val startEpochMillis: Long,
        val durationSeconds: Int?,
    ) {
        fun toBytes(): ByteArray {
            val properties = Properties()
            properties.setProperty("title", title)
            properties.setProperty("startEpochMillis", startEpochMillis.toString())
            durationSeconds?.let { properties.setProperty("durationSeconds", it.toString()) }
            val out = java.io.ByteArrayOutputStream()
            OutputStreamWriter(out, Charsets.UTF_8).use { properties.store(it, null) }
            // `Properties.store` starts with a comment line holding the date; the file is the same whenever it is written.
            return out
                .toString(Charsets.UTF_8)
                .lines()
                .filterNot { it.startsWith("#") }
                .joinToString("\n")
                .encodeToByteArray()
        }
    }

    /**
     * Writes [bytes] to a temporary file next to [target], forces them to storage and renames it over [target], so a
     * reader (or a restart after a crash) sees the old file or the whole new one, never half of it.
     */
    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, target.name + TMP)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    /** Makes the renames durable too, where the platform lets a directory be opened for it; best effort. */
    private fun syncDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Not every file system allows it; the files themselves were flushed before their renames.
        }
    }

    private inline fun <T> attempt(action: () -> T): StoreResult<T> =
        try {
            StoreResult.Ok(action())
        } catch (e: IOException) {
            StoreResult.Failed(e)
        } catch (e: SecurityException) {
            StoreResult.Failed(e)
        } catch (e: Exception) {
            StoreResult.Failed(e, retryable = false)
        }

    companion object {
        const val LOG = ".ridelog"
        const val RECORD = ".json"
        const val FIT = ".fit"
        const val META = ".meta"
        private const val TMP = ".tmp"
        private val EXTENSIONS = listOf(LOG, RECORD, FIT, META)
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private const val SANITIZED_PREFIX_LENGTH = 40

        /**
         * The file-name form of a ride's id: the id itself when it is made of letters, digits, `_` and `-` (every id the
         * engine makes is, `ride-<start millis>`), else those characters of it with a checksum of the original, so two
         * different ids never share files and nothing can reach outside the directory.
         */
        fun idFor(rideId: String): String {
            if (SAFE_ID.matches(rideId)) return rideId
            val crc = CRC32().also { it.update(rideId.encodeToByteArray()) }.value
            val prefix = rideId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(SANITIZED_PREFIX_LENGTH).ifEmpty { "ride" }
            return "$prefix-${crc.toString(HEX).padStart(CRC_DIGITS, '0')}"
        }

        private const val HEX = 16
        private const val CRC_DIGITS = 8
    }
}
