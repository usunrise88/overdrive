package app.overdrive.companion

import app.overdrive.core.insights.DiscussionTopic
import app.overdrive.core.insights.Insight
import app.overdrive.core.insights.InsightKind
import app.overdrive.core.insights.InsightsResponse
import app.overdrive.core.insights.TopicStatus
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.remote.WellnessData
import app.overdrive.core.remote.WellnessRange
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.Reading
import app.overdrive.core.wellness.RecoveryFact
import app.overdrive.core.wellness.RecoveryMetric
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryRule
import app.overdrive.core.wellness.RecoveryState
import app.overdrive.core.wellness.RecoveryUnit
import app.overdrive.core.wellness.UsualRange
import app.overdrive.core.wellness.UsualSleepTimes
import app.overdrive.core.wellness.WellnessDay
import app.overdrive.core.wellness.WellnessResponse
import app.overdrive.core.wellness.WellnessSourceId
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthetic wellness, recovery and notes for the tests and screenshots: valid by the contract's own rules, deterministic, and
 * nothing in them is a real rider's body. The figures follow the design's mock-ups (resting heart rate 52 in 50 to 56, sleep 7:40
 * in 6:50 to 7:50, respiratory rate 14.2, oxygen saturation 96).
 */
object WatchSamples {
    /** A usual range, or none while fewer than 14 nights stand behind it. */
    private class Band(
        val low: Double?,
        val high: Double?,
    )

    private fun band(
        on: Boolean,
        low: Double,
        high: Double,
    ) = if (on) Band(low, high) else Band(null, null)

    private fun reading(
        value: Double?,
        band: Band,
        streak: Int = 0,
    ): Reading {
        if (value == null) return Reading(null, null, band.low, band.high, null, 0)
        val low = band.low
        val high = band.high
        val deviation =
            when {
                low == null || high == null -> null
                value > high -> Deviation.ABOVE
                value < low -> Deviation.BELOW
                else -> Deviation.INSIDE
            }
        val outside = deviation == Deviation.ABOVE || deviation == Deviation.BELOW
        val run = if (outside) maxOf(streak, 1) else 0
        return Reading(value, WellnessSourceId.HEALTH_CONNECT, low, high, deviation, run)
    }

    private fun wave(
        day: Long,
        amplitude: Double,
        period: Double,
    ) = amplitude * sin(day / period)

    /** What a range of days is drawn with, so one function makes a day. */
    private class Shape(
        val learning: Boolean,
        val withSpo2: Boolean,
        val withSleepingHr: Boolean,
        val withWeight: Boolean,
        val aboveRun: Int,
        val gapDay: String?,
        val last: Long,
    )

    private fun day(
        day: Long,
        shape: Shape,
    ): WellnessDay {
        val date = PlanCalendar.ofEpochDay(day)
        val on = !shape.learning
        val here = date != shape.gapDay
        val fromEnd = (shape.last - day).toInt()
        val high = fromEnd < shape.aboveRun
        val rhr = 52.0 + wave(day, 2.5, 3.0).roundToInt() + if (high) 6 else 0
        val sleep = (7.5 * 3600 + wave(day, 0.6 * 3600, 2.3)).roundToInt().toDouble()
        val bed = -55.0 + wave(day, 25.0, 4.0).roundToInt()
        val wake = 405.0 + wave(day, 20.0, 3.5).roundToInt()
        val resp = 14.1 + (wave(day, 0.4, 2.7) * 10).roundToInt() / 10.0
        val spo2 = 96.0 + wave(day, 1.5, 2.9).roundToInt()
        val sleepingHr = 48.0 + wave(day, 1.5, 3.1).roundToInt()
        val weight = 78.0 + (wave(day, 0.6, 5.0) * 10).roundToInt() / 10.0
        val average = 78.0 + (wave(day, 0.4, 5.0) * 10).roundToInt() / 10.0
        val minute = 55 + wave(day, 4.0, 4.0).roundToInt()
        return WellnessDay(
            date = date,
            restingHr = reading(rhr.takeIf { here }, band(on, 50.0, 56.0), if (high) shape.aboveRun - fromEnd else 0),
            sleepSeconds = reading(sleep.takeIf { here }, band(on, 24_600.0, 28_200.0)),
            bedtimeMinutes = reading(bed.takeIf { here }, band(on, -70.0, -20.0)),
            wakeMinutes = reading(wake.takeIf { here }, band(on, 380.0, 425.0)),
            respiratoryRate = reading(resp.takeIf { here }, band(on, 13.6, 14.6)),
            spo2 = reading(spo2.takeIf { shape.withSpo2 && here }, band(on, 95.0, 98.0)),
            sleepingHr = reading(sleepingHr.takeIf { shape.withSleepingHr && here }, band(on, 46.0, 51.0)),
            hrvRmssd = reading(null, band(false, 0.0, 0.0)),
            sleepStart = "${PlanCalendar.addDays(date, -1)}T22:$minute:00Z",
            sleepEnd = "${date}T06:45:00Z",
            steps = null,
            hrvSdnn = null,
            weightKg = weight.takeIf { shape.withWeight && day % 2L == 0L },
            weightKg7d = average.takeIf { shape.withWeight },
        )
    }

