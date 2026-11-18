package app.overdrive.core.ble.codec

/**
 * Which flag bit announces which field of Indoor Bike Data, for the two fields whose bits are disputed
 * (docs/protocols/ftms-hr.md §2.4): **Average Speed** and **Instantaneous Cadence**.
 *
 * The SIG's published XML labels bit 1 "Instantaneous Cadence" and bit 2 "Average Speed", against its own field list;
 * two independent decoders (and the byte layouts in §8) read bit 1 as Average Speed and bit 2 as Instantaneous Cadence.
 * The wire order of the fields never changes (§2.2): Average Speed first, then Instantaneous Cadence. A bench result for
 * FT 1 is applied by changing [DEFAULT] in one line, or per device through `FtmsOptions.flagMap`.
 */
public class IndoorBikeFlagMap(
    /** The flag bit (1 or 2) that announces the Average Speed field. */
    public val averageSpeedBit: Int,
    /** The flag bit (1 or 2) that announces the Instantaneous Cadence field. */
    public val instantaneousCadenceBit: Int,
) {
    init {
        require(setOf(averageSpeedBit, instantaneousCadenceBit) == setOf(1, 2)) {
            "Bits 1 and 2 are the two disputed ones, each used once: got $averageSpeedBit and $instantaneousCadenceBit"
        }
    }

    override fun toString(): String =
        "IndoorBikeFlagMap(averageSpeed=bit$averageSpeedBit, cadence=bit$instantaneousCadenceBit)"

    override fun equals(other: Any?): Boolean =
        other is IndoorBikeFlagMap &&
            averageSpeedBit == other.averageSpeedBit &&
            instantaneousCadenceBit == other.instantaneousCadenceBit

    override fun hashCode(): Int = averageSpeedBit * 31 + instantaneousCadenceBit

    public companion object {
        /** Bit 1 is Average Speed, bit 2 is Instantaneous Cadence: what pycycling and Ewoc decode (§2.4). */
        public val DECODERS: IndoorBikeFlagMap = IndoorBikeFlagMap(averageSpeedBit = 1, instantaneousCadenceBit = 2)

        /** Bit 1 is Instantaneous Cadence, bit 2 is Average Speed: the labels of the SIG XML (§2.4). */
        public val SIG_XML_LABELS: IndoorBikeFlagMap =
            IndoorBikeFlagMap(averageSpeedBit = 2, instantaneousCadenceBit = 1)

        /** **The one-line flip for bench question FT 1.** */
        public val DEFAULT: IndoorBikeFlagMap = DECODERS
    }
}

/**
 * One Indoor Bike Data notification (characteristic `0x2AD2`), decoded with the units of docs/protocols/ftms-hr.md §2.2.
 * Every field is optional in FTMS: null means "not in this notification", never zero.
 */
public data class IndoorBikeData(
    /** The 16-bit flags word as received. */
    val flags: Int,
    /** Bit 0, "More Data": the Instantaneous Speed field is absent. Splitting across notifications is unverified (FT 3). */
    val moreData: Boolean,
    val instantaneousSpeedKmh: Double?,
    val averageSpeedKmh: Double?,
    /** In rpm at the characteristic's resolution of 0.5. */
    val instantaneousCadenceRpm: Double?,
    val averageCadenceRpm: Double?,
    /** uint24, **metres** per the SIG; one decoder reads decimetres (FT 2). Nothing in the ride uses it. */
    val totalDistanceMetres: Int?,
    val resistanceLevel: Int?,
    /** sint16 watts: a trainer can report a small negative value. */
    val instantaneousPowerWatts: Int?,
    val averagePowerWatts: Int?,
    val totalEnergyKcal: Int?,
    val energyPerHourKcal: Int?,
    val energyPerMinuteKcal: Int?,
    val heartRateBpm: Int?,
    val metabolicEquivalent: Double?,
    val elapsedTimeSeconds: Int?,
    val remainingTimeSeconds: Int?,
)

/**
 * Decodes Indoor Bike Data (docs/protocols/ftms-hr.md §2). Pure, allocation-light, and free of exceptions for any input.
 */
public object IndoorBikeDataCodec {
    private const val BIT_MORE_DATA = 0
    private const val BIT_AVERAGE_CADENCE = 3
    private const val BIT_TOTAL_DISTANCE = 4
    private const val BIT_RESISTANCE = 5
    private const val BIT_INSTANTANEOUS_POWER = 6
    private const val BIT_AVERAGE_POWER = 7
    private const val BIT_EXPENDED_ENERGY = 8
    private const val BIT_HEART_RATE = 9
    private const val BIT_METABOLIC_EQUIVALENT = 10
    private const val BIT_ELAPSED_TIME = 11
    private const val BIT_REMAINING_TIME = 12

    private const val SPEED_RESOLUTION = 100.0
    private const val CADENCE_RESOLUTION = 2.0
    private const val MET_RESOLUTION = 10.0
    private const val FLAGS_SIZE = 2
    private const val ENERGY_TRIPLE_SIZE = 5

