package app.overdrive.core.control

/** Why a press was not taken as the live coach's button. */
public enum class CoachLearnRejection {
    /** More than one button was down: a press has to be one button at a time. */
    COMBO,

    /** The button is already one of the five roles' ([CoachLearnState.Rejected.takenBy] says which). */
    TAKEN,
}

/** Where the learning of the live coach's button is. */
public sealed interface CoachLearnState {
    /** Waiting for one press of the button that should mute the coach. */
    public data object Asking : CoachLearnState

    /** The last press was refused for [reason]; press another. */
    public data class Rejected(
        val reason: CoachLearnRejection,
        /** For [CoachLearnRejection.TAKEN], the role that has the button. */
        val takenBy: HandlebarButton?,
    ) : CoachLearnState

    /** A button was taken: [mapping] is the one in force with it added, ready to be saved. */
    public data class Complete(
        val mapping: ButtonMapping,
    ) : CoachLearnState
}

/**
 * The pure core of learning the **sixth button** (spec §12.1, docs/adr/0039): the one the rider may give to [ClickAction.MUTE_COACH], beside the five
 * roles [ButtonLearner] asks for. Feed it the Click's buttons as they change and it settles on **one press and its release** of one button, as
 * [ButtonLearner] does for a role: decided on the release, so a second button joining the press is seen.
 *
 * - **More than one button** (two together, or a second joining before the first is released) is [CoachLearnRejection.COMBO].
 * - **A button one of the five roles already has** is [CoachLearnRejection.TAKEN]: the roles keep what they have, and the rider may press another.
 * - [ignoredBits] are never looked at (a bit that reads as pressed all the time).
 *
 * Pure and exception-free. Clearing the button is `base.withCoachMute(0)`.
 *
 * @param base the mapping in force: the five roles keep their buttons, and a button the coach already had is replaced.
 */
public class CoachButtonLearner(
    private val base: ButtonMapping,
    private val ignoredBits: Int = 0,
) {
    private enum class Phase { WAITING, HOLDING }

    private var phase = Phase.WAITING
    private var candidate = 0
    private var combo = false

    /** What to show now. */
    public var state: CoachLearnState = CoachLearnState.Asking
        private set

    /** The buttons that are down now (1 for pressed, as `ButtonChange.held`); returns the new [state]. */
    public fun feed(held: Int): CoachLearnState {
        if (state is CoachLearnState.Complete) return state
        val down = held and ignoredBits.inv()
        when (phase) {
            Phase.WAITING -> {
                if (down != 0) {
                    candidate = down
                    combo = down.countOneBits() > 1
                    phase = Phase.HOLDING
                    // A new press starts a new try: the last refusal is no longer news.
                    if (state is CoachLearnState.Rejected) state = CoachLearnState.Asking
                }
            }

            Phase.HOLDING -> {
                if (down == 0) {
                    commit()
                } else if (down and candidate.inv() != 0) {
                    combo = true
                    candidate = candidate or down
                }
            }
        }
        return state
    }

    private fun commit() {
        phase = Phase.WAITING
        val mask = candidate
        candidate = 0
        if (combo) {
            combo = false
            state = CoachLearnState.Rejected(CoachLearnRejection.COMBO, null)
            return
        }
        val owner = HandlebarButton.entries.firstOrNull { base.maskOf(it) and mask != 0 }
        if (owner != null) {
            state = CoachLearnState.Rejected(CoachLearnRejection.TAKEN, owner)
            return
        }
        val mapping = base.withCoachMute(mask)
        state =
            if (mapping !=
                null
            ) {
                CoachLearnState.Complete(mapping)
            } else {
                CoachLearnState.Rejected(CoachLearnRejection.COMBO, null)
            }
    }
}
