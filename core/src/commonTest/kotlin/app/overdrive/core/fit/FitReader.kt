package app.overdrive.core.fit

/**
 * A FIT reader for tests, written from the facts of `docs/protocols/fit.md` and deliberately sharing no code with the
 * writer: its own CRC (bitwise, not the writer's nibble table), its own base-type table, and a generic parser that
 * learns every message from the file's own definition messages. It is strict: anything the format does not allow is a
 * [FitFormatException], so a file it reads is structurally sound.
 *
 * The profile facts below (message and field numbers, names, scales, base types) are a second transcription of fit.md
 * section 4, so a wrong number in the writer shows up as a field with the wrong name or type here.
 */
internal class FitFormatException(
    message: String,
) : Exception(message)

/** One field value: the base type number and the elements, as read (unsigned types as non-negative numbers). */
internal class FitValue(
    val baseTypeNumber: Int,
    val elements: List<Long>,
) {
    /** Whether every element is the invalid value of the base type: the field is a gap. */
    val isInvalid: Boolean get() = elements.all { it == FitReader.invalidValue(baseTypeNumber) }
}

/** A data message with its fields by field number. */
internal class FitMessage(
    val globalNumber: Int,
    val localType: Int,
    val fields: Map<Int, FitValue>,
) {
    val name: String get() = FitFacts.messageNames.getValue(globalNumber)

    /** The field called [fieldName] in this message's profile, or null if the message does not have it. */
    fun field(fieldName: String): FitValue? {
        val number = FitFacts.fieldsOf(globalNumber).entries.single { it.value.name == fieldName }.key
        return fields[number]
    }

    /** The raw integer of a scalar field, or null if it is absent or invalid. */
    fun raw(fieldName: String): Long? = field(fieldName)?.takeUnless { it.isInvalid }?.elements?.single()

    /** The value of a scalar field with its profile scale applied, or null if absent or invalid. */
    fun scaled(fieldName: String): Double? {
        val number = FitFacts.fieldsOf(globalNumber).entries.single { it.value.name == fieldName }
        return raw(fieldName)?.let { it.toDouble() / number.value.scale - number.value.offset }
    }

    /** Whether the message has the field at all (even as invalid). */
    fun has(fieldName: String): Boolean = field(fieldName) != null
}

/** A definition message as read. */
internal class FitDefinition(
    val localType: Int,
    val globalNumber: Int,
    val bigEndian: Boolean,
    val fields: List<Triple<Int, Int, Int>>, // number, size, base type byte
)

/** One item of the file's data section, in file order. */
internal sealed interface FitItem {
    class Definition(
        val definition: FitDefinition,
    ) : FitItem

    class Data(
        val message: FitMessage,
    ) : FitItem
}

internal class FitFile(
    val headerSize: Int,
    val protocolVersion: Int,
    val profileVersion: Int,
    val dataSize: Int,
    val items: List<FitItem>,
) {
    val messages: List<FitMessage> get() = items.filterIsInstance<FitItem.Data>().map { it.message }

    fun messages(name: String): List<FitMessage> = messages.filter { it.name == name }

    val definitions: List<FitDefinition> get() = items.filterIsInstance<FitItem.Definition>().map { it.definition }
}

internal object FitReader {
    private const val FIT_EPOCH_UNIX_SECONDS = 631_065_600L

    /** Unix seconds of a FIT timestamp. */
    fun unixSeconds(fitTime: Long): Long = fitTime + FIT_EPOCH_UNIX_SECONDS

    // Base type number (low 5 bits of the type byte) -> size and invalid value, from fit.md section 2.
    private class BaseType(
        val size: Int,
        val invalid: Long,
        val signed: Boolean,
    )

