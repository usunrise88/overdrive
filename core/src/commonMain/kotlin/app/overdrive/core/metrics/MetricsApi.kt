package app.overdrive.core.metrics

import app.overdrive.core.plan.PlanDates
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

// The training metrics of the sync API (spec §6.3, §8.3, ADR 0022): `GET /v1/metrics` and
// `GET /v1/rides/{id}/metrics`, as `contracts/schema/metrics-response.schema.json` and
// `ride-metrics-response.schema.json` say. Every number is computed by the service.

/**
 * How sure the service is of an FTP suggestion: low is two rides with a comparable effort, medium three or four, high
 * five or more.
 */
@Serializable
public enum class Confidence {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,
}

/** The state of a suggestion in an answer: only pending ones are shown; accepting or dismissing is another request. */
@Serializable
public enum class SuggestionStatus {
    @SerialName("pending")
    PENDING,
}

/**
 * One local day: [load] is the training stress of the day ([loadCycling] from rides plus [loadOther] from other
 * activities), [ctl] the fitness and [atl] the fatigue at the end of it (exponentially weighted averages of the daily
 * load over 42 and 7 days), [tsb] the form, `ctl - atl`. Numbers have one decimal.
 */
@Serializable
public data class MetricsDay(
    val date: String,
    val load: Double,
    val loadCycling: Double,
    val loadOther: Double,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
) {
    init {
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(load >= 0.0 && loadCycling >= 0.0 && loadOther >= 0.0) { "$date: a load is not negative" }
        require(ctl >= 0.0 && atl >= 0.0) { "$date: fitness and fatigue are not negative" }
        require(abs(tsb - (ctl - atl)) <= ROUNDING) { "$date: form $tsb is not fitness $ctl minus fatigue $atl" }
        require(abs(load - (loadCycling + loadOther)) <= ROUNDING) { "$date: load $load is not its two parts" }
    }
}

/** One ISO week: the Monday it starts, its load, the number of rides and their moving time in seconds. */
@Serializable
public data class MetricsWeek(
    val weekStart: String,
    val load: Double,
    val rides: Int,
    val durationSec: Int,
) {
    init {
        val day = requireNotNull(PlanDates.epochDay(weekStart)) { "Not a calendar date: '$weekStart'" }
        require(MetricsDates.isMonday(day)) { "$weekStart is not a Monday" }
        require(load >= 0.0 && rides >= 0 && durationSec >= 0) { "$weekStart: counts are not negative" }
    }
}

