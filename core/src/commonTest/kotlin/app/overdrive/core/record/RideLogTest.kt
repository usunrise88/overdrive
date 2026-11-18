package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for `java.io.IOException`, which common test code cannot name. */
internal class TestIoException(
    message: String,
) : Exception(message)

/** A sink that fails when it is told to, after writing some of the failing append, as a killed process or a full disk would. */
internal class ScriptedSink(
    private val failOnAppend: Int = Int.MAX_VALUE,
    /** How many bytes of the failing append reach the file before it throws. */
    private val tornBytes: Int = 0,
    private val failOnSync: Int = Int.MAX_VALUE,
    private val failOnClose: Boolean = false,
) : ByteSink,
    ByteSource {
    val store = InMemoryByteStore()
    var appends = 0
        private set
    var syncs = 0
        private set
    var closed = false
        private set

    override fun append(bytes: ByteArray) {
        appends++
        if (appends >= failOnAppend) {
            if (tornBytes > 0) store.append(bytes.copyOf(minOf(tornBytes, bytes.size)))
            throw TestIoException("No space left on device")
        }
        store.append(bytes)
    }

    override fun sync() {
        syncs++
        if (syncs >= failOnSync) throw TestIoException("fsync failed")
    }

    override fun close() {
        closed = true
        if (failOnClose) throw TestIoException("close failed")
    }

    override fun readAll(): ByteArray = store.readAll()
}

private fun lines(bytes: ByteArray): List<String> = bytes.decodeToString().split('\n').dropLast(1)

class RideLogTest {
    private val header = RideHeader("g", 1_794_996_000_000L, ftp = 250, devices = listOf("Trainer"))
    private val first = RideSample(1, power = 180, heartRate = 121, cadence = 90, speedKmh = 31.2, distanceMeters = 8.7)
    private val second = RideSample(2, speedKmh = 0.0, distanceMeters = 8.7, paused = true)

    private fun record(
        sink: ByteSink,
        finish: Boolean = true,
    ): RideLogRecorder {
        val recorder = RideLogRecorder(sink)
        recorder.onRideStarted(header)
        recorder.onSample(first)
        recorder.onRrIntervals(listOf(RrInterval(1, 812)))
        recorder.onSample(second)
        if (finish) recorder.onRideFinished(1_794_996_002_000L)
        return recorder
    }

    @Test
    fun `the log is the documented lines with their checksums`() {
        val store = InMemoryByteStore()
        record(store)
        // The checksums were computed outside Kotlin (Python zlib.crc32 over the text after the checksum).
        assertEquals(
            listOf(
                """9bfe7ecc header {"schema":1,"ride":{"id":"g","startEpochMillis":1794996000000,"ftp":250,"devices":["Trainer"]}}""",
                """f2064d88 sample {"timeOffsetSeconds":1,"power":180,"heartRate":121,"cadence":90,"speedKmh":31.2,"distanceMeters":8.7,"paused":false}""",
                """e62575d4 rr [{"timeOffsetSeconds":1,"intervalMs":812}]""",
                """84a40202 sample {"timeOffsetSeconds":2,"speedKmh":0.0,"distanceMeters":8.7,"paused":true}""",
                """8e127e54 finished {"endEpochMillis":1794996002000}""",
            ),
            lines(store.readAll()),
        )
        assertEquals('\n'.code.toByte(), store.readAll().last())
    }

    @Test
    fun `a finished ride is recovered whole`() {
        val store = InMemoryByteStore()
        record(store)
        val recovered = assertNotNull(RideLog.recover(store))
        assertEquals(header, recovered.header)
        assertEquals(listOf(first, second), recovered.samples)
        assertEquals(listOf(RrInterval(1, 812)), recovered.rrIntervals)
        assertEquals(1_794_996_002_000L, recovered.endEpochMillis)
        assertEquals(RideLogStatus.FINISHED, recovered.status)
        assertTrue(recovered.isFinished)
        assertEquals(store.size, recovered.validBytes)
        assertEquals(0, recovered.discardedBytes)
        val ride = recovered.toRecord()
        assertEquals(1_794_996_002_000L, ride.ride.endEpochMillis)
        assertEquals(2, ride.summary.durationSeconds)
    }

    @Test
    fun `an interrupted ride is recovered up to its last whole second, with an estimated end`() {
        val store = InMemoryByteStore()
        record(store, finish = false)
        val recovered = assertNotNull(RideLog.recover(store))
        assertEquals(RideLogStatus.INTERRUPTED, recovered.status)
        assertFalse(recovered.isFinished)
        assertNull(recovered.endEpochMillis)
        assertEquals(2, recovered.samples.size)
        // Two seconds were recorded, so the ride is taken to have ended two seconds after it started.
        assertEquals(1_794_996_002_000L, recovered.toRecord().ride.endEpochMillis)
    }

