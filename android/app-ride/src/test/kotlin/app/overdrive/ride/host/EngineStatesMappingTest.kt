package app.overdrive.ride.host

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.HeartRateHoldState
import app.overdrive.core.ride.HeartRateTarget
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.ride.overlay.OverlayPolicy
import app.overdrive.ride.ui.strapNotice
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.overlay.HoldPhase
import app.overdrive.uikit.overlay.HoldPlace
import app.overdrive.uikit.overlay.OverlayControl
import app.overdrive.uikit.overlay.holdWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the overlay shows of the engine's new states (docs/adr/0034): the stall guard, a heart-rate step and the previous-attempt line, and
 * how the words read. The mapping is pure, so the rules are here and the pictures are only looked at.
 */
class EngineStatesMappingTest {
    private val hold = HeartRateTarget(targetBpm = 135, band = 5, powerMin = 80, powerMax = 220)

    private val steps =
        listOf(
            FlatStep("Warm-up", 300, 0.45, 0.60, TrainerMode.ERG),
            FlatStep("Zone 2 by heart rate", 1800, null, null, TrainerMode.ERG, startSeconds = 300, heartRate = hold),
            FlatStep("Cool-down", 300, 0.50, 0.40, TrainerMode.ERG, startSeconds = 2100),
        )
    private val profile = WorkoutDescriber.profileOf(steps)

    private fun progress(index: Int) =
        WorkoutProgress(
            "Base",
            steps,
            index,
            100,
            steps[index].durationSeconds - 100,
            steps[index].startSeconds + 100,
            1000,
        )

    private fun holdState(
        targetBpm: Int = 135,
        inBand: Boolean? = true,
        startRamp: Boolean = false,
        signalLost: Boolean = false,
        strapLost: Boolean = false,
    ) = HeartRateHoldState(targetBpm, 5, inBand, startRamp, signalLost, strapLost, 80, 220)

    private fun heartRateSnapshot(
        heartRate: Int? = 133,
        state: HeartRateHoldState? = holdState(),
        guard: Int? = null,
        power: Int? = 142,
    ) = RideSnapshot(
        state = RideState.RIDING,
        ftp = 250,
        elapsedSeconds = 400,
        power = power,
        heartRate = heartRate,
        cadence = 88,
        mode = TrainerMode.ERG,
        targetPower = 142,
        heartRateHold = state,
        stallGuardActive = guard != null,
        stallGuardWatts = guard,
        workout = progress(1),
    )

    private fun ergSnapshot(guard: Int? = null) =
        RideSnapshot(
            state = RideState.RIDING,
            ftp = 250,
            elapsedSeconds = 100,
            power = 151,
            cadence = 48,
            mode = TrainerMode.ERG,
            targetPower = 220,
            intensityPercent = 105,
            stallGuardActive = guard != null,
            stallGuardWatts = guard,
            workout = progress(0),
        )

    // ---- The stall guard --------------------------------------------------------------------------------------------

    @Test
    fun theGuardTakesTheFifthCellWithWhatTheTrainerIsHeldAt() {
        val state = RideMapping.overlayState(ergSnapshot(guard = 154), profile)
        assertEquals(OverlayControl.Guard(154), state.control)
        assertNull("no heart-rate line outside a heart-rate step", state.hold)
    }

    @Test
    fun withoutTheGuardTheCellIsTheTargetAndTheIntensityAsBefore() {
        assertEquals(OverlayControl.Target(220, 105), RideMapping.overlayState(ergSnapshot(), profile).control)
    }

    @Test
    fun theGuardIsOnlyShownInAnErgStep() {
        val free = ergSnapshot(guard = 154).copy(mode = TrainerMode.SIMULATION, gear = 12, targetPower = null)
        assertEquals(OverlayControl.Gear(12), RideMapping.overlayState(free, null).control)
    }

    // ---- A heart-rate step ------------------------------------------------------------------------------------------

