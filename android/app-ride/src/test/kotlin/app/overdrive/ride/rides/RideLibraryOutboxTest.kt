package app.overdrive.ride.rides

import app.overdrive.data.rides.RideStore
import app.overdrive.data.rides.StoredRide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Every ride that is stored is announced to the sync outbox (spec §4.9, ADR 0012): the one that has just finished, a
 * recovered one, one completed silently at start and, at each start, every ride already on disk. The outbox ignores a ride
 * it has seen, so announcing more than once is how a ride whose entry was lost between two steps is still caught.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideLibraryOutboxTest {
    private val directory = RideFixtures.directory()
    private val day = 86_400_000L

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun TestScope.library(
        announced: MutableList<StoredRide>,
        reports: MutableList<String> = mutableListOf(),
        onSaved: (StoredRide) -> Unit = { announced += it },
    ) = RideLibrary(
        RideStore(directory),
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        utcOffsetSeconds = { 3600 },
        report = { note, _ -> reports += note },
        onSaved = onSaved,
    )

    private fun leave(
        id: String,
        daysAgo: Int,
        seconds: Int,
        finished: Boolean,
    ) = RideFixtures.writeLog(
        RideStore(directory),
        id,
        RideFixtures.START - daysAgo * day,
        seconds,
        finished,
        title = "Ride $id",
    )

    @Test
    fun aRideThatFinishesIsAnnouncedOnceWithWhatTheOutboxNeeds() =
        runTest {
            val announced = mutableListOf<StoredRide>()
            val library = library(announced)
            library.start()
            runCurrent()
            assertTrue("nothing on disk, nothing announced", announced.isEmpty())

            val record = RideFixtures.record("ride-1", RideFixtures.START, 120)
            val log = library.begin("Sweet spot 3×10")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            for (sample in record.samples) log.recorder.onSample(sample)
            log.recorder.onRideFinished(record.ride.endEpochMillis!!)
            runCurrent()
            log.finish(record)

            val ride = announced.single()
            assertEquals("ride-1", ride.id)
            assertEquals(RideFixtures.START, ride.startEpochMillis)
            assertEquals(120, ride.durationSeconds)
            assertEquals("Sweet spot 3×10", ride.title)
        }

    @Test
    fun aRecoveredRideIsAnnouncedWhenTheRiderKeepsIt() =
        runTest {
            leave("cut", daysAgo = 1, seconds = 400, finished = false)
            val announced = mutableListOf<StoredRide>()
            val library = library(announced)
            library.start()
            runCurrent()
            assertTrue("not before the rider decided: it is not stored yet", announced.isEmpty())
            library.saveRecovered("cut")
            runCurrent()
            assertEquals(listOf("cut"), announced.map { it.id })
        }

    @Test
    fun aRideThatWasDiscardedIsNeverAnnounced() =
        runTest {
            leave("cut", daysAgo = 1, seconds = 400, finished = false)
            val announced = mutableListOf<StoredRide>()
            val library = library(announced)
            library.start()
            runCurrent()
            library.discardRecovered("cut")
            runCurrent()
            assertTrue(announced.isEmpty())
        }

    @Test
    fun aFinishedLogWhoseExportWasCutOffIsAnnouncedWhenItIsCompletedAtStart() =
        runTest {
            leave("done", daysAgo = 2, seconds = 300, finished = true)
            val announced = mutableListOf<StoredRide>()
            library(announced).apply {
                start()
                runCurrent()
            }
            assertEquals(listOf("done"), announced.map { it.id })
        }

    @Test
    fun everyRideAlreadyOnDiskIsOfferedAtEachStartOldestFirst() =
        runTest {
            val store = RideStore(directory)
            for ((id, daysAgo) in listOf("c" to 1, "a" to 5, "b" to 3)) {
                val record = RideFixtures.record(id, RideFixtures.START - daysAgo * day, 90)
                store.finalize(record, "Ride $id", 3600)
            }
            val first = mutableListOf<StoredRide>()
            library(first).apply {
                start()
                runCurrent()
            }
            assertEquals(listOf("a", "b", "c"), first.map { it.id })

            // A second start offers them again: the outbox is what says "seen".
            val second = mutableListOf<StoredRide>()
            library(second).apply {
                start()
                runCurrent()
            }
            assertEquals(listOf("a", "b", "c"), second.map { it.id })
        }

    @Test
    fun anOutboxThatFailsIsReportedAndDroppedAndNeverFailsTheRide() =
        runTest {
            val reports = mutableListOf<String>()
            val library = library(mutableListOf(), reports) { throw IOException("the disk is full") }
            library.start()
            runCurrent()
            val record = RideFixtures.record("ride-1", RideFixtures.START, 120)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            for (sample in record.samples) log.recorder.onSample(sample)
            log.recorder.onRideFinished(record.ride.endEpochMillis!!)
            runCurrent()
            val state = log.finish(record)
            assertTrue("the ride is stored all the same: $state", state is SaveState.Saved)
            assertTrue(reports.toString(), reports.contains("Queueing a stored ride for upload failed"))
            assertEquals("ride-1", library.lastSaved.value?.id)
        }
}
