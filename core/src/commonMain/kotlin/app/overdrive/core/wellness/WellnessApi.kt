package app.overdrive.core.wellness

import app.overdrive.core.plan.PlanDates
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The wellness part of the sync API (spec §6.2, §6.4, §6.7, §8.3, ADR 0023): the batch the companion app uploads to
 * `POST /v1/wellness`, and the answers of `GET /v1/wellness`, `GET /v1/recovery` and `GET /v1/activities/{id}`.
 * Every model is as strict as its schema in `contracts/schema/` and checks the rules the schema cannot state, so a
 * value out of range, an unknown key or a missing one is an [IllegalArgumentException] when it is read. Messages name
 * a field and the rule, never a measured value.
 */

/** Instants and dates as the wellness API writes them. */
public object WellnessTime {
    private val instant = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$")
    private val utcInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$")
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE_OR_SECOND = 59
    private const val MILLIS_DIGITS = 3

    /**
     * The epoch milliseconds of a UTC instant as the Health Connect records write it (`2026-11-18T06:30:00.123456789Z`;
     * fractions beyond milliseconds are dropped), or null when it is not a real date and time.
     */
    public fun parseInstant(text: String): Long? {
        val match = instant.matchEntire(text) ?: return null
        val (year, month, day, hour, minute, second, fraction) = match.destructured
        val epochDay = PlanDates.epochDay("$year-$month-$day") ?: return null
        if (hour.toInt() > MAX_HOUR || minute.toInt() > MAX_MINUTE_OR_SECOND || second.toInt() > MAX_MINUTE_OR_SECOND) {
            return null
        }
        val millis = fraction.padEnd(MILLIS_DIGITS, '0').take(MILLIS_DIGITS).toLong()
        return epochDay * MILLIS_PER_DAY + hour.toLong() * MILLIS_PER_HOUR + minute.toLong() * MILLIS_PER_MINUTE +
            second.toLong() * MILLIS_PER_SECOND + millis
    }

    /** A UTC instant as the answers write it: `2026-11-18T07:40:00Z`, with up to three fractional digits. */
    public fun isUtcInstant(text: String): Boolean = utcInstant.matches(text) && parseInstant(text) != null

    /** A calendar date, `YYYY-MM-DD`, that is a real one. */
    public fun isDate(text: String): Boolean = PlanDates.epochDay(text) != null
}

// ---- The upload ---------------------------------------------------------------------------------------

/** Sleep stages by name (`SleepSessionRecord.Stage`); `awake`, `awake_in_bed` and `out_of_bed` do not count as sleep. */
@Serializable
public enum class SleepStage {
    @SerialName("awake")
    AWAKE,

    @SerialName("awake_in_bed")
    AWAKE_IN_BED,

    @SerialName("out_of_bed")
    OUT_OF_BED,

    @SerialName("sleeping")
    SLEEPING,

    @SerialName("light")
    LIGHT,

    @SerialName("deep")
    DEEP,

    @SerialName("rem")
    REM,

    @SerialName("unknown")
    UNKNOWN,
}

/** One heart-rate sample: the instant it was taken and the rate in beats per minute (1 to 300). */
@Serializable
public data class HeartRateSample(
    val t: String,
    val bpm: Int,
) {
    init {
        require(WellnessTime.parseInstant(t) != null) { "A sample's t is not a real UTC instant" }
        require(bpm in WellnessChecks.MIN_BPM..WellnessChecks.MAX_BPM) { "A sample's bpm is not within 1..300" }
    }
}

/** A stage of a sleep session. */
@Serializable
public data class SleepStageSpan(
    val stage: SleepStage,
    val start: String,
    val end: String,
) {
    init {
        val from = WellnessChecks.instant(start, "a stage's start")
        val to = WellnessChecks.instant(end, "a stage's end")
        require(to >= from) { "A stage ends before it starts" }
    }
}

/** A record of the upload, one of the eight kinds, named by its `kind` key. */
@Serializable
public sealed interface WellnessRecord {
    /** The Health Connect record id: 1 to 100 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit. */
    public val id: String

    /** `metadata.lastModifiedTime`, when known. */
    public val lastModified: String?

    /** Seconds the zone the record was written in is ahead of UTC, when Health Connect says. */
    public val zoneOffsetSeconds: Int?
}

