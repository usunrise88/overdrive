package app.overdrive.core.remote

import app.overdrive.core.effort.EffortActivity
import app.overdrive.core.effort.EffortApiJson
import app.overdrive.core.effort.EffortDay
import app.overdrive.core.effort.EffortKind
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.effort.EffortVerdict
import app.overdrive.core.effort.EffortWeek
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.powerskills.POWER_LEVEL_NAMES
import app.overdrive.core.powerskills.POWER_SKILL_DURATIONS
import app.overdrive.core.powerskills.PowerSkill
import app.overdrive.core.powerskills.PowerSkillDuration
import app.overdrive.core.powerskills.PowerSkillEffort
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.powerskills.PowerSkillLevel
import app.overdrive.core.powerskills.PowerSkillsApiJson
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.powerskills.PowerSkillsWindowInfo
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.RiderWeight

/** Synthetic answers for the Power skills and Effort resources, valid by the contract's own rules. Nothing here is a real rider's. */
internal object ChartSamples {
    /** The power skills of a rider of 80 kg whose window best is 300 W (3.75 W/kg, score 3.5) everywhere and whose best ever is 320 W. */
    fun skills(
        from: String,
        to: String,
        withWeight: Boolean = true,
        rideCount: Int = 3,
        table: PowerProfileTable = PowerProfileTable.MEN,
    ): PowerSkillsResponse {
        fun effort(
            watts: Double,
            score: Double,
            name: String,
        ) = PowerSkillEffort(
            watts = watts,
            wattsPerKg = if (withWeight) watts / WEIGHT_KG else null,
            score = if (withWeight) score else null,
            date = to,
            rideId = "ride-$to",
            rideName = name,
        )
        val durations =
            POWER_SKILL_DURATIONS.map { seconds ->
                PowerSkillDuration(
                    seconds = seconds,
                    window = effort(300.0, 3.5, "Sweet spot 2x10"),
                    allTime = effort(320.0, 4.0, "Free ride"),
                )
            }
        val level = if (withWeight) PowerSkillLevel(3, POWER_LEVEL_NAMES[2]) else null
        return PowerSkillsResponse(
            generatedAt = "${to}T07:40:00Z",
            table = table,
            window = PowerSkillsWindowInfo(from, to, rideCount),
            durations = durations,
            riderWeight = if (withWeight) RiderWeight(WEIGHT_KG, to, 5) else null,
            skills = PowerSkillId.entries.map { PowerSkill(it, it.title, it.fromSeconds, it.toSeconds, level) },
            levelsNote = "Levels are approximate.",
            outdoorNote = "Only rides on the tablet count here.",
        )
    }

    fun json(response: PowerSkillsResponse): String = PowerSkillsApiJson.encodePowerSkills(response)

    /**
     * [weeks] weeks ending with the one that begins on [lastMonday] (the current one): a ride of [load] on each Wednesday, so every week
     * but the first is "learning" (at most 5 weeks of training before it) and nothing here needs a usual range.
     */
    fun effort(
        lastMonday: String,
        weeks: Int = 6,
        load: Double = 60.0,
    ): EffortResponse {
        require(weeks in 1..8) { "the samples stay in the learning weeks" }
        val list =
            (0 until weeks).map { i ->
                val start = PlanCalendar.addDays(lastMonday, -7 * (weeks - 1 - i))
                val wednesday = PlanCalendar.addDays(start, 2)
                EffortWeek(
                    weekStart = start,
                    load = load,
                    approximate = false,
                    current = i == weeks - 1,
                    usualLow = null,
                    usualHigh = null,
                    weeksLogged = i,
                    verdict = EffortVerdict.LEARNING,
                    days =
                        (0 until 7).map { d ->
                            EffortDay(
                                PlanCalendar.addDays(start, d),
                                if (d ==
                                    2
                                ) {
                                    load
                                } else {
                                    0.0
                                },
                            )
                        },
                    activities =
                        listOf(
                            EffortActivity(
                                "ride-$wednesday",
                                EffortKind.RIDE,
                                null,
                                "Free ride",
                                wednesday,
                                load,
                                false,
                                1.0,
                            ),
                        ),
                )
            }
        return EffortResponse(
            generatedAt = "${PlanCalendar.addDays(lastMonday, 2)}T07:40:00Z",
            weeksNeeded = 8,
            weeksLogged = weeks - 1,
            weeks = list,
        )
    }

    fun json(response: EffortResponse): String = EffortApiJson.encodeEffort(response)

    private const val WEIGHT_KG = 80.0
}