/** The best average power over [durationSec] seconds, in watts, with the ride it was ridden in and that ride's day. */
@Serializable
public data class CurvePoint(
    val durationSec: Int,
    val power: Double,
    val rideId: String,
    val date: String,
) {
    init {
        require(durationSec in MetricsApiJson.POWER_CURVE_DURATIONS) { "Not a power curve duration: $durationSec s" }
        require(power >= 0.0) { "Power is not negative" }
        require(MetricsApiJson.isValidRideId(rideId)) { "Not a ride id: '$rideId'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
    }
}

/** The effort an FTP suggestion comes from. */
@Serializable
public data class FtpBasis(
    val durationSec: Int,
    val power: Double,
    val rideId: String,
    val date: String,
) {
    init {
        require(durationSec in MetricsApiJson.POWER_CURVE_DURATIONS) { "Not a power curve duration: $durationSec s" }
        require(power >= 0.0) { "Power is not negative" }
        require(MetricsApiJson.isValidRideId(rideId)) { "Not a ride id: '$rideId'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
    }
}

/**
 * An FTP the service estimates from recent best efforts; only a suggestion: the profile changes when the rider accepts
 * it. [differencePercent] is [value] against [currentFtp] (null when there was none).
 */
@Serializable
public data class FtpSuggestion(
    val id: Int,
    val value: Int,
    val basis: FtpBasis,
    val confidence: Confidence,
    val currentFtp: Int?,
    val differencePercent: Double?,
    val status: SuggestionStatus,
    val createdAt: String,
) {
    init {
        require(id >= 1) { "A suggestion id is positive" }
        require(value in MetricsApiJson.MIN_FTP..MetricsApiJson.MAX_FTP) { "Not an FTP: $value W" }
        require(MetricsApiJson.isValidInstant(createdAt)) { "createdAt is not a UTC instant: '$createdAt'" }
        if (currentFtp == null) {
            require(differencePercent == null) { "differencePercent needs a currentFtp" }
        } else {
            require(differencePercent != null) { "differencePercent is missing" }
            require(
                abs(differencePercent - (value - currentFtp).toDouble() / currentFtp * PERCENT) <= PERCENT_ROUNDING,
            ) {
                "differencePercent $differencePercent is not $value against $currentFtp"
            }
        }
    }
}

/** The day the profile's FTP became [ftp] (null: it was cleared). */
@Serializable
public data class FtpHistoryEntry(
    val date: String,
    val ftp: Int?,
) {
    init {
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(ftp == null || ftp in MetricsApiJson.MIN_FTP..MetricsApiJson.MAX_FTP) { "Not an FTP: $ftp W" }
    }
}

/**
 * The aerobic drift of a steady ride: how much the efficiency factor (power over heart rate) fell from the first half
 * to the second.
 */
@Serializable
public data class DriftPoint(
    val date: String,
    val rideId: String,
    val driftPercent: Double,
    val efFirst: Double,
    val efSecond: Double,
) {
    init {
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(MetricsApiJson.isValidRideId(rideId)) { "Not a ride id: '$rideId'" }
        require(efFirst > 0.0 && efSecond > 0.0) { "An efficiency factor is positive" }
    }
}

/** The best efforts of the range: [windowDays] long, ascending durations with a power that does not rise. */
@Serializable
public data class PowerCurveBest(
    val windowDays: Int,
    val best: List<CurvePoint>,
) {
    init {
        require(
            windowDays in 1..MetricsApiJson.MAX_RANGE_DAYS,
        ) { "windowDays is 1 to ${MetricsApiJson.MAX_RANGE_DAYS}" }
        for (i in 1 until best.size) {
            require(best[i].durationSec > best[i - 1].durationSec) { "Curve durations must ascend" }
            require(best[i].power <= best[i - 1].power) {
                "The ${best[i].durationSec} s power is above the ${best[i - 1].durationSec} s power"
            }
        }
    }
}

/**
 * The FTP: the profile's [current], the pending [suggestion] (or in [suggestionNote] why there is none) and its
 * [history].
 */
@Serializable
public data class FtpInfo(
    val current: Int?,
    val suggestion: FtpSuggestion?,
    val suggestionNote: String?,
    val history: List<FtpHistoryEntry>,
) {
    init {
        require(
            current == null || current in MetricsApiJson.MIN_FTP..MetricsApiJson.MAX_FTP,
        ) { "Not an FTP: $current W" }
        if (suggestion != null) {
            require(suggestionNote == null) { "suggestionNote must be null while a suggestion is pending" }
        } else {
            require(!suggestionNote.isNullOrEmpty()) { "suggestionNote must say why there is no suggestion" }
        }
        for (i in 1 until history.size) {
            require(
                checkNotNull(PlanDates.epochDay(history[i].date)) >=
                    checkNotNull(PlanDates.epochDay(history[i - 1].date)),
            ) { "FTP history must not go back in time" }
        }
    }
}

/**
 * The answer to `GET /v1/metrics?from&to` (spec §6.3, §8.3): the training metrics of the local days [from] to [to]
 * inclusive, at most [MetricsApiJson.MAX_RANGE_DAYS] days. [daily] has one entry per day; [weekly] one per ISO week
 * from the week of [from] to the week of [to]; [firstLoadDate] is the first day with any load (fitness, fatigue and
 * form are 0 before it), null when there is none.
 */
@Serializable
public data class MetricsResponse(
    val from: String,
    val to: String,
    val generatedAt: String,
    val firstLoadDate: String?,
    val daily: List<MetricsDay>,
    val weekly: List<MetricsWeek>,
    val powerCurve: PowerCurveBest,
    val ftp: FtpInfo,
    val drift: List<DriftPoint>,
) {
    init {
        val first = PlanDates.epochDay(from)
        val last = PlanDates.epochDay(to)
        require(first != null && last != null) { "from and to must be real calendar dates: '$from', '$to'" }
        require(last >= first) { "from $from is after to $to" }
        val days = last - first + 1
        require(days <= MetricsApiJson.MAX_RANGE_DAYS) {
            "The range spans $days days, at most ${MetricsApiJson.MAX_RANGE_DAYS} are served"
        }
        require(MetricsApiJson.isValidInstant(generatedAt)) { "generatedAt is not a UTC instant: '$generatedAt'" }
        require(firstLoadDate == null || PlanDates.epochDay(firstLoadDate) != null) { "firstLoadDate is not a date" }
        require(daily.size.toLong() == days) { "daily has ${daily.size} entries, the range has $days days" }
        for ((index, day) in daily.withIndex()) {
            require(PlanDates.epochDay(day.date) == first + index) { "daily[$index] is ${day.date}, out of order" }
        }
        val firstWeek = MetricsDates.mondayOf(first)
        val weeks = (MetricsDates.mondayOf(last) - firstWeek) / DAYS_PER_WEEK + 1
        require(weekly.size.toLong() == weeks) { "weekly has ${weekly.size} entries, the range touches $weeks weeks" }
        for ((index, week) in weekly.withIndex()) {
            require(PlanDates.epochDay(week.weekStart) == firstWeek + DAYS_PER_WEEK * index) {
                "weekly[$index] is ${week.weekStart}, not week ${index + 1} of the range"
            }
        }
        require(
            powerCurve.windowDays.toLong() == days,
        ) { "windowDays is ${powerCurve.windowDays}, the range is $days days" }
        for (point in powerCurve.best) {
            require(checkNotNull(PlanDates.epochDay(point.date)) in first..last) {
                "The ${point.durationSec} s effort on ${point.date} is outside $from to $to"
            }
        }
        var previous = Long.MIN_VALUE
        for (point in drift) {
            val day = checkNotNull(PlanDates.epochDay(point.date))
            require(day in first..last) { "Drift on ${point.date} is outside $from to $to" }
            require(day >= previous) { "Drift entries must not go back in time" }
            previous = day
        }
    }
}

/** The aerobic drift of one ride: the numbers when [available], else the [reason] there are none. */
@Serializable
public data class RideDrift(
    val available: Boolean,
    val reason: String?,
    val driftPercent: Double?,
    val efFirst: Double?,
    val efSecond: Double?,
    val startSec: Int?,
    val endSec: Int?,
) {
    init {
        if (available) {
            require(
                reason == null && driftPercent != null && efFirst != null && efSecond != null &&
                    startSec != null && endSec != null,
            ) { "An available drift has all its numbers and no reason" }
            require(efFirst > 0.0 && efSecond > 0.0) { "An efficiency factor is positive" }
            require(endSec > startSec) { "endSec must be after startSec" }
        } else {
            require(!reason.isNullOrEmpty()) { "A drift that is not available says why" }
            require(
                driftPercent == null && efFirst == null && efSecond == null && startSec == null && endSec == null,
            ) { "A drift that is not available has no numbers" }
        }
    }
}

/** The best average power of one ride over [durationSec] seconds. */
@Serializable
public data class RideCurvePoint(
    val durationSec: Int,
    val power: Double,
) {
    init {
        require(durationSec in MetricsApiJson.POWER_CURVE_DURATIONS) { "Not a power curve duration: $durationSec s" }
        require(power >= 0.0) { "Power is not negative" }
    }
}

/** The W′ balance in kilojoules at the end of second [stepSeconds], twice that, and so on. */
@Serializable
public data class WPrimeSeries(
    val stepSeconds: Int,
    val balanceKj: List<Double>,
) {
    init {
        require(stepSeconds >= 1) { "stepSeconds is at least 1" }
    }
}

/** Where the critical power of a W′ balance came from. */
@Serializable
public enum class CpSource {
    @SerialName("profile")
    PROFILE,

    @SerialName("ride")
    RIDE,

    @SerialName("request")
    REQUEST,
}

/** Where the W′ of a W′ balance came from. */
@Serializable
public enum class WPrimeSource {
    @SerialName("profile")
    PROFILE,

    @SerialName("default")
    DEFAULT,

    @SerialName("request")
    REQUEST,
}

/**
 * The W′ balance of one ride (Skiba et al., 2012) for [cpWatts] and [wPrimeKj]: the recovery's time constant
 * [tauSeconds], the lowest balance ([minBalanceKj], reached at [minAtSec], [maxDepletionPercent] of W′) and, only when
 * asked, the [series].
 */
@Serializable
public data class WPrimeBalance(
    val cpWatts: Double,
    val cpSource: CpSource,
    val wPrimeKj: Double,
    val wPrimeSource: WPrimeSource,
    val tauSeconds: Double,
    val dCpWatts: Double,
    val minBalanceKj: Double,
    val minAtSec: Int,
    val maxDepletionPercent: Double,
    val series: WPrimeSeries? = null,
) {
    init {
        require(cpWatts > 0.0 && wPrimeKj > 0.0 && tauSeconds > 0.0) { "CP, W′ and tau are positive" }
        require(minAtSec >= 0) { "minAtSec is not negative" }
        require(minBalanceKj <= wPrimeKj + KJ_ROUNDING) { "The lowest balance is above W′" }
        val depletion = (wPrimeKj - minBalanceKj) / wPrimeKj * PERCENT
        require(abs(depletion - maxDepletionPercent) <= DEPLETION_ROUNDING) {
            "maxDepletionPercent $maxDepletionPercent does not follow from the minimum"
        }
    }
}

/**
 * The answer to `GET /v1/rides/{rideId}/metrics`: what the service computed from one ride. [powerCurve] is empty when
 * no power was measured; [wPrimeBalance] is there only when it was asked for.
 */
@Serializable
public data class RideMetricsResponse(
    val rideId: String,
    val startTime: String,
    val date: String,
    val ftp: Int,
    val durationSec: Int,
    val movingSec: Int,
    val load: Double?,
    val powerCurve: List<RideCurvePoint>,
    val drift: RideDrift,
    val wPrimeBalance: WPrimeBalance? = null,
) {
    init {
        require(MetricsApiJson.isValidRideId(rideId)) { "Not a ride id: '$rideId'" }
        require(MetricsApiJson.isValidInstant(startTime)) { "startTime is not a UTC instant: '$startTime'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(ftp >= 1) { "FTP is at least 1 W" }
        require(durationSec >= 0 && movingSec in 0..durationSec) { "movingSec is within the duration" }
        require(load == null || load >= 0.0) { "Load is not negative" }
        for (i in 1 until powerCurve.size) {
            require(powerCurve[i].durationSec > powerCurve[i - 1].durationSec) { "Curve durations must ascend" }
            require(
                powerCurve[i].power <= powerCurve[i - 1].power,
            ) { "The curve's power must not rise with the duration" }
        }
        if (drift.available) require(checkNotNull(drift.endSec) <= durationSec) { "The drift ends after the ride" }
        if (wPrimeBalance != null) {
            require(wPrimeBalance.minAtSec <= durationSec) { "minAtSec is after the end of the ride" }
            val series = wPrimeBalance.series
            if (series != null) {
                require(series.balanceKj.size <= durationSec / series.stepSeconds) {
                    "The series has ${series.balanceKj.size} points, more than the ride has steps"
                }
            }
        }
    }
}

/** Calendar helpers of the metrics answers. */
internal object MetricsDates {
    private const val THURSDAY_OFFSET = 3L

    /** Whether the day (days since 1970-01-01) is a Monday; 1970-01-01 was a Thursday. */
    fun isMonday(epochDay: Long): Boolean = (epochDay + THURSDAY_OFFSET).mod(DAYS_PER_WEEK) == 0L

    /** The Monday of the week of [epochDay]. */
    fun mondayOf(epochDay: Long): Long = epochDay - (epochDay + THURSDAY_OFFSET).mod(DAYS_PER_WEEK)
}

/** The rounding of one decimal, with room for the representation of sums. */
private const val ROUNDING: Double = 0.1 + 1e-9
private const val PERCENT: Double = 100.0
private const val PERCENT_ROUNDING: Double = 0.051
private const val KJ_ROUNDING: Double = 0.005
private const val DEPLETION_ROUNDING: Double = 0.5
private const val DAYS_PER_WEEK: Long = 7

/**
 * Reads and writes the metrics bodies of the sync API (spec §8.3). Strict like the schemas: unknown keys, a missing
 * key, a value of the wrong type and every rule of the schemas' descriptions are rejected with an
 * [IllegalArgumentException].
 */
public object MetricsApiJson {
    /** The durations of the power curve, in seconds. */
    public val POWER_CURVE_DURATIONS: List<Int> = listOf(1, 2, 5, 10, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600)

    /** The longest range `GET /v1/metrics` serves, in days, both ends included. */
    public const val MAX_RANGE_DAYS: Int = 400

    internal const val MIN_FTP: Int = 30
    internal const val MAX_FTP: Int = 2000

    private val json = Json

    private val rideIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val instantPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$")

    internal fun isValidRideId(id: String): Boolean = rideIdPattern.matches(id)

    internal fun isValidInstant(text: String): Boolean = instantPattern.matches(text)

    /** @throws IllegalArgumentException if [text] is not a metrics answer of the schema. */
    public fun decodeMetrics(text: String): MetricsResponse = json.decodeFromString(MetricsResponse.serializer(), text)

    public fun encodeMetrics(response: MetricsResponse): String =
        json.encodeToString(MetricsResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a ride metrics answer of the schema. */
    public fun decodeRideMetrics(text: String): RideMetricsResponse =
        json.decodeFromString(RideMetricsResponse.serializer(), text)

    public fun encodeRideMetrics(response: RideMetricsResponse): String =
        json.encodeToString(RideMetricsResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a rides list answer of the schema. */
    public fun decodeRides(text: String): RidesResponse = json.decodeFromString(RidesResponse.serializer(), text)

    public fun encodeRides(response: RidesResponse): String = json.encodeToString(RidesResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a ride detail answer of the schema. */
    public fun decodeRideDetail(text: String): RideDetailResponse =
        json.decodeFromString(RideDetailResponse.serializer(), text)

    public fun encodeRideDetail(response: RideDetailResponse): String =
        json.encodeToString(RideDetailResponse.serializer(), response)
}