/** `RestingHeartRateRecord`. */
@Serializable
@SerialName("restingHeartRate")
public data class RestingHeartRateRecord(
    override val id: String,
    val time: String,
    val bpm: Int,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.instant(time, "time")
        require(bpm in WellnessChecks.MIN_BPM..WellnessChecks.MAX_BPM) { "bpm is not within 1..300" }
    }
}

/** `HeartRateRecord`: samples between [start] and [end], at most 20000. */
@Serializable
@SerialName("heartRateSamples")
public data class HeartRateSamplesRecord(
    override val id: String,
    val start: String,
    val end: String,
    val samples: List<HeartRateSample>,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.span(start, end, mustBeLonger = false)
        require(samples.size in 1..WellnessChecks.MAX_RECORD_SAMPLES) { "samples must have 1 to 20000 entries" }
    }
}

/** `SleepSessionRecord`, with its stages when the watch writes them. */
@Serializable
@SerialName("sleepSession")
public data class SleepSessionRecord(
    override val id: String,
    val start: String,
    val end: String,
    val title: String? = null,
    val stages: List<SleepStageSpan>? = null,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.span(start, end, mustBeLonger = false)
        WellnessChecks.title(title)
        require(stages == null || stages.size <= WellnessChecks.MAX_STAGES) { "stages has more than 2000 entries" }
    }
}

/** `RespiratoryRateRecord`: breaths per minute (0 to 1000). */
@Serializable
@SerialName("respiratoryRate")
public data class RespiratoryRateRecord(
    override val id: String,
    val time: String,
    val rate: Double,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.instant(time, "time")
        require(rate in 0.0..WellnessChecks.MAX_RATE) { "rate is not within 0..1000" }
    }
}

/** `OxygenSaturationRecord`: percent (0 to 100). */
@Serializable
@SerialName("oxygenSaturation")
public data class OxygenSaturationRecord(
    override val id: String,
    val time: String,
    val percent: Double,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.instant(time, "time")
        require(percent in 0.0..WellnessChecks.MAX_PERCENT) { "percent is not within 0..100" }
    }
}

/** `StepsRecord`. */
@Serializable
@SerialName("steps")
public data class StepsRecord(
    override val id: String,
    val start: String,
    val end: String,
    val count: Int,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.span(start, end, mustBeLonger = false)
        require(count in 0..WellnessChecks.MAX_STEPS) { "count is not within 0..1000000" }
    }
}

/**
 * `ExerciseSessionRecord` (spec §6.7): [type] is the exercise type as a lower-case token (`biking`, `running`,
 * `other_workout`, ...); [heartRateSamples] are the samples inside the session, at most 50000.
 */
@Serializable
@SerialName("exerciseSession")
public data class ExerciseSessionRecord(
    override val id: String,
    val type: String,
    val start: String,
    val end: String,
    val title: String? = null,
    val heartRateSamples: List<HeartRateSample>? = null,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        require(WellnessChecks.exerciseType.matches(type)) { "type is not a lower-case token" }
        WellnessChecks.span(start, end, mustBeLonger = true)
        WellnessChecks.title(title)
        require(heartRateSamples == null || heartRateSamples.size <= WellnessChecks.MAX_SESSION_SAMPLES) {
            "heartRateSamples has more than 50000 entries"
        }
    }
}

/** `HeartRateVariabilityRmssdRecord`: rMSSD in milliseconds (above 0, at most 1000). */
@Serializable
@SerialName("heartRateVariabilityRmssd")
public data class HeartRateVariabilityRmssdRecord(
    override val id: String,
    val time: String,
    val ms: Double,
    override val lastModified: String? = null,
    override val zoneOffsetSeconds: Int? = null,
) : WellnessRecord {
    init {
        WellnessChecks.common(id, lastModified, zoneOffsetSeconds)
        WellnessChecks.instant(time, "time")
        require(ms > 0.0 && ms <= WellnessChecks.MAX_HRV) { "ms is not within (0, 1000]" }
    }
}

/**
 * The body of `POST /v1/wellness`: at most [WellnessApiJson.MAX_RECORDS] records, each id used once. [device] names the
 * phone for the service's logs.
 */
