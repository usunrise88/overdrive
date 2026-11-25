package app.overdrive.core.fit

import app.overdrive.core.record.FitGoldenRide
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The worked example of `docs/protocols/fit.md` section 6.1 is the golden: the writer must produce exactly its 376 bytes.
 * The listing below is copied from the document, line by line, and is the only place this test takes its expectations from.
 *
 * The document's example has one quirk the test accommodates: its `activity.local_timestamp` equals the ride's start time
 * while `activity.timestamp` is two seconds later, which is what a UTC offset of −2 s gives. A real ride's local time is
 * its timestamp plus the real offset ([FitOptions.utcOffsetSeconds]), which readers use to find the time zone, so the
 * writer does that and the test passes −2 s to reproduce the document's bytes.
 */
class FitGoldenTest {
    private val listing =
        listOf(
            "0e 20 e6 52 68 01 00 00 2e 46 49 54 8a e1", // header
            "40 00 00 00 00 05 00 01 00 01 02 84 02 02 84 03 04 8c 04 04 86", // file_id definition
            "00 04 ff 00 01 00 01 00 00 00 20 2b 60 45",
            "41 00 00 15 00 03 fd 04 86 00 01 00 01 01 00", // event definition
            "01 20 2b 60 45 00 00", // timer start
            "42 00 00 14 00 07 fd 04 86 03 01 02 04 01 02 05 04 86 06 02 84 07 02 84 49 04 86", // record def
            "02 20 2b 60 45 78 5a 00 00 00 00 40 1f c8 00 40 1f 00 00",
            "02 21 2b 60 45 79 5b 20 03 00 00 a4 1f d2 00 a4 1f 00 00",
            "02 22 2b 60 45 7a 5c 4a 06 00 00 08 20 dc 00 08 20 00 00",
            "01 22 2b 60 45 00 04", // timer stop_all
            "43 00 00 13 00 0e fd 04 86 02 04 86 07 04 86 08 04 86 09 04 86 0f 01 02 10 01 02 11 01 02 " +
                "13 02 84 14 02 84 18 01 00 19 01 00 27 01 00 fe 02 84", // lap definition
            "03 22 2b 60 45 20 2b 60 45 b8 0b 00 00 b8 0b 00 00 4a 06 00 00 " +
                "79 7a 5b d2 00 dc 00 07 02 3a 00 00",
            "44 00 00 12 00 0f fd 04 86 02 04 86 05 01 00 06 01 00 07 04 86 08 04 86 09 04 86 " +
                "10 01 02 11 01 02 12 01 02 14 02 84 15 02 84 19 02 84 1a 02 84 1c 01 00", // session definition
            "04 22 2b 60 45 20 2b 60 45 02 3a b8 0b 00 00 b8 0b 00 00 4a 06 00 00 " +
                "79 7a 5b d2 00 dc 00 00 00 01 00 00",
            "45 00 00 22 00 07 fd 04 86 00 04 86 01 02 84 02 01 00 03 01 00 04 01 00 05 04 86", // activity def
            "05 22 2b 60 45 b8 0b 00 00 01 00 00 1a 01 20 2b 60 45",
            "9c 34", // CRC
        )

    private val expected: ByteArray = listing.joinToString(" ").split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `the writer reproduces the worked example byte for byte`() {
        val actual = FitWriter(FitOptions(utcOffsetSeconds = -2)).write(FitGoldenRide.record())
        assertEquals(expected.size, actual.size)
        assertEquals(hex(expected), hex(actual))
    }

    @Test
    fun `the golden is a file our own reader accepts with the sizes and checksums the document gives`() {
        val file = FitReader.parse(expected)
        assertEquals(376, expected.size)
        assertEquals(14, file.headerSize)
        assertEquals(360, file.dataSize)
        assertEquals(0x20, file.protocolVersion)
        assertEquals(21222, file.profileVersion)
        assertEquals(0xE18A, FitReader.crc16(expected, 0, 12))
        assertEquals(0x349C, FitReader.crc16(expected, 0, 374))
        assertEquals(
            mapOf("file_id" to 1, "event" to 2, "record" to 3, "lap" to 1, "session" to 1, "activity" to 1),
            file.messages.groupingBy { it.name }.eachCount(),
        )
        assertEquals(emptyList(), FitFacts.problems(file))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
