package app.overdrive.ride.settings

import app.overdrive.core.ride.HeartRateHoldSettings
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.ride.StallGuardSettings
import app.overdrive.core.workout.WorkoutPlayer
import kotlin.math.roundToInt

/**
 * The engine's tuning numbers that the owner changes on the bike without a rebuild (stage A1c, docs/adr/0034): resuming after a pause
 * (docs/adr/0032), the ERG stall guard and the heart-rate hold (docs/adr/0033). Every default is the engine's own, so a fresh install rides as
 * the engine was built to; every value is held inside its [TuningField] rule and [sanitized] puts a stored or edited one back, so
 * **nothing outside the rules can reach `RideSettings`** (whose `require`s would throw): [toRideSettings] builds the engine's settings
 * from a sanitized copy.
 *
 * A change reaches a ride that has not started (the host rebuilds it after the settings settle); a ride under way keeps what it began with.
 */
data class RideTuning(
    /** The small resistance an auto-paused ERG step holds so that the first stroke can be felt; 0 holds the paused target (0 W). */
    val autoPauseTargetWatts: Int = RideSettings.DEFAULT_AUTO_PAUSE_TARGET_WATTS,
    /** Power at or above this ends an auto-pause (0: off). */
    val autoResumePowerWatts: Int = RideSettings.DEFAULT_AUTO_RESUME_POWER_WATTS,
    /** The flywheel speeding up by this much between two readings ends an auto-pause (0: off). */
    val autoResumeFlywheelStepKmh: Double = RideSettings.DEFAULT_AUTO_RESUME_FLYWHEEL_STEP_KMH,
    /** Seconds the ERG target takes to climb back after a pause (0: at once). */
    val rampInSeconds: Int = WorkoutPlayer.DEFAULT_RAMP_IN_SECONDS,
    /** The ERG target held while paused by the rider (and before the start). */
    val pausedTargetWatts: Int = 0,
    val stallGuardEnabled: Boolean = true,
    val stallGuardFloorRpm: Int = StallGuardSettings.DEFAULT_CADENCE_FLOOR_RPM,
    val stallGuardTriggerSeconds: Int = StallGuardSettings.DEFAULT_TRIGGER_SECONDS,
    /** The guard holds the target at this percent of the step's. */
    val stallGuardTargetPercent: Int = (StallGuardSettings.DEFAULT_TARGET_FRACTION * PERCENT).roundToInt(),
    val stallGuardReleaseMarginRpm: Int = StallGuardSettings.DEFAULT_RELEASE_MARGIN_RPM,
    val stallGuardReleaseSeconds: Int = StallGuardSettings.DEFAULT_RELEASE_SECONDS,
    val stallGuardRampBackSeconds: Int = StallGuardSettings.DEFAULT_RAMP_BACK_SECONDS,
    val stallGuardResumeGraceSeconds: Int = StallGuardSettings.DEFAULT_RESUME_GRACE_SECONDS,
    val stallGuardArmTimeoutSeconds: Int = StallGuardSettings.DEFAULT_ARM_TIMEOUT_SECONDS,
    val hrStartRampSeconds: Int = HeartRateHoldSettings.DEFAULT_START_RAMP_SECONDS,
    /** Where the start ramp ends, as a percent of the step's power range. */
    val hrStartRampPercent: Int = (HeartRateHoldSettings.DEFAULT_START_RAMP_FRACTION * PERCENT).roundToInt(),
    val hrGainWattsPerBpm: Double = HeartRateHoldSettings.DEFAULT_GAIN_WATTS_PER_BPM,
    val hrLeadSeconds: Int = HeartRateHoldSettings.DEFAULT_LEAD_SECONDS,
    val hrMaxStepUpWatts: Int = HeartRateHoldSettings.DEFAULT_MAX_STEP_UP_WATTS,
    val hrMaxStepDownWatts: Int = HeartRateHoldSettings.DEFAULT_MAX_STEP_DOWN_WATTS,
    val hrDeadbandBpm: Double = HeartRateHoldSettings.DEFAULT_DEADBAND_BPM,
    val hrSmoothingSeconds: Int = HeartRateHoldSettings.DEFAULT_SMOOTHING_SECONDS,
    val hrTrendWindowSeconds: Int = HeartRateHoldSettings.DEFAULT_TREND_WINDOW_SECONDS,
    val hrSignalHoldSeconds: Int = HeartRateHoldSettings.DEFAULT_SIGNAL_HOLD_SECONDS,
    val hrSignalDropSeconds: Int = HeartRateHoldSettings.DEFAULT_SIGNAL_DROP_SECONDS,
    val hrTargetStepBpm: Int = HeartRateHoldSettings.DEFAULT_TARGET_STEP_BPM,
    val hrMaxTargetOffsetBpm: Int = HeartRateHoldSettings.DEFAULT_MAX_TARGET_OFFSET_BPM,
) {
    /** Every value inside its rule and on its step, and the two strap times in order: what a stored or edited value is read as. */
    fun sanitized(): RideTuning {
        var next = this
        for (field in TuningFields.all) next = field.sanitize(next)
        // The power drops after the signal is held, never before (HeartRateHoldSettings).
        return next.copy(hrSignalDropSeconds = maxOf(next.hrSignalDropSeconds, next.hrSignalHoldSeconds))
    }

    /** What is wrong with these values, in plain words, one line each; empty when they are all acceptable. */
    fun problems(): List<String> {
        val found = TuningFields.all.mapNotNull { field -> field.check(this)?.let { "${field.label}: $it" } }
        return found + listOfNotNull(TuningFields.crossFieldProblem(this))
    }

    /** The engine's settings for these values: the resume-after-pause numbers, the stall guard and the heart-rate hold. */
    fun applyTo(base: RideSettings): RideSettings {
        val t = sanitized()
        return base.copy(
            autoPauseTargetWatts = t.autoPauseTargetWatts,
            autoResumePowerWatts = t.autoResumePowerWatts,
            autoResumeFlywheelStepKmh = t.autoResumeFlywheelStepKmh,
            rampInSeconds = t.rampInSeconds,
            pausedTargetWatts = t.pausedTargetWatts,
            stallGuard =
                StallGuardSettings(
                    enabled = t.stallGuardEnabled,
                    cadenceFloorRpm = t.stallGuardFloorRpm,
                    triggerSeconds = t.stallGuardTriggerSeconds,
                    targetFraction = t.stallGuardTargetPercent / PERCENT,
                    releaseMarginRpm = t.stallGuardReleaseMarginRpm,
                    releaseSeconds = t.stallGuardReleaseSeconds,
                    rampBackSeconds = t.stallGuardRampBackSeconds,
                    resumeGraceSeconds = t.stallGuardResumeGraceSeconds,
                    armTimeoutSeconds = t.stallGuardArmTimeoutSeconds,
                ),
            heartRateHold =
                HeartRateHoldSettings(
                    startRampSeconds = t.hrStartRampSeconds,
                    startRampFraction = t.hrStartRampPercent / PERCENT,
                    gainWattsPerBpm = t.hrGainWattsPerBpm,
                    leadSeconds = t.hrLeadSeconds,
                    smoothingSeconds = t.hrSmoothingSeconds,
                    trendWindowSeconds = t.hrTrendWindowSeconds,
                    deadbandBpm = t.hrDeadbandBpm,
                    maxStepUpWatts = t.hrMaxStepUpWatts,
                    maxStepDownWatts = t.hrMaxStepDownWatts,
                    signalHoldSeconds = t.hrSignalHoldSeconds,
                    signalDropSeconds = t.hrSignalDropSeconds,
                    targetStepBpm = t.hrTargetStepBpm,
                    maxTargetOffsetBpm = t.hrMaxTargetOffsetBpm,
                ),
        )
    }

    /** These values with the fields of [group] put back to their defaults and the others as they are. */
    fun resetGroup(group: TuningGroup): RideTuning {
        var next = this
        for (field in TuningFields.all) if (field.group == group) next = field.reset(next)
        return next
    }

    companion object {
        const val PERCENT: Double = 100.0
    }
}

