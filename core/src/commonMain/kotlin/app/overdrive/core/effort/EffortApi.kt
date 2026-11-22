package app.overdrive.core.effort

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/*
 * Weekly effort of the sync API (spec §6.3, §8.3, docs/adr/0042, design/companion.md §4.15, §6.12): `GET /v1/effort`, the training load
 * per week, each week's usual range and verdict, its days and its activities. The model is as strict as
 * `contracts/schema/effort-response.schema.json` and checks the rules the schema cannot state (its description lists them), so an unknown
 * key, a missing one, a value out of range or an answer that breaks a rule is an [IllegalArgumentException] when it is read. Messages name
 * a field and the rule, never a figure. **The phone computes none of it**: the loads, the usual range, the verdict and the shares are the
 * service's, and the words for the verdicts are the app's.
 */

/** The weeks of training a usual range needs before the week it is for. */
public const val EFFORT_WEEKS_NEEDED: Int = 8

/** The weeks of `GET /v1/effort` without a `weeks`, and the most it serves. */
public const val EFFORT_DEFAULT_WEEKS: Int = 12
public const val EFFORT_MAX_WEEKS: Int = 52

/** What the models check; the limits are those of the schema. */
internal object EffortChecks {
    const val MAX_LOAD = 100_000.0
    const val MAX_ID = 140
    const val MAX_SPORT = 100
    const val MAX_NAME = 200

    /** Loads are one decimal and every sum is of rounded figures: this is only float noise. */
    const val SUM_TOLERANCE = 0.0501
    const val SHARE_TOLERANCE = 0.0006
    const val DAYS_PER_WEEK = 7
    const val MONDAY = 1

    fun requireDate(
        text: String,
        what: String,
    ) = require(WellnessTime.isDate(text)) { "$what is not a real calendar date" }

    fun requireLoad(
        value: Double?,
        what: String,
    ) = require(value == null || value in 0.0..MAX_LOAD) { "$what is not within 0..100000" }
}

/** `ride` for a ride on the tablet, `activity` for a workout of the watch or an outdoor ride imported from a FIT file. */
@Serializable
public enum class EffortKind {
    @SerialName("ride")
    RIDE,

    @SerialName("activity")
    ACTIVITY,
}

/** How a week's load compares with its usual range; the words are the app's (design/companion.md §7.8). */
@Serializable
public enum class EffortVerdict {
    @SerialName("above")
    ABOVE,

    @SerialName("within")
    WITHIN,

    /** For the week in progress this is "below so far". */
    @SerialName("below")
    BELOW,

    /** The week in progress has nothing recorded yet. */
    @SerialName("none")
    NONE,

    /** There is no usual range yet. */
    @SerialName("learning")
    LEARNING,
}

/**
 * A ride or a workout in a week. [load] is null when it could not be worked out (no heart rate, or no limits to scale it by) and then
 * it is in no sum and has no [share]; [approximate] is true for a load that comes from heart rate; [sport] is null for a ride and
 * `cycling-outdoor` for an outdoor ride.
 */
@Serializable
public data class EffortActivity(
    val id: String,
    val kind: EffortKind,
    val sport: String?,
    val name: String,
    /** The rider's local day. */
    val date: String,
    val load: Double?,
    val approximate: Boolean,
    /** The load's share of the week's load, 0 to 1; null when [load] is. */
    val share: Double?,
) {
    init {
        require(id.length in 1..EffortChecks.MAX_ID) { "id must have 1 to 140 characters" }
        require(sport == null || sport.length in 1..EffortChecks.MAX_SPORT) { "sport must have 1 to 100 characters" }
        require(name.length in 1..EffortChecks.MAX_NAME) { "name must have 1 to 200 characters" }
        EffortChecks.requireDate(date, "date")
        EffortChecks.requireLoad(load, "load")
        require(share == null || share in 0.0..1.0) { "share is not within 0..1" }
        require((load == null) == (share == null)) { "share is null exactly when load is" }
        require((kind == EffortKind.RIDE) == (sport == null)) { "a ride has no sport and an activity has one" }
    }
}

/** One day of a week: its date and the sum of its activities' loads. */
@Serializable
public data class EffortDay(
    val date: String,
    val load: Double,
) {
    init {
        EffortChecks.requireDate(date, "date")
        EffortChecks.requireLoad(load, "load")
    }
}