    private val baseTypes =
        mapOf(
            0 to BaseType(1, 0xFF, false), // enum
            1 to BaseType(1, 0x7F, true), // sint8
            2 to BaseType(1, 0xFF, false), // uint8
            3 to BaseType(2, 0x7FFF, true), // sint16
            4 to BaseType(2, 0xFFFF, false), // uint16
            5 to BaseType(4, 0x7FFFFFFFL, true), // sint32
            6 to BaseType(4, 0xFFFFFFFFL, false), // uint32
            10 to BaseType(1, 0, false), // uint8z
            11 to BaseType(2, 0, false), // uint16z
            12 to BaseType(4, 0, false), // uint32z
            13 to BaseType(1, 0xFF, false), // byte
        )

    fun invalidValue(baseTypeNumber: Int): Long = baseTypes.getValue(baseTypeNumber).invalid

    /** CRC-16/ARC computed bit by bit: reflected polynomial 0xA001, initial value 0, no final XOR. */
    fun crc16(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    private fun fail(message: String): Nothing = throw FitFormatException(message)

    fun parse(bytes: ByteArray): FitFile {
        if (bytes.size < 12 + 2) fail("File of ${bytes.size} bytes is shorter than a header and a CRC")

        fun u8(at: Int) = bytes[at].toInt() and 0xFF

        fun le(
            at: Int,
            count: Int,
        ): Long {
            var v = 0L
            for (i in 0 until count) v = v or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        val headerSize = u8(0)
        if (headerSize != 12 && headerSize != 14) fail("Header size $headerSize is not 12 or 14")
        val protocol = u8(1)
        val profile = le(2, 2).toInt()
        val dataSize = le(4, 4).toInt()
        if (bytes.decodeToString(8, 12) != ".FIT") fail("Missing .FIT signature")
        if (headerSize == 14) {
            val stored = le(12, 2).toInt()
            val computed = crc16(bytes, 0, 12)
            if (stored != computed) fail("Header CRC $stored, computed $computed")
        }
        if (headerSize + dataSize + 2 != bytes.size) {
            fail("Header says $headerSize + $dataSize + 2 bytes, file has ${bytes.size}")
        }
        val fileCrc = le(bytes.size - 2, 2).toInt()
        val computedFileCrc = crc16(bytes, 0, bytes.size - 2)
        if (fileCrc != computedFileCrc) fail("File CRC $fileCrc, computed $computedFileCrc")
        if (crc16(bytes, 0, bytes.size) != 0) fail("CRC over the whole file including its own CRC is not 0")

        val definitions = mutableMapOf<Int, FitDefinition>()
        val items = mutableListOf<FitItem>()
        var position = headerSize
        val end = headerSize + dataSize
        while (position < end) {
            val header = u8(position++)
            if (header and 0x80 != 0) fail("Compressed timestamp header at ${position - 1}")
            if (header and 0x10 != 0) fail("Reserved bit set in record header $header")
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                if (header and 0x20 != 0) fail("Developer fields are not expected")
                if (u8(position++) != 0) fail("Reserved byte of a definition is not 0")
                val architecture = u8(position++)
                if (architecture != 0 && architecture != 1) fail("Architecture $architecture")
                val global =
                    (
                        if (architecture ==
                            0
                        ) {
                            le(position, 2)
                        } else {
                            (u8(position).toLong() shl 8) or u8(position + 1).toLong()
                        }
                    ).toInt()
                position += 2
                val count = u8(position++)
                val fields = mutableListOf<Triple<Int, Int, Int>>()
                repeat(count) {
                    val number = u8(position++)
                    val size = u8(position++)
                    val type = u8(position++)
                    val baseType = baseTypes[type and 0x1F] ?: fail("Unknown base type byte $type")
                    val multiByte = baseType.size > 1
                    if ((type and 0x80 != 0) != multiByte) fail("Endian flag of base type byte $type")
                    if (size == 0 || size % baseType.size != 0) fail("Field $number size $size for base type $type")
                    fields += Triple(number, size, type)
                }
                if (fields.map { it.first }.toSet().size != fields.size) fail("Duplicate field in a definition")
                val definition = FitDefinition(local, global, architecture == 1, fields)
                definitions[local] = definition
                items += FitItem.Definition(definition)
            } else {
                val definition = definitions[local] ?: fail("Data message for local type $local before its definition")
                val values = mutableMapOf<Int, FitValue>()
                for ((number, size, type) in definition.fields) {
                    val baseType = baseTypes.getValue(type and 0x1F)
                    val elements =
                        (0 until size / baseType.size).map {
                            var raw = 0L
                            for (i in 0 until baseType.size) {
                                val byte = bytes[position + it * baseType.size + i].toLong() and 0xFF
                                val shift = if (definition.bigEndian) 8 * (baseType.size - 1 - i) else 8 * i
                                raw = raw or (byte shl shift)
                            }
                            if (baseType.signed &&
                                raw >= (1L shl (8 * baseType.size - 1))
                            ) {
                                raw - (1L shl (8 * baseType.size))
                            } else {
                                raw
                            }
                        }
                    position += size
                    values[number] = FitValue(type and 0x1F, elements)
                }
                if (position > end) fail("A data message runs past the end of the data section")
                items += FitItem.Data(FitMessage(definition.globalNumber, local, values))
            }
        }
        if (position != end) fail("Data section ends at $position, header says $end")
        return FitFile(headerSize, protocol, profile, dataSize, items)
    }
}

/** What the profile says about a field: name, scale and the base type number it must have. */
internal class FitFieldFact(
    val name: String,
    val baseTypeNumber: Int,
    val scale: Int = 1,
    /** The profile's offset: the value is `raw / scale - offset` (altitude: a fifth of a metre above a datum 500 m down). */
    val offset: Int = 0,
)

/** A second transcription of the profile facts of fit.md section 4, for naming and type-checking what the writer wrote. */
internal object FitFacts {
    private const val ENUM = 0
    private const val SINT32 = 5
    private const val UINT8 = 2
    private const val UINT16 = 4
    private const val UINT32 = 6
    private const val UINT32Z = 12

