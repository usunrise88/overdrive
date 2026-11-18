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

    /** An ERG step: the target in [watts] with the intensity percentage next to the label. */
    @Immutable
    data class Target(
        val watts: Int?,
        val intensityPercent: Int,
    ) : OverlayControl
}

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
)
