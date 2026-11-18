package app.overdrive.core.ble.codec

/** What is wrong with bytes that arrived from the air. */
public enum class DecodeErrorKind {
    /** No bytes at all. */
    EMPTY,

    /** The payload ends before a field its own flags announce. A truncated record is rejected whole, never partly used. */
    TRUNCATED,

    /** The payload has a length the characteristic never has, such as a Fitness Machine Feature of 5 bytes. */
    WRONG_LENGTH,

    /** The first byte is not the marker this characteristic starts with. [DecodeError.needed] is the marker, [DecodeError.available] the byte found. */
    UNEXPECTED_MARKER,
}

/** A malformed payload: what, which [field] was being read, and how many bytes it [needed] against what was [available]. */
public data class DecodeError(
    val kind: DecodeErrorKind,
    val field: String,
    val needed: Int,
    val available: Int,
)

/**
 * The outcome of decoding a payload. Everything decoded here arrives from the air, so a malformed payload is a normal
 * result, not an exception: no function in this package throws for any input.
 */
public sealed interface DecodeResult<out T> {
    /** The payload was well formed. */
    public data class Ok<out T>(
        val value: T,
    ) : DecodeResult<T>

    /** The payload was malformed. */
    public data class Malformed(
        val error: DecodeError,
    ) : DecodeResult<Nothing>
}

/** The decoded value, or null if the payload was malformed. */
public fun <T> DecodeResult<T>.valueOrNull(): T? = (this as? DecodeResult.Ok<T>)?.value

/** The error, or null if the payload was well formed. */
public fun <T> DecodeResult<T>.errorOrNull(): DecodeError? = (this as? DecodeResult.Malformed)?.error

/** A little-endian cursor over a payload. Callers check [has] first, so reading never goes out of bounds. */
internal class ByteReader(
    private val bytes: ByteArray,
    var position: Int = 0,
) {
    val size: Int get() = bytes.size
    val remaining: Int get() = bytes.size - position

    fun has(count: Int): Boolean = remaining >= count

    fun u8(): Int = bytes[position++].toInt() and BYTE_MASK

    fun u16(): Int {
        val low = u8()
        return low or (u8() shl BITS_PER_BYTE)
    }

    fun s16(): Int = u16().toShort().toInt()

    fun u24(): Int {
        val low = u16()
        return low or (u8() shl 2 * BITS_PER_BYTE)
    }

    fun u32(): Long {
        val low = u16().toLong()
        return low or (u16().toLong() shl 2 * BITS_PER_BYTE)
    }

    fun truncated(
        field: String,
        needed: Int,
    ): DecodeResult.Malformed = DecodeResult.Malformed(DecodeError(DecodeErrorKind.TRUNCATED, field, needed, remaining))

    companion object {
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8
    }
}

internal fun emptyPayload(): DecodeResult.Malformed =
    DecodeResult.Malformed(DecodeError(DecodeErrorKind.EMPTY, "payload", 1, 0))

/** Hex helpers for the session recorder and for tests: `4400C409` and back. */
public object Hex {
    private const val DIGITS = "0123456789ABCDEF"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xF
    private const val BYTE_MASK = 0xFF

    /** [bytes] as upper-case hex without separators. */
    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            out.append(DIGITS[value shr NIBBLE_BITS]).append(DIGITS[value and NIBBLE_MASK])
        }
        return out.toString()
    }

    /** Hex (spaces allowed, any case) as bytes, or null if [text] is not an even number of hex digits. */
    public fun decode(text: String): ByteArray? {
        val digits = text.filterNot { it == ' ' }
        if (digits.length % 2 != 0) return null
        val out = ByteArray(digits.length / 2)
        for (i in out.indices) {
            val high = digits[2 * i].digitToIntOrNull(HEX_RADIX) ?: return null
            val low = digits[2 * i + 1].digitToIntOrNull(HEX_RADIX) ?: return null
            out[i] = ((high shl NIBBLE_BITS) or low).toByte()
        }
        return out
    }

    private const val HEX_RADIX = 16
}
