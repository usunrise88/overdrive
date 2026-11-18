package app.overdrive.core.ride

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/** Three one-minute steps: ERG at 50% FTP, ERG at 80% FTP, free ride. */
internal val abcWorkout =
    Workout(
        schema = 1,
        id = "abc",
        name = "ABC",
        steps =
            listOf(
                SteadyStep(duration = 60, target = 0.5, label = "A"),
                SteadyStep(duration = 60, target = 0.8, label = "B"),
                FreeStep(duration = 60, label = "C"),
            ),
    )

/** No random noise and no drift, so signals are smooth and exact. */
internal val quietScenario = SimulationScenario(noise = 0.0)

internal fun TestScope.newSession(
    workout: Workout? = null,
    scenario: SimulationScenario = quietScenario,
    seed: Long = 1L,
    ftp: Int = 200,
    startConnected: Boolean = true,
    tickInterval: Duration = Duration.parse("1s"),
    speedModel: SpeedModel = PlaceholderSpeedModel,
): SimulatedRideSession =
    SimulatedRideSession(
        scope = backgroundScope,
        workout = workout,
        ftp = ftp,
        seed = seed,
        scenario = scenario,
        speedModel = speedModel,
        tickInterval = tickInterval,
        startConnected = startConnected,
    )

/** Lets the session process the commands issued so far. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.settle() = runCurrent()

/** Advances virtual time by [seconds] and lets every tick scheduled up to and including that moment run. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.advanceSeconds(seconds: Int) {
    advanceTimeBy(seconds * 1000L)
    runCurrent()
}

/** Collects every snapshot the session publishes, starting with the current one. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.record(session: RideSession): List<RideSnapshot> {
    val seen = mutableListOf<RideSnapshot>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.snapshots.collect { seen += it } }
    return seen
}

internal val RideSession.now: RideSnapshot get() = snapshots.value

/** Starts a ride and lets [seconds] of ride time pass. */
internal fun TestScope.ride(
    session: RideSession,
    seconds: Int,
) {
    session.start()
    settle()
    advanceSeconds(seconds)
}
