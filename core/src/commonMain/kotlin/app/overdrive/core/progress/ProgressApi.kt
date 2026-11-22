package app.overdrive.core.progress

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/*
 * Progress over time of the sync API (spec §6.3 "Progress over time", §8.3, ADR 0043): `GET /v1/progress?from&to`. Every model is as
 * strict as `contracts/schema/progress-response.schema.json` and checks the rules the schema cannot state (the mirror of
 * `progressConsistencyErrors` in `contracts/src/progress.ts`), so an unknown key, a missing one, a value out of range or an answer that
 * breaks a rule is an [IllegalArgumentException] when it is read. Messages name a field and the rule, never a figure.
 *
 * The phone computes no trend and no verdict: [ProgressTrend.direction], [ProgressTrend.verdictText] and the rest are the service's.
 * A screen draws the series and words the numbers around the service's sentences.
 */

/** What the models check; the limits are those of the schema. */
internal object ProgressChecks {
    val RIDE_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    val MONTH: Regex = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
    const val MAX_NAME = 100
    const val MAX_SENTENCE = 300
    const val MIN_FTP = 30
    const val MAX_FTP = 2000
    const val MIN_REFERENCE_POWER = 5
    const val MIN_REFERENCE_HEART_RATE = 30
    const val MAX_REFERENCE_HEART_RATE = 230
    const val MIN_POINTS = 2
    const val MAX_NIGHTS_OR_WEIGHINGS = 7
    const val MIN_EFFICIENCY_SECONDS = 1200
    const val MAX_LINES = 3

    /** Differences of figures that are rounded to a thousandth each are within this of their exact difference. */
    const val DIFFERENCE_TOLERANCE = 0.0501

    /** The ISO weekday of a Monday in [PlanCalendar.dayOfWeek]. */
    const val MONDAY = 1

    fun requireDate(
        text: String,
        what: String,
    ) = require(WellnessTime.isDate(text)) { "$what is not a real calendar date" }

    fun requireSentence(
        text: String,
        what: String,
    ) = require(text.length in 1..MAX_SENTENCE) { "$what must have 1 to 300 characters" }

    fun requireOptionalSentence(
        text: String?,
        what: String,
    ) = require(text == null || text.length in 1..MAX_SENTENCE) { "$what must have 1 to 300 characters" }

    fun requireRideId(
        id: String,
        what: String,
    ) = require(RIDE_ID.matches(id)) { "$what is not a ride id" }

    fun requireName(
        name: String,
        what: String,
    ) = require(name.length in 1..MAX_NAME) { "$what must have 1 to 100 characters" }

    fun requirePositive(
        value: Double?,
        what: String,
    ) = require(value == null || value > 0.0) { "$what is not above 0" }

    fun nextMonth(month: String): String {
        val year = month.substring(0, 4).toInt()
        val m = month.substring(5, 7).toInt()
        return if (m == 12) "${year + 1}-01" else "$year-${(m + 1).toString().padStart(2, '0')}"
    }
}

/** Which way a measure has been moving over the range, as the service judged it. */
@Serializable
public enum class ProgressDirection {
    @SerialName("improving")
    IMPROVING,

    @SerialName("worsening")
    WORSENING,

    @SerialName("steady")
    STEADY,

    @SerialName("unknown")
    UNKNOWN,
}

/** Which side of a measure is the better one. */
@Serializable
public enum class ProgressBetterWhen {
    @SerialName("higher")
    HIGHER,

    @SerialName("lower")
    LOWER,
}

/** The unit of a trend's figures. */
@Serializable
public enum class ProgressUnit(
    /** The unit as it is written after a number. */
    public val symbol: String,
) {
    @SerialName("W")
    WATTS("W"),

    @SerialName("bpm")
    BEATS_PER_MINUTE("bpm"),

    @SerialName("W/kg")
    WATTS_PER_KILOGRAM("W/kg"),

    @SerialName("W/bpm")
    WATTS_PER_BEAT("W/bpm"),

    @SerialName("%")
    PERCENT("%"),
}

/** The measures of the page; the headline names one of them. */
@Serializable
public enum class ProgressMeasure {
    @SerialName("bestPower")
    BEST_POWER,

    @SerialName("aerobicEfficiency")
    AEROBIC_EFFICIENCY,

    @SerialName("efficiencyFactor")
    EFFICIENCY_FACTOR,

    @SerialName("decoupling")
    DECOUPLING,

    @SerialName("restingHeartRate")
    RESTING_HEART_RATE,

    @SerialName("weight")
    WEIGHT,
}