/** Where a tuning number is shown on the Settings screen (docs/adr/0034). */
enum class TuningGroup(
    val title: String,
) {
    /** The two numbers a rider would look for: the cadence guard's switch and floor. */
    Ride("Ride"),
    ResumeAfterPause("Developer · Resuming after a pause"),
    StallGuard("Developer · Cadence guard"),
    HeartRateHold("Developer · Heart-rate hold"),
}

/**
 * One tuning number of [RideTuning]: its plain [label], what it does ([note]), its [unit] and default, its rule, and how it is read and
 * written. The Settings screen draws a row from each, the repository stores each under `tune_<id>`, and the tests walk the list, so a number
 * added here is shown, kept and tested without more code.
 */
sealed class TuningField(
    /** The stored key's suffix: `tune_<id>`. */
    val id: String,
    val label: String,
    /** One short line: what it does and when to change it. */
    val note: String,
    /** "W", "s", "rpm", ... or "" for a switch. */
    val unit: String,
    val group: TuningGroup,
) {
    /** Puts this field of [tuning] on its rule. */
    abstract fun sanitize(tuning: RideTuning): RideTuning

    /** Why this field's value in [tuning] is not acceptable, or null. */
    abstract fun check(tuning: RideTuning): String?

    /** [tuning] with this field at its default. */
    abstract fun reset(tuning: RideTuning): RideTuning

    /** Whether this field is at its default in [tuning]. */
    abstract fun isDefault(tuning: RideTuning): Boolean

    /** The default as the row says it: "55 rpm", "On". */
    abstract fun defaultText(): String
}

