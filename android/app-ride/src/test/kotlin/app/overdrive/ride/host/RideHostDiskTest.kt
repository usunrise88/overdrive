package app.overdrive.ride.host

import app.overdrive.core.record.ByteSink
import app.overdrive.core.record.FileByteSink
import app.overdrive.core.record.RideLog
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.rides.FitFileCheck
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DemoSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A file sink whose `sync` (fsync) blocks until the test lets it go: a flash write that takes seconds. */
private class StallingSink(
    private val real: ByteSink,
    private val stalled: CountDownLatch,
    private val release: CountDownLatch,
) : ByteSink by real {
    override fun sync() {
        stalled.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "the test never released the sink" }
        real.sync()
    }
}

/**
 * The real host, engine and simulated devices on their real threads, at 60 times speed, with a library that writes to a
 * real folder: what the virtual-time tests cannot show, that the disk is on another thread (spec §7.3, ADR 0008).
 */
class RideHostDiskTest {
    private val directory = RideFixtures.directory()
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var host: RideHost? = null

    @After
    fun cleanUp() {
        host?.close()
        diskScope.cancel()
        directory.deleteRecursively()
    }

    private fun await(
        what: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    @Test
    fun aStalledFsyncNeverDelaysTheEngine() {
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = RideStore(directory, openSink = { StallingSink(FileByteSink(it), stalled, release) })
        val library = RideLibrary(store, diskScope)
        val host =
            RideHost(
                SimulatedRideSessionFactory(),
                flowOf(AppSettings(ftp = 250, demoSpeed = DemoSpeed.Faster)),
                RideChoice.Free,
                persistence = library,
            ).also { this.host = it }
        await("the ride is ready") { host.snapshots.value.state == RideState.READY }
        host.actions.start()
        assertTrue("the writer reached its first fsync", stalled.await(10, TimeUnit.SECONDS))

        // The disk is stuck. The engine must not be: it rides on, a second of ride at a time.
        await("ten seconds of ride while the disk is stuck") { host.snapshots.value.elapsedSeconds >= 10 }
        assertEquals("the sink is still stuck", 1L, release.count)
        val onDisk = RideLog.recover(File(directory, "${rideIdOnDisk()}.ridelog").readBytes())!!
        assertEquals("nothing but the header has been written", 0, onDisk.samples.size)

        release.countDown()
        host.actions.finish()
        await("the ride is saved") { host.ui.value.finished?.save is SaveState.Saved }
        val finished = checkNotNull(host.ui.value.finished)
        val saved = (finished.save as SaveState.Saved).ride
        val json = RideRecordJson.decode(File(directory, "${saved.id}.json").readText())
        assertTrue(json.samples.size >= 10)
        assertEquals(
            "every second the engine rode reached the record",
            finished.summary.durationSeconds,
            json.samples.size,
        )
        FitFileCheck.verify(File(directory, "${saved.id}.fit").readBytes())
        assertFalse(directory.list().orEmpty().any { it.endsWith(".ridelog") })
    }

    private fun rideIdOnDisk(): String =
        directory.list().orEmpty().first { it.endsWith(".ridelog") }.removeSuffix(".ridelog")
}
