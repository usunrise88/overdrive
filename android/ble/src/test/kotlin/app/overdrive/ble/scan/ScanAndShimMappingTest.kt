package app.overdrive.ble.scan

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import app.overdrive.ble.gatt.Start
import app.overdrive.ble.gatt.characteristicsOf
import app.overdrive.ble.gatt.propertiesOf
import app.overdrive.ble.gatt.startOfStatusCode
import app.overdrive.ble.gatt.toGattUuid
import app.overdrive.ble.gatt.toUuid
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.ScanFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** The pure parts of the scanner and of the `BluetoothGatt` shim: what a scan result and a discovery turn into. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanAndShimMappingTest {
    @Test
    fun `a scan result becomes an advertisement with an opaque id, its name, its services and its signal`() {
        val advertisement =
            checkNotNull(
                ScanMapping.advertisement(
                    address = "AA:BB:CC:DD:EE:02",
                    name = "  D500 ",
                    services = listOf(FtmsUuids.SERVICE.toUuid(), UUID.randomUUID()),
                    rssi = -61,
                ),
            )
        assertEquals("D500", advertisement.name)
        assertEquals(-61, advertisement.rssi)
        assertEquals(true, FtmsUuids.SERVICE in advertisement.serviceUuids)
        assertEquals(2, advertisement.serviceUuids.size)
        assertEquals("AA:BB:CC:DD:EE:02", advertisement.id.value)
        assertEquals("the id never prints the address", false, advertisement.id.toString().contains("AA:BB"))
        assertEquals("nor does the advertisement", false, advertisement.toString().contains("AA:BB"))
    }

    @Test
    fun `a blank name is no name, no address is no advertisement, no services is an empty set`() {
        assertNull(ScanMapping.advertisement("AA:BB:CC:DD:EE:03", "   ", null, -70)!!.name)
        assertNull(ScanMapping.advertisement("AA:BB:CC:DD:EE:03", null, null, -70)!!.name)
        assertEquals(
            emptySet<GattUuid>(),
            ScanMapping.advertisement("AA:BB:CC:DD:EE:03", null, null, -70)!!.serviceUuids,
        )
        assertNull(ScanMapping.advertisement(null, "D500", emptyList(), -70))
        assertNull(ScanMapping.advertisement("  ", "D500", emptyList(), -70))
    }

    @Test
    fun `the platform's scan errors are refusals unless the radio itself went away`() {
        assertEquals(ScanFailure.REFUSED, ScanMapping.failure(BleAvailability.READY))
        assertEquals(ScanFailure.BLUETOOTH_OFF, ScanMapping.failure(BleAvailability.BLUETOOTH_OFF))
        assertEquals(ScanFailure.PERMISSION_DENIED, ScanMapping.failure(BleAvailability.PERMISSION_DENIED))
        assertNull(ScanMapping.unavailable(BleAvailability.READY))
        assertEquals(ScanFailure.BLUETOOTH_OFF, ScanMapping.unavailable(BleAvailability.UNSUPPORTED))
    }

    @Test
    fun `discovery maps services, characteristics and every property bit`() {
        val trainer = BluetoothGattService(FtmsUuids.SERVICE.toUuid(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        trainer.addCharacteristic(
            BluetoothGattCharacteristic(
                FtmsUuids.INDOOR_BIKE_DATA.toUuid(),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            ),
        )
        trainer.addCharacteristic(
            BluetoothGattCharacteristic(
                FtmsUuids.CONTROL_POINT.toUuid(),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                0,
            ),
        )
        val strap = BluetoothGattService(HeartRateUuids.SERVICE.toUuid(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        strap.addCharacteristic(
            BluetoothGattCharacteristic(
                HeartRateUuids.MEASUREMENT.toUuid(),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                0,
            ),
        )
        val found = characteristicsOf(listOf(trainer, strap))
        assertEquals(
            listOf(
                GattCharacteristicInfo(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA, setOf(GattProperty.NOTIFY)),
                GattCharacteristicInfo(
                    FtmsUuids.SERVICE,
                    FtmsUuids.CONTROL_POINT,
                    setOf(GattProperty.WRITE, GattProperty.INDICATE),
                ),
                GattCharacteristicInfo(
                    HeartRateUuids.SERVICE,
                    HeartRateUuids.MEASUREMENT,
                    setOf(GattProperty.NOTIFY, GattProperty.READ),
                ),
            ),
            found,
        )
    }

    @Test
    fun `every property bit has its own name`() {
        assertEquals(
            setOf(
                GattProperty.READ,
                GattProperty.WRITE,
                GattProperty.WRITE_WITHOUT_RESPONSE,
                GattProperty.NOTIFY,
                GattProperty.INDICATE,
            ),
            propertiesOf(
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            ),
        )
        assertEquals(emptySet<GattProperty>(), propertiesOf(0))
    }

    @Test
    fun `uuids round trip between core and the platform`() {
        val core = GattUuid.short(0x2AD9)
        assertEquals(UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb"), core.toUuid())
        assertEquals(core, core.toUuid().toGattUuid())
        assertNotNull(UUID.randomUUID().toGattUuid())
    }

    @Test
    fun `the status codes of Android 13 and later map to what the transport understands`() {
        assertEquals(Start.STARTED, startOfStatusCode(BluetoothStatusCodes.SUCCESS))
        assertEquals(Start.BUSY, startOfStatusCode(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY))
        assertEquals(
            Start.PERMISSION,
            startOfStatusCode(BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION),
        )
        assertEquals(Start.UNAVAILABLE, startOfStatusCode(BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED))
        assertEquals(Start.UNAVAILABLE, startOfStatusCode(BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND))
        assertEquals(Start.REFUSED, startOfStatusCode(BluetoothStatusCodes.ERROR_UNKNOWN))
        assertEquals(Start.REFUSED, startOfStatusCode(BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED))
    }
}
