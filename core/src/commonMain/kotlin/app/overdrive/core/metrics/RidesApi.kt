package app.overdrive.core.metrics

import app.overdrive.core.plan.PlanDates
import app.overdrive.core.workout.Workout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The rides of the sync API (spec §6.7, §8.3, ADR 0022): `GET /v1/rides` and `GET /v1/rides/{id}`, as
// `contracts/schema/rides-response.schema.json` and `ride-detail-response.schema.json` say.

/** `GET /v1/rides`: the most items in one page. */
public const val MAX_RIDES_LIMIT: Int = 100

/** `GET /v1/rides`: how many items without `limit`. */
public const val DEFAULT_RIDES_LIMIT: Int = 50

/** The characters of `2026-11-12T18:40:00` in an instant. */
private const val SECONDS: Int = 19

/** The power zones of a ride's time in zones. */
private const val ZONES: Int = 7

/** What an item of the list is: a ride recorded on the tablet, or an activity from the watch (spec §6.7). */
@Serializable
public enum class RideKind {
    @SerialName("ride")
    RIDE,

    @SerialName("activity")
    ACTIVITY,
}

/**
 * One item of the list, newest first. A ride has an exact [load] and an [averagePower] and no [sport]; an activity has
 * a [load] from heart rate (approximate), its [sport] and an [averageHeartRate]. [name] is the workout a ride was
 * played from, or `Free ride`; [date] is the local day of [start].
 */
