package app.overdrive.core.powerskills

import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.RiderWeight
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * The power skills of the sync API (spec §6.3, §8.3, docs/adr/0042, design/companion.md §4.14, §6.11): `GET /v1/power-skills`, the best
 * power at twelve durations over a window and over all time, and a level for each of five groups of durations. The model is as strict
 * as `contracts/schema/power-skills-response.schema.json` and checks the rules the schema cannot state (its description lists them), so
 * an unknown key, a missing one, a value out of range or an answer that breaks a rule is an [IllegalArgumentException] when it is read.
 * Messages name a field and the rule, never a figure. **The phone computes none of it**: the scores and the levels are the service's.
 */

/** The twelve durations of the power skills, in seconds, in the order the answer has them. */
public val POWER_SKILL_DURATIONS: List<Int> = listOf(15, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 2700, 3600)

/** The window of the request when none is asked for: eight weeks, in days. */
public const val POWER_SKILLS_DEFAULT_DAYS: Int = 56

/** The longest window the service serves, in days with both ends included: three years. */
public const val POWER_SKILLS_MAX_DAYS: Int = 1096

/** The nine levels' words, level 1 first (design/companion.md §6.11). */
public val POWER_LEVEL_NAMES: List<String> =
    listOf("Starting", "Casual", "Regular", "Fit", "Strong", "Very strong", "Racer", "Elite", "World class")

/** The five skills, in the order they are shown. */
@Serializable
public enum class PowerSkillId(
    /** Our own name for it. */
    public val title: String,
    /** The shortest and the longest of its durations, in seconds. */
    public val fromSeconds: Int,
    public val toSeconds: Int,
) {
    @SerialName("sprint")
    SPRINT("Sprint", 15, 30),

    @SerialName("attack")
    ATTACK("Attack", 60, 180),

    @SerialName("climb")
    CLIMB("Climb", 300, 600),

    @SerialName("time-trial")
    TIME_TRIAL("Time trial", 900, 1800),

    @SerialName("endurance")
    ENDURANCE("Endurance", 2700, 3600),
    ;

    /** The durations of the twelve that belong to this skill. */
    public val durations: List<Int> get() = POWER_SKILL_DURATIONS.filter { it in fromSeconds..toSeconds }
}

/** What the models check; the limits are those of the schema. */
internal object PowerSkillChecks {
    val RIDE_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    const val MAX_WATTS = 3000.0
    const val MAX_WATTS_PER_KG = 100.0
    const val MAX_SCORE = 9.0
    const val MAX_NAME = 200
    const val MAX_NOTE = 400
    const val MAX_WEIGHT_KG = 500.0

    /** A figure in watts per kilogram is rounded to the hundredth, from the rounded weight. */
    const val WATTS_PER_KG_TOLERANCE = 0.0051

    fun requireDate(
        text: String,
        what: String,
    ) = require(WellnessTime.isDate(text)) { "$what is not a real calendar date" }
}

/**
 * The best power at one duration, with where it came from. [wattsPerKg] and [score] are null exactly when the answer has no rider
 * weight. [score] is the watts per kilogram placed on this duration's level scale, 0 to 9 (1 is the floor of level 1, 9 the floor of
 * level 9): what the radar draws.
 */
@Serializable
public data class PowerSkillEffort(
    val watts: Double,
    val wattsPerKg: Double?,
    val score: Double?,
    /** The rider's local day of the ride. */
    val date: String,
    val rideId: String,
    /** The name of the workout the ride was played from, or "Free ride". */
    val rideName: String,
) {
    init {
        require(watts in 0.0..PowerSkillChecks.MAX_WATTS) { "watts is not within 0..3000" }
        require(wattsPerKg == null || wattsPerKg in 0.0..PowerSkillChecks.MAX_WATTS_PER_KG) {
            "wattsPerKg is not within 0..100"
        }
        require(score == null || score in 0.0..PowerSkillChecks.MAX_SCORE) { "score is not within 0..9" }
        PowerSkillChecks.requireDate(date, "date")
        require(PowerSkillChecks.RIDE_ID.matches(rideId)) { "rideId is not a ride id" }
        require(rideName.length in 1..PowerSkillChecks.MAX_NAME) { "rideName must have 1 to 200 characters" }
    }
}

/** The best at one of the twelve durations: in the window, and over all time up to the window's end. Null where no ride is long enough. */
@Serializable
public data class PowerSkillDuration(
    val seconds: Int,
    val window: PowerSkillEffort?,
    val allTime: PowerSkillEffort?,
) {
    init {
        require(seconds in POWER_SKILL_DURATIONS) { "seconds is not one of the twelve durations" }
        if (window != null) {
            require(allTime != null) { "allTime is needed when there is a window effort" }
            require(allTime.watts >= window.watts) { "allTime is lower than the window's best" }
        }
    }
}

/** A skill's level, 1 to 9, with its word. */
@Serializable
public data class PowerSkillLevel(
    val index: Int,
    val name: String,
) {
    init {
        require(index in 1..POWER_LEVEL_NAMES.size) { "index is not within 1..9" }
        require(name == POWER_LEVEL_NAMES[index - 1]) { "name is not the word of level $index" }
    }
}

