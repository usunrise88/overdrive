package app.overdrive.companion

import app.overdrive.core.effort.EffortActivity
import app.overdrive.core.effort.EffortDay
import app.overdrive.core.effort.EffortKind
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.effort.EffortVerdict
import app.overdrive.core.effort.EffortWeek
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.powerskills.POWER_SKILL_DURATIONS
import app.overdrive.core.powerskills.PowerSkill
import app.overdrive.core.powerskills.PowerSkillDuration
import app.overdrive.core.powerskills.PowerSkillEffort
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.powerskills.PowerSkillLevel
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.powerskills.PowerSkillsWindowInfo
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.RiderWeight
import kotlin.math.floor

/**
 * Synthetic answers for the Power skills and Effort cards in the numbers of the design's mock-ups (`design/mockups/companion`): invented,
 * valid by the contract's own rules. The scores are worked out here from the design's table (design/companion.md §6.11) only so that a
 * sample is a valid answer: the phone itself never holds a table.
 */
object ChartScreenSamples {
    const val TODAY = "2026-11-18"
    const val WEIGHT_KG = 72.0

    /** The floors of level 1 (B) and level 9 (T) at the twelve durations, men's, from the design. */
    private val floorB = listOf(8.2, 6.9, 5.6, 4.2, 3.35, 2.3, 2.2, 2.1, 2.1, 2.0, 1.95, 1.9)
    private val topT = listOf(18.5, 15.0, 11.5, 9.8, 8.85, 7.6, 7.25, 7.05, 6.95, 6.75, 6.55, 6.4)

    /** One best effort: the watts and where it was ridden. */
    class Best(
        val watts: Double,
        val date: String,
        val name: String,
    )

    private fun rideIdOf(
        date: String,
        name: String,
    ) = "ride-$date-${name.lowercase().filter { it.isLetterOrDigit() }.take(12)}"

    /** The 8 weeks' bests of the mock-up: 930 W for 15 s down to 236 W for 60 min. */
    val eightWeeks: List<Best?> =
        listOf(
            Best(930.0, "2026-11-09", "Sprint intervals"),
            Best(705.0, "2026-11-09", "Sprint intervals"),
            Best(478.0, "2026-11-12", "VO2 5×3"),
            Best(372.0, "2026-11-12", "VO2 5×3"),
            Best(341.0, "2026-11-12", "VO2 5×3"),
            Best(316.0, "2026-11-12", "VO2 5×3"),
            Best(289.0, "2026-11-16", "Threshold 2×20"),
            Best(279.0, "2026-11-16", "Threshold 2×20"),
            Best(273.0, "2026-11-16", "Threshold 2×20"),
            Best(259.0, "2026-11-16", "Threshold 2×20"),
            Best(244.0, "2026-10-28", "Endurance 90"),
            Best(236.0, "2026-10-28", "Endurance 90"),
        )

    /** The best ever, up to today: never below the 8 weeks'. */
    val bestEver: List<Best?> =
        listOf(
            Best(1010.0, "2026-10-03", "Sprint intervals"),
            Best(760.0, "2026-10-03", "Sprint intervals"),
            Best(496.0, "2026-10-03", "VO2 5×3"),
            Best(388.0, "2026-10-03", "VO2 5×3"),
            Best(352.0, "2026-10-03", "Climb repeats"),
            Best(330.0, "2026-10-03", "Climb repeats"),
            Best(300.0, "2026-10-03", "Climb repeats"),
            Best(291.0, "2026-10-03", "Threshold 2×20"),
            Best(286.0, "2026-09-26", "Threshold 2×20"),
            Best(270.0, "2026-09-26", "Threshold 2×20"),
            Best(256.0, "2026-09-26", "Endurance 90"),
            Best(247.0, "2026-09-26", "Endurance 90"),
        )

    /** A custom range of the mock-up (2 Nov to 15 Nov): slightly different bests and no ride of 45 or 60 minutes. */
    val custom: List<Best?> =
        listOf(
            Best(905.0, "2026-11-09", "Sprint intervals"),
            Best(690.0, "2026-11-09", "Sprint intervals"),
            Best(462.0, "2026-11-12", "VO2 5×3"),
            Best(360.0, "2026-11-12", "VO2 5×3"),
            Best(333.0, "2026-11-12", "VO2 5×3"),
            Best(309.0, "2026-11-12", "VO2 5×3"),
            Best(281.0, "2026-11-12", "VO2 5×3"),
            Best(270.0, "2026-11-12", "VO2 5×3"),
            Best(266.0, "2026-11-12", "VO2 5×3"),
            Best(251.0, "2026-11-12", "VO2 5×3"),
            null,
            null,
        )

    private fun score(
        index: Int,
        watts: Double,
    ): Double {
        val perKg = watts / WEIGHT_KG
        return (
            Math.round((1 + 8 * (perKg - floorB[index]) / (topT[index] - floorB[index])).coerceIn(0.0, 9.0) * 100) /
                100.0
        )
    }

