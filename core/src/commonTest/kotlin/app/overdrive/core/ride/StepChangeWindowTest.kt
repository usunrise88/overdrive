package app.overdrive.core.ride

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The window around a step change (spec §11.3, docs/adr/0034): a table of cases, then the rider's tap. */
class StepChangeWindowTest {
    private fun workout(vararg steps: Pair<Int, Double?>): Workout =
        Workout(
            schema = 1,
            id = "w",
            name = "Test",
            steps =
                steps.map { (seconds, target) ->
                    if (target ==
                        null
                    ) {
                        FreeStep(seconds)
                    } else {
                        SteadyStep(seconds, target)
                    }
                },
        )

    private fun player(workout: Workout) = WorkoutPlayer(workout.flatSteps(), ftp = 200)

    private fun snapshot(
        player: WorkoutPlayer,
        state: RideState = RideState.RIDING,
        withWorkout: Boolean = true,
    ) = RideSnapshot(
        state = state,
        ftp = 200,
        workout = if (withWorkout) player.progress("Test") else null,
    )

    /** The player advanced to [step] and [elapsed] seconds into it. */
    private fun at(
        workout: Workout,
        step: Int,
        elapsed: Int,
    ): WorkoutPlayer {
        val p = player(workout)
        val target = p.steps[step].startSeconds + elapsed
        p.advance(target)
        check(p.stepIndex == step && p.stepElapsedSeconds == elapsed) { "no such moment: $step, $elapsed" }
        return p
    }

    // A, B, C: three 60 s steps; the middle one is B.
    private val three = workout(60 to 0.5, 60 to 0.9, 60 to 0.5)

    private fun open(
        p: WorkoutPlayer,
        enabled: Boolean = true,
        state: RideState = RideState.RIDING,
    ) = StepChangeWindow.isOpen(snapshot(p, state), enabled)

    @Test
    fun `the start of the workout is not a step change`() {
        assertFalse(open(at(three, 0, 0)))
        assertFalse(open(at(three, 0, 1)))
        assertFalse(open(at(three, 0, 30)))
    }

    @Test
    fun `opens 10 s before the next step and stays open to the change`() {
        assertFalse(open(at(three, 0, 49)), "11 s left")
        assertTrue(open(at(three, 0, 50)), "10 s left")
        assertTrue(open(at(three, 0, 59)), "1 s left")
    }

    @Test
    fun `stays open for 10 s after the change and then closes`() {
        assertTrue(open(at(three, 1, 0)), "the second the step begins")
        assertTrue(open(at(three, 1, 9)), "9 s in")
        assertFalse(open(at(three, 1, 10)), "10 s in")
        assertFalse(open(at(three, 1, 49)), "11 s left")
        assertTrue(open(at(three, 1, 50)), "10 s left of the next one")
    }

    @Test
    fun `the last step opens after the change but never before an end that has no change after it`() {
        assertTrue(open(at(three, 2, 0)))
        assertTrue(open(at(three, 2, 9)))
        assertFalse(open(at(three, 2, 10)))
        assertFalse(open(at(three, 2, 50)), "10 s before the end of the workout: nothing follows")
        assertFalse(open(at(three, 2, 59)))
    }

    @Test
    fun `a workout of one step has no change at all`() {
        val one = workout(120 to 0.8)
        for (second in listOf(0, 1, 60, 100, 110, 119)) assertFalse(open(at(one, 0, second)), "second $second")
    }

    @Test
    fun `a step of 20 s or less is expanded from its first second to its last, with no gap`() {
        val short = workout(60 to 0.5, 20 to 0.9, 60 to 0.5)
        for (second in 0 until 20) assertTrue(open(at(short, 1, second)), "20 s step, second $second")
        val shorter = workout(60 to 0.5, 12 to 0.9, 60 to 0.5)
        for (second in 0 until 12) assertTrue(open(at(shorter, 1, second)), "12 s step, second $second")
        val tiny = workout(60 to 0.5, 3 to 0.9, 2 to 0.7, 60 to 0.5)
        for (second in 0 until 3) assertTrue(open(at(tiny, 1, second)), "3 s step, second $second")
        for (second in 0 until 2) assertTrue(open(at(tiny, 2, second)), "2 s step, second $second")
    }

