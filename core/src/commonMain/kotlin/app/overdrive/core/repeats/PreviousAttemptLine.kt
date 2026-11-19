package app.overdrive.core.repeats

import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The line the overlay shows against the previous attempt of the same session (spec §11.7, docs/adr/0034): one plain sentence, or none.
 * [differenceBpm] is this ride's heart rate at the same power minus last time's, rounded to whole bpm (negative: lower, which is better,
 * but the line never says "fitter": one day's heart rate also moves with heat, sleep and caffeine). [text] is the sentence.
 */
public data class PreviousLine(
    val text: String,
    val differenceBpm: Int,
)

/**
 * A live comparison of the ride under way with an earlier one, as the overlay shows it. **The hook for routes** (spec §11.4, stage
 * v0.4.0): a ride on a route is compared by *time gap* at the same distance, which is another implementation of this interface that
 * reads the distance from the snapshot; the overlay and the host only know [line]. Implementations are single-threaded: the host feeds
 * them from the thread that owns the ride.
 */
public interface LiveComparison {
    /** Takes in the latest snapshot of the ride; idempotent for a second it has already seen. */
    public fun onSnapshot(snapshot: RideSnapshot)

    /** The line to show now, or null: nothing to compare, not enough data yet, or not a step the comparison speaks in. */
    public fun line(): PreviousLine?
}

/**
 * The comparison for an **ERG workout**, where power is fixed and so the heart rate at the same power is the figure (ADR 0026): this
 * ride's mean heart rate over the seconds of the current step that count, against the previous attempt's figure for the same step.
 *
 * **The seconds that count** are the rules of the service's figure (`repeat-detail-response.schema.json`, ADR 0026), so that at the end
 * of the step this line says what the companion's page will say: the ride is not paused, the power is **within 3%** of the step's
 * target (its fraction of FTP times the FTP now), there is a heart rate, and it is **after the first 60 s of each stretch** of the step (a
 * pause ends a stretch; the minute after a resume is left out too). Only a **steady ERG step** is judged: a ramp, a free-ride step and a
 * heart-rate step have no fixed power and no line.
 *
 * **When it speaks.** Only when the previous attempt has a figure for this step, **at the same power** (its target within 3% of this
 * one's: an attempt at another FTP is not comparable, as on the service), and when [minimumCountedSeconds] of this step have counted:
 * the repeat rule's **120 s**, or **half of the counted length the previous attempt had** (the step's length less the first 60 s)
 * when that is more. The second is not in the service's rule: a running mean over the first minutes of a long step sits below the whole
 * step's mean by cardiac drift, and a line that said "3 below" for that reason would be wrong in the rider's favour. It says "same as
 * last time" when the rounded difference is at most [SAME_WITHIN_BPM] bpm.
 *
 * The previous attempt arrives whenever the fetch finishes ([setPrevious]); the counting goes on without it, so a late answer speaks at once
 * if enough has counted. It never throws and never waits.
 */
public class PreviousAttemptComparison(
    previous: RepeatPreviousAttempt? = null,
) : LiveComparison {
    private var figures: Map<Int, Figure> = figuresOf(previous)

    // The step being counted: its index in the flattened workout, the power it asks for at the FTP of this ride, and what has counted.
    private var stepIndex = -1
    private var stepTargetWatts: Double? = null
    private var stepDurationSeconds = 0
    private var counted = 0
    private var heartRateSum = 0L

    // Seconds of the current stretch of the step (a pause starts a new one), and the last second seen, so that the several
    // snapshots a second counts once.
    private var stretchSeconds = 0
    private var lastElapsed = -1

    private class Figure(
        val targetWatts: Double,
        val averageHeartRate: Double,
    )

    /** Whether there is a previous attempt with any figure to compare with. */
    public val hasPrevious: Boolean get() = figures.isNotEmpty()

    /** Sets the previous attempt (null: there is none, or it could not be had). */
    public fun setPrevious(previous: RepeatPreviousAttempt?) {
        figures = figuresOf(previous)
    }

    override fun onSnapshot(snapshot: RideSnapshot) {
        when (snapshot.state) {
            // A pause ends the stretch: the minute after the resume is not counted (ADR 0026).
            RideState.PAUSED -> {
                stretchSeconds = 0
                return
            }

            RideState.RIDING -> {
                Unit
            }

            RideState.IDLE, RideState.READY, RideState.FINISHED -> {
                return
            }
        }
        val progress = snapshot.workout
        if (progress == null) {
            forgetStep()
            return
        }
        if (progress.stepIndex != stepIndex) startStep(progress.stepIndex, progress.step, snapshot.ftp)
        if (snapshot.elapsedSeconds == lastElapsed) return
        val gap = if (lastElapsed < 0) 1 else (snapshot.elapsedSeconds - lastElapsed).coerceAtLeast(1)
        lastElapsed = snapshot.elapsedSeconds
        stretchSeconds += gap
        val target = stepTargetWatts ?: return
        if (stretchSeconds <= SKIP_SECONDS) return
        val power = snapshot.power ?: return
        val heartRate = snapshot.heartRate ?: return
        if (abs(power - target) <= target * POWER_TOLERANCE_PERCENT / PERCENT) {
            counted++
            heartRateSum += heartRate
        }
    }

    override fun line(): PreviousLine? {
        val figure = figures[stepIndex + 1] ?: return null
        val target = stepTargetWatts ?: return null
        if (abs(figure.targetWatts - target) > target * POWER_TOLERANCE_PERCENT / PERCENT) return null
        if (counted < minimumCountedSeconds(stepDurationSeconds)) return null
        val difference = (heartRateSum.toDouble() / counted - figure.averageHeartRate).roundToInt()
        val words =
            when {
                abs(difference) <= SAME_WITHIN_BPM -> "Heart rate same as last time"
                difference < 0 -> "Heart rate ${-difference} below last time"
                else -> "Heart rate $difference above last time"
            }
        return PreviousLine(words, difference)
    }

    private fun startStep(
        index: Int,
        step: FlatStep,
        ftp: Int,
    ) {
        stepIndex = index
        counted = 0
        heartRateSum = 0
        stretchSeconds = 0
        stepDurationSeconds = step.durationSeconds
        val from = step.targetFrom
        // Only a steady ERG step has a fixed power; the two ends of a ramp differ, a free step and a heart-rate step have none.
        stepTargetWatts =
            if (step.mode == TrainerMode.ERG && step.heartRate == null && from != null && from == step.targetTo) {
                from * ftp
            } else {
                null
            }
    }

    private fun forgetStep() {
        stepIndex = -1
        stepTargetWatts = null
        counted = 0
        heartRateSum = 0
        stretchSeconds = 0
    }

    public companion object {
        /** A second counts when the power is within this percent of the step's target (ADR 0026). */
        public const val POWER_TOLERANCE_PERCENT: Double = 3.0

        /** The first seconds of each stretch of a step are left out, as the heart rate takes that long to settle (ADR 0026). */
        public const val SKIP_SECONDS: Int = 60

        /** The service's figure needs this many counted seconds (ADR 0026). */
        public const val MIN_COUNTED_SECONDS: Int = 120

        /** The line also waits for this share of the counted length the previous attempt had, against cardiac drift (docs/adr/0034). */
        public const val MIN_SHARE_OF_PREVIOUS: Double = 0.5

        /** A rounded difference of this many bpm or less is "same as last time". */
        public const val SAME_WITHIN_BPM: Int = 1

        private const val PERCENT = 100.0

        /** The counted seconds a step of [stepDurationSeconds] needs before the line speaks. */
        public fun minimumCountedSeconds(stepDurationSeconds: Int): Int {
            val previousCounted = (stepDurationSeconds - SKIP_SECONDS).coerceAtLeast(0)
            return maxOf(MIN_COUNTED_SECONDS, ceil(MIN_SHARE_OF_PREVIOUS * previousCounted).toInt())
        }

        private fun figuresOf(previous: RepeatPreviousAttempt?): Map<Int, Figure> =
            previous
                ?.steps
                .orEmpty()
                .mapNotNull { step ->
                    val target = step.targetWatts
                    val heartRate = step.averageHeartRate
                    if (target == null || heartRate == null) null else step.step to Figure(target, heartRate)
                }.toMap()
    }
}
