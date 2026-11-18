package app.overdrive.core.ride

import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Step
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The simulated rider and sensors: signal shapes, dropouts and determinism. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedRideSignalsTest {
    private fun workoutOf(vararg steps: Step) = Workout(schema = 1, id = "w", name = "W", steps = steps.toList())

    private fun List<RideSnapshot>.riding() = filter { it.state == RideState.RIDING }

    // Power

    @Test
    fun `power holds the ERG target and the zone follows power`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            val seen = record(session)
            ride(session, 60)
            with(seen.riding().last { it.elapsedSeconds == 59 }) {
                assertEquals(100, targetPower)
                assertEquals(100, power)
                assertEquals(PowerZone.Z1, zone) // 50% of FTP
            }
            advanceSeconds(30) // 30 s into step B
            with(session.now) {
                assertEquals(160, targetPower)
                assertEquals(160, power)
                assertEquals(PowerZone.Z3, zone) // 80% of FTP
            }
        }

    @Test
    fun `power lags behind a step change instead of jumping`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            val seen = record(session)
            ride(session, 62)
            val afterChange = seen.riding().filter { it.elapsedSeconds in 60..65 }.map { it.power!! }
            assertEquals(afterChange.sorted(), afterChange, "power rises monotonically")
            assertTrue(afterChange.first() < 160 - 20, "does not jump to the target at once: $afterChange")
            assertTrue(afterChange.first() >= 100, "starts from the old level: $afterChange")
        }

    @Test
    fun `power follows a ramp closely`() =
        runTest {
            val ramp = workoutOf(RampStep(duration = 300, from = 0.4, to = 0.8))
            val session = newSession(workout = ramp)
            val seen = record(session)
            ride(session, 299)
            val settled = seen.riding().filter { it.elapsedSeconds in 30..299 }
            assertTrue(settled.isNotEmpty())
            for (snapshot in settled) {
                val error = abs(snapshot.power!! - snapshot.targetPower!!)
                assertTrue(error <= 2, "at ${snapshot.elapsedSeconds}: $snapshot")
            }
            assertEquals(80, seen.riding().first { it.elapsedSeconds == 0 }.targetPower)
            assertTrue(session.now.targetPower!! in 158..160)
        }

    @Test
    fun `a free ride floats around 55 percent of FTP`() =
        runTest {
            val session = newSession(scenario = SimulationScenario())
            val seen = record(session)
            ride(session, 600)
            val powers = seen.riding().filter { it.elapsedSeconds >= 60 }.map { it.power!! }
            val average = powers.average()
            assertTrue(average in 100.0..120.0, "average $average W, expected about 110")
            assertTrue(powers.toSet().size > 20, "power varies")
        }

    @Test
    fun `noise makes the ERG power wobble around the target`() =
        runTest {
            val session = newSession(workout = abcWorkout, scenario = SimulationScenario())
            val seen = record(session)
            ride(session, 59)
            val powers = seen.riding().filter { it.elapsedSeconds in 20..59 }.map { it.power!! }
            assertTrue(powers.toSet().size > 3, "power varies: $powers")
            assertTrue(powers.all { it in 85..115 }, "stays near 100 W: $powers")
        }

    // Cadence

    @Test
    fun `cadence stays around 90 rpm`() =
        runTest {
            val session = newSession(scenario = SimulationScenario())
            val seen = record(session)
            ride(session, 600)
            val cadences = seen.riding().filter { it.elapsedSeconds >= 1 }.map { it.cadence!! }
            assertTrue(cadences.all { it in 80..100 }, "range ${cadences.min()}..${cadences.max()}")
            assertTrue(cadences.average() in 88.0..92.0, "average ${cadences.average()}")
            assertTrue(cadences.toSet().size > 4, "cadence varies")
        }

    @Test
    fun `cadence settles around the step cadence target`() =
        runTest {
            val session = newSession(workout = workoutOf(SteadyStep(duration = 120, target = 0.6, cadence = 100.0)))
            ride(session, 60)
            assertEquals(100, session.now.cadence)
        }

    // Heart rate

    @Test
    fun `heart rate follows power through a lag`() =
        runTest {
            val session = newSession()
            val seen = record(session)
            ride(session, 400)
            val heartRates = seen.riding().filter { it.elapsedSeconds >= 1 }.map { it.heartRate!! }
            val early = heartRates.take(200)
            assertEquals(early.sorted(), early, "rises monotonically towards steady state")
            assertTrue(heartRates.first() < 80, "starts near rest: ${heartRates.first()}")
            val after30 = seen.riding().first { it.elapsedSeconds == 30 }.heartRate!!
            assertTrue(after30 < 110, "has not caught up after 30 s: $after30")
            // 65 + (170 - 65) * 110 / 200
            assertEquals(123, session.now.heartRate)
        }

    @Test
    fun `a harder step raises the heart rate`() =
        runTest {
            val easyThenHard = workoutOf(SteadyStep(300, 0.5), SteadyStep(300, 0.95))
            val session = newSession(workout = easyThenHard)
            ride(session, 299)
            val easy = session.now.heartRate!!
            advanceSeconds(300)
            val hard = session.now.heartRate!!
            assertTrue(hard > easy + 30, "easy $easy, hard $hard")
        }

    // Speed and distance

    @Test
    fun `speed and distance come from the speed model`() =
        runTest {
            val session = newSession(speedModel = { 36.0 })
            ride(session, 10)
            assertEquals(36.0, session.now.speedKmh, 1e-9)
            assertEquals(100.0, session.now.distanceMeters, 1e-6)
        }

    @Test
    fun `the placeholder speed model is plausible and monotonic`() {
        assertEquals(0.0, PlaceholderSpeedModel.speedKmh(0.0))
        assertEquals(0.0, PlaceholderSpeedModel.speedKmh(-10.0))
        assertTrue(PlaceholderSpeedModel.speedKmh(150.0) in 26.0..30.0)
        assertTrue(PlaceholderSpeedModel.speedKmh(250.0) in 31.0..35.0)
        assertTrue(PlaceholderSpeedModel.speedKmh(251.0) > PlaceholderSpeedModel.speedKmh(250.0))
    }

    @Test
    fun `distance grows while riding and stands still while paused`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            val seen = record(session)
            ride(session, 60)
            val riding = seen.riding().map { it.distanceMeters }
            assertEquals(riding.sorted(), riding)
            assertTrue(session.now.speedKmh in 15.0..35.0, "speed ${session.now.speedKmh}")
            assertTrue(session.now.distanceMeters in 100.0..500.0, "distance ${session.now.distanceMeters}")

            session.pause()
            settle()
            val atPause = session.now.distanceMeters
            advanceSeconds(20)
            assertEquals(atPause, session.now.distanceMeters)
        }

    // Dropouts

    private fun TestScope.dropoutRun(vararg dropouts: SignalDropout): List<RideSnapshot> {
        val scenario = SimulationScenario(dropouts = dropouts.toList())
        val session = newSession(workout = abcWorkout, scenario = scenario, seed = 7L)
        val seen = record(session)
        ride(session, 100)
        return seen.toList()
    }

    @Test
    fun `a heart rate dropout produces nulls for exactly its duration and then recovers`() =
        runTest {
            val baseline = dropoutRun()
            val dropped = dropoutRun(SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 30, durationSeconds = 20))

            assertEquals(baseline.size, dropped.size)
            for ((expected, actual) in baseline.zip(dropped)) {
                val inGap = actual.state == RideState.RIDING && actual.elapsedSeconds in 30..49
                if (inGap) {
                    assertNull(actual.heartRate, "heart rate at ${actual.elapsedSeconds} s")
                    assertNotNull(expected.heartRate)
                } else {
                    assertEquals(expected, actual, "everything else is untouched at ${actual.elapsedSeconds} s")
                }
                val others = "other signals are untouched at ${actual.elapsedSeconds} s"
                assertEquals(expected.copy(heartRate = null), actual.copy(heartRate = null), others)
            }
            assertEquals(20, dropped.riding().count { it.heartRate == null && it.elapsedSeconds in 30..49 })
            assertNotNull(dropped.last().heartRate)
        }

    @Test
    fun `a power dropout also clears the zone`() =
        runTest {
            val dropped = dropoutRun(SignalDropout(SimulatedSignal.POWER, startSecond = 10, durationSeconds = 5))
            val gap = dropped.filter { it.state == RideState.RIDING && it.elapsedSeconds in 10..14 }
            assertEquals(5, gap.size)
            assertTrue(gap.all { it.power == null && it.zone == null })
            assertTrue(gap.all { it.heartRate != null && it.cadence != null })
            val after = dropped.first { it.elapsedSeconds == 15 }
            assertNotNull(after.power)
            assertNotNull(after.zone)
            assertTrue(dropped.last().distanceMeters > 0.0, "the rider kept riding through the gap")
        }

    @Test
    fun `a cadence dropout clears only cadence`() =
        runTest {
            val dropped = dropoutRun(SignalDropout(SimulatedSignal.CADENCE, startSecond = 5, durationSeconds = 3))
            val gap = dropped.filter { it.state == RideState.RIDING && it.elapsedSeconds in 5..7 }
            assertTrue(gap.all { it.cadence == null && it.power != null && it.heartRate != null })
            assertNotNull(dropped.first { it.elapsedSeconds == 8 }.cadence)
        }

    @Test
    fun `several signals can drop out at once and overlapping dropouts merge`() =
        runTest {
            val dropped =
                dropoutRun(
                    SignalDropout(SimulatedSignal.POWER, startSecond = 20, durationSeconds = 10),
                    SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 20, durationSeconds = 10),
                    SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 25, durationSeconds = 10),
                )
            assertTrue(dropped.filter { it.elapsedSeconds in 20..29 }.all { it.power == null && it.heartRate == null })
            assertTrue(dropped.filter { it.elapsedSeconds in 30..34 }.all { it.power != null && it.heartRate == null })
            assertTrue(dropped.first { it.elapsedSeconds == 35 }.heartRate != null)
        }

    @Test
    fun `dropouts run on sensor time and carry on through a pause`() =
        runTest {
            val dropout = SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 10, durationSeconds = 5)
            val scenario = SimulationScenario(dropouts = listOf(dropout))
            val session = newSession(workout = abcWorkout, scenario = scenario)
            ride(session, 8)
            session.pause()
            settle()
            assertNotNull(session.now.heartRate)
            advanceSeconds(6) // sensor second 14, still inside the dropout
            assertEquals(RideState.PAUSED, session.now.state)
            assertNull(session.now.heartRate)
            advanceSeconds(1) // sensor second 15, over
            assertNotNull(session.now.heartRate)
        }

    // Determinism

    private fun TestScope.fullRun(
        seed: Long,
        scenario: SimulationScenario = SimulationScenario(),
    ): List<RideSnapshot> {
        val session = newSession(workout = abcWorkout, scenario = scenario, seed = seed)
        val seen = record(session)
        session.start()
        settle()
        advanceSeconds(60)
        session.intensityUp()
        session.pause()
        settle()
        advanceSeconds(10)
        session.resume()
        settle()
        advanceSeconds(120)
        return seen.toList()
    }

    @Test
    fun `the same seed gives identical snapshot sequences`() =
        runTest {
            val first = fullRun(seed = 42)
            val second = fullRun(seed = 42)
            assertTrue(first.size > 100)
            assertEquals(first, second)
        }

    @Test
    fun `a different seed gives a different sequence`() =
        runTest {
            val first = fullRun(seed = 42)
            val second = fullRun(seed = 43)
            assertEquals(first.size, second.size)
            assertNotEquals(first, second)
            assertTrue(first.zip(second).count { (a, b) -> a.power != b.power } > 20)
            assertTrue(first.zip(second).count { (a, b) -> a.cadence != b.cadence } > 20)
        }

    @Test
    fun `without noise the seed makes no difference`() =
        runTest {
            val quiet = SimulationScenario(noise = 0.0)
            assertEquals(fullRun(seed = 1, scenario = quiet), fullRun(seed = 2, scenario = quiet))
        }
}
