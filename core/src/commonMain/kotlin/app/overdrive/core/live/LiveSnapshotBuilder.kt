package app.overdrive.core.live

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How well the rider is holding the step's target, as the live coach's `compliancePercent` says it (spec §12.1, `contracts/schema/live-snapshot.schema.json`):
 * **the share of the current step's judged seconds that were on target**, 0 to 100, from what the ride engine already knows and nothing else.
 *
 * A second of the ride is **judged** when all of these hold, and is otherwise left out of both the count and the share:
 *
 * - the ride is riding (not paused, not before the start or after the end);
 * - it is the second of a step that has a target: a steady or ramp step's power, or a heart-rate step's band; **a free-ride step has none** and
 *   no share (`null`);
 * - the step has been going for at least one second (the readings of the tick that moves the ride into a step still belong to the one before);
 * - the rider is not still getting going after a pause: not within the [RESUME_GRACE_SECONDS] after a resume (the trainer ramps its target
 *   back in, and the stall guard does not judge them either);
 * - the **stall guard is not holding the target down** (the target the rider is held to is the guard's, not the step's, and the coach is told
 *   about the guard separately);
 * - there is a reading to judge: the power for a steady or ramp step, and for a heart-rate step the controller's own verdict (`HeartRateHoldState.inBand`),
 *   which is only given once its start ramp is over and the strap has a signal.
 *
 * A second is **on target** when the power is within [POWER_TOLERANCE_PERCENT] of the target the trainer is asked for (the step's target
 * times the rider's intensity, without the stall guard's cut: `RideSnapshot.targetPower`), or when the heart rate is inside the band of the step.
 * The share is rounded to a whole percent, halves up; before the first judged second there is none.
 *
 * Pure: the same snapshot is always the same verdict.
 */
public object LiveCompliance {
    /** A second is on target when the power is within this many percent of the target. */
    public const val POWER_TOLERANCE_PERCENT: Int = 5

    /** Seconds after a resume in which a second is not judged. */
    public const val RESUME_GRACE_SECONDS: Int = 5

    /** What one second of the ride came to. */
    public enum class Verdict {
        NOT_JUDGED,
        OFF_TARGET,
        ON_TARGET,
    }

    /** The verdict on the second the [snapshot] was taken at the end of. */
    public fun judge(snapshot: RideSnapshot): Verdict {
        if (snapshot.state != RideState.RIDING) return Verdict.NOT_JUDGED
        val progress = snapshot.workout ?: return Verdict.NOT_JUDGED
        if (progress.stepElapsedSeconds < 1) return Verdict.NOT_JUDGED
        val resumed = snapshot.secondsSinceResume
        if (resumed != null && resumed < RESUME_GRACE_SECONDS) return Verdict.NOT_JUDGED
        val hold = snapshot.heartRateHold
        if (hold != null) {
            if (hold.startRamp || hold.signalLost || hold.strapLost) return Verdict.NOT_JUDGED
            val inBand = hold.inBand ?: return Verdict.NOT_JUDGED
            return if (inBand) Verdict.ON_TARGET else Verdict.OFF_TARGET
        }
        if (progress.step.targetFrom == null || snapshot.stallGuardActive) return Verdict.NOT_JUDGED
        val power = snapshot.power ?: return Verdict.NOT_JUDGED
        val target = snapshot.targetPower ?: return Verdict.NOT_JUDGED
        return if (abs(power - target) * PERCENT <=
            target * POWER_TOLERANCE_PERCENT
        ) {
            Verdict.ON_TARGET
        } else {
            Verdict.OFF_TARGET
        }
    }

    /** The share of [judged] seconds that were on target, a whole percent (halves up), or null before the first judged second. */
    public fun percent(
        onTarget: Int,
        judged: Int,
    ): Int? {
        if (judged <= 0) return null
        return ((onTarget * PERCENT + judged / 2) / judged).coerceIn(0, PERCENT)
    }

    private const val PERCENT = 100
}