/** The direction of a headline: a verdict, so never unknown. */
@Serializable
public enum class ProgressHeadlineDirection {
    @SerialName("improving")
    IMPROVING,

    @SerialName("worsening")
    WORSENING,

    @SerialName("steady")
    STEADY,
}

/**
 * The trend of one measure over the range: a least-squares line over the [points] that have a figure, steady when the fitted [change]
 * is smaller than [steadyBand], else improving or worsening by [betterWhen], unknown below [minPoints]. [verdictText] is the service's
 * sentence for it; [reason] says why a verdict is unknown; [note] is the heat, sleep and caffeine caution of the heart-rate measures.
 */
@Serializable
public data class ProgressTrend(
    val direction: ProgressDirection,
    val betterWhen: ProgressBetterWhen,
    val unit: ProgressUnit,
    val points: Int,
    val minPoints: Int,
    val change: Double?,
    val difference: Double?,
    val from: Double?,
    val latest: Double?,
    val since: String?,
    val steadyBand: Double,
    val verdictText: String,
    val reason: String?,
    val note: String?,
) {
    init {
        require(points >= 0) { "points is negative" }
        require(minPoints >= ProgressChecks.MIN_POINTS) { "minPoints is below 2" }
        require(steadyBand > 0.0) { "steadyBand is not above 0" }
        ProgressChecks.requireSentence(verdictText, "verdictText")
        ProgressChecks.requireOptionalSentence(reason, "reason")
        ProgressChecks.requireOptionalSentence(note, "note")
        if (points < minPoints) {
            require(direction == ProgressDirection.UNKNOWN) { "direction must be unknown below minPoints" }
            require(change == null && difference == null) { "no change or difference below minPoints" }
            require(reason != null) { "reason is needed when the direction is unknown" }
        } else {
            require(direction != ProgressDirection.UNKNOWN) { "direction cannot be unknown with enough points" }
            require(reason == null) { "reason must be null when there is a direction" }
            require(change != null) { "change is needed with enough points" }
            val wanted =
                when {
                    abs(change) < steadyBand -> ProgressDirection.STEADY
                    (change < 0.0) == (betterWhen == ProgressBetterWhen.LOWER) -> ProgressDirection.IMPROVING
                    else -> ProgressDirection.WORSENING
                }
            require(direction == wanted) { "direction does not follow from change, steadyBand and betterWhen" }
        }
        require((from == null) == (since == null)) { "since must be set exactly when from is" }
        if (since != null) ProgressChecks.requireDate(since, "since")
        require(points > 0 || (from == null && latest == null)) { "a figure without a point" }
        if (difference != null) {
            require(from != null && latest != null) { "difference needs from and latest" }
            require(abs(difference - (latest - from)) <= ProgressChecks.DIFFERENCE_TOLERANCE) {
                "difference is not latest minus from"
            }
        }
    }
}

/** The verdict of best power over its three durations: improving when at least two lines improve and none worsens, the reverse, else steady. */
@Serializable
public data class ProgressOverallTrend(
    val direction: ProgressDirection,
    val betterWhen: ProgressBetterWhen,
    /** How many of the three durations have a verdict. */
    val lines: Int,
    val verdictText: String,
    val reason: String?,
    val note: String?,
) {
    init {
        require(betterWhen == ProgressBetterWhen.HIGHER) { "betterWhen must be higher" }
        require(lines in 0..ProgressChecks.MAX_LINES) { "lines is not within 0..3" }
        ProgressChecks.requireSentence(verdictText, "verdictText")
        ProgressChecks.requireOptionalSentence(reason, "reason")
        require(note == null) { "note must be null" }
        require((direction == ProgressDirection.UNKNOWN) == (reason != null)) {
            "reason must be set exactly when the direction is unknown"
        }
    }
}

/** The best mean power of one duration in one month, with the weight behind watts per kilogram. */
@Serializable
public data class ProgressBestEffort(
    val watts: Double,
    val wattsPerKg: Double?,
    val weightKg: Double?,
    val date: String,
    val rideId: String,
    val rideName: String,
) {
    init {
        require(watts > 0.0) { "watts is not above 0" }
        ProgressChecks.requirePositive(wattsPerKg, "wattsPerKg")
        ProgressChecks.requirePositive(weightKg, "weightKg")
        require((wattsPerKg == null) == (weightKg == null)) { "wattsPerKg and weightKg are set together" }
        ProgressChecks.requireDate(date, "date")
        ProgressChecks.requireRideId(rideId, "rideId")
        ProgressChecks.requireName(rideName, "rideName")
    }
}