@Serializable
public data class RideItem(
    val id: String,
    val kind: RideKind,
    val name: String,
    val start: String,
    val date: String,
    val durationSeconds: Int,
    val load: Double?,
    val loadApproximate: Boolean,
    val sport: String?,
    val averagePower: Double?,
    val averageHeartRate: Double?,
) {
    init {
        require(MetricsApiJson.isValidRideId(id)) { "Not a ride id: '$id'" }
        require(name.isNotEmpty()) { "An item has a name" }
        require(MetricsApiJson.isValidInstant(start)) { "start is not a UTC instant: '$start'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(durationSeconds >= 0) { "Duration is not negative" }
        require((load ?: 0.0) >= 0.0 && (averagePower ?: 0.0) >= 0.0 && (averageHeartRate ?: 0.0) >= 0.0) {
            "Load and averages are not negative"
        }
        require(sport == null || sport.isNotEmpty()) { "A sport has a name" }
        when (kind) {
            RideKind.RIDE -> {
                require(sport == null && averageHeartRate == null && !loadApproximate) {
                    "A ride has no sport and no averageHeartRate, and an exact load"
                }
            }

            RideKind.ACTIVITY -> {
                require(averagePower == null && loadApproximate) {
                    "An activity has no averagePower and an approximate load"
                }
            }
        }
    }
}

/**
 * The answer to `GET /v1/rides?before&limit`: what the rider did, newest first, one page at a time, computed at
 * [generatedAt] ([stale]: a source of items failed). [before] is the cursor as asked; [hasMore] says whether older
 * items exist, and then [nextBefore] is the start of the last item, to ask for the next page.
 */
@Serializable
public data class RidesResponse(
    val generatedAt: String,
    val stale: Boolean,
    val before: String?,
    val limit: Int,
    val hasMore: Boolean,
    val nextBefore: String?,
    val items: List<RideItem>,
) {
    init {
        require(MetricsApiJson.isValidInstant(generatedAt)) { "generatedAt is not a UTC instant: '$generatedAt'" }
        require(before == null || MetricsApiJson.isValidInstant(before)) { "before is not a UTC instant: '$before'" }
        require(nextBefore == null || MetricsApiJson.isValidInstant(nextBefore)) { "nextBefore is not an instant" }
        require(limit in 1..MAX_RIDES_LIMIT) { "limit is 1 to $MAX_RIDES_LIMIT" }
        require(items.size <= limit) { "${items.size} items, the limit is $limit" }
        if (hasMore) {
            require(items.isNotEmpty() && nextBefore == items.last().start) {
                "nextBefore must be the start of the last item"
            }
        } else {
            require(nextBefore == null) { "nextBefore must be null when hasMore is false" }
        }
        require(items.map { it.kind to it.id }.toSet().size == items.size) { "An item is listed twice" }
        for (i in 1 until items.size) {
            // To the second: the fraction is optional and a string comparison would misplace it.
            require(items[i].start.take(SECONDS) <= items[i - 1].start.take(SECONDS)) { "Items are newest first" }
        }
        if (before != null) {
            for (item in items) {
                require(item.start.take(SECONDS) < before.take(SECONDS)) { "${item.id} does not start before $before" }
            }
        }
    }
}

/**
 * The summary the tablet recorded when a ride ended, as the ride record holds it: the optional numbers are absent when
 * there was nothing to average (no power, no heart rate).
 */
@Serializable
public data class StoredRideSummary(
    val durationSeconds: Int,
    val movingSeconds: Int,
    val distanceMeters: Double,
    val averagePower: Double? = null,
    val normalizedPower: Double? = null,
    val maxPower: Int? = null,
    val averageHeartRate: Double? = null,
    val maxHeartRate: Int? = null,
    val workKilojoules: Double,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
) {
    init {
        require(durationSeconds >= 0 && movingSeconds >= 0) { "Seconds are not negative" }
        require(distanceMeters >= 0.0 && workKilojoules >= 0.0) { "Distance and work are not negative" }
        require(
            listOfNotNull(averagePower, normalizedPower, averageHeartRate, intensityFactor, trainingStressScore)
                .all { it >= 0.0 },
        ) { "Averages are not negative" }
        require((maxPower ?: 0) >= 0 && (maxHeartRate ?: 0) >= 0) { "Maxima are not negative" }
    }
}

/** The kind of a workout step. */
@Serializable
public enum class LapKind {
    @SerialName("steady")
    STEADY,

    @SerialName("ramp")
    RAMP,

    @SerialName("free")
    FREE,

    /** A heart-rate hold step (spec §11.2): no fixed power, so no [DetailLap.targetPower]. */
    @SerialName("hr")
    HR,
}

/**
 * One lap of a ride: the samples from [startSeconds] + 1 to [endSeconds]. With a workout, [label] and [kind] are the
 * step's and [targetPower] is its target in whole watts for a steady step (null for a ramp, a free step, no plan).
 */
@Serializable
public data class DetailLap(
    val stepIndex: Int?,
    val label: String?,
    val type: LapKind?,
    val startSeconds: Int,
    val endSeconds: Int,
    val averagePower: Double?,
    val averageHeartRate: Double?,
    val targetPower: Int?,
) {
    init {
        require(startSeconds >= 0 && endSeconds > startSeconds) { "A lap ends after it starts" }
        require((stepIndex ?: 0) >= 0 && (targetPower ?: 0) >= 0) { "Step and target are not negative" }
        require((averagePower ?: 0.0) >= 0.0 && (averageHeartRate ?: 0.0) >= 0.0) { "Lap averages are not negative" }
        require(label == null || label.isNotEmpty()) { "A label is not empty" }
    }
}

/** The lowest and highest average power the ride held in the steps of the workout's efforts, in whole watts. */
@Serializable
public data class EffortRange(
    val minWatts: Int,
    val maxWatts: Int,
) {
    init {
        require(minWatts in 0..maxWatts) { "The range is ordered" }
    }
}

/**
 * The workout a ride followed, as it was when the ride was stored, and how it went: [stepsRidden] of [stepsPlanned]
 * (repeats expanded) were ridden for their whole time; [effortTarget] is the planned average power of the effort steps
 * and [effortRange] what the ride held in them (both null when the workout has no such steps).
 */
@Serializable
public data class RidePlan(
    val workout: Workout,
    val stepsRidden: Int,
    val stepsPlanned: Int,
    val effortRange: EffortRange?,
    val effortTarget: Double?,
) {
    init {
        require(stepsPlanned >= 0 && stepsRidden in 0..stepsPlanned) { "stepsRidden is within stepsPlanned" }
        require((effortRange == null) == (effortTarget == null)) { "effortRange is null exactly when effortTarget is" }
        require((effortTarget ?: 0.0) >= 0.0) { "The effort target is not negative" }
    }
}

/** The lowest W′ balance of the ride in kilojoules (below 0: W′ was exceeded) and the second it was reached. */
@Serializable
public data class WPrimeLowest(
    val kilojoules: Double,
    val atSec: Int,
) {
    init {
        require(atSec >= 0) { "atSec is not negative" }
    }
}

/** One point of the ride's series: the means over the step ending at second [t], and the W′ balance at [t] in kJ. */
@Serializable
public data class DetailPoint(
    val t: Int,
    val power: Int?,
    val heartRate: Int?,
    val wBal: Double,
) {
    init {
        require(t >= 1) { "t is at least 1" }
        require((power ?: 0) >= 0 && (heartRate ?: 0) >= 0) { "Readings are not negative" }
    }
}

/**
 * The answer to `GET /v1/rides/{rideId}[?series=true]`: one ride as the companion's ride screen shows it, computed at
 * [generatedAt] ([stale]: the workout could not be looked up because a source failed). [cp] and [wPrime] are what the
 * W′ balance used (watts, kilojoules); [secondsInZones] has the seven power zones; [series] only when asked for.
 */
@Serializable
public data class RideDetailResponse(
    val generatedAt: String,
    val stale: Boolean,
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String?,
    val date: String,
    val workoutId: String?,
    val devices: List<String>,
    val ftp: Int,
    val cp: Double,
    val wPrime: Double,
    val wPrimeLowest: WPrimeLowest,
    val summary: StoredRideSummary,
    val secondsInZones: List<Int>,
    val laps: List<DetailLap>,
    val plan: RidePlan?,
    val series: List<DetailPoint>? = null,
) {
    init {
        require(MetricsApiJson.isValidInstant(generatedAt)) { "generatedAt is not a UTC instant: '$generatedAt'" }
        require(MetricsApiJson.isValidRideId(id)) { "Not a ride id: '$id'" }
        require(name.isNotEmpty()) { "A ride has a name" }
        require(MetricsApiJson.isValidInstant(startTime)) { "startTime is not a UTC instant: '$startTime'" }
        require(endTime == null || MetricsApiJson.isValidInstant(endTime)) { "endTime is not a UTC instant" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(ftp >= 1 && cp > 0.0 && wPrime > 0.0) { "FTP, CP and W′ are positive" }
        require(secondsInZones.size == ZONES && secondsInZones.all { it >= 0 }) { "Seven zones, no negative time" }
        if (laps.isNotEmpty()) {
            require(laps.first().startSeconds == 0) { "The first lap starts at 0" }
            for (i in 1 until laps.size) {
                require(laps[i - 1].endSeconds == laps[i].startSeconds) { "Laps follow each other" }
            }
            require(laps.last().endSeconds == summary.durationSeconds) { "The last lap ends with the ride" }
        }
        var previous = 0
        for (point in series.orEmpty()) {
            require(point.t > previous && point.t <= summary.durationSeconds) {
                "Series times ascend and stay within the ride"
            }
            previous = point.t
        }
    }
}
