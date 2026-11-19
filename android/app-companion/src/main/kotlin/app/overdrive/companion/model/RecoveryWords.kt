package app.overdrive.companion.model

import app.overdrive.companion.wellness.RecoveryFactData
import app.overdrive.companion.wellness.RecoveryLevel
import app.overdrive.companion.wellness.RecoveryState
import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.RecoveryFact
import app.overdrive.core.wellness.RecoveryMetric
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryUnit
import app.overdrive.core.wellness.RecoveryState as ServiceState

/**
 * `GET /v1/recovery` as the card says it (design/companion.md §4.1, §7.2, ADR 0023). **The service decides and words the state**
 * (`reason`, `guidance`, the order of the facts); the phone only writes figures the way the design does and never rates anything, so
 * there is no rule in here. Pure, so every sentence is a unit test.
 */
object RecoveryWords {
    /** The card's state for [response] on the local date [today]: this morning's, or an older one that says so. */
    fun state(
        response: RecoveryResponse,
        today: String,
    ): RecoveryState {
        val facts = response.facts.map(::fact)
        return when (response.state) {
            ServiceState.READY,
            ServiceState.CAUTION,
            ServiceState.REST,
            -> {
                RecoveryState.Rated(
                    level =
                        when (response.state) {
                            ServiceState.READY -> RecoveryLevel.Ready
                            ServiceState.CAUTION -> RecoveryLevel.Caution
                            else -> RecoveryLevel.Rest
                        },
                    reason = listOfNotNull(response.reason, response.guidance).joinToString(" "),
                    facts = facts,
                    thisMorning = response.date == today,
                )
            }

            ServiceState.LEARNING -> {
                RecoveryState.Learning(response.nightsLogged, response.nightsNeeded, facts)
            }

            ServiceState.NO_DATA -> {
                RecoveryState.NoReading
            }
        }
    }

    /** One signal: its name, its figure and unit, and the caption under it (design/companion.md §4.1). */
    fun fact(fact: RecoveryFact): RecoveryFactData {
        val outside = fact.deviation == Deviation.ABOVE || fact.deviation == Deviation.BELOW
        val format = formatter(fact.metric, fact.unit)
        val usual = WatchWords.range(fact.baselineLow, fact.baselineHigh, format)
        val caption =
            when {
                fact.value == null -> "no reading"
                fact.deviation == null || usual == null -> "last night"
                fact.deviation == Deviation.INSIDE -> "usual $usual"
                fact.deviation == Deviation.ABOVE -> "${above(fact.metric)} $usual"
                else -> "${below(fact.metric)} $usual"
            }
        return RecoveryFactData(
            key = key(fact.metric),
            name = fact.label,
            value = fact.value?.let { format(it) },
            unit = WatchWords.unitWord(fact.unit),
            caption = caption,
            outside = outside,
        )
    }

    private fun above(metric: RecoveryMetric) =
        if (metric ==
            RecoveryMetric.BEDTIME_MINUTES
        ) {
            "later than usual"
        } else {
            "above usual"
        }

    private fun below(metric: RecoveryMetric) =
        if (metric ==
            RecoveryMetric.BEDTIME_MINUTES
        ) {
            "earlier than usual"
        } else {
            "below usual"
        }

    /** How a figure of this metric is written: sleep as `h:mm`, a bedtime as a clock time, a rate with a decimal, the rest whole. */
    private fun formatter(
        metric: RecoveryMetric,
        unit: RecoveryUnit,
    ): (Double?) -> String =
        when {
            metric == RecoveryMetric.BEDTIME_MINUTES -> WatchWords::clock
            unit == RecoveryUnit.SECONDS -> WatchWords::hoursMinutes
            unit == RecoveryUnit.PER_MINUTE -> WatchWords::oneDecimal
            else -> WatchWords::whole
        }

    /** The key the Wellness screen scrolls to (the card of that metric). */
    fun key(metric: RecoveryMetric): String =
        when (metric) {
            RecoveryMetric.RESTING_HR -> "restingHr"
            RecoveryMetric.SLEEP_SECONDS -> "sleepSeconds"
            RecoveryMetric.BEDTIME_MINUTES -> "bedtimeMinutes"
            RecoveryMetric.RESPIRATORY_RATE -> "respiratoryRate"
            RecoveryMetric.SPO2 -> "spo2"
            RecoveryMetric.SLEEPING_HR -> "sleepingHr"
            RecoveryMetric.HRV_RMSSD -> "hrvRmssd"
        }
}
