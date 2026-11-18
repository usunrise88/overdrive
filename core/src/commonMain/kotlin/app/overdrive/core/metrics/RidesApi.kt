package app.overdrive.core.metrics

import app.overdrive.core.plan.PlanDates
import kotlinx.serialization.Serializable

// The rides of the sync API (spec §8.3, ADR 0022): `GET /v1/rides` and `GET /v1/rides/{id}`, as
// `contracts/schema/rides-response.schema.json` and `ride-detail-response.schema.json` say.

/** `GET /v1/rides`: the most rides in one page. */
public const val MAX_RIDES_LIMIT: Int = 100

/** `GET /v1/rides`: how many rides without `limit`. */
public const val DEFAULT_RIDES_LIMIT: Int = 20

/** The characters of `2026-11-12T18:40:00` in an instant. */
private const val SECONDS: Int = 19

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
            listOfNotNull(averagePower, normalizedPower, averageHeartRate, intensityFactor, trainingStressScore).all {
                it >=
                    0.0
            },
        ) {
            "Averages are not negative"
        }
        require((maxPower ?: 0) >= 0 && (maxHeartRate ?: 0) >= 0) { "Maxima are not negative" }
    }
}

/** One lap of a ride as the record holds it: the samples from [startSeconds] + 1 to [endSeconds] in one step. */
@Serializable
public data class StoredLap(
    val stepIndex: Int? = null,
    val startSeconds: Int,
    val endSeconds: Int,
    val averagePower: Double? = null,
    val averageHeartRate: Double? = null,
) {
    init {
        require(startSeconds >= 0 && endSeconds >= 1) { "Lap seconds are not negative" }
        require((stepIndex ?: 0) >= 0) { "A step index is not negative" }
        require((averagePower ?: 0.0) >= 0.0 && (averageHeartRate ?: 0.0) >= 0.0) { "Lap averages are not negative" }
    }
}

/**
 * One ride of the list: [workoutName] is the name of the workout it was played from as it was when stored, null for a
 * free ride.
 */
@Serializable
public data class RideListEntry(
    val id: String,
    val startTime: String,
    val endTime: String?,
    val date: String,
    val workoutId: String?,
    val workoutName: String?,
    val ftp: Int,
    val devices: List<String>,
    val summary: StoredRideSummary,
) {
    init {
        require(MetricsApiJson.isValidRideId(id)) { "Not a ride id: '$id'" }
        require(MetricsApiJson.isValidInstant(startTime)) { "startTime is not a UTC instant: '$startTime'" }
        require(
            endTime == null || MetricsApiJson.isValidInstant(endTime),
        ) { "endTime is not a UTC instant: '$endTime'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(ftp >= 1) { "FTP is at least 1 W" }
    }
}

/**
 * The answer to `GET /v1/rides`: the stored rides newest first. [from] and [to] are the local days asked for (null when
 * not given), [total] how many rides match, [offset] how many were skipped, [hasMore] whether more follow this page.
 */
@Serializable
public data class RidesResponse(
    val from: String?,
    val to: String?,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val rides: List<RideListEntry>,
) {
    init {
        val first = from?.let { requireNotNull(PlanDates.epochDay(it)) { "Not a calendar date: '$it'" } }
        val last = to?.let { requireNotNull(PlanDates.epochDay(it)) { "Not a calendar date: '$it'" } }
        if (first != null && last != null) require(first <= last) { "from $from is after to $to" }
        require(total >= 0 && offset >= 0) { "total and offset are not negative" }
        require(limit in 1..MAX_RIDES_LIMIT) { "limit is 1 to $MAX_RIDES_LIMIT" }
        require(rides.size <= limit) { "${rides.size} rides, the limit is $limit" }
        require(rides.size <= maxOf(total - offset, 0)) { "More rides than are left after the offset" }
        require(hasMore == (offset + rides.size < total)) { "hasMore must be ${offset + rides.size < total}" }
        require(rides.map { it.id }.toSet().size == rides.size) { "A ride is listed twice" }
        for (i in 1 until rides.size) {
            // To the second: the fraction is optional and a string comparison would misplace it.
            require(
                rides[i].startTime.take(SECONDS) <= rides[i - 1].startTime.take(SECONDS),
            ) { "Rides are newest first" }
        }
    }
}

/** Each point is the mean of [stepSeconds] seconds, a whole number, null where nothing was measured in the step. */
@Serializable
public data class RideSeriesBody(
    val stepSeconds: Int,
    val power: List<Int?>,
    val heartRate: List<Int?>,
    val cadence: List<Int?>,
    val targetPower: List<Int?>,
) {
    init {
        require(stepSeconds >= 1) { "stepSeconds is at least 1" }
        require(listOf(power, heartRate, cadence, targetPower).all { series -> series.all { it == null || it >= 0 } }) {
            "A series has no negative value"
        }
        require(listOf(heartRate, cadence, targetPower).all { it.size == power.size }) { "The series differ in length" }
    }
}

/**
 * The answer to `GET /v1/rides/{rideId}`: the ride with its summary and laps, without samples; [series] only when asked
 * for (`series=true`).
 */
@Serializable
public data class RideDetailResponse(
    val id: String,
    val startTime: String,
    val endTime: String?,
    val date: String,
    val workoutId: String?,
    val workoutName: String?,
    val ftp: Int,
    val devices: List<String>,
    val summary: StoredRideSummary,
    val laps: List<StoredLap>,
    val series: RideSeriesBody? = null,
) {
    init {
        require(MetricsApiJson.isValidRideId(id)) { "Not a ride id: '$id'" }
        require(MetricsApiJson.isValidInstant(startTime)) { "startTime is not a UTC instant: '$startTime'" }
        require(
            endTime == null || MetricsApiJson.isValidInstant(endTime),
        ) { "endTime is not a UTC instant: '$endTime'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(ftp >= 1) { "FTP is at least 1 W" }
        if (laps.isNotEmpty()) {
            require(laps.first().startSeconds == 0) { "The first lap starts at 0" }
            for ((index, lap) in laps.withIndex()) {
                require(lap.endSeconds > lap.startSeconds) { "Lap $index ends after it starts" }
                if (index > 0) require(laps[index - 1].endSeconds == lap.startSeconds) { "Laps follow each other" }
            }
            require(laps.last().endSeconds == summary.durationSeconds) { "The last lap ends with the ride" }
        }
        if (series != null) {
            val expected = (summary.durationSeconds + series.stepSeconds - 1) / series.stepSeconds
            require(series.power.size == expected) { "The series has ${series.power.size} points, expected $expected" }
        }
    }
}