    /**
     * Parses one notification. A payload that ends before a field its flags announce is [DecodeResult.Malformed]; bytes
     * after the last announced field are ignored (§2.3). Bits 13 to 15 are reserved and ignored.
     *
     * @param flagMap the disputed bits 1 and 2, see [IndoorBikeFlagMap].
     */
    public fun parse(
        bytes: ByteArray,
        flagMap: IndoorBikeFlagMap = IndoorBikeFlagMap.DEFAULT,
    ): DecodeResult<IndoorBikeData> {
        if (bytes.isEmpty()) return emptyPayload()
        val reader = ByteReader(bytes)
        if (!reader.has(FLAGS_SIZE)) return reader.truncated("flags", FLAGS_SIZE)
        val flags = reader.u16()

        fun has(bit: Int) = (flags shr bit) and 1 == 1

        // Bit 0 is inverted: the field is present when the bit is 0 (§2.1).
        val moreData = has(BIT_MORE_DATA)
        var instantaneousSpeed: Double? = null
        if (!moreData) {
            if (!reader.has(2)) return reader.truncated("instantaneous speed", 2)
            instantaneousSpeed = reader.u16() / SPEED_RESOLUTION
        }
        var averageSpeed: Double? = null
        if (has(flagMap.averageSpeedBit)) {
            if (!reader.has(2)) return reader.truncated("average speed", 2)
            averageSpeed = reader.u16() / SPEED_RESOLUTION
        }
        var instantaneousCadence: Double? = null
        if (has(flagMap.instantaneousCadenceBit)) {
            if (!reader.has(2)) return reader.truncated("instantaneous cadence", 2)
            instantaneousCadence = reader.u16() / CADENCE_RESOLUTION
        }
        var averageCadence: Double? = null
        if (has(BIT_AVERAGE_CADENCE)) {
            if (!reader.has(2)) return reader.truncated("average cadence", 2)
            averageCadence = reader.u16() / CADENCE_RESOLUTION
        }
        var totalDistance: Int? = null
        if (has(BIT_TOTAL_DISTANCE)) {
            if (!reader.has(3)) return reader.truncated("total distance", 3)
            totalDistance = reader.u24()
        }
        var resistance: Int? = null
        if (has(BIT_RESISTANCE)) {
            if (!reader.has(2)) return reader.truncated("resistance level", 2)
            resistance = reader.s16()
        }
        var power: Int? = null
        if (has(BIT_INSTANTANEOUS_POWER)) {
            if (!reader.has(2)) return reader.truncated("instantaneous power", 2)
            power = reader.s16()
        }
        var averagePower: Int? = null
        if (has(BIT_AVERAGE_POWER)) {
            if (!reader.has(2)) return reader.truncated("average power", 2)
            averagePower = reader.s16()
        }
        var totalEnergy: Int? = null
        var energyPerHour: Int? = null
        var energyPerMinute: Int? = null
        if (has(BIT_EXPENDED_ENERGY)) {
            if (!reader.has(ENERGY_TRIPLE_SIZE)) return reader.truncated("expended energy", ENERGY_TRIPLE_SIZE)
            totalEnergy = reader.u16()
            energyPerHour = reader.u16()
            energyPerMinute = reader.u8()
        }
        var heartRate: Int? = null
        if (has(BIT_HEART_RATE)) {
            if (!reader.has(1)) return reader.truncated("heart rate", 1)
            heartRate = reader.u8()
        }
        var metabolicEquivalent: Double? = null
        if (has(BIT_METABOLIC_EQUIVALENT)) {
            if (!reader.has(1)) return reader.truncated("metabolic equivalent", 1)
            metabolicEquivalent = reader.u8() / MET_RESOLUTION
        }
        var elapsed: Int? = null
        if (has(BIT_ELAPSED_TIME)) {
            if (!reader.has(2)) return reader.truncated("elapsed time", 2)
            elapsed = reader.u16()
        }
        var remaining: Int? = null
        if (has(BIT_REMAINING_TIME)) {
            if (!reader.has(2)) return reader.truncated("remaining time", 2)
            remaining = reader.u16()
        }
        return DecodeResult.Ok(
            IndoorBikeData(
                flags = flags,
                moreData = moreData,
                instantaneousSpeedKmh = instantaneousSpeed,
                averageSpeedKmh = averageSpeed,
                instantaneousCadenceRpm = instantaneousCadence,
                averageCadenceRpm = averageCadence,
                totalDistanceMetres = totalDistance,
                resistanceLevel = resistance,
                instantaneousPowerWatts = power,
                averagePowerWatts = averagePower,
                totalEnergyKcal = totalEnergy,
                energyPerHourKcal = energyPerHour,
                energyPerMinuteKcal = energyPerMinute,
                heartRateBpm = heartRate,
                metabolicEquivalent = metabolicEquivalent,
                elapsedTimeSeconds = elapsed,
                remainingTimeSeconds = remaining,
            ),
        )
    }
}
