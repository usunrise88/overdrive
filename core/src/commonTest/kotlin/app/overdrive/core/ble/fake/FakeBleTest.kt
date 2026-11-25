package app.overdrive.core.ble.fake

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattServices
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.ScanEvent
import app.overdrive.core.ble.ScanFilter
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The fake must be as strict as the platform, or a green test proves nothing about the real stack. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeBleTest {
    private suspend fun connected(rig: BleRig): Pair<GattTransport, GattServices> {
        val transport = rig.ble.open(TRAINER_ID)
        assertIs<GattResult.Ok<Unit>>(transport.connect())
        val services = (transport.discoverServices() as GattResult.Ok).value
        return transport to services
    }

    @Test
    fun `a connect takes its latency then the state is connected`() =
        runTest {
            val rig = BleRig(this)
            val transport = rig.ble.open(TRAINER_ID)
            assertEquals(GattLinkState.Idle, transport.state.value)
            val job = launch { transport.connect() }
            rig.advance(100)
            assertEquals(GattLinkState.Connecting, transport.state.value)
            rig.advance(100)
            job.join()
            assertEquals(GattLinkState.Connected, transport.state.value)
            rig.assertNoViolations()
        }

    @Test
    fun `values reach the central only for a characteristic it enabled as it enabled it`() =
        runTest {
            val rig = BleRig(this)
            val (transport, services) = connected(rig)
            val seen = collectAll(transport.notifications)
            val bike = services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)!!
            val point = services.find(FtmsUuids.SERVICE, FtmsUuids.CONTROL_POINT)!!

            rig.advance(1_500)
            assertTrue(seen.isEmpty(), "nothing subscribed yet, nothing delivered")

            assertIs<GattResult.Ok<Unit>>(transport.enable(bike, ValueUpdate.NOTIFICATION))
            assertIs<GattResult.Ok<Unit>>(transport.enable(point, ValueUpdate.INDICATION))
            rig.advance(1_000)
            assertTrue(seen.isNotEmpty())
            assertTrue(
                seen.all { it.characteristic == FtmsUuids.INDOOR_BIKE_DATA && it.kind == ValueUpdate.NOTIFICATION },
            )
            rig.assertNoViolations()
        }

    @Test
    fun `enabling a mode the characteristic does not offer is refused`() =
        runTest {
            val rig = BleRig(this)
            val (transport, services) = connected(rig)
            val bike = services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)!!
            val result = transport.enable(bike, ValueUpdate.INDICATION)
            assertEquals(GattFailureKind.STATUS, (result as GattResult.Failed).failure.kind)
        }

    @Test
    fun `overlapping GATT operations are a violation and the second gets BUSY`() =
        runTest {
            val rig = BleRig(this)
            val (transport, services) = connected(rig)
            val feature = services.find(FtmsUuids.SERVICE, FtmsUuids.FEATURE)!!
            val first = launch { transport.read(feature) }
            rig.settle()
            val second = transport.read(feature)
            assertEquals(GattFailureKind.BUSY, (second as GattResult.Failed).failure.kind)
            first.join()
            assertEquals(1, rig.ble.violations.size)
        }

    @Test
    fun `a closed transport answers CLOSED and the misuse is recorded`() =
        runTest {
            val rig = BleRig(this)
            val (transport, services) = connected(rig)
            transport.close()
            assertEquals(GattLinkState.Disconnected(GattStatus.SUCCESS), transport.state.value)
            val read = transport.read(services.find(FtmsUuids.SERVICE, FtmsUuids.FEATURE)!!)
            assertEquals(GattFailureKind.CLOSED, (read as GattResult.Failed).failure.kind)
            assertEquals(1, rig.ble.violations.size)
        }

    @Test
    fun `opening a second transport before closing the first is a violation`() =
        runTest {
            val rig = BleRig(this)
            val first = rig.ble.open(TRAINER_ID)
            rig.ble.open(TRAINER_ID)
            assertEquals(1, rig.ble.violations.size, "the first one was never closed")
            first.close()
        }

    @Test
    fun `a peripheral accepts one central at a time`() =
        runTest {
            val rig = BleRig(this)
            connected(rig)
            val second = rig.ble.open(TRAINER_ID)
            val result = second.connect()
            assertEquals(GattStatus.GATT_ERROR, (result as GattResult.Failed).failure.status)
            assertEquals(GattLinkState.Disconnected(GattStatus.GATT_ERROR), second.state.value)
        }

    @Test
    fun `scripted connect failures use the given status and then stop`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextConnects(2, status = 62)
            val first = rig.ble.open(TRAINER_ID)
            assertEquals(62, (first.connect() as GattResult.Failed).failure.status)
            first.close()
            val second = rig.ble.open(TRAINER_ID)
            assertEquals(62, (second.connect() as GattResult.Failed).failure.status)
            second.close()
            val third = rig.ble.open(TRAINER_ID)
            assertIs<GattResult.Ok<Unit>>(third.connect())
        }

    @Test
    fun `a dropped link fails the operation waiting on it and ends the transport`() =
        runTest {
            val rig = BleRig(this)
            val (transport, services) = connected(rig)
            var result: GattResult<ByteArray>? = null
            val feature = services.find(FtmsUuids.SERVICE, FtmsUuids.FEATURE)!!
            val job = launch { result = transport.read(feature) }
            rig.settle()
            rig.fakeTrainer.dropLink(status = 8)
            job.join()
            assertEquals(GattFailureKind.DISCONNECTED, (result as GattResult.Failed).failure.kind)
            assertEquals(GattLinkState.Disconnected(8), transport.state.value)
        }

    @Test
    fun `a scripted silence window suppresses notifications and nothing else`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.silence(
                from = kotlin.time.Duration.parse("3s"),
                until = kotlin.time.Duration.parse("6s"),
            )
            val (transport, services) = connected(rig)
            val seen = collectAll(transport.notifications)
            transport.enable(services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)!!, ValueUpdate.NOTIFICATION)
            rig.advance(9_000)
            val seconds = seen.map { it.timestampNanos / 1_000_000_000 }
            assertTrue(seconds.none { it in 3..5 }, "no notification between 3 s and 6 s: $seconds")
            assertTrue(seconds.any { it >= 6 } && seconds.any { it < 3 })
        }

    @Test
    fun `Bluetooth going off drops every link and refuses connects until it is back`() =
        runTest {
            val rig = BleRig(this)
            val (transport, _) = connected(rig)
            rig.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            assertIs<GattLinkState.Disconnected>(transport.state.value)
            val refused = rig.ble.open(TRAINER_ID).also { transport.close() }.connect()
            assertEquals(GattFailureKind.UNAVAILABLE, (refused as GattResult.Failed).failure.kind)
        }

    @Test
    fun `the scanner reports peripherals that match its filter and a failure when Bluetooth is off`() =
        runTest {
            val rig = BleRig(this)
            val heard = collectAll(rig.ble.scan(ScanFilter(setOf(HeartRateUuids.SERVICE))))
            rig.advance(100)
            val found = heard.filterIsInstance<ScanEvent.Found>().map { it.advertisement }
            assertEquals(1, found.size)
            assertEquals("HR7", found.single().name)
            assertEquals(setOf(HeartRateUuids.SERVICE), found.single().serviceUuids)

            val all = collectAll(rig.ble.scan())
            rig.advance(100)
            assertEquals(2, all.filterIsInstance<ScanEvent.Found>().size)

            rig.ble.setAvailability(BleAvailability.PERMISSION_DENIED)
            val denied = collectAll(rig.ble.scan())
            rig.advance(100)
            assertEquals(listOf(ScanEvent.Failed(app.overdrive.core.ble.ScanFailure.PERMISSION_DENIED)), denied)
        }

    @Test
    fun `a notification keeps its bytes and the world's clock`() {
        val sample = GattNotification(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(1, 2, 3), 5L)
        assertEquals(sample, GattNotification(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(1, 2, 3), 5L))
        assertTrue(sample != GattNotification(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(1, 2, 4), 5L))
    }
}
