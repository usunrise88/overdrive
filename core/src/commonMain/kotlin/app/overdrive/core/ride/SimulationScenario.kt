package app.overdrive.core.ride

/** A sensor whose signal a [SimulationScenario] can drop. */
public enum class SimulatedSignal {
    POWER,
    HEART_RATE,
    CADENCE,
}

/**
 * [signal] reads null (no signal) for [durationSeconds] seconds from [startSecond] on.
 *
 * Time here is sensor time: the number of seconds since `start()`, counting the first tick as second 1.
 * It keeps running while the ride is paused, like a real sensor, and equals
 * [RideSnapshot.elapsedSeconds] in a ride that was never paused. The dropout covers the seconds
 * `startSecond until startSecond + durationSeconds`. Only the reading is lost; the simulated rider
 * keeps riding, so distance and the other signals are unaffected and the signal is back to its
 * natural value when the dropout ends.
 */
public data class SignalDropout(
    val signal: SimulatedSignal,
    val startSecond: Int,
    val durationSeconds: Int,
) {
    init {
        require(startSecond >= 1) { "A dropout starts at second 1 or later, got $startSecond" }
        require(durationSeconds >= 1) { "A dropout lasts at least a second, got $durationSeconds" }
    }

    internal fun covers(
        sensorSecond: Int,
        of: SimulatedSignal,
    ): Boolean = signal == of && sensorSecond >= startSecond && sensorSecond - startSecond < durationSeconds
}

/**
 * Parameters of a [SimulatedRideSession]: what the simulated rider and sensors do, and what goes wrong.
 * The defaults describe an unremarkable ride with no failures.
 */
public data class SimulationScenario(
    /** Signal gaps to inject, so the UI's rendering of missing data can be tried. */
    val dropouts: List<SignalDropout> = emptyList(),
    /** Number of virtual gears (spec §4.4). */
    val gearCount: Int = RideSnapshot.DEFAULT_GEAR_COUNT,
    /** The default gear, 1-based. The middle one unless set. */
    val neutralGear: Int = (gearCount + 1) / 2,
    /**
     * How much one gear changes the simulated power in a simulation step, as a fraction of the
     * neutral-gear power: with 0.04, three gears up means 12% more watts.
     */
    val powerPerGear: Double = 0.04,
    /** Power in a simulation step in neutral gear, as a fraction of FTP. */
    val freeRideFtpFraction: Double = 0.55,
    /** Cadence the rider settles around when the step does not ask for one, in rpm. */
    val cadenceRpm: Double = 90.0,
    /** Heart rate at zero power, in bpm. */
    val restingHeartRate: Int = 65,
    /** Steady-state heart rate when riding at FTP, in bpm. */
    val heartRateAtFtp: Int = 170,
    /** Scales all random noise: 1 is natural, 0 turns it off and makes the signals smooth. */
    val noise: Double = 1.0,
) {
    init {
        require(gearCount >= 1) { "Gear count must be at least 1, got $gearCount" }
        require(neutralGear in 1..gearCount) { "Neutral gear $neutralGear is outside 1..$gearCount" }
        require(powerPerGear >= 0.0) { "Power per gear must not be negative, got $powerPerGear" }
        require(freeRideFtpFraction > 0.0) { "Free-ride power must be positive, got $freeRideFtpFraction" }
        require(cadenceRpm > 0.0) { "Cadence must be positive, got $cadenceRpm" }
        require(restingHeartRate in 1..<heartRateAtFtp) { "Heart rate at FTP must exceed the resting rate" }
        require(noise >= 0.0) { "Noise must not be negative, got $noise" }
    }
}