/**
 * Turns the ride's snapshots into the live coach's (spec §12.1, docs/adr/0039): fed with **every** [RideSnapshot] the host publishes, it keeps
 * what a snapshot of the last 5 seconds needs and builds one on request, so the controller decides only when.
 *
 * - **Means of the last 5 seconds** of power, heart rate and cadence: over the ticks of the ride's own clock ([RideSnapshot.rideSeconds]; the
 *   engine also publishes after every command, and those repeats are counted once), whole numbers, absent when none of the 5 had a reading. A heart
 *   rate that is not a heart rate (under 20 bpm) is absent.
 * - **The step** as the ride record flattens it: its index, type (a ramp whose ends are equal is steady), label, length, time left (pauses do not
 *   count), the target power (the step's, never the stall guard's cut) or the heart rate the step holds, and **`stepCount` exactly the number of the flattened workout's steps**.
 * - **Compliance** by [LiveCompliance], counted for the step now: it starts over when the ride moves to another step or back to the start of this one.
 * - **Events**: what the coach may care about, newest first, at most 8 and no older than 10 minutes: the stall guard taking hold, a pause and a
 *   resume, and the strap lost (a heart rate that was there is gone for [STRAP_LOST_AFTER_SECONDS] ticks; it is reported once per loss).
 * - `paused` is the ride being paused for any reason; `elapsedSeconds` is the ride's clock with the pauses in it.
 *
 * Pure and single-threaded: no clock, nothing but the snapshots it is given. **The readings are the rider's body**: nothing here prints one.
 */
public class LiveSnapshotBuilder {
    private class Reading(
        val second: Int,
        val power: Int?,
        val heartRate: Int?,
        val cadence: Int?,
    )

    private class Occurrence(
        val type: LiveEventType,
        val second: Int,
    )

    private val window = ArrayDeque<Reading>()
    private var latest: RideSnapshot? = null
    private var lastTick = 0

    private var tallyStep = -1
    private var tallyStepElapsed = -1
    private var judged = 0
    private var onTarget = 0

    private val occurrences = ArrayDeque<Occurrence>()
    private var wasPaused = false
    private var wasGuarded = false
    private var strapSeen = false
    private var strapMissing = 0
    private var strapLostReported = false

    /** Takes the ride's latest snapshot. Call it for every one that is published. */
    public fun accept(snapshot: RideSnapshot) {
        latest = snapshot
        noteStates(snapshot)
        val second = snapshot.rideSeconds
        if (second > lastTick) {
            lastTick = second
            noteTick(snapshot)
        }
    }

    private fun noteStates(snapshot: RideSnapshot) {
        val second = snapshot.rideSeconds
        val paused = snapshot.state == RideState.PAUSED
        if (snapshot.state == RideState.PAUSED || snapshot.state == RideState.RIDING) {
            if (paused && !wasPaused) note(LiveEventType.PAUSED, second)
            if (!paused && wasPaused) note(LiveEventType.RESUMED, second)
            wasPaused = paused
        }
        val guarded = snapshot.stallGuardActive
        if (guarded && !wasGuarded) note(LiveEventType.STALL_GUARD, second)
        wasGuarded = guarded
    }

    private fun note(
        type: LiveEventType,
        second: Int,
    ) {
        occurrences.addLast(Occurrence(type, second))
        while (occurrences.size > KEPT_EVENTS) occurrences.removeFirst()
    }

    private fun noteTick(snapshot: RideSnapshot) {
        val second = snapshot.rideSeconds
        window.addLast(Reading(second, snapshot.power, snapshot.heartRate, snapshot.cadence))
        while (window.isNotEmpty() && window.first().second <= second - WINDOW_SECONDS) window.removeFirst()

        // The strap: a heart rate that was there and is gone for a while.
        if (snapshot.heartRate != null) {
            strapSeen = true
            strapMissing = 0
            strapLostReported = false
        } else if (strapSeen) {
            strapMissing++
            if (strapMissing >= STRAP_LOST_AFTER_SECONDS && !strapLostReported) {
                strapLostReported = true
                note(LiveEventType.STRAP_LOST, second)
            }
        }

        // Compliance of the step now.
        val progress = snapshot.workout
        if (progress == null) {
            tallyStep = -1
            return
        }
        if (progress.stepIndex != tallyStep || progress.stepElapsedSeconds < tallyStepElapsed) {
            tallyStep = progress.stepIndex
            judged = 0
            onTarget = 0
        }
        tallyStepElapsed = progress.stepElapsedSeconds
        when (LiveCompliance.judge(snapshot)) {
            LiveCompliance.Verdict.ON_TARGET -> {
                judged++
                onTarget++
            }

            LiveCompliance.Verdict.OFF_TARGET -> {
                judged++
            }

            LiveCompliance.Verdict.NOT_JUDGED -> {}
        }
    }

