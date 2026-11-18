package app.overdrive.core.ble.fake

import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickCodec

/**
 * The inverse of `ClickCodec`, for the simulated Click and for round-trip tests: the bytes a controller sends
 * (docs/protocols/click-v2.md §3.1, §4). A button state is type `0x23`, field 1 (tag `0x08`) as a varint of the 32-bit word,
 * so the all-released frame is `23 08 FF FF FF FF 0F`.
 */
public object ClickEncoder {
    private const val TAG_FIELD_1_VARINT = 0x08
    private const val TAG_FIELD_2_VARINT = 0x10
    private const val WORD_MASK = 0xFFFFFFFFL
    private const val VARINT_MORE = 0x80
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_BITS = 7

    /** A button state frame for the bitmap [bitmap] (a clear bit is a pressed button). */
    public fun buttonState(bitmap: Int): ByteArray =
        byteArrayOf(ClickCodec.TYPE_BUTTON_STATE.toByte(), TAG_FIELD_1_VARINT.toByte()) +
            varint(bitmap.toLong() and WORD_MASK)

    /** A button state frame in which exactly the buttons of [pressed] (1 for pressed) are down. */
    public fun pressed(pressed: Int): ByteArray = buttonState(ClickBits.bitmapOf(pressed))

    /** The idle heartbeat: `15`. */
    public fun heartbeat(): ByteArray = byteArrayOf(ClickCodec.TYPE_HEARTBEAT.toByte())

    /** A battery report: `19 10 <percent>`; `19 10 1F` is 31 %. */
    public fun battery(percent: Int): ByteArray =
        byteArrayOf(ClickCodec.TYPE_BATTERY.toByte(), TAG_FIELD_2_VARINT.toByte()) + varint(percent.toLong())

    /** The disconnect notice: `FE`. */
    public fun disconnectNotice(): ByteArray = byteArrayOf(ClickCodec.TYPE_DISCONNECT.toByte())

    /** A frame of any [type] with [body] after it, for the types the codec reports as unrecognised. */
    public fun frame(
        type: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray = byteArrayOf(type.toByte()) + body

    /**
     * The reply to the handshake: `RideOn`, the two bytes of [version], then [tailLength] bytes of a stand-in for the public
     * key (a repeating counter, not a key). A [version] of null is a bare `RideOn`.
     */
    public fun handshakeReply(
        version: Int? = DEFAULT_VERSION,
        tailLength: Int = 0,
    ): ByteArray {
        val head = ClickCodec.handshake()
        if (version == null) return head
        val tail = ByteArray(tailLength) { (it + 1).toByte() }
        return head + byteArrayOf((version shr BITS_PER_BYTE).toByte(), version.toByte()) + tail
    }

    /** The protobuf varint of [value] (unsigned, up to 64 bits). */
    public fun varint(value: Long): ByteArray {
        val out = ArrayList<Byte>()
        var rest = value
        do {
            var byte = (rest and VARINT_PAYLOAD.toLong()).toInt()
            rest = rest ushr VARINT_BITS
            if (rest != 0L) byte = byte or VARINT_MORE
            out += byte.toByte()
        } while (rest != 0L)
        return out.toByteArray()
    }

    /** The version a Click v2 answers with, `02 03` (§3.1). */
    public const val DEFAULT_VERSION: Int = 0x0203

    private const val BITS_PER_BYTE = 8
}
