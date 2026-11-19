package app.overdrive.core.ride

import kotlin.math.roundToInt

/**
 * The settings of the ERG stall guard (spec §11.1, docs/adr/0033). The defaults are the spec's; the rest are first guesses
 * for the owner to tune on the bike.
 */
public data class StallGuardSettings(
    /** Whether the guard is on. Off, the ride engine never lowers a target because of the cadence. */
    val enabled: Boolean = true,
    /** Cadence below this many rpm counts as a stall (spec §11.1: 55). */
    val cadenceFloorRpm: Int = DEFAULT_CADENCE_FLOOR_RPM,
    /** Seconds in a row below the floor before the guard takes hold (spec §11.1: 3). */
    val triggerSeconds: Int = DEFAULT_TRIGGER_SECONDS,
    /** The fraction of the target the trainer is held at while the guard has hold (spec §11.1: 70%). */
    val targetFraction: Double = DEFAULT_TARGET_FRACTION,
    /** The guard lets go when cadence is above the floor plus this many rpm (spec §11.1: 10)... */
    val releaseMarginRpm: Int = DEFAULT_RELEASE_MARGIN_RPM,
    /** ...for this many seconds in a row (spec §11.1: 5). */
    val releaseSeconds: Int = DEFAULT_RELEASE_SECONDS,
    /** Seconds over which the target then climbs back to what the step asks for (spec §11.1: 5). */
    val rampBackSeconds: Int = DEFAULT_RAMP_BACK_SECONDS,
    /**
     * Seconds after a resume (docs/adr/0032, `RideSnapshot.secondsSinceResume`) in which cadence is not judged at all: a
     * rider who restarts after a pause is not stalling.
     */
    val resumeGraceSeconds: Int = DEFAULT_RESUME_GRACE_SECONDS,
    /**
     * A ride that starts, or restarts after a pause, is not judged until the rider has pedalled at or above the floor once:
     * getting up to speed is not a stall. This bounds the wait: after this many riding seconds the guard is armed anyway, so
     * a rider who never gets up to speed against the target is still protected.
     */
    val armTimeoutSeconds: Int = DEFAULT_ARM_TIMEOUT_SECONDS,
) {
    init {
        require(cadenceFloorRpm in 1..MAX_RPM) { "The cadence floor must be 1..$MAX_RPM rpm, got $cadenceFloorRpm" }
        require(triggerSeconds >= 1) { "The trigger needs at least 1 second, got $triggerSeconds" }
        require(targetFraction > 0.0 && targetFraction < 1.0) {
            "The guard target must be a fraction between 0 and 1, got $targetFraction"
        }
        require(releaseMarginRpm >= 0) { "The release margin must not be negative, got $releaseMarginRpm" }
        require(releaseSeconds >= 1) { "The release needs at least 1 second, got $releaseSeconds" }
        require(rampBackSeconds >= 0) { "The ramp back must not be negative, got $rampBackSeconds" }
        require(resumeGraceSeconds >= 0) { "The resume grace must not be negative, got $resumeGraceSeconds" }
        require(armTimeoutSeconds >= 0) { "The arm timeout must not be negative, got $armTimeoutSeconds" }
    }

    public companion object {
        public const val DEFAULT_CADENCE_FLOOR_RPM: Int = 55
        public const val DEFAULT_TRIGGER_SECONDS: Int = 3
        public const val DEFAULT_TARGET_FRACTION: Double = 0.70
        public const val DEFAULT_RELEASE_MARGIN_RPM: Int = 10
        public const val DEFAULT_RELEASE_SECONDS: Int = 5
        public const val DEFAULT_RAMP_BACK_SECONDS: Int = 5

        /** Covers the 3 s ramp-in and the D500's 4 to 9 s response to a new target (docs/adr/0032). */
        public const val DEFAULT_RESUME_GRACE_SECONDS: Int = 5
        public const val DEFAULT_ARM_TIMEOUT_SECONDS: Int = 30
        private const val MAX_RPM = 250
    }
}

/**
 * The ERG stall guard (spec §11.1). In ERG a falling cadence makes the trainer raise the torque to hold the power, which
 * drops the cadence further until the pedals stop; the guard breaks the loop by lowering the target to a fraction of what
 * the step asks for (70%) and holding it there until the rider is turning the pedals again.
 *
 * It is **pure and deterministic**: no clock, no coroutines, no I/O. The ride engine calls [tick] once for every second of
 * *riding* (never while paused) and [reset] whenever the guard has nothing to judge (paused, or not in an ERG step), and
 * asks [limit] what to send the trainer.
 *
 * **States.** *Watching* (the target is as the step asks), *holding* (the target is the fraction) and *releasing* (the target
 * climbs back over [StallGuardSettings.rampBackSeconds]); the guard is [active] in the last two.
 * - **Takes hold** when cadence has been below the floor for [StallGuardSettings.triggerSeconds] ticks in a row.
 * - **Lets go** when cadence has been above the floor plus the margin for [StallGuardSettings.releaseSeconds] ticks in a row,
 *   and then climbs back linearly in whole-second steps. A new stall while it climbs back takes hold again at once (a new
 *   activation).
 * - **A missing cadence (null) is not a low cadence**: it never counts toward taking hold (it restarts the count, since
 *   the seconds must be in a row) and never toward letting go.
 * - **The guard caps whatever target it is given**: [limit] scales the watts the player would command (the step's target
 *   times the intensity, or a ramp-in after a resume, or the heart-rate controller's power) by the factor, so it composes
 *   with all of them and never raises anything.
 * - **Not judged right after a start or a resume.** [reset] leaves the guard *unarmed*: until the rider has pedalled at or
 *   above the floor once (or [StallGuardSettings.armTimeoutSeconds] riding seconds have passed) nothing is judged, and for
 *   [StallGuardSettings.resumeGraceSeconds] after a resume (`secondsSinceResume`, docs/adr/0032) nothing is judged either.
 * - **Auto-pause wins at 0 rpm** when it is on ([zeroCadenceIsAStop]): a cadence of exactly 0 means the rider stopped, which is
 *   the auto-pause's business (it holds a small target and waits to resume); it is not a slow rider, so it does not count
 *   toward taking hold (it restarts the count) and the guard never fires on a stop. A guard that already has hold stays in
 *   force through zeros until the pause comes and resets it. With auto-pause off, 0 rpm is below the floor like any cadence.
 *
 * @param zeroCadenceIsAStop true when the ride auto-pauses on zero cadence, so a zero is left to the auto-pause.
 */
