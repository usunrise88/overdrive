package app.overdrive.core.ble

import kotlin.jvm.JvmInline

/**
 * A Bluetooth UUID in its canonical 128-bit lower-case form (`00002ad2-0000-1000-8000-00805f9b34fb`). Equality is by
 * value, so `GattUuid.short(0x2AD2)` equals the parsed full string in any case. Core has no Android types: the
 * transport converts to and from `java.util.UUID`.
 */
@JvmInline
public value class GattUuid private constructor(
    /** The 36-character lower-case string. */
    public val value: String,
) {
    /**
     * The 16-bit form (four upper-case hex digits, such as `2AD2`) when this UUID is on the Bluetooth SIG base, else
     * the full string. This is the spelling recorded sessions use (docs/protocols/ftms-hr.md §7.1).
     */
    public val shortForm: String
        get() =
            if (value.startsWith(SHORT_PREFIX) && value.endsWith(BASE_SUFFIX)) {
                value.substring(SHORT_FROM, SHORT_TO).uppercase()
            } else {
                value
            }

    override fun toString(): String = shortForm

    public companion object {
        private const val SHORT_PREFIX = "0000"
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
        private const val SHORT_FROM = 4
        private const val SHORT_TO = 8
        private const val FULL_LENGTH = 36
        private const val MAX_SHORT = 0xFFFF
        private val dashPositions = intArrayOf(8, 13, 18, 23)

        /** The UUID `0000xxxx-0000-1000-8000-00805f9b34fb` of a 16-bit [assigned] number. */
        public fun short(assigned: Int): GattUuid {
            require(assigned in 0..MAX_SHORT) { "A 16-bit UUID is 0 to 0xFFFF, got $assigned" }
            return GattUuid(SHORT_PREFIX + assigned.toString(16).padStart(SHORT_TO - SHORT_FROM, '0') + BASE_SUFFIX)
        }

        /**
         * Reads `2AD2`, `0x2ad2` or a full 128-bit UUID with dashes, in any case. Returns null for anything else;
         * recorded sessions come from disk, so this never throws.
         */
        public fun parse(text: String): GattUuid? {
            val trimmed = text.trim().removePrefix("0x").removePrefix("0X")
            return when {
                trimmed.length == SHORT_TO - SHORT_FROM && trimmed.all(::isHexDigit) -> {
                    short(trimmed.toInt(16))
                }

                trimmed.length == FULL_LENGTH && isFullUuid(trimmed) -> {
                    GattUuid(trimmed.lowercase())
                }

                else -> {
                    null
                }
            }
        }

        private fun isFullUuid(text: String): Boolean =
            text.indices.all { i -> if (i in dashPositions) text[i] == '-' else isHexDigit(text[i]) }

        private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
    }
}

/** The Fitness Machine Service and its characteristics (docs/protocols/ftms-hr.md §1). */
public object FtmsUuids {
    public val SERVICE: GattUuid = GattUuid.short(0x1826)
    public val INDOOR_BIKE_DATA: GattUuid = GattUuid.short(0x2AD2)
    public val FEATURE: GattUuid = GattUuid.short(0x2ACC)
    public val CONTROL_POINT: GattUuid = GattUuid.short(0x2AD9)
    public val STATUS: GattUuid = GattUuid.short(0x2ADA)
}

/** The Heart Rate Service and its characteristics (docs/protocols/ftms-hr.md §1). */
public object HeartRateUuids {
    public val SERVICE: GattUuid = GattUuid.short(0x180D)
    public val MEASUREMENT: GattUuid = GattUuid.short(0x2A37)
    public val BODY_SENSOR_LOCATION: GattUuid = GattUuid.short(0x2A38)
}

/**
 * Device Information Service characteristics that identify a unit (serial number, system id, device name…). The session
 * recorder never writes their values (docs/protocols/ftms-hr.md §7.1, `CLAUDE.md`: the repository is public).
 */
public object IdentityUuids {
    public val DEVICE_NAME: GattUuid = GattUuid.short(0x2A00)
    public val SYSTEM_ID: GattUuid = GattUuid.short(0x2A23)
    public val MODEL_NUMBER: GattUuid = GattUuid.short(0x2A24)
    public val SERIAL_NUMBER: GattUuid = GattUuid.short(0x2A25)
    public val FIRMWARE_REVISION: GattUuid = GattUuid.short(0x2A26)
    public val HARDWARE_REVISION: GattUuid = GattUuid.short(0x2A27)
    public val SOFTWARE_REVISION: GattUuid = GattUuid.short(0x2A28)
    public val MANUFACTURER_NAME: GattUuid = GattUuid.short(0x2A29)

    /** Every characteristic above. */
    public val ALL: Set<GattUuid> =
        setOf(
            DEVICE_NAME,
            SYSTEM_ID,
            MODEL_NUMBER,
            SERIAL_NUMBER,
            FIRMWARE_REVISION,
            HARDWARE_REVISION,
            SOFTWARE_REVISION,
            MANUFACTURER_NAME,
        )
}
