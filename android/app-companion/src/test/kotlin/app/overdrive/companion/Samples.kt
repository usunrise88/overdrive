package app.overdrive.companion

import app.overdrive.core.metrics.Confidence
import app.overdrive.core.metrics.CurvePoint
import app.overdrive.core.metrics.DetailLap
import app.overdrive.core.metrics.DetailPoint
import app.overdrive.core.metrics.DriftPoint
import app.overdrive.core.metrics.EffortRange
import app.overdrive.core.metrics.FtpBasis
import app.overdrive.core.metrics.FtpEstimate
import app.overdrive.core.metrics.FtpHistoryEntry
import app.overdrive.core.metrics.FtpInfo
import app.overdrive.core.metrics.FtpSuggestion
import app.overdrive.core.metrics.LapKind
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.MetricsDay
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.metrics.MetricsWeek
import app.overdrive.core.metrics.PowerCurves
import app.overdrive.core.metrics.RideDetailResponse
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.metrics.RidePlan
import app.overdrive.core.metrics.RidesResponse
import app.overdrive.core.metrics.StoredRideSummary
import app.overdrive.core.metrics.WPrimeLowest
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.profile.Profile
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthetic answers of the service for the screens' tests and screenshots: valid by the contract's own rules, deterministic, and
 * nothing in them is a real rider's. The numbers follow the design's mock-ups (FTP 250, form 11, a VO2 5×3 ride).
 */
object Samples {
    const val TODAY = "2026-11-18"
    const val NOW_MILLIS = 1_795_000_000_000L // 2026-11-18T09:46:40Z, replaced by [clock] below for the words

    private fun round1(value: Double) = Math.round(value * 10) / 10.0

    /** The daily and weekly figures from [from] to [to], with fitness and fatigue as the service computes them. */
    fun metrics(
        from: String,
        to: String = TODAY,
        suggestion: FtpSuggestion? = suggestion(),
        ftp: Int? = 250,
        withDrift: Boolean = true,
        noLoad: Boolean = false,
    ): MetricsResponse {
        val first = checkNotNull(PlanDates.epochDay(from))
        val last = checkNotNull(PlanDates.epochDay(to))
        var ctl = 50.0
        var atl = 45.0
        val daily =
            (first..last).map { day ->
                val weekday = ((day + 3).mod(7L)).toInt()
                val wave = 1.0 + 0.35 * sin(day / 9.0)
                val ridden = !noLoad && weekday != 0 && weekday != 4 && (day % 11L != 0L)
                val cycling = if (ridden) round1((40 + 12 * weekday) * wave) else 0.0
                val other = if (!noLoad && weekday == 6 && day % 2L == 0L) 38.0 else 0.0
                ctl = round1(ctl + (cycling + other - ctl) / 42.0)
                atl = round1(atl + (cycling + other - atl) / 7.0)
                MetricsDay(
                    PlanCalendar.ofEpochDay(day),
                    round1(cycling + other),
                    cycling,
                    other,
                    ctl = ctl,
                    atl = atl,
                    tsb = round1(ctl - atl),
                )
            }
        val firstMonday = first - (first + 3).mod(7L)
        val lastMonday = last - (last + 3).mod(7L)
        val weekly =
            (firstMonday..lastMonday step 7).map { monday ->
                val days = daily.filter { checkNotNull(PlanDates.epochDay(it.date)) in monday until monday + 7 }
                MetricsWeek(
                    PlanCalendar.ofEpochDay(monday),
                    round1(days.sumOf { it.load }),
                    round1(days.sumOf { it.loadCycling }),
                    round1(days.sumOf { it.loadOther }),
                    days.count { it.loadCycling > 0 },
                    days.count { it.loadOther > 0 },
                )
            }
        val allTime = CURVE_DURATIONS.map { CurvePoint(it, curve(it, 1.0), PlanCalendar.addDays(to, -10), "ride-best") }
        val range =
            CURVE_DURATIONS
                .filter {
                    it <= 3600
                }.map { CurvePoint(it, curve(it, 0.96), PlanCalendar.addDays(to, -2), "ride-sweet") }
        val estimates =
            (0 until 20)
                .map { i ->
                    val date = PlanCalendar.addDays(to, -7 * (19 - i))
                    FtpEstimate(date, 236 + i)
                }.filter { checkNotNull(PlanDates.epochDay(it.date)) >= first }
        // The changes of the FTP, and what the service sends: the value in force at the start of the range, then the changes after it.
        val changes = listOf("2026-03-01" to 238, PlanCalendar.addDays(to, -70) to 244, "2026-10-02" to 250)
        val inForce =
            changes.lastOrNull { checkNotNull(PlanDates.epochDay(it.first)) <= first }?.second ?: changes.first().second
        val history =
            listOf(FtpHistoryEntry(from, inForce)) +
                changes
                    .filter { checkNotNull(PlanDates.epochDay(it.first)) in (first + 1)..last }
                    .map { FtpHistoryEntry(it.first, it.second) }
        val drift =
            if (withDrift) {
                (0 until 18)
                    .map { i ->
                        val date = PlanCalendar.addDays(to, -9 * (17 - i))
                        DriftPoint(
                            date,
                            "ride-d$i",
                            if (i % 2 ==
                                0
                            ) {
                                "Endurance 90"
                            } else {
                                "Sweet spot 3×10"
                            },
                            round1(7.2 - i * 0.22),
                            1.6,
                            1.5,
                        )
                    }.filter { checkNotNull(PlanDates.epochDay(it.date)) in first..last }
            } else {
                emptyList()
            }
        return MetricsResponse(
            from = from,
            to = to,
            generatedAt = "${to}T07:40:00Z",
            stale = false,
            firstLoadDate = if (noLoad) null else from,
            daily = daily,
            weekly = weekly,
            powerCurve =
                if (noLoad) {
                    PowerCurves(emptyList(), emptyList())
                } else {
                    PowerCurves(
                        range.filter { r ->
                            allTime.any {
                                it.durationSec ==
                                    r.durationSec
                            }
                        },
                        allTime,
                    )
                },
            ftp =
                FtpInfo(
                    current = ftp,
                    setAt = ftp?.let { "2026-10-02" },
                    suggestion = suggestion,
                    latestEstimate = if (noLoad) null else 256,
                    history = if (ftp == null) emptyList() else history,
                    estimates = if (noLoad) emptyList() else estimates,
                ),
            drift = drift,
        )
    }

