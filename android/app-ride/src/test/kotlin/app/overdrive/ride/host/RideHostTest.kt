package app.overdrive.ride.host

import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's lifecycle over the real engine and simulated devices, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideHostTest {
    private val short: Workout =
        WorkoutImport.fromJson(
            """
            { "schema": 1, "id": "short", "name": "Short", "steps": [
              { "type": "steady", "duration": 40, "target": 0.8, "label": "Steady" },
              { "type": "free", "duration": 20, "label": "Free ride" } ] }
            """.trimIndent(),
        )

    private fun TestScope.host(
        settings: Flow<AppSettings> = MutableStateFlow(AppSettings(ftp = 250)),
        choice: RideChoice = RideChoice.Planned(short),
    ) = RideHost(
        SimulatedRideSessionFactory(),
        settings,
        choice,
        StandardTestDispatcher(testScheduler),
        debounceMillis = 100,
    )

    @Test
    fun preparesAReadyRideAtStart() =
        runTest {
            val host = host()
            runCurrent()
            val ui = host.ui.value
            assertEquals(RideState.READY, ui.snapshot.state)
            assertEquals(250, ui.snapshot.ftp)
            assertEquals(1, ui.rideNumber)
            assertNotNull(ui.profile)
            host.close()
        }

    @Test
    fun ridesAWorkoutToItsEndAndSummarises() =
        runTest {
            val host = host()
            runCurrent()
            host.actions.start()
            advanceTimeBy(30_500)
            runCurrent()
            val riding = host.ui.value
            assertEquals(RideState.RIDING, riding.snapshot.state)
            assertTrue("ride seconds advance", riding.snapshot.elapsedSeconds in 29..31)
            assertNotNull("average power builds up", riding.averagePower)
            assertNotNull("lap average builds up", riding.lapAveragePower)
            assertNull(riding.finished)

            advanceTimeBy(35_000)
            runCurrent()
            val done = host.ui.value
            assertEquals(RideState.FINISHED, done.snapshot.state)
            val finished = checkNotNull(done.finished)
            assertEquals(60, finished.summary.durationSeconds)
            assertEquals(listOf("Steady", "Free ride"), finished.laps.map { it.name })
            assertTrue("seconds in zones never exceed the ride", finished.zoneSeconds.sum() in 1..60)
            host.close()
        }

    @Test
    fun pauseAndResumeGoThroughTheActions() =
        runTest {
            val host = host()
            runCurrent()
            host.actions.start()
            advanceTimeBy(5_500)
            runCurrent()
            host.actions.pause()
            runCurrent()
            assertEquals(RideState.PAUSED, host.snapshots.value.state)
            host.actions.togglePause()
            runCurrent()
            assertEquals(RideState.RIDING, host.snapshots.value.state)
            host.close()
        }

    @Test
    fun choosingAnotherRideBeforeTheStartPreparesANewSession() =
        runTest {
            val host = host()
            runCurrent()
            host.select(RideChoice.Free)
            runCurrent()
            val ui = host.ui.value
            assertEquals(2, ui.rideNumber)
            assertEquals(RideChoice.Free, ui.choice)
            assertNull("a free ride has no profile", ui.profile)
            assertNull(ui.snapshot.workout)
            host.close()
        }

    @Test
    fun aRideUnderWayIsNeverReplaced() =
        runTest {
            val host = host()
            runCurrent()
            host.actions.start()
            advanceTimeBy(3_500)
            runCurrent()
            host.select(RideChoice.Free)
            host.reset()
            runCurrent()
            assertEquals(1, host.ui.value.rideNumber)
            assertEquals(RideChoice.Planned(short), host.choice.value)
            host.close()
        }

    @Test
    fun resetAfterTheFinishPreparesTheNextRide() =
        runTest {
            val host = host()
            runCurrent()
            host.actions.start()
            advanceTimeBy(65_000)
            runCurrent()
            assertEquals(RideState.FINISHED, host.snapshots.value.state)
            host.reset()
            runCurrent()
            assertEquals(RideState.READY, host.snapshots.value.state)
            assertEquals(2, host.ui.value.rideNumber)
            assertNull(host.ui.value.finished)
            host.close()
        }

    @Test
    fun settingsChangesBeforeTheStartRebuildTheReadyRide() =
        runTest {
            val settings = MutableStateFlow(AppSettings(ftp = 250))
            val host = host(settings)
            runCurrent()
            settings.value = AppSettings(ftp = 300)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(300, host.snapshots.value.ftp)
            assertEquals(2, host.ui.value.rideNumber)
            host.close()
        }

    @Test
    fun aSettingThatArrivesRightAfterTheFirstIsNeverOverwrittenByTheFirstWhenItIsLate() =
        runTest {
            // Every collection gets the tablet's FTP of 250 and, 100 ms later, 300. The first collection is slow to wind down
            // once it is cancelled. A host that took the first value with `first()` and followed the later ones in another
            // coroutine stored the first value again when the first collection finally returned, after the newer one, and kept
            // the ride built from it until the next change (a container whose settings were changed at once after the start).
            var collections = 0
            val settings: Flow<AppSettings> =
                flow {
                    val number = ++collections
                    try {
                        emit(AppSettings(ftp = 250))
                        delay(100)
                        emit(AppSettings(ftp = 300))
                        awaitCancellation()
                    } finally {
                        if (number == 1) withContext(NonCancellable) { delay(1_000) }
                    }
                }
            val host = host(settings)
            try {
                runCurrent()
                advanceTimeBy(5_000)
                runCurrent()
                assertEquals(
                    "and the ride follows the later ones, whatever the order things wind down in",
                    300,
                    host.snapshots.value.ftp,
                )
            } finally {
                // Closed even when an assertion fails: a ride left running keeps the test's virtual clock busy for ever.
                host.close()
            }
        }

    @Test
    fun nothingIsBuiltBeforeTheFirstSettingsHaveArrivedAndTheChoiceMadeMeanwhileIsTheFirstRide() =
        runTest {
            val settings = MutableStateFlow<AppSettings?>(null)
            val host = host(settings.filterNotNull())
            try {
                runCurrent()
                host.select(RideChoice.Free)
                host.reset()
                runCurrent()
                assertEquals("no session yet: the settings are not known", 0, host.ui.value.rideNumber)
                settings.value = AppSettings(ftp = 260)
                runCurrent()
                assertEquals(1, host.ui.value.rideNumber)
                assertEquals(260, host.snapshots.value.ftp)
                assertEquals("the choice made while waiting is what was built", RideChoice.Free, host.ui.value.choice)
            } finally {
                host.close()
            }
        }

    @Test
    fun settingsChangesDuringTheRideWaitForTheNextOne() =
        runTest {
            val settings = MutableStateFlow(AppSettings(ftp = 250))
            val host = host(settings)
            runCurrent()
            host.actions.start()
            advanceTimeBy(3_500)
            runCurrent()
            settings.value = AppSettings(ftp = 300)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(250, host.snapshots.value.ftp)
            assertEquals(1, host.ui.value.rideNumber)
            host.close()
        }

    @Test
    fun overlayOnlySettingsDoNotRebuildTheRide() =
        runTest {
            val settings = MutableStateFlow(AppSettings(ftp = 250))
            val host = host(settings)
            runCurrent()
            settings.value = settings.value.copy(overlayOpacityPercent = 90, overlayBlur = true)
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, host.ui.value.rideNumber)
            host.close()
        }

    @Test
    fun theRideRunsOffTheCallersThread() =
        runTest {
            val host = host()
            runCurrent()
            // The host's own dispatcher drives everything; commands from here only enqueue.
            host.actions.start()
            assertEquals(
                "a command takes effect on the ride's coroutine, not before",
                RideState.READY,
                host.snapshots.value.state,
            )
            runCurrent()
            assertEquals(RideState.RIDING, host.snapshots.value.state)
            host.close()
        }
}
