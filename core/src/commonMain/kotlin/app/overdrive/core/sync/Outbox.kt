package app.overdrive.core.sync

import app.overdrive.core.record.RideRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A finished ride waiting to be uploaded (spec §4.9: "Finished rides go into a persistent outbox, so Phase 2 uploads them
 * without touching ride code"). Just enough to name it in a list and to find its record: the ride's id, which is the file
 * stem of the stored record, and a few facts for a "waiting to upload" line. **No payload and no wire format**: what an
 * upload sends is Phase 2's (spec §8.3).
 */
public data class OutboxEntry(
    /** The ride record's id (`RideHeader.id`). Unique in an outbox. */
    val rideId: String,
    /** When the entry was queued, wall-clock milliseconds since the Unix epoch. */
    val enqueuedAtMillis: Long,
    /** The ride's start, wall-clock milliseconds since the Unix epoch. */
    val startedAtMillis: Long,
    /** The ride's length in seconds. */
    val durationSeconds: Int,
    /** The workout's name or the like, for display; null for a free ride. */
    val title: String? = null,
) {
    init {
        require(rideId.isNotEmpty()) { "An outbox entry needs a ride id" }
    }

    public companion object {
        /** The entry for a finished [record], queued at [enqueuedAtMillis]. */
        public fun of(
            record: RideRecord,
            enqueuedAtMillis: Long,
            title: String? = null,
        ): OutboxEntry =
            OutboxEntry(
                rideId = record.ride.id,
                enqueuedAtMillis = enqueuedAtMillis,
                startedAtMillis = record.ride.startEpochMillis,
                durationSeconds = record.summary.durationSeconds,
                title = title,
            )
    }
}

/**
 * Where finished rides wait for an upload (spec §4.9). [InMemoryOutbox] is the tests' stand-in; **the persistent one, on the tablet's
 * disk, is `FileOutbox` in `android/data` (ADR 0012)**, and it survives the app being killed: an entry is removed only by
 * [markDone]. Functions may suspend for disk and may throw for a disk that fails; the [SyncCoordinator] catches that and
 * reports it, so a ride is never failed by its outbox.
 */
public interface Outbox {
    /** Queues [entry]. A second entry for the same [OutboxEntry.rideId] replaces nothing and adds nothing (idempotent). */
    public suspend fun enqueue(entry: OutboxEntry)

    /** Everything not yet [markDone], oldest first (by [OutboxEntry.enqueuedAtMillis], then arrival). */
    public suspend fun pending(): List<OutboxEntry>

    /** The ride was uploaded: forget it. Does nothing for an unknown id. */
    public suspend fun markDone(rideId: String)
}

/** An [Outbox] in memory: the stage's stand-in, and what tests use. Safe from any coroutine. Loses everything with the process. */
public class InMemoryOutbox : Outbox {
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, OutboxEntry>()

    override suspend fun enqueue(entry: OutboxEntry) {
        lock.withLock { if (entry.rideId !in entries) entries[entry.rideId] = entry }
    }

    override suspend fun pending(): List<OutboxEntry> =
        lock.withLock { entries.values.sortedBy { it.enqueuedAtMillis } }

    override suspend fun markDone(rideId: String) {
        lock.withLock { entries.remove(rideId) }
    }
}
