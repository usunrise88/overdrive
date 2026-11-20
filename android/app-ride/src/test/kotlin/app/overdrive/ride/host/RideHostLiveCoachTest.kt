package app.overdrive.ride.host

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.LeafStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.core.workout.wantsLiveCoach
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host and the live coach (docs/adr/0039): every snapshot it publishes is told to the coach with the choice and, once the ride has started, the ride record's id; the coach is never
 * waited for; and the ride itself plays and keeps **only the steps**: the brief and the lines are for the service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideHostLiveCoachTest {
    private val coached: Workout =
        WorkoutImport.fromJson(
            """
            { "schema": 1, "id": "icu-6001", "name": "Threshold progression", "live_coach": true,
              "coach_brief": "Keep him smooth in the first two minutes.",
              "steps": [
                { "type": "steady", "duration": 300, "target": 0.8, "label": "Threshold",
                  "must_say": [ { "at": "start", "text": "First one. Ease into it." } ] },
                { "type": "repeat", "count": 2, "steps": [
                  { "type": "steady", "duration": 120, "target": 0.6,
                    "must_say": [ { "at": 30, "text": "Settle." } ] } ] } ] }
            """.trimIndent(),
        )

    private class Sink : LiveCoachSink {
        class Told(
            val number: Int,
            val choice: RideChoice,
            val rideId: String?,
            val state: RideState,
        )

        val told = mutableListOf<Told>()

        override fun onSnapshot(
            number: Int,
            choice: RideChoice,
            rideId: String?,
            snapshot: RideSnapshot,
        ) {
            told += Told(number, choice, rideId, snapshot.state)
        }
    }

    /** The real simulated factory, remembering what workout it was asked to play. */
    private class Spy : RideSessionFactory {
        val workouts = mutableListOf<Workout?>()
        private val real = SimulatedRideSessionFactory()

        override fun create(request: RideRequest): RideSessionHandle {
            workouts += request.workout
            return real.create(request)
        }
    }

    private fun TestScope.host(
        sink: LiveCoachSink,
        spy: Spy = Spy(),
        choice: RideChoice = RideChoice.Planned(coached, "833a9d4d7f4ebf35"),
    ) = RideHost(
        spy,
        MutableStateFlow(AppSettings(ftp = 250)),
        choice,
        StandardTestDispatcher(testScheduler),
        debounceMillis = 100,
        liveCoach = sink,
    )

    @Test
    fun everySnapshotIsToldToTheCoachWithTheChoiceAndTheRideIdOnceItHasStarted() =
        runTest {
            val sink = Sink()
            val host = host(sink)
            runCurrent()
            assertTrue("a ready ride is told too", sink.told.isNotEmpty())
            assertTrue(sink.told.all { it.rideId == null && it.state != RideState.RIDING })
            host.actions.start()
            advanceTimeBy(5_000)
            runCurrent()
            val riding = sink.told.filter { it.state == RideState.RIDING }
            assertTrue(riding.isNotEmpty())
            val id = checkNotNull(riding.first().rideId)
            assertTrue(id, id.startsWith("ride-"))
            assertTrue("the same id for the whole ride", riding.all { it.rideId == id })
            assertTrue(sink.told.all { it.number == sink.told.first().number })
            assertTrue(
                "the choice is the planned one, with its flag",
                riding.all {
                    (it.choice as RideChoice.Planned).workout.wantsLiveCoach
                },
            )
            host.close()
        }

    @Test
    fun theRidePlaysAndKeepsTheStepsAndNothingOfTheCoach() =
        runTest {
            val spy = Spy()
            val host = host(Sink(), spy)
            runCurrent()
            val played = checkNotNull(spy.workouts.last())
            assertFalse(played.wantsLiveCoach)
            assertNull(played.liveCoach)
            assertNull(played.coachBrief)
            val leaves = played.steps.flatMap { if (it is RepeatStep) it.steps else listOf(it as LeafStep) }
            assertTrue("no step carries a line to say", leaves.all { it.mustSay == null })
            assertEquals("the same steps", coached.steps.size, played.steps.size)
            // What the rider chose is untouched: the coach reads its flag from there.
            assertTrue((host.choice.value as RideChoice.Planned).workout.wantsLiveCoach)
            host.close()
        }

    @Test
    fun aFreeRideIsToldToo_andTheCoachDecidesNothingIsToBeDone() =
        runTest {
            val sink = Sink()
            val host = host(sink, choice = RideChoice.Free)
            runCurrent()
            assertTrue(sink.told.all { it.choice === RideChoice.Free })
            assertSame(RideChoice.Free, host.choice.value)
            host.close()
        }

    @Test
    fun aCoachThatThrowsCannotStopTheHostFromPublishing() =
        runTest {
            val exploding =
                object : LiveCoachSink {
                    var calls = 0

                    override fun onSnapshot(
                        number: Int,
                        choice: RideChoice,
                        rideId: String?,
                        snapshot: RideSnapshot,
                    ) {
                        calls++
                        error("not the host's business")
                    }
                }
            // The real coach guards itself; this is the host's own check that it is never waited for and a failure is its own: the host keeps publishing.
            val host = host(exploding)
            runCurrent()
            assertNotNull(host.snapshots.value)
            host.close()
        }
}
