package app.overdrive.core.device

/**
 * Domain-layer conversion of RR intervals (docs/protocols/ftms-hr.md §6.3, §10): the strap sends them in units of
 * 1/1024 s and the ride record keeps whole milliseconds. The codec (`HeartRateCodec`) delivers the raw values, and the
 * session recorder keeps them raw; this is the single place the rounding rule lives.
 */
public object RrIntervals {
    private const val MILLIS_PER_SECOND = 1000L
    private const val UNITS_PER_SECOND = 1024L

    /** The largest raw value that makes sense: 65535/1024 s is over a minute, which no heartbeat is. */
    private const val MAX_RAW = 0xFFFF

    /**
     * `raw · 1000 / 1024` ms **rounded to the nearest whole millisecond, halves up**: 900 is 878.9 and becomes 879, 1024
     * becomes 1000, 512 becomes 500. Integer arithmetic only, so the result never depends on floating point.
     */
    public fun rawToMillis(raw: Int): Int =
        ((raw * MILLIS_PER_SECOND + UNITS_PER_SECOND / 2) / UNITS_PER_SECOND).toInt()

    /**
     * Converts a notification's raw intervals, oldest first. A raw value of 0 is dropped: a heartbeat of 0 ms is a strap
     * glitch and [HeartRateReading] only accepts positive intervals. Values outside 0..65535 cannot come from a
     * 16-bit field and are dropped too.
     */
    public fun toMillis(raw: List<Int>): List<Int> = raw.filter { it in 1..MAX_RAW }.map { rawToMillis(it) }
}