    @Test
    fun aHeartRateStepShowsTheControllersPowerWithNoIntensityAndTheLine() {
        val state = RideMapping.overlayState(heartRateSnapshot(), profile)
        assertEquals(OverlayControl.Target(142, null), state.control)
        val hold = checkNotNull(state.hold)
        assertEquals(135, hold.targetBpm)
        assertEquals(5, hold.band)
        assertEquals(0, hold.offsetBpm)
        assertEquals(HoldPlace.In, hold.place)
        assertEquals(HoldPhase.Holding, hold.phase)
        assertEquals(142, hold.watts)
        assertEquals(80, hold.minimumWatts)
    }

    @Test
    fun theGuardWinsTheCellInAHeartRateStepToo() {
        val state = RideMapping.overlayState(heartRateSnapshot(guard = 99), profile)
        assertEquals(OverlayControl.Guard(99), state.control)
        assertNotNull("and the line stays", state.hold)
    }

    @Test
    fun outOfTheBandIsAboveOrBelowByTheLiveHeartRate() {
        val above =
            RideMapping.overlayState(
                heartRateSnapshot(heartRate = 143, state = holdState(inBand = false)),
                profile,
            )
        assertEquals(HoldPlace.Above, above.hold?.place)
        val below =
            RideMapping.overlayState(
                heartRateSnapshot(heartRate = 126, state = holdState(inBand = false)),
                profile,
            )
        assertEquals(HoldPlace.Below, below.hold?.place)
    }

    @Test
    fun theTargetMovedByTheGearButtonsShowsAsTheTargetChangingWithItsOffset() {
        val up = RideMapping.overlayState(heartRateSnapshot(state = holdState(targetBpm = 137)), profile)
        assertEquals(137, up.hold?.targetBpm)
        assertEquals(2, up.hold?.offsetBpm)
        val down = RideMapping.overlayState(heartRateSnapshot(state = holdState(targetBpm = 131)), profile)
        assertEquals(-4, down.hold?.offsetBpm)
    }

    @Test
    fun theStartRampIsNotJudged() {
        val easing =
            RideMapping.overlayState(
                heartRateSnapshot(heartRate = 104, state = holdState(inBand = false, startRamp = true)),
                profile,
            )
        assertEquals(HoldPhase.EasingIn, easing.hold?.phase)
        assertNull("the heart rate is not judged while the ramp runs", easing.hold?.place)
    }

    @Test
    fun noHeartRateIsNotAPlace() {
        val none =
            RideMapping.overlayState(
                heartRateSnapshot(heartRate = null, state = holdState(inBand = null)),
                profile,
            )
        assertNull(none.hold?.place)
        assertEquals(HoldPhase.Holding, none.hold?.phase)
    }

    @Test
    fun theStrapStatesAreWordsAndNeverAPlace() {
        val lost =
            RideMapping.overlayState(
                heartRateSnapshot(heartRate = null, state = holdState(inBand = null, signalLost = true)),
                profile,
            )
        assertEquals(HoldPhase.StrapLost, lost.hold?.phase)
        assertNull(lost.hold?.place)
        val dropped =
            RideMapping.overlayState(
                heartRateSnapshot(
                    heartRate = null,
                    state = holdState(inBand = null, signalLost = true, strapLost = true),
                    power = 81,
                ).copy(targetPower = 80),
                profile,
            )
        assertEquals(HoldPhase.StrapDropped, dropped.hold?.phase)
        assertEquals(80, dropped.hold?.watts)
    }

    @Test
    fun aPowerStepAndAFreeRideHaveNoHeartRateLine() {
        assertNull(RideMapping.overlayState(ergSnapshot(), profile).hold)
        assertNull(RideMapping.hold(RideSnapshot(RideState.RIDING, ftp = 250)))
    }

    // ---- The words --------------------------------------------------------------------------------------------------

    private fun words(snapshot: RideSnapshot) =
        holdWords(checkNotNull(RideMapping.overlayState(snapshot, profile).hold))

