package app.overdrive.core.workout

import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.flatSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkoutPlayerTest {
    /** 60 s: ramp 0..10 (50% to 100%), steady 10..30 (80%), free 30..40, repeat 40..60 (120% then 50%, 5 s each, twice). */
    private val workout =
        Workout(
            schema = 1,
            id = "player",
            name = "Player test",
            steps =
                listOf(
                    RampStep(duration = 10, from = 0.5, to = 1.0, label = "Ramp"),
                    SteadyStep(duration = 20, target = 0.8, label = "Steady"),
                    FreeStep(duration = 10, label = "Free"),
                    RepeatStep(
                        count = 2,
                        steps = listOf(SteadyStep(duration = 5, target = 1.2), SteadyStep(duration = 5, target = 0.5)),
                    ),
                ),
        )

    private fun player(
        ftp: Int = 200,
        rampInSeconds: Int = 3,
    ) = WorkoutPlayer(workout.flatSteps(), ftp, rampInSeconds)

    private fun WorkoutPlayer.erg(): PlayerTarget.Erg = target as PlayerTarget.Erg

    @Test
    fun `it starts on the first step with the ERG target of its first second`() {
        val player = player()
        assertEquals(0, player.stepIndex)
        assertEquals(0, player.positionSeconds)
        assertEquals(60, player.totalSeconds)
        assertEquals(TrainerMode.ERG, player.mode)
        assertEquals(PlayerTarget.Erg(watts = 100, nominalWatts = 100), player.target)
        assertFalse(player.isComplete)
    }

    @Test
    fun `a ramp interpolates linearly over its step and the next step takes over at its end`() {
        val player = player()
        player.advance(5)
        assertEquals(150, player.erg().watts) // 75% of 200 W
        player.advance(4)
        assertEquals(190, player.erg().watts) // 95%, one second before the end
        player.advance(1)
        assertEquals(1, player.stepIndex)
        assertEquals(160, player.erg().watts) // the steady 80%
    }

    @Test
    fun `a steady step holds and a free-ride step has no target`() {
        val player = player()
        player.advance(10)
        repeat(20) {
            assertEquals(160, player.erg().watts)
            player.advance()
        }
        assertEquals(2, player.stepIndex)
        assertEquals(PlayerTarget.Free, player.target)
        assertEquals(TrainerMode.SIMULATION, player.mode)
    }

    @Test
    fun `advancing across several steps at once lands in the right one`() {
        val player = player()
        player.advance(42)
        assertEquals(3, player.stepIndex)
        assertEquals(2, player.stepElapsedSeconds)
        assertEquals(3, player.stepRemainingSeconds)
        assertEquals(240, player.erg().watts) // 120%
        player.advance(3)
        assertEquals(4, player.stepIndex)
        assertEquals(100, player.erg().watts) // 50% in the recovery
    }

    @Test
    fun `intensity scales ERG targets in steps of five between 50 and 150`() {
        val player = player()
        player.advance(10) // steady 80% = 160 W
        assertTrue(player.intensityUp())
        assertEquals(105, player.intensityPercent)
        assertEquals(PlayerTarget.Erg(watts = 168, nominalWatts = 168), player.target)
        repeat(9) { assertTrue(player.intensityUp()) }
        assertEquals(150, player.intensityPercent)
        assertFalse(player.intensityUp(), "clamped at 150")
        assertEquals(240, player.erg().nominalWatts)

        repeat(20) { assertTrue(player.intensityDown()) }
        assertEquals(50, player.intensityPercent)
        assertFalse(player.intensityDown(), "clamped at 50")
        assertEquals(80, player.erg().nominalWatts)
    }

    @Test
    fun `intensity applies to ramps too and does nothing in a free-ride step`() {
        val player = player()
        player.intensityDown()
        player.advance(5)
        assertEquals(143, player.erg().watts) // 75% of 200 W at 95%: 142.5 rounds up

        player.advance(25) // into the free ride
        assertEquals(TrainerMode.SIMULATION, player.mode)
        assertFalse(player.intensityUp())
        assertFalse(player.intensityDown())
        assertEquals(95, player.intensityPercent)
        player.advance(10) // the intensity is remembered for the next ERG step
        assertEquals(228, player.erg().watts) // 120% of 200 W at 95%
    }

    @Test
    fun `next interval jumps to the start of the next step and stops at the last one`() {
        val player = player()
        player.advance(4)
        assertTrue(player.nextInterval())
        assertEquals(1, player.stepIndex)
        assertEquals(10, player.positionSeconds)
        repeat(3) { assertTrue(player.nextInterval()) }
        assertEquals(4, player.stepIndex)
        assertEquals(45, player.positionSeconds)
        assertTrue(player.nextInterval())
        assertEquals(5, player.stepIndex)
        assertEquals(50, player.positionSeconds)
        assertTrue(player.nextInterval())
        assertEquals(6, player.stepIndex)
        assertEquals(55, player.positionSeconds)
        assertFalse(player.nextInterval(), "last step")
        assertEquals(6, player.stepIndex)
        assertEquals(55, player.positionSeconds)
    }

    @Test
    fun `previous interval restarts the step after more than three seconds and goes back before that`() {
        val player = player()
        player.advance(10 + 7) // 7 s into the steady step
        player.previousInterval()
        assertEquals(1, player.stepIndex)
        assertEquals(10, player.positionSeconds)

        player.advance(3) // exactly 3 s in is not more than 3 s
        player.previousInterval()
        assertEquals(0, player.stepIndex)
        assertEquals(0, player.positionSeconds)

        player.advance(2)
        player.previousInterval()
        assertEquals(0, player.stepIndex, "the first step has nothing before it, so it restarts")
        assertEquals(0, player.positionSeconds)

        player.advance(4)
        player.previousInterval()
        assertEquals(0, player.positionSeconds, "more than 3 s into the first step: restart")
    }

    @Test
    fun `the target ramps in over three seconds after a pause`() {
        val player = player()
        player.advance(10) // steady 160 W
        player.beginRampIn()
        assertEquals(PlayerTarget.Erg(watts = 0, nominalWatts = 160), player.target)
        player.advance()
        assertEquals(53, player.erg().watts) // 1/3
        assertEquals(160, player.erg().nominalWatts)
        player.advance()
        assertEquals(107, player.erg().watts) // 2/3
        player.advance()
        assertEquals(160, player.erg().watts)
        player.advance()
        assertEquals(160, player.erg().watts)
    }

    @Test
    fun `the ramp-in follows the target and the intensity, and a ramp-in of zero seconds is off`() {
        val player = player()
        player.advance(10) // the steady step, 160 W
        player.intensityUp() // 105%: 168 W
        player.beginRampIn()
        player.advance()
        assertEquals(168, player.erg().nominalWatts)
        assertEquals(56, player.erg().watts) // a third of it

        val noRamp = player(rampInSeconds = 0)
        noRamp.beginRampIn()
        assertEquals(100, noRamp.erg().watts)
    }

    @Test
    fun `there is no ramp-in at the start, nor in a free-ride step`() {
        val player = player()
        assertEquals(100, player.erg().watts)
        player.advance(30)
        player.beginRampIn()
        assertEquals(PlayerTarget.Free, player.target)
    }

    @Test
    fun `a ramp-in spanning a step change keeps going`() {
        val player = player()
        player.advance(9)
        player.beginRampIn()
        player.advance() // into the steady step, a third of the way in
        assertEquals(1, player.stepIndex)
        assertEquals(53, player.erg().watts)
        player.advance(2)
        assertEquals(160, player.erg().watts)
    }

    @Test
    fun `the workout completes after its last second and then stays put`() {
        val player = player()
        player.advance(59)
        assertFalse(player.isComplete)
        assertEquals(6, player.stepIndex)
        player.advance()
        assertTrue(player.isComplete)
        assertEquals(60, player.positionSeconds)
        assertEquals(6, player.stepIndex)
        assertEquals(0, player.stepRemainingSeconds)
        player.advance(100)
        assertEquals(60, player.positionSeconds)
        assertEquals(60, player.progress("x").workoutElapsedSeconds)
    }

    @Test
    fun `progress reports where the workout is`() {
        val player = player()
        player.advance(14)
        val progress = player.progress("Player test")
        assertEquals("Player test", progress.workoutName)
        assertEquals(1, progress.stepIndex)
        assertEquals("Steady", progress.stepLabel)
        assertEquals("Free", progress.nextStepLabel)
        assertEquals(4, progress.stepElapsedSeconds)
        assertEquals(16, progress.stepRemainingSeconds)
        assertEquals(14, progress.workoutElapsedSeconds)
        assertEquals(46, progress.workoutRemainingSeconds)
        assertEquals(60, progress.workoutTotalSeconds)
        assertTrue(progress.steps === player.steps)
    }

    @Test
    fun `it is deterministic`() {
        fun run(): List<PlayerTarget> {
            val player = player()
            return buildList {
                repeat(60) {
                    add(player.target)
                    if (it == 20) player.intensityUp()
                    if (it == 25) player.beginRampIn()
                    player.advance()
                }
            }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertFailsWith<IllegalArgumentException> { WorkoutPlayer(emptyList(), 200) }
        assertFailsWith<IllegalArgumentException> { WorkoutPlayer(workout.flatSteps(), 0) }
        assertFailsWith<IllegalArgumentException> { WorkoutPlayer(workout.flatSteps(), 200, rampInSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { player().advance(-1) }
    }
}
