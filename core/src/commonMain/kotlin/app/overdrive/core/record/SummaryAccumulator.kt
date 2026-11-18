package app.overdrive.core.record

import kotlin.math.sqrt

/**
 * Computes a [RideSummary] one sample at a time, without keeping the samples, so a ride of any length costs a
 * fixed amount of memory and a recovered ride can be fed through the same code (spec §4.2 crash safety).
 * See [RideSummary] for the definitions.
 */
internal class SummaryAccumulator(
    private val ftp: Int,
) {
    private var seconds = 0
    private var movingSeconds = 0
    private var distanceMeters = 0.0

    private var powerCount = 0
    private var powerSum = 0L
    private var maxPower: Int? = null

    // Normalised power: a ring buffer of the last 30 powers with its running sum, and the sum of the fourth powers
    // of every complete window's average.
    private val window = IntArray(NP_WINDOW_SECONDS)
    private var windowSum = 0L
    private var fourthPowerSum = 0.0
    private var windowCount = 0

    private var heartRateCount = 0
    private var heartRateSum = 0L
    private var maxHeartRate: Int? = null

    fun add(sample: RideSample) {
        seconds = sample.timeOffsetSeconds
        distanceMeters = sample.distanceMeters
        if (sample.paused) return
        movingSeconds++
        sample.power?.let(::addPower)
        sample.heartRate?.let(::addHeartRate)
    }

    private fun addPower(power: Int) {
        val slot = powerCount % NP_WINDOW_SECONDS
        if (powerCount >= NP_WINDOW_SECONDS) windowSum -= window[slot]
        window[slot] = power
        windowSum += power
        powerCount++
        powerSum += power
        if (maxPower?.let { power > it } != false) maxPower = power
        if (powerCount >= NP_WINDOW_SECONDS) {
            val average = windowSum.toDouble() / NP_WINDOW_SECONDS
            val squared = average * average
            fourthPowerSum += squared * squared
            windowCount++
        }
    }

    private fun addHeartRate(heartRate: Int) {
        heartRateCount++
        heartRateSum += heartRate
        if (maxHeartRate?.let { heartRate > it } != false) maxHeartRate = heartRate
    }

    fun summary(): RideSummary {
        val normalized = if (windowCount > 0) sqrt(sqrt(fourthPowerSum / windowCount)) else null
        val intensity = normalized?.let { it / ftp }
        return RideSummary(
            durationSeconds = seconds,
            movingSeconds = movingSeconds,
            distanceMeters = distanceMeters,
            averagePower = if (powerCount > 0) powerSum.toDouble() / powerCount else null,
            normalizedPower = normalized,
            maxPower = maxPower,
            averageHeartRate = if (heartRateCount > 0) heartRateSum.toDouble() / heartRateCount else null,
            maxHeartRate = maxHeartRate,
            workKilojoules = powerSum / JOULES_PER_KILOJOULE,
            intensityFactor = intensity,
            trainingStressScore =
                if (normalized != null && intensity != null) {
                    powerCount * normalized * intensity / (ftp * SECONDS_PER_HOUR) * PERCENT
                } else {
                    null
                },
        )
    }

    private companion object {
        const val NP_WINDOW_SECONDS = 30
        const val JOULES_PER_KILOJOULE = 1000.0
        const val SECONDS_PER_HOUR = 3600.0
        const val PERCENT = 100.0
    }
}
