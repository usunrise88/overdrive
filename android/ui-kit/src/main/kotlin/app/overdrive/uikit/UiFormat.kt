package app.overdrive.uikit

import java.util.Locale

/**
 * How every number on screen is written (design/system.md §3). Pure functions, no Compose, so the
 * overlay, the ride screen and the companion print the same strings and the rules are unit-tested.
 *
 * Missing data is an em dash, never "0", "--" or blank. Negative numbers use the true minus sign.
 */
object UiFormat {
    /** The empty value: no sensor data yet, or the signal is lost. */
    const val EMPTY: String = "—"

    /** The true minus sign (U+2212). */
    const val MINUS: Char = '−'

    /** Power, heart rate, cadence and other whole numbers. Null is [EMPTY]. */
    fun count(value: Int?): String = value?.let { signed(it) } ?: EMPTY

    /** A whole percentage such as "100%". Null is [EMPTY]. */
    fun percent(value: Int?): String = value?.let { "${signed(it)}%" } ?: EMPTY

    /** An integer with the true minus sign for negatives. */
    fun signed(value: Int): String = if (value < 0) "$MINUS${-value.toLong()}" else value.toString()

    /**
     * Elapsed or remaining time: `m:ss` below one hour, `h:mm:ss` from one hour. Negative values read as zero.
     */
    fun elapsed(seconds: Int?): String {
        if (seconds == null) return EMPTY
        val total = seconds.coerceAtLeast(0)
        val hours = total / SECONDS_PER_HOUR
        val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val secs = total % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "$hours:${minutes.twoDigits()}:${secs.twoDigits()}"
        } else {
            "$minutes:${secs.twoDigits()}"
        }
    }

    /** Duration in whole minutes for list subtitles: "65 min". */
    fun minutes(seconds: Int): String = "${(seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE} min"

    /** Speed in km/h with one decimal. Null is [EMPTY]. */
    fun speed(kmh: Double?): String = kmh?.let { String.format(Locale.ROOT, "%.1f", it.coerceAtLeast(0.0)) } ?: EMPTY

    /** Distance in kilometres: two decimals below 100 km, one decimal above. Null is [EMPTY]. */
    fun distanceKm(meters: Double?): String {
        if (meters == null) return EMPTY
        val km = meters.coerceAtLeast(0.0) / METERS_PER_KM
        return String.format(Locale.ROOT, if (km < HUNDRED_KM) "%.2f" else "%.1f", km)
    }

    /** A decimal with a fixed number of places, for settings values. */
    fun decimal(
        value: Double,
        places: Int,
    ): String = String.format(Locale.ROOT, "%.${places}f", value)

    /** A range such as "113–175" (en dash), or a single value when both ends are equal. */
    fun range(
        from: Int,
        to: Int,
    ): String = if (from == to) from.toString() else "$from–$to"

    private fun Int.twoDigits(): String = if (this < TEN) "0$this" else toString()

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3600
    private const val METERS_PER_KM = 1000.0
    private const val HUNDRED_KM = 100.0
    private const val TEN = 10
}
