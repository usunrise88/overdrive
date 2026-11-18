package app.overdrive.core.ble.recording

import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.codec.Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleSessionFormatTest {
    private val sample = "1250000000 N 2AD2 - 4400C409B400FA00"

    @Test
    fun `a notification line reads as documented`() {
        val event = assertNotNull(BleSessionFormat.parseLine(sample))
        assertEquals(1_250_000_000L, event.timeNanos)
        assertEquals(BleDirection.NOTIFICATION, event.direction)
        assertEquals(FtmsUuids.INDOOR_BIKE_DATA, event.characteristic)
        assertNull(event.status)
        assertEquals("4400C409B400FA00", event.data)
        assertEquals(
            listOf<Byte>(0x44, 0x00, 0xC4.toByte(), 0x09, 0xB4.toByte(), 0x00, 0xFA.toByte(), 0x00),
            event.bytes()?.toList(),
        )
        assertEquals(sample, BleSessionFormat.format(event))
    }

    @Test
    fun `every direction round trips through a line`() {
        val lines =
            listOf(
                "0 S - - connecting",
                "200000000 S - 0 connected",
                "200000000 D 2AD9 - 1826/WI",
                "300000000 S - 0 mtu=64",
                "310000000 R 2ACC 0 0654000008200000",
                "320000000 C 2AD9 0 0200",
                "330000000 C 2AD2 133 0100",
                "340000000 W 2AD9 0 05C800",
                "400000000 I 2AD9 - 800501",
                "1250000000 N 2AD2 - 4400C409B400FA00",
                "9000000000 S - 8 disconnected",
                "9000000001 S - - closed",
                "9500000000 R 2A25 0 *",
                "9600000000 R 2A00 0 -",
            )
        for (line in lines) {
            val event = assertNotNull(BleSessionFormat.parseLine(line), line)
            assertEquals(line, BleSessionFormat.format(event))
        }
    }

    @Test
    fun `a full 128-bit characteristic is accepted and written back in short form when it has one`() {
        val event = assertNotNull(BleSessionFormat.parseLine("10 N 00002ad2-0000-1000-8000-00805f9b34fb - 00"))
        assertEquals(FtmsUuids.INDOOR_BIKE_DATA, event.characteristic)
        assertEquals("10 N 2AD2 - 00", BleSessionFormat.format(event))
        val vendor = assertNotNull(BleSessionFormat.parseLine("10 N 6e400001-b5a3-f393-e0a9-e50e24dcca9e - 00"))
        assertEquals("10 N 6e400001-b5a3-f393-e0a9-e50e24dcca9e - 00", BleSessionFormat.format(vendor))
    }

    @Test
    fun `lines that are not events are rejected, never thrown on`() {
        for (bad in listOf(
            "",
            "   ",
            "garbage",
            "1 N 2AD2 - ",
            "x N 2AD2 - 00",
            "-5 N 2AD2 - 00",
            "1 Q 2AD2 - 00",
            "1 NN 2AD2 - 00",
            "1 N ZZZZ - 00",
            "1 N 2AD2 x 00",
            "1 N 2AD2 - 00 extra",
        )) {
            assertNull(BleSessionFormat.parseLine(bad), "'$bad'")
        }
    }

    @Test
    fun `a file has a version, an optional model, comments and events, and counts what it cannot read`() {
        val text =
            """
            # overdrive-ble-session 1
            # model D500
            # a comment of a person

            0 S - - connecting
            not an event
            200000000 S - 0 connected
            """.trimIndent()
        val parsed = BleSessionFormat.parseWithDiagnostics(text)
        assertEquals(1, parsed.version)
        assertEquals("D500", parsed.session.model)
        assertEquals(2, parsed.session.events.size)
        assertEquals(1, parsed.skippedLines)
    }

    @Test
    fun `a file without a header still reads, and its version is null`() {
        val parsed = BleSessionFormat.parseWithDiagnostics("0 S - - connecting\n")
        assertNull(parsed.version)
        assertNull(parsed.session.model)
        assertEquals(1, parsed.session.events.size)
    }

    @Test
    fun `a written session reads back identical`() {
        val random = Random(11)
        val characteristics =
            listOf(
                null,
                FtmsUuids.INDOOR_BIKE_DATA,
                FtmsUuids.CONTROL_POINT,
                GattUuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            )
        val events =
            List(300) {
                BleSessionEvent(
                    timeNanos = random.nextLong(0, 10_000_000_000),
                    direction = BleDirection.entries[random.nextInt(BleDirection.entries.size)],
                    characteristic = characteristics[random.nextInt(characteristics.size)],
                    status = if (random.nextBoolean()) random.nextInt(0, 300) else null,
                    data =
                        if (random.nextBoolean()) {
                            Hex.encode(
                                random.nextBytes(random.nextInt(1, 30)),
                            )
                        } else {
                            "connected"
                        },
                )
            }
        val original = BleSession("D500", events)
        val text = BleSessionFormat.write(original)
        val parsed = BleSessionFormat.parseWithDiagnostics(text)
        assertEquals(0, parsed.skippedLines)
        assertEquals(events, parsed.session.events)
        assertEquals("D500", parsed.session.model)
        assertEquals(text, BleSessionFormat.write(parsed.session), "writing is deterministic")
    }

    @Test
    fun `random text never throws and only whole lines become events`() {
        val random = Random(3)
        val alphabet = "0123456789ABCDEFNIWRCSD -*#\n\tx"
        repeat(3_000) {
            val text = String(CharArray(random.nextInt(0, 200)) { alphabet[random.nextInt(alphabet.length)] })
            val parsed = BleSessionFormat.parseWithDiagnostics(text)
            assertTrue(parsed.skippedLines >= 0)
            parsed.session.events.forEach { assertEquals(it, BleSessionFormat.parseLine(BleSessionFormat.format(it))) }
        }
    }

    @Test
    fun `a redacted or missing value has no bytes, an empty one is empty`() {
        assertNull(BleSessionFormat.parseLine("1 R 2A25 0 *")!!.bytes())
        assertEquals(0, BleSessionFormat.parseLine("1 R 2A25 0 -")!!.bytes()!!.size)
        assertNull(BleSessionFormat.parseLine("1 R 2A25 0 ZZ")!!.bytes())
    }
}