@Serializable
public data class WellnessUpload(
    val source: String,
    val records: List<WellnessRecord>,
    val device: String? = null,
) {
    init {
        require(source == "healthconnect") { "source must be healthconnect" }
        require(
            device == null || device.length in 1..WellnessChecks.MAX_DEVICE,
        ) { "device must have 1 to 64 characters" }
        require(records.size <= WellnessApiJson.MAX_RECORDS) { "At most ${WellnessApiJson.MAX_RECORDS} records" }
        require(records.map { it.id }.toSet().size == records.size) { "A record id is used twice in the batch" }
    }
}

/** accepted, duplicates and rejected for one kind of record, or for the whole batch. */
@Serializable
public data class RecordCounts(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
) {
    init {
        require(accepted in 0..WellnessApiJson.MAX_RECORDS) { "accepted is not within 0..2000" }
        require(duplicates in 0..WellnessApiJson.MAX_RECORDS) { "duplicates is not within 0..2000" }
        require(rejected in 0..WellnessApiJson.MAX_RECORDS) { "rejected is not within 0..2000" }
    }
}

/** The answer to `POST /v1/wellness`: counts only. [byType] is keyed by the record kind (`steps`, `sleepSession`, ...). */
@Serializable
public data class WellnessUploadResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    val byType: Map<String, RecordCounts>,
    val generatedAt: String,
) {
    init {
        RecordCounts(accepted, duplicates, rejected)
        for (kind in byType.keys) require(kind in WellnessApiJson.RECORD_KINDS) { "byType names an unknown kind" }
        require(byType.values.sumOf { it.accepted } == accepted) { "accepted is not the sum of byType" }
        require(byType.values.sumOf { it.duplicates } == duplicates) { "duplicates is not the sum of byType" }
        require(byType.values.sumOf { it.rejected } == rejected) { "rejected is not the sum of byType" }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant" }
    }
}

// ---- Daily figures --------------------------------------------------------------------------------------

/** Where a daily figure came from. */
@Serializable
public enum class WellnessSourceId {
    @SerialName("healthconnect")
    HEALTH_CONNECT,

    @SerialName("intervals")
    INTERVALS,
}

/** Where a value stands against the personal range of that day. */
@Serializable
public enum class Deviation {
    @SerialName("inside")
    INSIDE,

    @SerialName("above")
    ABOVE,

    @SerialName("below")
    BELOW,
}

/**
 * One metric on one day with the personal baseline in force that day (`wellness-response.schema.json`): [value] and its
 * [source]; the usual range [baselineLow]..[baselineHigh] (null until 14 readings stand behind it); [deviation] against it;
 * [streak], the days in a row up to this one outside the range on the same side (0 inside, or without a value or a range).
 */
@Serializable
public data class Reading(
    val value: Double?,
    val source: WellnessSourceId?,
    val baselineLow: Double?,
    val baselineHigh: Double?,
    val deviation: Deviation?,
    val streak: Int,
) {
    init {
        require(streak >= 0) { "streak is negative" }
        if (value == null) {
            require(source == null) { "source must be null without a value" }
            require(deviation == null) { "deviation must be null without a value" }
            require(streak == 0) { "streak must be 0 without a value" }
        } else {
            require(source != null) { "source is needed with a value" }
        }
        require(
            (baselineLow == null) == (baselineHigh == null),
        ) { "baselineLow and baselineHigh are both null or both set" }
        require(baselineLow == null || baselineHigh == null || baselineLow <= baselineHigh) {
            "baselineLow is above baselineHigh"
        }
        require(deviation == null || baselineLow != null) { "deviation needs a range" }
        require(deviation != Deviation.INSIDE || streak == 0) { "streak must be 0 inside the range" }
        require((deviation != Deviation.ABOVE && deviation != Deviation.BELOW) || streak >= 1) {
            "streak must be at least 1 outside the range"
        }
    }
}

/** One local day of the wellness answer. A night belongs to the date of the morning it ends. */
@Serializable
public data class WellnessDay(
    val date: String,
    val restingHr: Reading,
    val sleepSeconds: Reading,
    val bedtimeMinutes: Reading,
    val wakeMinutes: Reading,
    val respiratoryRate: Reading,
    val spo2: Reading,
    val sleepingHr: Reading,
    val hrvRmssd: Reading,
    val sleepStart: String?,
    val sleepEnd: String?,
    val steps: Int?,
    val hrvSdnn: Double?,
) {
    init {
        require(WellnessTime.isDate(date)) { "date is not a real calendar date" }
        require(sleepStart == null || WellnessTime.isUtcInstant(sleepStart)) { "sleepStart is not a UTC instant" }
        require(sleepEnd == null || WellnessTime.isUtcInstant(sleepEnd)) { "sleepEnd is not a UTC instant" }
        require(steps == null || steps >= 0) { "steps is negative" }
    }
}