/** One week, Monday to Sunday, and how it compares with the rider's usual range for it. */
@Serializable
public data class EffortWeek(
    /** The Monday, a local date. */
    val weekStart: String,
    val load: Double,
    /** Any of the load comes from heart rate. */
    val approximate: Boolean,
    /** True for the week in progress, the last one. */
    val current: Boolean,
    /** The mean minus one standard deviation of the 8 complete weeks before this one (not below 0); null while learning. */
    val usualLow: Double?,
    /** The mean plus one standard deviation; null while learning. */
    val usualHigh: Double?,
    /** Complete weeks of training before this one, at most [EFFORT_WEEKS_NEEDED]. */
    val weeksLogged: Int,
    val verdict: EffortVerdict,
    /** Seven days, Monday to Sunday; the days after today are 0. */
    val days: List<EffortDay>,
    /** Oldest first. */
    val activities: List<EffortActivity>,
) {
    init {
        EffortChecks.requireDate(weekStart, "weekStart")
        require(PlanCalendar.dayOfWeek(weekStart) == EffortChecks.MONDAY) { "weekStart is not a Monday" }
        EffortChecks.requireLoad(load, "load")
        EffortChecks.requireLoad(usualLow, "usualLow")
        EffortChecks.requireLoad(usualHigh, "usualHigh")
        require((usualLow == null) == (usualHigh == null)) { "usualLow and usualHigh are both numbers or both null" }
        require(usualLow == null || usualHigh == null || usualLow <= usualHigh) { "usualLow is above usualHigh" }
        require(weeksLogged in 0..EFFORT_WEEKS_NEEDED) { "weeksLogged is not within 0..8" }
        require((weeksLogged < EFFORT_WEEKS_NEEDED) == (usualLow == null)) {
            "a week has a usual range exactly when weeksLogged has reached weeksNeeded"
        }
        require(days.size == EffortChecks.DAYS_PER_WEEK) { "a week has seven days" }
        for ((index, day) in days.withIndex()) {
            require(day.date == PlanCalendar.addDays(weekStart, index)) {
                "days/$index is not the day it should be from the Monday on"
            }
        }
        require(abs(days.sumOf { it.load } - load) <= EffortChecks.SUM_TOLERANCE) { "load is not the sum of its days" }
        var lastDate = ""
        val daySums = HashMap<String, Double>()
        for ((index, activity) in activities.withIndex()) {
            require(days.any { it.date == activity.date }) { "activities/$index/date is not a day of the week" }
            require(activity.date >= lastDate) { "activities/$index/date is before the activity before it" }
            lastDate = activity.date
            if (activity.load != null) {
                daySums[activity.date] = (daySums[activity.date] ?: 0.0) + activity.load
                val wanted = if (load == 0.0) 0.0 else activity.load / load
                require(abs(checkNotNull(activity.share) - wanted) <= EffortChecks.SHARE_TOLERANCE) {
                    "activities/$index/share is not the activity's load over the week's"
                }
            }
        }
        for (day in days) {
            require(abs((daySums[day.date] ?: 0.0) - day.load) <= EffortChecks.SUM_TOLERANCE) {
                "days is not the sum of the activities on ${day.date}"
            }
        }
        require(
            approximate ==
                activities.any {
                    it.approximate
                },
        ) { "approximate is true exactly when one of its activities is" }
        val range = if (usualLow != null && usualHigh != null) usualLow to usualHigh else null
        val wanted =
            when {
                range == null -> EffortVerdict.LEARNING
                current && activities.isEmpty() -> EffortVerdict.NONE
                load < range.first -> EffortVerdict.BELOW
                load > range.second -> EffortVerdict.ABOVE
                else -> EffortVerdict.WITHIN
            }
        require(verdict == wanted) { "verdict does not follow from the load and the usual range" }
    }

    /** The usual range as a pair, or null while learning. */
    public val usual: ClosedFloatingPointRange<Double>?
        get() = if (usualLow != null && usualHigh != null) usualLow..usualHigh else null
}

/** The answer to `GET /v1/effort`. */
@Serializable
public data class EffortResponse(
    val generatedAt: String,
    val weeksNeeded: Int,
    /** The current week's [EffortWeek.weeksLogged]. */
    val weeksLogged: Int,
    /** The weeks ending with the current one, oldest first. */
    val weeks: List<EffortWeek>,
) {
    init {
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a real UTC instant" }
        require(weeksNeeded == EFFORT_WEEKS_NEEDED) { "weeksNeeded is not 8" }
        require(weeks.size in 1..EFFORT_MAX_WEEKS) { "weeks must have 1 to 52 weeks" }
        for ((index, week) in weeks.withIndex()) {
            require(week.current == (index == weeks.lastIndex)) {
                "weeks/$index/current must be true for the last week and only for it"
            }
            if (index > 0) {
                require(
                    week.weekStart == PlanCalendar.addDays(weeks[index - 1].weekStart, EffortChecks.DAYS_PER_WEEK),
                ) {
                    "weeks/$index/weekStart does not follow the week before it"
                }
                require(
                    week.weeksLogged >= weeks[index - 1].weeksLogged,
                ) { "weeks/$index/weeksLogged falls from the week before" }
            }
        }
        require(weeksLogged == weeks.last().weeksLogged) { "weeksLogged is not the current week's" }
    }

    /** The week in progress. */
    public val currentWeek: EffortWeek get() = weeks.last()
}
