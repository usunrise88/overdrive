package app.overdrive.ride.host

import app.overdrive.core.repeats.RepeatPreviousAttempt
import app.overdrive.core.repeats.RepeatPreviousStep
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The previous attempt in the host (spec §11.7, docs/adr/0034): asked once when the ride starts, only for a workout the service gave a hash
 * to, never waited for, and never an error. The simulated rider's heart rate is not a number to test, so the words are checked in the
 * ride's own `core` tests; here it is when the host asks, what it asks with and what it does when nothing comes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviousAttemptHostTest {
    private val hash = "b1add004e12d9617"

    // 500 s steady at 80% of the 250 W FTP: 200 W; long enough for the line to have the seconds it needs.
    private val long: Workout =
        WorkoutImport.fromJson(
            """
            { "schema": 1, "id": "icu-9", "name": "Long steady", "steps": [
              { "type": "steady", "duration": 500, "target": 0.8, "label": "Steady" } ] }
            """.trimIndent(),
        )

    private fun attempt(heartRate: Double = 100.0) =
        RepeatPreviousAttempt(
            rideId = "ride-1791500000000",
            date = "2026-11-12",
            startTime = "2026-11-12T08:00:00Z",
            durationSeconds = 500,
            ftp = 250,
            attempt = 1,
            heartRateAtPower = heartRate,
            steps = listOf(RepeatPreviousStep(1, "Steady", 0, 500, 200.0, heartRate)),
        )

    private class Source(
        val answer: suspend (String) -> RepeatPreviousAttempt?,
    ) : PreviousAttemptSource {
        val asked = mutableListOf<String>()

        override suspend fun previous(hash: String): RepeatPreviousAttempt? {
            asked += hash
            return answer(hash)
        }
    }

    private fun TestScope.host(
        source: PreviousAttemptSource,
        choice: RideChoice = RideChoice.Planned(long, hash),
    ) = RideHost(
        SimulatedRideSessionFactory(),
        MutableStateFlow(AppSettings(ftp = 250)),
        choice,
        StandardTestDispatcher(testScheduler),
        debounceMillis = 100,
        previousAttempts = source,
    )

    @Test
    fun asksOnceWhenTheRideStartsAndNotBefore() =
        runTest {
            val source = Source { attempt() }
            val host = host(source)
            runCurrent()
            assertTrue("a ready ride asks nothing", source.asked.isEmpty())
            host.actions.start()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(hash), source.asked)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals("once, however long the ride goes on", listOf(hash), source.asked)
            host.close()
        }

    @Test
    fun aWorkoutWithoutAHashIsNeverAsked() =
        runTest {
            val source = Source { attempt() }
            val host = host(source, RideChoice.Planned(long))
            runCurrent()
            host.actions.start()
            advanceTimeBy(20_000)
            runCurrent()
            assertTrue(source.asked.isEmpty())
            assertNull(host.ui.value.previousLine)
            host.close()
        }

    @Test
    fun aFreeRideIsNeverAsked() =
        runTest {
            val source = Source { attempt() }
            val host = host(source, RideChoice.Free)
            runCurrent()
            host.actions.start()
            advanceTimeBy(20_000)
            runCurrent()
            assertTrue(source.asked.isEmpty())
            host.close()
        }

    @Test
    fun theRideNeverWaitsForAnAnswerThatDoesNotCome() =
        runTest {
            val source = Source { awaitCancellation() }
            val host = host(source)
            runCurrent()
            host.actions.start()
            advanceTimeBy(30_500)
            runCurrent()
            val ui = host.ui.value
            assertEquals(RideState.RIDING, ui.snapshot.state)
            assertTrue("the ride goes on at its own pace", ui.snapshot.elapsedSeconds in 29..31)
            assertNull(ui.previousLine)
            host.close()
        }

    @Test
    fun anAnswerThatFailsIsNoLineAndNeverAnErrorOnTheRidingScreen() =
        runTest {
            for (failure in listOf<suspend (String) -> RepeatPreviousAttempt?>(
                { null },
                { throw IllegalStateException("The service said something odd") },
                { throw java.io.IOException("No route to host") },
            )) {
                val host = host(Source(failure))
                runCurrent()
                host.actions.start()
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(RideState.RIDING, host.ui.value.snapshot.state)
                assertNull(host.ui.value.previousLine)
                host.close()
            }
        }

    @Test
    fun anAnswerThatArrivesLateIsUsedAtOnce() =
        runTest {
            val gate = CompletableDeferred<RepeatPreviousAttempt?>()
            val source = Source { gate.await() }
            val host = host(source)
            runCurrent()
            host.actions.start()
            advanceTimeBy(10_000)
            runCurrent()
            assertNull(host.ui.value.previousLine)
            gate.complete(attempt())
            runCurrent()
            // Nothing has counted yet (the first minute of a step does not), so there is still no line, and still no error.
            assertNull(host.ui.value.previousLine)
            host.close()
        }

    @Test
    fun theLineAppearsOnceEnoughOfTheStepHasCountedAndRidesOnTheRidesOwnPower() =
        runTest {
            val host = host(Source { attempt(heartRate = 100.0) })
            runCurrent()
            host.actions.start()
            advanceTimeBy(480_000)
            runCurrent()
            // The simulated rider holds the ERG target and its heart rate is far above 100 bpm: a line, in plain words, no verdict.
            val line = host.ui.value.previousLine
            assertNotNull("after eight minutes of a 500 s step there is enough", line)
            assertTrue(line, line!!.startsWith("Heart rate ") && line.endsWith(" last time"))
            assertTrue(line, "fitter" !in line)
            assertTrue("above last time: the previous figure was 100 bpm", "above" in line)
            host.close()
        }
}
