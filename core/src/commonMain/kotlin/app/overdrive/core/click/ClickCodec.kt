package app.overdrive.core.click

import app.overdrive.core.ble.codec.DecodeError
import app.overdrive.core.ble.codec.DecodeErrorKind
import app.overdrive.core.ble.codec.DecodeResult
import app.overdrive.core.ble.codec.Hex
import app.overdrive.core.ble.codec.emptyPayload

/**
 * One message from the Click (device to host), decoded far enough to use (docs/protocols/click-v2.md §4). Every message
 * starts with a one-byte type; the rest is a protobuf-style body.
 */
public sealed interface ClickMessage {
    /**
     * Type `0x23`: the state of every button, field 1 as a 32-bit word in which **a clear bit means pressed** (§4). Use
     * [pressed] rather than reading the bits yourself.
     */
    public data class ButtonState(
        val bitmap: Int,
    ) : ClickMessage {
        /** The mask of pressed buttons: the bits of [bitmap] that are clear. */
        val pressed: Int get() = ClickBits.pressedOf(bitmap)
    }

    /** Type `0x15`: the idle heartbeat, an empty body (§4). Proof that the controller is talking, nothing more. */
    public data object Heartbeat : ClickMessage

    /**
     * Type `0x19`: the battery, field 2 as a percentage (§4; the notes' example `19 10 1F` is 31 %). The value is as sent:
     * the device adapter ignores anything above 100.
     */
    public data class Battery(
        val percent: Int,
    ) : ClickMessage

    /** Type `0xFE`: the disconnect notice a controller sends when another host takes it (§4). */
    public data object DisconnectNotice : ClickMessage

    /**
     * Any other type, reported and never an error: `0x3C` is documented as "unknown Click v2 type" (§4, bench question 10)
     * and a controller may send others. [body] is everything after the type byte, untouched.
     */
    public class Unrecognised(
        public val type: Int,
        body: ByteArray,
    ) : ClickMessage {
        private val bytes: ByteArray = body.copyOf()

        /** A copy of the bytes after the type. */
        public val body: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Unrecognised && type == other.type && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = type * HASH_FACTOR + bytes.contentHashCode()

        override fun toString(): String = "Unrecognised(type=0x${type.toString(HEX_RADIX)}, ${bytes.size} body bytes)"

        private companion object {
            const val HASH_FACTOR = 31
            const val HEX_RADIX = 16
        }
    }
}

/** The reply to the `RideOn` handshake: `RideOn`, two version bytes, and optionally a tail (click-v2 §3.1). */
public data class HandshakeReply(
    /** The two bytes after `RideOn` as one number, `0x0203` for the Click v2 the notes expect; null for a bare `RideOn`. */
    val version: Int?,
    /** How many bytes followed the version: the public key the notes mention, which is ignored. */
    val tailLength: Int,
) {
    /** The version as the notes write it, `02 03`; null for a bare `RideOn`. */
    val versionText: String?
        get() =
            version?.let {
                Hex.encode(byteArrayOf((it shr BITS_PER_BYTE).toByte(), it.toByte())).chunked(2).joinToString(" ")
            }

    private companion object {
        const val BITS_PER_BYTE = 8
    }
}

/**
 * The Click v2 wire format as pure functions: the bytes the host writes, and the parsing of what arrives
 * (docs/protocols/click-v2.md §3 and §4). Everything parsed here comes from the air, so **no function throws for any input**:
 * a malformed payload is a typed [DecodeResult.Malformed], and a type this code does not know is
 * [ClickMessage.Unrecognised], not an error.
 *
 * The encryption of §3.3 is **not implemented**: the notes mark it unconfirmed for Click v2 and the plain path is what they
 * support (§7). Frames are read as plain protobuf-style bytes.
 */
public object ClickCodec {
    /** Message type of the button state (§4). */
    public const val TYPE_BUTTON_STATE: Int = 0x23

    /** Message type of the idle heartbeat (§4). */
    public const val TYPE_HEARTBEAT: Int = 0x15

    /** Message type of the battery report (§4). */
    public const val TYPE_BATTERY: Int = 0x19

    /** Message type the notes call unknown (§4); reported as [ClickMessage.Unrecognised]. */
    public const val TYPE_UNKNOWN_CLICK_V2: Int = 0x3C

