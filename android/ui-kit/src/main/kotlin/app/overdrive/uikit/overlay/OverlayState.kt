package app.overdrive.uikit.overlay

import androidx.compose.runtime.Immutable
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.tokens.OverdriveDimens

/** Compact is one row of five values; detailed adds speed, distance and the workout section. */
enum class OverlayMode {
    Compact,
    Detailed,
}

/**
 * The overlay's settings (design/system.md §5.1): the backing opacity (0.85 to 1.0, default 0.95) and the mode.
 * Immutable and tiny, so a change recomposes only what reads it.
 */
@Immutable
data class OverlaySettings(
    val opacity: Float = OverdriveDimens.overlayOpacityDefault,
    val mode: OverlayMode = OverlayMode.Compact,
)

/** The fifth cell of the main row, which is the Click's feedback channel (design/system.md §5.2). */
@Immutable
sealed interface OverlayControl {
    /** A free-ride step: the virtual [gear] (1-based), or null when it is not known. */
    @Immutable
    data class Gear(
        val gear: Int?,
    ) : OverlayControl

    /**
     * An ERG step: the target in [watts] with the intensity percentage next to the label. A heart-rate step has no intensity to scale
     * (the controller chooses the watts), so there [intensityPercent] is null and the label says only "Target".
     */
    @Immutable
    data class Target(
        val watts: Int?,
        val intensityPercent: Int?,
    ) : OverlayControl

    /**
     * The ERG stall guard has hold (spec §11.1): cadence fell, so the trainer is held at [watts], below the step's target, until the
     * rider pedals again. The label says "Cadence guard" in primary text, so it is read as a word and not as a colour.
     */
    @Immutable
    data class Guard(
        val watts: Int?,
    ) : OverlayControl
}

/** Where the heart rate is against the band of a heart-rate step (spec §11.2). */
enum class HoldPlace {
    Below,
    In,
    Above,
}

/** What a heart-rate step is doing, in the overlay's words. */
enum class HoldPhase {
    /** The start ramp: the heart rate is not judged yet. */
    EasingIn,

    /** The controller is in charge. */
    Holding,

    /** No heart rate for 15 s: the last power is held. */
    StrapLost,

    /** No heart rate for 60 s: the power dropped to the minimum. */
    StrapDropped,
}

/**
 * The line under the main row while a heart-rate step plays (spec §11.2, design/system.md §5.6): the target heart rate and band, where the
 * heart rate is against them, and the strap states. The controller's power is the fifth cell's target. The target moves by 2 bpm with the
 * gear buttons, which shows as [targetBpm] changing at once (and [offsetBpm] saying by how much it differs from the step's own).
 */
@Immutable
data class OverlayHold(
    val targetBpm: Int,
    /** Half-width of the band, in bpm. */
    val band: Int,
    /** What the gear buttons moved the target by, in bpm; 0 when they have not. */
    val offsetBpm: Int,
    /** The live heart rate, for the gauge's marker; null without a signal. */
    val heartRate: Int?,
    /** Null while the heart rate is not judged (easing in) or missing. */
    val place: HoldPlace?,
    val phase: HoldPhase,
    /** The controller's current power in watts, for "holding 140 W". */
    val watts: Int?,
    /** The step's lowest power, for "power at the minimum, 80 W". */
    val minimumWatts: Int?,
)

/** The workout section of the detailed overlay (design/system.md §5.3). */
@Immutable
data class OverlayInterval(
    /** The step's name, such as "Sweet spot". */
    val name: String,
    /** "2 of 3" for a repeat, else null. */
    val repeatText: String?,
    /** Seconds left in the step. */
    val remainingSeconds: Int,
    /** "Recover · 5:00 · 125 W", "Free ride · 5:00", or "Finish" for the last step. */
    val next: String,
    /** The workout's steps, one instance per workout. */
    val profile: ProfileData,
    /** Seconds into the workout, for the now marker. */
    val nowSeconds: Int,
    /**
     * The one line against the previous attempt of this session (spec §11.7), such as "Heart rate 3 below last time", or null when
     * there is nothing to say yet (no earlier attempt, no connection, not enough of this step ridden). Detailed mode only.
     */
    val previousLine: String? = null,
)

/**
 * Everything the overlay shows, as plain immutable data derived from a ride snapshot. No window code and no
 * formatting happen here; `OverlayContent` writes the numbers. A null number is a missing signal and is drawn as "—".
 */
@Immutable
data class OverlayUiState(
    val power: Int?,
    /** Zone 1 to 7 of [power], null without power. */
    val zone: Int?,
    val heartRate: Int?,
    val cadence: Int?,
    val elapsedSeconds: Int,
    /** Paused by the rider, by auto-pause or by a lost trainer: values dim and "Time" reads "Paused". */
    val paused: Boolean,
    val control: OverlayControl,
    val speedKmh: Double,
    val distanceMeters: Double,
    /** Null in a free ride without a workout. */
    val interval: OverlayInterval?,
    /** Set while a heart-rate step plays (spec §11.2): the line under the main row, in both modes. */
    val hold: OverlayHold? = null,
)

/** The state of the mute control under the overlay (spec §12.1, docs/adr/0039), in words: "Coach on" and "Coach muted". */
enum class OverlayCoachControl(
    val words: String,
) {
    /** A live session is on and the rider has not muted it. */
    On("Coach on"),

    /** The rider muted the coach for the rest of the ride; it cannot be undone. */
    Muted("Coach muted"),
}

/**
 * What the overlay shows of the live coach, **under the panel and apart from it** (spec §12.1, docs/adr/0039, design/system.md §5.7): the mute control while a
 * session is on, the one-time notice that the coach is unavailable, and the plate with the text of the cue being spoken. It is its own value, and not part
 * of [OverlayUiState], because it changes on its own clock (a cue, a mute) and must not wait for the once-a-second spacing of the ride's numbers.
 */
@Immutable
data class OverlayCoach(
    /** The mute control: present exactly while a session is on. Null otherwise. */
    val control: OverlayCoachControl? = null,
    /** "Live coach unavailable", once at the start and for a few seconds only; null otherwise. Never together with [control]. */
    val notice: String? = null,
    /** The cue being spoken and, for a few seconds after, its text; null otherwise. */
    val plate: String? = null,
) {
    /** Nothing of the coach is shown: the overlay is exactly the panel. */
    val isNone: Boolean get() = control == null && notice == null && plate == null

    companion object {
        val None = OverlayCoach()

        /** The words of the notice. */
        const val UNAVAILABLE = "Live coach unavailable"
    }
}
