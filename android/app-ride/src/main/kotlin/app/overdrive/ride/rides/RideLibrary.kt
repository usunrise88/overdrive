package app.overdrive.ride.rides

import androidx.compose.runtime.Immutable
import app.overdrive.core.record.AsyncRideRecorder
import app.overdrive.core.record.NoRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecorder
import app.overdrive.data.rides.RideLogWriter
import app.overdrive.data.rides.RideStore
import app.overdrive.data.rides.StoreResult
import app.overdrive.data.rides.StoredRide
import app.overdrive.data.rides.StoredRideStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** How storing a finished ride went, as the finished screen shows it (ADR 0008). */
@Immutable
sealed interface SaveState {
    /** The record and the FIT file are being written. */
    data object Saving : SaveState

    /** Both are on disk. [ride] says where and how big. */
    data class Saved(
        val ride: StoredRide,
    ) : SaveState

    /** Nothing could be stored. [message] is plain words for the screen; the cause is in the log. */
    data class Failed(
        val message: String,
        /** Whether trying again can help (a full disk can be emptied). */
        val retryable: Boolean,
    ) : SaveState
}

/** What the Ready screen shows about rides the app found on disk that were cut off (ADR 0008). */
@Immutable
sealed interface RecoveryState {
    /** Nothing to offer. */
    data object None : RecoveryState

    /** The newest interrupted ride, and how many more wait behind it. The rider keeps or discards it. */
    data class Found(
        val ride: StoredRide,
        val more: Int,
    ) : RecoveryState

    /** The ride is being stored. */
    data class Saving(
        val ride: StoredRide,
        val more: Int,
    ) : RecoveryState

    /** It could not be stored; [retryable] says whether another try can help. */
    data class Failed(
        val ride: StoredRide,
        val more: Int,
        val retryable: Boolean,
    ) : RecoveryState
}

/** The interrupted ride the notice is about, or null when there is none. */
val RecoveryState.ride: StoredRide?
    get() =
        when (this) {
            RecoveryState.None -> null
            is RecoveryState.Found -> ride
            is RecoveryState.Saving -> ride
            is RecoveryState.Failed -> ride
        }

/** The disk side of one ride, as [app.overdrive.ride.host.RideHost] sees it. */
interface RideDiskLog {
    /** What the engine writes to: cheap and non-blocking, the disk work happens elsewhere. */
    val recorder: RideRecorder

    /** True once the ride is not being written to disk (a full disk, a missing folder); the ride itself goes on. */
    val failed: StateFlow<Boolean>

    /**
     * After the ride ended: waits for the log to be written out, then stores the finished [record] (JSON and FIT) and
     * removes the log. Can be called again after a failure. The work is not cancelled with the caller: a rider who
     * leaves the summary while it runs still gets the ride stored.
     */
    suspend fun finish(record: RideRecord): SaveState
}

/** Where rides go: the library on the tablet, or [None] where nothing is stored (previews and plain host tests). */
fun interface RidePersistence {
    /** The disk log of a ride that is about to be ridden, under [title]. Creates no file until the ride starts. */
    fun begin(title: String): RideDiskLog

    companion object {
        /** Keeps nothing: a ride ends in a failed save that cannot be retried. */
        val None: RidePersistence =
            RidePersistence {
                object : RideDiskLog {
                    override val recorder: RideRecorder = NoRideRecorder
                    override val failed: StateFlow<Boolean> = MutableStateFlow(false)

                    override suspend fun finish(record: RideRecord): SaveState =
                        SaveState.Failed("Rides are not stored in this build.", retryable = false)
                }
            }
    }
}

/**
 * The app's rides (spec §4.2, §4.8, ADR 0008): everything between the ride engine and the files of a [RideStore].
 *
 * - **While riding**, [begin] gives the host a recorder that queues every call and writes the crash-safe log on [scope]'s
 *   thread, so the engine never waits for the disk.
 * - **When the ride ends**, [RideDiskLog.finish] stores the record and the FIT file and updates [lastSaved].
 * - **On start**, [start] tidies the folder, finishes the exports that were cut off, deletes interrupted rides that are
 *   too short to matter and offers the others one by one through [recovery]: [saveRecovered] or [discardRecovered].
 *
 * [scope] must run on **one thread** (the app gives it `Dispatchers.IO.limitedParallelism(1)`): the store's calls about a
 * ride must not overlap, and this class keeps its lists without locks because only that thread touches them.
 *
 * @param utcOffsetSeconds the device's offset from UTC at a given time, for the FIT file's local time.
 * @param minimumRecoverableSeconds an interrupted ride shorter than this is deleted without asking (ADR 0008).
 * @param report where the cause of a disk problem is written down (the log, never the screen).
 * @param onSaved told about **every ride that is stored**, so the sync outbox can queue it (spec §4.9, ADR 0012): a ride
 * that has just finished, a recovered one the rider kept, one completed silently at start, and, at each start, every ride
 * already on disk (an outbox that has seen a ride ignores it, so this only catches a ride whose entry was lost between its
 * last rename and the outbox's append). Called on [scope]'s thread; it must not block, and what it throws is reported and
 * dropped, because a ride is never failed by its outbox.
 */
