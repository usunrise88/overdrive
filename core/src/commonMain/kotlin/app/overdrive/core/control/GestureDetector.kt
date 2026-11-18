package app.overdrive.core.control

/** The timing of the gestures (spec §4.7: "Hold threshold 500 ms. Volume repeats every 150 ms."), as named values. */
public class GestureTiming(
    /** A press held at least this long is a hold, not a press. */
    public val holdThresholdMillis: Long = HOLD_THRESHOLD_MILLIS,
    /** While a volume button stays down, one step this often once the hold threshold has passed. */
    public val repeatIntervalMillis: Long = VOLUME_REPEAT_MILLIS,
    /**
     * Contact bounce: a release followed by a press of the same button within this time is one continuous press, and the
     * release is swallowed. **0, off, by default**: the controller reports debounced states and nothing says it bounces
     * (docs/protocols/click-v2.md does not mention it); turning it on delays every release by this long. Bench 5.7.
     */
    public val bounceMillis: Long = 0L,
) {
    init {
        require(holdThresholdMillis > 0) { "The hold threshold must be positive, got $holdThresholdMillis" }
        require(repeatIntervalMillis > 0) { "The repeat interval must be positive, got $repeatIntervalMillis" }
        require(bounceMillis >= 0) { "The bounce time must not be negative, got $bounceMillis" }
    }

    public companion object {
        /** Spec §4.7. */
        public const val HOLD_THRESHOLD_MILLIS: Long = 500L

        /** Spec §4.7. */
        public const val VOLUME_REPEAT_MILLIS: Long = 150L
    }
}

/**
 * One action the detector decided on. [atMillis] is the moment it was **due**: the release for a short press, the threshold
 * for a hold, the scheduled step for a repeat. A caller that was late reads a [atMillis] earlier than its own clock.
 */
public data class GestureEvent(
    val action: ClickAction,
    val button: HandlebarButton,
    val atMillis: Long,
)

/**
 * Turns presses and releases of the five buttons into [ClickAction]s (spec §4.7). **Pure**: no clock, no coroutine; every
 * call says what time it is and gets back what fired, so a test runs hours of riding in no time at all and a stalled clock or
 * a stepped one is just another input. Times are milliseconds on any monotonic clock; a time before the last one seen is
 * treated as the last one (the clock never runs backwards here, whatever it does).
 *
 * ### When each action fires
 *
 * There are no double clicks, so nothing waits to see whether a second click follows. There are two kinds of button
 * ([HandlebarButton.repeats]):
 *
 * - **Plus, Button 2, Button 3 (tap or hold).** The press cannot be told from the start of a hold, so it fires **on
 *   release, if the button was down for less than the hold threshold**. A button still down **at the threshold fires its
 *   hold once, then nothing**: the later release does nothing. Elapsed time of exactly the threshold is a hold.
 * - **Button 4, Button 5 (volume).** The first step fires **on press**, with no delay. If the button stays down, a repeated
 *   step fires **at the threshold and then every repeat interval**; the release does nothing.
 *
 * ### Everything else the rider's hands can do
 *
 * - **Buttons are independent.** Two taps that overlap, as a thumb rolling from one button to the next does, both fire at
 *   their own release.
 * - **A hold needs the button alone.** If another button is down when a hold (or a repeated step) comes due, it is skipped:
 *   a palm over the puck must not skip an interval and pause the ride at once. A skipped *hold* is gone for that press (the
 *   release does nothing too); a skipped *repeated step* is just missed, and the next one is tried an interval later.
 * - **No catch-up.** If the caller is late (the tablet slept, a coroutine stalled) a repeating button fires one step and
 *   resumes its cadence from the present, never a burst of the steps it missed. A hold fires once however late.
 * - **Duplicates are ignored**: a press of a button that is down, a release of one that is up.
 * - **A lost release** (the link dropped): [cancelAll] forgets every press without firing anything. A press whose release was
 *   lost is not a press the rider finished.
 * - **Bounce** ([GestureTiming.bounceMillis], off by default): see there.
 */