/** A usual range in minutes from local midnight. */
@Serializable
public data class UsualRange(
    val low: Double,
    val high: Double,
) {
    init {
        require(low <= high) { "low is above high" }
    }
}

/** The usual bedtime and wake-up ranges as of the last day of the answer. */
@Serializable
public data class UsualSleepTimes(
    val bedtime: UsualRange?,
    val wake: UsualRange?,
)

/** The answer to `GET /v1/wellness?from&to`: one [WellnessDay] for every date from [from] to [to], at most 186. */
@Serializable
public data class WellnessResponse(
    val from: String,
    val to: String,
    val generatedAt: String,
    val stale: Boolean,
    val nightsLogged: Int,
    val nightsNeeded: Int,
    val baselineDays: Int,
    val usual: UsualSleepTimes,
    val days: List<WellnessDay>,
) {
    init {
        val first = PlanDates.epochDay(from)
        val last = PlanDates.epochDay(to)
        require(first != null && last != null) { "from and to must be real calendar dates" }
        require(last >= first) { "from is after to" }
        val count = last - first + 1
        require(count <= WellnessApiJson.MAX_RANGE_DAYS) {
            "The range spans $count days, at most ${WellnessApiJson.MAX_RANGE_DAYS} are served"
        }
        require(days.size.toLong() == count) { "days has ${days.size} entries, the range has $count days" }
        for ((index, day) in days.withIndex()) {
            require(
                PlanDates.epochDay(day.date) == first + index,
            ) { "days[$index] is not day ${index + 1} of the range" }
        }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant" }
        require(nightsLogged >= 0) { "nightsLogged is negative" }
        require(nightsNeeded >= 1) { "nightsNeeded is below 1" }
        require(baselineDays >= 1) { "baselineDays is below 1" }
    }
}

// ---- Recovery ---------------------------------------------------------------------------------------------

/** The recovery state of a morning. */
@Serializable
public enum class RecoveryState {
    @SerialName("ready")
    READY,

    @SerialName("caution")
    CAUTION,

    @SerialName("rest")
    REST,

    @SerialName("learning")
    LEARNING,

    @SerialName("noData")
    NO_DATA,
}

/** The row of the rule table that decided the state (ADR 0023). */
@Serializable
public enum class RecoveryRule {
    R1,
    R2,
    C1,
    C2,
    C3,
    OK,
}

/** A metric that can be a fact of the recovery answer. */
@Serializable
public enum class RecoveryMetric {
    @SerialName("restingHr")
    RESTING_HR,

    @SerialName("sleepSeconds")
    SLEEP_SECONDS,

    @SerialName("bedtimeMinutes")
    BEDTIME_MINUTES,

    @SerialName("respiratoryRate")
    RESPIRATORY_RATE,

    @SerialName("spo2")
    SPO2,

    @SerialName("sleepingHr")
    SLEEPING_HR,

    @SerialName("hrvRmssd")
    HRV_RMSSD,
}

/** The unit of a fact's value. */
@Serializable
public enum class RecoveryUnit {
    @SerialName("bpm")
    BPM,

    @SerialName("s")
    SECONDS,

    @SerialName("min")
    MINUTES,

    @SerialName("/min")
    PER_MINUTE,

    @SerialName("%")
    PERCENT,

    @SerialName("ms")
    MILLISECONDS,
}

/** One fact behind the state, in decisive order. [value] is null when there is no reading. */
@Serializable
public data class RecoveryFact(
    val metric: RecoveryMetric,
    val label: String,
    val value: Double?,
    val unit: RecoveryUnit,
    val baselineLow: Double?,
    val baselineHigh: Double?,
    val deviation: Deviation?,
    val streak: Int,
) {
    init {
        require(label.length in 1..WellnessChecks.MAX_LABEL) { "label must have 1 to 40 characters" }
        // The same rules as a Reading; the source is not part of a fact.
        Reading(
            value,
            if (value ==
                null
            ) {
                null
            } else {
                WellnessSourceId.HEALTH_CONNECT
            },
            baselineLow,
            baselineHigh,
            deviation,
            streak,
        )
    }
}