    @Test
    fun `a ride that stopped right after its header has no samples but is still a ride`() {
        val store = InMemoryByteStore()
        RideLogRecorder(store).onRideStarted(header)
        val recovered = assertNotNull(RideLog.recover(store))
        assertEquals(emptyList(), recovered.samples)
        assertEquals(RideLogStatus.INTERRUPTED, recovered.status)
        assertEquals(0, recovered.toRecord().summary.durationSeconds)
    }

    @Test
    fun `nothing to recover is null, whatever the bytes`() {
        assertNull(RideLog.recover(ByteArray(0)))
        assertNull(RideLog.recover("garbage without a newline".encodeToByteArray()))
        assertNull(RideLog.recover("garbage\nmore garbage\n".encodeToByteArray()))
        assertNull(RideLog.recover(ByteArray(500)))
        assertNull(RideLog.recover(ByteArray(500) { '\n'.code.toByte() }))
        // A log whose first intact line is a sample, not the header, is not a ride log.
        val sampleOnly = RideLogFormat.sample(first)
        assertNull(RideLog.recover(sampleOnly))
        // A header of another schema is not ours to read.
        val future =
            RideLogFormat
                .header(
                    header,
                ).decodeToString()
                .replace("\"schema\":1", "\"schema\":2")
                .encodeToByteArray()
        assertNull(RideLog.recover(future))
    }

    @Test
    fun `a log with its samples out of order ends where the order breaks`() {
        val bytes =
            RideLogFormat.header(header) + RideLogFormat.sample(first) +
                RideLogFormat.sample(RideSample(3)) + RideLogFormat.sample(second)
        val recovered = assertNotNull(RideLog.recover(bytes))
        assertEquals(listOf(first), recovered.samples)
        assertEquals(
            RideLogFormat.sample(RideSample(3)).size + RideLogFormat.sample(second).size,
            recovered.discardedBytes,
        )
    }

    @Test
    fun `nothing after the finished line is read`() {
        val store = InMemoryByteStore()
        record(store)
        val extra = RideLogFormat.sample(RideSample(3))
        val recovered = assertNotNull(RideLog.recover(store.readAll() + extra))
        assertEquals(2, recovered.samples.size)
        assertEquals(extra.size, recovered.discardedBytes)
        assertTrue(recovered.isFinished)
    }

    @Test
    fun `a line that passes its checksum but is not what it claims ends the log`() {
        val good = RideLogFormat.header(header) + RideLogFormat.sample(first)
        // A well-checksummed line of an unknown kind, and one whose JSON is nonsense.
        for (body in listOf(
            "lap {}",
            "sample {\"timeOffsetSeconds\":2,\"power\":-5}",
            "sample not json",
            "rr []x",
            "sample",
        )) {
            val bytes = good + lineWithValidChecksum(body) + RideLogFormat.sample(second)
            val recovered = assertNotNull(RideLog.recover(bytes), body)
            assertEquals(listOf(first), recovered.samples, body)
        }
    }

    @Test
    fun `text with quotes, unicode and line breaks does not break the framing`() {
        val odd =
            RideHeader(
                id = "ride \"1\"\n",
                startEpochMillis = 5,
                workoutId = "w é🚴",
                ftp = 200,
                devices = listOf("Zwift Click\r\n", "Ж"),
            )
        val store = InMemoryByteStore()
        val recorder = RideLogRecorder(store)
        recorder.onRideStarted(odd)
        recorder.onSample(RideSample(1))
        recorder.onRideFinished(6_000)
        assertEquals(3, store.readAll().count { it == '\n'.code.toByte() }) // header, sample, finished
        assertEquals(odd, RideLog.recover(store)!!.header)
    }

    @Test
    fun `an empty RR list writes nothing`() {
        val store = InMemoryByteStore()
        val recorder = RideLogRecorder(store)
        recorder.onRideStarted(header)
        val before = store.size
        recorder.onRrIntervals(emptyList())
        assertEquals(before, store.size)
    }

    @Test
    fun `the recorder syncs after the header, every few samples and at the end, then closes`() {
        val sink = ScriptedSink()
        val recorder = RideLogRecorder(sink, syncEverySamples = 3)
        recorder.onRideStarted(header)
        assertEquals(1, sink.syncs)
        for (second in 1..7) recorder.onSample(RideSample(second))
        assertEquals(1 + 2, sink.syncs) // after samples 3 and 6
        recorder.onRideFinished(7_000)
        assertEquals(4, sink.syncs)
        assertTrue(sink.closed)
        assertFailsWith<IllegalArgumentException> { RideLogRecorder(sink, syncEverySamples = 0) }
        assertEquals(5, RideLogRecorder.DEFAULT_SYNC_EVERY_SAMPLES)
    }

