package app.overdrive.core.workout

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.flatSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The heart-rate step in the model, in the file format and in the player (spec §11.2, docs/adr/0033). */
class HeartRateStepTest {
    private fun fileWith(step: String) = """{"schema":1,"id":"a","name":"A","steps":[$step]}"""

    @Test
    fun `a step is read from the native format with the band defaulting to 5`() {
        val workout =
            WorkoutImport.fromJson(
                fileWith(
                    """{"type":"hr","duration":2400,"target_bpm":135,"power_min":80,"power_max":220,"label":"Z2"}""",
                ),
            )
        assertEquals(
            HeartRateStep(duration = 2400, targetBpm = 135, band = 5, powerMin = 80, powerMax = 220, label = "Z2"),
            workout.steps.single(),
        )
    }

    @Test
    fun `a target as a fraction of the threshold is read as such`() {
        val workout =
            WorkoutImport.fromJson(
                fileWith(
                    """{"type":"hr","duration":600,"target_lthr":0.88,"band":3,"power_min":100,"power_max":260}""",
                ),
            )
        val step = assertIs<HeartRateStep>(workout.steps.single())
        assertEquals(0.88, step.targetLthr)
        assertNull(step.targetBpm)
        assertEquals(3, step.band)
    }

    @Test
    fun `the file is refused when it breaks a rule of the step`() {
        val bad =
            listOf(
                """{"type":"hr","duration":600,"power_min":80,"power_max":220}""",
                """{"type":"hr","duration":600,"target_bpm":135,"target_lthr":0.9,"power_min":80,"power_max":220}""",
                """{"type":"hr","duration":600,"target_bpm":135,"power_min":220,"power_max":80}""",
                """{"type":"hr","duration":600,"target_bpm":135,"power_min":80}""",
                """{"type":"hr","duration":600,"target_bpm":30,"power_min":80,"power_max":220}""",
                """{"type":"hr","duration":600,"target_bpm":135,"band":0,"power_min":80,"power_max":220}""",
            )
        for (step in bad) assertFailsWith<WorkoutParseException>(step) { WorkoutImport.fromJson(fileWith(step)) }
    }

    @Test
    fun `a step inside a repeat flattens with its iteration`() {
        val workout =
            WorkoutImport.fromJson(
                fileWith(
                    """{"type":"repeat","count":2,"steps":[
                        {"type":"hr","duration":300,"target_bpm":140,"power_min":90,"power_max":200,"label":"Tempo"},
                        {"type":"free","duration":60}]}""",
                ),
            )
        val flat = workout.flatSteps()
        assertEquals(listOf("Tempo", "Free ride", "Tempo", "Free ride"), flat.map { it.label })
        assertEquals(
            listOf(
                TrainerMode.ERG,
                TrainerMode.SIMULATION,
                TrainerMode.ERG,
                TrainerMode.SIMULATION,
            ),
            flat.map {
                it.mode
            },
        )
        assertEquals(140, flat[2].heartRate?.targetBpm)
        assertEquals(2, flat[2].repeatIteration)
        assertEquals(360, flat[2].startSeconds)
    }

    // region the player

    private fun player(vararg steps: LeafStep) =
        WorkoutPlayer(Workout(1, "w", "W", steps = steps.toList()).flatSteps(), ftp = 200)

    private val zone2 = HeartRateStep(duration = 600, targetBpm = 135, powerMin = 80, powerMax = 220)

    @Test
    fun `in a heart-rate step the target is ERG at the power the controller asked for`() {
        val player = player(zone2)
        assertEquals(TrainerMode.ERG, player.mode)
        player.setHeartRateWatts(95)
        assertEquals(PlayerTarget.Erg(watts = 95, nominalWatts = 95), player.target)
        player.setHeartRateWatts(110)
        assertEquals(110, assertIs<PlayerTarget.Erg>(player.target).nominalWatts)
    }

    @Test
    fun `the intensity does not apply to a heart-rate step`() {
        val player = player(zone2)
        player.setHeartRateWatts(100)
        assertFalse(player.intensityUp())
        assertFalse(player.intensityDown())
        assertEquals(RideSnapshot.DEFAULT_INTENSITY_PERCENT, player.intensityPercent)
        assertEquals(100, assertIs<PlayerTarget.Erg>(player.target).watts)
    }

    @Test
    fun `after a pause the ramp-in climbs to the controller's power`() {
        val player = player(zone2)
        player.setHeartRateWatts(150)
        player.beginRampIn(fromWatts = 30)
        assertEquals(PlayerTarget.Erg(watts = 30, nominalWatts = 150), player.target)
        player.advance()
        assertEquals(70, assertIs<PlayerTarget.Erg>(player.target).watts)
        player.advance(2)
        assertEquals(PlayerTarget.Erg(watts = 150, nominalWatts = 150), player.target)
    }

    @Test
    fun `the next step takes over from a heart-rate step with its own kind of target`() {
        val player = player(zone2.copy(duration = 10), SteadyStep(duration = 10, target = 0.5))
        player.setHeartRateWatts(120)
        player.advance(10)
        assertEquals(PlayerTarget.Erg(watts = 100, nominalWatts = 100), player.target, "50% of FTP 200")
        assertTrue(player.intensityUp())
    }

    // endregion

    @Test
    fun `a snapshot says nothing about the guard or the heart rate until they apply`() {
        val snapshot = RideSnapshot(state = RideState.RIDING, ftp = 200)
        assertFalse(snapshot.stallGuardActive)
        assertNull(snapshot.stallGuardWatts)
        assertNull(snapshot.heartRateHold)
        assertFailsWith<IllegalArgumentException> { RideSnapshot(RideState.RIDING, 200, stallGuardWatts = 100) }
    }
}
