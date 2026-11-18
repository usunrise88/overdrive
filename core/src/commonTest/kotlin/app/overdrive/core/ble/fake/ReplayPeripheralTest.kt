package app.overdrive.core.ble.fake

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.STRAP_ID
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateReading
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A recorded or hand-written session played back from the device's side (spec §7.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayPeripheralTest {
    /** A strap's session: two connections; the first ends with the strap dropping the link with status 8. */
    private val strapSession =
        """
        # overdrive-ble-session 1
        # model HR7
        0 S - - connecting
        200000000 S - 0 connected
        200000000 D 2A37 - 180D/N
        200000000 D 2A38 - 180D/R
        250000000 C 2A37 0 0100
        1250000000 N 2A37 - 0048
        2250000000 N 2A37 - 164800040002
        2250000000 N 2A37 - 004A
        4000000000 S - 8 disconnected
        4000000001 S - - closed
        9000000000 S - - connecting
        9200000000 S - 0 connected
        9250000000 C 2A37 0 0100
        10250000000 N 2A37 - 0050
        """.trimIndent()

    @Test
    fun `the n-th connect plays the n-th segment, with the drop the recording ended in`() =
        runTest {
            val rig = BleRig(this)
            val replay = ReplayPeripheral(BleSessionFormat.parse(strapSession))
            assertEquals(2, replay.segmentCount)
            assertEquals("HR7", replay.advertisedName)
            assertEquals(setOf(HeartRateUuids.SERVICE), replay.advertisedServices)
            rig.ble.add(STRAP_ID, replay)

            val strap = rig.strap()
            val readings = collectAll(strap.data)
            strap.start()
            rig.advance(1_500)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            // The recorded times are since the link came up (at 0.2 s in the recording).
            rig.advance(2_000)
            assertEquals(
                listOf(HeartRateReading(72), HeartRateReading(72, listOf(1000, 500)), HeartRateReading(74)),
                readings,
            )

            rig.advance(1_000) // the recorded drop, 3.8 s after the link came up
            assertEquals(ConnectionState.DISCONNECTED, strap.connection.value)
            assertEquals(8, assertIs<LinkStatus.WaitingToRetry>(strap.status.value).failure.status)

            rig.advance(5_000)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            rig.advance(2_000)
            assertEquals(HeartRateReading(80), readings.last())
            rig.assertNoViolations()
        }

    @Test
    fun `reads and write acknowledgements come from the recording, in order`() =
        runTest {
            val text =
                """
                0 S - - connecting
                100000000 S - 0 connected
                100000000 D 2ACC - 1826/R
                100000000 D 2AD9 - 1826/WI
                110000000 R 2ACC 0 0654000008200000
                120000000 R 2ACC 0 0000000000000000
                130000000 W 2AD9 0 00
                140000000 W 2AD9 3 05C800
                """.trimIndent()
            val rig = BleRig(this)
            rig.ble.add(TRAINER_ID, ReplayPeripheral(BleSessionFormat.parse(text)))
            val transport = rig.ble.open(TRAINER_ID)
            transport.connect()
            val services = (transport.discoverServices() as GattResult.Ok).value
            val feature = assertNotNull(services.find(FtmsUuids.SERVICE, FtmsUuids.FEATURE))
            val point = assertNotNull(services.find(FtmsUuids.SERVICE, FtmsUuids.CONTROL_POINT))
            assertContentEquals(
                byteArrayOf(0x06, 0x54, 0, 0, 0x08, 0x20, 0, 0),
                (transport.read(feature) as GattResult.Ok).value,
            )
            assertContentEquals(ByteArray(8), (transport.read(feature) as GattResult.Ok).value)
            assertIs<GattResult.Failed>(transport.read(feature), "nothing left to replay")
            assertIs<GattResult.Ok<Unit>>(transport.write(point, byteArrayOf(0)))
            assertEquals(
                3,
                (transport.write(point, byteArrayOf(5, 0xC8.toByte(), 0)) as GattResult.Failed).failure.status,
            )
            assertIs<GattResult.Ok<Unit>>(
                transport.write(point, byteArrayOf(0)),
                "after the recording runs out: success",
            )
        }

    @Test
    fun `a recorded CCCD failure fails that write on replay`() =
        runTest {
            val text =
                """
                0 S - - connecting
                100000000 S - 0 connected
                100000000 D 2AD2 - 1826/N
                120000000 C 2AD2 133 0100
                """.trimIndent()
            val rig = BleRig(this)
            rig.ble.add(TRAINER_ID, ReplayPeripheral(BleSessionFormat.parse(text)))
            val transport = rig.ble.open(TRAINER_ID)
            transport.connect()
            val bike = (transport.discoverServices() as GattResult.Ok).value.characteristics.single()
            assertEquals(133, (transport.enable(bike, ValueUpdate.NOTIFICATION) as GattResult.Failed).failure.status)
            assertIs<GattResult.Ok<Unit>>(
                transport.enable(bike, ValueUpdate.NOTIFICATION),
                "only the recorded one fails",
            )
        }

    @Test
    fun `without discovery lines the known characteristics are inferred from the events`() {
        val text =
            """
            0 S - - connecting
            100000000 S - 0 connected
            1000000000 N 2AD2 - 4400C409B400FA00
            1000000000 I 2AD9 - 800001
            1000000000 N 2A37 - 0048
            """.trimIndent()
        val replay = ReplayPeripheral(BleSessionFormat.parse(text))
        val services = replay.characteristics.map { it.service to it.characteristic }.toSet()
        assertEquals(
            setOf(
                FtmsUuids.SERVICE to FtmsUuids.INDOOR_BIKE_DATA,
                FtmsUuids.SERVICE to FtmsUuids.CONTROL_POINT,
                HeartRateUuids.SERVICE to HeartRateUuids.MEASUREMENT,
            ),
            services,
        )
    }

    @Test
    fun `an empty or unusable session is an idle peripheral, not a crash`() =
        runTest {
            val rig = BleRig(this)
            val replay = ReplayPeripheral(BleSessionFormat.parse("garbage\n\n# comment\n"))
            assertEquals(0, replay.segmentCount)
            rig.ble.add(STRAP_ID, replay)
            val strap = rig.strap()
            strap.start()
            rig.advance(5_000)
            assertTrue(strap.connection.value != ConnectionState.CONNECTED, "it has no Heart Rate Measurement to offer")
        }
}
