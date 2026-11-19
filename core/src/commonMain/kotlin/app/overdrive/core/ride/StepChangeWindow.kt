package app.overdrive.core.ride

/**
 * When the overlay expands around a step change, and what a tap does meanwhile (spec §11.3, docs/adr/0034). **Pure functions of the
 * snapshot's step progress and the rider's choice**: no clock, no state of their own, so every rule is a row of a table in the tests.
 *
 * - **The window.** [LEAD_SECONDS] before a step change the overlay switches to the detailed mode (the countdown and the next step are
 *   there), and [TRAIL_SECONDS] after it it goes back to the mode the rider chose. A step shorter than the two together is expanded
 *   from its first second to its last, with no gap between its two windows. Both ends are read from the progress alone:
 *   *before* is "there is a next step and at most [LEAD_SECONDS] of this one are left", *after* is "this is not the first step and fewer
 *   than [TRAIL_SECONDS] of it have gone". So a skip to the next or previous step is a change like any other (the new step has just
 *   begun), the start of the workout is not a change, and the end of the workout is not one either (there is no next step; the ride
 *   finishes and the overlay closes). A pause freezes the progress and so the window with it.
 * - **The rider's choice wins.** The overlay is shown detailed when the rider chose that or the window is open and the rider has not
 *   [dismissed][ModeChoice.dismissed] it. A tap flips what is **shown**: tapping an expanded overlay collapses it for the rest of that
 *   window and leaves the chosen mode alone, so the automatic return afterwards does nothing; tapping a compact overlay chooses
 *   detailed for good. The dismissal lasts until the window has closed (the caller clears it then, see [ModeChoice.afterWindow]), so the
 *   next step change expands the overlay again.
 */
public object StepChangeWindow {
    /** Spec §11.3: 10 s before a step change the overlay expands. */
    public const val LEAD_SECONDS: Int = 10

    /** Spec §11.3: 10 s after it the overlay returns to the rider's mode. */
    public const val TRAIL_SECONDS: Int = 10

    /**
     * Whether the window around a step change is open for [snapshot]: the ride is live (riding or paused) in a workout, the setting is
     * [enabled], and the current step is within [leadSeconds] of its end (with a step after it) or within [trailSeconds] of its start
     * (and not the first).
     */
    public fun isOpen(
        snapshot: RideSnapshot,
        enabled: Boolean = true,
        leadSeconds: Int = LEAD_SECONDS,
        trailSeconds: Int = TRAIL_SECONDS,
    ): Boolean {
        require(leadSeconds >= 0 && trailSeconds >= 0) { "The window's ends must not be negative" }
        if (!enabled) return false
        if (snapshot.state != RideState.RIDING && snapshot.state != RideState.PAUSED) return false
        val progress = snapshot.workout ?: return false
        val before = progress.nextStep != null && progress.stepRemainingSeconds <= leadSeconds
        val after = progress.stepIndex > 0 && progress.stepElapsedSeconds < trailSeconds
        return before || after
    }

    /** Whether the overlay is shown in its detailed mode: the rider chose it, or the window is open and has not been dismissed. */
    public fun showsDetailed(
        choice: ModeChoice,
        windowOpen: Boolean,
    ): Boolean = choice.detailed || (windowOpen && !choice.dismissed)

    /**
     * What a tap does: the shown mode flips. Collapsing an overlay that shows detailed because the window is open (or because the rider
     * chose detailed during it) dismisses the window; choosing detailed is a choice for good.
     */
    public fun tap(
        choice: ModeChoice,
        windowOpen: Boolean,
    ): ModeChoice =
        if (showsDetailed(choice, windowOpen)) {
            ModeChoice(detailed = false, dismissed = choice.dismissed || windowOpen)
        } else {
            ModeChoice(detailed = true, dismissed = choice.dismissed)
        }
}

/**
 * The rider's side of the overlay's mode: [detailed] is the mode they chose (what a tap made it, persisted), [dismissed] says they
 * collapsed the overlay while a step-change window was open, so that window must not expand it again.
 */
public data class ModeChoice(
    val detailed: Boolean,
    val dismissed: Boolean = false,
) {
    /** The same choice once the window has closed: a dismissal only lasts as long as the window it was made in. */
    public fun afterWindow(windowOpen: Boolean): ModeChoice =
        if (windowOpen ||
            !dismissed
        ) {
            this
        } else {
            copy(dismissed = false)
        }
}
