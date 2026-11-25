package app.overdrive.core.click

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattServices
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.ScanEvent
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Click needed from the Bluetooth boundary: writes without response, and manufacturer data in a scan. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickTransportTest {
    private suspend fun connected(rig: BleRig): Pair<GattTransport, GattServices> {
        val transport = rig.ble.open(CLICK_ID)
        assertIs<GattResult.Ok<Unit>>(transport.connect())
        val services = transport.discoverServices().valueOrNull()
        return transport to assertNotNull(services)
    }

    @Test
    fun `the fake takes a write without response where the characteristic offers it and tells the two writes apart`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.addClick()
            val (transport, services) = connected(rig)
            val rx = assertNotNull(services.find(ClickUuids.SERVICE, ClickUuids.SYNC_RX))
            assertIs<GattResult.Ok<Unit>>(transport.writeWithoutResponse(rx, byteArrayOf(1)))
            assertIs<GattResult.Ok<Unit>>(transport.write(rx, byteArrayOf(2)))
            assertEquals(listOf(false, true), fake.writes.map { it.withResponse })
            transport.close()
            rig.assertNoViolations()
        }

    @Test
    fun `a write without response to a characteristic that does not offer it fails with a status not a violation`() =
        runTest {
            val rig = BleRig(this)
            rig.addClick()
            val (transport, services) = connected(rig)
            val async = assertNotNull(services.find(ClickUuids.SERVICE, ClickUuids.ASYNC))
            val failed = assertIs<GattResult.Failed>(transport.writeWithoutResponse(async, byteArrayOf(1)))
            assertEquals(GattFailureKind.STATUS, failed.failure.kind)
            assertEquals(3, failed.failure.status)
            transport.close()
            rig.assertNoViolations()
        }

    @Test
    fun `a transport written before the method existed gets a write with response`() =
        runTest {
            class OldTransport : GattTransport {
                var withResponse = 0
                override val state: StateFlow<GattLinkState> = MutableStateFlow(GattLinkState.Connected)
                override val notifications: Flow<GattNotification> = emptyFlow()

                override suspend fun connect(): GattResult<Unit> = GattResult.Ok(Unit)

                override suspend fun discoverServices(): GattResult<GattServices> =
                    GattResult.Failed(GattFailure(GattFailureKind.CLOSED))

                override suspend fun requestMtu(mtu: Int): GattResult<Int> = GattResult.Ok(mtu)

                override suspend fun read(characteristic: GattCharacteristicInfo): GattResult<ByteArray> =
                    GattResult.Ok(ByteArray(0))

                override suspend fun write(
                    characteristic: GattCharacteristicInfo,
                    value: ByteArray,
                ): GattResult<Unit> {
                    withResponse++
                    return GattResult.Ok(Unit)
                }

                override suspend fun enable(
                    characteristic: GattCharacteristicInfo,
                    mode: ValueUpdate,
                ): GattResult<Unit> = GattResult.Ok(Unit)

                override fun close() = Unit
            }
            val old = OldTransport()
            val info = GattCharacteristicInfo(ClickUuids.SERVICE, ClickUuids.SYNC_RX)
            assertIs<GattResult.Ok<Unit>>(old.writeWithoutResponse(info, byteArrayOf(1)))
            assertEquals(1, old.withResponse, "the fallback: the Click's SYNC_RX offers both")
        }

    @Test
    fun `the recording decorator passes the mode through and records the write as a write`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.addClick()
            val recorded = rig.recording()
            rig.click(factory = recorded.factory).startAndSettle(rig)
            rig.settle()
            assertEquals(listOf(false, false), fake.writes.map { it.withResponse })
            val writes = recorded.events().filter { it.direction.code == 'W' }
            assertEquals(2, writes.size)
            assertTrue(writes.all { it.status == 0 && it.characteristic == ClickUuids.SYNC_RX })
            assertContentEquals("RideOn".encodeToByteArray(), writes.first().bytes())
        }

    @Test
    fun `a scan hears the Click with its manufacturer data and the service filter hides the other devices`() =
        runTest {
            val rig = BleRig(this)
            rig.addClick()
            val found =
                rig.ble
                    .scan(ClickAdvertisements.scanFilter())
                    .first { it is ScanEvent.Found }
            val advertisement = assertIs<ScanEvent.Found>(found).advertisement
            assertEquals(CLICK_ID, advertisement.id)
            assertEquals(
                ClickMatch(ClickSide.RIGHT, ClickService.CURRENT),
                ClickAdvertisements.recognise(advertisement),
            )

            // Without a filter the trainer and the strap are heard too, and none of them is a Click.
            val heard = mutableListOf<app.overdrive.core.ble.Advertisement>()
            rig.ble.scan().collectFirstN(3) { heard += (it as ScanEvent.Found).advertisement }
            assertEquals(3, heard.size)
            assertEquals(1, heard.count { ClickAdvertisements.recognise(it) != null })
        }

    @Test
    fun `the left puck of the fake advertises the other byte`() =
        runTest {
            val rig = BleRig(this)
            rig.addClick(
                FakeClick(
                    app.overdrive.core.ble.fake
                        .FakeClickOptions(side = ClickSide.LEFT),
                ),
            )
            val advertisement =
                assertIs<ScanEvent.Found>(
                    rig.ble.scan(ClickAdvertisements.scanFilter()).first(),
                ).advertisement
            assertNull(ClickAdvertisements.recognise(advertisement), "not wanted by default")
            assertEquals(
                ClickSide.LEFT,
                ClickAdvertisements.recognise(advertisement, ClickOptions(acceptedSides = setOf(ClickSide.LEFT)))?.side,
            )
        }

    private suspend fun <T> Flow<T>.collectFirstN(
        count: Int,
        action: (T) -> Unit,
    ) {
        var seen = 0
        this.first {
            action(it)
            ++seen >= count
        }
    }
}