public class StallGuard(
    private val settings: StallGuardSettings = StallGuardSettings(),
    private val zeroCadenceIsAStop: Boolean = true,
) {
    private enum class Phase { WATCHING, HOLDING, RELEASING }

    private var phase = Phase.WATCHING
    private var armed = false
    private var ridingSeconds = 0
    private var lowSeconds = 0
    private var recoveredSeconds = 0
    private var rampBackStep = 0

    /** Whether the guard is limiting the target now: holding it down, or still climbing back. */
    public val active: Boolean get() = phase != Phase.WATCHING

    /** Whether the guard is holding the target at its fraction now (not yet climbing back). */
    public val holding: Boolean get() = phase == Phase.HOLDING

    /** The fraction of the target that is commanded now: 1.0 when [active] is false, the fraction while holding, between while it climbs back. */
    public val factor: Double
        get() =
            when (phase) {
                Phase.WATCHING -> {
                    1.0
                }

                Phase.HOLDING -> {
                    settings.targetFraction
                }

                Phase.RELEASING -> {
                    settings.targetFraction +
                        (1.0 - settings.targetFraction) * rampBackStep / settings.rampBackSeconds.coerceAtLeast(1)
                }
            }

    /** The watts to command for [watts] that the step (or the ramp-in, or the controller) asks for: scaled by [factor], whole watts. */
    public fun limit(watts: Int): Int = if (phase == Phase.WATCHING) watts else (watts * factor).roundToInt()

    /** Forgets everything and leaves the guard unarmed: call when the ride is paused, or the step has no ERG target. */
    public fun reset() {
        phase = Phase.WATCHING
        armed = false
        ridingSeconds = 0
        lowSeconds = 0
        recoveredSeconds = 0
        rampBackStep = 0
    }

    /**
     * One second of riding in an ERG step has passed, with the cadence the trainer reported for it ([cadenceRpm], null for no
     * reading) and the whole seconds since the ride last resumed ([secondsSinceResume], null when it has not: the ride's
     * start is not a resume).
     *
     * @return true on the tick the guard takes hold, which is the moment to record it as an event; false otherwise.
     */
    public fun tick(
        cadenceRpm: Int?,
        secondsSinceResume: Int?,
    ): Boolean {
        if (!settings.enabled) {
            reset()
            return false
        }
        ridingSeconds++
        val floor = settings.cadenceFloorRpm
        when (phase) {
            Phase.WATCHING -> {
                return watch(cadenceRpm, secondsSinceResume)
            }

            Phase.HOLDING -> {
                if (cadenceRpm != null &&
                    cadenceRpm >= floor + settings.releaseMarginRpm
                ) {
                    recoveredSeconds++
                } else {
                    recoveredSeconds = 0
                }
                if (recoveredSeconds >= settings.releaseSeconds) {
                    phase = Phase.RELEASING
                    recoveredSeconds = 0
                    rampBackStep = 0
                    lowSeconds = 0
                    if (settings.rampBackSeconds == 0) phase = Phase.WATCHING
                }
            }

            Phase.RELEASING -> {
                lowSeconds = if (isLow(cadenceRpm)) lowSeconds + 1 else 0
                if (lowSeconds >= settings.triggerSeconds) {
                    phase = Phase.HOLDING
                    recoveredSeconds = 0
                    lowSeconds = 0
                    return true
                }
                rampBackStep++
                if (rampBackStep >= settings.rampBackSeconds) {
                    phase = Phase.WATCHING
                    lowSeconds = 0
                }
            }
        }
        return false
    }

    private fun watch(
        cadenceRpm: Int?,
        secondsSinceResume: Int?,
    ): Boolean {
        if (!armed) {
            if (cadenceRpm != null && cadenceRpm >= settings.cadenceFloorRpm) armed = true
            if (ridingSeconds >= settings.armTimeoutSeconds) armed = true
        }
        val justResumed = secondsSinceResume != null && secondsSinceResume < settings.resumeGraceSeconds
        if (!armed || justResumed) {
            lowSeconds = 0
            return false
        }
        lowSeconds = if (isLow(cadenceRpm)) lowSeconds + 1 else 0
        if (lowSeconds < settings.triggerSeconds) return false
        phase = Phase.HOLDING
        recoveredSeconds = 0
        lowSeconds = 0
        return true
    }

    /** Below the floor, with a missing reading never low and a stop (0 rpm) left to the auto-pause when it is on. */
    private fun isLow(cadenceRpm: Int?): Boolean =
        cadenceRpm != null && cadenceRpm < settings.cadenceFloorRpm && !(cadenceRpm == 0 && zeroCadenceIsAStop)
}
