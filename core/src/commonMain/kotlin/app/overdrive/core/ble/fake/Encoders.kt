package app.overdrive.core.ble.fake

import app.overdrive.core.ble.codec.IndoorBikeData
import app.overdrive.core.ble.codec.IndoorBikeFlagMap
import kotlin.math.roundToInt

/**
 * The inverse of `IndoorBikeDataCodec`, for the simulated peripherals and for round-trip tests: every non-null field of
 * [IndoorBikeData] is written and its flag set (bits 1 and 2 as [flagMap] says); [IndoorBikeData.moreData] sets bit 0 and
 * leaves the speed out. The [IndoorBikeData.flags] of the input is ignored.
 */
public object IndoorBikeDataEncoder {
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

    /** The bytes of one notification for [data]. */
    public fun encode(
        data: IndoorBikeData,
        flagMap: IndoorBikeFlagMap = IndoorBikeFlagMap.DEFAULT,
    ): ByteArray {
        var flags = 0
        val body = ByteSink()

        fun flag(bit: Int) {
            flags = flags or (1 shl bit)
        }
        if (data.moreData) {
            flag(BIT_MORE_DATA)
        } else {
            body.u16(((data.instantaneousSpeedKmh ?: 0.0) * 100).roundToInt())
        }
        data.averageSpeedKmh?.let {
            flag(flagMap.averageSpeedBit)
            body.u16((it * 100).roundToInt())
        }
        data.instantaneousCadenceRpm?.let {
            flag(flagMap.instantaneousCadenceBit)
            body.u16((it * 2).roundToInt())
        }
        data.averageCadenceRpm?.let {
            flag(BIT_AVERAGE_CADENCE)
            body.u16((it * 2).roundToInt())
        }
        data.totalDistanceMetres?.let {
            flag(BIT_TOTAL_DISTANCE)
            body.u24(it)
        }
        data.resistanceLevel?.let {
            flag(BIT_RESISTANCE)
            body.u16(it)
        }
        data.instantaneousPowerWatts?.let {
            flag(BIT_INSTANTANEOUS_POWER)
            body.u16(it)
        }
        data.averagePowerWatts?.let {
            flag(BIT_AVERAGE_POWER)
            body.u16(it)
        }
        if (data.totalEnergyKcal != null && data.energyPerHourKcal != null && data.energyPerMinuteKcal != null) {
            flag(BIT_EXPENDED_ENERGY)
            body.u16(data.totalEnergyKcal)
            body.u16(data.energyPerHourKcal)
            body.u8(data.energyPerMinuteKcal)
        }
        data.heartRateBpm?.let {
            flag(BIT_HEART_RATE)
            body.u8(it)
        }
        data.metabolicEquivalent?.let {
            flag(BIT_METABOLIC_EQUIVALENT)
            body.u8((it * 10).roundToInt())
        }
        data.elapsedTimeSeconds?.let {
            flag(BIT_ELAPSED_TIME)
            body.u16(it)
        }
        data.remainingTimeSeconds?.let {
            flag(BIT_REMAINING_TIME)
            body.u16(it)
        }
        val out = ByteSink()
        out.u16(flags)
        return out.toByteArray() + body.toByteArray()
    }
}

/** The inverse of `HeartRateCodec`, for the simulated strap and for tests. */
public object HeartRateEncoder {
    private const val FLAG_16_BIT = 0x01
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10
    private const val SHIFT_CONTACT = 1
    private const val MAX_8_BIT = 255

    /**
     * One Heart Rate Measurement. [contactBits] is the two-bit contact field (0 to 3, written as is), [rrRaw] the RR
     * intervals in 1/1024 s (null leaves the RR flag clear), [energyKilojoules] optional. A bpm above 255 is written in
     * the 16-bit format; [force16Bit] writes it for any value.
     */
    public fun encode(
        bpm: Int,
        contactBits: Int = 0,
        rrRaw: List<Int>? = null,
        energyKilojoules: Int? = null,
        force16Bit: Boolean = false,
    ): ByteArray {
        val wide = force16Bit || bpm > MAX_8_BIT
        var flags = (contactBits and 0x03) shl SHIFT_CONTACT
        if (wide) flags = flags or FLAG_16_BIT
        if (energyKilojoules != null) flags = flags or FLAG_ENERGY
        if (rrRaw != null) flags = flags or FLAG_RR
        val out = ByteSink()
        out.u8(flags)
        if (wide) out.u16(bpm) else out.u8(bpm)
        energyKilojoules?.let { out.u16(it) }
        rrRaw?.forEach { out.u16(it) }
        return out.toByteArray()
    }
}

/** A growing little-endian byte buffer. */
internal class ByteSink {
    private var bytes = ByteArray(INITIAL)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun u8(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun u16(value: Int) {
        u8(value)
        u8(value shr BITS)
    }

    fun u24(value: Int) {
        u16(value)
        u8(value shr 2 * BITS)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private companion object {
        const val INITIAL = 16
        const val BITS = 8
    }
}
