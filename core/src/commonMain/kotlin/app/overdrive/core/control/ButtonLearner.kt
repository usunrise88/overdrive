package app.overdrive.core.control

/** Why a press was not accepted while learning the buttons. */
public enum class LearnRejection {
    /** More than one button was down: a press has to be one button at a time. */
    COMBO,

    /** The button is already another role's: [LearnState.Rejected.clashesWith] says which. */
    DUPLICATE,
}

/** Where *Learn buttons* is (spec §4.7: the mapping is "assigned once their identifiers are observed on the device"). */
public sealed interface LearnState {
    /** Waiting for one press of the button that is [button]; [step] counts from 1 of [total]. [learned] is what is settled so far. */
    public data class Asking(
        val button: HandlebarButton,
        val step: Int,
        val total: Int,
        val learned: Map<HandlebarButton, Int>,
    ) : LearnState

    /** The last press for [button] was refused for [reason]; press the right one again. */
    public data class Rejected(
        val button: HandlebarButton,
        val reason: LearnRejection,
        /** For [LearnRejection.DUPLICATE], the role that already owns the button. */
        val clashesWith: HandlebarButton?,
    ) : LearnState

    /** Every role has its button: [mapping] is ready to be saved. */
    public data class Complete(
        val mapping: ButtonMapping,
    ) : LearnState
}

/**
 * The pure core of a *Learn buttons* screen (spec §4.7): feed it the Click's buttons as they change and it builds a
 * [ButtonMapping]. It asks for one role after another, in [roles]' order; each answer is **one press and its release** of
 * one button, and it is decided on the release so that a second button joining the press is seen.
 *
 * - A press that is **more than one button** (two buttons together, or a second one joining before the first is released)
 *   is rejected as a combo, and the same role is asked again. With [allowMultiBitButtons] a press that sets several bits **in
 *   its first frame** is accepted as one button with all of them (the notes list a second bit for Plus, `0x2000`, and the
 *   bench may show that a button sets two); a bit that joins later is still a combo.
 * - A button that already belongs to another role (one learned earlier, or one of the roles not being re-learned) is
 *   rejected as a duplicate, naming that role.
 * - [ignoredBits] are never looked at: a bit that reads as pressed all the time would otherwise be a button held for ever
 *   and the learning could not go on. It is the same escape hatch as `ClickOptions.ignoredBits`, which the device already
 *   applies; pass it on if the bench finds a stuck bit.
 *
 * Pure and exception-free; the screen shows [state] and calls [feed] with [app.overdrive.core.click.ButtonChange.held] each
 * time the Click reports a change (a synthetic change, a link that dropped, is a [feed] of 0 and abandons the press).
 *
 * @param roles the roles to learn, in the order to ask. All five by default.
 * @param ignoredBits bits to treat as never pressed.
 * @param base the mapping the other roles keep when [roles] is a subset (re-learning one button).
 */
public class ButtonLearner(
    private val roles: List<HandlebarButton> = HandlebarButton.entries,
    private val allowMultiBitButtons: Boolean = false,
    private val ignoredBits: Int = 0,
    private val base: ButtonMapping = ButtonMapping.DEFAULT,
) {
    private enum class Phase { WAITING, HOLDING }

    private val learned = LinkedHashMap<HandlebarButton, Int>()
    private var index = 0
    private var phase = Phase.WAITING
    private var candidate = 0
    private var combo = false

    init {
        require(roles.isNotEmpty() && roles.toSet().size == roles.size) { "Learn each role once, got $roles" }
    }

    /** What to show now. */
    public var state: LearnState = asking()
        private set

    /** Starts over from the first role. */
    public fun reset() {
        learned.clear()
        index = 0
        phase = Phase.WAITING
        candidate = 0
        combo = false
        state = asking()
    }

    /** The buttons that are down now (1 for pressed, as [app.overdrive.core.click.ButtonChange.held]); returns the new [state]. */
    public fun feed(held: Int): LearnState {
        if (state is LearnState.Complete) return state
        val down = held and ignoredBits.inv()
        when (phase) {
            Phase.WAITING -> {
                if (down != 0) {
                    candidate = down
                    combo = down.countOneBits() > 1 && !allowMultiBitButtons
                    phase = Phase.HOLDING
                    // A new press starts a new try: the last refusal is no longer news.
                    if (state is LearnState.Rejected) state = asking()
                }
            }

            Phase.HOLDING -> {
                if (down == 0) {
                    commit()
                } else if (down and candidate.inv() != 0) {
                    combo = true
                    candidate = candidate or down
                } else {
                    // Some of the bits came up before the rest: still one press, decided when all are up.
                    Unit
                }
            }
        }
        return state
    }

    private fun commit() {
        val role = roles[index]
        phase = Phase.WAITING
        val mask = candidate
        candidate = 0
        if (combo) {
            combo = false
            state = LearnState.Rejected(role, LearnRejection.COMBO, null)
            return
        }
        val owner = ownerOf(mask, role)
        if (owner != null) {
            state = LearnState.Rejected(role, LearnRejection.DUPLICATE, owner)
            return
        }
        learned[role] = mask
        index++
        state = if (index < roles.size) asking() else finish()
    }

    /** The role other than [role] that has a bit of [mask]: a role learned already, or one that keeps its [base] mask. */
    private fun ownerOf(
        mask: Int,
        role: HandlebarButton,
    ): HandlebarButton? =
        HandlebarButton.entries.firstOrNull { other ->
            other != role && currentMask(other) and mask != 0
        }

    private fun currentMask(role: HandlebarButton): Int =
        when {
            role in learned -> learned.getValue(role)
            role in roles -> 0
            else -> base.maskOf(role)
        }

    private fun asking(): LearnState.Asking =
        LearnState.Asking(roles[index.coerceAtMost(roles.lastIndex)], index + 1, roles.size, learned.toMap())

    private fun finish(): LearnState {
        val all = HandlebarButton.entries.associateWith { currentMask(it) }
        // The live coach's button, if the rider gave it one, stays unless a role has just been given the same button.
        val mapping = ButtonMapping.of(all, base.coachMuteMask) ?: ButtonMapping.of(all)
        // Cannot fail: every learned mask was checked against every other, and the kept ones come from a valid mapping.
        return if (mapping != null) LearnState.Complete(mapping) else asking()
    }
}