/** One calendar month of best power; a duration without a ride of that length is null. */
@Serializable
public data class ProgressMonth(
    /** `YYYY-MM`, in the rider's time zone. */
    val month: String,
    /** The current month: it is not in the trend. */
    val partial: Boolean,
    val d300: ProgressBestEffort?,
    val d1200: ProgressBestEffort?,
    val d3600: ProgressBestEffort?,
) {
    init {
        require(ProgressChecks.MONTH.matches(month)) { "month is not YYYY-MM" }
        for ((name, effort) in listOf("d300" to d300, "d1200" to d1200, "d3600" to d3600)) {
            if (effort != null) require(effort.date.startsWith(month)) { "$name/date is not in the month" }
        }
    }
}

/** The best 5, 20 and 60 minute power of each month, the weight behind watts per kilogram, and the verdicts. */
@Serializable
public data class ProgressBestPower(
    val months: List<ProgressMonth>,
    val trend: ProgressOverallTrend,
    val trends: ProgressBestPowerTrends,
) {
    init {
        for (i in 1 until months.size) {
            require(ProgressChecks.nextMonth(months[i - 1].month) == months[i].month) {
                "months/$i does not follow the month before it"
            }
        }
        require(months.dropLast(1).none { it.partial }) { "only the last month can be partial" }
        val known = listOf(trends.d300, trends.d1200, trends.d3600).filter { it.direction != ProgressDirection.UNKNOWN }
        val improving = known.count { it.direction == ProgressDirection.IMPROVING }
        val worsening = known.count { it.direction == ProgressDirection.WORSENING }
        require(trend.lines == known.size) { "trend/lines is not the lines with a verdict" }
        val wanted =
            when {
                known.size < 2 -> ProgressDirection.UNKNOWN
                improving >= 2 && worsening == 0 -> ProgressDirection.IMPROVING
                worsening >= 2 && improving == 0 -> ProgressDirection.WORSENING
                else -> ProgressDirection.STEADY
            }
        require(trend.direction == wanted) { "trend/direction does not follow from the lines" }
    }
}

/** The trend of each duration of best power. */
@Serializable
public data class ProgressBestPowerTrends(
    val d300: ProgressTrend,
    val d1200: ProgressTrend,
    val d3600: ProgressTrend,
)

/** The stretches and seconds behind one of a week's two figures. */
@Serializable
public data class ProgressStretchCount(
    val stretches: Int,
    val seconds: Int,
) {
    init {
        require(stretches >= 0 && seconds >= 0) { "a count is negative" }
    }
}

/** One week of aerobic efficiency: the heart rate at the reference power and the power at the reference heart rate. */
@Serializable
public data class ProgressEfficiencyWeek(
    /** The Monday. */
    val weekStart: String,
    val heartRateAtPower: Double?,
    val powerAtHeartRate: Double?,
    val atPower: ProgressStretchCount,
    val atHeartRate: ProgressStretchCount,
) {
    init {
        ProgressChecks.requireDate(weekStart, "weekStart")
        ProgressChecks.requirePositive(heartRateAtPower, "heartRateAtPower")
        ProgressChecks.requirePositive(powerAtHeartRate, "powerAtHeartRate")
    }
}

/** Where the reference heart rate comes from. */
@Serializable
public enum class ProgressHeartRateBasis {
    @SerialName("threshold")
    THRESHOLD,

    @SerialName("maximum")
    MAXIMUM,
}

/**
 * Aerobic efficiency: both references are fixed for the range (so a change of FTP never moves them silently) and named here. [trend] is
 * the heart rate at the reference power (the card's verdict); [powerTrend] the power at the reference heart rate.
 */
@Serializable
public data class ProgressAerobicEfficiency(
    val ftpAtStart: Int?,
    val referencePower: Int?,
    val referenceHeartRate: Int?,
    val referenceHeartRateBasis: ProgressHeartRateBasis?,
    val weeks: List<ProgressEfficiencyWeek>,
    val trend: ProgressTrend,
    val powerTrend: ProgressTrend,
) {
    init {
        require(ftpAtStart == null || ftpAtStart in ProgressChecks.MIN_FTP..ProgressChecks.MAX_FTP) {
            "ftpAtStart is not within 30..2000"
        }
        require(
            referencePower == null || referencePower in ProgressChecks.MIN_REFERENCE_POWER..ProgressChecks.MAX_FTP,
        ) {
            "referencePower is not within 5..2000"
        }
        require(
            referenceHeartRate == null ||
                referenceHeartRate in ProgressChecks.MIN_REFERENCE_HEART_RATE..ProgressChecks.MAX_REFERENCE_HEART_RATE,
        ) { "referenceHeartRate is not within 30..230" }
        require((referenceHeartRate == null) == (referenceHeartRateBasis == null)) {
            "referenceHeartRateBasis is set exactly when the reference is"
        }
        require((referencePower == null) == (ftpAtStart == null)) { "referencePower is set exactly when ftpAtStart is" }
        requireWeeks(weeks.map { it.weekStart }, "weeks")
    }
}