    /**
     * The days from [from] to [to]. [learning] draws no range (fewer than 14 nights). [withSpo2] false leaves oxygen saturation out
     * of the whole range, the design's "no readings" card; [withWeight] adds a weighing every other morning and its 7-day average;
     * [aboveRun] makes the last days' resting heart rate high, a run the service counts as a streak; [gapDay] has no readings.
     */
    fun wellnessResponse(
        from: String,
        to: String = Samples.TODAY,
        learning: Boolean = false,
        withSpo2: Boolean = true,
        withSleepingHr: Boolean = false,
        withWeight: Boolean = false,
        aboveRun: Int = 0,
        gapDay: String? = null,
    ): WellnessResponse {
        val first = checkNotNull(PlanDates.epochDay(from))
        val last = checkNotNull(PlanDates.epochDay(to))
        val shape = Shape(learning, withSpo2, withSleepingHr, withWeight, aboveRun, gapDay, last)
        val usual =
            if (learning) {
                UsualSleepTimes(null, null)
            } else {
                UsualSleepTimes(UsualRange(-70.0, -20.0), UsualRange(380.0, 425.0))
            }
        return WellnessResponse(
            from = from,
            to = to,
            generatedAt = "${to}T07:40:00Z",
            stale = false,
            nightsLogged = if (learning) 9 else 31,
            nightsNeeded = 14,
            baselineDays = 42,
            usual = usual,
            days = (first..last).map { day(it, shape) },
        )
    }

    fun wellnessData(
        range: WellnessRange = WellnessRange.FOUR_WEEKS,
        learning: Boolean = false,
        withSpo2: Boolean = true,
        withSleepingHr: Boolean = false,
        withWeight: Boolean = false,
        aboveRun: Int = 0,
        gapDay: String? = null,
    ): WellnessData {
        // The service serves 186 days at most: a year is two answers, joined as the phone joins them.
        val parts =
            range.parts(Samples.TODAY).map { (from, to) ->
                wellnessResponse(from, to, learning, withSpo2, withSleepingHr, withWeight, aboveRun, gapDay)
            }
        return if (parts.size == 1) WellnessData.of(parts.single()) else WellnessData.join(parts)
    }

    private fun fact(
        metric: RecoveryMetric,
        label: String,
        value: Double?,
        unit: RecoveryUnit,
        range: Pair<Double, Double>?,
        streak: Int = 0,
    ): RecoveryFact {
        val band = if (range == null) Band(null, null) else Band(range.first, range.second)
        val reading = reading(value, band, streak)
        return RecoveryFact(metric, label, value, unit, band.low, band.high, reading.deviation, reading.streak)
    }

    private val restingRange = 50.0 to 56.0
    private val sleepRange = 24_600.0 to 28_200.0
    private val respiratoryRange = 13.6 to 14.6
    private val oxygenRange = 95.0 to 98.0

    private fun resting(
        value: Double?,
        range: Pair<Double, Double>? = restingRange,
        streak: Int = 0,
    ) = fact(RecoveryMetric.RESTING_HR, "Resting heart rate", value, RecoveryUnit.BPM, range, streak)

    private fun sleep(
        seconds: Double?,
        range: Pair<Double, Double>? = sleepRange,
        streak: Int = 0,
    ) = fact(RecoveryMetric.SLEEP_SECONDS, "Sleep", seconds, RecoveryUnit.SECONDS, range, streak)

    private fun breathing(
        rate: Double?,
        range: Pair<Double, Double>? = respiratoryRange,
        streak: Int = 0,
    ) = fact(RecoveryMetric.RESPIRATORY_RATE, "Respiratory rate", rate, RecoveryUnit.PER_MINUTE, range, streak)

    private fun oxygen(
        percent: Double?,
        range: Pair<Double, Double>? = oxygenRange,
    ) = fact(RecoveryMetric.SPO2, "Oxygen saturation", percent, RecoveryUnit.PERCENT, range)