    /** Message type of the disconnect notice (§4). */
    public const val TYPE_DISCONNECT: Int = 0xFE

    /** The handshake write: `52 69 64 65 4F 6E` ("RideOn"), nothing after it (§3.1). */
    public fun handshake(): ByteArray = RIDE_ON_BYTES.copyOf()

    /** The optional post-handshake write `FF 04 00`, whose meaning is unknown (§3.1, bench question 6). */
    public fun postHandshake(): ByteArray = byteArrayOf(POST_FF.toByte(), POST_04.toByte(), 0x00)

    /**
     * The neutral frame, `23 08 FF FF FF FF 0F`: type, field 1, `0xFFFFFFFF` (§4). The notes know it as what BikeControl
     * sends to a host when it emulates a controller; the type byte is **inferred**, and it is not known to mean anything to
     * the controller. It is the default of the optional keep-alive (`ClickOptions.keepAliveFrame`, bench question 7).
     */
    public fun neutralFrame(): ByteArray = byteArrayOf(TYPE_BUTTON_STATE.toByte()) + NEUTRAL_BODY

    /** Parses one message from the ASYNC characteristic (or from SYNC_TX after the handshake). */
    public fun parse(bytes: ByteArray): DecodeResult<ClickMessage> {
        if (bytes.isEmpty()) return emptyPayload()
        val type = bytes[0].toInt() and BYTE_MASK
        val body = bytes.copyOfRange(1, bytes.size)
        return when (type) {
            TYPE_BUTTON_STATE -> parseButtonState(body)
            TYPE_HEARTBEAT -> DecodeResult.Ok(ClickMessage.Heartbeat)
            TYPE_BATTERY -> parseBattery(body)
            TYPE_DISCONNECT -> DecodeResult.Ok(ClickMessage.DisconnectNotice)
            else -> DecodeResult.Ok(ClickMessage.Unrecognised(type, body))
        }
    }

    /**
     * Parses the reply to the handshake: `RideOn`, two version bytes, then whatever follows (the notes: a public key,
     * logged and not used). A bare `RideOn` is accepted with no version (Zwift Play's "unencrypted mode", §3.2). Anything
     * that does not start with `RideOn` is [DecodeErrorKind.UNEXPECTED_MARKER]; a reply of seven bytes has half a version.
     */
    public fun parseHandshakeReply(bytes: ByteArray): DecodeResult<HandshakeReply> {
        if (bytes.isEmpty()) return emptyPayload()
        val prefix = minOf(bytes.size, RIDE_ON_BYTES.size)
        for (i in 0 until prefix) {
            if (bytes[i] != RIDE_ON_BYTES[i]) {
                return DecodeResult.Malformed(
                    DecodeError(
                        DecodeErrorKind.UNEXPECTED_MARKER,
                        "handshake reply",
                        RIDE_ON_BYTES[i].toInt() and BYTE_MASK,
                        bytes[i].toInt() and BYTE_MASK,
                    ),
                )
            }
        }
        if (bytes.size < RIDE_ON_BYTES.size) {
            return DecodeResult.Malformed(
                DecodeError(DecodeErrorKind.TRUNCATED, "handshake reply", RIDE_ON_BYTES.size, bytes.size),
            )
        }
        val afterWord = bytes.size - RIDE_ON_BYTES.size
        return when {
            afterWord == 0 -> {
                DecodeResult.Ok(HandshakeReply(version = null, tailLength = 0))
            }

            afterWord < VERSION_BYTES -> {
                DecodeResult.Malformed(
                    DecodeError(DecodeErrorKind.TRUNCATED, "handshake version", VERSION_BYTES, afterWord),
                )
            }

            else -> {
                val high = bytes[RIDE_ON_BYTES.size].toInt() and BYTE_MASK
                val low = bytes[RIDE_ON_BYTES.size + 1].toInt() and BYTE_MASK
                DecodeResult.Ok(HandshakeReply((high shl BITS_PER_BYTE) or low, afterWord - VERSION_BYTES))
            }
        }
    }

    // region protobuf-style bodies

