package app.overdrive.core.repeats

import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/*
 * Repeated sessions of the sync API (spec §11.7, §8.3, ADR 0026): `GET /v1/repeats`, `GET /v1/repeats/{hash}` and
 * `GET /v1/repeats/{hash}/previous`. Every model is as strict as its schema in `contracts/schema/` (`repeats-response`,
 * `repeat-detail-response`, `repeat-previous-response`) and checks the rules the schemas cannot state, so an unknown key, a
 * missing one, a value out of range or an answer that breaks a rule is an [IllegalArgumentException] when it is read. Messages
 * name a field and the rule, never a figure.
 */

/** What the models check; the limits are those of the schemas. */
internal object RepeatChecks {
    val HASH: Regex = Regex("^[0-9a-f]{16}$")
    val RIDE_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    const val MAX_NAME = 100
    const val MAX_REASON = 300
    const val MAX_NOTE = 400
    const val MAX_ROUTE_KEY = 64
    const val MIN_FTP = 30
    const val MAX_FTP = 2000
    const val MAX_HR = 300.0
    const val MAX_STEP_FRACTION = 3.0
    const val MAX_EFFORT_RATING = 10.0
    const val MAX_PERCENT = 100.0
    const val MAX_UNIT = 20
    const val MIN_ATTEMPTS = 2

    /** The difference of two figures that are rounded to one decimal each is within this of their exact difference. */
    const val DIFFERENCE_TOLERANCE = 0.0501

    /** `change` is `slopePerAttempt * (attempts - 1)`: the slope is rounded to a thousandth and the change to a hundredth. */
    const val CHANGE_SLOPE_ROUNDING = 0.0005
    const val CHANGE_ROUNDING = 0.0051

    fun requireDate(
        text: String,
        what: String,
    ) = require(WellnessTime.isDate(text)) { "$what is not a real calendar date" }

    fun requireInstant(
        text: String,
        what: String,
    ) = require(WellnessTime.isUtcInstant(text)) { "$what is not a real UTC instant" }

    fun requireReason(
        reason: String?,
        what: String,
    ) = require(reason == null || reason.length in 1..MAX_REASON) { "$what must have 1 to 300 characters" }

    fun requireHeartRate(
        value: Double?,
        what: String,
    ) = require(value == null || value in 1.0..MAX_HR) { "$what is not within 1..300" }
}

/** A hash identifies a session: 16 lower-case hex characters. */
public fun isRepeatHash(text: String): Boolean = RepeatChecks.HASH.matches(text)

// ---- The list ---------------------------------------------------------------------------------------------------------

/** One figure of the latest attempt against the previous one: [difference] is latest minus previous, or null with a [reason]. */
@Serializable
public data class RepeatChange(
    val latest: Double?,
    val previous: Double?,
    val difference: Double?,
    val reason: String?,
) {
    init {
        RepeatChecks.requireReason(reason, "reason")
        if (latest != null && previous != null) {
            require(difference != null) { "difference must be set when both figures are" }
            require(abs(difference - (latest - previous)) <= RepeatChecks.DIFFERENCE_TOLERANCE) {
                "difference is not latest minus previous"
            }
            require(reason == null) { "reason must be null when both figures are set" }
        } else {
            require(difference == null) { "difference must be null when a figure is missing" }
            require(reason != null) { "reason is needed when a figure is missing" }
        }
    }
}