class SwitchTuning(
    id: String,
    label: String,
    note: String,
    group: TuningGroup,
    val default: Boolean,
    val read: (RideTuning) -> Boolean,
    val write: (RideTuning, Boolean) -> RideTuning,
) : TuningField(id, label, note, "", group) {
    override fun sanitize(tuning: RideTuning) = tuning

    override fun check(tuning: RideTuning): String? = null

    override fun reset(tuning: RideTuning) = write(tuning, default)

    override fun isDefault(tuning: RideTuning) = read(tuning) == default

    override fun defaultText() = if (default) "On" else "Off"
}

class IntTuning(
    id: String,
    label: String,
    note: String,
    unit: String,
    group: TuningGroup,
    val rule: IntRule,
    val default: Int,
    val read: (RideTuning) -> Int,
    val write: (RideTuning, Int) -> RideTuning,
    /** A limit that depends on another value (the strap times), or null for the rule's own. */
    val lowest: (RideTuning) -> Int = { rule.min },
    val highest: (RideTuning) -> Int = { rule.max },
) : TuningField(id, label, note, unit, group) {
    override fun sanitize(tuning: RideTuning) = write(tuning, rule.normalize(read(tuning)))

    /** Why [value] is refused, or null: outside the range, or not on the step. */
    fun refusal(
        value: Int,
        tuning: RideTuning,
    ): String? =
        when {
            value < rule.min || value > rule.max -> "Must be ${rule.min} to ${rule.max} $unit".trim()
            value < lowest(tuning) -> "Not below ${lowest(tuning)} $unit".trim()
            value > highest(tuning) -> "Not above ${highest(tuning)} $unit".trim()
            (value - rule.min) % rule.step != 0 -> "Goes in steps of ${rule.step} $unit".trim()
            else -> null
        }

    override fun check(tuning: RideTuning): String? = refusal(read(tuning), tuning)

    override fun reset(tuning: RideTuning) = write(tuning, default)

    override fun isDefault(tuning: RideTuning) = read(tuning) == default

    override fun defaultText() = "$default $unit".trim()

    /** One step up or down from [tuning]'s value, or null when the rule or a limit stops it. */
    fun stepped(
        tuning: RideTuning,
        direction: Int,
    ): Int? {
        val next = rule.stepped(read(tuning), direction)
        return next.takeIf { it != read(tuning) && it >= lowest(tuning) && it <= highest(tuning) }
    }
}

class DecimalTuning(
    id: String,
    label: String,
    note: String,
    unit: String,
    group: TuningGroup,
    val rule: DecimalRule,
    val default: Double,
    /** Decimals shown. */
    val places: Int,
    val read: (RideTuning) -> Double,
    val write: (RideTuning, Double) -> RideTuning,
) : TuningField(id, label, note, unit, group) {
    override fun sanitize(tuning: RideTuning) = write(tuning, rule.normalize(read(tuning)))

    fun refusal(value: Double): String? =
        when {
            !value.isFinite() -> {
                "Must be a number"
            }

            value < rule.min - EPS || value > rule.max + EPS -> {
                "Must be ${show(
                    rule.min,
                )} to ${show(rule.max)} $unit".trim()
            }

            else -> {
                null
            }
        }

    override fun check(tuning: RideTuning): String? = refusal(read(tuning))

    override fun reset(tuning: RideTuning) = write(tuning, default)

    override fun isDefault(tuning: RideTuning) = kotlin.math.abs(read(tuning) - default) < EPS

    override fun defaultText() = "${show(default)} $unit".trim()

    fun show(value: Double): String = app.overdrive.uikit.UiFormat.decimal(value, places)

    private companion object {
        const val EPS = 1e-9
    }
}