    val CURVE_DURATIONS = MetricsApiJson.POWER_CURVE_DURATIONS

    /** A power curve: 1250 W for a second down to FTP-ish for an hour. */
    private fun curve(
        seconds: Int,
        scale: Double,
    ): Double = ((248 + 960 * exp(-seconds / 75.0) + 0.0) * scale).roundToInt().toDouble()

    fun suggestion(
        id: Int = 7,
        watts: Int = 256,
    ) = FtpSuggestion(
        id,
        watts,
        3,
        42,
        "2026-11-17T20:00:00Z",
        Confidence.MEDIUM,
        FtpBasis(1200, 270.0, "ride-sweet", "2026-11-16"),
    )

    // region rides

    fun ride(
        id: String,
        date: String,
        name: String,
        seconds: Int,
        load: Double,
        watts: Double,
    ) = RideItem(id, RideKind.RIDE, name, "${date}T17:00:00Z", date, seconds, load, false, null, watts, null)

    fun activity(
        id: String,
        date: String,
        name: String,
        seconds: Int,
        load: Double,
        heart: Double,
    ) = RideItem(
        id,
        RideKind.ACTIVITY,
        name,
        "${date}T19:00:00Z",
        date,
        seconds,
        load,
        true,
        "VirtualReality",
        null,
        heart,
    )

    /** The design's list: November and October, with two watch activities. */
    fun rideItems(): List<RideItem> =
        listOf(
            ride("ride-1118", "2026-11-18", "Sweet spot 3×10", 3912, 74.0, 196.0),
            ride("ride-1116", "2026-11-16", "Threshold 2×20", 4500, 97.0, 211.0),
            activity("act-1115", "2026-11-15", "Beat Saber", 2530, 38.0, 142.0),
            ride("ride-1114", "2026-11-14", "Free ride", 5530, 81.0, 172.0),
            ride("ride-1112", "2026-11-12", "VO2 5×3", 3360, 68.0, 189.0),
            activity("act-1111", "2026-11-11", "Superhot VR", 2140, 27.0, 131.0),
            ride("ride-1110", "2026-11-10", "Endurance 90", 5400, 74.0, 165.0),
            ride("ride-1030", "2026-10-30", "Sweet spot 3×10", 3900, 71.0, 194.0),
            ride("ride-1028", "2026-10-28", "Free ride", 2902, 39.0, 158.0),
        )

    fun ridesPage(
        items: List<RideItem> = rideItems(),
        hasMore: Boolean = false,
        before: String? = null,
        limit: Int = 50,
    ) = RidesResponse(
        generatedAt = "2026-11-18T21:10:00Z",
        stale = false,
        before = before,
        limit = limit,
        hasMore = hasMore,
        nextBefore = if (hasMore) items.last().start else null,
        items = items,
    )

    // region ride detail

    val vo2: Workout =
        Workout(
            1,
            "icu-vo2",
            "VO2 5×3",
            null,
            listOf(
                RampStep(600, 0.5, 0.8, label = "Warm-up"),
                SteadyStep(300, 0.95, label = "Opener"),
                SteadyStep(180, 0.55, label = "Easy"),
                RepeatStep(
                    5,
                    listOf(SteadyStep(180, 1.15, label = "VO2"), SteadyStep(180, 0.5, label = "Recover")),
                    label = "VO2",
                ),
                SteadyStep(60, 1.6, label = "Sprint"),
                RampStep(420, 0.5, 0.3, label = "Cool-down"),
            ),
        )