/** An attempt named in the list: its ride, the rider's local day and the UTC start. */
@Serializable
public data class RepeatAttemptRef(
    val rideId: String,
    val date: String,
    val startTime: String,
) {
    init {
        require(RepeatChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        RepeatChecks.requireDate(date, "date")
        RepeatChecks.requireInstant(startTime, "startTime")
    }
}

/** The three figures of the list: bpm, percent and percent of the planned time ridden. */
@Serializable
public data class RepeatComparison(
    val heartRateAtPower: RepeatChange,
    val driftPercent: RepeatChange,
    val compliance: RepeatChange,
)

/** One repeated session: [hash] identifies it, [routeKey] is the hook for routes (stage v0.4.0, null today). */
@Serializable
public data class RepeatSummary(
    val hash: String,
    val name: String,
    val routeKey: String?,
    val attempts: Int,
    val firstDate: String,
    val lastDate: String,
    val latest: RepeatAttemptRef,
    val previous: RepeatAttemptRef,
    val comparison: RepeatComparison,
) {
    init {
        require(isRepeatHash(hash)) { "hash is not 16 lower-case hex characters" }
        require(name.length in 1..RepeatChecks.MAX_NAME) { "name must have 1 to 100 characters" }
        require(routeKey == null || routeKey.length in 1..RepeatChecks.MAX_ROUTE_KEY) {
            "routeKey must have 1 to 64 characters"
        }
        require(attempts >= RepeatChecks.MIN_ATTEMPTS) { "attempts is below 2" }
        RepeatChecks.requireDate(firstDate, "firstDate")
        RepeatChecks.requireDate(lastDate, "lastDate")
        require(firstDate <= lastDate) { "firstDate is after lastDate" }
        require(lastDate == latest.date) { "lastDate is not the day of latest" }
        require(firstDate <= previous.date) { "firstDate is after the day of previous" }
        require(latest.rideId != previous.rideId) { "latest and previous are the same ride" }
        require(
            requireNotNull(WellnessTime.parseInstant(previous.startTime)) <=
                requireNotNull(WellnessTime.parseInstant(latest.startTime)),
        ) { "previous started after latest" }
    }
}

/** The answer to `GET /v1/repeats`: the sessions ridden more than once, most recently ridden first, then by hash. */
@Serializable
public data class RepeatsResponse(
    val generatedAt: String,
    val repeats: List<RepeatSummary>,
) {
    init {
        RepeatChecks.requireInstant(generatedAt, "generatedAt")
        require(repeats.map { it.hash }.toSet().size == repeats.size) { "A hash is listed twice" }
        for (i in 1 until repeats.size) {
            val before = repeats[i - 1]
            val here = repeats[i]
            require(here.lastDate < before.lastDate || (here.lastDate == before.lastDate && here.hash > before.hash)) {
                "repeats/$i is out of order: most recently ridden first, then by hash"
            }
        }
    }
}

// ---- The detail -------------------------------------------------------------------------------------------------------

/** The rules the figures follow, fixed by the contract: they are stated in every answer so a reader need not know them. */
@Serializable
public data class RepeatDefinition(
    val powerTolerancePercent: Int,
    val skipSeconds: Int,
    val minStepSeconds: Int,
    val minTrendAttempts: Int,
) {
    init {
        require(powerTolerancePercent == POWER_TOLERANCE_PERCENT) { "powerTolerancePercent must be 3" }
        require(skipSeconds == SKIP_SECONDS) { "skipSeconds must be 60" }
        require(minStepSeconds == MIN_STEP_SECONDS) { "minStepSeconds must be 120" }
        require(minTrendAttempts == MIN_TREND_ATTEMPTS) { "minTrendAttempts must be 3" }
    }

    public companion object {
        /** A second counts when the power is within this percent of the step's target. */
        public const val POWER_TOLERANCE_PERCENT: Int = 3

        /** The first seconds of a step (and after each pause inside it) are left out. */
        public const val SKIP_SECONDS: Int = 60

        /** A step has a heart rate at the same power only with this many counted seconds. */
        public const val MIN_STEP_SECONDS: Int = 120

        /** A trend needs this many attempts with a figure. */
        public const val MIN_TREND_ATTEMPTS: Int = 3

        /** The standing caution of every repeat page (spec §11.7). */
        public const val NOTE: String =
            "Heart rate also moves from day to day with heat, sleep and caffeine, so look at the trend over several " +
                "attempts, not at a single comparison."
    }
}

/** A fixed-power step of the session: its 1-based [step] in the flattened workout, its length and target as a fraction of FTP. */
@Serializable
public data class RepeatStep(
    val step: Int,
    val label: String,
    val durationSeconds: Int,
    val targetFraction: Double,
) {
    init {
        require(step >= 1) { "step is below 1" }
        require(label.length in 1..RepeatChecks.MAX_NAME) { "label must have 1 to 100 characters" }
        require(durationSeconds >= 1) { "durationSeconds is below 1" }
        require(targetFraction > 0.0 && targetFraction <= RepeatChecks.MAX_STEP_FRACTION) {
            "targetFraction is not within 0..3"
        }
    }
}

/** What one attempt did in one fixed-power step: the figure over its counted seconds, or null with a reason. */
@Serializable
public data class RepeatStepHeartRate(
    val step: Int,
    val targetWatts: Double,
    val averagePower: Double?,
    val averageHeartRate: Double?,
    val seconds: Int,
    val reason: String?,
) {
    init {
        require(step >= 1) { "step is below 1" }
        require(targetWatts > 0.0) { "targetWatts is not above 0" }
        require(averagePower == null || averagePower >= 0.0) { "averagePower is negative" }
        RepeatChecks.requireHeartRate(averageHeartRate, "averageHeartRate")
        require(seconds >= 0) { "seconds is negative" }
        RepeatChecks.requireReason(reason, "reason")
        require((averageHeartRate == null) != (reason == null)) { "exactly one of averageHeartRate and reason is set" }
        if (averageHeartRate != null) {
            require(seconds >= RepeatDefinition.MIN_STEP_SECONDS) { "seconds is below 120" }
            require(averagePower != null) { "averagePower is needed with a heart rate" }
        }
    }
}

/** A figure with the reason there is none: exactly one of the two is set. */
@Serializable
public data class RepeatFigure(
    val value: Double?,
    val reason: String?,
) {
    init {
        RepeatChecks.requireReason(reason, "reason")
        require((value == null) != (reason == null)) { "exactly one of value and reason is set" }
    }
}

/** An attempt's overall heart rate at the same power over the session's compared steps, and the seconds behind it. */
@Serializable
public data class RepeatOverallHeartRate(
    val value: Double?,
    val seconds: Int,
    val reason: String?,
) {
    init {
        RepeatChecks.requireHeartRate(value, "value")
        require(seconds >= 0) { "seconds is negative" }
        RepeatChecks.requireReason(reason, "reason")
        require((value == null) != (reason == null)) { "exactly one of value and reason is set" }
        if (value != null) require(seconds > 0) { "seconds must be above 0 with a value" }
    }
}

/** How an attempt went against the plan: the share of the planned time ridden, the steps held for their whole time, and the power. */
@Serializable
public data class RepeatCompliance(
    val percent: Double?,
    val stepsCompleted: Int,
    val stepsPlanned: Int,
    val powerDeltaPercent: Double?,
) {
    init {
        require(percent == null || percent in 0.0..RepeatChecks.MAX_PERCENT) { "percent is not within 0..100" }
        require(stepsCompleted >= 0 && stepsPlanned >= 0) { "a step count is negative" }
        require(stepsCompleted <= stepsPlanned) { "stepsCompleted is more than stepsPlanned" }
    }
}

/** One attempt at a session. */
@Serializable
public data class RepeatAttempt(
    val rideId: String,
    val number: Int,
    val date: String,
    val startTime: String,
    val durationSeconds: Int,
    val ftp: Int,
    val averagePower: Double?,
    val averageHeartRate: Double?,
    val effortRating: Double?,
    val heartRateAtPower: RepeatOverallHeartRate,
    val stepHeartRates: List<RepeatStepHeartRate>,
    val driftPercent: RepeatFigure,
    val compliance: RepeatCompliance,
) {
    init {
        require(RepeatChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        require(number >= 1) { "number is below 1" }
        RepeatChecks.requireDate(date, "date")
        RepeatChecks.requireInstant(startTime, "startTime")
        require(durationSeconds >= 0) { "durationSeconds is negative" }
        require(ftp in RepeatChecks.MIN_FTP..RepeatChecks.MAX_FTP) { "ftp is not within 30..2000" }
        require(averagePower == null || averagePower >= 0.0) { "averagePower is negative" }
        RepeatChecks.requireHeartRate(averageHeartRate, "averageHeartRate")
        require(effortRating == null || effortRating in 0.0..RepeatChecks.MAX_EFFORT_RATING) {
            "effortRating is not within 0..10"
        }
    }
}

/** Which way a measure is moving. */
@Serializable
public enum class RepeatDirection {
    @SerialName("improving")
    IMPROVING,

    @SerialName("worsening")
    WORSENING,

    @SerialName("steady")
    STEADY,

    @SerialName("unknown")
    UNKNOWN,
}

/** Whether a lower or a higher figure is the better one. */
@Serializable
public enum class RepeatBetterWhen {
    @SerialName("lower")
    LOWER,

    @SerialName("higher")
    HIGHER,
}

/**
 * The trend of one measure over the attempts that had a figure: the least-squares [slopePerAttempt] over them in order and the
 * fitted [change] from the first to the last. [direction] is unknown below [minAttempts]; a [change] smaller than [steadyBelow]
 * in size is steady.
 */
@Serializable
public data class RepeatTrend(
    val unit: String,
    val betterWhen: RepeatBetterWhen,
    val attempts: Int,
    val minAttempts: Int,
    val direction: RepeatDirection,
    val slopePerAttempt: Double?,
    val change: Double?,
    val steadyBelow: Double,
    val reason: String?,
) {
    init {
        require(unit.length in 1..RepeatChecks.MAX_UNIT) { "unit must have 1 to 20 characters" }
        require(attempts >= 0) { "attempts is negative" }
        require(minAttempts == RepeatDefinition.MIN_TREND_ATTEMPTS) { "minAttempts must be 3" }
        require(steadyBelow > 0.0) { "steadyBelow is not above 0" }
        RepeatChecks.requireReason(reason, "reason")
        if (attempts < minAttempts) {
            require(direction == RepeatDirection.UNKNOWN) { "direction must be unknown below minAttempts" }
            require(slopePerAttempt == null && change == null) { "no slope or change below minAttempts" }
            require(reason != null) { "reason is needed when the direction is unknown" }
        } else {
            require(direction != RepeatDirection.UNKNOWN) { "direction cannot be unknown with enough attempts" }
            require(reason == null) { "reason must be null when there is a direction" }
            require(slopePerAttempt != null && change != null) { "slopePerAttempt and change are needed" }
            val expected = slopePerAttempt * (attempts - 1)
            require(
                abs(change - expected) <=
                    RepeatChecks.CHANGE_SLOPE_ROUNDING * (attempts - 1) + RepeatChecks.CHANGE_ROUNDING,
            ) { "change is not slopePerAttempt times attempts minus one" }
            val wanted =
                when {
                    abs(change) < steadyBelow -> RepeatDirection.STEADY
                    (change < 0.0) == (betterWhen == RepeatBetterWhen.LOWER) -> RepeatDirection.IMPROVING
                    else -> RepeatDirection.WORSENING
                }
            require(direction == wanted) { "direction does not follow from change, steadyBelow and betterWhen" }
        }
    }
}

/** The three trends of a session. */
@Serializable
public data class RepeatTrends(
    val heartRateAtPower: RepeatTrend,
    val driftPercent: RepeatTrend,
    val compliance: RepeatTrend,
)

/** The answer to `GET /v1/repeats/{hash}`: every attempt at one session side by side, with the trends. */
@Serializable
public data class RepeatDetailResponse(
    val generatedAt: String,
    val hash: String,
    val name: String,
    val routeKey: String?,
    val note: String,
    val definition: RepeatDefinition,
    val steps: List<RepeatStep>,
    val comparedSteps: List<Int>,
    val attempts: List<RepeatAttempt>,
    val trends: RepeatTrends,
) {
    init {
        RepeatChecks.requireInstant(generatedAt, "generatedAt")
        require(isRepeatHash(hash)) { "hash is not 16 lower-case hex characters" }
        require(name.length in 1..RepeatChecks.MAX_NAME) { "name must have 1 to 100 characters" }
        require(routeKey == null || routeKey.length in 1..RepeatChecks.MAX_ROUTE_KEY) {
            "routeKey must have 1 to 64 characters"
        }
        require(note.length in 1..RepeatChecks.MAX_NOTE) { "note must have 1 to 400 characters" }
        require(attempts.size >= RepeatChecks.MIN_ATTEMPTS) { "attempts has fewer than 2" }
        val stepNumbers = steps.map { it.step }
        for (i in 1 until stepNumbers.size) require(stepNumbers[i] > stepNumbers[i - 1]) { "steps/$i is out of order" }
        val known = stepNumbers.toSet()
        for ((i, step) in comparedSteps.withIndex()) {
            require(step in known) { "comparedSteps/$i is not one of steps" }
            if (i > 0) require(step > comparedSteps[i - 1]) { "comparedSteps/$i is out of order" }
        }
        var lastStart = Long.MIN_VALUE
        val rides = mutableSetOf<String>()
        for ((index, attempt) in attempts.withIndex()) {
            val at = "attempts/$index"
            require(attempt.number == index + 1) { "$at/number is not ${index + 1}" }
            require(rides.add(attempt.rideId)) { "$at/rideId is used twice" }
            val start = requireNotNull(WellnessTime.parseInstant(attempt.startTime))
            require(start > lastStart) { "$at is not later than the attempt before it: oldest first" }
            lastStart = start
            require(attempt.stepHeartRates.size == stepNumbers.size) { "$at/stepHeartRates needs one entry per step" }
            for ((i, figure) in attempt.stepHeartRates.withIndex()) {
                require(
                    figure.step == stepNumbers[i],
                ) { "$at/stepHeartRates/$i/step is not the session's step ${i + 1}" }
            }
            val overall = attempt.heartRateAtPower
            if (overall.value != null) {
                require(comparedSteps.isNotEmpty()) { "$at/heartRateAtPower has a value but no step is compared" }
                for (step in comparedSteps) {
                    require(attempt.stepHeartRates[stepNumbers.indexOf(step)].averageHeartRate != null) {
                        "$at/heartRateAtPower has a value but a compared step has no heart rate"
                    }
                }
            }
        }
        requireTrend(
            "trends/heartRateAtPower",
            trends.heartRateAtPower,
            attempts.count {
                it.heartRateAtPower.value !=
                    null
            },
        )
        requireTrend("trends/driftPercent", trends.driftPercent, attempts.count { it.driftPercent.value != null })
        requireTrend("trends/compliance", trends.compliance, attempts.count { it.compliance.percent != null })
    }

    private fun requireTrend(
        at: String,
        trend: RepeatTrend,
        usable: Int,
    ) {
        require(trend.attempts == usable) { "$at/attempts is not the number of attempts that have the figure" }
        require(trend.attempts <= attempts.size) { "$at/attempts is more than there are attempts" }
    }
}

// ---- The previous attempt ---------------------------------------------------------------------------------------------

/** A step the previous attempt rode: where it ran (seconds from that ride's start), its target and its heart rate at that power. */
@Serializable
public data class RepeatPreviousStep(
    val step: Int,
    val label: String,
    val startSeconds: Int,
    val endSeconds: Int,
    val targetWatts: Double?,
    val averageHeartRate: Double?,
) {
    init {
        require(step >= 1) { "step is below 1" }
        require(label.length in 1..RepeatChecks.MAX_NAME) { "label must have 1 to 100 characters" }
        require(startSeconds >= 0) { "startSeconds is negative" }
        require(endSeconds > startSeconds) { "endSeconds is not after startSeconds" }
        require(targetWatts == null || targetWatts > 0.0) { "targetWatts is not above 0" }
        RepeatChecks.requireHeartRate(averageHeartRate, "averageHeartRate")
        require(averageHeartRate == null || targetWatts != null) { "targetWatts is needed with a heart rate" }
    }
}

/** The attempt before a ride, small enough for the tablet's overlay to carry. */
@Serializable
public data class RepeatPreviousAttempt(
    val rideId: String,
    val date: String,
    val startTime: String,
    val durationSeconds: Int,
    val ftp: Int,
    val attempt: Int,
    val heartRateAtPower: Double?,
    val steps: List<RepeatPreviousStep>,
) {
    init {
        require(RepeatChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        RepeatChecks.requireDate(date, "date")
        RepeatChecks.requireInstant(startTime, "startTime")
        require(durationSeconds >= 0) { "durationSeconds is negative" }
        require(ftp in RepeatChecks.MIN_FTP..RepeatChecks.MAX_FTP) { "ftp is not within 30..2000" }
        require(attempt >= 1) { "attempt is below 1" }
        RepeatChecks.requireHeartRate(heartRateAtPower, "heartRateAtPower")
        var end = 0
        var step = 0
        for ((i, entry) in steps.withIndex()) {
            require(entry.step > step) { "steps/$i/step is not after the step before it" }
            require(entry.startSeconds >= end) { "steps/$i starts before the step before it ends" }
            step = entry.step
            end = entry.endSeconds
        }
    }
}

/** The answer to `GET /v1/repeats/{hash}/previous[?before=rideId]`; [previous] is null when there is no earlier attempt. */
@Serializable
public data class RepeatPreviousResponse(
    val generatedAt: String,
    val hash: String,
    val before: String?,
    val previous: RepeatPreviousAttempt?,
) {
    init {
        RepeatChecks.requireInstant(generatedAt, "generatedAt")
        require(isRepeatHash(hash)) { "hash is not 16 lower-case hex characters" }
        require(before == null || RepeatChecks.RIDE_ID.matches(before)) { "before is not a ride id" }
        require(previous == null || before == null || previous.rideId != before) {
            "previous is the ride asked about, not the one before it"
        }
    }
}