    /**
     * The snapshot to send now, with [seq] its number in the session, [clockMillis] the app's clock and [muted] the rider's mute; or null while
     * nothing is known of a workout step (a free ride, or no snapshot yet).
     */
    public fun build(
        seq: Int,
        clockMillis: Long,
        muted: Boolean,
    ): LiveSnapshot? {
        val ride = latest ?: return null
        val progress = ride.workout ?: return null
        val flat = progress.step
        val type =
            when {
                flat.heartRate != null -> LiveStepType.HEART_RATE
                flat.targetFrom == null -> LiveStepType.FREE
                flat.targetFrom != flat.targetTo -> LiveStepType.RAMP
                else -> LiveStepType.STEADY
            }
        val step =
            LiveSnapshotStep(
                index = progress.stepIndex.coerceIn(0, LiveSnapshotStep.MAX_STEP_INDEX),
                type = type,
                label = flat.label.takeCodePoints(LiveSnapshotStep.MAX_LABEL_CHARS).ifEmpty { null },
                durationSeconds = flat.durationSeconds.coerceAtMost(LiveSnapshotStep.MAX_SECONDS),
                timeLeftSeconds =
                    progress.stepRemainingSeconds.coerceIn(
                        0,
                        flat.durationSeconds.coerceAtMost(LiveSnapshotStep.MAX_SECONDS),
                    ),
                targetWatts =
                    if (type == LiveStepType.STEADY || type == LiveStepType.RAMP) {
                        ride.targetPower?.coerceIn(0, LiveSnapshotStep.MAX_WATTS)
                    } else {
                        null
                    },
                targetBpm =
                    if (type == LiveStepType.HEART_RATE) {
                        ride.heartRateHold?.targetBpm?.coerceIn(LiveSnapshotStep.MIN_BPM, LiveSnapshotStep.MAX_BPM)
                    } else {
                        null
                    },
            )
        val second = ride.rideSeconds
        val recent = window.filter { it.second > second - WINDOW_SECONDS }
        return LiveSnapshot(
            seq = seq,
            clock = clockMillis,
            elapsedSeconds = second.coerceIn(0, LiveSnapshotStep.MAX_SECONDS),
            paused = ride.state == RideState.PAUSED,
            muted = muted,
            step = step,
            stepCount = progress.steps.size.coerceIn(step.index + 1, LiveSnapshot.MAX_STEP_COUNT),
            power = mean(recent.mapNotNull { it.power })?.coerceIn(0, LiveSnapshotStep.MAX_WATTS),
            heartRate =
                mean(recent.mapNotNull { it.heartRate })
                    ?.takeIf { it >= LiveSnapshotStep.MIN_BPM }
                    ?.coerceAtMost(LiveSnapshotStep.MAX_BPM),
            cadence = mean(recent.mapNotNull { it.cadence })?.coerceIn(0, LiveSnapshot.MAX_CADENCE),
            compliancePercent = if (type == LiveStepType.FREE) null else LiveCompliance.percent(onTarget, judged),
            stallGuardActive = ride.stallGuardActive,
            events = eventsAt(second),
        )
    }

    private fun eventsAt(second: Int): List<LiveSnapshotEvent> =
        occurrences
            .filter { second - it.second in 0..LiveCoachJson.EVENT_WINDOW_SECONDS }
            .asReversed()
            .take(LiveSnapshot.MAX_EVENTS)
            .map { LiveSnapshotEvent(it.type, second - it.second) }

    private fun mean(values: List<Int>): Int? =
        if (values.isEmpty()) null else (values.sum().toDouble() / values.size).roundToInt()

    private fun String.takeCodePoints(count: Int): String {
        var points = 0
        var i = 0
        while (i < length && points < count) {
            i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
            points++
        }
        return substring(0, i)
    }

    public companion object {
        /** The means are over this many seconds of the ride's clock. */
        public const val WINDOW_SECONDS: Int = 5

        /** A heart rate that was there and is gone for this many seconds is a strap that was lost. */
        public const val STRAP_LOST_AFTER_SECONDS: Int = 15

        private const val KEPT_EVENTS = 64
    }
}
