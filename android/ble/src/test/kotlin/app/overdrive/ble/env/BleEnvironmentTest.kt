package app.overdrive.ble.env

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Looper
import app.overdrive.core.ble.BleAvailability
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What [BleAvailability] says for each combination of the three facts, and how the environment follows the platform. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BleEnvironmentTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class FakeProbe(
        var supported: Boolean = true,
        var adapterOn: Boolean = true,
        var permissions: Boolean = true,
    ) : RadioProbe {
        override fun supported() = supported

        override fun adapterOn() = adapterOn

        override fun permissionsGranted() = permissions
    }

    @Test
    fun `availability is unsupported, then permission, then Bluetooth off, then ready`() {
        val table =
            listOf(
                Triple(false, false, false) to BleAvailability.UNSUPPORTED,
                Triple(false, true, true) to BleAvailability.UNSUPPORTED,
                Triple(true, true, false) to BleAvailability.PERMISSION_DENIED,
                Triple(true, false, false) to BleAvailability.PERMISSION_DENIED,
                Triple(true, false, true) to BleAvailability.BLUETOOTH_OFF,
                Triple(true, true, true) to BleAvailability.READY,
            )
        for ((facts, expected) in table) {
            assertEquals("$facts", expected, availabilityOf(facts.first, facts.second, facts.third))
        }
    }

    @Test
    fun `the environment starts with what the platform says`() {
        assertEquals(BleAvailability.READY, AndroidBleEnvironment(context, FakeProbe()).availability.value)
        assertEquals(
            BleAvailability.BLUETOOTH_OFF,
            AndroidBleEnvironment(context, FakeProbe(adapterOn = false)).availability.value,
        )
        assertEquals(
            BleAvailability.PERMISSION_DENIED,
            AndroidBleEnvironment(context, FakeProbe(permissions = false)).availability.value,
        )
    }

    @Test
    fun `the adapter state broadcast moves the environment`() {
        val probe = FakeProbe()
        val environment = AndroidBleEnvironment(context, probe)
        assertEquals(BleAvailability.READY, environment.availability.value)

        probe.adapterOn = false
        context.sendBroadcast(
            Intent(
                BluetoothAdapter.ACTION_STATE_CHANGED,
            ).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BleAvailability.BLUETOOTH_OFF, environment.availability.value)

        probe.adapterOn = true
        context.sendBroadcast(
            Intent(
                BluetoothAdapter.ACTION_STATE_CHANGED,
            ).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BleAvailability.READY, environment.availability.value)
        environment.close()
    }

    @Test
    fun `refresh picks up a permission that was granted or revoked, which has no broadcast`() {
        val probe = FakeProbe(permissions = false)
        val environment = AndroidBleEnvironment(context, probe)
        assertEquals(BleAvailability.PERMISSION_DENIED, environment.availability.value)
        probe.permissions = true
        environment.refresh()
        assertEquals(BleAvailability.READY, environment.availability.value)
        probe.permissions = false
        environment.refresh()
        assertEquals(BleAvailability.PERMISSION_DENIED, environment.availability.value)
        environment.close()
    }

    @Test
    fun `after close the broadcast no longer reaches it, and closing twice is harmless`() {
        val probe = FakeProbe()
        val environment = AndroidBleEnvironment(context, probe)
        environment.close()
        environment.close()
        probe.adapterOn = false
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BleAvailability.READY, environment.availability.value)
    }

    @Test
    fun `the system probe reads both Nearby-devices permissions from the package`() {
        val probe = SystemRadioProbe(context)
        shadowOf(context as android.app.Application).denyPermissions(*SystemRadioProbe.REQUIRED)
        assertEquals(false, probe.permissionsGranted())
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        assertEquals("one of the two is not enough", false, probe.permissionsGranted())
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(true, probe.permissionsGranted())
    }

    @Test
    fun `the permissions the rider is asked for are exactly scan and connect`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            AndroidBleEnvironment.REQUIRED_PERMISSIONS.toList(),
        )
    }
}
