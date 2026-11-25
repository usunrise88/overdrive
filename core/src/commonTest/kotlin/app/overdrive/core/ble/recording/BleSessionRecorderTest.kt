package app.overdrive.core.ble.recording

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakePeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorder and its decorator (docs/protocols/ftms-hr.md §7.1): what is written, and above all what is not. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleSessionRecorderTest {
    /** A peripheral with a Device Information Service whose values identify the unit. */
    private class IdentifyingPeripheral : FakePeripheral("My Own Bike 8842", setOf(FtmsUuids.SERVICE)) {
        private val deviceInformation = GattUuid.short(0x180A)

        override val characteristics =
            listOf(
                GattCharacteristicInfo(FtmsUuids.SERVICE, FtmsUuids.FEATURE, setOf(GattProperty.READ)),
                GattCharacteristicInfo(deviceInformation, IdentityUuids.SERIAL_NUMBER, setOf(GattProperty.READ)),
                GattCharacteristicInfo(deviceInformation, IdentityUuids.SYSTEM_ID, setOf(GattProperty.READ)),
                GattCharacteristicInfo(deviceInformation, IdentityUuids.DEVICE_NAME, setOf(GattProperty.READ)),
                GattCharacteristicInfo(deviceInformation, IdentityUuids.MODEL_NUMBER, setOf(GattProperty.READ)),
            )

        override fun onRead(characteristic: GattUuid): ByteArray? =
            when (characteristic) {
                IdentityUuids.SERIAL_NUMBER -> "SN-4711-SECRET".encodeToByteArray()
                IdentityUuids.SYSTEM_ID -> byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x01)
                IdentityUuids.DEVICE_NAME -> "Owner's D500".encodeToByteArray()
                IdentityUuids.MODEL_NUMBER -> "D500".encodeToByteArray()
                FtmsUuids.FEATURE -> byteArrayOf(0x06, 0x54, 0x00, 0x00, 0x08, 0x20, 0x00, 0x00)
                else -> null
            }
    }

    @Test
    fun `a device id never prints its value`() {
        val id = DeviceId("AA:BB:CC:DD:EE:FF")
        assertFalse("AA:BB" in id.toString())
        assertTrue(id.toString().startsWith("DeviceId(#"))
        assertEquals(id.toString(), DeviceId("AA:BB:CC:DD:EE:FF").toString(), "stable")
        assertTrue(DeviceId("AA:BB:CC:DD:EE:00").toString() != id.toString())
    }

    @Test
    fun `the advertised model is cleaned of anything that looks like a serial`() {
        assertEquals("D500", BleRecordingPolicy.sanitizeModel("D500"))
        assertEquals("Coospo HR7", BleRecordingPolicy.sanitizeModel("Coospo HR7-123456"))
        assertEquals("HR7", BleRecordingPolicy.sanitizeModel("HR7 8A3F1C"))
        assertEquals("HR7", BleRecordingPolicy.sanitizeModel("HR7:AA:BB:CC:DD:EE:FF"))
        assertEquals("Van Rysel D500", BleRecordingPolicy.sanitizeModel("Van Rysel D500"))
        assertNull(BleRecordingPolicy.sanitizeModel("1234567890"))
        assertNull(BleRecordingPolicy.sanitizeModel(null))
        assertNull(BleRecordingPolicy.sanitizeModel("  "))
        assertEquals(24, BleRecordingPolicy.sanitizeModel("Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")!!.length)
        assertEquals("Mine", BleRecordingPolicy.sanitizeModel("Mine éè!?"), "only plain characters survive")
    }

    @Test
    fun `identifying characteristics keep their line and lose their value`() =
        runTest {
            val rig = BleRig(this)
            val id = DeviceId("11:22:33:44:55:66")
            rig.ble.add(id, IdentifyingPeripheral())
            val lines = mutableListOf<String>()
            val recorder = BleSessionRecorder(backgroundScope, { lines += it }, { 0L }, model = "My Own Bike 8842")
            val transport = rig.ble.recording(recorder, backgroundScope).open(id)
            transport.connect()
            val services = (transport.discoverServices() as GattResult.Ok).value
            for (info in services.characteristics) transport.read(info)
            transport.close()
            recorder.close()
            recorder.join()

            val text = lines.joinToString("\n")
            assertTrue("R 2A25 0 *" in text, text)
            assertTrue("R 2A23 0 *" in text)
            assertTrue("R 2A00 0 *" in text)
            assertTrue("R 2A24 0 *" in text)
            assertTrue("R 2ACC 0 0654000008200000" in text, "the Feature word is not identity")
            for (secret in listOf("SN-4711", "Owner", "11:22", "8842", "4711", "1122334455667701")) {
                assertFalse(secret.lowercase() in text.lowercase(), "'$secret' leaked into: $text")
            }
            assertEquals("# model My Own Bike", lines[1], "the model keeps its words, loses the serial-like number")
        }

    @Test
    fun `nothing a recording holds can name the device it came from`() =
        runTest {
            val rig = BleRig(this)
            val recorded = rig.recording(model = "D500")
            val trainer = rig.trainer(factory = recorded.factory)
            trainer.start()
            rig.advance(3_000)
            rig.fakeTrainer.dropLink(GattStatus.GATT_ERROR)
            rig.advance(3_000)
            trainer.close()
            rig.settle()
            val text = recorded.lines.joinToString("\n")
            assertFalse(TRAINER_ID.value in text)
            assertFalse("test-trainer" in text)
            assertTrue(text.isNotEmpty())
        }

    @Test
    fun `times are relative to the start of the recording not the clock of the device`() =
        runTest {
            val lines = mutableListOf<String>()
            var now = 5_000_000_000_000L
            val recorder = BleSessionRecorder(backgroundScope, { lines += it }, { now }, model = null)
            now += 250_000_000
            recorder.record(BleDirection.STATE, null, null, BleStates.CONNECTING)
            recorder.recordBytes(
                BleDirection.NOTIFICATION,
                FtmsUuids.INDOOR_BIKE_DATA,
                null,
                byteArrayOf(1, 2),
                timeNanos = 5_000_001_000_000L,
            )
            recorder.record(BleDirection.STATE, null, null, "early", timeNanos = 1L)
            recorder.close()
            recorder.join()
            assertEquals(
                listOf(
                    "# overdrive-ble-session 1",
                    "250000000 S - - connecting",
                    "1000000 N 2AD2 - 0102",
                    "0 S - - early",
                ),
                lines,
                "an event stamped before the start is clamped to zero",
            )
        }

    @Test
    fun `the decorator records subscriptions writes indications and notifications and passes everything through`() =
        runTest {
            val rig = BleRig(this)
            val lines = mutableListOf<String>()
            val recorder =
                BleSessionRecorder(
                    backgroundScope,
                    { lines += it },
                    { testScheduler.currentTime * 1_000_000 },
                    model = "D500",
                )
            val transport = rig.ble.recording(recorder, backgroundScope).open(TRAINER_ID)
            val seen = mutableListOf<Pair<ValueUpdate, Int>>()
            backgroundScope.launch { transport.notifications.collect { seen += it.kind to it.value.size } }
            transport.connect()
            val services = (transport.discoverServices() as GattResult.Ok).value
            val bike = services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)!!
            val point = services.find(FtmsUuids.SERVICE, FtmsUuids.CONTROL_POINT)!!
            transport.enable(point, ValueUpdate.INDICATION)
            transport.enable(bike, ValueUpdate.NOTIFICATION)
            transport.write(point, byteArrayOf(0x00))
            rig.advance(1_500)
            transport.close()
            rig.settle()

            assertTrue(seen.any { it.first == ValueUpdate.INDICATION }, "the indication reached the caller: $seen")
            assertTrue(seen.any { it.first == ValueUpdate.NOTIFICATION })
            val compact = lines.drop(2).map { it.substringAfter(' ') }
            assertEquals("S - - connecting", compact[0])
            assertTrue("S - 0 connected" in compact)
            assertTrue("D 2AD9 - 1826/WI" in compact)
            assertTrue("C 2AD9 0 0200" in compact)
            assertTrue("C 2AD2 0 0100" in compact)
            assertTrue("W 2AD9 0 00" in compact)
            assertTrue("I 2AD9 - 800001" in compact)
            assertTrue(compact.any { it.startsWith("N 2AD2 - ") })
            assertEquals("S - 0 disconnected", compact[compact.size - 2])
            assertEquals("S - - closed", compact.last())
        }

    @Test
    fun `a failed CCCD write and a link drop are recorded with their status`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextCccdWrites(1, GattStatus.GATT_ERROR, FtmsUuids.INDOOR_BIKE_DATA)
            val lines = mutableListOf<String>()
            val recorder =
                BleSessionRecorder(backgroundScope, { lines += it }, { testScheduler.currentTime * 1_000_000 })
            val transport = rig.ble.recording(recorder, backgroundScope).open(TRAINER_ID)
            transport.connect()
            val services = (transport.discoverServices() as GattResult.Ok).value
            transport.enable(services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)!!, ValueUpdate.NOTIFICATION)
            rig.fakeTrainer.dropLink(status = 8)
            rig.settle()
            val text = lines.joinToString("\n")
            assertTrue(" C 2AD2 133 0100" in text, text)
            assertTrue(" S - 8 disconnected" in text, text)
            transport.close()
            rig.settle()
            assertEquals(1, lines.count { " disconnected" in it }, "the drop is recorded once, not again at close")
        }

    @Test
    fun `a sink that fails stops the recording and nothing else`() =
        runTest {
            var written = 0
            val recorder =
                BleSessionRecorder(
                    backgroundScope,
                    {
                        if (++written > 2) error("disk full")
                    },
                    { 0L },
                )
            repeat(10) { recorder.record(BleDirection.STATE, null, null, "x") }
            testScheduler.runCurrent()
            assertNotNull(recorder.failure)
            recorder.record(BleDirection.STATE, null, null, "still does not throw")
            recorder.close()
        }

    @Test
    fun `recorded characteristics keep one line per event in order`() =
        runTest {
            val rig = BleRig(this)
            val recorded = rig.recording()
            val strap = rig.strap(factory = recorded.factory)
            strap.start()
            rig.advance(3_500)
            rig.settle()
            val notifications = recorded.events().filter { it.direction == BleDirection.NOTIFICATION }
            assertEquals(3, notifications.size)
            assertEquals(notifications.map { it.timeNanos }.sorted(), notifications.map { it.timeNanos })
            assertTrue(notifications.all { it.bytes()!!.size >= 2 })
        }
}
