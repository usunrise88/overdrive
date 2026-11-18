package app.overdrive.core.record

/**
 * CRC-32 as used by zip, PNG and Ethernet (reflected polynomial `0xEDB88320`, initial value and final XOR all ones),
 * which checksums every line of the ride log. Check value of `"123456789"`: `0xCBF43926`.
 *
 * Common Kotlin has no CRC of its own, and `java.util.zip` is a platform API, so core carries the table form.
 */
internal object Crc32 {
    private val table =
        IntArray(256) { index ->
            var crc = index
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor POLYNOMIAL else crc ushr 1 }
            crc
        }

    /** The checksum of `bytes[from, to)`, as the unsigned 32-bit value in a [Long]. */
    fun of(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Long {
        var crc = -1
        for (i in from until to) {
            crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }

    private const val POLYNOMIAL = 0xEDB88320.toInt()
}
