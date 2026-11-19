package app.overdrive.core.ride

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The settings of the heart-rate hold controller (spec §11.2, docs/adr/0033). **They are first guesses, found on a
 * first-order model of the heart rate, not on a rider: the owner tunes them on the bike** (the ADR lists what to look for).
 * The controller is deliberately slow: heart rate lags effort by tens of seconds, and a controller that chases it would
 * oscillate.
 */
public data class HeartRateHoldSettings(
    /** Length of the fixed easy ramp at the start of a heart-rate step, in seconds (spec §11.2: "the first minutes"). */
    val startRampSeconds: Int = DEFAULT_START_RAMP_SECONDS,
    /**
     * Where the start ramp ends, as a fraction of the step's power range: `power_min + fraction * (power_max - power_min)`.
     * The controller takes over from there. A low value is the safe side: the heart rate lags, so a ramp that ends too high
     * overshoots for minutes, while one that ends too low only takes a little longer to climb.
     */
    val startRampFraction: Double = DEFAULT_START_RAMP_FRACTION,
    /** Seconds between two decisions of the controller (spec §11.2: 10). */
    val decisionIntervalSeconds: Int = DEFAULT_DECISION_INTERVAL_SECONDS,
    /**
     * Watts added to the power per bpm of error at one decision (the error is the target minus the predicted heart rate).
     * With the defaults a rider 10 bpm under target gets 5 W more every 10 s, which is the step limit.
     */
    val gainWattsPerBpm: Double = DEFAULT_GAIN_WATTS_PER_BPM,
    /**
     * Seconds of look-ahead: the error is taken against the heart rate the current trend is heading for, `smoothed + lead *
     * slope`, which is what makes a lagging heart rate controllable without oscillation. 0 turns the prediction off.
     */
    val leadSeconds: Int = DEFAULT_LEAD_SECONDS,
    /** Time constant in seconds of the filter that smooths the heart rate before anything is decided on it. */
    val smoothingSeconds: Int = DEFAULT_SMOOTHING_SECONDS,
    /** The slope is taken over this many seconds of smoothed heart rate; no decision is made until that much is known. */
    val trendWindowSeconds: Int = DEFAULT_TREND_WINDOW_SECONDS,
    /** An error smaller than this many bpm changes nothing (the heart rate reads in whole bpm, and noise is not chased). */
    val deadbandBpm: Double = DEFAULT_DEADBAND_BPM,
    /** The most the power rises at one decision, in watts. */
    val maxStepUpWatts: Int = DEFAULT_MAX_STEP_UP_WATTS,
    /** The most the power falls at one decision, in watts: larger, because a heart rate that is too high should not last. */
    val maxStepDownWatts: Int = DEFAULT_MAX_STEP_DOWN_WATTS,
    /** Without a heart rate for this many seconds the signal counts as lost: the last power is held (spec §11.2: 15). */
    val signalHoldSeconds: Int = DEFAULT_SIGNAL_HOLD_SECONDS,
    /** Without a heart rate for this many seconds the power drops to `power_min` and the snapshot says so (spec §11.2: 60). */
    val signalDropSeconds: Int = DEFAULT_SIGNAL_DROP_SECONDS,
    /** What one press of a gear button moves the target heart rate, in bpm (spec §11.2: 2). */
    val targetStepBpm: Int = DEFAULT_TARGET_STEP_BPM,
    /** How far the buttons can move the target from the step's, either way, in bpm. */
    val maxTargetOffsetBpm: Int = DEFAULT_MAX_TARGET_OFFSET_BPM,
) {
    init {
        require(startRampSeconds >= 0) { "The start ramp must not be negative, got $startRampSeconds" }
        require(startRampFraction in 0.0..1.0) { "The start ramp ends at 0..1 of the range, got $startRampFraction" }
        require(decisionIntervalSeconds >= 1) { "A decision every at least 1 second, got $decisionIntervalSeconds" }
        require(
            gainWattsPerBpm.isFinite() && gainWattsPerBpm >= 0.0,
        ) { "The gain must not be negative, got $gainWattsPerBpm" }
        require(leadSeconds >= 0) { "The look-ahead must not be negative, got $leadSeconds" }
        require(smoothingSeconds >= 1) { "The smoothing needs at least 1 second, got $smoothingSeconds" }
        require(trendWindowSeconds >= 1) { "The trend window needs at least 1 second, got $trendWindowSeconds" }
        require(deadbandBpm.isFinite() && deadbandBpm >= 0.0) { "The dead band must not be negative, got $deadbandBpm" }
        require(maxStepUpWatts >= 0 && maxStepDownWatts >= 0) { "The step limits must not be negative" }
        require(signalHoldSeconds >= 1) { "The strap hold needs at least 1 second, got $signalHoldSeconds" }
        require(signalDropSeconds >= signalHoldSeconds) {
            "The power drops after the signal is held: $signalDropSeconds is below $signalHoldSeconds"
        }
        require(targetStepBpm >= 0 && maxTargetOffsetBpm >= 0) { "The target steps must not be negative" }
    }

    public companion object {
        /** Spec §11.2: "a fixed easy ramp for the first minutes". */
        public const val DEFAULT_START_RAMP_SECONDS: Int = 180
        public const val DEFAULT_START_RAMP_FRACTION: Double = 0.3

        /** Spec §11.2. */
        public const val DEFAULT_DECISION_INTERVAL_SECONDS: Int = 10
        public const val DEFAULT_GAIN_WATTS_PER_BPM: Double = 0.5
        public const val DEFAULT_LEAD_SECONDS: Int = 30
        public const val DEFAULT_SMOOTHING_SECONDS: Int = 15
        public const val DEFAULT_TREND_WINDOW_SECONDS: Int = 30
        public const val DEFAULT_DEADBAND_BPM: Double = 1.0
        public const val DEFAULT_MAX_STEP_UP_WATTS: Int = 5
        public const val DEFAULT_MAX_STEP_DOWN_WATTS: Int = 10

        /** Spec §11.2. */
        public const val DEFAULT_SIGNAL_HOLD_SECONDS: Int = 15

        /** Spec §11.2. */
        public const val DEFAULT_SIGNAL_DROP_SECONDS: Int = 60

        /** Spec §11.2: the two gear buttons shift the target by 2 bpm. */
        public const val DEFAULT_TARGET_STEP_BPM: Int = 2
        public const val DEFAULT_MAX_TARGET_OFFSET_BPM: Int = 20
    }
}