    @Test
    fun `calls out of order are programming errors, as in the in-memory recorder`() {
        val recorder = RideLogRecorder(InMemoryByteStore())
        assertFailsWith<IllegalStateException> { recorder.onSample(first) }
        assertFailsWith<IllegalStateException> { recorder.onRrIntervals(emptyList()) }
        assertFailsWith<IllegalStateException> { recorder.onRideFinished(1) }
        recorder.onRideStarted(header)
        assertFailsWith<IllegalStateException> { recorder.onRideStarted(header) }
        recorder.onRideFinished(1_000)
        recorder.onRideFinished(2_000) // a second finish is ignored
        assertFailsWith<IllegalStateException> { recorder.onSample(first) }
    }

    @Test
    fun `a full disk does not stop the ride and leaves a clean prefix`() {
        val sink = ScriptedSink(failOnAppend = 4, tornBytes = 30)
        val recorder = RideLogRecorder(sink)
        recorder.onRideStarted(header) // append 1
        recorder.onSample(first) // 2
        recorder.onRrIntervals(listOf(RrInterval(1, 812))) // 3
        assertNull(recorder.failure)
        recorder.onSample(second) // 4: torn, throws inside the sink
        assertIs<TestIoException>(recorder.failure)
        // The ride carries on as far as the recorder is concerned; nothing more is written, nothing is thrown.
        val written = sink.store.size
        for (s in 3..10) recorder.onSample(RideSample(s))
        recorder.onRrIntervals(listOf(RrInterval(9, 700)))
        recorder.onRideFinished(10_000)
        assertEquals(written, sink.store.size)
        assertTrue(sink.closed)
        val recovered = assertNotNull(RideLog.recover(sink))
        assertEquals(listOf(first), recovered.samples)
        assertEquals(RideLogStatus.INTERRUPTED, recovered.status)
        assertEquals(30, recovered.discardedBytes) // the torn start of the second sample
    }

    @Test
    fun `a failing sync or close is kept, not thrown`() {
        val syncFails = ScriptedSink(failOnSync = 1)
        val recorder = RideLogRecorder(syncFails)
        recorder.onRideStarted(header)
        assertIs<TestIoException>(recorder.failure)
        recorder.onSample(first)
        assertEquals(1, syncFails.appends) // only the header: after a failure nothing more is written

        val closeFails = ScriptedSink(failOnClose = true)
        val other = RideLogRecorder(closeFails)
        other.onRideStarted(header)
        other.onRideFinished(1_000)
        assertIs<TestIoException>(other.failure)
        assertTrue(RideLog.recover(closeFails)!!.isFinished)
    }

    @Test
    fun `a composite feeds every recorder and survives one that throws`() {
        val memory = InMemoryRideRecorder()
        val log = InMemoryByteStore()
        val broken =
            object : RideRecorder {
                override fun onRideStarted(header: RideHeader): Unit = error("broken")

                override fun onSample(sample: RideSample): Unit = error("broken")

                override fun onRrIntervals(intervals: List<RrInterval>): Unit = error("broken")

                override fun onRideFinished(endEpochMillis: Long): Unit = error("broken")
            }
        val composite = CompositeRideRecorder(broken, RideLogRecorder(log), memory)
        assertFailsWith<IllegalStateException> { composite.onRideStarted(header) }
        assertFailsWith<IllegalStateException> { composite.onSample(first) }
        assertFailsWith<IllegalStateException> { composite.onRrIntervals(listOf(RrInterval(1, 800))) }
        assertFailsWith<IllegalStateException> { composite.onRideFinished(2_000) }
        assertEquals(listOf(first), memory.record!!.samples)
        assertTrue(memory.isFinished)
        assertEquals(listOf(first), RideLog.recover(log)!!.samples)
        // The list form takes the same recorders.
        val alone = InMemoryRideRecorder()
        CompositeRideRecorder(listOf(alone)).onRideStarted(header)
        assertEquals(header, alone.record!!.ride)
    }

    @Test
    fun `the whole synthetic ride survives the log, bit for bit`() {
        val live = SyntheticRide.record()
        val store = InMemoryByteStore()
        val memory = InMemoryRideRecorder()
        val recorder = CompositeRideRecorder(RideLogRecorder(store), memory)
        recorder.onRideStarted(live.ride.copy(endEpochMillis = null))
        val byOffset = live.rrIntervals.groupBy { it.timeOffsetSeconds }
        for (sample in live.samples) {
            recorder.onSample(sample)
            byOffset[sample.timeOffsetSeconds]?.let(recorder::onRrIntervals)
        }
        recorder.onRideFinished(live.ride.endEpochMillis!!)
        assertEquals(live, memory.record)
        assertEquals(live, RideLog.recover(store)!!.toRecord())
        // One header, a line per second, a line per second that had RR intervals, and the end.
        assertEquals(1 + 300 + byOffset.size + 1, lines(store.readAll()).size)
    }

    /** A line with the right checksum around [body] (`kind payload`), which only its content can fault. */
    private fun lineWithValidChecksum(body: String): ByteArray {
        val bytes = body.encodeToByteArray()
        val crc = Crc32.of(bytes).toString(16).padStart(8, '0')
        return "$crc $body\n".encodeToByteArray()
    }
}
