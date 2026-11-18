package app.overdrive.ride.host

import app.overdrive.core.fit.FitOptions
import app.overdrive.core.fit.FitWriter
import app.overdrive.core.record.CompositeRideRecorder
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.data.rides.FinalizeStep
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.overlay.overlayStates
import app.overdrive.ride.rides.FitFileCheck
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The host with the real engine, the simulated devices and the real [RideLibrary] over a real temporary folder, in virtual
 * time (ADR 0008): a ride ends as stored files, a ride cut off is found by the next start, and disk problems are states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideHostPersistenceTest {
    private val directory = RideFixtures.directory()

    private val short: Workout =
        WorkoutImport.fromJson(
            """
            { "schema": 1, "id": "short", "name": "Short", "steps": [
              { "type": "steady", "duration": 40, "target": 0.8, "label": "Steady" },
              { "type": "free", "duration": 20, "label": "Free ride" } ] }
            """.trimIndent(),
        )

    /** The simulated session, plus a copy of the ride the engine hands to its recorder, to compare the files with. */
    private class CapturingFactory : RideSessionFactory {
        val live = mutableListOf<InMemoryRideRecorder>()
        private val inner = SimulatedRideSessionFactory()

        override fun create(request: RideRequest): RideSessionHandle {
            val copy = InMemoryRideRecorder().also { live += it }
            val recorder = CompositeRideRecorder(request.recorder, copy)
            return inner.create(
                RideRequest(request.scope, request.workout, request.settings, recorder, request.rideNumber),
            )
        }

        val record: RideRecord get() = checkNotNull(live.last().record)
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun names() = directory.list().orEmpty().sorted()

    private fun TestScope.library(
        store: RideStore = RideStore(directory),
        offset: Int = 3600,
    ) = RideLibrary(store, CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), utcOffsetSeconds = { offset })

    private fun TestScope.host(
        library: RideLibrary,
        factory: RideSessionFactory = SimulatedRideSessionFactory(),
        choice: RideChoice = RideChoice.Planned(short),
    ) = RideHost(
        factory,
        MutableStateFlow(AppSettings(ftp = 250)),
        choice,
        StandardTestDispatcher(testScheduler),
        debounceMillis = 100,
        persistence = library,
    )

    // ---- A finished ride --------------------------------------------------------------------------------------------

    @Test
    fun aFullRideLeavesTheRecordAndTheFitFileAndNoLog() =
        runTest {
            val factory = CapturingFactory()
            val library = library(offset = 7200)
            val host = host(library, factory)
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()

            assertEquals(RideState.FINISHED, host.snapshots.value.state)
            val id = "ride-${factory.record.ride.startEpochMillis}"
            assertEquals(listOf("$id.fit", "$id.json", "$id.meta"), names())
            assertFalse("no stray log", names().any { it.endsWith(".ridelog") })

            assertEquals(factory.record, RideRecordJson.decode(File(directory, "$id.json").readText()))
            val fit = File(directory, "$id.fit").readBytes()
            val dataSize = FitFileCheck.verify(fit)
            assertEquals((fit.size - 16).toLong(), dataSize)
            // The file is the one the core writes for that record with the device's offset at the start.
            val expected = FitWriter(FitOptions(utcOffsetSeconds = 7200)).write(factory.record)
            assertTrue(expected.contentEquals(fit))

            val saved = host.ui.value.finished?.save as SaveState.Saved
            assertEquals(id, saved.ride.id)
            assertEquals("Short", saved.ride.title)
            assertEquals(60, saved.ride.durationSeconds)
            assertEquals(fit.size.toLong(), saved.ride.fitBytes)
            assertEquals(saved.ride, library.lastSaved.value)
            host.close()
        }

    @Test
    fun theSummaryShowsSavingFirstAndThenSaved() =
        runTest {
            val library = library()
            val host = host(library)
            val saves = mutableListOf<SaveState?>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    host.ui.collect { saves += it.finished?.save }
                }
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            collector.cancel()
            val seen = saves.filterNotNull().distinct()
            assertEquals(2, seen.size)
            assertEquals(SaveState.Saving, seen[0])
            assertTrue(seen[1] is SaveState.Saved)
            host.close()
        }

    @Test
    fun doneWhileSavingStillStoresTheRide() =
        runTest {
            val library = library()
            val host = host(library)
            runCurrent()
            host.actions.start()
            advanceTimeBy(60_500)
            // The rider presses Done as soon as the summary appears, before the files are written.
            host.reset()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(RideState.READY, host.snapshots.value.state)
            assertEquals(1, names().count { it.endsWith(".fit") })
            assertNotNull(library.lastSaved.value)
            assertFalse(names().any { it.endsWith(".ridelog") })
            host.close()
        }

    @Test
    fun aRideThatIsPreparedButNeverStartedLeavesNoFile() =
        runTest {
            val library = library()
            val host = host(library)
            runCurrent()
            host.select(RideChoice.Free)
            host.select(RideChoice.Planned(short))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(emptyList<String>(), names())
            host.close()
        }

    // ---- Interrupted rides -----------------------------------------------------------------------------------------

    @Test
    fun aRideCutOffMidWayIsFoundAndRecoveredByTheNextStart() =
        runTest {
            val factory = CapturingFactory()
            val first = library()
            val host = host(first, factory, RideChoice.Free)
            runCurrent()
            host.actions.start()
            advanceTimeBy(100_500)
            runCurrent()
            assertEquals(RideState.RIDING, host.snapshots.value.state)
            val written = factory.record
            assertEquals(100, written.samples.size)
            // The process dies: nothing is finished, the files stay as they are.
            host.close()
            assertTrue(names().any { it.endsWith(".ridelog") })

            val second = library()
            second.start()
            runCurrent()
            val found = second.recovery.value as RecoveryState.Found
            assertEquals("Free ride", found.ride.title)
            assertTrue("every second that reached the disk is there", found.ride.durationSeconds in 99..100)

            second.saveRecovered(found.ride.id)
            runCurrent()
            assertEquals(RecoveryState.None, second.recovery.value)
            val stored = RideRecordJson.decode(File(directory, "${found.ride.id}.json").readText())
            assertEquals(written.samples.take(stored.samples.size), stored.samples)
            assertEquals(written.ride.id, stored.ride.id)
            assertEquals(written.ride.startEpochMillis, stored.ride.startEpochMillis)
            assertEquals(written.ride.startEpochMillis + stored.samples.size * 1000L, stored.ride.endEpochMillis)
            FitFileCheck.verify(File(directory, "${found.ride.id}.fit").readBytes())
            assertFalse(names().any { it.endsWith(".ridelog") })
        }

    @Test
    fun aRideCutOffInTheFirstMinuteIsDroppedWithoutAsking() =
        runTest {
            val first = library()
            val host = host(first, choice = RideChoice.Free)
            runCurrent()
            host.actions.start()
            advanceTimeBy(30_500)
            runCurrent()
            host.close()
            val second = library()
            second.start()
            runCurrent()
            assertEquals(RecoveryState.None, second.recovery.value)
            assertEquals(emptyList<String>(), names())
        }

    @Test
    fun aRideThatFinishedJustBeforeTheProcessDiedIsCompletedByTheNextStart() =
        runTest {
            // The finish was written but storing was cut off after the record and before the FIT file.
            val store =
                RideStore(directory, afterStep = {
                    if (it ==
                        FinalizeStep.Record
                    ) {
                        throw IOException("the process died here")
                    }
                })
            val factory = CapturingFactory()
            val host = host(library(store), factory)
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            assertTrue(host.ui.value.finished?.save is SaveState.Failed)
            host.close()

            val next = library()
            next.start()
            runCurrent()
            assertEquals("completed silently, nothing to ask", RecoveryState.None, next.recovery.value)
            val id = "ride-${factory.record.ride.startEpochMillis}"
            assertEquals(id, next.lastSaved.value?.id)
            assertEquals(factory.record, RideRecordJson.decode(File(directory, "$id.json").readText()))
            FitFileCheck.verify(File(directory, "$id.fit").readBytes())
        }

    // ---- Disk problems ---------------------------------------------------------------------------------------------

    @Test
    fun aLogThatCannotBeWrittenIsOneFlagAndTheRideGoesOnAndIsStoredFromMemory() =
        runTest {
            val factory = CapturingFactory()
            val store = RideStore(directory, openSink = { throw IOException("Read-only file system") })
            val library = library(store)
            val host = host(library, factory)
            runCurrent()
            assertFalse(host.ui.value.logFailed)
            host.actions.start()
            advanceTimeBy(20_500)
            runCurrent()
            assertTrue("the notice appears", host.ui.value.logFailed)
            assertEquals(RideState.RIDING, host.snapshots.value.state)
            assertTrue("the ride carries on", host.snapshots.value.elapsedSeconds >= 20)

            advanceTimeBy(45_000)
            runCurrent()
            assertEquals(RideState.FINISHED, host.snapshots.value.state)
            assertEquals(60, factory.record.samples.size)
            val saved = host.ui.value.finished?.save
            assertTrue(
                "the disk is fine at the end, so the ride is stored from memory: $saved",
                saved is SaveState.Saved,
            )
            host.close()
        }

    @Test
    fun aHealthyRideNeverShowsTheNotice() =
        runTest {
            val host = host(library())
            runCurrent()
            host.actions.start()
            advanceTimeBy(30_500)
            runCurrent()
            assertFalse(host.ui.value.logFailed)
            host.close()
        }

    @Test
    fun aFailedSaveIsShownAndRetryStoresTheRide() =
        runTest {
            var full = true
            val store =
                RideStore(directory, afterStep = {
                    if (full &&
                        it == FinalizeStep.Fit
                    ) {
                        throw IOException("No space left")
                    }
                })
            val library = library(store)
            val host = host(library)
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            val failed = host.ui.value.finished?.save as SaveState.Failed
            assertTrue(failed.retryable)
            assertTrue(failed.message.contains("space"))
            assertTrue("the log is the only copy meanwhile", names().any { it.endsWith(".ridelog") })

            full = false
            host.retrySave()
            runCurrent()
            assertTrue(host.ui.value.finished?.save is SaveState.Saved)
            assertFalse(names().any { it.endsWith(".ridelog") })
            host.close()
        }

    @Test
    fun retryDoesNothingWhenThereIsNothingToRetry() =
        runTest {
            val host = host(library())
            runCurrent()
            host.retrySave()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            val saved = host.ui.value.finished?.save
            host.retrySave()
            runCurrent()
            assertEquals(saved, host.ui.value.finished?.save)
            host.close()
        }

    @Test
    fun withoutPersistenceTheSummaryCannotBeSavedAndSaysSo() =
        runTest {
            val host =
                RideHost(
                    SimulatedRideSessionFactory(),
                    MutableStateFlow(AppSettings(ftp = 250)),
                    RideChoice.Planned(short),
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            val save = host.ui.value.finished?.save as SaveState.Failed
            assertFalse(save.retryable)
            host.close()
        }

    // ---- The overlay -----------------------------------------------------------------------------------------------

    @Test
    fun storingTheRideChangesNothingTheOverlayShows() =
        runTest {
            val host = host(library())
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            val finished = host.ui.value
            assertTrue(finished.finished?.save is SaveState.Saved)
            val saving = finished.copy(finished = finished.finished?.copy(save = SaveState.Saving))
            val states = overlayStates(flowOf(saving, finished, saving, finished), now = { 0L }).toList()
            assertEquals("one overlay state for four republished summaries", 1, states.size)
            host.close()
        }
}