class RideLibrary(
    private val store: RideStore,
    private val scope: CoroutineScope,
    private val utcOffsetSeconds: (startEpochMillis: Long) -> Int = ::deviceUtcOffsetSeconds,
    private val minimumRecoverableSeconds: Int = MINIMUM_RECOVERABLE_SECONDS,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
    private val onSaved: (StoredRide) -> Unit = {},
) : RidePersistence {
    private val mutableLastSaved = MutableStateFlow<StoredRide?>(null)
    private val mutableRecovery = MutableStateFlow<RecoveryState>(RecoveryState.None)

    /** Interrupted rides waiting for the rider's decision, newest first. Touched on [scope]'s thread only. */
    private var pending: List<StoredRide> = emptyList()

    /** The ride saved last, for the Ready screen's "Last ride" row: the newest on disk at start, then each ride as it is saved. */
    val lastSaved: StateFlow<StoredRide?> = mutableLastSaved.asStateFlow()

    /** The interrupted ride the Ready screen asks about, if any. */
    val recovery: StateFlow<RecoveryState> = mutableRecovery.asStateFlow()

    /** The ride's FIT file, for share and save. May not exist; the caller checks. */
    fun fitFile(ride: StoredRide): File = store.fitFile(ride.id)

    /** The FIT file of the ride stored under [id]. */
    fun fitFile(id: String): File = store.fitFile(id)

    /** Scans the folder once, in the background. Safe to call again; each call rescans. */
    fun start() {
        scope.launch { scan() }
    }

    /**
     * Keeps the interrupted ride in the notice, which is [id]: recovers it, stores it like a finished ride and shows it as
     * the last ride. A call for a ride that is no longer the one in the notice (a second tap that arrived after the first
     * had finished) does nothing, so a double tap never saves the next ride by accident.
     */
    fun saveRecovered(id: String) {
        scope.launch {
            val ride = pending.firstOrNull()?.takeIf { it.id == id } ?: return@launch
            mutableRecovery.value = RecoveryState.Saving(ride, pending.size - 1)
            when (val result = store.finalizeStored(ride.id, utcOffsetSeconds)) {
                is StoreResult.Ok -> {
                    pending = pending.filterNot { it.id == ride.id }
                    mutableLastSaved.value = result.value
                    announce(result.value)
                    publishRecovery()
                }

                is StoreResult.Failed -> {
                    report("Recovering an interrupted ride failed", result.cause)
                    mutableRecovery.value = RecoveryState.Failed(ride, pending.size - 1, result.retryable)
                }
            }
        }
    }

    /** Deletes the interrupted ride in the notice, which is [id] (the screen has asked the rider to confirm). */
    fun discardRecovered(id: String) {
        scope.launch {
            val ride = pending.firstOrNull()?.takeIf { it.id == id } ?: return@launch
            if (!store.delete(
                    ride.id,
                )
            ) {
                report("An interrupted ride could not be deleted", IllegalStateException(ride.id))
            }
            pending = pending.filterNot { it.id == ride.id }
            publishRecovery()
        }
    }

    override fun begin(title: String): RideDiskLog = DiskLog(title)

    private fun scan() {
        val rides =
            try {
                store.scan()
            } catch (e: Exception) {
                report("Scanning the rides folder failed", e)
                return
            }
        val saved = rides.filter { it.status == StoredRideStatus.Saved }.toMutableList()
        // A ride that ended but was never exported: nothing to ask, finish it.
        for (ride in rides.filter { it.status == StoredRideStatus.Unsaved }) {
            when (val result = store.finalizeStored(ride.id, utcOffsetSeconds)) {
                is StoreResult.Ok -> saved += result.value
                is StoreResult.Failed -> report("Finishing a stored ride failed", result.cause)
            }
        }
        // Everything that is stored is offered to the outbox, oldest first: it queues what it has not seen.
        saved.sortedBy { it.startEpochMillis }.forEach(::announce)
        val interrupted = rides.filter { it.status == StoredRideStatus.Interrupted }
        for (ride in interrupted.filter { it.durationSeconds < minimumRecoverableSeconds }) store.delete(ride.id)
        pending =
            interrupted
                .filter {
                    it.durationSeconds >= minimumRecoverableSeconds
                }.sortedByDescending { it.startEpochMillis }
        mutableLastSaved.value = saved.maxByOrNull { it.startEpochMillis }
        publishRecovery()
    }

    /** Tells [onSaved] about a stored ride; what it throws is reported and dropped. */
    private fun announce(ride: StoredRide) {
        try {
            onSaved(ride)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Queueing a stored ride for upload failed", e)
        }
    }

    private fun publishRecovery() {
        mutableRecovery.value =
            pending.firstOrNull()?.let { RecoveryState.Found(it, pending.size - 1) } ?: RecoveryState.None
    }

    private inner class DiskLog(
        private val title: String,
    ) : RideDiskLog {
        private val writer = RideLogWriter(store, title, report)
        private val queue = AsyncRideRecorder(writer, scope)

        override val recorder: RideRecorder get() = queue
        override val failed: StateFlow<Boolean> get() = writer.failed

        override suspend fun finish(record: RideRecord): SaveState =
            scope
                .async {
                    // Everything the engine handed over is written, the `finished` line synced and the file closed.
                    queue.close()
                    when (val result = store.finalize(record, title, utcOffsetSeconds(record.ride.startEpochMillis))) {
                        is StoreResult.Ok -> {
                            mutableLastSaved.value = result.value
                            announce(result.value)
                            SaveState.Saved(result.value)
                        }

                        is StoreResult.Failed -> {
                            report("Storing a finished ride failed", result.cause)
                            SaveState.Failed(
                                if (result.retryable) {
                                    "The ride could not be saved. Free up some space on the tablet and try again."
                                } else {
                                    "This ride could not be saved as a FIT file."
                                },
                                result.retryable,
                            )
                        }
                    }
                }.await()
    }

    companion object {
        /** An interrupted ride shorter than this is not worth asking about (ADR 0008). */
        const val MINIMUM_RECOVERABLE_SECONDS: Int = 60
    }
}

/** The device's offset from UTC at [startEpochMillis], in seconds east: what the FIT file's local time needs. */
fun deviceUtcOffsetSeconds(startEpochMillis: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(startEpochMillis)).totalSeconds