    @Test
    fun `a step of 21 s has the one second between its two windows`() {
        val w = workout(60 to 0.5, 21 to 0.9, 60 to 0.5)
        assertTrue(open(at(w, 1, 9)))
        assertFalse(open(at(w, 1, 10)), "11 s left, 10 s in")
        assertTrue(open(at(w, 1, 11)), "10 s left")
    }

    @Test
    fun `a repeat set and a free-ride step change like any other`() {
        val w =
            Workout(
                schema = 1,
                id = "w",
                name = "Test",
                steps = listOf(RepeatStep(count = 2, steps = listOf(SteadyStep(30, 0.9), FreeStep(30)))),
            )
        // Free ride in the middle of the repeat: expanded at both of its ends.
        assertTrue(open(at(w, 1, 0)))
        assertFalse(open(at(w, 1, 10)))
        assertTrue(open(at(w, 1, 20)))
        // The second pass of the steady step begins after the free step.
        assertTrue(open(at(w, 2, 5)))
        // The last step is a free ride: opened by its beginning only.
        assertTrue(open(at(w, 3, 0)))
        assertFalse(open(at(w, 3, 25)))
    }

    @Test
    fun `a skip to the next step is a step change, a skip back to the first step is not`() {
        val p = at(three, 1, 30)
        assertFalse(open(p))
        p.nextInterval()
        assertTrue(open(p), "the new step has just begun")
        val q = at(three, 1, 30)
        q.previousInterval()
        // Past the restart limit, previous restarts the current step: it has just begun again.
        assertEquals(1, q.stepIndex)
        assertTrue(open(q))
        val r = at(three, 1, 2)
        r.previousInterval()
        assertEquals(0, r.stepIndex)
        assertFalse(open(r), "back at the start of the workout")
    }

    @Test
    fun `a pause freezes the progress, so the window it was in stays open and one that was closed stays closed`() {
        assertTrue(open(at(three, 0, 55), state = RideState.PAUSED))
        assertFalse(open(at(three, 0, 20), state = RideState.PAUSED))
    }

    @Test
    fun `no window while the ride is not live`() {
        for (state in listOf(RideState.IDLE, RideState.READY, RideState.FINISHED)) {
            assertFalse(open(at(three, 0, 55), state = state), state.name)
        }
    }

    @Test
    fun `no window in a free ride without a workout`() {
        val p = player(three)
        assertFalse(StepChangeWindow.isOpen(snapshot(p, withWorkout = false)))
    }

    @Test
    fun `the setting turns it off at every moment`() {
        for ((step, second) in listOf(0 to 55, 1 to 0, 1 to 5, 2 to 3)) {
            assertTrue(open(at(three, step, second)), "on at $step/$second")
            assertFalse(open(at(three, step, second), enabled = false), "off at $step/$second")
        }
    }

    @Test
    fun `the ends of the window can be moved, and not below zero`() {
        val p = at(three, 0, 40)
        assertFalse(StepChangeWindow.isOpen(snapshot(p), leadSeconds = 10))
        assertTrue(StepChangeWindow.isOpen(snapshot(p), leadSeconds = 20))
        assertFalse(StepChangeWindow.isOpen(snapshot(at(three, 1, 0)), leadSeconds = 0, trailSeconds = 0))
        assertFailsWith<IllegalArgumentException> { StepChangeWindow.isOpen(snapshot(p), leadSeconds = -1) }
    }

    // ---- The rider's choice ----------------------------------------------------------------------------------------------

    @Test
    fun `what is shown follows the choice, the window and a dismissal`() {
        val compact = ModeChoice(detailed = false)
        val detailed = ModeChoice(detailed = true)
        val dismissed = ModeChoice(detailed = false, dismissed = true)
        assertFalse(StepChangeWindow.showsDetailed(compact, windowOpen = false))
        assertTrue(StepChangeWindow.showsDetailed(compact, windowOpen = true), "expands around a change")
        assertTrue(StepChangeWindow.showsDetailed(detailed, windowOpen = false))
        assertTrue(StepChangeWindow.showsDetailed(detailed, windowOpen = true))
        assertFalse(StepChangeWindow.showsDetailed(dismissed, windowOpen = true), "the rider collapsed it")
        assertFalse(StepChangeWindow.showsDetailed(dismissed, windowOpen = false))
    }

