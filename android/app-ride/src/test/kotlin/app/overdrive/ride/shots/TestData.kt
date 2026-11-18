package app.overdrive.ride.shots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.PowerZone
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.workout.Workout
import app.overdrive.data.rides.StoredRide
import app.overdrive.data.rides.StoredRideStatus
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.FinishedRide
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.ride.workouts.WorkoutLibrary
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.preview.PreviewSamples
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Synthetic rides for the screenshot tests: the numbers of the design mock-ups. Nothing here is a real ride. */
object TestData {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    val library: WorkoutLibrary by lazy { WorkoutLibrary.fromAssets(context.assets) }

    val sweetSpot: Workout get() = library.workouts.value.first { it.id == "2026-11-20-sweet-spot" }

    val steps: List<FlatStep> by lazy { sweetSpot.flatSteps() }

    fun progress(workoutSeconds: Int): WorkoutProgress {
        val index = steps.indexOfLast { it.startSeconds <= workoutSeconds }
        val step = steps[index]
        val total = steps.last().endSeconds
        return WorkoutProgress(
            workoutName = sweetSpot.name,
            steps = steps,
            stepIndex = index,
            stepElapsedSeconds = workoutSeconds - step.startSeconds,
            stepRemainingSeconds = step.endSeconds - workoutSeconds,
            workoutElapsedSeconds = workoutSeconds,
            workoutRemainingSeconds = total - workoutSeconds,
        )
    }

    /** 32:17 into Sweet spot 3×10 at 223 W (Z3), target 220 W: the mock-up `ride--riding`. */
    fun riding(
        state: RideState = RideState.RIDING,
        pauseReason: PauseReason? = null,
    ): RideSnapshot =
        RideSnapshot(
            state = state,
            ftp = 250,
            elapsedSeconds = 1937,
            power = 223,
            heartRate = 148,
            cadence = 91,
            speedKmh = 34.6,
            distanceMeters = 18_420.0,
            mode = TrainerMode.ERG,
            targetPower = 220,
            zone = PowerZone.Z3,
            workout = progress(1937),
            pauseReason = pauseReason,
        )

    fun freeRide(): RideSnapshot =
        RideSnapshot(
            state = RideState.RIDING,
            ftp = 250,
            elapsedSeconds = 3849,
            power = 182,
            heartRate = 139,
            cadence = 87,
            speedKmh = 31.9,
            distanceMeters = 35_070.0,
            mode = TrainerMode.SIMULATION,
            gear = 12,
            zone = PowerZone.Z3,
        )

    fun ui(
        snapshot: RideSnapshot,
        choice: RideChoice,
        profile: Boolean = true,
    ) = RideUi(
        snapshot = snapshot,
        choice = choice,
        profile = if (profile) WorkoutDescriber.profileOf(steps) else null,
        averagePower = 204,
        lapAveragePower = 218,
        trace = if (profile) PreviewSamples.trace else emptyList(),
        finished = null,
        rideNumber = 1,
    )

    /** A whole simulated Sweet spot 3x10 ridden by the real engine in virtual time, then summarised by the host. */
    fun finishedRide(): FinishedRide {
        var result: FinishedRide? = null
        runTest {
            val host =
                RideHost(
                    SimulatedRideSessionFactory(),
                    flowOf(AppSettings(ftp = 250)),
                    RideChoice.Planned(sweetSpot),
                    StandardTestDispatcher(testScheduler),
                )
            runCurrent()
            host.actions.start()
            advanceTimeBy(3_905_000)
            runCurrent()
            result = host.ui.value.finished
            host.close()
        }
        return checkNotNull(result)
    }

    /** The mock-up's ride, as the store lists it: Wednesday 18 November 2026, 18:04 in the test's zone. */
    fun storedRide(
        status: StoredRideStatus = StoredRideStatus.Saved,
        durationSeconds: Int = 3900,
        fitBytes: Long? = 312_480,
        title: String = "Sweet spot 3×10",
    ) = StoredRide(
        id = "ride-1794996000000",
        title = title,
        startEpochMillis = 1_794_996_000_000L,
        durationSeconds = durationSeconds,
        status = status,
        fitBytes = fitBytes,
    )

    val devicesOk =
        DevicesUi(
            DeviceRowUi(DeviceStatus.Connected, "Simulated trainer · ERG and simulation"),
            DeviceRowUi(DeviceStatus.Connected, "Simulated strap"),
            DeviceRowUi(DeviceStatus.Connected, "Simulated Click"),
        )

    /** Like the mock-up `devices--pairing`: the strap is being searched for and the Click is locked. */
    val devicesPairing =
        DevicesUi(
            DeviceRowUi(DeviceStatus.Connected, "Simulated trainer · ERG and simulation"),
            DeviceRowUi(DeviceStatus.Searching, "Searching for heart-rate straps…"),
            DeviceRowUi(DeviceStatus.Locked, "Simulated Click · connected"),
        )
}
