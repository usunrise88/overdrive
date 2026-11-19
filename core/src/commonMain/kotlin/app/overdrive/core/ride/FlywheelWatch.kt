package app.overdrive.core.ride

/**
 * Follows the trainer's own flywheel speed from one reading to the next, so that the engine can tell a rider who sets
 * the flywheel turning from a flywheel that merely winds down by itself (docs/adr/0032). The speed is the one field of
 * the trainer's reading that the engine otherwise ignores (spec §4.3). It carries no clock: it counts readings, so it
 * works the same at 1 Hz and at the D500's 4 Hz.
 *
 * - [accelerated]: this reading is at least [stepKmh] above the one before it. A coasting flywheel never does that; a
 *   first stroke does, whatever the cadence field says.
 * - [coasting]: the flywheel has been falling: its last move of more than [DEAD_BAND_KMH] away from where it last
 *   settled was downwards. A coast-down is slow (a heavy flywheel loses a few tenths of a km/h per reading) and a sensor
 *   may repeat its last value between two updates, so this is judged against the last speed that differed by the dead
 *   band, not against the reading before. A rider who pedals steadily makes the speed wobble both ways, so the flag
 *   follows the wobble and is false about half of the readings, which is enough for a rider to be recognised.
 *
 * A step of 0 turns both off. A reading without speed, and a new link, start over: nothing is compared across a gap.
 */
internal class FlywheelWatch(
    private val stepKmh: Double,
) {
    private var previous: Double? = null
    private var settled: Double? = null

    /** Whether the latest reading shows the flywheel speeding up by a step or more. */
    var accelerated: Boolean = false
        private set

    /** Whether the flywheel is winding down. */
    var coasting: Boolean = false
        private set

    /** Takes the speed of the next reading, null when the reading has none. */
    fun offer(speedKmh: Double?) {
        if (stepKmh <= 0.0) return
        val before = previous
        previous = speedKmh
        if (speedKmh == null || !speedKmh.isFinite()) {
            reset()
            return
        }
        accelerated = before != null && speedKmh - before >= stepKmh
        val anchor = settled
        if (anchor == null) {
            settled = speedKmh
        } else if (speedKmh <= anchor - DEAD_BAND_KMH) {
            coasting = true
            settled = speedKmh
        } else if (speedKmh >= anchor + DEAD_BAND_KMH) {
            coasting = false
            settled = speedKmh
        }
    }

    /** Forgets everything: the trainer's link went down or a reading had no speed. */
    fun reset() {
        previous = null
        settled = null
        accelerated = false
        coasting = false
    }

    private companion object {
        /** Quantisation and sensor flicker of a flywheel speed (0.01 km/h on the D500) stay well inside this. */
        const val DEAD_BAND_KMH = 0.05
    }
}
