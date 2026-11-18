package app.overdrive.ride.rides

import app.overdrive.core.record.RideRecordJson
import app.overdrive.data.rides.FinalizeStep
import app.overdrive.data.rides.RideStore
import app.overdrive.data.rides.StoredRideStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The library over a real temporary folder, in virtual time: recovery on start, saving, discarding, storing at the end. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideLibraryTest {
    private val directory = RideFixtures.directory()
    private val day = 86_400_000L

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun names() = directory.list().orEmpty().sorted()

    private fun TestScope.library(
        store: RideStore = RideStore(directory),
        reports: MutableList<String> = mutableListOf(),
        minimum: Int = RideLibrary.MINIMUM_RECOVERABLE_SECONDS,
    ) = RideLibrary(
        store,
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        utcOffsetSeconds = { 3600 },
        minimumRecoverableSeconds = minimum,
        report = { note, _ -> reports += note },
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

    // ---- Recovery on start -----------------------------------------------------------------------------------------

    @Test
    fun nothingOnDiskMeansNothingToOffer() =
        runTest {
            val library = library()
            library.start()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            assertNull(library.lastSaved.value)
        }

    @Test
    fun anInterruptedRideOfAMinuteOrMoreIsOffered() =
        runTest {
            leave("long", daysAgo = 0, seconds = 600, finished = false)
            val library = library()
            library.start()
            runCurrent()
            val found = library.recovery.value as RecoveryState.Found
            assertEquals("long", found.ride.id)
            assertEquals(600, found.ride.durationSeconds)
            assertEquals("Ride long", found.ride.title)
            assertEquals(0, found.more)
            assertTrue("it is only offered, nothing is stored yet", File(directory, "long.ridelog").exists())
            assertFalse(File(directory, "long.fit").exists())
        }

    @Test
    fun anInterruptedRideUnderAMinuteIsDeletedWithoutAsking() =
        runTest {
            leave("short", daysAgo = 0, seconds = 59, finished = false)
            leave("edge", daysAgo = 1, seconds = 60, finished = false)
            val library = library()
            library.start()
            runCurrent()
            assertEquals("edge", (library.recovery.value as RecoveryState.Found).ride.id)
            assertEquals(listOf("edge.meta", "edge.ridelog"), names())
        }

    @Test
    fun aFinishedLogWhoseExportWasCutOffIsCompletedSilently() =
        runTest {
            leave("done", daysAgo = 0, seconds = 300, finished = true)
            val library = library()
            library.start()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            val last = checkNotNull(library.lastSaved.value)
            assertEquals("done", last.id)
            assertEquals(StoredRideStatus.Saved, last.status)
            assertEquals(listOf("done.fit", "done.json", "done.meta"), names())
        }

    @Test
    fun aLogWithoutAHeaderIsDeletedAndNothingIsOffered() =
        runTest {
            File(directory, "ride-9.ridelog").writeBytes(ByteArray(120))
            val library = library()
            library.start()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            assertEquals(emptyList<String>(), names())
        }

    @Test
    fun severalInterruptedRidesAreOfferedNewestFirstOneAfterTheOther() =
        runTest {
            leave("old", daysAgo = 3, seconds = 200, finished = false)
            leave("new", daysAgo = 0, seconds = 300, finished = false)
            leave("mid", daysAgo = 1, seconds = 100, finished = false)
            val library = library()
            library.start()
            runCurrent()
            var found = library.recovery.value as RecoveryState.Found
            assertEquals("new", found.ride.id)
            assertEquals(2, found.more)

            library.discardRecovered()
            runCurrent()
            found = library.recovery.value as RecoveryState.Found
            assertEquals("mid", found.ride.id)
            assertEquals(1, found.more)

            library.saveRecovered()
            runCurrent()
            found = library.recovery.value as RecoveryState.Found
            assertEquals("old", found.ride.id)
            assertEquals(0, found.more)
            assertEquals("mid", library.lastSaved.value?.id)

            library.discardRecovered()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            assertEquals(listOf("mid.fit", "mid.json", "mid.meta"), names())
        }

    @Test
    fun theLastRideAtStartIsTheNewestSavedOne() =
        runTest {
            val store = RideStore(directory)
            for ((index, id) in listOf("a", "b", "c").withIndex()) {
                store.finalize(RideFixtures.record(id, RideFixtures.START + index * day, 120), "Ride $id", 0)
            }
            val library = library()
            library.start()
            runCurrent()
            assertEquals("c", library.lastSaved.value?.id)
        }

    // ---- Save and discard ------------------------------------------------------------------------------------------

    @Test
    fun savingARecoveredRideStoresItLikeAFinishedOneAndOffersItAsTheLastRide() =
        runTest {
            leave("cut", daysAgo = 0, seconds = 400, finished = false)
            val library = library()
            library.start()
            runCurrent()
            library.saveRecovered()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            val last = checkNotNull(library.lastSaved.value)
            assertEquals("cut", last.id)
            assertEquals(400, last.durationSeconds)
            assertTrue(last.hasFit)
            assertEquals(listOf("cut.fit", "cut.json", "cut.meta"), names())
            val stored = RideRecordJson.decode(File(directory, "cut.json").readText())
            assertEquals(400, stored.samples.size)
            assertEquals(RideFixtures.START + 400_000L, stored.ride.endEpochMillis)
            assertEquals(last.fitBytes, library.fitFile(last).length())
        }

    @Test
    fun discardingDeletesTheRideForGood() =
        runTest {
            leave("cut", daysAgo = 0, seconds = 400, finished = false)
            val library = library()
            library.start()
            runCurrent()
            library.discardRecovered()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            assertEquals(emptyList<String>(), names())
            assertNull(library.lastSaved.value)
        }

    @Test
    fun aFailedSaveIsShownAndRetryingAfterwardsWorks() =
        runTest {
            leave("cut", daysAgo = 0, seconds = 400, finished = false)
            var full = true
            val store =
                RideStore(directory, afterStep = {
                    if (full &&
                        it == FinalizeStep.Record
                    ) {
                        throw IOException("No space")
                    }
                })
            val reports = mutableListOf<String>()
            val library = library(store, reports)
            library.start()
            runCurrent()
            library.saveRecovered()
            runCurrent()
            val failed = library.recovery.value as RecoveryState.Failed
            assertEquals("cut", failed.ride.id)
            assertTrue("a full disk can be emptied", failed.retryable)
            assertEquals(listOf("Recovering an interrupted ride failed"), reports)
            assertNull(library.lastSaved.value)
            assertTrue("the ride is still on disk", File(directory, "cut.ridelog").exists())

            full = false
            library.saveRecovered()
            runCurrent()
            assertEquals(RecoveryState.None, library.recovery.value)
            assertEquals("cut", library.lastSaved.value?.id)
        }

    @Test
    fun theSavingStateShowsWhileTheRideIsStored() =
        runTest {
            leave("cut", daysAgo = 0, seconds = 400, finished = false)
            val seen = mutableListOf<RecoveryState>()
            var current: RideLibrary? = null
            val store =
                RideStore(directory, afterStep = {
                    if (it == FinalizeStep.Meta) seen += current!!.recovery.value
                })
            val library = library(store)
            current = library
            library.start()
            runCurrent()
            library.saveRecovered()
            runCurrent()
            assertTrue(seen.single() is RecoveryState.Saving)
        }

    // ---- Storing at the end of a ride ------------------------------------------------------------------------------

    @Test
    fun finishingARideStoresItAndItBecomesTheLastRide() =
        runTest {
            val library = library()
            val record = RideFixtures.record("ride-1", RideFixtures.START, 120)
            val log = library.begin("Sweet spot 3×10")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            for (sample in record.samples) log.recorder.onSample(sample)
            log.recorder.onRideFinished(record.ride.endEpochMillis!!)
            runCurrent()
            assertEquals("the log is being written", listOf("ride-1.meta", "ride-1.ridelog"), names())

            val state = log.finish(record) as SaveState.Saved
            assertEquals(listOf("ride-1.fit", "ride-1.json", "ride-1.meta"), names())
            assertEquals("Sweet spot 3×10", state.ride.title)
            assertEquals(120, state.ride.durationSeconds)
            assertEquals(state.ride, library.lastSaved.value)
            assertEquals(record, RideRecordJson.decode(File(directory, "ride-1.json").readText()))
        }

    @Test
    fun aRideThatWasNeverWrittenToDiskIsStillStoredFromMemory() =
        runTest {
            // The log could not be opened (the folder was unwritable at the start); at the end the disk is fine again.
            val broken = RideStore(directory, openSink = { throw IOException("Read-only file system") })
            val library = library(broken)
            val record = RideFixtures.record("ride-2", RideFixtures.START, 90)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            runCurrent()
            assertTrue(log.failed.value)
            val state = log.finish(record)
            assertTrue(state is SaveState.Saved)
            assertEquals(listOf("ride-2.fit", "ride-2.json", "ride-2.meta"), names())
        }

    @Test
    fun aFailedFinishCanBeRetriedAndKeepsTheLog() =
        runTest {
            var full = true
            val store =
                RideStore(directory, afterStep = {
                    if (full &&
                        it == FinalizeStep.Fit
                    ) {
                        throw IOException("No space")
                    }
                })
            val library = library(store)
            val record = RideFixtures.record("ride-3", RideFixtures.START, 90)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            for (sample in record.samples) log.recorder.onSample(sample)
            log.recorder.onRideFinished(record.ride.endEpochMillis!!)

            val failed = log.finish(record) as SaveState.Failed
            assertTrue(failed.retryable)
            assertTrue(File(directory, "ride-3.ridelog").exists())
            assertNull(library.lastSaved.value)

            full = false
            assertTrue(log.finish(record) is SaveState.Saved)
            assertFalse(File(directory, "ride-3.ridelog").exists())
        }

    @Test
    fun storingGoesOnWhenTheCallerLeavesBeforeItIsDone() =
        runTest {
            // The rider presses Done while the ride is being stored: the screen stops waiting, the ride is still stored.
            val library = library()
            val record = RideFixtures.record("ride-5", RideFixtures.START, 90)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            val waiting = launch { log.finish(record) }
            runCurrent()
            waiting.cancel()
            runCurrent()
            assertEquals(listOf("ride-5.fit", "ride-5.json", "ride-5.meta"), names())
            assertEquals("ride-5", library.lastSaved.value?.id)
        }

    @Test
    fun aRideTheFitFormatCannotHoldFailsForGood() =
        runTest {
            val library = library()
            val record = RideFixtures.record("old", start = 5_000L, seconds = 90)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            val failed = log.finish(record) as SaveState.Failed
            assertFalse(failed.retryable)
        }

    @Test
    fun theFitFilesLocalTimeUsesTheOffsetOfTheRideStart() =
        runTest {
            val offsets = mutableListOf<Long>()
            val library =
                RideLibrary(
                    RideStore(directory),
                    CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
                    utcOffsetSeconds = {
                        offsets += it
                        7200
                    },
                )
            val record = RideFixtures.record("ride-4", RideFixtures.START, 90)
            val log = library.begin("Free ride")
            log.recorder.onRideStarted(record.ride.copy(endEpochMillis = null))
            log.finish(record)
            assertEquals(listOf(RideFixtures.START), offsets)
            val expected =
                app.overdrive.core.fit
                    .FitWriter(app.overdrive.core.fit.FitOptions(utcOffsetSeconds = 7200))
                    .write(record)
            assertTrue(expected.contentEquals(File(directory, "ride-4.fit").readBytes()))
        }

    @Test
    fun theDeviceOffsetFunctionFollowsTheZone() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Berlin"))
            assertEquals(3600, deviceUtcOffsetSeconds(RideFixtures.START)) // November: standard time
            assertEquals(7200, deviceUtcOffsetSeconds(RideFixtures.START + 200 * day)) // June: summer time
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
