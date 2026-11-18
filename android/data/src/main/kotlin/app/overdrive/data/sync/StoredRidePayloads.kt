package app.overdrive.data.sync

import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.RidePayload
import app.overdrive.core.sync.RidePayloads
import app.overdrive.core.sync.RideUnavailableException
import app.overdrive.data.rides.RideStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What an upload reads: the stored ride of an outbox entry, **as the tablet already keeps it** (ADR 0008): `<id>.json`, the
 * ride record in the wire format, and `<id>.fit`, the FIT file generated from it when the ride was stored. Both are sent as
 * they are on disk, streamed ([FileBody]); the outbox entry stays free of payload (ADR 0011).
 *
 * The record is read once, to learn the ride's own id (`ride.id`, which the service keys it by and checks the record
 * against; the entry holds the file-safe form of it). A ride whose files are gone, damaged or larger than the service accepts
 * is a [RideUnavailableException]: its own, permanent problem, which the provider turns into a refusal. A disk that cannot be
 * read for now throws an `IOException`, and the upload is tried again later.
 */
class StoredRidePayloads(
    private val store: RideStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RidePayloads {
    override suspend fun payload(entry: OutboxEntry): RidePayload =
        withContext(dispatcher) {
            val id = RideStore.idFor(entry.rideId)
            val recordFile = store.recordFile(id)
            val fitFile = store.fitFile(id)
            if (!recordFile.isFile || !fitFile.isFile) {
                throw RideUnavailableException("The ride's files are no longer on this tablet")
            }
            if (recordFile.length() > MAX_RECORD_BYTES || fitFile.length() > MAX_FIT_BYTES) {
                throw RideUnavailableException("The ride is larger than the service accepts")
            }
            val rideId =
                try {
                    RideRecordJson.decode(recordFile.readText()).ride.id
                } catch (e: IllegalArgumentException) {
                    throw RideUnavailableException("The ride's record on this tablet is damaged")
                }
            RidePayload(rideId, FileBody(recordFile), FileBody(fitFile))
        }

    companion object {
        /** The service's limits (ADR 0014 section 7): 16 MiB for the record, 8 MiB for the FIT. Larger is refused there anyway. */
        const val MAX_RECORD_BYTES = 16L * 1024 * 1024
        const val MAX_FIT_BYTES = 8L * 1024 * 1024
    }
}

/**
 * When an upload last succeeded, kept in one small file next to the outbox so that Settings still says so after the app was
 * stopped. Just a number of wall-clock milliseconds; a missing or unreadable file reads as "never". Written through a
 * temporary file and an atomic rename, so it is never half there.
 */
class LastUploadFile(
    private val file: File,
) {
    fun read(): Long? =
        try {
            file
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: IOException) {
            null
        }

    /** @throws IOException when the disk refuses; the caller reports it and moves on, nothing depends on it. */
    fun write(millis: Long) {
        file.absoluteFile.parentFile?.mkdirs()
        val temporary = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            temporary.writeText(millis.toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temporary.delete()
            throw e
        }
    }
}