    /** The value of the last varint with number [wanted] in [body], or a typed error; every other field is skipped. */
    private fun varintField(
        body: ByteArray,
        wanted: Int,
        name: String,
    ): DecodeResult<Long> {
        val reader = ProtoReader(body)
        var found: Long? = null
        while (reader.hasMore()) {
            val tag = reader.varint() ?: return reader.failure("field tag")
            val field = (tag ushr WIRE_TYPE_BITS).toInt()
            when (val wire = (tag and WIRE_TYPE_MASK).toInt()) {
                WIRE_VARINT -> {
                    val value = reader.varint() ?: return reader.failure("field $field")
                    if (field == wanted) found = value
                }

                WIRE_64_BIT -> {
                    if (!reader.skip(EIGHT)) return reader.failure("field $field")
                }

                WIRE_LENGTH -> {
                    val length = reader.varint() ?: return reader.failure("field $field length")
                    if (length < 0 || length > reader.remaining || !reader.skip(length.toInt())) {
                        return reader.failure("field $field")
                    }
                }

                WIRE_32_BIT -> {
                    if (!reader.skip(FOUR)) return reader.failure("field $field")
                }

                else -> {
                    // Groups and the reserved wire types are not part of these messages: stop, never guess.
                    return DecodeResult.Malformed(DecodeError(DecodeErrorKind.UNEXPECTED_MARKER, "wire type", 0, wire))
                }
            }
        }
        val value = found ?: return DecodeResult.Malformed(DecodeError(DecodeErrorKind.TRUNCATED, name, 1, 0))
        return DecodeResult.Ok(value)
    }

    private fun parseButtonState(body: ByteArray): DecodeResult<ClickMessage> =
        when (val word = varintField(body, FIELD_BITMAP, "button bitmap")) {
            // A uint32 in a varint: the low 32 bits are the word (a longer encoding of a negative int32 is cut the same way).
            is DecodeResult.Ok -> DecodeResult.Ok(ClickMessage.ButtonState((word.value and WORD_MASK).toInt()))

            is DecodeResult.Malformed -> word
        }

    private fun parseBattery(body: ByteArray): DecodeResult<ClickMessage> =
        when (val percent = varintField(body, FIELD_BATTERY, "battery percent")) {
            is DecodeResult.Ok -> {
                DecodeResult.Ok(
                    ClickMessage.Battery(percent.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
                )
            }

            is DecodeResult.Malformed -> {
                percent
            }
        }

    /** A cursor over a protobuf-style body; every read reports a short or overlong field instead of throwing. */
    private class ProtoReader(
        private val bytes: ByteArray,
    ) {
        private var position = 0
        private var failedKind = DecodeErrorKind.TRUNCATED

        val remaining: Int get() = bytes.size - position

        fun hasMore(): Boolean = position < bytes.size

        fun skip(count: Int): Boolean {
            if (count < 0 || count > remaining) return false
            position += count
            return true
        }

        /** The next varint, or null when the bytes end inside it or it is longer than ten bytes (see [failure]). */
        fun varint(): Long? {
            var result = 0L
            var shift = 0
            for (i in 0 until MAX_VARINT_BYTES) {
                if (position >= bytes.size) {
                    failedKind = DecodeErrorKind.TRUNCATED
                    return null
                }
                val byte = bytes[position++].toInt() and BYTE_MASK
                result = result or ((byte and VARINT_PAYLOAD).toLong() shl shift)
                if (byte and VARINT_MORE == 0) return result
                shift += VARINT_BITS
            }
            failedKind = DecodeErrorKind.WRONG_LENGTH
            return null
        }

        fun failure(field: String): DecodeResult.Malformed =
            DecodeResult.Malformed(DecodeError(failedKind, field, 1, remaining))
    }

    // endregion

    private val RIDE_ON_BYTES = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)
    private val NEUTRAL_BODY =
        byteArrayOf(0x08, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)

    private const val POST_FF = 0xFF
    private const val POST_04 = 0x04
    private const val VERSION_BYTES = 2
    private const val FIELD_BITMAP = 1
    private const val FIELD_BATTERY = 2
    private const val WIRE_VARINT = 0
    private const val WIRE_64_BIT = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_32_BIT = 5
    private const val WIRE_TYPE_BITS = 3
    private const val WIRE_TYPE_MASK = 0x07L
    private const val EIGHT = 8
    private const val FOUR = 4
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_MORE = 0x80
    private const val VARINT_BITS = 7
    private const val MAX_VARINT_BYTES = 10
    private const val WORD_MASK = 0xFFFFFFFFL
}
