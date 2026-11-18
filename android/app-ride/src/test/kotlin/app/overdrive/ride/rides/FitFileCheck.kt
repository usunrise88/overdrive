package app.overdrive.ride.rides

/**
 * A tiny independent check of a FIT file's framing (docs/protocols/fit.md section 1): the 14-byte header and its CRC, the
 * data size, and the CRC-16 of the whole file. Garmin's published nibble-table algorithm, written out here so the test
 * does not use the code it checks. (The same helper is in the `data` module's tests; test code is not shared.)
 */
object FitFileCheck {
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

    fun crc16(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var crc = 0
        for (i in from until to) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[byte and 0xF]
            tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[(byte shr 4) and 0xF]
        }
        return crc
    }

    private fun le16(
        bytes: ByteArray,
        at: Int,
    ) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(
        bytes: ByteArray,
        at: Int,
    ) = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)

    /** Throws with the first thing wrong; returns the data size of a sound file. */
    fun verify(bytes: ByteArray): Long {
        check(bytes.size > 16) { "Too short for a FIT file: ${bytes.size} bytes" }
        check(bytes[0].toInt() == 14) { "Header size ${bytes[0]}" }
        check(String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") { "No .FIT signature" }
        val dataSize = le32(bytes, 4)
        check(dataSize == (bytes.size - 16).toLong()) { "Data size $dataSize for a file of ${bytes.size} bytes" }
        check(le16(bytes, 12) == crc16(bytes, 0, 12)) { "Header CRC mismatch" }
        check(le16(bytes, bytes.size - 2) == crc16(bytes, 0, bytes.size - 2)) { "File CRC mismatch" }
        check(crc16(bytes, 0, bytes.size) == 0) { "CRC over the whole file is not 0" }
        return dataSize
    }
}
