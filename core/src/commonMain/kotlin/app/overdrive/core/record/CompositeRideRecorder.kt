package app.overdrive.core.record

/**
 * Passes every call to each of [recorders] in order, so one ride can go to the crash-safe log and to memory at the
 * same time: `CompositeRideRecorder(RideLogRecorder(sink), InMemoryRideRecorder())`.
 *
 * If a recorder throws, the others still get the call and the first exception is rethrown afterwards, so one broken
 * recorder cannot starve the rest. ([RideLogRecorder] does not throw for a failing sink.)
 */
public class CompositeRideRecorder(
    private val recorders: List<RideRecorder>,
) : RideRecorder {
    public constructor(vararg recorders: RideRecorder) : this(recorders.toList())

    override fun onRideStarted(header: RideHeader): Unit = forEach { it.onRideStarted(header) }

    override fun onSample(sample: RideSample): Unit = forEach { it.onSample(sample) }

    override fun onRrIntervals(intervals: List<RrInterval>): Unit = forEach { it.onRrIntervals(intervals) }

    override fun onRideFinished(endEpochMillis: Long): Unit = forEach { it.onRideFinished(endEpochMillis) }

    private inline fun forEach(call: (RideRecorder) -> Unit) {
        var first: Exception? = null
        for (recorder in recorders) {
            try {
                call(recorder)
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        first?.let { throw it }
    }
}