/**
 * The answer to `GET /v1/recovery`: the state of the morning [date] with the [reason] and the [facts] behind it. A verdict
 * (ready, caution, rest) has a [rule], a [guidance] sentence and facts; learning and noData have neither rule nor guidance;
 * noData has no facts.
 */
@Serializable
public data class RecoveryResponse(
    val date: String,
    val state: RecoveryState,
    val reason: String,
    val guidance: String?,
    val rule: RecoveryRule?,
    val nightsLogged: Int,
    val nightsNeeded: Int,
    val generatedAt: String,
    val stale: Boolean,
    val facts: List<RecoveryFact>,
) {
    init {
        require(WellnessTime.isDate(date)) { "date is not a real calendar date" }
        require(reason.length in 1..WellnessChecks.MAX_REASON) { "reason must have 1 to 300 characters" }
        require(guidance == null || guidance.length in 1..WellnessChecks.MAX_GUIDANCE) {
            "guidance must have 1 to 120 characters"
        }
        require(nightsLogged >= 0) { "nightsLogged is negative" }
        require(nightsNeeded >= 1) { "nightsNeeded is below 1" }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant" }
        require(facts.size <= WellnessChecks.MAX_FACTS) { "At most 4 facts" }
        require(facts.map { it.metric }.toSet().size == facts.size) { "A metric is listed twice" }
        val verdict = state == RecoveryState.READY || state == RecoveryState.CAUTION || state == RecoveryState.REST
        if (verdict) {
            require(rule != null) { "rule is needed for a verdict" }
            require(guidance != null) { "guidance is needed for a verdict" }
            require(facts.isNotEmpty()) { "A verdict needs at least one fact" }
            require(
                nightsLogged >= nightsNeeded,
            ) { "nightsLogged is below nightsNeeded: that is learning, not a verdict" }
        } else {
            require(rule == null) { "rule must be null without a verdict" }
            require(guidance == null) { "guidance must be null without a verdict" }
        }
        require(state != RecoveryState.NO_DATA || facts.isEmpty()) { "facts must be empty for noData" }
        require(state != RecoveryState.READY || rule == RecoveryRule.OK) { "rule must be OK for ready" }
        require(state != RecoveryState.CAUTION || rule in WellnessChecks.CAUTION_RULES) {
            "rule must be C1, C2 or C3 for caution"
        }
        require(state != RecoveryState.REST || rule in WellnessChecks.REST_RULES) { "rule must be R1 or R2 for rest" }
    }
}

// ---- Activities from the watch -----------------------------------------------------------------------------

/** How dense the heart-rate samples of an activity are. */
@Serializable
public data class HeartRateStats(
    val samples: Int,
    val medianGapSeconds: Double?,
    val maxGapSeconds: Double?,
    val coverage: Double,
) {
    init {
        require(samples >= 0) { "samples is negative" }
        require(medianGapSeconds == null || medianGapSeconds >= 0.0) { "medianGapSeconds is negative" }
        require(maxGapSeconds == null || maxGapSeconds >= 0.0) { "maxGapSeconds is negative" }
        require(coverage in 0.0..1.0) { "coverage is not within 0..1" }
    }
}

/** One point of an activity's heart-rate series: [t] seconds from the start. */
@Serializable
public data class HeartRatePoint(
    val t: Int,
    val heartRate: Double,
) {
    init {
        require(t >= 0) { "t is negative" }
        require(heartRate in 1.0..WellnessChecks.MAX_HR) { "heartRate is not within 1..300" }
    }
}

