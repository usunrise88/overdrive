package app.overdrive.core.ride

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The state machine, the clocks and what each command does in each state (see [RideSession]). */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedRideSessionStateTest {
    private fun TestScope.sessionIn(state: RideState): SimulatedRideSession =
        when (state) {
            RideState.IDLE -> {
                newSession(startConnected = false)
            }

            RideState.READY -> {
                newSession()
            }

            RideState.RIDING -> {
                newSession().also { ride(it, 5) }
            }

            RideState.PAUSED -> {
                newSession().also {
                    ride(it, 5)
                    it.pause()
                    settle()
                }
            }

            RideState.FINISHED -> {
                newSession().also {
                    ride(it, 5)
                    it.finish()
                    settle()
                }
            }
        }

    private fun RideSession.commands(): Map<String, () -> Unit> =
        mapOf(
            "start" to ::start,
            "pause" to ::pause,
            "resume" to ::resume,
            "finish" to ::finish,
            "nextInterval" to ::nextInterval,
            "previousInterval" to ::previousInterval,
            "gearUp" to ::gearUp,
            "gearDown" to ::gearDown,
            "intensityUp" to ::intensityUp,
            "intensityDown" to ::intensityDown,
        )

    @Test
    fun `a new session is ready with no sensor data`() =
        runTest {
            val session = newSession()
            settle()
            with(session.now) {
                assertEquals(RideState.READY, state)
                assertEquals(0, elapsedSeconds)
                assertNull(power)
                assertNull(heartRate)
                assertNull(cadence)
                assertNull(zone)
                assertEquals(0.0, speedKmh)
                assertEquals(0.0, distanceMeters)
                assertEquals(100, intensityPercent)
                assertEquals(200, ftp)
            }
        }

    @Test
    fun `lifecycle commands move through the states`() {
        val expected =
            mapOf(
                RideState.IDLE to listOf(RideState.IDLE, RideState.IDLE, RideState.IDLE, RideState.IDLE),
                RideState.READY to listOf(RideState.RIDING, RideState.READY, RideState.READY, RideState.READY),
                RideState.RIDING to listOf(RideState.RIDING, RideState.PAUSED, RideState.RIDING, RideState.FINISHED),
                RideState.PAUSED to listOf(RideState.PAUSED, RideState.PAUSED, RideState.RIDING, RideState.FINISHED),
                RideState.FINISHED to
                    listOf(RideState.FINISHED, RideState.FINISHED, RideState.FINISHED, RideState.FINISHED),
            )
        for ((from, results) in expected) {
            val names = listOf("start", "pause", "resume", "finish")
            for ((index, name) in names.withIndex()) {
                runTest {
                    val session = sessionIn(from)
                    assertEquals(from, session.now.state, "setup for $from")
                    session.commands().getValue(name)()
                    settle()
                    assertEquals(results[index], session.now.state, "$name in $from")
                }
            }
        }
    }

    @Test
    fun `every command except connect is a no-op while idle`() =
        runTest {
            val session = newSession(workout = abcWorkout, startConnected = false)
            settle()
            val before = session.now
            assertEquals(RideState.IDLE, before.state)
            session.commands().values.forEach { it() }
            settle()
            advanceSeconds(10)
            assertEquals(before, session.now)

            session.connect()
            settle()
            assertEquals(RideState.READY, session.now.state)
            session.connect()
            settle()
            assertEquals(RideState.READY, session.now.state)
        }

    @Test
    fun `connect does nothing once the ride is under way`() =
        runTest {
            val session = sessionIn(RideState.RIDING)
            session.connect()
            settle()
            assertEquals(RideState.RIDING, session.now.state)
        }

    @Test
    fun `every command is a no-op after the ride is finished`() =
        runTest {
            val session = sessionIn(RideState.FINISHED)
            val before = session.now
            session.commands().values.forEach { it() }
            session.connect()
            settle()
            advanceSeconds(10)
            assertEquals(before, session.now)
        }

    @Test
    fun `the ride clock ticks once a second`() =
        runTest {
            val session = newSession()
            val seen = record(session)
            session.start()
            settle()
            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertEquals(0, session.now.elapsedSeconds)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(1, session.now.elapsedSeconds)
            advanceSeconds(9)
            assertEquals(10, session.now.elapsedSeconds)
            assertEquals((0..10).toList(), seen.filter { it.state == RideState.RIDING }.map { it.elapsedSeconds })
        }

    @Test
    fun `a shorter tick interval fast-forwards the ride`() =
        runTest {
            val session = newSession(workout = abcWorkout, tickInterval = 100.milliseconds)
            session.start()
            settle()
            advanceTimeBy(10_000.milliseconds)
            runCurrent()
            assertEquals(100, session.now.elapsedSeconds)
            advanceTimeBy(8_000.milliseconds)
            runCurrent()
            assertEquals(RideState.FINISHED, session.now.state)
            assertEquals(180, session.now.elapsedSeconds)
        }

    @Test
    fun `pausing stops the ride clock and the workout and zeroes the rider`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 10)
            val distanceBefore = session.now.distanceMeters
            assertNotNull(session.now.power)

            session.pause()
            settle()
            with(session.now) {
                assertEquals(RideState.PAUSED, state)
                assertEquals(0, power)
                assertEquals(0, cadence)
                assertEquals(0.0, speedKmh)
                assertEquals(distanceBefore, distanceMeters)
                assertNotNull(heartRate, "the strap keeps reporting while paused")
            }

            advanceSeconds(30)
            with(session.now) {
                assertEquals(RideState.PAUSED, state)
                assertEquals(10, elapsedSeconds)
                assertEquals(10, workout!!.workoutElapsedSeconds)
                assertEquals(0, power)
                assertEquals(distanceBefore, distanceMeters)
            }

            session.resume()
            settle()
            advanceSeconds(5)
            with(session.now) {
                assertEquals(RideState.RIDING, state)
                assertEquals(15, elapsedSeconds)
                assertEquals(15, workout!!.workoutElapsedSeconds)
                assertNotNull(power)
            }
        }

    @Test
    fun `heart rate relaxes while paused`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 100)
            val peak = session.now.heartRate!!
            session.pause()
            settle()
            advanceSeconds(60)
            assertEquals(true, session.now.heartRate!! < peak, "heart rate fell from $peak")
        }

    @Test
    fun `a free ride has no workout and ends only when finished`() =
        runTest {
            val session = newSession()
            ride(session, 7200)
            assertEquals(RideState.RIDING, session.now.state)
            assertEquals(7200, session.now.elapsedSeconds)
            assertNull(session.now.workout)
            assertNull(session.now.targetPower)
            assertEquals(TrainerMode.SIMULATION, session.now.mode)
            session.finish()
            settle()
            assertEquals(RideState.FINISHED, session.now.state)
            assertEquals(7200, session.now.elapsedSeconds)
        }

    @Test
    fun `a workout ride finishes after exactly the workout duration`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 179)
            assertEquals(RideState.RIDING, session.now.state)
            advanceSeconds(1)
            with(session.now) {
                assertEquals(RideState.FINISHED, state)
                assertEquals(180, elapsedSeconds)
                val progress = workout!!
                assertEquals(2, progress.stepIndex)
                assertEquals(60, progress.stepElapsedSeconds)
                assertEquals(0, progress.stepRemainingSeconds)
                assertEquals(180, progress.workoutElapsedSeconds)
                assertEquals(0, progress.workoutRemainingSeconds)
                assertNull(progress.nextStepLabel)
            }
        }

    @Test
    fun `sensor values and targets are null once the ride is finished`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 20)
            assertNotNull(session.now.power)
            session.finish()
            settle()
            with(session.now) {
                assertEquals(RideState.FINISHED, state)
                assertNull(power)
                assertNull(heartRate)
                assertNull(cadence)
                assertNull(zone)
                assertNull(targetPower)
                assertNull(gear)
                assertEquals(0.0, speedKmh)
                assertEquals(20, elapsedSeconds)
                assertEquals(20, workout!!.workoutElapsedSeconds)
            }
        }

    @Test
    fun `a finished session leaves no coroutines running in its scope`() =
        runTest {
            // The session runs in the test's own scope here. If it kept waiting for commands after the
            // ride was over, runTest would never complete.
            val session = SimulatedRideSession(this, abcWorkout, scenario = quietScenario)
            session.start()
            advanceSeconds(180)
            assertEquals(RideState.FINISHED, session.now.state)
        }

    @Test
    fun `a ready session shows the first step of the workout`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            settle()
            with(session.now) {
                assertEquals(RideState.READY, state)
                assertEquals(TrainerMode.ERG, mode)
                assertEquals(100, targetPower)
                assertNull(gear)
                val progress = workout!!
                assertEquals("ABC", progress.workoutName)
                assertEquals(0, progress.stepIndex)
                assertEquals("A", progress.stepLabel)
                assertEquals("B", progress.nextStepLabel)
                assertEquals(0, progress.stepElapsedSeconds)
                assertEquals(60, progress.stepRemainingSeconds)
                assertEquals(0, progress.workoutElapsedSeconds)
                assertEquals(180, progress.workoutRemainingSeconds)
                assertEquals(180, progress.workoutTotalSeconds)
                assertEquals(abcWorkout.flatSteps(), progress.steps)
            }
        }

    @Test
    fun `workout progress counts down inside a step`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 75)
            val progress = session.now.workout!!
            assertEquals(1, progress.stepIndex)
            assertEquals("B", progress.stepLabel)
            assertEquals("C", progress.nextStepLabel)
            assertEquals(15, progress.stepElapsedSeconds)
            assertEquals(45, progress.stepRemainingSeconds)
            assertEquals(75, progress.workoutElapsedSeconds)
            assertEquals(105, progress.workoutRemainingSeconds)
            assertEquals(160, session.now.targetPower)
        }

    @Test
    fun `the step list is the same instance in every snapshot`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            val seen = record(session)
            ride(session, 20)
            val first = seen.last().workout!!.steps
            assertTrue(seen.all { it.workout!!.steps === first })
        }

    // Skipping intervals

    @Test
    fun `next interval jumps to the start of the next step without rewinding ride time`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 10)
            session.nextInterval()
            settle()
            with(session.now) {
                assertEquals(10, elapsedSeconds)
                val progress = workout!!
                assertEquals(1, progress.stepIndex)
                assertEquals(0, progress.stepElapsedSeconds)
                assertEquals(60, progress.workoutElapsedSeconds)
                assertEquals(120, progress.workoutRemainingSeconds)
                assertEquals("C", progress.nextStepLabel)
                assertEquals(160, targetPower)
            }
            session.nextInterval()
            settle()
            with(session.now) {
                assertEquals(2, workout!!.stepIndex)
                assertEquals(TrainerMode.SIMULATION, mode)
                assertNull(targetPower)
                assertEquals(12, gear)
                assertNull(workout.nextStepLabel)
            }
        }

    @Test
    fun `next interval in the last step is a no-op`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 130)
            val before = session.now
            session.nextInterval()
            settle()
            assertEquals(before, session.now)
        }

    @Test
    fun `skipping to the last step ends the ride early`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            session.start()
            settle()
            session.nextInterval()
            session.nextInterval()
            settle()
            advanceSeconds(59)
            assertEquals(RideState.RIDING, session.now.state)
            advanceSeconds(1)
            assertEquals(RideState.FINISHED, session.now.state)
            assertEquals(60, session.now.elapsedSeconds)
        }

    @Test
    fun `previous interval restarts the step when more than 3 s into it else goes back`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 70) // 10 s into step B
            session.previousInterval()
            settle()
            with(session.now.workout!!) {
                assertEquals(1, stepIndex)
                assertEquals(0, stepElapsedSeconds)
                assertEquals(60, workoutElapsedSeconds)
            }
            assertEquals(70, session.now.elapsedSeconds)

            session.previousInterval()
            settle()
            with(session.now.workout!!) {
                assertEquals(0, stepIndex)
                assertEquals(0, workoutElapsedSeconds)
            }

            session.previousInterval()
            settle()
            assertEquals(0, session.now.workout!!.stepIndex, "nothing before the first step")
            assertEquals(0, session.now.workout!!.workoutElapsedSeconds)
        }

    @Test
    fun `previous interval treats exactly 3 s as the start of the step`() =
        runTest {
            val atThree = newSession(workout = abcWorkout)
            ride(atThree, 63) // 3 s into step B
            atThree.previousInterval()
            settle()
            assertEquals(0, atThree.now.workout!!.stepIndex)

            val atFour = newSession(workout = abcWorkout)
            ride(atFour, 64) // 4 s into step B
            atFour.previousInterval()
            settle()
            assertEquals(1, atFour.now.workout!!.stepIndex)
            assertEquals(0, atFour.now.workout!!.stepElapsedSeconds)
        }

    @Test
    fun `previous interval in the first step restarts it`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 2)
            session.previousInterval()
            settle()
            assertEquals(0, session.now.workout!!.stepIndex)
            assertEquals(0, session.now.workout!!.stepElapsedSeconds)
            advanceSeconds(1)
            assertEquals(1, session.now.workout!!.stepElapsedSeconds)
        }

    @Test
    fun `skipping works while paused and keeps the ride paused`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 10)
            session.pause()
            session.nextInterval()
            settle()
            assertEquals(RideState.PAUSED, session.now.state)
            assertEquals(1, session.now.workout!!.stepIndex)
            session.previousInterval()
            settle()
            assertEquals(RideState.PAUSED, session.now.state)
            assertEquals(0, session.now.workout!!.stepIndex)
        }

    @Test
    fun `skipping is a no-op before the start and without a workout`() =
        runTest {
            val ready = newSession(workout = abcWorkout)
            ready.nextInterval()
            ready.previousInterval()
            settle()
            assertEquals(0, ready.now.workout!!.stepIndex)
            assertEquals(0, ready.now.workout!!.workoutElapsedSeconds)

            val free = newSession()
            ride(free, 10)
            val before = free.now
            free.nextInterval()
            free.previousInterval()
            settle()
            assertEquals(before, free.now)
        }

    // Intensity

    @Test
    fun `intensity moves in steps of 5 and is clamped to 50 to 150`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 5)
            session.intensityUp()
            settle()
            assertEquals(105, session.now.intensityPercent)
            repeat(20) { session.intensityUp() }
            settle()
            assertEquals(150, session.now.intensityPercent)
            repeat(30) { session.intensityDown() }
            settle()
            assertEquals(50, session.now.intensityPercent)
            session.intensityUp()
            settle()
            assertEquals(55, session.now.intensityPercent)
        }

    @Test
    fun `intensity scales the target power and the rider follows it`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 30)
            assertEquals(100, session.now.targetPower)
            assertEquals(100, session.now.power)

            repeat(2) { session.intensityUp() } // 110%
            settle()
            assertEquals(110, session.now.targetPower)
            advanceSeconds(20)
            assertEquals(110, session.now.power)

            repeat(12) { session.intensityDown() } // 50%
            settle()
            assertEquals(50, session.now.targetPower)
        }

    @Test
    fun `intensity is kept across steps and applies to the next ERG step`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 5)
            repeat(4) { session.intensityUp() } // 120%
            session.nextInterval()
            settle()
            assertEquals(192, session.now.targetPower) // 0.8 * 200 * 1.2
        }

    @Test
    fun `intensity is a no-op in a simulation step`() =
        runTest {
            val free = newSession()
            ride(free, 5)
            free.intensityUp()
            free.intensityDown()
            settle()
            assertEquals(100, free.now.intensityPercent)

            val session = newSession(workout = abcWorkout)
            ride(session, 130) // in the free step C
            session.intensityUp()
            settle()
            assertEquals(100, session.now.intensityPercent)
        }

    @Test
    fun `intensity can be set while ready and while paused`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            session.intensityUp()
            settle()
            assertEquals(105, session.now.intensityPercent)
            assertEquals(105, session.now.targetPower)

            ride(session, 5)
            session.pause()
            session.intensityDown()
            settle()
            assertEquals(RideState.PAUSED, session.now.state)
            assertEquals(100, session.now.intensityPercent)
        }

    // Gears

    @Test
    fun `gears start in neutral and are clamped to the range`() =
        runTest {
            val session = newSession()
            ride(session, 5)
            with(session.now) {
                assertEquals(12, gear)
                assertEquals(12, neutralGear)
                assertEquals(24, gearCount)
            }
            session.gearUp()
            settle()
            assertEquals(13, session.now.gear)
            repeat(40) { session.gearUp() }
            settle()
            assertEquals(24, session.now.gear)
            repeat(40) { session.gearDown() }
            settle()
            assertEquals(1, session.now.gear)
            session.gearUp()
            settle()
            assertEquals(2, session.now.gear)
        }

    @Test
    fun `the number of gears and the neutral gear are parameters`() =
        runTest {
            val odd = newSession(scenario = SimulationScenario(gearCount = 5, noise = 0.0))
            settle()
            assertEquals(3, odd.now.neutralGear)
            assertEquals(5, odd.now.gearCount)
            assertEquals(3, odd.now.gear)

            val custom = newSession(scenario = SimulationScenario(gearCount = 10, neutralGear = 2, noise = 0.0))
            settle()
            assertEquals(2, custom.now.gear)
            repeat(20) { custom.gearUp() }
            settle()
            assertEquals(10, custom.now.gear)
        }

    @Test
    fun `gears are unavailable in an ERG step and keep their value across it`() =
        runTest {
            val session = newSession(workout = abcWorkout)
            ride(session, 5)
            assertNull(session.now.gear)
            session.gearUp()
            session.gearDown()
            settle()
            assertNull(session.now.gear)
            session.nextInterval()
            session.nextInterval()
            settle()
            assertEquals(12, session.now.gear, "the ERG step did not move the gear")
        }

    @Test
    fun `gears can be changed while ready and while paused but not before connecting`() =
        runTest {
            val ready = newSession()
            ready.gearUp()
            settle()
            assertEquals(13, ready.now.gear)

            val paused = sessionIn(RideState.PAUSED)
            paused.gearDown()
            settle()
            assertEquals(11, paused.now.gear)

            val idle = newSession(startConnected = false)
            idle.gearUp()
            settle()
            assertEquals(12, idle.now.gear)
        }

    @Test
    fun `gear changes move the simulated power`() =
        runTest {
            val session = newSession()
            ride(session, 60)
            assertEquals(110, session.now.power) // 55% of 200 W in the neutral gear

            repeat(3) { session.gearUp() }
            settle()
            advanceSeconds(30)
            assertEquals(123, session.now.power) // +4% per gear: 110 * 1.12

            repeat(6) { session.gearDown() }
            settle()
            advanceSeconds(30)
            assertEquals(97, session.now.power) // 110 * 0.88
        }

    @Test
    fun `an invalid FTP or tick interval is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { newSession(ftp = 0) }
            assertFailsWith<IllegalArgumentException> { newSession(tickInterval = 0.milliseconds) }
        }
}
