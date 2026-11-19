package app.overdrive.core.control

import app.overdrive.core.click.ClickBits

/**
 * Which physical buttons are the five roles of the handlebar (spec §4.7: "Physical buttons are assigned once their
 * identifiers are observed on the device. The mapping is a setting."). Each role has a **mask** of the bits of the Click's
 * bitmap (1 for pressed, see [ClickBits]); a role is down while **any** of its bits is down, so a button that the bench shows
 * to set two bits is one role with a two-bit mask. Masks never overlap and are never empty, so a bit is at most one role.
 *
 * Immutable, with value equality: a setting that changed is a mapping that is not equal to the old one.
 *
 * ### The default ([DEFAULT]): an ergonomic guess, to be replaced by *Learn buttons*
 *
 * The right puck is a diamond (Y at the top, Z left, A right, B at the bottom) with Plus under B
 * (docs/protocols/click-v2.md §4, "Physical layout"). The thumb rests on the bottom of the puck, so the two **gear** roles,
 * the ones used all ride, are the two nearest: **Plus is gear up** (the symbol says so) and **B, directly above it, is gear
 * down**. The two **volume** roles are a left-right pair, like a slider, **Z (left) quieter and A (right) louder**. The
 * **play-and-pause** role, a deliberate and rare one, is the farthest, **Y at the top**, which also keeps a stray thumb from
 * pausing the video. Nothing in the notes says which button is which in the spec's list ("Button 2 to 5"), so this is a guess
 * the bench replaces (bench-checklist 5.5, `ButtonLearner`).
 *
 * ### The sixth button, for the live coach ([coachMuteMask])
 *
 * [ClickAction.MUTE_COACH] (spec §12.1, docs/adr/0039) is on **no** button by default: [coachMuteMask] is 0, the default mapping is exactly the
 * one above and writes exactly the text it wrote before. The rider may give the action one more button of the Click (`CoachButtonLearner`, under
 * Devices): a bit no role owns, which fires the action **when it is pressed** (it has no hold). A mapping with one is written with one more
 * `COACH_MUTE=` field, which an older reader of the text would refuse and so read as the default.
 */