/** The answer to `GET /v1/activities/{id}` (`activity.schema.json`); its first part is the row of a history list. */
@Serializable
public data class Activity(
    val id: String,
    val kind: String,
    val source: String,
    val type: String,
    val name: String,
    val start: String,
    val end: String,
    val startDate: String,
    val durationSeconds: Int,
    val averageHeartRate: Double?,
    val maxHeartRate: Int?,
    val load: Double?,
    val loadApproximate: Boolean,
    val countsTowardLoad: Boolean,
    val overlapsRideId: String?,
    val loadNote: String,
    val thresholdHr: Int?,
    val maxHr: Int?,
    val heartRate: HeartRateStats,
    val series: List<HeartRatePoint>,
    val generatedAt: String,
) {
    init {
        require(WellnessChecks.activityId.matches(id)) { "id is not watch-<record id>" }
        require(kind == "activity") { "kind must be activity" }
        require(source == "watch") { "source must be watch" }
        require(WellnessChecks.exerciseType.matches(type)) { "type is not a lower-case token" }
        require(name.length in 1..WellnessChecks.MAX_TITLE) { "name must have 1 to 100 characters" }
        val from = WellnessChecks.instant(start, "start")
        val to = WellnessChecks.instant(end, "end")
        require(to > from) { "end is not after start" }
        require(durationSeconds.toLong() == (to - from + 500) / 1000) {
            "durationSeconds is not the difference of start and end"
        }
        require(WellnessTime.isDate(startDate)) { "startDate is not a real calendar date" }
        require(averageHeartRate == null || averageHeartRate in 1.0..WellnessChecks.MAX_HR) {
            "averageHeartRate is not within 1..300"
        }
        require(
            maxHeartRate == null || maxHeartRate in 1..WellnessChecks.MAX_BPM,
        ) { "maxHeartRate is not within 1..300" }
        require(load == null || load >= 0.0) { "load is negative" }
        require(
            !(load != null && !countsTowardLoad),
        ) { "load must be null when the activity does not count toward load" }
        require(
            countsTowardLoad == (overlapsRideId == null),
        ) { "overlapsRideId is set exactly when countsTowardLoad is false" }
        require(overlapsRideId == null || overlapsRideId.length in 1..WellnessChecks.MAX_RIDE_ID) {
            "overlapsRideId is not a ride id"
        }
        require(loadNote.length in 1..WellnessChecks.MAX_REASON) { "loadNote must have 1 to 300 characters" }
        require(thresholdHr == null || thresholdHr in 1..WellnessChecks.MAX_BPM) { "thresholdHr is not within 1..300" }
        require(maxHr == null || maxHr in 1..WellnessChecks.MAX_BPM) { "maxHr is not within 1..300" }
        require(series.size <= WellnessChecks.MAX_SERIES) { "series has more than 300 points" }
        for (i in 1 until series.size) require(series[i].t > series[i - 1].t) { "series is not in order of t" }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant" }
    }
}

/** The checks the records and answers share. */
internal object WellnessChecks {
    const val MIN_BPM = 1
    const val MAX_BPM = 300
    const val MAX_HR = 300.0
    const val MAX_RATE = 1000.0
    const val MAX_PERCENT = 100.0
    const val MAX_HRV = 1000.0
    const val MAX_STEPS = 1_000_000
    const val MAX_RECORD_SAMPLES = 20_000
    const val MAX_SESSION_SAMPLES = 50_000
    const val MAX_STAGES = 2000
    const val MAX_TITLE = 100
    const val MAX_DEVICE = 64
    const val MAX_LABEL = 40
    const val MAX_REASON = 300
    const val MAX_GUIDANCE = 120
    const val MAX_FACTS = 4
    const val MAX_SERIES = 300
    const val MAX_RIDE_ID = 128
    private const val MAX_OFFSET = 64_800
    val CAUTION_RULES = setOf(RecoveryRule.C1, RecoveryRule.C2, RecoveryRule.C3)
    val REST_RULES = setOf(RecoveryRule.R1, RecoveryRule.R2)

    private val recordId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    val exerciseType = Regex("^[a-z][a-z0-9_]{0,39}$")
    val activityId = Regex("^watch-[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun instant(
        text: String,
        what: String,
    ): Long = requireNotNull(WellnessTime.parseInstant(text)) { "$what is not a real UTC instant" }

    fun common(
        id: String,
        lastModified: String?,
        zoneOffsetSeconds: Int?,
    ) {
        require(recordId.matches(id)) { "id is not 1 to 100 characters from A-Z a-z 0-9 . _ -" }
        if (lastModified != null) instant(lastModified, "lastModified")
        require(zoneOffsetSeconds == null || zoneOffsetSeconds in -MAX_OFFSET..MAX_OFFSET) {
            "zoneOffsetSeconds is not within -64800..64800"
        }
    }

    fun span(
        start: String,
        end: String,
        mustBeLonger: Boolean,
    ) {
        val from = instant(start, "start")
        val to = instant(end, "end")
        require(if (mustBeLonger) to > from else to >= from) {
            if (mustBeLonger) "end is not after start" else "end is before start"
        }
    }

    fun title(title: String?) {
        require(title == null || title.length in 1..MAX_TITLE) { "title must have 1 to 100 characters" }
    }
}
