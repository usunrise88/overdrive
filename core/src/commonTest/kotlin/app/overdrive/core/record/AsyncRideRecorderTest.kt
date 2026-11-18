package app.overdrive.core.record

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Writes down every call it gets, as a readable line. */
internal class CallLog(
    private val failOnSecond: Int = Int.MAX_VALUE,
) : RideRecorder {
    val calls = mutableListOf<String>()

    override fun onRideStarted(header: RideHeader) {
        calls += "started ${header.id}"
    }

    override fun onSample(sample: RideSample) {
        check(sample.timeOffsetSeconds != failOnSecond) { "boom at ${sample.timeOffsetSeconds}" }
        calls += "sample ${sample.timeOffsetSeconds}"
    }

    override fun onRrIntervals(intervals: List<RrInterval>) {
        calls += "rr ${intervals.joinToString { "${it.timeOffsetSeconds}:${it.intervalMs}" }}"
    }

    override fun onRideFinished(endEpochMillis: Long) {
        calls += "finished $endEpochMillis"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncRideRecorderTest {
    private val header = RideHeader(id = "a", startEpochMillis = SYNTHETIC_START_MILLIS, ftp = 250)

    private fun sample(second: Int) = RideSample(timeOffsetSeconds = second, power = 100 + second)

    private fun TestScope.scope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    @Test
    fun passesEveryCallOnInTheOrderItWasMade() =
        runTest {
            val log = CallLog()
            val recorder = AsyncRideRecorder(log, scope())
            recorder.onRideStarted(header)
            for (second in 1..5) {
                recorder.onSample(sample(second))
                if (second % 2 == 0) recorder.onRrIntervals(listOf(RrInterval(second, 800 + second)))
            }
            recorder.onRideFinished(1234)
            recorder.close()
            assertEquals(
                listOf(
                    "started a",
                    "sample 1",
                    "sample 2",
                    "rr 2:802",
                    "sample 3",
                    "sample 4",
                    "rr 4:804",
                    "sample 5",
                    "finished 1234",
                ),
                log.calls,
            )
        }

    @Test
    fun theCallerNeverRunsTheDelegate() =
        runTest {
            val log = CallLog()
            val recorder = AsyncRideRecorder(log, scope())
            recorder.onRideStarted(header)
            recorder.onSample(sample(1))
            assertTrue(log.calls.isEmpty(), "the calls are queued, the delegate has not run yet")
            runCurrent()
            assertEquals(listOf("started a", "sample 1"), log.calls)
            recorder.close()
        }

    @Test
    fun flushWaitsForWhatWasQueuedAndTheRecorderGoesOn() =
        runTest {
            val log = CallLog()
            val recorder = AsyncRideRecorder(log, scope())
            recorder.onRideStarted(header)
            recorder.onSample(sample(1))
            recorder.flush()
            assertEquals(listOf("started a", "sample 1"), log.calls)
            recorder.onSample(sample(2))
            recorder.flush()
            assertEquals(listOf("started a", "sample 1", "sample 2"), log.calls)
            recorder.close()
        }

    @Test
    fun closeDrainsTheQueueAndLaterCallsAreDropped() =
        runTest {
            val log = CallLog()
            val recorder = AsyncRideRecorder(log, scope())
            recorder.onRideStarted(header)
            recorder.onSample(sample(1))
            recorder.close()
            assertEquals(listOf("started a", "sample 1"), log.calls)
            recorder.onSample(sample(2))
            recorder.close()
            runCurrent()
            assertEquals(listOf("started a", "sample 1"), log.calls)
            recorder.flush()
        }

    @Test
    fun aRecorderThatWasNeverUsedCostsNothingAndClosesAtOnce() =
        runTest {
            val scope = scope()
            val recorder = AsyncRideRecorder(CallLog(), scope)
            runCurrent()
            recorder.close()
            recorder.flush()
            scope.cancel()
        }

    @Test
    fun anExceptionFromTheDelegateIsKeptAndTheNextCallsGoOn() =
        runTest {
            val log = CallLog(failOnSecond = 2)
            val recorder = AsyncRideRecorder(log, scope())
            assertNull(recorder.failure.value)
            recorder.onRideStarted(header)
            recorder.onSample(sample(1))
            recorder.onSample(sample(2))
            recorder.onSample(sample(3))
            recorder.close()
            assertEquals(listOf("started a", "sample 1", "sample 3"), log.calls)
            val failure = assertNotNull(recorder.failure.value)
            assertIs<IllegalStateException>(failure)
            assertEquals("boom at 2", failure.message)
        }

    @Test
    fun flushReturnsWhenTheScopeIsCancelled() =
        runTest {
            val scope = scope()
            val recorder = AsyncRideRecorder(CallLog(), scope)
            recorder.onRideStarted(header)
            scope.cancel()
            recorder.flush()
            recorder.close()
        }

    @Test
    fun theLogRecorderBehindItWritesTheSameBytesAsWithoutIt() =
        runTest {
            val direct = InMemoryByteStore()
            val queued = InMemoryByteStore()
            val a = RideLogRecorder(direct)
            val b = AsyncRideRecorder(RideLogRecorder(queued), scope())
            val record = SyntheticRide.record()
            val rrBySecond = record.rrIntervals.groupBy { it.timeOffsetSeconds }
            for (recorder in listOf<RideRecorder>(a, b)) {
                recorder.onRideStarted(record.ride)
                for (s in record.samples) {
                    recorder.onSample(s)
                    rrBySecond[s.timeOffsetSeconds]?.let(recorder::onRrIntervals)
                }
                recorder.onRideFinished(SYNTHETIC_START_MILLIS + 300_000L)
            }
            b.close()
            assertTrue(direct.readAll().contentEquals(queued.readAll()))
            assertEquals(record, RideLog.recover(queued)?.toRecord())
            assertTrue(queued.isClosed, "the sink was closed by the log recorder behind the queue")
        }
}
