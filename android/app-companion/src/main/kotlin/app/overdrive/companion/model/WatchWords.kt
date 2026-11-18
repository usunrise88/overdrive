package app.overdrive.companion.model

import app.overdrive.core.wellness.Deviation
import app.overdrive.core.wellness.RecoveryUnit
import app.overdrive.uikit.UiFormat
import kotlin.math.roundToInt

/**
 * The figures of the watch data as the design writes them (design/companion.md §4.1, §6.7, §7.2, §7.4): sleep as `h:mm`, a time of
 * night as a clock time, a rate with one decimal, and the usual range of each in the same form. Pure, so every word is a unit test.
 */
object WatchWords {
    private const val MINUTES_PER_DAY = 1440
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /** Sleep as `h:mm` ("7:40"), rounded to the minute; null is the dash. */
    fun hoursMinutes(seconds: Double?): String {
        if (seconds == null) return UiFormat.EMPTY
        val minutes = (seconds / SECONDS_PER_MINUTE).roundToInt().coerceAtLeast(0)
        return "${minutes / MINUTES_PER_HOUR}:${(minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')}"
    }

    /** A clock time from minutes since local midnight, a bedtime before midnight negative: −55 is "23:05", 405 is "06:45". */
    fun clock(minutes: Double?): String {
        if (minutes == null) return UiFormat.EMPTY
        val whole = ((minutes.roundToInt() % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "${(whole / MINUTES_PER_HOUR).toString().padStart(
            2,
            '0',
        )}:${(whole % MINUTES_PER_HOUR).toString().padStart(2, '0')}"
    }

    /** One decimal with the point, "14.2". */
    fun oneDecimal(value: Double?): String = value?.let { UiFormat.decimal(it, 1) } ?: UiFormat.EMPTY

    /** A whole number, "52". */
    fun whole(value: Double?): String = value?.let { UiFormat.count(it.roundToInt()) } ?: UiFormat.EMPTY

    /** The words under a figure that is inside, above or below its usual range: "In your usual range", "Above usual · day 3". */
    fun verdict(
        deviation: Deviation?,
        streak: Int,
    ): String? =
        when (deviation) {
            null -> null
            Deviation.INSIDE -> "In your usual range"
            Deviation.ABOVE -> "Above usual" + days(streak)
            Deviation.BELOW -> "Below usual" + days(streak)
        }

    /** The same for the time of going to bed (design/companion.md §7.4): by bedtime, later or earlier. */
    fun timingVerdict(
        deviation: Deviation?,
        streak: Int,
    ): String? =
        when (deviation) {
            null -> null
            Deviation.INSIDE -> "In your usual times"
            Deviation.ABOVE -> "Later than usual" + days(streak)
            Deviation.BELOW -> "Earlier than usual" + days(streak)
        }

    private fun days(streak: Int): String = if (streak >= 2) " · day $streak" else ""

    /** A range as "low–high" with each end written by [format]; one value when both read the same. */
    fun range(
        low: Double?,
        high: Double?,
        format: (Double?) -> String,
    ): String? {
        if (low == null || high == null) return null
        val a = format(low)
        val b = format(high)
        return if (a == b) a else "$a–$b"
    }

    /** The word the service's fact unit stands for on a card ("bpm", "/min", "%", "ms"); null where the value says it ("7:40"). */
    fun unitWord(unit: RecoveryUnit): String? =
        when (unit) {
            RecoveryUnit.BPM -> "bpm"
            RecoveryUnit.PER_MINUTE -> "/min"
            RecoveryUnit.PERCENT -> "%"
            RecoveryUnit.MILLISECONDS -> "ms"
            RecoveryUnit.SECONDS, RecoveryUnit.MINUTES -> null
        }
}