public class GestureDetector(
    private val timing: GestureTiming = GestureTiming(),
) {
    private class Slot(
        val button: HandlebarButton,
    ) {
        var down = false
        var since = 0L

        /** The hold fired or was skipped: a release fires nothing. */
        var holdHandled = false

        /** When the next repeated step is due, or [NONE]. */
        var nextRepeat = NONE

        /** When the release was seen and awaits the bounce time, or [NONE]. */
        var releasedAt = NONE

        val isHeld: Boolean get() = down && releasedAt == NONE

        fun reset() {
            down = false
            holdHandled = false
            nextRepeat = NONE
            releasedAt = NONE
        }
    }

    private enum class Due { RELEASE, HOLD, REPEAT }

    private val slots = HandlebarButton.entries.map { Slot(it) }
    private var lastNow = Long.MIN_VALUE

    /** Whether [button] is down. */
    public fun isDown(button: HandlebarButton): Boolean = slots[button.ordinal].isHeld

    /** Whether any button is down. */
    public val anyDown: Boolean get() = slots.any { it.isHeld }

    /**
     * When something is due without any further input: a hold, a repeated step, or a release waiting out the bounce time.
     * Null when nothing is pending. A caller sleeps until this and then calls [advance].
     */
    public val nextDeadlineMillis: Long?
        get() = slots.mapNotNull { dueOf(it)?.second }.minOrNull()

    /** [button] went down at [nowMillis]. Returns what fired, in order. */
    public fun press(
        button: HandlebarButton,
        nowMillis: Long,
    ): List<GestureEvent> {
        val now = clamp(nowMillis)
        val out = ArrayList<GestureEvent>(MAX_EVENTS)
        run(now, out)
        val slot = slots[button.ordinal]
        when {
            slot.down && slot.releasedAt != NONE -> {
                // Contact bounce: the release was not one. The same press goes on.
                slot.releasedAt = NONE
            }

            slot.down -> {
                Unit
            }

            else -> {
                slot.down = true
                slot.since = now
                slot.holdHandled = false
                slot.nextRepeat = NONE
                slot.releasedAt = NONE
                if (button.repeats) out += GestureEvent(button.press, button, now)
            }
        }
        return out
    }

    /** [button] came up at [nowMillis]. Returns what fired, in order. */
    public fun release(
        button: HandlebarButton,
        nowMillis: Long,
    ): List<GestureEvent> {
        val now = clamp(nowMillis)
        val out = ArrayList<GestureEvent>(MAX_EVENTS)
        run(now, out)
        val slot = slots[button.ordinal]
        if (slot.isHeld) {
            if (timing.bounceMillis == 0L) {
                finishRelease(slot, now, out)
            } else {
                slot.releasedAt = now
            }
        }
        return out
    }

    /** Lets time pass to [nowMillis]: fires every hold and repeated step that came due, and confirms releases past the bounce time. */
    public fun advance(nowMillis: Long): List<GestureEvent> {
        val out = ArrayList<GestureEvent>(MAX_EVENTS)
        run(clamp(nowMillis), out)
        return out
    }

    /** Forgets every press without firing anything: the link dropped and the releases will never come. */
    public fun cancelAll() {
        slots.forEach { it.reset() }
    }

    private fun clamp(nowMillis: Long): Long {
        if (nowMillis > lastNow) lastNow = nowMillis
        return lastNow
    }

    /** What is due next for [slot] and when, or null. */
    private fun dueOf(slot: Slot): Pair<Due, Long>? =
        when {
            !slot.down -> null
            slot.releasedAt != NONE -> Due.RELEASE to (slot.releasedAt + timing.bounceMillis)
            !slot.holdHandled -> Due.HOLD to (slot.since + timing.holdThresholdMillis)
            slot.button.repeats && slot.nextRepeat != NONE -> Due.REPEAT to slot.nextRepeat
            else -> null
        }

    /** Processes everything due at or before [now], earliest first (a release before a hold at the same time). */
    private fun run(
        now: Long,
        out: MutableList<GestureEvent>,
    ) {
        while (true) {
            var best: Slot? = null
            var bestDue: Due? = null
            var bestAt = Long.MAX_VALUE
            for (slot in slots) {
                val (due, at) = dueOf(slot) ?: continue
                if (at < bestAt || (at == bestAt && due == Due.RELEASE && bestDue != Due.RELEASE)) {
                    best = slot
                    bestDue = due
                    bestAt = at
                }
            }
            if (best == null || bestDue == null || bestAt > now) return
            when (bestDue) {
                Due.RELEASE -> finishRelease(best, best.releasedAt, out)
                Due.HOLD -> fireHold(best, bestAt, now, out)
                Due.REPEAT -> fireRepeat(best, bestAt, now, out)
            }
        }
    }

    private fun finishRelease(
        slot: Slot,
        releasedAt: Long,
        out: MutableList<GestureEvent>,
    ) {
        // A short press of a tap-or-hold button fires now; a repeating button fired on press; a hold fires nothing more.
        if (!slot.holdHandled && !slot.button.repeats && releasedAt - slot.since < timing.holdThresholdMillis) {
            out += GestureEvent(slot.button.press, slot.button, releasedAt)
        }
        slot.reset()
    }

    private fun fireHold(
        slot: Slot,
        at: Long,
        now: Long,
        out: MutableList<GestureEvent>,
    ) {
        slot.holdHandled = true
        if (slot.button.repeats) {
            fireRepeat(slot, at, now, out)
        } else if (!otherDown(slot)) {
            out += GestureEvent(slot.button.hold, slot.button, at)
        }
    }

    private fun fireRepeat(
        slot: Slot,
        at: Long,
        now: Long,
        out: MutableList<GestureEvent>,
    ) {
        if (!otherDown(slot)) out += GestureEvent(slot.button.hold, slot.button, at)
        val next = at + timing.repeatIntervalMillis
        // No catch-up: a caller that was late resumes the cadence from the present.
        slot.nextRepeat = if (next > now) next else now + timing.repeatIntervalMillis
    }

    private fun otherDown(slot: Slot): Boolean = slots.any { it !== slot && it.isHeld }

    private companion object {
        const val NONE = -1L
        const val MAX_EVENTS = 4
    }
}
