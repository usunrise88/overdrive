package app.overdrive.data.rides

import app.overdrive.core.record.RideEvent
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideLogRecorder
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ride engine's recorder that writes the crash-safe log into a [RideStore] (ADR 0007, 0008): when the ride starts it
 * opens `<id>.ridelog` there and then forwards every call to a `RideLogRecorder` over that file. It opens the file itself,
 * on whichever thread calls it, so wrap it in an `AsyncRideRecorder`: the engine then never waits for the file to be
 * created, written or flushed.
 *
 * **It never throws for a disk problem.** If the file cannot be opened, or a write fails later, [failed] turns true, the
 * cause goes to [report] (for the log, never the screen) and the calls that follow are ignored: the file stays a clean
 * prefix of the ride and the ride itself goes on, from memory (spec §4.2).
 *
 * @param report where the cause of a failure is written down: a short note and the exception. Called at most once.
 */
class RideLogWriter(
    private val store: RideStore,
    private val title: String,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
) : RideRecorder {
    private var log: RideLogRecorder? = null
    private var reported = false
    private val mutableFailed = MutableStateFlow(false)

    /** True once the ride is not (or no longer) being written to disk. */
    val failed: StateFlow<Boolean> = mutableFailed.asStateFlow()

    override fun onRideStarted(header: RideHeader) {
        when (val opened = store.openLog(header, title)) {
            is StoreResult.Ok -> log = RideLogRecorder(opened.value).also { it.onRideStarted(header) }
            is StoreResult.Failed -> fail("The ride log could not be opened", opened.cause)
        }
        update()
    }

    override fun onSample(sample: RideSample) {
        log?.onSample(sample)
        update()
    }

    override fun onRrIntervals(intervals: List<RrInterval>) {
        log?.onRrIntervals(intervals)
        update()
    }

    override fun onEvent(event: RideEvent) {
        log?.onEvent(event)
        update()
    }

    override fun onRideFinished(endEpochMillis: Long) {
        log?.onRideFinished(endEpochMillis)
        update()
    }

    private fun update() {
        val cause = log?.failure
        if (cause != null) fail("Writing the ride log failed", cause)
    }

    private fun fail(
        note: String,
        cause: Throwable,
    ) {
        mutableFailed.value = true
        if (!reported) {
            reported = true
            report(note, cause)
        }
    }
}
