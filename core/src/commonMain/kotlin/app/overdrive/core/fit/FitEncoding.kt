package app.overdrive.core.fit

/**
 * CRC-16 of a FIT file and its header (fit.md 1.4): CRC-16/ARC (reflected polynomial `0xA001`, initial value 0, no
 * final XOR) in the nibble-table form Garmin's SDK uses. Check value of `"123456789"`: `0xBB3D`; over a whole file
 * including its own two CRC bytes the result is 0.
 */
internal object FitCrc {
    private val table =
        intArrayOf(
            0x0000,
            0xCC01,
            0xD801,
            0x1400,
            0xF001,
            0x3C00,
            0x2800,
            0xE401,
            0xA001,
            0x6C00,
            0x7800,
            0xB401,
            0x5000,
            0x9C01,
            0x8801,
            0x4400,
        )

    /** The CRC after [crc] has taken in one more byte. */
    fun update(
        crc: Int,
        byte: Byte,
    ): Int {
        val b = byte.toInt() and 0xFF
        var c = crc
        var t = table[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor t xor table[b and 0xF]
        t = table[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor t xor table[(b shr 4) and 0xF]
        return c
    }

    /** The CRC of `bytes[from, to)`. */
    fun of(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var crc = 0
        for (i in from until to) crc = update(crc, bytes[i])
        return crc
    }
}

/**
 * The FIT base types the writer uses (fit.md section 2): the type byte of a field definition, its size, and the
 * value that readers take for "no data". Multi-byte types have bit 7 set in their type byte.
 */
internal enum class FitBaseType(
    val code: Int,
    val size: Int,
    val invalid: Long,
    /** The largest value that is real data, below [invalid] for the types whose invalid value is the maximum. */
    val maxValid: Long,
) {
    ENUM(0x00, 1, 0xFF, 0xFE),
    UINT8(0x02, 1, 0xFF, 0xFE),
    UINT16(0x84, 2, 0xFFFF, 0xFFFE),
    UINT32(0x86, 4, 0xFFFFFFFFL, 0xFFFFFFFEL),

    /** Zero is invalid, so any other 32-bit value is data. */
    UINT32Z(0x8C, 4, 0, 0xFFFFFFFFL),
}

/** A field of a message: its number in the profile, its base type and how many array elements it has. */
internal class FitField(
    val number: Int,
    val type: FitBaseType,
    val count: Int = 1,
) {
    val size: Int get() = type.size * count
}

/** A message type bound to a local message type (0 to 15), as one definition message declares it. */
internal class FitMessageType(
    val local: Int,
    val global: Int,
    val fields: List<FitField>,
)

/** A growable byte array with the little-endian writes FIT needs. */
internal class FitBuffer {
    private var bytes = ByteArray(INITIAL_CAPACITY)

    var size: Int = 0
        private set

    fun u8(value: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
        bytes[size++] = value.toByte()
    }

    fun uint(
        value: Long,
        byteCount: Int,
    ) {
        for (i in 0 until byteCount) u8(((value shr (8 * i)) and 0xFF).toInt())
    }

    /** Overwrites the bytes at [at] with the little-endian [value] of [byteCount] bytes. */
    fun patch(
        at: Int,
        value: Long,
        byteCount: Int,
    ) {
        for (i in 0 until byteCount) bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    /** The CRC of the first [length] bytes. */
    fun crc(length: Int): Int = FitCrc.of(bytes, 0, length)

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}

/** Writes a definition message: the record header, the architecture (little endian) and the field list (fit.md 1.3). */
internal fun FitBuffer.definition(type: FitMessageType) {
    u8(DEFINITION_FLAG or type.local)
    u8(0) // reserved
    u8(0) // architecture: little endian
    uint(type.global.toLong(), 2)
    u8(type.fields.size)
    for (field in type.fields) {
        u8(field.number)
        u8(field.size)
        u8(field.type.code)
    }
}

/**
 * Writes a data message of [type]: the record header, then one value per field in the order of the definition. A null
 * value is written as the base type's invalid value, a gap; a value above what the type can hold is held at its largest
 * real value.
 */
internal fun FitBuffer.data(
    type: FitMessageType,
    vararg values: Long?,
) {
    u8(type.local)
    require(values.size == type.fields.size) { "Message ${type.global} has ${type.fields.size} fields" }
    for ((index, field) in type.fields.withIndex()) {
        val value = values[index]
        uint(if (value == null) field.type.invalid else value.coerceIn(0, field.type.maxValid), field.type.size)
    }
}

/** Writes a data message whose single field is an array, such as `hrv`; each element is null or clamped as in [data]. */
internal fun FitBuffer.dataArray(
    type: FitMessageType,
    values: List<Long?>,
) {
    u8(type.local)
    val field = type.fields.single()
    require(values.size == field.count) { "Expected ${field.count} values, got ${values.size}" }
    for (value in values) {
        uint(if (value == null) field.type.invalid else value.coerceIn(0, field.type.maxValid), field.type.size)
    }
}

private const val DEFINITION_FLAG = 0x40