    private const val READY_REASON = "All four signals are in your usual range."
    private const val CAUTION_REASON = "Resting heart rate is above your usual range for the second morning."
    private const val REST_REASON = "Three signals are well outside your usual range."
    private const val LEARNING_REASON = "Overdrive rates recovery after 14 nights with the watch. 9 nights so far."

    /** `GET /v1/recovery` for [state]; the reasons are the service's own words, since the phone never writes them. */
    fun recovery(
        state: RecoveryState,
        date: String = Samples.TODAY,
        nights: Int = 31,
    ): RecoveryResponse =
        when (state) {
            RecoveryState.READY -> {
                val facts = listOf(resting(52.0), sleep(27_600.0), breathing(14.2), oxygen(96.0))
                response(date, state, READY_REASON, "Train as planned.", RecoveryRule.OK, nights, facts)
            }

            RecoveryState.CAUTION -> {
                val facts = listOf(resting(59.0, streak = 2), sleep(27_600.0), breathing(14.2), oxygen(96.0))
                val guidance = "Train, but keep the hard parts short."
                response(date, state, CAUTION_REASON, guidance, RecoveryRule.C3, nights, facts)
            }

            RecoveryState.REST -> {
                val facts =
                    listOf(
                        resting(61.0, streak = 1),
                        sleep(19_800.0, streak = 1),
                        breathing(16.2, streak = 1),
                        oxygen(96.0),
                    )
                response(date, state, REST_REASON, "Keep today easy or take it off.", RecoveryRule.R1, nights, facts)
            }

            RecoveryState.LEARNING -> {
                val facts =
                    listOf(resting(52.0, null), sleep(27_600.0, null), breathing(14.2, null), oxygen(null, null))
                response(date, state, LEARNING_REASON, null, null, 9, facts)
            }

            RecoveryState.NO_DATA -> {
                response(date, state, "No reading from last night.", null, null, nights, emptyList())
            }
        }

    private fun response(
        date: String,
        state: RecoveryState,
        reason: String,
        guidance: String?,
        rule: RecoveryRule?,
        nights: Int,
        facts: List<RecoveryFact>,
    ) = RecoveryResponse(date, state, reason, guidance, rule, nights, 14, "${date}T07:40:00Z", false, facts)

    fun topic(
        rideId: String = "ride-1",
        status: TopicStatus = TopicStatus.OPEN,
    ) = DiscussionTopic(
        id = 4,
        text = "You stopped Threshold 2×20 at 62% of the planned length.",
        openingQuestion = "I stopped Threshold 2×20 at 62% of the plan. What would you change next time?",
        rideId = rideId,
        createdAt = "2026-11-16T19:42:00Z",
        status = status,
    )

    private const val MORNING_TEXT =
        "You slept 7 hours 40 and your resting heart rate is at its usual 52. Ride as planned."
    private const val ALERT_TEXT = "Resting heart rate has been above your usual range for three mornings."
    private const val RIDE_TEXT =
        "Threshold 2×20 went to plan: both efforts held 236 and 233 W against a target of 235."
    private const val WEEK_TEXT = "Last week's load was 395 against a plan of 410, with all five sessions done."
    private const val DRIFT_TEXT = "Two planned sessions had no ride last week and the load ran 40% under the plan."

    /** Notes: this morning's, a heads-up, an after-ride note with a topic on Monday, a weekly review and a plan check. */
    fun insights(
        withToday: Boolean = true,
        withTopic: Boolean = true,
    ): InsightsResponse {
        val morning = Insight(7, InsightKind.MORNING, "2026-11-18T07:30:00Z", MORNING_TEXT, null, null)
        val alert = Insight(6, InsightKind.DEVIATION, "2026-11-17T07:10:00Z", ALERT_TEXT, null, null)
        val ride =
            Insight(5, InsightKind.RIDE, "2026-11-16T19:42:00Z", RIDE_TEXT, "ride-1", topic().takeIf { withTopic })
        val week = Insight(4, InsightKind.WEEK, "2026-11-16T08:00:00Z", WEEK_TEXT, null, null)
        val drift = Insight(3, InsightKind.DRIFT, "2026-11-10T07:00:00Z", DRIFT_TEXT, null, null)
        val notes = listOfNotNull(morning.takeIf { withToday }, alert, ride, week, drift)
        return InsightsResponse("2026-10-20", Samples.TODAY, "${Samples.TODAY}T07:40:00Z", false, notes)
    }
}
