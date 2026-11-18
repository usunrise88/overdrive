package app.overdrive.core.insights

import app.overdrive.core.plan.PlanDates
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The written insights of the sync API (spec §6.5, §6.6, §12.3, §8.3, ADR 0025): the answer of `GET /v1/insights?from&to` and the
 * discussion topic a ride note may carry. Every model is as strict as its schema in `contracts/schema/` (`insights-response`,
 * `discussion-topic`) and checks the rules the schema cannot state, so an unknown key, a missing one, a value out of range or a note
 * that breaks a rule is an [IllegalArgumentException] when it is read. Messages name a field and the rule, never the text of a note.
 */

/** The longest range `GET /v1/insights` serves, in days, both ends included. */
public const val MAX_INSIGHTS_RANGE_DAYS: Int = 366

/** What the models check; the limits are those of the schemas. */
internal object InsightsChecks {
    const val MAX_TEXT = 1500
    const val MAX_TOPIC_TEXT = 300
    const val MAX_QUESTION = 400
    val RIDE_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
}

/**
 * The kind of a note: [MORNING], [RIDE] and [WEEK] are the notes of spec §6.5 (the companion's Today and Notes labels them "Morning
 * note", "After your ride", "Weekly review"); [DEVIATION] and [DRIFT] are the alerts of spec §6.6, written to the same list.
 */
@Serializable
public enum class InsightKind {
    @SerialName("morning")
    MORNING,

    @SerialName("ride")
    RIDE,

    @SerialName("week")
    WEEK,

    /** A metric outside the rider's own baseline for several days in a row, and what that usually means for training. */
    @SerialName("deviation")
    DEVIATION,

    /** Missed sessions, or load running far from the plan. */
    @SerialName("drift")
    DRIFT,
}

/** Where a discussion topic stands: open until the coach marks it discussed or 14 days pass. */
@Serializable
public enum class TopicStatus {
    @SerialName("open")
    OPEN,

    @SerialName("discussed")
    DISCUSSED,

    @SerialName("expired")
    EXPIRED,
}

/** A reason to talk to the coach about a ride (spec §12.3): what stood out, and a question to open the chat with. */
@Serializable
public data class DiscussionTopic(
    val id: Long,
    val text: String,
    val openingQuestion: String,
    val rideId: String,
    val createdAt: String,
    val status: TopicStatus,
) {
    init {
        require(id >= 1) { "id is below 1" }
        require(text.length in 1..InsightsChecks.MAX_TOPIC_TEXT) { "text must have 1 to 300 characters" }
        require(openingQuestion.length in 1..InsightsChecks.MAX_QUESTION) {
            "openingQuestion must have 1 to 400 characters"
        }
        require(InsightsChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        require(WellnessTime.isUtcInstant(createdAt)) { "createdAt is not a UTC instant" }
    }
}

/** One written note. [rideId] is set exactly for a [InsightKind.RIDE] note; only a ride note has a [topic]. */
@Serializable
public data class Insight(
    val id: Long,
    val kind: InsightKind,
    val createdAt: String,
    val text: String,
    val rideId: String?,
    val topic: DiscussionTopic?,
) {
    init {
        require(id >= 1) { "id is below 1" }
        require(WellnessTime.isUtcInstant(createdAt)) { "createdAt is not a UTC instant" }
        require(text.length in 1..InsightsChecks.MAX_TEXT) { "text must have 1 to 1500 characters" }
        require(rideId == null || InsightsChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        require((kind == InsightKind.RIDE) == (rideId != null)) { "rideId is set exactly for kind ride" }
        if (topic != null) {
            require(kind == InsightKind.RIDE) { "topic must be null unless kind is ride" }
            require(topic.rideId == rideId) { "topic.rideId is not the note's rideId" }
        }
    }
}

/**
 * The answer to `GET /v1/insights?from&to`: the notes created on the local days [from] to [to] (both included, at most 366 days),
 * newest first. [stale] is always false: the notes are the service's own rows.
 */
@Serializable
public data class InsightsResponse(
    val from: String,
    val to: String,
    val generatedAt: String,
    val stale: Boolean,
    val insights: List<Insight>,
) {
    init {
        val first = PlanDates.epochDay(from)
        val last = PlanDates.epochDay(to)
        require(first != null && last != null) { "from and to must be real calendar dates" }
        require(first <= last) { "from is after to" }
        require(last - first + 1 <= MAX_INSIGHTS_RANGE_DAYS) { "The range spans more than 366 days" }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a UTC instant" }
        require(!stale) { "stale must be false" }
        require(insights.map { it.id }.toSet().size == insights.size) { "A note id is used twice" }
        for ((index, pair) in insights.zipWithNext().withIndex()) {
            val (newer, older) = pair
            val newerAt = WellnessTime.parseInstant(newer.createdAt) ?: error("createdAt")
            val olderAt = WellnessTime.parseInstant(older.createdAt) ?: error("createdAt")
            require(newerAt > olderAt || (newerAt == olderAt && newer.id > older.id)) {
                "insights[${index + 1}] is newer than the note before it: newest first"
            }
        }
    }
}
