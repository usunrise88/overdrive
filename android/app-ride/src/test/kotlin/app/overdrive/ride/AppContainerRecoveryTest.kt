package app.overdrive.ride

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.record.CompositeRideRecorder
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
import app.overdrive.core.ride.RideState
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.RideRequest
import app.overdrive.ride.host.RideSessionFactory
import app.overdrive.ride.host.RideSessionHandle
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.rides.FitFileCheck
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DemoSpeed
import app.overdrive.ride.settings.DeviceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Keeps a thread-safe copy of the samples the engine records. */
private class SampleCapture : RideRecorder {
    val samples = java.util.Collections.synchronizedList(mutableListOf<RideSample>())

    override fun onRideStarted(header: RideHeader) = Unit

    override fun onSample(sample: RideSample) {
        samples += sample
    }

    override fun onRrIntervals(intervals: List<RrInterval>) = Unit

    override fun onRideFinished(endEpochMillis: Long) = Unit
}

private class CapturingFactory(
    private val capture: SampleCapture,
) : RideSessionFactory {
    private val inner = SimulatedRideSessionFactory()

    override fun create(request: RideRequest): RideSessionHandle =
        inner.create(
            RideRequest(
                request.scope,
                request.workout,
                request.settings,
                CompositeRideRecorder(request.recorder, capture),
                request.rideNumber,
            ),
        )
}

/**
 * Acceptance criterion 5 without a device (spec §4.10, ADR 0008): a ride is cut off in the middle (the process "dies": its
 * threads stop and nothing is finished, the files stay), and the next start, a new [AppContainer] over the same folder,
 * finds it, recovers it, and then rides and stores a whole new ride the same way. Real threads, the simulated bike at
 * 60 times speed, a real folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun names() = directory.list().orEmpty().sorted()

    @Test
    fun aRideCutOffMidWayIsRecoveredByTheNextContainerAndThenANewRideIsStored() {
        // ---- The first run: a free ride, 90 seconds of it, then the process dies. ----
        val capture = SampleCapture()
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        val firstHost =
            RideHost(
                CapturingFactory(capture),
                flowOf(AppSettings(ftp = 250, demoSpeed = DemoSpeed.Faster)),
                RideChoice.Free,
                persistence = RideLibrary(RideStore(directory), diskScope),
            )
        await("the first ride is ready") { firstHost.snapshots.value.state == RideState.READY }
        firstHost.actions.start()
        await("90 seconds of ride") { firstHost.snapshots.value.elapsedSeconds >= 90 }
        firstHost.close()
        diskScope.cancel()
        assertTrue("the log is on disk and was never finished", names().any { it.endsWith(".ridelog") })
        assertFalse(names().any { it.endsWith(".fit") })
        Thread.sleep(200) // whatever the writer thread was in the middle of ends; nothing else is written
        val ridden = capture.samples.toList()
        assertTrue(ridden.size >= 90)

        // ---- The next start. ----
        val container = AppContainer(context, directory, settingsName = "restart").also { containers += it }
        await("the interrupted ride is found") { container.rides.recovery.value is RecoveryState.Found }
        val found = (container.rides.recovery.value as RecoveryState.Found).ride
        assertTrue("at least what was ridden before the last second reached the disk", found.durationSeconds >= 60)
        assertEquals("Free ride", found.title)

        container.rides.saveRecovered(found.id)
        // The library sets the last ride first and clears the notice a moment later (two state flows, one after the other),
        // so waiting for the first and reading the second at once would race; wait for both.
        await("the ride is saved and the notice is gone") {
            container.rides.lastSaved.value?.id == found.id && container.rides.recovery.value == RecoveryState.None
        }
        assertEquals(RecoveryState.None, container.rides.recovery.value)
        val stored = RideRecordJson.decode(File(directory, "${found.id}.json").readText())
        assertEquals(found.durationSeconds, stored.samples.size)
        assertEquals(
            "the recovered seconds are exactly the first ones that were ridden",
            ridden.take(stored.samples.size),
            stored.samples,
        )
        FitFileCheck.verify(File(directory, "${found.id}.fit").readBytes())
        assertFalse(names().any { it.endsWith(".ridelog") })

        // ---- And the new container rides and stores a whole ride itself. ----
        // The ride's devices are Bluetooth by default (ADR 0010) and there is no radio here: this ride is the simulated one.
        runBlocking {
            container.settingsRepository.update {
                it.copy(demoSpeed = DemoSpeed.Faster, deviceSource = DeviceSource.Simulated)
            }
        }
        val first = container.host.ui.value.rideNumber
        container.host.select(RideChoice.Free)
        await("a new ride is prepared at the new speed") {
            container.host.ui.value.rideNumber > first && container.host.snapshots.value.state == RideState.READY
        }
        container.host.actions.start()
        await("a few seconds of ride") { container.host.snapshots.value.elapsedSeconds >= 8 }
        container.host.actions.finish()
        await("the ride is saved") {
            container.host.ui.value.finished
                ?.save is SaveState.Saved
        }
        val finished = checkNotNull(container.host.ui.value.finished)
        val saved = (finished.save as SaveState.Saved).ride
        val json = RideRecordJson.decode(File(directory, "${saved.id}.json").readText())
        assertEquals(finished.summary.durationSeconds, json.samples.size)
        FitFileCheck.verify(File(directory, "${saved.id}.fit").readBytes())
        assertEquals(saved, container.rides.lastSaved.value)
        assertEquals(2, names().count { it.endsWith(".fit") })
        assertFalse(names().any { it.endsWith(".ridelog") })
    }
}
