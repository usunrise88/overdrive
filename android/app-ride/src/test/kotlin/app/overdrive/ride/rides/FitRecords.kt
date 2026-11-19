package app.overdrive.ride.rides

/**
 * The `record` messages of a FIT file (global message 20) as the field numbers they carry and, for each record, the raw value of
 * every field, read by a small parser of the framing of `docs/protocols/fit.md` section 1 (little endian, normal headers, no developer
 * fields: what the tablet's writer produces). Test-only, sharing no code with the writer: it is how an app-level test sees whether a ride's
 * file has positions and an altitude without reading the writer's code.
 */
class FitRecords(
    bytes: ByteArray,
) {
    /** The field numbers of the `record` definition, in order. */
    val fieldNumbers: List<Int>

    /** For each record message, its field number to the raw value as an unsigned or signed integer (invalid values included). */
    val records: List<Map<Int, Long>>

    init {
        var position = bytes[0].toInt() and 0xFF
        val end = position + le(bytes, 4, 4).toInt()
        val definitions = HashMap<Int, Pair<Int, List<Triple<Int, Int, Int>>>>()
        var recordFields = emptyList<Int>()
        val found = mutableListOf<Map<Int, Long>>()
        while (position < end) {
            val header = bytes[position++].toInt() and 0xFF
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                position += 2 // reserved, architecture
                val global = le(bytes, position, 2).toInt()
                position += 2
                val count = bytes[position++].toInt() and 0xFF
                val fields =
                    (0 until count).map {
                        Triple(
                            bytes[position].toInt() and 0xFF,
                            bytes[position + 1].toInt() and 0xFF,
                            bytes[position + 2].toInt() and 0xFF,
                        ).also { position += 3 }
                    }
                definitions[local] = global to fields
                if (global == RECORD) recordFields = fields.map { it.first }
            } else {
                val (global, fields) = checkNotNull(definitions[local]) { "no definition for local type $local" }
                val values = HashMap<Int, Long>()
                for ((number, size, type) in fields) {
                    if (global == RECORD) {
                        val raw = le(bytes, position, size)
                        val signed = (type and 0x1F) == SINT32 && raw >= 1L shl (SIGN_BIT)
                        values[number] = if (signed) raw - (1L shl (SIGN_BIT + 1)) else raw
                    }
                    position += size
                }
                if (global == RECORD) found += values
            }
        }
        fieldNumbers = recordFields
        records = found
    }

    private fun le(
        bytes: ByteArray,
        at: Int,
        count: Int,
    ): Long {
        var value = 0L
        for (i in 0 until count) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    /** Whether the records carry `position_lat` and `position_long`. */
    val hasPositions: Boolean get() = 0 in fieldNumbers && 1 in fieldNumbers

    /** Whether the records carry `altitude` and `enhanced_altitude`. */
    val hasAltitude: Boolean get() = 2 in fieldNumbers && 78 in fieldNumbers

    private companion object {
        const val RECORD = 20
        const val SINT32 = 5
        const val SIGN_BIT = 31
    }
}
