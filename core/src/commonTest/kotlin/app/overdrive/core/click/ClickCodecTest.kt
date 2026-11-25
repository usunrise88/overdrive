package app.overdrive.core.click

import app.overdrive.core.ble.codec.DecodeErrorKind
import app.overdrive.core.ble.codec.DecodeResult
import app.overdrive.core.ble.codec.Hex
import app.overdrive.core.ble.codec.hex
import app.overdrive.core.ble.codec.malformed
import app.overdrive.core.ble.codec.ok
import app.overdrive.core.ble.fake.ClickEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Click v2 codec against vectors **derived by hand from docs/protocols/click-v2.md §3.1 and §4**: synthetic, generated
 * from the notes' own examples and the protobuf varint rule, never captured from a controller (nothing was observed on the
 * bench). Each vector has an ID that the docs refer to.
 *
 * | ID | Bytes | What it is | Source |
 * |---|---|---|---|
 * | CK-V1 | `23 08 FF FF FF FF 0F` | neutral frame, every bit set | §4 "Neutral frame" |
 * | CK-V2 | `23 08 EF FF FF FF 0F` | A pressed (bit `0x0010` clear) | §4 table, varint rule |
 * | CK-V3 | `23 08 DF FF FF FF 0F` | B pressed (`0x0020`) | same |
 * | CK-V4 | `23 08 BF FF FF FF 0F` | Y pressed (`0x0040`) | same |
 * | CK-V5 | `23 08 FF FE FF FF 0F` | Z pressed (`0x0080`) | same |
 * | CK-V6 | `23 08 FF DF FF FF 0F` | Plus pressed (`0x1000`) | same |
 * | CK-V7 | `23 08 FF BF FF FF 0F` | Plus's second bit pressed (`0x2000`) | same |
 * | CK-V8 | `23 08 EF DF FF FF 0F` | A and Plus together | same |
 * | CK-V9 | `19 10 1F` | battery 31 % | §4 "Battery" (trainer-control's example) |
 * | CK-V10 | `15` | idle heartbeat | §4 |
 * | CK-V11 | `3C 01 02` | the unknown Click v2 type | §4 |
 * | CK-V12 | `FE` | disconnect notice | §4 |
 * | CK-V13 | `52 69 64 65 4F 6E` | the handshake write, "RideOn" | §3.1 |
 * | CK-V14 | `52 69 64 65 4F 6E 02 03` | the reply the notes expect | §3.1 |
 * | CK-V15 | `FF 04 00` | the post-handshake write | §3.1 |
 */
class ClickCodecTest {
    private fun state(text: String): ClickMessage.ButtonState = assertIs(ClickCodec.parse(hex(text)).ok())

    // region vectors

    @Test
    fun `CK-V1 the neutral frame has every bit set and nothing pressed`() {
        val message = state("23 08 FF FF FF FF 0F")
        assertEquals(-1, message.bitmap, "0xFFFFFFFF")
        assertEquals(0, message.pressed)
        assertContentEquals(hex("23 08 FF FF FF FF 0F"), ClickCodec.neutralFrame())
    }

    @Test
    fun `CK-V2 to CK-V8 a clear bit is a pressed button`() {
        val vectors =
            listOf(
                "23 08 EF FF FF FF 0F" to ClickBits.A,
                "23 08 DF FF FF FF 0F" to ClickBits.B,
                "23 08 BF FF FF FF 0F" to ClickBits.Y,
                "23 08 FF FE FF FF 0F" to ClickBits.Z,
                "23 08 FF DF FF FF 0F" to ClickBits.PLUS,
                "23 08 FF BF FF FF 0F" to ClickBits.PLUS_SHIFT_DOWN,
                "23 08 EF DF FF FF 0F" to (ClickBits.A or ClickBits.PLUS),
            )
        for ((text, pressed) in vectors) {
            assertEquals(pressed, state(text).pressed, text)
            assertEquals(ClickBits.bitmapOf(pressed), state(text).bitmap, text)
        }
    }

    @Test
    fun `the left puck's bits are in the same word`() {
        // 0x0001 (nav left) clear: bits 0..6 of the first varint byte = 1111110 -> FE.
        assertEquals(ClickBits.NAV_LEFT, state("23 08 FE FF FF FF 0F").pressed)
        // 0x0100 (minus) clear: second group bit 1 -> 1111101 = 7D | 80 -> FD.
        assertEquals(ClickBits.MINUS, state("23 08 FF FD FF FF 0F").pressed)
    }

    @Test
    fun `CK-V9 the battery example from the notes is 31 percent`() {
        assertEquals(ClickMessage.Battery(31), ClickCodec.parse(hex("19 10 1F")).ok())
    }

    @Test
    fun `CK-V10 to CK-V12 heartbeat the unknown type and the disconnect notice`() {
        assertEquals(ClickMessage.Heartbeat, ClickCodec.parse(hex("15")).ok())
        assertEquals(ClickMessage.DisconnectNotice, ClickCodec.parse(hex("FE")).ok())
        val unknown = assertIs<ClickMessage.Unrecognised>(ClickCodec.parse(hex("3C 01 02")).ok())
        assertEquals(ClickCodec.TYPE_UNKNOWN_CLICK_V2, unknown.type)
        assertContentEquals(hex("01 02"), unknown.body)
    }

    @Test
    fun `a heartbeat or a notice with a body is still that message`() {
        assertEquals(ClickMessage.Heartbeat, ClickCodec.parse(hex("15 00 00")).ok())
        assertEquals(ClickMessage.DisconnectNotice, ClickCodec.parse(hex("FE 01")).ok())
    }

    @Test
    fun `CK-V13 to CK-V15 the bytes the host writes`() {
        assertContentEquals(hex("52 69 64 65 4F 6E"), ClickCodec.handshake())
        assertEquals("RideOn", ClickCodec.handshake().decodeToString())
        assertContentEquals(hex("FF 04 00"), ClickCodec.postHandshake())
    }

    // endregion

    // region unknown types are not errors

    @Test
    fun `every type this code does not know is reported as unrecognised never as an error`() {
        for (type in 0..255) {
            if (type in setOf(0x23, 0x15, 0x19, 0xFE)) continue
            val message = ClickCodec.parse(byteArrayOf(type.toByte(), 1, 2, 3)).ok()
            val unrecognised = assertIs<ClickMessage.Unrecognised>(message, "type $type")
            assertEquals(type, unrecognised.type)
            assertContentEquals(byteArrayOf(1, 2, 3), unrecognised.body)
        }
        assertEquals(
            ClickMessage.Unrecognised(0x37, hex("08 00 10 00")),
            ClickCodec.parse(hex("37 08 00 10 00")).ok(),
            "Click v1",
        )
        assertEquals(ClickMessage.Unrecognised(0x07, ByteArray(0)), ClickCodec.parse(hex("07")).ok(), "a Play frame")
    }

    // endregion

    // region protobuf structure

    @Test
    fun `fields in another order repeated fields and unknown fields are skipped the last bitmap wins`() {
        // field 3 (analog paddles, §4) length-delimited before field 1.
        assertEquals(ClickBits.A, state("23 1A 02 01 02 08 EF FF FF FF 0F").pressed)
        // field 3 as a varint, then field 1, then a 32-bit and a 64-bit field.
        assertEquals(ClickBits.B, state("23 18 05 08 DF FF FF FF 0F 25 00 00 00 00 29 00 00 00 00 00 00 00 00").pressed)
        // two bitmaps: the later one counts (protobuf).
        assertEquals(ClickBits.Y, state("23 08 EF FF FF FF 0F 08 BF FF FF FF 0F").pressed)
    }

    @Test
    fun `a short varint is the same word`() {
        // Field 1 = 0 is every button pressed; a one-byte varint is a legal encoding of a small word.
        assertEquals(-1, state("23 08 00").pressed)
        assertEquals(0x7F, state("23 08 7F").bitmap)
    }

    @Test
    fun `a ten byte varint keeps the low 32 bits a longer one is malformed`() {
        // The negative int32 encoding of -17 (0xFFFFFFEF): ten bytes.
        assertEquals(ClickBits.A, state("23 08 EF FF FF FF FF FF FF FF FF 01").pressed)
        assertEquals(
            DecodeErrorKind.WRONG_LENGTH,
            ClickCodec.parse(hex("23 08 FF FF FF FF FF FF FF FF FF FF 01")).malformed().kind,
        )
    }

    @Test
    fun `a button frame without the bitmap or with unusable wire types is malformed`() {
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("23")).malformed().kind, "no body")
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("23 1A 00")).malformed().kind, "field 3 only")
        // Wire type 3 (start group) and 7 (reserved) are not part of this message.
        assertEquals(DecodeErrorKind.UNEXPECTED_MARKER, ClickCodec.parse(hex("23 0B")).malformed().kind)
        assertEquals(DecodeErrorKind.UNEXPECTED_MARKER, ClickCodec.parse(hex("23 0F")).malformed().kind)
        // A field 1 that is not a varint does not give a bitmap.
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("23 0A 00")).malformed().kind)
    }

    @Test
    fun `battery frames with and without the percentage`() {
        assertEquals(ClickMessage.Battery(100), ClickCodec.parse(hex("19 10 64")).ok())
        assertEquals(ClickMessage.Battery(0), ClickCodec.parse(hex("19 10 00")).ok())
        assertEquals(ClickMessage.Battery(128), ClickCodec.parse(hex("19 10 80 01")).ok(), "a two byte varint")
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("19")).malformed().kind)
        assertEquals(
            DecodeErrorKind.TRUNCATED,
            ClickCodec.parse(hex("19 08 05")).malformed().kind,
            "field 1 is not the percentage",
        )
        assertEquals(ClickMessage.Battery(31), ClickCodec.parse(hex("19 08 05 10 1F")).ok(), "another field first")
    }

    // endregion

    // region handshake reply

    @Test
    fun `CK-V14 the reply the notes expect with and without a public key`() {
        assertEquals(HandshakeReply(0x0203, 0), ClickCodec.parseHandshakeReply(hex("52 69 64 65 4F 6E 02 03")).ok())
        val withKey = ClickEncoder.handshakeReply(0x0203, 64)
        assertEquals(72, withKey.size)
        assertEquals(HandshakeReply(0x0203, 64), ClickCodec.parseHandshakeReply(withKey).ok())
        assertEquals("02 03", HandshakeReply(0x0203, 0).versionText)
        assertEquals("01 03", HandshakeReply(0x0103, 0).versionText)
    }

    @Test
    fun `a bare RideOn has no version a seventh byte is half a version other bytes are not a reply`() {
        assertEquals(HandshakeReply(null, 0), ClickCodec.parseHandshakeReply(hex("52 69 64 65 4F 6E")).ok())
        assertNull(HandshakeReply(null, 0).versionText)
        assertEquals(
            DecodeErrorKind.TRUNCATED,
            ClickCodec.parseHandshakeReply(hex("52 69 64 65 4F 6E 02")).malformed().kind,
        )
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parseHandshakeReply(hex("52 69 64")).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, ClickCodec.parseHandshakeReply(ByteArray(0)).malformed().kind)
        assertEquals(
            DecodeErrorKind.UNEXPECTED_MARKER,
            ClickCodec.parseHandshakeReply(hex("23 08 FF FF FF FF 0F")).malformed().kind,
        )
        assertEquals(
            DecodeErrorKind.UNEXPECTED_MARKER,
            ClickCodec.parseHandshakeReply(hex("52 69 64 65 4F 6F 02 03")).malformed().kind,
        )
    }

    // endregion

    // region never throws

    @Test
    fun `the bitmap table is the notes' table`() {
        assertEquals(0x0010, ClickBits.A)
        assertEquals(0x0020, ClickBits.B)
        assertEquals(0x0040, ClickBits.Y)
        assertEquals(0x0080, ClickBits.Z)
        assertEquals(0x1000, ClickBits.PLUS)
        assertEquals(0x2000, ClickBits.PLUS_SHIFT_DOWN)
        assertEquals(0x0001, ClickBits.NAV_LEFT)
        assertEquals(0x0100, ClickBits.MINUS)
        assertEquals(listOf("A", "PLUS"), ClickBits.namesOf(ClickBits.A or ClickBits.PLUS))
        assertEquals(listOf("Y", "0x4000"), ClickBits.namesOf(ClickBits.Y or 0x4000))
        assertEquals(emptyList(), ClickBits.namesOf(0))
    }

    @Test
    fun `random bytes of every short length never throw`() {
        val random = Random(20261118)
        repeat(60_000) {
            val bytes = random.nextBytes(random.nextInt(0, 40))
            ClickCodec.parse(bytes)
            ClickCodec.parseHandshakeReply(bytes)
            if (bytes.isNotEmpty()) {
                // The two typed bodies, with random content after a valid type.
                ClickCodec.parse(byteArrayOf(0x23) + bytes)
                ClickCodec.parse(byteArrayOf(0x19) + bytes)
            }
        }
    }

    @Test
    fun `every proper prefix of a button frame is a typed error or a shorter valid frame never partly used`() {
        val vectors =
            listOf(
                "23 08 FF FF FF FF 0F",
                "23 08 EF DF FF FF 0F",
                "23 1A 02 01 02 08 EF FF FF FF 0F",
                "19 10 1F",
                "19 08 05 10 80 01",
            )
        for (vector in vectors) {
            val full = hex(vector)
            assertTrue(ClickCodec.parse(full) is DecodeResult.Ok, vector)
            for (length in 1 until full.size) {
                val result = ClickCodec.parse(full.copyOf(length))
                if (result is DecodeResult.Malformed) {
                    assertTrue(
                        result.error.kind in setOf(DecodeErrorKind.TRUNCATED, DecodeErrorKind.WRONG_LENGTH),
                        "$vector cut to $length",
                    )
                }
            }
        }
        // The two-byte-varint cases are exactly where a cut changes the value: 0x80 alone is truncated, not 128.
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("19 10 80")).malformed().kind)
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("23 08 FF FF FF FF")).malformed().kind)
        assertEquals(DecodeErrorKind.TRUNCATED, ClickCodec.parse(hex("23 08")).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, ClickCodec.parse(ByteArray(0)).malformed().kind)
    }

    @Test
    fun `random button frames survive an encode and decode round trip`() {
        val random = Random(99)
        repeat(5_000) {
            val bitmap = random.nextInt()
            assertEquals(bitmap, state(Hex.encode(ClickEncoder.buttonState(bitmap))).bitmap)
            val percent = random.nextInt(0, 101)
            assertEquals(ClickMessage.Battery(percent), ClickCodec.parse(ClickEncoder.battery(percent)).ok())
        }
        assertContentEquals(hex("23 08 FF FF FF FF 0F"), ClickEncoder.buttonState(-1))
        assertContentEquals(hex("19 10 1F"), ClickEncoder.battery(31))
    }

    // endregion
}