/** One steady ride's efficiency factor: normalised power over average heart rate of its steady parts. */
@Serializable
public data class ProgressEfficiencyRide(
    val date: String,
    val rideId: String,
    val name: String,
    val value: Double,
    val normalizedPower: Double,
    val averageHeartRate: Double,
    val seconds: Int,
) {
    init {
        ProgressChecks.requireDate(date, "date")
        ProgressChecks.requireRideId(rideId, "rideId")
        ProgressChecks.requireName(name, "name")
        require(value > 0.0 && normalizedPower > 0.0 && averageHeartRate > 0.0) { "a figure is not above 0" }
        require(seconds >= ProgressChecks.MIN_EFFICIENCY_SECONDS) { "seconds is below 1200" }
    }
}

/** One ride's aerobic drift (spec §6.3). */
@Serializable
public data class ProgressDriftRide(
    val date: String,
    val rideId: String,
    val name: String,
    val driftPercent: Double,
) {
    init {
        ProgressChecks.requireDate(date, "date")
        ProgressChecks.requireRideId(rideId, "rideId")
        ProgressChecks.requireName(name, "name")
    }
}

/** The mean of the last five rides at the day of the ride that completes them. */
@Serializable
public data class ProgressRolling(
    val date: String,
    val rideId: String,
    val value: Double,
) {
    init {
        ProgressChecks.requireDate(date, "date")
        ProgressChecks.requireRideId(rideId, "rideId")
    }
}

/** Efficiency factor: one value per steady ride, with the mean of the last five ([rolling5]). */
@Serializable
public data class ProgressEfficiencyFactor(
    val rides: List<ProgressEfficiencyRide>,
    val rolling5: List<ProgressRolling>,
    val trend: ProgressTrend,
) {
    init {
        requireRides(rides.map { it.date to it.rideId }, "rides")
        requireRides(rolling5.map { it.date to it.rideId }, "rolling5")
    }
}

/** Aerobic drift per ride of 40 minutes or more, with the mean of the last five. */
@Serializable
public data class ProgressDecoupling(
    val rides: List<ProgressDriftRide>,
    val rolling5: List<ProgressRolling>,
    val trend: ProgressTrend,
) {
    init {
        requireRides(rides.map { it.date to it.rideId }, "rides")
        requireRides(rolling5.map { it.date to it.rideId }, "rolling5")
    }
}

/** One week of the watch's resting heart rate: the mean of the nights, from three nights. */
@Serializable
public data class ProgressRestingWeek(
    val weekStart: String,
    val mean: Double?,
    val nights: Int,
) {
    init {
        ProgressChecks.requireDate(weekStart, "weekStart")
        ProgressChecks.requirePositive(mean, "mean")
        require(nights in 0..ProgressChecks.MAX_NIGHTS_OR_WEIGHINGS) { "nights is not within 0..7" }
        require((mean == null) == (nights < MIN_NIGHTS)) { "mean is set exactly with enough nights" }
    }

    public companion object {
        /** A week has a resting heart rate with this many nights. */
        public const val MIN_NIGHTS: Int = 3
    }
}

/** Resting heart rate per week. */
@Serializable
public data class ProgressRestingHeartRate(
    val weeks: List<ProgressRestingWeek>,
    val trend: ProgressTrend,
) {
    init {
        requireWeeks(weeks.map { it.weekStart }, "weeks")
    }
}

/** One week of weight and watts per kilogram (the FTP in force that week over the weight). */
@Serializable
public data class ProgressWeightWeek(
    val weekStart: String,
    val kg: Double?,
    val weighings: Int,
    val ftp: Int?,
    val wattsPerKg: Double?,
) {
    init {
        ProgressChecks.requireDate(weekStart, "weekStart")
        ProgressChecks.requirePositive(kg, "kg")
        ProgressChecks.requirePositive(wattsPerKg, "wattsPerKg")
        require(weighings in 0..ProgressChecks.MAX_NIGHTS_OR_WEIGHINGS) { "weighings is not within 0..7" }
        require(ftp == null || ftp in ProgressChecks.MIN_FTP..ProgressChecks.MAX_FTP) { "ftp is not within 30..2000" }
        require((kg == null) == (weighings < MIN_WEIGHINGS)) { "kg is set exactly with enough weighings" }
        require(wattsPerKg == null || (kg != null && ftp != null)) { "wattsPerKg needs a weight and an FTP" }
    }

    public companion object {
        /** A week has a weight with this many weighings. */
        public const val MIN_WEIGHINGS: Int = 2
    }
}