/** One of the five skills; [level] is null when the weight is not known or the window has no effort at any of its durations. */
@Serializable
public data class PowerSkill(
    val id: PowerSkillId,
    val name: String,
    val fromSeconds: Int,
    val toSeconds: Int,
    val level: PowerSkillLevel?,
) {
    init {
        require(name == id.title && fromSeconds == id.fromSeconds && toSeconds == id.toSeconds) {
            "the skill's name and range are not the contract's"
        }
    }
}

/** The window asked for: the local days from [from] to [to], both included, and the rides in it that have a power curve. */
@Serializable
public data class PowerSkillsWindowInfo(
    val from: String,
    val to: String,
    val rideCount: Int,
) {
    init {
        PowerSkillChecks.requireDate(from, "from")
        PowerSkillChecks.requireDate(to, "to")
        require(rideCount >= 0) { "rideCount is negative" }
        val days = PlanCalendar.daysBetween(from, to) + 1
        require(days >= 1) { "from is after to" }
        require(days <= POWER_SKILLS_MAX_DAYS) { "the window is longer than $POWER_SKILLS_MAX_DAYS days" }
    }
}

/** The answer to `GET /v1/power-skills`. */
@Serializable
public data class PowerSkillsResponse(
    val generatedAt: String,
    /** The table the levels were read against. */
    val table: PowerProfileTable,
    val window: PowerSkillsWindowInfo,
    /** The twelve durations, in [POWER_SKILL_DURATIONS] order. */
    val durations: List<PowerSkillDuration>,
    /** The rider's 7-day average weight, or null when the service does not know it: then there are no scores and no levels. */
    val riderWeight: RiderWeight?,
    /** The five skills, in order. */
    val skills: List<PowerSkill>,
    /** Sentences for readers of the answer with no words of their own; the companion writes its own (design/companion.md §4.14). */
    val levelsNote: String,
    val outdoorNote: String,
) {
    init {
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a real UTC instant" }
        require(riderWeight == null || riderWeight.kg < PowerSkillChecks.MAX_WEIGHT_KG) { "riderWeight.kg is too high" }
        require(levelsNote.length in 1..PowerSkillChecks.MAX_NOTE) { "levelsNote must have 1 to 400 characters" }
        require(outdoorNote.length in 1..PowerSkillChecks.MAX_NOTE) { "outdoorNote must have 1 to 400 characters" }
        require(durations.size == POWER_SKILL_DURATIONS.size) { "durations must have the twelve durations" }
        require(skills.size == PowerSkillId.entries.size) { "skills must have the five skills" }
        for ((index, row) in durations.withIndex()) {
            require(
                row.seconds == POWER_SKILL_DURATIONS[index],
            ) { "durations/$index is not the duration the contract puts there" }
            for ((key, effort) in listOf("window" to row.window, "allTime" to row.allTime)) {
                if (effort == null) continue
                if (key == "window") {
                    require(
                        effort.date >= window.from && effort.date <= window.to,
                    ) { "durations/$index/window/date is outside the window" }
                } else {
                    require(effort.date <= window.to) { "durations/$index/allTime/date is after the end of the window" }
                }
                if (riderWeight == null) {
                    require(
                        effort.wattsPerKg == null && effort.score == null,
                    ) { "durations/$index/$key has figures that need a weight" }
                } else {
                    require(effort.wattsPerKg != null && effort.score != null) {
                        "durations/$index/$key needs wattsPerKg and score with a weight"
                    }
                    val wanted = (effort.watts / riderWeight.kg * HUNDRED).roundToLong() / HUNDRED
                    require(abs(effort.wattsPerKg - wanted) <= PowerSkillChecks.WATTS_PER_KG_TOLERANCE) {
                        "durations/$index/$key/wattsPerKg is not watts over the weight"
                    }
                }
            }
            if (window.rideCount ==
                0
            ) {
                require(row.window == null) { "durations/$index/window has an effort with no ride in the window" }
            }
        }
        for ((index, skill) in skills.withIndex()) {
            require(
                skill.id == PowerSkillId.entries[index],
            ) { "skills/$index is not the skill the contract puts there" }
            val scores =
                skill.id.durations.mapNotNull { seconds ->
                    durations.first { it.seconds == seconds }.window?.score
                }
            val judged = riderWeight != null && scores.isNotEmpty()
            val level = skill.level
            if (level == null) {
                require(!judged) { "skills/$index/level is needed: there is a weight and a window effort" }
            } else {
                require(judged) { "skills/$index/level needs a weight and a window effort at one of its durations" }
                require(level.index == levelIndexOf(scores)) {
                    "skills/$index/level/index is not the mean of the scores of its durations, rounded down"
                }
            }
        }
    }

    /** The row of [seconds]: one of the twelve durations. @throws NoSuchElementException for any other. */
    public fun duration(seconds: Int): PowerSkillDuration = durations.first { it.seconds == seconds }

    /** The skill [id]. */
    public fun skill(id: PowerSkillId): PowerSkill = skills.first { it.id == id }
}

/** The level of a skill from the scores of its durations: their mean rounded down, held between 1 and 9. The service's rule, kept for checks. */
internal fun levelIndexOf(scores: List<Double>): Int =
    min(
        POWER_LEVEL_NAMES.size,
        max(
            1,
            floor(scores.sum() / scores.size + LEVEL_EPSILON).toInt(),
        ),
    )

private const val LEVEL_EPSILON = 1e-9
private const val HUNDRED = 100.0
