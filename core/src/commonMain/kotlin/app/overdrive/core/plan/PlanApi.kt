package app.overdrive.core.plan

import app.overdrive.core.workout.Workout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a planned workout comes from (`contracts/schema/workouts-response.schema.json`). */
@Serializable
public enum class PlanSource {
    @SerialName("intervals.icu")
    INTERVALS_ICU,
}

/**
 * One workout of the plan, already converted to the native format. [id] is `icu-123` for intervals.icu event 123 and
 * equals [workout]`.id`, so a ride recorded from it can carry that id as `ride.workoutId`. [date] is the day it is planned
 * for, `YYYY-MM-DD`, in the rider's local time. [name] is the calendar title, the same as `workout.name` today.
 */
@Serializable
public data class PlannedWorkout(
    val id: String,
    val date: String,
    val name: String,
    val source: PlanSource,
    val workout: Workout,
    /**
     * The structure hash of [workout] (16 lower-case hex characters) that the service keys the repeated sessions by (spec §11.7, ADR 0026),
     * worked out by the **service** from the workout as it serves it; the tablet never computes it (docs/adr/0034). Null from a service that
     * predates it: the previous-attempt line then has nothing to ask for.
     */
    val structureHash: String? = null,
) {
    init {
        require(structureHash == null || STRUCTURE_HASH.matches(structureHash)) {
            "A structure hash is 16 lower-case hex characters"
        }
        require(PlanApiJson.isValidPlannedWorkoutId(id)) { "Not a planned workout id: '$id'" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
        require(name.isNotEmpty()) { "A planned workout needs a name" }
        require(workout.id == id) { "Workout id '${workout.id}' differs from the planned workout's id '$id'" }
    }
}

private val STRUCTURE_HASH = Regex("^[0-9a-f]{16}$")

/**
 * The service's answer to `GET /v1/workouts?from&to` (spec §5.1, §8.3, ADR 0016): the planned workouts from [from] to [to]
 * inclusive (at most [PlanApiJson.MAX_RANGE_DAYS] days), ordered by date then id.
 *
 * - [generatedAt]: when the service last read the plan from its source successfully (UTC, ISO-8601 with a Z), or null.
 * - [stale]: the last attempt to refresh failed, so [workouts] are the last known ones and may be out of date.
 * - [configured]: false when the service has no plan source; [workouts] is then empty, [stale] false and [generatedAt] null.
 */
@Serializable
public data class WorkoutsResponse(
    val from: String,
    val to: String,
    val generatedAt: String?,
    val stale: Boolean,
    val configured: Boolean,
    val workouts: List<PlannedWorkout>,
) {
    init {
        val first = PlanDates.epochDay(from)
        val last = PlanDates.epochDay(to)
        require(first != null && last != null) { "from and to must be real calendar dates: '$from', '$to'" }
        require(last >= first) { "from $from is after to $to" }
        val days = last - first + 1
        require(days <= PlanApiJson.MAX_RANGE_DAYS) {
            "The range spans $days days, at most ${PlanApiJson.MAX_RANGE_DAYS} are served"
        }
        require(generatedAt == null || PlanApiJson.isValidInstant(generatedAt)) {
            "generatedAt is not a UTC instant: '$generatedAt'"
        }
        val ids = HashSet<String>()
        for (entry in workouts) {
            val day = checkNotNull(PlanDates.epochDay(entry.date))
            require(day in first..last) { "${entry.id} is planned for ${entry.date}, outside $from to $to" }
            require(ids.add(entry.id)) { "${entry.id} is listed twice" }
        }
        if (!configured) {
            require(workouts.isEmpty()) { "A service that is not configured has no workouts" }
            require(!stale) { "A service that is not configured is not stale" }
            require(generatedAt == null) { "A service that is not configured has no generatedAt" }
        }
    }
}

/** Calendar dates as the plan API writes them: `YYYY-MM-DD`, a real date in a four-digit year from 1000. */
public object PlanDates {
    private val pattern = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    /** Days since 1970-01-01 of [date], or null when it is not a real calendar date. */
    public fun epochDay(date: String): Long? {
        val match = pattern.matchEntire(date) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (year < MIN_YEAR || month !in 1..MONTHS_IN_YEAR || day < 1 || day > daysInMonth(year, month)) return null
        // Days from civil (proleptic Gregorian), after Howard Hinnant's algorithm.
        val y = if (month <= 2) year - 1 else year
        val era = y / YEARS_PER_ERA
        val yearOfEra = y - era * YEARS_PER_ERA
        val shiftedMonth = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * DAYS_PER_ERA + dayOfEra - DAYS_FROM_YEAR_0_TO_EPOCH
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int =
        when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

    private const val MIN_YEAR = 1000
    private const val MONTHS_IN_YEAR = 12
    private const val YEARS_PER_ERA = 400
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_FROM_YEAR_0_TO_EPOCH = 719_468L
}

/**
 * Reads and writes the planned workouts answer (spec §8.3). Strict like the schema: unknown keys, a source other than
 * `intervals.icu`, an id outside the accepted syntax and every rule of the schema's description are rejected with an
 * [IllegalArgumentException].
 */
public object PlanApiJson {
    /** The longest range `GET /v1/workouts` serves, in days, both ends included. */
    public const val MAX_RANGE_DAYS: Int = 62

    private val json = Json

    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val instantPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$")

    /** Planned workout ids: 1 to 128 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit. */
    public fun isValidPlannedWorkoutId(id: String): Boolean = idPattern.matches(id)

    /** A UTC instant as `generatedAt` writes it: `2026-11-19T07:30:12Z`, with optional milliseconds. */
    public fun isValidInstant(text: String): Boolean = instantPattern.matches(text)

    /** @throws IllegalArgumentException if [text] is not a planned workouts answer of the schema. */
    public fun decodeWorkoutsResponse(text: String): WorkoutsResponse =
        json.decodeFromString(WorkoutsResponse.serializer(), text)

    public fun encodeWorkoutsResponse(response: WorkoutsResponse): String =
        json.encodeToString(WorkoutsResponse.serializer(), response)
}
