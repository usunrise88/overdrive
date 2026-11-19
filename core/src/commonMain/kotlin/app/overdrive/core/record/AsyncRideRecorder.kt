package app.overdrive.core.record

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hands every call to [delegate] from another coroutine, so that what the delegate does (a disk write, an `fsync`) can
 * never delay the caller, which is the ride engine (spec §7.3, ADR 0008). Calls go into an unbounded queue and one
 * consumer, running in [scope], passes them on **in the order they were made**, one at a time; [RideRecorder]'s
 * contract ("calls come in order, one at a time") therefore holds for the delegate too, whatever thread it ends up on.
 * Give it a scope on a single-threaded dispatcher for disk work.
 *
 * The engine's calls return at once and never block, however slow the delegate is. The queue grows while the delegate
 * is slow: a sample is a few hundred bytes a second, so a stalled disk costs memory in kilobytes, not time.
 *
 * Before the ride's files are read or finalised, call [close]: it waits until everything queued has reached the delegate
 * (and so the log has its `finished` line, synced and closed). [flush] waits the same way without ending the recorder.
 *
 * An exception from the delegate (a call out of order, a bug) does not end the consumer: the first one is kept in
 * [failure] and the next calls go on. [RideLogRecorder] does not throw for a failing sink; it reports it in its own
 * `failure`, which the owner of the delegate reads.
 *
 * The consumer starts with the first call, so a recorder that is created for a ride that never starts costs nothing,
 * and ends with [close] or when [scope] is cancelled. Calls after [close] are dropped.
 */
public class AsyncRideRecorder(
    private val delegate: RideRecorder,
    scope: CoroutineScope,
) : RideRecorder {
    private sealed interface Call {
        class Started(
            val header: RideHeader,
        ) : Call

        class Sample(
            val sample: RideSample,
        ) : Call

        class Rr(
            val intervals: List<RrInterval>,
        ) : Call

        class Finished(
            val endEpochMillis: Long,
        ) : Call

        class Event(
            val event: RideEvent,
        ) : Call

        class Barrier(
            val reached: CompletableDeferred<Unit>,
        ) : Call
    }

    private val queue = Channel<Call>(Channel.UNLIMITED)
    private val mutableFailure = MutableStateFlow<Exception?>(null)

    /** The first exception the delegate threw, or null. Safe to read from any thread. */
    public val failure: StateFlow<Exception?> = mutableFailure.asStateFlow()

    private val consumer: Job =
        scope.launch(start = CoroutineStart.LAZY) {
            for (call in queue) pass(call)
        }

    override fun onRideStarted(header: RideHeader) {
        enqueue(Call.Started(header))
    }

    override fun onSample(sample: RideSample) {
        enqueue(Call.Sample(sample))
    }

    override fun onRrIntervals(intervals: List<RrInterval>) {
        enqueue(Call.Rr(intervals))
    }

    override fun onRideFinished(endEpochMillis: Long) {
        enqueue(Call.Finished(endEpochMillis))
    }

    override fun onEvent(event: RideEvent) {
        enqueue(Call.Event(event))
    }

    /**
     * Suspends until every call made before this one has been passed to the delegate. Returns at once if the recorder
     * is closed or its scope cancelled. The recorder keeps working afterwards.
     */
    public suspend fun flush() {
        val reached = CompletableDeferred<Unit>()
        if (!queue.trySend(Call.Barrier(reached)).isSuccess) {
            consumer.join()
            return
        }
        consumer.start()
        val release = consumer.invokeOnCompletion { reached.complete(Unit) }
        try {
            reached.await()
        } finally {
            release.dispose()
        }
    }

    /**
     * Stops taking calls and suspends until everything already queued has been passed to the delegate. Safe to call more
     * than once; if no call was ever made it returns at once.
     */
    public suspend fun close() {
        queue.close()
        // A consumer that never started has nothing to drain; start it so it sees the closed, empty queue and ends.
        consumer.start()
        consumer.join()
    }

    private fun enqueue(call: Call) {
        if (queue.trySend(call).isSuccess) consumer.start()
    }

    private fun pass(call: Call) {
        try {
            when (call) {
                is Call.Started -> delegate.onRideStarted(call.header)
                is Call.Sample -> delegate.onSample(call.sample)
                is Call.Rr -> delegate.onRrIntervals(call.intervals)
                is Call.Finished -> delegate.onRideFinished(call.endEpochMillis)
                is Call.Event -> delegate.onEvent(call.event)
                is Call.Barrier -> call.reached.complete(Unit)
            }
        } catch (e: Exception) {
            if (mutableFailure.value == null) mutableFailure.value = e
        }
    }
}