/**
 * The heart-rate hold controller (spec §11.2): chooses the ERG target power of a heart-rate step so that the heart rate
 * stays at the target. **Pure and deterministic**: no clock, no coroutines; one call to [tick] is one second of riding.
 *
 * - **Start.** For [HeartRateHoldSettings.startRampSeconds] the power is a fixed ramp from `power_min` up to a point in
 *   the power range ([HeartRateHoldSettings.startRampFraction]); the heart rate is not judged and [startRamp] is true.
 *   Then the controller takes over from the power reached.
 * - **Decisions.** Every [HeartRateHoldSettings.decisionIntervalSeconds] the controller looks at the heart rate **smoothed**
 *   by a first-order filter and at its **trend** over a window, predicts where the heart rate is heading
 *   (`smoothed + lead * slope`, so it acts on the lag and not on the past), and moves the power by
 *   `gain * (target - predicted)` watts, limited by the step limits and the power limits, nothing inside the dead band. It
 *   makes no decision until the window of smoothed values is full.
 * - **Power never leaves `[power_min, power_max]`**, in the ramp and in the controller.
 * - **Strap loss.** A second without a heart rate (null) decides nothing. Without one for [HeartRateHoldSettings.signalHoldSeconds]
 *   the signal is [signalLost] (the last power is held, as it was from the first missing second); without one for
 *   [HeartRateHoldSettings.signalDropSeconds] the power drops to `power_min` and [strapLost] is true. When the heart rate comes
 *   back after a loss the filter starts over and the controller continues from where the power is, with its usual step limits.
 * - **The target** is the step's, moved by the rider with [adjustTarget] (the gear buttons, [HeartRateHoldSettings.targetStepBpm]
 *   each, within [HeartRateHoldSettings.maxTargetOffsetBpm]).
 * - **Frozen** (`tick(frozen = true)`, while the stall guard has hold): the filter follows the heart rate but no decision is
 *   made, so the controller does not wind the power up against a guard that is holding it down.
 *
 * @param target what the step asks for.
 * @param targetOffsetBpm the rider's adjustment carried over from an earlier heart-rate step.
 */
