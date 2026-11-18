package app.overdrive.core.ble.codec

/**
 * The Fitness Machine Feature characteristic (`0x2ACC`): what the trainer measures and what it lets us set
 * (docs/protocols/ftms-hr.md §3). Bit meanings are single-source (pycycling) until the bench (FT 3.2).
 */
public data class FitnessMachineFeatures(
    /** The "Fitness Machine Features" word (bytes 0 to 3). */
    val machineFeatures: Long,
    /** The "Target Setting Features" word (bytes 4 to 7). */
    val targetSettingFeatures: Long,
) {
    private fun machine(bit: Int) = (machineFeatures shr bit) and 1L == 1L

    private fun target(bit: Int) = (targetSettingFeatures shr bit) and 1L == 1L

    /** Machine bit 1. */
    val cadenceSupported: Boolean get() = machine(BIT_CADENCE)

    /** Machine bit 2. */
    val totalDistanceSupported: Boolean get() = machine(BIT_TOTAL_DISTANCE)

    /** Machine bit 10. The strap is the heart-rate source, so this is informational. */
    val heartRateSupported: Boolean get() = machine(BIT_HEART_RATE)

    /** Machine bit 14: Indoor Bike Data carries power. */
    val powerMeasurementSupported: Boolean get() = machine(BIT_POWER_MEASUREMENT)

    /** Target bit 3: Set Target Power, needed for ERG. */
    val powerTargetSettingSupported: Boolean get() = target(BIT_POWER_TARGET)

    /** Target bit 13: Set Indoor Bike Simulation Parameters, needed for gears and grade. */
    val indoorBikeSimulationSupported: Boolean get() = target(BIT_SIMULATION)

    /** Power measurement and power target: what a workout in ERG needs (§3 "Rule for the app"). */
    val supportsErg: Boolean get() = powerMeasurementSupported && powerTargetSettingSupported

    /** Power measurement and simulation parameters: what gears need. */
    val supportsGears: Boolean get() = powerMeasurementSupported && indoorBikeSimulationSupported

    /** The features of §3 the ride needs that this trainer lacks, empty when it has them all. */
    val missingForRide: List<RequiredFeature>
        get() =
            buildList {
                if (!powerMeasurementSupported) add(RequiredFeature.POWER_MEASUREMENT)
                if (!powerTargetSettingSupported) add(RequiredFeature.POWER_TARGET_SETTING)
                if (!indoorBikeSimulationSupported) add(RequiredFeature.INDOOR_BIKE_SIMULATION)
            }

    private companion object {
        const val BIT_CADENCE = 1
        const val BIT_TOTAL_DISTANCE = 2
        const val BIT_HEART_RATE = 10
        const val BIT_POWER_MEASUREMENT = 14
        const val BIT_POWER_TARGET = 3
        const val BIT_SIMULATION = 13
    }
}

/** The features the ride needs from a trainer (docs/protocols/ftms-hr.md §3, "Rule for the app"). */
public enum class RequiredFeature {
    POWER_MEASUREMENT,
    POWER_TARGET_SETTING,
    INDOOR_BIKE_SIMULATION,
}

/** Decodes the Fitness Machine Feature characteristic: exactly 8 bytes, two little-endian 32-bit words. */
public object FitnessMachineFeatureCodec {
    /** The length of the characteristic. */
    public const val SIZE: Int = 8

    /** Parses [bytes]: anything but 8 bytes is [DecodeResult.Malformed]. */
    public fun parse(bytes: ByteArray): DecodeResult<FitnessMachineFeatures> {
        if (bytes.isEmpty()) return emptyPayload()
        if (bytes.size != SIZE) {
            val kind = if (bytes.size < SIZE) DecodeErrorKind.TRUNCATED else DecodeErrorKind.WRONG_LENGTH
            return DecodeResult.Malformed(DecodeError(kind, "fitness machine feature", SIZE, bytes.size))
        }
        val reader = ByteReader(bytes)
        val machine = reader.u32()
        val target = reader.u32()
        return DecodeResult.Ok(FitnessMachineFeatures(machine, target))
    }
}