    val messageNames =
        mapOf(
            0 to "file_id",
            18 to "session",
            19 to "lap",
            20 to "record",
            21 to "event",
            34 to "activity",
            78 to "hrv",
        )

    private val common = mapOf(253 to FitFieldFact("timestamp", UINT32))

    private val byMessage: Map<Int, Map<Int, FitFieldFact>> =
        mapOf(
            0 to
                mapOf(
                    0 to FitFieldFact("type", ENUM),
                    1 to FitFieldFact("manufacturer", UINT16),
                    2 to FitFieldFact("product", UINT16),
                    3 to FitFieldFact("serial_number", UINT32Z),
                    4 to FitFieldFact("time_created", UINT32),
                    5 to FitFieldFact("number", UINT16),
                ),
            21 to
                mapOf(
                    0 to FitFieldFact("event", ENUM),
                    1 to FitFieldFact("event_type", ENUM),
                    3 to FitFieldFact("data", UINT32),
                ),
            20 to
                mapOf(
                    0 to FitFieldFact("position_lat", SINT32), // semicircles
                    1 to FitFieldFact("position_long", SINT32),
                    2 to FitFieldFact("altitude", UINT16, scale = 5, offset = 500),
                    3 to FitFieldFact("heart_rate", UINT8),
                    4 to FitFieldFact("cadence", UINT8),
                    5 to FitFieldFact("distance", UINT32, 100),
                    6 to FitFieldFact("speed", UINT16, 1000),
                    7 to FitFieldFact("power", UINT16),
                    73 to FitFieldFact("enhanced_speed", UINT32, 1000),
                    78 to FitFieldFact("enhanced_altitude", UINT32, scale = 5, offset = 500),
                ),
            19 to
                mapOf(
                    2 to FitFieldFact("start_time", UINT32),
                    7 to FitFieldFact("total_elapsed_time", UINT32, 1000),
                    8 to FitFieldFact("total_timer_time", UINT32, 1000),
                    9 to FitFieldFact("total_distance", UINT32, 100),
                    13 to FitFieldFact("avg_speed", UINT16, 1000),
                    14 to FitFieldFact("max_speed", UINT16, 1000),
                    15 to FitFieldFact("avg_heart_rate", UINT8),
                    16 to FitFieldFact("max_heart_rate", UINT8),
                    17 to FitFieldFact("avg_cadence", UINT8),
                    18 to FitFieldFact("max_cadence", UINT8),
                    19 to FitFieldFact("avg_power", UINT16),
                    20 to FitFieldFact("max_power", UINT16),
                    24 to FitFieldFact("lap_trigger", ENUM),
                    25 to FitFieldFact("sport", ENUM),
                    39 to FitFieldFact("sub_sport", ENUM),
                    254 to FitFieldFact("message_index", UINT16),
                ),
            18 to
                mapOf(
                    2 to FitFieldFact("start_time", UINT32),
                    5 to FitFieldFact("sport", ENUM),
                    6 to FitFieldFact("sub_sport", ENUM),
                    7 to FitFieldFact("total_elapsed_time", UINT32, 1000),
                    8 to FitFieldFact("total_timer_time", UINT32, 1000),
                    9 to FitFieldFact("total_distance", UINT32, 100),
                    14 to FitFieldFact("avg_speed", UINT16, 1000),
                    15 to FitFieldFact("max_speed", UINT16, 1000),
                    16 to FitFieldFact("avg_heart_rate", UINT8),
                    17 to FitFieldFact("max_heart_rate", UINT8),
                    18 to FitFieldFact("avg_cadence", UINT8),
                    19 to FitFieldFact("max_cadence", UINT8),
                    20 to FitFieldFact("avg_power", UINT16),
                    21 to FitFieldFact("max_power", UINT16),
                    25 to FitFieldFact("first_lap_index", UINT16),
                    26 to FitFieldFact("num_laps", UINT16),
                    28 to FitFieldFact("trigger", ENUM),
                    34 to FitFieldFact("normalized_power", UINT16),
                    35 to FitFieldFact("training_stress_score", UINT16, 10),
                    36 to FitFieldFact("intensity_factor", UINT16, 1000),
                    48 to FitFieldFact("total_work", UINT32),
                ),
            34 to
                mapOf(
                    0 to FitFieldFact("total_timer_time", UINT32, 1000),
                    1 to FitFieldFact("num_sessions", UINT16),
                    2 to FitFieldFact("type", ENUM),
                    3 to FitFieldFact("event", ENUM),
                    4 to FitFieldFact("event_type", ENUM),
                    5 to FitFieldFact("local_timestamp", UINT32),
                ),
            78 to mapOf(0 to FitFieldFact("time", UINT16, 1000)),
        )

    fun fieldsOf(globalNumber: Int): Map<Int, FitFieldFact> =
        (if (globalNumber == 0 || globalNumber == 78) emptyMap() else common) + byMessage.getValue(globalNumber)

    /**
     * Checks every definition and data message of [file] against the profile facts: each field exists in its
     * message, has the profile's base type, and a data message has exactly the fields of its definition. Returns the
     * problems found; empty means conformant.
     */
    fun problems(file: FitFile): List<String> {
        val problems = mutableListOf<String>()
        for (definition in file.definitions) {
            val name = messageNames[definition.globalNumber]
            if (name == null) {
                problems += "Unknown message ${definition.globalNumber}"
                continue
            }
            val facts = fieldsOf(definition.globalNumber)
            for ((number, _, type) in definition.fields) {
                val fact = facts[number]
                if (fact == null) {
                    problems += "$name has no field $number"
                } else if (fact.baseTypeNumber != (type and 0x1F)) {
                    problems += "$name.${fact.name} is base type ${type and 0x1F}, profile says ${fact.baseTypeNumber}"
                }
            }
        }
        return problems
    }
}