public class HeartRateHold(
    private val target: HeartRateTarget,
    private val settings: HeartRateHoldSettings = HeartRateHoldSettings(),
    targetOffsetBpm: Int = 0,
) {
    private var seconds = 0
    private var exactWatts = target.powerMin.toDouble()
    private var smoothed: Double? = null
    private val history = ArrayDeque<Double>()
    private var secondsMissing = 0
    private var secondsSinceDecision = 0
    private var lastBpm: Int? = null
    private var rampFinished = settings.startRampSeconds == 0
    private val alpha = 1.0 - exp(-1.0 / settings.smoothingSeconds)

    /** The rider's adjustment of the target in bpm (what the gear buttons moved it by); carry it into the next heart-rate step. */
    public var targetOffsetBpm: Int =
        targetOffsetBpm.coerceIn(
            -settings.maxTargetOffsetBpm,
            settings.maxTargetOffsetBpm,
        )
        private set

    /** The power to command now, whole watts, within the step's limits. */
    public val watts: Int get() = exactWatts.roundToInt().coerceIn(target.powerMin, target.powerMax)

    /** The heart rate being held now: the step's target moved by the rider. */
    public val targetBpm: Int get() = (target.targetBpm + targetOffsetBpm).coerceAtLeast(1)

    /** Half-width of the band in bpm. */
    public val band: Int get() = target.band

    /** Whether the start ramp is still running: the heart rate is not judged and the controller is not in charge. */
    public val startRamp: Boolean get() = !rampFinished

    /** Whether the last reading was inside the band; null when there is no heart rate now. */
    public val inBand: Boolean? get() = lastBpm?.let { abs(it - targetBpm) <= band }

    /** Whole seconds without a heart rate in a row. */
    public val secondsWithoutHeartRate: Int get() = secondsMissing

    /** The strap has been silent for [HeartRateHoldSettings.signalHoldSeconds]: the last power is held and the overlay should say so. */
    public val signalLost: Boolean get() = secondsMissing >= settings.signalHoldSeconds

    /** The strap has been silent for [HeartRateHoldSettings.signalDropSeconds]: the power was dropped to `power_min`. */
    public val strapLost: Boolean get() = secondsMissing >= settings.signalDropSeconds

    /** What the overlay shows of the hold. */
    public fun state(): HeartRateHoldState =
        HeartRateHoldState(
            targetBpm = targetBpm,
            band = band,
            inBand = inBand,
            startRamp = startRamp,
            signalLost = signalLost,
            strapLost = strapLost,
            powerMin = target.powerMin,
            powerMax = target.powerMax,
        )

    /**
     * Moves the target by [steps] presses of a gear button (positive: up), [HeartRateHoldSettings.targetStepBpm] each, within the
     * limit. Returns whether it changed.
     */
    public fun adjustTarget(steps: Int): Boolean {
        val next =
            (targetOffsetBpm + steps * settings.targetStepBpm)
                .coerceIn(-settings.maxTargetOffsetBpm, settings.maxTargetOffsetBpm)
        if (next == targetOffsetBpm) return false
        targetOffsetBpm = next
        return true
    }

    /**
     * The ride resumes after a pause: the heart rate fell while the rider rested, so what was smoothed and its trend are
     * stale. The filter starts over (no decision until its window is full again) and the power stays where it was.
     */
    public fun resume() {
        forgetHeartRate()
        secondsSinceDecision = 0
    }

    /**
     * One second of riding has passed with [bpm] as the heart rate (null, or 0 from the caller, for no signal). [frozen] stops
     * decisions for this second (the stall guard has hold).
     */
    public fun tick(
        bpm: Int?,
        frozen: Boolean = false,
    ) {
        seconds++
        lastBpm = bpm
        if (bpm == null) {
            secondsMissing++
            if (strapLost) {
                // The strap has been gone too long to trust the last power: the easy end of the range, and no ramp after.
                exactWatts = target.powerMin.toDouble()
                rampFinished = true
            }
        } else {
            if (signalLost) forgetHeartRate()
            secondsMissing = 0
            follow(bpm.toDouble())
        }
        if (!rampFinished) {
            if (!strapLost) exactWatts = rampWatts()
            if (seconds >= settings.startRampSeconds) rampFinished = true
            return
        }
        if (bpm == null || frozen) return
        if (++secondsSinceDecision >= settings.decisionIntervalSeconds) {
            secondsSinceDecision = 0
            decide()
        }
    }

    private fun rampWatts(): Double {
        val end = target.powerMin + settings.startRampFraction * (target.powerMax - target.powerMin)
        return target.powerMin + (end - target.powerMin) * seconds / settings.startRampSeconds
    }

    private fun forgetHeartRate() {
        smoothed = null
        history.clear()
    }

    private fun follow(reading: Double) {
        val next = smoothed?.let { it + alpha * (reading - it) } ?: reading
        smoothed = next
        history.addLast(next)
        if (history.size > settings.trendWindowSeconds + 1) history.removeFirst()
    }

    private fun decide() {
        val now = smoothed ?: return
        if (history.size <= settings.trendWindowSeconds) return
        val slope = (history.last() - history.first()) / (history.size - 1)
        val predicted = now + settings.leadSeconds * slope
        val error = targetBpm - predicted
        if (abs(error) < settings.deadbandBpm) return
        val delta =
            (settings.gainWattsPerBpm * error).coerceIn(
                -settings.maxStepDownWatts.toDouble(),
                settings.maxStepUpWatts.toDouble(),
            )
        exactWatts = (exactWatts + delta).coerceIn(target.powerMin.toDouble(), target.powerMax.toDouble())
    }
}

/**
 * What the overlay needs to show a heart-rate step (spec §11.2), in a [RideSnapshot]: no formatting, no colours.
 */
public data class HeartRateHoldState(
    /** The heart rate being held, in bpm: the step's target moved by the rider's gear buttons. */
    val targetBpm: Int,
    /** Half-width of the band in bpm. */
    val band: Int,
    /** Whether the heart rate is inside the band now; null with no heart-rate signal. */
    val inBand: Boolean?,
    /** The start ramp is running: the controller is not in charge yet and the heart rate is not judged. */
    val startRamp: Boolean,
    /** No heart rate for 15 s (a setting): the last power is held. */
    val signalLost: Boolean,
    /** No heart rate for 60 s (a setting): the power was dropped to [powerMin]. */
    val strapLost: Boolean,
    /** The step's power limits in watts. */
    val powerMin: Int,
    val powerMax: Int,
)
