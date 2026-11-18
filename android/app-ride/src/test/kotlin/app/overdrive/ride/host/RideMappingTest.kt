package app.overdrive.ride.host

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.PowerZone
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.ride.settings.SimulatedClick
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.overlay.OverlayControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapping from the engine's snapshot to the overlay's plain state (ADR 0005). */
class RideMappingTest {
    private val steps =
        listOf(
            FlatStep("Warm-up", 600, 0.45, 0.70, TrainerMode.ERG, startSeconds = 0),
            FlatStep("Sweet spot", 600, 0.88, 0.88, TrainerMode.ERG, startSeconds = 600, repeatIteration = 1, repeatCount = 3),
            FlatStep("Recover", 300, 0.50, 0.50, TrainerMode.ERG, startSeconds = 1200, repeatIteration = 1, repeatCount = 3),
            FlatStep("Free ride", 300, null, null, TrainerMode.SIMULATION, startSeconds = 1500),
        )
    private val profile = WorkoutDescriber.profileOf(steps)

    private fun progress(index: Int) =
        WorkoutProgress(
            workoutName = "Sweet spot 3x10",
            steps = steps,
            stepIndex = index,
            stepElapsedSeconds = 100,
            stepRemainingSeconds = steps[index].durationSeconds - 100,
            workoutElapsedSeconds = steps[index].startSeconds + 100,
            workoutRemainingSeconds = 1800 - steps[index].startSeconds - 100,
        )

    private fun ergSnapshot(
        state: RideState = RideState.RIDING,
        power: Int? = 223,
    ) = RideSnapshot(
        state = state,
        ftp = 250,
        elapsedSeconds = 700,
        power = power,
        heartRate = 148,
        cadence = 91,
        mode = TrainerMode.ERG,
        targetPower = 220,
        intensityPercent = 105,
        zone = power?.let { PowerZone.of(it, 250) },
        workout = progress(1),
    )

    @Test
    fun ergStepShowsTargetAndIntensityAndTheZoneNumber() {
        val state = RideMapping.overlayState(ergSnapshot(), profile)
        assertEquals(OverlayControl.Target(watts = 220, intensityPercent = 105), state.control)
        assertEquals(3, state.zone)
        assertEquals(223, state.power)
        assertFalse(state.paused)
    }

    @Test
    fun simulationStepShowsTheGear() {
        val snapshot =
            RideSnapshot(
                state = RideState.RIDING,
                ftp = 250,
                power = 182,
                mode = TrainerMode.SIMULATION,
                gear = 12,
                zone = PowerZone.Z3,
            )
        assertEquals(OverlayControl.Gear(12), RideMapping.overlayState(snapshot, null).control)
    }

    @Test
    fun anyPauseDimsTheOverlayWhateverItsReason() {
        for (reason in PauseReason.entries) {
            val paused = ergSnapshot(RideState.PAUSED).copy(pauseReason = reason)
            assertTrue(RideMapping.overlayState(paused, profile).paused)
        }
        assertFalse(RideMapping.overlayState(ergSnapshot(RideState.READY), profile).paused)
    }

    @Test
    fun missingSignalsStayNullSoTheOverlayDrawsADash() {
        val snapshot = ergSnapshot(power = null).copy(heartRate = null, cadence = null)
        val state = RideMapping.overlayState(snapshot, profile)
        assertNull(state.power)
        assertNull(state.zone)
        assertNull(state.heartRate)
        assertNull(state.cadence)
    }

    @Test
    fun intervalSectionNamesTheStepItsRepeatAndWhatIsNext() {
        val interval = checkNotNull(RideMapping.overlayState(ergSnapshot(), profile).interval)
        assertEquals("Sweet spot", interval.name)
        assertEquals("1 of 3", interval.repeatText)
        assertEquals(500, interval.remainingSeconds)
        // Next is the recovery at 50%, scaled by the 105% intensity: 0.5 * 250 * 1.05 = 131.25 W.
        assertEquals("Recover · 5:00 · 131 W", interval.next)
        assertEquals(700, interval.nowSeconds)
    }

    @Test
    fun theLastStepsNextIsFinishAndAFreeStepIsNamedByItsLabel() {
        val last = ergSnapshot().copy(workout = progress(3), mode = TrainerMode.SIMULATION, targetPower = null, gear = 12)
        assertEquals("Finish", checkNotNull(RideMapping.overlayState(last, profile).interval).next)
        val beforeFree = ergSnapshot().copy(workout = progress(2))
        assertEquals("Free ride · 5:00", checkNotNull(RideMapping.overlayState(beforeFree, profile).interval).next)
    }

    @Test
    fun aRideWithoutAWorkoutHasNoIntervalSection() {
        val free = RideSnapshot(RideState.RIDING, ftp = 250, mode = TrainerMode.SIMULATION, gear = 12)
        assertNull(RideMapping.overlayState(free, null).interval)
    }

    @Test
    fun repeatTextOnlyForRealRepeats() {
        assertEquals("1 of 3", RideMapping.repeatText(ergSnapshot()))
        assertNull(RideMapping.repeatText(ergSnapshot().copy(workout = progress(0))))
    }

    @Test
    fun deviceRowsFollowTheLinkStates() {
        val ok = DeviceRows.of("Simulated trainer", ConnectionState.CONNECTED, "Simulated strap", ConnectionState.CONNECTED, SimulatedClick.Connected)
        assertEquals(DeviceStatus.Connected, ok.trainer.status)
        assertEquals(DeviceStatus.Connected, ok.heartRate.status)
        assertEquals(DeviceStatus.Connected, ok.click.status)

        val trouble = DeviceRows.of("T", ConnectionState.DISCONNECTED, "S", ConnectionState.CONNECTING, SimulatedClick.Locked)
        assertEquals(DeviceStatus.Lost, trouble.trainer.status)
        assertEquals(DeviceStatus.Searching, trouble.heartRate.status)
        assertEquals(DeviceStatus.Locked, trouble.click.status)

        val none = DeviceRows.of("T", ConnectionState.CONNECTING, null, null, SimulatedClick.NotPaired)
        assertEquals(DeviceStatus.Searching, none.trainer.status)
        assertEquals(DeviceStatus.NotPaired, none.heartRate.status)
        assertEquals(DeviceStatus.NotPaired, none.click.status)
    }
}
