package app.overdrive.data.rides

/** Where a ride on disk stands (ADR 0008). */
enum class StoredRideStatus {
    /** The record and the FIT file are stored and no log is left: the ride is complete. */
    Saved,

    /** A log without its `finished` line that no ride in this process is writing: the app stopped during the ride. */
    Interrupted,

    /**
     * The ride ended (its log has the `finished` line, or its record is stored) but the record or the FIT file is
     * missing: the app stopped between the finish and the export. Finalising it completes the ride.
     */
    Unsaved,

    /** A ride this process is writing, or has just finished and is still storing. Never offered for recovery. */
    Recording,
}

/** One ride on disk, as the app's ride list and recovery see it. Nothing here is more than a few bytes. */
data class StoredRide(
    /** The ride's id made safe for file names; the key of every [RideStore] call. */
    val id: String,
    val title: String,
    val startEpochMillis: Long,
    /** Seconds of ride: the samples of the record, or of the intact part of a log. */
    val durationSeconds: Int,
    val status: StoredRideStatus,
    /** Size of the FIT file in bytes, or null while there is none. */
    val fitBytes: Long?,
    /**
     * The id of the workout the ride was made from (`ride.workoutId`), such as a planned workout's `icu-123` (spec §5.7), or
     * null for a free ride and for a ride stored before the id was kept in the small file beside it.
     */
    val workoutId: String? = null,
) {
    val hasFit: Boolean get() = fitBytes != null
    val isInterrupted: Boolean get() = status == StoredRideStatus.Interrupted
}

/** What a [RideStore] call that touches the disk gives back: a value, or the reason it could not be done. It never throws. */
sealed interface StoreResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : StoreResult<T>

    /**
     * The call failed. [cause] is for the log, not for the screen. A [retryable] failure may succeed later (storage was
     * full, a file was locked); one that is not never will (a record the FIT format cannot hold, nothing to recover).
     */
    data class Failed(
        val cause: Throwable,
        val retryable: Boolean = true,
    ) : StoreResult<Nothing>
}