    private fun effort(
        index: Int,
        best: Best?,
        weight: Boolean,
    ): PowerSkillEffort? =
        best?.let {
            PowerSkillEffort(
                watts = it.watts,
                wattsPerKg = if (weight) Math.round(it.watts / WEIGHT_KG * 100) / 100.0 else null,
                score = if (weight) score(index, it.watts) else null,
                date = it.date,
                rideId = rideIdOf(it.date, it.name),
                rideName = it.name,
            )
        }

    /**
     * An answer for the days [from] to [to]: [window] and [allTime] are the twelve bests (null for no ride that long). Levels are the
     * floor of the mean score of each skill's durations that have a window effort, as the service says.
     */
    fun skills(
        from: String = "2026-09-24",
        to: String = TODAY,
        window: List<Best?> = eightWeeks,
        allTime: List<Best?> = bestEver,
        weight: Boolean = true,
        rideCount: Int = 24,
        table: PowerProfileTable = PowerProfileTable.MEN,
    ): PowerSkillsResponse {
        val durations =
            POWER_SKILL_DURATIONS.mapIndexed { i, seconds ->
                val inWindow = effort(i, window[i], weight)
                val ever = effort(i, allTime[i] ?: window[i], weight)
                PowerSkillDuration(seconds, inWindow, ever)
            }
        val skills =
            PowerSkillId.entries.map { id ->
                val scores =
                    id.durations.mapNotNull { seconds ->
                        durations.first { it.seconds == seconds }.window?.score
                    }
                val level =
                    if (!weight || scores.isEmpty()) {
                        null
                    } else {
                        val index = floor(scores.sum() / scores.size + 1e-9).toInt().coerceIn(1, 9)
                        PowerSkillLevel(index, app.overdrive.core.powerskills.POWER_LEVEL_NAMES[index - 1])
                    }
                PowerSkill(id, id.title, id.fromSeconds, id.toSeconds, level)
            }
        return PowerSkillsResponse(
            generatedAt = "${to}T07:40:00Z",
            table = table,
            window = PowerSkillsWindowInfo(from, to, if (window.all { it == null }) 0 else rideCount),
            durations = durations,
            riderWeight = if (weight) RiderWeight(WEIGHT_KG, to, 6) else null,
            skills = skills,
            levelsNote = "Levels are approximate.",
            outdoorNote = "Only rides on the tablet count here.",
        )
    }

    /** The mock-up's custom range: 2 Nov to 15 Nov, 9 rides, the longest durations without a ride. */
    fun customSkills(weight: Boolean = true): PowerSkillsResponse =
        skills(
            "2026-11-02",
            "2026-11-15",
            custom,
            bestEver.map {
                it?.takeIf { b ->
                    b.date <= "2026-11-15"
                }
            },
            weight,
            rideCount = 9,
        )

    /** Only 15 s and 30 s have an effort: the card is a list. */
    fun fewDurations(): PowerSkillsResponse =
        skills(window = eightWeeks.mapIndexed { i, best -> best.takeIf { i < 2 } }, rideCount = 1)

    /** A window without a ride, with the best ever behind it. */
    fun emptyWindow(
        from: String,
        to: String,
    ): PowerSkillsResponse =
        skills(
            from,
            to,
            List(12) { null },
            bestEver.map {
                it?.takeIf { b ->
                    b.date <= to
                }
            },
            rideCount = 0,
        )

    /** No ride with a power curve has ever been uploaded. */
    fun noRidesEver(): PowerSkillsResponse =
        skills(window = List(12) { null }, allTime = List(12) { null }, rideCount = 0)

    // region effort

    /** An activity of a week in the builder: [day] is 0 for Monday; a null [load] is an activity without heart rate. */
    class Act(
        val name: String,
        val day: Int,
        val load: Double?,
        val kind: EffortKind = EffortKind.RIDE,
        val sport: String? = null,
        val approximate: Boolean = false,
    )

    private fun oneDecimal(value: Double) = Math.round(value * 10) / 10.0