public class ButtonMapping private constructor(
    private val masks: Map<HandlebarButton, Int>,
    /** The bits of the button that mutes the live coach, or 0 when it has none (the default). Never shares a bit with a role. */
    public val coachMuteMask: Int = 0,
) {
    /** Whether the live coach's button is down when the buttons in [held] (1 for pressed) are. False when it has none. */
    public fun coachMuteDown(held: Int): Boolean = coachMuteMask and held != 0

    /**
     * This mapping with [mask] as the live coach's button, or without one for 0; null when [mask] shares a bit with a role (the caller would
     * have to move that role first).
     */
    public fun withCoachMute(mask: Int): ButtonMapping? = of(masks, mask)

    /** The mask of bits that make [button] down. Never 0. */
    public fun maskOf(button: HandlebarButton): Int = masks.getValue(button)

    /** The roles that are down when the buttons in [held] (1 for pressed) are, in role order. Bits no role owns are ignored. */
    public fun rolesDown(held: Int): Set<HandlebarButton> =
        HandlebarButton.entries.filterTo(LinkedHashSet()) {
            masks.getValue(it) and
                held !=
                0
        }

    /** Every bit that belongs to some role, or to the live coach's button. */
    public val allBits: Int get() = masks.values.fold(coachMuteMask) { all, mask -> all or mask }

    /** The role that owns [bit], or null. */
    public fun roleOf(bit: Int): HandlebarButton? =
        HandlebarButton.entries.firstOrNull {
            masks.getValue(it) and bit !=
                0
        }

    /**
     * This mapping with [button] given [mask], or null when [mask] is empty or shares a bit with another role (the caller
     * would have to move that role first).
     */
    public fun with(
        button: HandlebarButton,
        mask: Int,
    ): ButtonMapping? = of(masks + (button to mask), coachMuteMask)

    /**
     * The mapping as text for the settings file: `PLUS=00001000;BUTTON_2=00000020;…`, eight hex digits a role, in role order, and
     * `;COACH_MUTE=…` after them only when the live coach has a button. [parse] reads it back.
     */
    public fun serialize(): String =
        HandlebarButton.entries.joinToString(";") { "${it.name}=${hex8(masks.getValue(it))}" } +
            if (coachMuteMask == 0) "" else ";$COACH_KEY=${hex8(coachMuteMask)}"

    override fun equals(other: Any?): Boolean =
        other is ButtonMapping && masks == other.masks && coachMuteMask == other.coachMuteMask

    override fun hashCode(): Int = masks.hashCode() * HASH_PRIME + coachMuteMask

    override fun toString(): String =
        HandlebarButton.entries.joinToString(
            ", ",
            "ButtonMapping(",
            if (coachMuteMask ==
                0
            ) {
                ")"
            } else {
                ", Coach mute=${ClickBits.namesOf(coachMuteMask).joinToString("+")})"
            },
        ) {
            "${it.label}=${ClickBits.namesOf(masks.getValue(it)).joinToString("+")}"
        }

    public companion object {
        /** The ergonomic guess described above: Plus gear up, B gear down, Y play and pause, A volume up, Z volume down. */
        public val DEFAULT: ButtonMapping =
            checkNotNull(
                of(
                    mapOf(
                        HandlebarButton.PLUS to ClickBits.PLUS,
                        HandlebarButton.BUTTON_2 to ClickBits.B,
                        HandlebarButton.BUTTON_3 to ClickBits.Y,
                        HandlebarButton.BUTTON_4 to ClickBits.A,
                        HandlebarButton.BUTTON_5 to ClickBits.Z,
                    ),
                ),
            ) { "The default mapping is invalid" }

        /**
         * A mapping from [masks], which must give every role a non-empty mask that no other role shares; null otherwise. [coachMuteMask] is
         * the live coach's button: 0 for none, otherwise bits that no role has. Never throws.
         */
        public fun of(
            masks: Map<HandlebarButton, Int>,
            coachMuteMask: Int = 0,
        ): ButtonMapping? {
            if (HandlebarButton.entries.any { (masks[it] ?: 0) == 0 }) return null
            var seen = 0
            for (button in HandlebarButton.entries) {
                val mask = masks.getValue(button)
                if (seen and mask != 0) return null
                seen = seen or mask
            }
            if (seen and coachMuteMask != 0) return null
            return ButtonMapping(HandlebarButton.entries.associateWith { masks.getValue(it) }, coachMuteMask)
        }

        /** Reads [serialize]'s text, or returns null for anything that is not a valid mapping. Never throws. */
        public fun parse(text: String): ButtonMapping? {
            val parts = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val masks = HashMap<HandlebarButton, Int>()
            var coach: Int? = null
            for (part in parts) {
                val pair = part.split('=')
                if (pair.size != 2) return null
                val key = pair[0].trim()
                val digits = pair[1].trim()
                // Exactly eight hex digits, so a text cut short is never read as another, valid mapping.
                if (digits.length != HEX_DIGITS || !digits.all { it in HEX_ALPHABET }) return null
                val mask = (digits.toLongOrNull(HEX_RADIX) ?: return null).toInt()
                if (key == COACH_KEY) {
                    if (coach != null || mask == 0) return null
                    coach = mask
                    continue
                }
                val button = HandlebarButton.entries.firstOrNull { it.name == key } ?: return null
                if (button in masks) return null
                masks[button] = mask
            }
            return of(masks, coach ?: 0)
        }

        private fun hex8(mask: Int): String = mask.toUInt().toString(HEX_RADIX).padStart(HEX_DIGITS, '0')

        private const val COACH_KEY = "COACH_MUTE"
        private const val HASH_PRIME = 31

        private const val HEX_RADIX = 16
        private const val HEX_DIGITS = 8
        private const val HEX_ALPHABET = "0123456789abcdefABCDEF"
    }
}