/** The tuning numbers, in the order the Settings screen shows them. */
object TuningFields {
    val all: List<TuningField> =
        listOf(
            // ---- Ride: what a rider would look for ----
            SwitchTuning(
                "stall_guard_enabled",
                "Cadence guard",
                "Lowers the target when your cadence drops, so ERG cannot stall you",
                TuningGroup.Ride,
                true,
                { it.stallGuardEnabled },
                { t, v -> t.copy(stallGuardEnabled = v) },
            ),
            IntTuning(
                "stall_guard_floor_rpm",
                "Cadence floor",
                "The guard acts below this cadence",
                "rpm",
                TuningGroup.Ride,
                IntRule(30, 90, 1),
                StallGuardSettings.DEFAULT_CADENCE_FLOOR_RPM,
                { it.stallGuardFloorRpm },
                { t, v -> t.copy(stallGuardFloorRpm = v) },
            ),
            // ---- Resuming after a pause (docs/adr/0032) ----
            IntTuning(
                "auto_pause_target_watts",
                "Resistance in an auto-pause",
                "Held when you stop pedalling, so the first stroke is felt. 0 holds the paused target",
                "W",
                TuningGroup.ResumeAfterPause,
                IntRule(0, 300, 5),
                RideSettings.DEFAULT_AUTO_PAUSE_TARGET_WATTS,
                { it.autoPauseTargetWatts },
                { t, v -> t.copy(autoPauseTargetWatts = v) },
            ),
            IntTuning(
                "auto_resume_power_watts",
                "Resume at power",
                "Power at or above this ends an auto-pause. 0 turns it off",
                "W",
                TuningGroup.ResumeAfterPause,
                IntRule(0, 100, 1),
                RideSettings.DEFAULT_AUTO_RESUME_POWER_WATTS,
                { it.autoResumePowerWatts },
                { t, v -> t.copy(autoResumePowerWatts = v) },
            ),
            DecimalTuning(
                "auto_resume_flywheel_step_kmh",
                "Resume at flywheel speed-up",
                "The flywheel speeding up by this much ends an auto-pause. 0 turns it off",
                "km/h",
                TuningGroup.ResumeAfterPause,
                DecimalRule(0.0, 5.0, 0.1),
                RideSettings.DEFAULT_AUTO_RESUME_FLYWHEEL_STEP_KMH,
                1,
                { it.autoResumeFlywheelStepKmh },
                { t, v -> t.copy(autoResumeFlywheelStepKmh = v) },
            ),
            IntTuning(
                "ramp_in_seconds",
                "Ramp back in",
                "How long the target takes to climb back after a pause. 0 is at once",
                "s",
                TuningGroup.ResumeAfterPause,
                IntRule(0, 10, 1),
                WorkoutPlayer.DEFAULT_RAMP_IN_SECONDS,
                { it.rampInSeconds },
                { t, v -> t.copy(rampInSeconds = v) },
            ),
            IntTuning(
                "paused_target_watts",
                "Target while paused",
                "Held when you pause by hand, so you can spin up freely",
                "W",
                TuningGroup.ResumeAfterPause,
                IntRule(0, 100, 5),
                0,
                { it.pausedTargetWatts },
                { t, v -> t.copy(pausedTargetWatts = v) },
            ),
            // ---- The stall guard (spec §11.1) ----
            IntTuning(
                "stall_guard_trigger_seconds",
                "Seconds below the floor",
                "How long the cadence stays under the floor before the guard acts",
                "s",
                TuningGroup.StallGuard,
                IntRule(1, 10, 1),
                StallGuardSettings.DEFAULT_TRIGGER_SECONDS,
                { it.stallGuardTriggerSeconds },
                { t, v -> t.copy(stallGuardTriggerSeconds = v) },
            ),
            IntTuning(
                "stall_guard_target_percent",
                "Target while it holds",
                "The share of the step's target the trainer is held at",
                "%",
                TuningGroup.StallGuard,
                IntRule(30, 95, 5),
                (StallGuardSettings.DEFAULT_TARGET_FRACTION * RideTuning.PERCENT).roundToInt(),
                { it.stallGuardTargetPercent },
                { t, v -> t.copy(stallGuardTargetPercent = v) },
            ),
            IntTuning(
                "stall_guard_release_margin_rpm",
                "Let go above the floor by",
                "Cadence this far over the floor starts the release",
                "rpm",
                TuningGroup.StallGuard,
                IntRule(0, 30, 1),
                StallGuardSettings.DEFAULT_RELEASE_MARGIN_RPM,
                { it.stallGuardReleaseMarginRpm },
                { t, v -> t.copy(stallGuardReleaseMarginRpm = v) },
            ),
            IntTuning(
                "stall_guard_release_seconds",
                "Seconds above to let go",
                "How long the cadence must stay there",
                "s",
                TuningGroup.StallGuard,
                IntRule(1, 30, 1),
                StallGuardSettings.DEFAULT_RELEASE_SECONDS,
                { it.stallGuardReleaseSeconds },
                { t, v -> t.copy(stallGuardReleaseSeconds = v) },
            ),
            IntTuning(
                "stall_guard_ramp_back_seconds",
                "Climb back over",
                "How long the target takes to come back after the release",
                "s",
                TuningGroup.StallGuard,
                IntRule(0, 30, 1),
                StallGuardSettings.DEFAULT_RAMP_BACK_SECONDS,
                { it.stallGuardRampBackSeconds },
                { t, v -> t.copy(stallGuardRampBackSeconds = v) },
            ),
            IntTuning(
                "stall_guard_resume_grace_seconds",
                "Not judged after a resume",
                "Seconds after a pause in which the cadence is not judged",
                "s",
                TuningGroup.StallGuard,
                IntRule(0, 30, 1),
                StallGuardSettings.DEFAULT_RESUME_GRACE_SECONDS,
                { it.stallGuardResumeGraceSeconds },
                { t, v -> t.copy(stallGuardResumeGraceSeconds = v) },
            ),
            IntTuning(
                "stall_guard_arm_timeout_seconds",
                "Longest wait to get up to speed",
                "After the start or a resume the guard waits for the first pedalling, at most this long",
                "s",
                TuningGroup.StallGuard,
                IntRule(0, 120, 5),
                StallGuardSettings.DEFAULT_ARM_TIMEOUT_SECONDS,
                { it.stallGuardArmTimeoutSeconds },
                { t, v -> t.copy(stallGuardArmTimeoutSeconds = v) },
            ),
            // ---- The heart-rate hold (spec §11.2) ----
            IntTuning(
                "hr_start_ramp_seconds",
                "Start ramp",
                "An easy ramp before the controller takes over; the heart rate is not judged meanwhile",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(0, 600, 10),
                HeartRateHoldSettings.DEFAULT_START_RAMP_SECONDS,
                { it.hrStartRampSeconds },
                { t, v -> t.copy(hrStartRampSeconds = v) },
            ),
            IntTuning(
                "hr_start_ramp_percent",
                "Start ramp ends at",
                "The share of the step's power range where the ramp ends. Lower if the heart rate overshoots",
                "%",
                TuningGroup.HeartRateHold,
                IntRule(0, 100, 5),
                (HeartRateHoldSettings.DEFAULT_START_RAMP_FRACTION * RideTuning.PERCENT).roundToInt(),
                { it.hrStartRampPercent },
                { t, v -> t.copy(hrStartRampPercent = v) },
            ),
            DecimalTuning(
                "hr_gain_watts_per_bpm",
                "Gain",
                "Watts added per bpm of error at each decision. Lower if power and heart rate swing",
                "W/bpm",
                TuningGroup.HeartRateHold,
                DecimalRule(0.0, 2.0, 0.1),
                HeartRateHoldSettings.DEFAULT_GAIN_WATTS_PER_BPM,
                1,
                { it.hrGainWattsPerBpm },
                { t, v -> t.copy(hrGainWattsPerBpm = v) },
            ),
            IntTuning(
                "hr_lead_seconds",
                "Look-ahead",
                "How far ahead the trend is read. 0 turns the prediction off",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(0, 120, 5),
                HeartRateHoldSettings.DEFAULT_LEAD_SECONDS,
                { it.hrLeadSeconds },
                { t, v -> t.copy(hrLeadSeconds = v) },
            ),
            IntTuning(
                "hr_max_step_up_watts",
                "Largest rise per decision",
                "The most the power goes up at one decision",
                "W",
                TuningGroup.HeartRateHold,
                IntRule(0, 20, 1),
                HeartRateHoldSettings.DEFAULT_MAX_STEP_UP_WATTS,
                { it.hrMaxStepUpWatts },
                { t, v -> t.copy(hrMaxStepUpWatts = v) },
            ),
            IntTuning(
                "hr_max_step_down_watts",
                "Largest fall per decision",
                "The most the power goes down at one decision",
                "W",
                TuningGroup.HeartRateHold,
                IntRule(0, 30, 1),
                HeartRateHoldSettings.DEFAULT_MAX_STEP_DOWN_WATTS,
                { it.hrMaxStepDownWatts },
                { t, v -> t.copy(hrMaxStepDownWatts = v) },
            ),
            DecimalTuning(
                "hr_deadband_bpm",
                "Ignore errors under",
                "A heart rate this close to the target changes nothing",
                "bpm",
                TuningGroup.HeartRateHold,
                DecimalRule(0.0, 5.0, 0.5),
                HeartRateHoldSettings.DEFAULT_DEADBAND_BPM,
                1,
                { it.hrDeadbandBpm },
                { t, v -> t.copy(hrDeadbandBpm = v) },
            ),
            IntTuning(
                "hr_smoothing_seconds",
                "Smoothing",
                "How much the heart rate is smoothed before it is used",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(1, 60, 1),
                HeartRateHoldSettings.DEFAULT_SMOOTHING_SECONDS,
                { it.hrSmoothingSeconds },
                { t, v -> t.copy(hrSmoothingSeconds = v) },
            ),
            IntTuning(
                "hr_trend_window_seconds",
                "Trend window",
                "The time the trend is read over; no decision until it is full",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(10, 120, 5),
                HeartRateHoldSettings.DEFAULT_TREND_WINDOW_SECONDS,
                { it.hrTrendWindowSeconds },
                { t, v -> t.copy(hrTrendWindowSeconds = v) },
            ),
            IntTuning(
                "hr_signal_hold_seconds",
                "Strap lost: hold power after",
                "Without a heart rate this long the last power is held and the overlay says so",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(5, 60, 1),
                HeartRateHoldSettings.DEFAULT_SIGNAL_HOLD_SECONDS,
                { it.hrSignalHoldSeconds },
                { t, v -> t.copy(hrSignalHoldSeconds = v) },
                highest = { it.hrSignalDropSeconds },
            ),
            IntTuning(
                "hr_signal_drop_seconds",
                "Strap lost: drop to minimum after",
                "Without a heart rate this long the power drops to the step's minimum",
                "s",
                TuningGroup.HeartRateHold,
                IntRule(15, 300, 5),
                HeartRateHoldSettings.DEFAULT_SIGNAL_DROP_SECONDS,
                { it.hrSignalDropSeconds },
                { t, v -> t.copy(hrSignalDropSeconds = v) },
                lowest = { it.hrSignalHoldSeconds },
            ),
            IntTuning(
                "hr_target_step_bpm",
                "Gear button changes the target by",
                "One press of a gear button moves the target heart rate by this much",
                "bpm",
                TuningGroup.HeartRateHold,
                IntRule(0, 5, 1),
                HeartRateHoldSettings.DEFAULT_TARGET_STEP_BPM,
                { it.hrTargetStepBpm },
                { t, v -> t.copy(hrTargetStepBpm = v) },
            ),
            IntTuning(
                "hr_max_target_offset_bpm",
                "Target can move at most",
                "How far the gear buttons can move the target from the step's",
                "bpm",
                TuningGroup.HeartRateHold,
                IntRule(0, 30, 1),
                HeartRateHoldSettings.DEFAULT_MAX_TARGET_OFFSET_BPM,
                { it.hrMaxTargetOffsetBpm },
                { t, v -> t.copy(hrMaxTargetOffsetBpm = v) },
            ),
        )

    fun of(group: TuningGroup): List<TuningField> = all.filter { it.group == group }

    /** The one rule between two fields (the engine's `require`): the power drops after the signal is held, never before. */
    fun crossFieldProblem(tuning: RideTuning): String? =
        if (tuning.hrSignalDropSeconds < tuning.hrSignalHoldSeconds) {
            "Strap lost: the power drops after it is held, so ${tuning.hrSignalDropSeconds} s is below ${tuning.hrSignalHoldSeconds} s"
        } else {
            null
        }
}