    @Test
    fun `a tap on a compact overlay chooses detailed for good`() {
        val next = StepChangeWindow.tap(ModeChoice(detailed = false), windowOpen = false)
        assertEquals(ModeChoice(detailed = true), next)
    }

    @Test
    fun `a tap on an overlay that expanded by itself collapses it and leaves the chosen mode alone`() {
        val next = StepChangeWindow.tap(ModeChoice(detailed = false), windowOpen = true)
        assertEquals(ModeChoice(detailed = false, dismissed = true), next)
        assertFalse(StepChangeWindow.showsDetailed(next, windowOpen = true))
    }

    @Test
    fun `a tap on a detailed overlay inside a window collapses it for that window`() {
        val next = StepChangeWindow.tap(ModeChoice(detailed = true), windowOpen = true)
        assertEquals(ModeChoice(detailed = false, dismissed = true), next)
    }

    @Test
    fun `a tap on a detailed overlay outside a window is a plain collapse`() {
        assertEquals(
            ModeChoice(detailed = false),
            StepChangeWindow.tap(ModeChoice(detailed = true), windowOpen = false),
        )
    }

    @Test
    fun `after collapsing, a second tap inside the same window chooses detailed and the return does nothing`() {
        var choice = StepChangeWindow.tap(ModeChoice(detailed = false), windowOpen = true)
        choice = StepChangeWindow.tap(choice, windowOpen = true)
        assertEquals(ModeChoice(detailed = true, dismissed = true), choice)
        // The window closes: the rider's detailed choice stands, the dismissal is forgotten.
        choice = choice.afterWindow(windowOpen = false)
        assertEquals(ModeChoice(detailed = true), choice)
        assertTrue(StepChangeWindow.showsDetailed(choice, windowOpen = false))
    }

    @Test
    fun `a dismissal lasts to the end of its window and the next step change expands again`() {
        val w = workout(60 to 0.5, 60 to 0.9, 60 to 0.5)
        var choice = ModeChoice(detailed = false)
        val shown = mutableListOf<Boolean>()
        // Ride the workout second by second; at the second the step 0 to 1 change is 5 s away the rider taps.
        val p = player(w)
        for (second in 0 until w.flatSteps().totalDurationSeconds()) {
            val windowOpen = StepChangeWindow.isOpen(snapshot(p))
            choice = choice.afterWindow(windowOpen)
            if (second == 55) choice = StepChangeWindow.tap(choice, windowOpen)
            shown += StepChangeWindow.showsDetailed(choice, windowOpen)
            p.advance(1)
        }
        // Window 1 runs from 50 to 69 (10 s before, 10 s after the change at 60): the tap at 55 collapses 55 to 69 only.
        assertTrue(shown[50] && shown[54], "expanded before the tap")
        assertTrue(shown.subList(55, 70).none { it }, "collapsed for the rest of the window")
        // Window 2 (the change at 120) opens at 110 and expands again.
        assertTrue(shown.subList(110, 130).all { it })
        assertFalse(shown[100])
        assertFalse(shown[135])
    }

    @Test
    fun `a dismissal also covers the second window of a step shorter than 20 s, whose two windows touch`() {
        val w = workout(60 to 0.5, 12 to 0.9, 60 to 0.5)
        var choice = ModeChoice(detailed = false)
        val shown = mutableListOf<Boolean>()
        val p = player(w)
        for (second in 0 until w.flatSteps().totalDurationSeconds()) {
            val windowOpen = StepChangeWindow.isOpen(snapshot(p))
            choice = choice.afterWindow(windowOpen)
            if (second == 62) choice = StepChangeWindow.tap(choice, windowOpen) // 2 s into the 12 s step
            shown += StepChangeWindow.showsDetailed(choice, windowOpen)
            p.advance(1)
        }
        // From the tap to the end of the second window (the change at 72, plus 10 s): collapsed, never re-expanded.
        assertTrue(shown.subList(62, 82).none { it })
        assertTrue(shown.subList(50, 62).all { it })
    }
}
