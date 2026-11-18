package app.overdrive.core.click

/**
 * What changed between two button frames (docs/protocols/click-v2.md §4: the controller sends the whole state, not one
 * event per press, so a press is a bit that went from released to pressed and a release is the way back).
 *
 * All masks use **1 for pressed** (the inverse of the wire's bitmap): [pressed] are the buttons that went down in this
 * frame, [released] the ones that came up, [held] everything that is down afterwards. Several buttons can change in one
 * frame, and [held] is the whole truth: a consumer that missed an earlier change recovers from this one.
 */
public data class ButtonChange(
    /** The buttons that went down in this frame. */
    val pressed: Int,
    /** The buttons that came up in this frame. */
    val released: Int,
    /** Every button that is down once this frame is applied. */
    val held: Int,
    /** When the frame arrived, on the transport's monotonic clock in nanoseconds; for a [synthetic] change, when the link ended. */
    val timestampNanos: Long,
    /**
     * True when the controller did not say this: the link dropped while buttons were held and their releases were lost, so
     * the adapter releases them itself. A consumer treats it as "forget the presses" and fires nothing for them.
     */
    val synthetic: Boolean = false,
)

/**
 * Turns successive button bitmaps into press and release edges (docs/protocols/click-v2.md §4). Pure and exception-free:
 * the same bitmap twice is no change, a button held across frames is reported once, and every button can change in one
 * frame.
 *
 * @param ignoredBits bits that always read as released, whatever the controller says. An escape hatch for the bench: a bit
 * that turns out to be stuck at 0 would otherwise read as a button held for ever (`ClickOptions.ignoredBits`).
 */
public class ButtonEdges(
    private val ignoredBits: Int = 0,
) {
    /** The buttons held now, 1 for pressed. */
    public var held: Int = 0
        private set

    /** Applies one bitmap received at [timestampNanos] and returns what changed, or null when nothing did (a repeat). */
    public fun update(
        bitmap: Int,
        timestampNanos: Long,
    ): ButtonChange? {
        val now = ClickBits.pressedOf(bitmap) and ignoredBits.inv()
        if (now == held) return null
        val change =
            ButtonChange(
                pressed = now and held.inv(),
                released = held and now.inv(),
                held = now,
                timestampNanos = timestampNanos,
            )
        held = now
        return change
    }

    /**
     * The link is gone: whatever was held is released, because no release will ever arrive for it. Returns the synthetic
     * change, or null when nothing was held. The next bitmap after a reconnect is compared with an all-released state.
     */
    public fun releaseAll(timestampNanos: Long): ButtonChange? {
        if (held == 0) return null
        val change =
            ButtonChange(
                pressed = 0,
                released = held,
                held = 0,
                timestampNanos = timestampNanos,
                synthetic = true,
            )
        held = 0
        return change
    }
}
