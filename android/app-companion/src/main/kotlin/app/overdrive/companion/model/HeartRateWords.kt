package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.profile.Profile

/** One stepper of Settings' Heart rate group: its text, what its buttons can do, the note under it and the estimate to take. */
@Immutable
data class HeartRateRowUi(
    /** "186 bpm", or "Not set" while the profile has none. */
    val valueText: String,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val note: String,
    /** The value of the **Use estimate** action, or null when there is none or it is what is set already. */
    val estimate: Int?,
)

@Immutable
data class HeartRateUi(
    val maximum: HeartRateRowUi,
    val threshold: HeartRateRowUi,
)

/**
 * The maximum and threshold heart rate of Settings (design/companion.md §4.9, spec §6.7, ADR 0023): steppers of 1 bpm inside the
 * profile's own ranges (maximum 120 to 220, threshold 100 to 210, the threshold below the maximum), the service's estimates from
 * the rides shown beside them and never edited. A change goes to the service with `PATCH /v1/profile`. Pure.
 */
object HeartRateWords {
    const val MAX_LOW = 120
    const val MAX_HIGH = 220
    const val THRESHOLD_LOW = 100
    const val THRESHOLD_HIGH = 210

    /** Where a first tap starts when the profile has no value and the rides suggest none. */
    private const val START_MAX = 180
    private const val START_THRESHOLD = 160

    fun of(profile: Profile): HeartRateUi {
        val max = profile.maxHeartRate
        val threshold = profile.thresholdHeartRate
        return HeartRateUi(
            maximum =
                HeartRateRowUi(
                    valueText = max?.let { "$it bpm" } ?: "Not set",
                    canDecrease = max == null || max > lowestMax(threshold),
                    canIncrease = max == null || max < MAX_HIGH,
                    note =
                        "Highest you have reached." +
                            (profile.estimatedMaxHeartRate?.let { " Your rides show $it." } ?: ""),
                    estimate = profile.estimatedMaxHeartRate?.takeIf { it != max && fitsMax(it, threshold) },
                ),
            threshold =
                HeartRateRowUi(
                    valueText = threshold?.let { "$it bpm" } ?: "Not set",
                    canDecrease = threshold == null || threshold > THRESHOLD_LOW,
                    canIncrease = threshold == null || threshold < highestThreshold(max),
                    note =
                        "Used to count the load of watch activities." +
                            (profile.estimatedThresholdHeartRate?.let { " Your rides suggest $it." } ?: ""),
                    estimate =
                        profile.estimatedThresholdHeartRate?.takeIf {
                            it != threshold &&
                                fitsThreshold(
                                    it,
                                    max,
                                )
                        },
                ),
        )
    }

    /** The maximum after a step of [by] (±1), or null when the profile would not hold it. A first step starts at the estimate. */
    fun stepMax(
        profile: Profile,
        by: Int,
    ): Int? {
        val current =
            profile.maxHeartRate
                ?: return (profile.estimatedMaxHeartRate ?: START_MAX).takeIf {
                    fitsMax(
                        it,
                        profile.thresholdHeartRate,
                    )
                }
        return (current + by).takeIf { fitsMax(it, profile.thresholdHeartRate) }
    }

    fun stepThreshold(
        profile: Profile,
        by: Int,
    ): Int? {
        val current =
            profile.thresholdHeartRate
                ?: return (profile.estimatedThresholdHeartRate ?: START_THRESHOLD).takeIf {
                    fitsThreshold(
                        it,
                        profile.maxHeartRate,
                    )
                }
        return (current + by).takeIf { fitsThreshold(it, profile.maxHeartRate) }
    }

    private fun lowestMax(threshold: Int?): Int = maxOf(MAX_LOW, (threshold ?: 0) + 1)

    private fun highestThreshold(max: Int?): Int = minOf(THRESHOLD_HIGH, (max ?: Int.MAX_VALUE) - 1)

    private fun fitsMax(
        value: Int,
        threshold: Int?,
    ): Boolean = value in lowestMax(threshold)..MAX_HIGH

    private fun fitsThreshold(
        value: Int,
        max: Int?,
    ): Boolean = value in THRESHOLD_LOW..highestThreshold(max)
}
