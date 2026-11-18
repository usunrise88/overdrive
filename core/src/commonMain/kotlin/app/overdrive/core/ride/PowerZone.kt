package app.overdrive.core.ride

/**
 * Coggan power zones as a share of FTP. The zone colour is the one accent of the UI (spec §7.4).
 *
 * Coggan's table is written in whole percentages (Z1 below 55, Z2 56 to 75, Z3 76 to 90, Z4 91 to 105,
 * Z5 106 to 120, Z6 121 to 150, Z7 above 150), which leaves gaps such as 55.5%. Power is measured
 * in whole watts, so the gaps are closed by reading every printed upper bound as **inclusive** and
 * every zone as starting just above the previous bound: Z2 is `(55%, 75%]`, Z7 is `(150%, ∞)`.
 * [of] compares exact ratios in integer arithmetic, so 55.0% is Z1, 55.1% is Z2 and 150.0% is Z6.
 */
public enum class PowerZone(
    /** 1 for Z1 up to 7 for Z7. */
    public val number: Int,
    /** Inclusive upper bound as a whole percentage of FTP. Null for Z7, which has none. */
    public val upperPercentOfFtp: Int?,
) {
    Z1(1, 55),
    Z2(2, 75),
    Z3(3, 90),
    Z4(4, 105),
    Z5(5, 120),
    Z6(6, 150),
    Z7(7, null),
    ;

    public companion object {
        /**
         * The zone of [powerWatts] for a rider with the given [ftpWatts].
         *
         * Negative power (a sensor glitch) is treated like zero, so it lands in Z1.
         *
         * @throws IllegalArgumentException if [ftpWatts] is not positive.
         */
        public fun of(
            powerWatts: Int,
            ftpWatts: Int,
        ): PowerZone {
            require(ftpWatts > 0) { "FTP must be positive, got $ftpWatts" }
            val scaledPower = powerWatts.toLong() * 100
            for (zone in entries) {
                val upper = zone.upperPercentOfFtp ?: return zone
                if (scaledPower <= upper.toLong() * ftpWatts) return zone
            }
            return Z7
        }
    }
}