    /** A 56-minute VO2 ride with its series at 10 s, the plan it followed and the laps of its steps. */
    fun rideDetail(
        id: String = "ride-1112",
        withPlan: Boolean = true,
        withHeartRate: Boolean = true,
    ): RideDetailResponse {
        val total = 3360
        val series =
            (1..total / 10).map { i ->
                val t = i * 10
                val target = if (withPlan) targetAt(t) else 0.62
                val power = (250 * target + 4 * sin(i * 1.7)).roundToInt().coerceAtLeast(0)
                val heart = (95 + 70 * (1 - exp(-t / 400.0)) + 12 * sin(t / 90.0)).roundToInt()
                val wBal =
                    (
                        20.0 -
                            12.0 * maxOf(0.0, sin(t / 180.0 * Math.PI / 1.0)) * (
                                if (t in
                                    1020..2400
                                ) {
                                    1.0
                                } else {
                                    0.2
                                }
                            )
                    ).coerceIn(7.2, 20.0)
                DetailPoint(t, power, if (withHeartRate) heart else null, round1(wBal))
            }
        val laps =
            if (withPlan) {
                var start = 0
                lapDefs().mapIndexed { i, (label, length, target, type) ->
                    DetailLap(i, label, type, start, start + length, 150.0 + i, 140.0 + i, target).also {
                        start +=
                            length
                    }
                }
            } else {
                listOf(DetailLap(null, null, null, 0, total, 189.0, 141.0, null))
            }
        return RideDetailResponse(
            generatedAt = "2026-11-12T19:10:00Z",
            stale = false,
            id = id,
            name = if (withPlan) "VO2 5×3" else "Free ride",
            startTime = "2026-11-12T18:02:00Z",
            endTime = "2026-11-12T18:58:00Z",
            date = "2026-11-12",
            workoutId = if (withPlan) "icu-vo2" else null,
            devices = listOf("Trainer"),
            ftp = 250,
            cp = 250.0,
            wPrime = 20.0,
            wPrimeLowest = WPrimeLowest(7.2, 2930),
            summary =
                StoredRideSummary(
                    durationSeconds = total,
                    movingSeconds = total,
                    distanceMeters = 24_000.0,
                    averagePower = 189.0,
                    normalizedPower = 211.0,
                    maxPower = 398,
                    averageHeartRate = if (withHeartRate) 141.0 else null,
                    maxHeartRate = if (withHeartRate) 178 else null,
                    workKilojoules = 635.0,
                    intensityFactor = 0.85,
                    trainingStressScore = 68.0,
                ),
            secondsInZones = listOf(1180, 720, 260, 330, 810, 0, 60),
            laps = laps,
            plan = if (withPlan) RidePlan(vo2, 15, 15, EffortRange(287, 291), 288.0) else null,
            series = series,
        )
    }

    private fun lapDefs(): List<LapDef> =
        buildList {
            add(LapDef("Warm-up", 600, null, LapKind.RAMP))
            add(LapDef("Opener", 300, 238, LapKind.STEADY))
            add(LapDef("Easy", 180, 138, LapKind.STEADY))
            repeat(5) {
                add(LapDef("VO2", 180, 288, LapKind.STEADY))
                add(LapDef("Recover", 180, 125, LapKind.STEADY))
            }
            add(LapDef("Sprint", 60, 400, LapKind.STEADY))
            add(LapDef("Cool-down", 420, null, LapKind.RAMP))
            // The steps above add up to 600 + 300 + 180 + 1800 + 60 + 420 = 3360 s, the ride's length.
        }

    private data class LapDef(
        val label: String,
        val length: Int,
        val target: Int?,
        val type: LapKind,
    )

    private fun targetAt(t: Int): Double =
        when {
            t <= 600 -> 0.5 + 0.3 * t / 600
            t <= 900 -> 0.95
            t <= 1080 -> 0.55
            t <= 2880 -> if (((t - 1080) / 180) % 2 == 0) 1.15 else 0.5
            t <= 2940 -> 1.6
            else -> 0.5 - 0.2 * (t - 2940) / 420
        }

    // region plan and profile

    fun workouts(
        today: String = TODAY,
        configured: Boolean = true,
        withToday: Boolean = true,
    ): WorkoutsResponse {
        val sweetSpot =
            Workout(
                1,
                "icu-sweet",
                "Sweet spot 3×10",
                null,
                listOf(
                    RampStep(300, 0.45, 0.65, label = "Warm-up"),
                    RepeatStep(
                        3,
                        listOf(SteadyStep(600, 0.88, label = "Sweet spot"), SteadyStep(300, 0.55, label = "Recover")),
                        label = "Sweet spot",
                    ),
                    FreeStep(120),
                    RampStep(300, 0.6, 0.4, label = "Cool-down"),
                ),
            )
        return WorkoutsResponse(
            from = today,
            to = today,
            generatedAt = if (configured) "${today}T07:40:00Z" else null,
            stale = false,
            configured = configured,
            workouts =
                if (configured &&
                    withToday
                ) {
                    listOf(PlannedWorkout("icu-sweet", today, "Sweet spot 3×10", PlanSource.INTERVALS_ICU, sweetSpot))
                } else {
                    emptyList()
                },
        )
    }

    fun profile(ftp: Int? = 250): Profile = Profile.default().copy(ftp = ftp, updatedAt = "2026-10-02T08:00:00Z")
}
