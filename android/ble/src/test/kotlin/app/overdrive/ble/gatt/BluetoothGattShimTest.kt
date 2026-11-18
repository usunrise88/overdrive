package app.overdrive.ble.gatt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import app.overdrive.core.ble.ClickUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothGatt

/**
 * The shim over a real `BluetoothGatt` (Robolectric's shadow of it): what a write with and without response does to the
 * platform object and what comes back through [GattCallback]. The shadow does not keep the write type, so the type itself
 * is covered by `writeTypeOf`'s test and by the bench (ADR 0012); this proves the path is taken, the bytes arrive, the
 * answer maps and a missing characteristic is a [Start], not a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BluetoothGattShimTest {
    private val events = mutableListOf<PlatformEvent>()

    private fun rig(): Pair<BluetoothGattShim, ShadowBluetoothGatt> {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:07")
        val gatt: BluetoothGatt = ShadowBluetoothGatt.newInstance(device)
        val shadow =
            org.robolectric.shadow.api.Shadow
                .extract<ShadowBluetoothGatt>(gatt)
        shadow.setGattCallback(GattCallback { events += it })
        val service = BluetoothGattService(ClickUuids.SERVICE.toUuid(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                ClickUuids.SYNC_RX.toUuid(),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        shadow.addDiscoverableService(service)
        assertTrue(gatt.discoverServices())
        events.clear()
        return BluetoothGattShim(gatt) to shadow
    }

    @Test
    fun `a write without response is accepted, carries the bytes and is answered by the write callback`() {
        val (shim, shadow) = rig()
        val bytes = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)
        assertEquals(Start.STARTED, shim.writeWithoutResponse(ClickUuids.SERVICE, ClickUuids.SYNC_RX, bytes))
        assertEquals(bytes.toList(), shadow.latestWrittenBytes.toList())
        assertEquals(
            listOf<PlatformEvent>(PlatformEvent.CharacteristicWritten(ClickUuids.SYNC_RX, 0)),
            events,
        )
    }

    @Test
    fun `a write with response goes the same way`() {
        val (shim, shadow) = rig()
        assertEquals(Start.STARTED, shim.write(ClickUuids.SERVICE, ClickUuids.SYNC_RX, byteArrayOf(1, 2)))
        assertEquals(listOf<Byte>(1, 2), shadow.latestWrittenBytes.toList())
        assertEquals(1, events.size)
    }

    @Test
    fun `a characteristic the device does not have is NOT_FOUND for both kinds of write`() {
        val (shim, _) = rig()
        assertEquals(Start.NOT_FOUND, shim.writeWithoutResponse(ClickUuids.SERVICE, ClickUuids.ASYNC, byteArrayOf(1)))
        assertEquals(Start.NOT_FOUND, shim.write(ClickUuids.SERVICE, ClickUuids.ASYNC, byteArrayOf(1)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a platform that refuses the write is a refusal the transport understands`() {
        val (shim, shadow) = rig()
        shadow.setWriteCharacteristicResult(android.bluetooth.BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)
        assertEquals(Start.BUSY, shim.writeWithoutResponse(ClickUuids.SERVICE, ClickUuids.SYNC_RX, byteArrayOf(1)))
        shadow.setWriteCharacteristicResult(
            android.bluetooth.BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION,
        )
        assertEquals(
            Start.PERMISSION,
            shim.writeWithoutResponse(ClickUuids.SERVICE, ClickUuids.SYNC_RX, byteArrayOf(1)),
        )
    }
}
