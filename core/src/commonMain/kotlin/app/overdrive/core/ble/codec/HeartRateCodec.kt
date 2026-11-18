package app.overdrive.core.ble.codec

/** Whether the strap reports skin contact (docs/protocols/ftms-hr.md §6.1, bits 1 and 2 of the flags). */
public enum class SensorContact {
    /** The sensor does not detect contact at all, so the heart rate is to be trusted as it is. */
    NOT_SUPPORTED,

    /** The sensor detects contact and there is none: the strap is off the chest. */
    NOT_DETECTED,

    /** The sensor detects contact and there is some. */
    DETECTED,
}

/**
 * How the two contact bits are read. They are two bits: `0` and `1` mean "not supported", `2` "supported, not detected",
 * `3` "supported and detected".
 *
 * The SIG XML says only `3` means detected; pycycling treats any nonzero value as detected, which reads a strap that is
 * off the chest (`2`) and one that has no contact sensor (`1`) as in contact (docs/protocols/ftms-hr.md §6.5, vectors
 * HR4 and HR5). A bench result for FT 11 is applied by changing [DEFAULT] in one line.
 */
public enum class HeartRateContactRule {
    /** Bits `0` and `1` are [SensorContact.NOT_SUPPORTED], `2` is [SensorContact.NOT_DETECTED], `3` is [SensorContact.DETECTED]. */
    SIG_XML,

    /** Any nonzero value is [SensorContact.DETECTED], zero is [SensorContact.NOT_SUPPORTED] (pycycling's reading). */
    ANY_NONZERO,
    ;

    public companion object {
        /** **The one-line flip for bench question FT 11 (spec docs/protocols/ftms-hr.md §6.5).** */
        public val DEFAULT: HeartRateContactRule = SIG_XML
    }
}

/** One Heart Rate Measurement notification (characteristic `0x2A37`), decoded as the raw values of the wire. */
public data class HeartRateMeasurement(
    /** The flags byte as received. */
    val flags: Int,
    /** Beats per minute, from an 8-bit or 16-bit field. */
    val bpm: Int,
    val contact: SensorContact,
    /** Energy expended in **kJ**, or null when the strap does not send it. */
    val energyKilojoules: Int?,
    /**
     * RR intervals in **1/1024 s, unconverted**, oldest first. Null when the RR flag is clear (no RR data); an empty list
     * when it is set with no bytes following (§6.3). Conversion to milliseconds, with its rounding rule, belongs to the
     * domain layer: [app.overdrive.core.device.RrIntervals].
     */
    val rrIntervalsRaw: List<Int>?,
    /** An odd byte followed the last whole RR interval and was ignored (§6.3). */
    val strayRrByte: Boolean,
)

/** Decodes Heart Rate Measurement notifications (docs/protocols/ftms-hr.md §6). Pure and exception-free. */
public object HeartRateCodec {
    private const val BIT_FORMAT_16 = 0x01
    private const val SHIFT_CONTACT = 1
    private const val MASK_CONTACT = 0x03
    private const val BIT_ENERGY = 0x08
    private const val BIT_RR = 0x10
    private const val CONTACT_NOT_DETECTED = 2
    private const val CONTACT_DETECTED = 3

    /**
     * Parses one notification: flags, heart rate (8 or 16 bit), optional energy, then RR intervals to the end of the
     * payload. A payload that ends inside a field its flags announce is [DecodeResult.Malformed].
     *
     * @param contactRule the disputed reading of the contact bits, see [HeartRateContactRule].
     */
    public fun parse(
        bytes: ByteArray,
        contactRule: HeartRateContactRule = HeartRateContactRule.DEFAULT,
    ): DecodeResult<HeartRateMeasurement> {
        if (bytes.isEmpty()) return emptyPayload()
        val reader = ByteReader(bytes)
        val flags = reader.u8()
        val bpmSize = if (flags and BIT_FORMAT_16 != 0) 2 else 1
        if (!reader.has(bpmSize)) return reader.truncated("heart rate", bpmSize)
        val bpm = if (bpmSize == 2) reader.u16() else reader.u8()

        var energy: Int? = null
        if (flags and BIT_ENERGY != 0) {
            if (!reader.has(2)) return reader.truncated("energy expended", 2)
            energy = reader.u16()
        }
        var rr: ArrayList<Int>? = null
        var stray = false
        if (flags and BIT_RR != 0) {
            rr = ArrayList(reader.remaining / 2)
            while (reader.has(2)) rr += reader.u16()
            stray = reader.has(1)
        }
        return DecodeResult.Ok(
            HeartRateMeasurement(
                flags = flags,
                bpm = bpm,
                contact = contactOf((flags shr SHIFT_CONTACT) and MASK_CONTACT, contactRule),
                energyKilojoules = energy,
                rrIntervalsRaw = rr,
                strayRrByte = stray,
            ),
        )
    }

    private fun contactOf(
        bits: Int,
        rule: HeartRateContactRule,
    ): SensorContact =
        when (rule) {
            HeartRateContactRule.SIG_XML -> {
                when (bits) {
                    CONTACT_DETECTED -> SensorContact.DETECTED
                    CONTACT_NOT_DETECTED -> SensorContact.NOT_DETECTED
                    else -> SensorContact.NOT_SUPPORTED
                }
            }

            HeartRateContactRule.ANY_NONZERO -> {
                if (bits != 0) SensorContact.DETECTED else SensorContact.NOT_SUPPORTED
            }
        }
}
