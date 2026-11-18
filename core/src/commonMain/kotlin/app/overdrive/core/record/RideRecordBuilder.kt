package app.overdrive.core.record

/**
 * Assembles a [RideRecord] from the samples of a ride, one at a time. It computes the laps and the summary
 * itself, from the samples alone, so a ride read back from disk after a crash goes through exactly the same
 * code as one recorded live (spec §4.2): feed the recovered header, samples and RR intervals to a builder and
 * call [build].
 *
 * Not thread-safe.
 */
public class RideRecordBuilder(
    private val header: RideHeader,
) {
    private val samples = mutableListOf<RideSample>()
    private val rrIntervals = mutableListOf<RrInterval>()
    private val laps = mutableListOf<Lap>()
    private val summary = SummaryAccumulator(header.ftp)
    private var openLap: LapAccumulator? = null

    /** The number of samples added so far, which is also the offset of the last one. */
    public val sampleCount: Int get() = samples.size

    /**
     * Adds the next sample.
     *
     * @throws IllegalArgumentException if its offset is not exactly one more than the previous one's: the
     * record has one sample for every second.
     */
    public fun addSample(sample: RideSample) {
        require(sample.timeOffsetSeconds == samples.size + 1) {
            "Expected the sample for second ${samples.size + 1}, got ${sample.timeOffsetSeconds}"
        }
        samples += sample
        summary.add(sample)
        val lap = openLap
        if (lap == null || lap.stepIndex != sample.stepIndex) {
            lap?.let { laps += it.close() }
            openLap = LapAccumulator(sample.stepIndex, startSeconds = sample.timeOffsetSeconds - 1)
        }
        checkNotNull(openLap).add(sample)
    }

    /** Adds RR intervals in the order they were received. */
    public fun addRrIntervals(intervals: List<RrInterval>) {
        rrIntervals += intervals
    }

    /**
     * The record as it stands, which may be called at any time and as often as needed: the last lap is
     * included as far as it goes. [endEpochMillis] overrides the header's end time.
     */
    public fun build(endEpochMillis: Long? = header.endEpochMillis): RideRecord =
        RideRecord(
            ride = header.copy(endEpochMillis = endEpochMillis),
            samples = samples.toList(),
            rrIntervals = rrIntervals.toList(),
            laps = laps + listOfNotNull(openLap?.close()),
            summary = summary.summary(),
        )

    public companion object {
        /**
         * Rebuilds the record of a ride that was recorded elsewhere, such as one recovered from disk after an
         * interruption. [samples] must be consecutive from second 1.
         */
        public fun recover(
            header: RideHeader,
            samples: List<RideSample>,
            rrIntervals: List<RrInterval> = emptyList(),
            endEpochMillis: Long? = header.endEpochMillis,
        ): RideRecord {
            val builder = RideRecordBuilder(header)
            samples.forEach(builder::addSample)
            builder.addRrIntervals(rrIntervals)
            return builder.build(endEpochMillis)
        }
    }
}

/** The running totals of the lap that is still open. */
private class LapAccumulator(
    val stepIndex: Int?,
    private val startSeconds: Int,
) {
    private var endSeconds = startSeconds
    private var powerCount = 0
    private var powerSum = 0L
    private var heartRateCount = 0
    private var heartRateSum = 0L

    fun add(sample: RideSample) {
        endSeconds = sample.timeOffsetSeconds
        if (sample.paused) return
        sample.power?.let {
            powerCount++
            powerSum += it
        }
        sample.heartRate?.let {
            heartRateCount++
            heartRateSum += it
        }
    }

    fun close(): Lap =
        Lap(
            stepIndex = stepIndex,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            averagePower = if (powerCount > 0) powerSum.toDouble() / powerCount else null,
            averageHeartRate = if (heartRateCount > 0) heartRateSum.toDouble() / heartRateCount else null,
        )
}