/** The weight over the range, reported and never judged. */
@Serializable
public data class ProgressWeightChange(
    val points: Int,
    val from: Double,
    val latest: Double,
    val difference: Double,
    val since: String,
) {
    init {
        require(points >= ProgressChecks.MIN_POINTS) { "points is below 2" }
        require(from > 0.0 && latest > 0.0) { "a weight is not above 0" }
        require(abs(difference - (latest - from)) <= ProgressChecks.DIFFERENCE_TOLERANCE) {
            "difference is not latest minus from"
        }
        ProgressChecks.requireDate(since, "since")
    }
}

/** Weight and watts per kilogram per week; [trend] is of watts per kilogram, [weightChange] is the weight, never judged. */
@Serializable
public data class ProgressWeight(
    val weeks: List<ProgressWeightWeek>,
    val trend: ProgressTrend,
    val weightChange: ProgressWeightChange?,
) {
    init {
        requireWeeks(weeks.map { it.weekStart }, "weeks")
    }
}

/** The measure whose verdict the Fitness and Power rows show, with the service's sentence for it. */
@Serializable
public data class ProgressHeadline(
    val measure: ProgressMeasure,
    val direction: ProgressHeadlineDirection,
    val verdictText: String,
) {
    init {
        ProgressChecks.requireSentence(verdictText, "verdictText")
    }
}

/** The days an answer covers, both included. */
@Serializable
public data class ProgressRangeDays(
    val from: String,
    val to: String,
) {
    init {
        ProgressChecks.requireDate(from, "from")
        ProgressChecks.requireDate(to, "to")
        require(from <= to) { "from is after to" }
    }
}

/** The answer to `GET /v1/progress`. */
@Serializable
public data class ProgressResponse(
    val generatedAt: String,
    val stale: Boolean,
    val range: ProgressRangeDays,
    val headline: ProgressHeadline?,
    val bestPower: ProgressBestPower,
    val aerobicEfficiency: ProgressAerobicEfficiency,
    val efficiencyFactor: ProgressEfficiencyFactor,
    val decoupling: ProgressDecoupling,
    val restingHeartRate: ProgressRestingHeartRate,
    val weight: ProgressWeight,
) {
    init {
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a real UTC instant" }
        if (headline != null) {
            val direction =
                when (headline.measure) {
                    ProgressMeasure.BEST_POWER -> bestPower.trend.direction
                    ProgressMeasure.AEROBIC_EFFICIENCY -> aerobicEfficiency.trend.direction
                    ProgressMeasure.EFFICIENCY_FACTOR -> efficiencyFactor.trend.direction
                    ProgressMeasure.DECOUPLING -> decoupling.trend.direction
                    ProgressMeasure.RESTING_HEART_RATE -> restingHeartRate.trend.direction
                    ProgressMeasure.WEIGHT -> weight.trend.direction
                }
            val said =
                when (headline.direction) {
                    ProgressHeadlineDirection.IMPROVING -> ProgressDirection.IMPROVING
                    ProgressHeadlineDirection.WORSENING -> ProgressDirection.WORSENING
                    ProgressHeadlineDirection.STEADY -> ProgressDirection.STEADY
                }
            require(direction == said) { "headline/direction is not the direction of its measure" }
        }
    }
}

private fun requireWeeks(
    weekStarts: List<String>,
    what: String,
) {
    for ((i, week) in weekStarts.withIndex()) {
        ProgressChecks.requireDate(week, "$what/$i/weekStart")
        require(PlanCalendar.dayOfWeek(week) == ProgressChecks.MONDAY) { "$what/$i/weekStart is not a Monday" }
        if (i > 0) {
            require(PlanCalendar.addDays(weekStarts[i - 1], WEEK_DAYS) == week) {
                "$what/$i/weekStart does not follow the week before it"
            }
        }
    }
}

private fun requireRides(
    rides: List<Pair<String, String>>,
    what: String,
) {
    val seen = mutableSetOf<String>()
    for ((i, ride) in rides.withIndex()) {
        ProgressChecks.requireDate(ride.first, "$what/$i/date")
        require(seen.add(ride.second)) { "$what/$i/rideId is used twice" }
        if (i > 0) require(rides[i - 1].first <= ride.first) { "$what/$i is out of order: oldest first" }
    }
}

private const val WEEK_DAYS = 7