    /** One week from [start] (a Monday) with its [acts]; the sums, shares and the verdict follow the contract's rules. */
    fun week(
        start: String,
        acts: List<Act>,
        usual: Pair<Double, Double>?,
        weeksLogged: Int,
        current: Boolean = false,
    ): EffortWeek {
        val days =
            (0 until 7).map { d ->
                EffortDay(
                    PlanCalendar.addDays(start, d),
                    oneDecimal(
                        acts.filter { it.day == d }.sumOf {
                            it.load
                                ?: 0.0
                        },
                    ),
                )
            }
        val load = oneDecimal(days.sumOf { it.load })
        val activities =
            acts.sortedBy { it.day }.map {
                val date = PlanCalendar.addDays(start, it.day)
                EffortActivity(
                    id =
                        if (it.kind ==
                            EffortKind.RIDE
                        ) {
                            "ride-$date-${it.name.lowercase().filter { c -> c.isLetterOrDigit() }.take(8)}"
                        } else {
                            "act-$date-${it.name.lowercase().filter { c -> c.isLetterOrDigit() }.take(8)}"
                        },
                    kind = it.kind,
                    sport = if (it.kind == EffortKind.RIDE) null else (it.sport ?: "walking"),
                    name = it.name,
                    date = date,
                    load = it.load,
                    approximate = it.approximate,
                    share = it.load?.let { l -> if (load == 0.0) 0.0 else Math.round(l / load * 10000) / 10000.0 },
                )
            }
        val verdict =
            when {
                usual == null -> EffortVerdict.LEARNING
                current && activities.isEmpty() -> EffortVerdict.NONE
                load < usual.first -> EffortVerdict.BELOW
                load > usual.second -> EffortVerdict.ABOVE
                else -> EffortVerdict.WITHIN
            }
        return EffortWeek(
            weekStart = start,
            load = load,
            approximate = activities.any { it.approximate },
            current = current,
            usualLow = usual?.first,
            usualHigh = usual?.second,
            weeksLogged = weeksLogged,
            verdict = verdict,
            days = days,
            activities = activities,
        )
    }

    /** The loads of the mock-up's twelve weeks, from the week of 31 August; the last two have their own days. */
    val mockLoads = listOf(205.0, 442.0, 245.0, 508.0, 285.0, 390.0, 579.0, 487.0, 350.0, 392.0)

    /** A ride on a Wednesday with the whole [load], the default content of a week. */
    private fun plain(load: Double) = listOf(Act("Free ride", 2, load))

    /**
     * The mock-up's weeks ending with the current one (Monday 16 Nov, Wednesday today): twelve of them, or [weeks] with the earlier ones
     * made of the same pattern, so that Values has more than the card. [learningWeeks] weeks of training before the current one are all
     * there is when it is below 8: no usual range anywhere.
     */
    fun effort(
        weeks: Int = 12,
        thisWeek: List<Act> = listOf(Act("Threshold 2×20", 0, 97.0)),
        learning: Boolean = false,
        lastUsual: Pair<Double, Double> = 334.0 to 511.0,
    ): EffortResponse {
        val lastMonday = "2026-11-16"
        val list =
            (0 until weeks).map { i ->
                val start = PlanCalendar.addDays(lastMonday, -7 * (weeks - 1 - i))
                val fromEnd = weeks - 1 - i
                val first = weeks - 12
                val usual = if (learning || i < 8) null else 300.0 + (i % 3) * 5 to 505.0
                val logged = if (learning) minOf(i, 5) else minOf(i, 8)
                when (fromEnd) {
                    0 -> {
                        week(start, thisWeek, if (learning) null else lastUsual, if (learning) 5 else 8, current = true)
                    }

                    1 -> {
                        week(
                            start,
                            listOf(
                                Act("Threshold 2×20", 0, 107.0),
                                Act("Endurance 90", 1, 74.0),
                                Act("Beat Saber", 2, 27.0, EffortKind.ACTIVITY, "boxing", approximate = true),
                                Act("VO2 5×3", 3, 68.0),
                                Act("Free ride", 5, 81.0),
                                Act("Beat Saber", 6, 38.0, EffortKind.ACTIVITY, "boxing", approximate = true),
                            ),
                            if (learning) null else 298.0 to 510.0,
                            if (learning) 5 else 8,
                        )
                    }

                    else -> {
                        val position = i - first
                        val load =
                            if (position in
                                mockLoads.indices
                            ) {
                                mockLoads[position]
                            } else {
                                mockLoads[i % mockLoads.size]
                            }
                        if (learning && position < 7) {
                            week(start, emptyList(), null, logged)
                        } else {
                            week(start, plain(load), usual, logged)
                        }
                    }
                }
            }
        return EffortResponse("2026-11-18T07:40:00Z", 8, list.last().weeksLogged, list)
    }

    /** Nothing at all, ever: every week empty. */
    fun noEffort(): EffortResponse {
        val weeks =
            (0 until 12).map { i ->
                week(PlanCalendar.addDays("2026-11-16", -7 * (11 - i)), emptyList(), null, 0, current = i == 11)
            }
        return EffortResponse("2026-11-18T07:40:00Z", 8, 0, weeks)
    }

    /** A Monday morning with nothing yet this week, after eight weeks of training: the verdict waits. */
    fun nothingYetThisWeek(): EffortResponse = effort(thisWeek = emptyList())

    /** The current week holds an outdoor ride without heart rate, which adds no load. */
    fun withoutHeartRate(): EffortResponse =
        effort(
            thisWeek =
                listOf(
                    Act("Threshold 2×20", 0, 97.0),
                    Act("Outdoor ride", 1, null, EffortKind.ACTIVITY, "cycling-outdoor"),
                ),
        )
}
