package app.overdrive.core.record

/**
 * Where the ride engine writes the ride as it happens (spec §4.2, §8.2). The engine calls it in this order:
 * [onRideStarted] once, then every second [onSample] and, when the strap sent some, [onRrIntervals], and
 * finally [onRideFinished]. A ride that is interrupted simply stops calling it.
 *
 * Two implementations are planned: [InMemoryRideRecorder], which is all the engine needs, and the database
 * writer of stage M2b, which appends every sample to disk as it arrives so that an interrupted ride can be
 * recovered (spec §4.2 "Crash safety"). The latter can hand the samples it reads back to
 * [RideRecordBuilder.recover].
 *
 * **Threading.** Calls come from the engine's own coroutine, once a second, in order. An implementation must
 * not block for long (do the disk write on another dispatcher) and need not be thread-safe against the engine.
 * Exceptions are the implementation's to handle: one that escapes ends the ride engine's coroutine.
 */
public interface RideRecorder {
    /** The ride starts. [header] has the start time and no end time. */
    public fun onRideStarted(header: RideHeader)

    /** One second of the ride. Offsets run 1, 2, 3, … without gaps, including while paused. */
    public fun onSample(sample: RideSample)

    /** RR intervals received since the last sample, oldest first, already stamped with the second they arrived in. */
    public fun onRrIntervals(intervals: List<RrInterval>)

    /** The ride is over, by the rider's choice or because the workout ended, at [endEpochMillis]. */
    public fun onRideFinished(endEpochMillis: Long)

    /**
     * Something worth telling the coach happened (spec §11.1): called right after the [onSample] of the tick it was decided
     * at, in the order the events happened. A recorder that keeps no events can ignore it, which is the default; a ride
     * that never had any never calls it.
     */
    public fun onEvent(event: RideEvent) {}
}

/** Records nothing, for a ride that is not kept. */
public object NoRideRecorder : RideRecorder {
    override fun onRideStarted(header: RideHeader) {}

    override fun onSample(sample: RideSample) {}

    override fun onRrIntervals(intervals: List<RrInterval>) {}

    override fun onRideFinished(endEpochMillis: Long) {}
}

/**
 * Keeps the whole ride in memory as a [RideRecord], up to date after every call. A ride of three hours is about
 * ten thousand samples, a few hundred kilobytes.
 *
 * Not thread-safe: read [record] from the thread that drives the engine, or after the ride has finished.
 */
public class InMemoryRideRecorder : RideRecorder {
    private var builder: RideRecordBuilder? = null
    private var endEpochMillis: Long? = null

    /** The ride so far, or null before it started. Once [isFinished] it is the final record. */
    public val record: RideRecord? get() = builder?.build(endEpochMillis)

    /** Whether [onRideFinished] has been called. */
    public val isFinished: Boolean get() = endEpochMillis != null

    override fun onRideStarted(header: RideHeader) {
        check(builder == null) { "The ride was already started" }
        builder = RideRecordBuilder(header)
    }

    override fun onSample(sample: RideSample) {
        checkNotNull(builder) { "The ride has not started" }.addSample(sample)
    }

    override fun onRrIntervals(intervals: List<RrInterval>) {
        checkNotNull(builder) { "The ride has not started" }.addRrIntervals(intervals)
    }

    override fun onEvent(event: RideEvent) {
        checkNotNull(builder) { "The ride has not started" }.addEvent(event)
    }

    override fun onRideFinished(endEpochMillis: Long) {
        check(builder != null) { "The ride has not started" }
        this.endEpochMillis = endEpochMillis
    }
}
