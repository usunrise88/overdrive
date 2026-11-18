package app.overdrive.core.fit

import app.overdrive.core.record.SyntheticRide
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The CRC of fit.md section 1.4, and the test reader's own strictness (a reader that accepts anything proves nothing). */
class FitCrcAndReaderTest {
    private val good = FitWriter().write(SyntheticRide.record())

    @Test
    fun `the check value of the standard CRC-16 ARC`() {
        val bytes = "123456789".encodeToByteArray()
        assertEquals(0xBB3D, FitCrc.of(bytes))
        assertEquals(0xBB3D, FitReader.crc16(bytes, 0, bytes.size))
    }

    @Test
    fun `the nibble form of the writer equals the bitwise CRC on random buffers`() {
        val random = Random(20_261_118)
        repeat(500) {
            val bytes = random.nextBytes(random.nextInt(0, 300))
            assertEquals(FitReader.crc16(bytes, 0, bytes.size), FitCrc.of(bytes))
        }
    }

    @Test
    fun `the CRC over a whole file including its own is zero, and a damaged file is not`() {
        assertEquals(0, FitCrc.of(good))
        val damaged = good.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertEquals(true, FitCrc.of(damaged) != 0)
    }

    @Test
    fun `the reader rejects a flipped byte anywhere in the file`() {
        for (index in good.indices step 7) {
            val damaged = good.copyOf().also { it[index] = (it[index].toInt() xor 0x10).toByte() }
            assertFailsWith<FitFormatException>("byte $index") { FitReader.parse(damaged) }
        }
    }

    @Test
    fun `the reader rejects a truncated file`() {
        for (length in listOf(0, 5, 13, 14, 15, 100, good.size - 3, good.size - 1)) {
            assertFailsWith<FitFormatException>("length $length") { FitReader.parse(good.copyOf(length)) }
        }
    }

    @Test
    fun `the reader rejects a wrong data size or header size even with valid CRCs`() {
        val wrongSize = good.copyOf()
        wrongSize[4] = (wrongSize[4] + 1).toByte()
        refreshCrcs(wrongSize)
        assertFailsWith<FitFormatException> { FitReader.parse(wrongSize) }

        val wrongHeader = good.copyOf()
        wrongHeader[0] = 13
        refreshCrcs(wrongHeader)
        assertFailsWith<FitFormatException> { FitReader.parse(wrongHeader) }
    }

    @Test
    fun `the reader rejects a data message before its definition`() {
        // Header, then a data message of local type 3 with no definition, then the CRC.
        val body = byteArrayOf(0x03, 0x01, 0x02)
        val file = ByteArray(14 + body.size + 2)
        byteArrayOf(
            14,
            0x20,
            0xE6.toByte(),
            0x52,
            body.size.toByte(),
            0,
            0,
            0,
            '.'.code.toByte(),
            'F'.code.toByte(),
            'I'.code.toByte(),
            'T'.code.toByte(),
        ).copyInto(file)
        body.copyInto(file, 14)
        refreshCrcs(file)
        assertFailsWith<FitFormatException> { FitReader.parse(file) }
    }

    @Test
    fun `the profile facts catch a field with the wrong type or number`() {
        // Field 7 of record is power, a uint16. Declaring it uint8 must be reported.
        val file =
            FitFile(
                14,
                0x20,
                21222,
                0,
                listOf(
                    FitItem.Definition(FitDefinition(2, 20, false, listOf(Triple(7, 1, 0x02), Triple(99, 1, 0x02)))),
                ),
            )
        assertEquals(
            listOf("record.power is base type 2, profile says 4", "record has no field 99"),
            FitFacts.problems(file),
        )
    }

    /** Recomputes the header CRC and the file CRC so that only the field under test is wrong. */
    private fun refreshCrcs(file: ByteArray) {
        val header = file[0].toInt()
        if (header == 14) {
            val crc = FitReader.crc16(file, 0, 12)
            file[12] = crc.toByte()
            file[13] = (crc shr 8).toByte()
        }
        val crc = FitReader.crc16(file, 0, file.size - 2)
        file[file.size - 2] = crc.toByte()
        file[file.size - 1] = (crc shr 8).toByte()
    }
}
