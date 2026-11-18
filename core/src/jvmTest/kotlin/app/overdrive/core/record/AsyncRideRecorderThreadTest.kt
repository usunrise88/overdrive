package app.overdrive.core.record

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A sink whose first `sync` blocks until the test lets it go: a stalled flash write. */
private class StallingSink : ByteSink {
    val inner = InMemoryByteStore()
    val stalled = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile
    var appendThread: Thread? = null

    override fun append(bytes: ByteArray) {
        appendThread = Thread.currentThread()
        inner.append(bytes)
    }

    override fun sync() {
        stalled.countDown()
        check(release.await(20, TimeUnit.SECONDS)) { "the test never released the sink" }
        inner.sync()
    }

    override fun close() = inner.close()
}

/**
 * Real threads, because the point is that a slow disk does not hold up the engine (spec §7.3): the sink blocks, the
 * producer must not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncRideRecorderThreadTest {
    @Test
    fun aStalledSinkDoesNotBlockTheProducerAndNothingIsLost() =
        runBlocking {
            val sink = StallingSink()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
            val recorder = AsyncRideRecorder(RideLogRecorder(sink, syncEverySamples = 1), scope)
            val record = SyntheticRide.record()
            try {
                recorder.onRideStarted(record.ride)
                // The consumer is now stuck in the header's fsync; the producer goes on regardless.
                assertTrue(sink.stalled.await(10, TimeUnit.SECONDS), "the writer reached the fsync")
                val rrBySecond = record.rrIntervals.groupBy { it.timeOffsetSeconds }
                withTimeout(10_000) {
                    for (sample in record.samples) {
                        recorder.onSample(sample)
                        rrBySecond[sample.timeOffsetSeconds]?.let(recorder::onRrIntervals)
                    }
                    recorder.onRideFinished(SYNTHETIC_START_MILLIS + 300_000L)
                }
                assertNotEquals(
                    Thread.currentThread(),
                    sink.appendThread,
                    "the writes are not on the producer's thread",
                )

                sink.release.countDown()
                withTimeout(10_000) { recorder.close() }
                assertEquals(record, RideLog.recover(sink.inner)?.toRecord())
                assertTrue(sink.inner.isClosed)
            } finally {
                sink.release.countDown()
                scope.cancel()
            }
        }
}
