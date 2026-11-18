package app.overdrive.core.record

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The deterministic ride the child process of the kill test writes, which the test regenerates to compare. */
internal object ChildRide {
    val header = RideHeader(id = "child", startEpochMillis = SYNTHETIC_START_MILLIS, ftp = 250)

    fun sample(second: Int) =
        RideSample(
            timeOffsetSeconds = second,
            power = 100 + second % 200,
            heartRate = 90 + second % 80,
            cadence = 80 + second % 20,
            speedKmh = 30.0,
            distanceMeters = second * 8.25,
            stepIndex = second / 50,
        )
}

/**
 * A process that writes a ride log as fast as it can, as the ride engine does one line a second (see
 * [FileByteStorageTest]): it never finishes, and the test kills it.
 */
object RideLogChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val recorder = RideLogRecorder(FileByteSink(File(args[0])), syncEverySamples = 500)
        recorder.onRideStarted(ChildRide.header)
        for (second in 1..200_000) recorder.onSample(ChildRide.sample(second))
        Thread.sleep(60_000)
    }
}

/** The ride log on a real file (`FileByteSink`, `FileByteSource`), including a real SIGKILL. */
class FileByteStorageTest {
    private val directory: File = Files.createTempDirectory("overdrive-ridelog").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a ride recorded to a file is recovered from it`() {
        val file = File(directory, "nested/dirs/ride-1.ridelog")
        val live = InMemoryRideRecorder()
        val recorder = CompositeRideRecorder(RideLogRecorder(FileByteSink(file)), live)
        val ride = SyntheticRide.record()
        recorder.onRideStarted(ride.ride.copy(endEpochMillis = null))
        val rr = ride.rrIntervals.groupBy { it.timeOffsetSeconds }
        for (sample in ride.samples) {
            recorder.onSample(sample)
            rr[sample.timeOffsetSeconds]?.let(recorder::onRrIntervals)
        }
        recorder.onRideFinished(ride.ride.endEpochMillis!!)
        val recovered = assertNotNull(RideLog.recover(FileByteSource(file)))
        assertEquals(ride, recovered.toRecord())
        assertEquals(live.record, recovered.toRecord())
        assertTrue(recovered.isFinished)
        assertEquals(file.length().toInt(), recovered.validBytes)
    }

    @Test
    fun `a file cut at the end of any byte of its last lines recovers its earlier seconds`() {
        val file = File(directory, "cut.ridelog")
        val recorder = RideLogRecorder(FileByteSink(file))
        recorder.onRideStarted(ChildRide.header)
        for (second in 1..20) recorder.onSample(ChildRide.sample(second))
        val whole = file.length()
        for (cut in whole - 300..whole) {
            val copy = File(directory, "copy-$cut.ridelog")
            file.copyTo(copy)
            RandomAccessFile(copy, "rw").use { it.setLength(cut) }
            val recovered = assertNotNull(RideLog.recover(FileByteSource(copy)))
            assertEquals((1..recovered.samples.size).map(ChildRide::sample), recovered.samples)
            assertTrue(recovered.samples.size >= 16, "a cut within the last 300 bytes loses a few seconds at most")
        }
    }

    @Test
    fun `opening the sink again appends, it never truncates`() {
        val file = File(directory, "append.ridelog")
        FileByteSink(file).also {
            it.append("one\n".encodeToByteArray())
            it.close()
        }
        FileByteSink(file).also {
            it.append("two\n".encodeToByteArray())
            it.sync()
            it.close()
        }
        assertEquals("one\ntwo\n", FileByteSource(file).readAll().decodeToString())
        FileByteSink(file, append = false).close()
        assertEquals(0, file.length())
    }

    @Test
    fun `a sink that cannot open its file says so when it is made`() {
        assertFailsWith<IOException> { FileByteSink(directory) } // a directory is not a file
    }

    @Test
    fun `a process killed with SIGKILL while it writes leaves a log that recovers as a prefix of its ride`() {
        val file = File(directory, "killed.ridelog")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), RideLogChild::class.java.name, file.path)
                .redirectErrorStream(true)
                .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (file.length() < 200_000 && process.isAlive && System.nanoTime() < deadline) Thread.sleep(5)
            if (file.length() < 200_000) {
                fail("the child did not get going: alive=${process.isAlive}, log of ${file.length()} bytes")
            }
        } finally {
            process.destroyForcibly() // SIGKILL: no shutdown hooks, no flushing, whatever it was in the middle of
            process.waitFor()
        }
        val recovered = assertNotNull(RideLog.recover(FileByteSource(file)))
        assertEquals(RideLogStatus.INTERRUPTED, recovered.status)
        assertTrue(recovered.samples.size > 500)
        assertEquals((1..recovered.samples.size).map(ChildRide::sample), recovered.samples)
        // At most the line in progress is lost: a line is about 150 bytes, and the file ends there.
        assertTrue(recovered.discardedBytes < 400, "discarded ${recovered.discardedBytes} bytes")
        assertEquals(file.length().toInt(), recovered.validBytes + recovered.discardedBytes)
    }
}
