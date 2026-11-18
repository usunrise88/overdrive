package app.overdrive.core.control

/**
 * The ten things the handlebar can do (spec §4.7). One per gesture of the table: each of the five buttons has a press and a
 * hold. The two volume buttons have a *repeat* action for the steps after the hold threshold, so the first step (on press) and
 * the held steps can be told apart in a log and by a haptic, while the router sends both to the same volume key.
 */
public enum class ClickAction(
    /** Plain words for the notice or the overlay's "what the rider just did" line. */
    public val label: String,
) {
    /** One gear harder, or in an ERG step the intensity up by 5 points. */
    GEAR_UP("Gear up"),

    /** One gear easier, or in an ERG step the intensity down by 5 points. */
    GEAR_DOWN("Gear down"),

    /** Jump to the next workout step. */
    NEXT_INTERVAL("Next interval"),

    /** Restart the current step, or jump to the previous one. */
    PREVIOUS_INTERVAL("Previous interval"),

    /** Play or pause whatever app is playing: a media key. */
    PLAY_PAUSE_MEDIA("Play or pause"),

    /** Pause the ride while riding, resume it while paused. */
    TOGGLE_RIDE_PAUSE("Pause or resume ride"),

    /** One step of system volume up. */
    VOLUME_UP("Volume up"),

    /** One step of system volume up while the button is held. */
    VOLUME_UP_REPEAT("Volume up"),

    /** One step of system volume down. */
    VOLUME_DOWN("Volume down"),

    /** One step of system volume down while the button is held. */
    VOLUME_DOWN_REPEAT("Volume down"),
}

/**
 * The five buttons of the handlebar controller as the spec names them (spec §4.7), and what each does: **the gesture table
 * in one place**. Which physical bit is which button is a setting ([ButtonMapping]); this is only the roles.
 *
 * A button is either a *tap-or-hold* button (the first three: a short press fires [press] when released, a hold fires [hold]
 * once at the threshold) or a *repeating* button (the volume pair: [press] fires at once and [hold] repeats from the
 * threshold on), see [GestureDetector].
 */
public enum class HandlebarButton(
    /** The spec's name for it. */
    public val label: String,
    /** What a short press does. */
    public val press: ClickAction,
    /** What a hold does; for a repeating button, what each repeated step does. */
    public val hold: ClickAction,
    /** Whether [hold] repeats while the button stays down (the volume buttons). */
    public val repeats: Boolean,
) {
    PLUS("Plus", ClickAction.GEAR_UP, ClickAction.NEXT_INTERVAL, repeats = false),
    BUTTON_2("Button 2", ClickAction.GEAR_DOWN, ClickAction.PREVIOUS_INTERVAL, repeats = false),
    BUTTON_3("Button 3", ClickAction.PLAY_PAUSE_MEDIA, ClickAction.TOGGLE_RIDE_PAUSE, repeats = false),
    BUTTON_4("Button 4", ClickAction.VOLUME_UP, ClickAction.VOLUME_UP_REPEAT, repeats = true),
    BUTTON_5("Button 5", ClickAction.VOLUME_DOWN, ClickAction.VOLUME_DOWN_REPEAT, repeats = true),
}