    @Test
    fun theWordsSayWhereTheHeartRateIsAndNotOnlyByColour() {
        assertEquals("Target 135 bpm ±5 · In band", words(heartRateSnapshot()))
        assertEquals(
            "Target 137 bpm (+2) ±5 · Above band",
            words(heartRateSnapshot(heartRate = 143, state = holdState(targetBpm = 137, inBand = false))),
        )
        assertEquals(
            "Target 133 bpm (−2) ±5 · Below band",
            words(heartRateSnapshot(heartRate = 120, state = holdState(targetBpm = 133, inBand = false))),
        )
        assertEquals(
            "Easing in · target 135 bpm ±5",
            words(heartRateSnapshot(heartRate = 100, state = holdState(inBand = false, startRamp = true))),
        )
        assertEquals(
            "No heart rate · target 135 bpm",
            words(heartRateSnapshot(heartRate = null, state = holdState(inBand = null))),
        )
    }

    @Test
    fun strapLostSaysWhatThePowerIsDoing() {
        val lost = heartRateSnapshot(heartRate = null, state = holdState(inBand = null, signalLost = true))
        assertEquals("Strap lost · holding 142 W", words(lost))
        val dropped =
            lost.copy(
                heartRateHold = holdState(inBand = null, signalLost = true, strapLost = true),
                targetPower = 80,
            )
        assertEquals("Strap lost · power at the minimum, 80 W", words(dropped))
    }

    @Test
    fun theRidingScreenSaysTheSameOfTheStrapAndNothingOtherwise() {
        assertNull(strapNotice(heartRateSnapshot()))
        assertNull(strapNotice(ergSnapshot()))
        assertEquals(
            "Strap lost · holding 142 W",
            strapNotice(heartRateSnapshot(heartRate = null, state = holdState(inBand = null, signalLost = true))),
        )
    }

    // ---- The previous-attempt line ----------------------------------------------------------------------------------

    @Test
    fun thePreviousLineTravelsWithTheIntervalSectionAndIsNullWithoutOne() {
        assertNull(RideMapping.overlayState(ergSnapshot(), profile).interval?.previousLine)
        val line = RideMapping.overlayState(ergSnapshot(), profile, "Heart rate 3 below last time")
        assertEquals("Heart rate 3 below last time", line.interval?.previousLine)
        // No workout, no interval section, nowhere to put it.
        val free = RideSnapshot(RideState.RIDING, ftp = 250)
        assertNull(RideMapping.overlayState(free, null, "Heart rate 3 below last time").interval)
    }

    // ---- What the overlay shows at once -----------------------------------------------------------------------------

    @Test
    fun aGearButtonThatMovesTheTargetHeartRateIsShownAtOnce() {
        val before = RideMapping.overlayState(heartRateSnapshot(), profile)
        val moved = RideMapping.overlayState(heartRateSnapshot(state = holdState(targetBpm = 137)), profile)
        assertTrue(OverlayPolicy.isRiderAction(before, moved))
        // A heart rate that moved by itself waits for its turn.
        val drifted = RideMapping.overlayState(heartRateSnapshot(heartRate = 134), profile)
        assertFalse(OverlayPolicy.isRiderAction(before, drifted))
    }

    @Test
    fun theGuardTakingHoldAndLettingGoAreShownAtOnce() {
        val plain = RideMapping.overlayState(ergSnapshot(), profile)
        val held = RideMapping.overlayState(ergSnapshot(guard = 154), profile)
        assertTrue(OverlayPolicy.isRiderAction(plain, held))
        assertTrue(OverlayPolicy.isRiderAction(held, plain))
    }

    @Test
    fun aHeartRateStepStartingIsShownAtOnce() {
        val warmUp = RideMapping.overlayState(ergSnapshot().copy(workout = progress(0)), profile)
        val step = RideMapping.overlayState(heartRateSnapshot(), profile)
        assertTrue(OverlayPolicy.isRiderAction(warmUp, step))
    }
}
