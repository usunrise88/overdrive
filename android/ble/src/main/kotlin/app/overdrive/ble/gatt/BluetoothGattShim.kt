package app.overdrive.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ValueUpdate
import java.util.UUID

/** The Client Characteristic Configuration descriptor (`0x2902`). */
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** `0x0001`, `0x0002`: what the descriptor is written with (`BluetoothGattDescriptor.ENABLE_*_VALUE`, spelled out). */
private val ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
private val ENABLE_INDICATION = byteArrayOf(0x02, 0x00)

internal fun GattUuid.toUuid(): UUID = UUID.fromString(value)

internal fun UUID.toGattUuid(): GattUuid? = GattUuid.parse(toString())

/** What `BluetoothGatt` found, as the transport's handles; characteristics with an unreadable UUID are left out. */
internal fun characteristicsOf(services: List<BluetoothGattService>): List<GattCharacteristicInfo> =
    services.flatMap { service ->
        val serviceUuid = service.uuid.toGattUuid() ?: return@flatMap emptyList()
        service.characteristics.mapNotNull { characteristic ->
            characteristic.uuid.toGattUuid()?.let {
                GattCharacteristicInfo(serviceUuid, it, propertiesOf(characteristic.properties))
            }
        }
    }

private val PROPERTY_BITS =
    listOf(
        BluetoothGattCharacteristic.PROPERTY_READ to GattProperty.READ,
        BluetoothGattCharacteristic.PROPERTY_WRITE to GattProperty.WRITE,
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to GattProperty.WRITE_WITHOUT_RESPONSE,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY to GattProperty.NOTIFY,
        BluetoothGattCharacteristic.PROPERTY_INDICATE to GattProperty.INDICATE,
    )

internal fun propertiesOf(bits: Int): Set<GattProperty> =
    PROPERTY_BITS.filter { (bit, _) -> bits and bit != 0 }.map { (_, property) -> property }.toSet()

/**
 * The platform's write type for a write with or without response: `WRITE_TYPE_DEFAULT` asks the peripheral to confirm,
 * `WRITE_TYPE_NO_RESPONSE` sends a write command and the callback comes when the stack has taken it. The Click's
 * handshake is the second kind (docs/protocols/click-v2.md §3.1).
 */
internal fun writeTypeOf(withResponse: Boolean): Int =
    if (withResponse) {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    } else {
        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }

/** How `writeCharacteristic` and `writeDescriptor` answer on Android 13 and later (`BluetoothStatusCodes`). */
internal fun startOfStatusCode(code: Int): Start =
    when (code) {
        BluetoothStatusCodes.SUCCESS -> Start.STARTED
        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> Start.BUSY
        BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION -> Start.PERMISSION
        BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED -> Start.UNAVAILABLE
        BluetoothStatusCodes.ERROR_PROFILE_SERVICE_NOT_BOUND -> Start.UNAVAILABLE
        else -> Start.REFUSED
    }

/**
 * `BluetoothGatt` behind [PlatformGatt]: each method is one platform call and the mapping of its answer. No
 * logic; everything the transport has to get right is in [AndroidGattTransport].
 *
 * The SDK-33 overloads that take the value and return a status code are used where they exist, the older ones (set the
 * value on the object, answer a boolean) on Android 12 and 12L, the minimum.
 */
@SuppressLint("MissingPermission")
internal class BluetoothGattShim(
    private val gatt: BluetoothGatt,
) : PlatformGatt {
    override fun discoverServices(): Start = start { if (gatt.discoverServices()) Start.STARTED else Start.REFUSED }

    override fun requestMtu(mtu: Int): Start = start { if (gatt.requestMtu(mtu)) Start.STARTED else Start.REFUSED }

    override fun read(
        service: GattUuid,
        characteristic: GattUuid,
    ): Start =
        start {
            val target = find(service, characteristic) ?: return@start Start.NOT_FOUND
            if (gatt.readCharacteristic(target)) Start.STARTED else Start.REFUSED
        }

    override fun write(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start = writeWith(service, characteristic, value, writeTypeOf(withResponse = true))

    override fun writeWithoutResponse(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start = writeWith(service, characteristic, value, writeTypeOf(withResponse = false))

    /** One `writeCharacteristic` of [writeType]: `WRITE_TYPE_DEFAULT` (with response) or `WRITE_TYPE_NO_RESPONSE`. */
    @Suppress("DEPRECATION")
    private fun writeWith(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
        writeType: Int,
    ): Start =
        start {
            val target = find(service, characteristic) ?: return@start Start.NOT_FOUND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startOfStatusCode(gatt.writeCharacteristic(target, value, writeType))
            } else {
                target.writeType = writeType
                target.value = value
                if (gatt.writeCharacteristic(target)) Start.STARTED else Start.REFUSED
            }
        }

    @Suppress("DEPRECATION")
    override fun enable(
        service: GattUuid,
        characteristic: GattUuid,
        mode: ValueUpdate,
    ): Start =
        start {
            val target = find(service, characteristic) ?: return@start Start.NOT_FOUND
            val descriptor = target.getDescriptor(CCCD) ?: return@start Start.NOT_FOUND
            if (!gatt.setCharacteristicNotification(target, true)) return@start Start.REFUSED
            val value = if (mode == ValueUpdate.INDICATION) ENABLE_INDICATION else ENABLE_NOTIFICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startOfStatusCode(gatt.writeDescriptor(descriptor, value))
            } else {
                descriptor.value = value
                if (gatt.writeDescriptor(descriptor)) Start.STARTED else Start.REFUSED
            }
        }

    override fun disconnect() {
        try {
            gatt.disconnect()
        } catch (_: Exception) {
            // Already gone, or the permission is: the transport is closing either way.
        }
    }

    override fun close() {
        try {
            gatt.close()
        } catch (_: Exception) {
            // See disconnect().
        }
    }

    private fun find(
        service: GattUuid,
        characteristic: GattUuid,
    ): BluetoothGattCharacteristic? {
        val serviceUuid = service.toUuid()
        val characteristicUuid = characteristic.toUuid()
        // Two services may repeat a characteristic UUID, so the pair is looked up, not the characteristic alone.
        return gatt.services
            ?.firstOrNull { it.uuid == serviceUuid && it.getCharacteristic(characteristicUuid) != null }
            ?.getCharacteristic(characteristicUuid)
    }

    /** One platform call: a permission, a dead stack or anything else that throws is an answer, not a crash. */
    private inline fun start(block: () -> Start): Start =
        try {
            block()
        } catch (_: SecurityException) {
            Start.PERMISSION
        } catch (_: IllegalStateException) {
            Start.UNAVAILABLE
        } catch (_: android.os.DeadObjectException) {
            Start.UNAVAILABLE
        } catch (_: Exception) {
            Start.ERROR
        }
}

/**
 * `BluetoothGattCallback` turned into [PlatformEvent]s. It runs on a platform thread and does nothing but copy values
 * into an event and hand it over. No `Handler` is passed to `connectGatt`: before Android 13 the value of a
 * notification lives in the shared characteristic object and a callback posted to another thread could read the next
 * notification's bytes, so the bytes are copied on the thread the platform calls us on (ADR 0010).
 */
internal class GattCallback(
    private val listener: PlatformListener,
) : BluetoothGattCallback() {
    override fun onConnectionStateChange(
        gatt: BluetoothGatt?,
        status: Int,
        newState: Int,
    ) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                listener.onEvent(
                    PlatformEvent.ConnectionChanged(status, connected = true),
                )
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                listener.onEvent(
                    PlatformEvent.ConnectionChanged(status, connected = false),
                )
            }
        }
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt?,
        status: Int,
    ) {
        val found =
            try {
                gatt?.services?.let(::characteristicsOf).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        listener.onEvent(PlatformEvent.ServicesDiscovered(status, found))
    }

    override fun onMtuChanged(
        gatt: BluetoothGatt?,
        mtu: Int,
        status: Int,
    ) {
        listener.onEvent(PlatformEvent.MtuChanged(status, mtu))
    }

    // Android 13 and later call this one, with the value.
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        read(characteristic, value, status)
    }

    // Android 12 and 12L call this one, and the value is in the characteristic.
    @Deprecated("Replaced by the overload with the value in Android 13")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int,
    ) {
        read(characteristic, characteristic?.value, status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
        status: Int,
    ) {
        val uuid = characteristic?.uuid?.toGattUuid() ?: return
        listener.onEvent(PlatformEvent.CharacteristicWritten(uuid, status))
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt?,
        descriptor: BluetoothGattDescriptor?,
        status: Int,
    ) {
        val uuid = descriptor?.characteristic?.uuid?.toGattUuid() ?: return
        listener.onEvent(PlatformEvent.CccdWritten(uuid, status))
    }

    // Android 13 and later: the value comes with the callback, so it cannot change under us.
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        changed(characteristic, value)
    }

    // Android 12 and 12L.
    @Deprecated("Replaced by the overload with the value in Android 13")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt?,
        characteristic: BluetoothGattCharacteristic?,
    ) {
        changed(characteristic, characteristic?.value)
    }

    private fun read(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray?,
        status: Int,
    ) {
        val uuid = characteristic?.uuid?.toGattUuid() ?: return
        listener.onEvent(PlatformEvent.CharacteristicRead(uuid, status, value?.copyOf() ?: ByteArray(0)))
    }

    private fun changed(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray?,
    ) {
        val uuid = characteristic?.uuid?.toGattUuid() ?: return
        listener.onEvent(PlatformEvent.Changed(uuid, value?.copyOf() ?: ByteArray(0)))
    }
}

/**
 * `BluetoothDevice.connectGatt` behind [PlatformGattOpener]. **The overload is the deprecated one on purpose** (ADR
 * 0010):
 * `connectGatt(Context, boolean, BluetoothGattCallback, int)` is deprecated in API 37 in favour of
 * `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`, which exists only from API 37, while
 * the tablet runs Android 15. One call that works on every API level this app supports beats two code paths of which
 * the new one could never run on the owner's device; the deprecated overload is not removed, and the replacement is a
 * five-line change in this one method (`setAutoConnectEnabled(false)`, `setTransport(TRANSPORT_LE)`).
 */
internal class BluetoothGattOpener(
    private val context: Context,
    private val adapter: () -> BluetoothAdapter?,
) : PlatformGattOpener {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun open(
        device: DeviceId,
        listener: PlatformListener,
        background: Boolean,
    ): PlatformGatt? {
        val remote = adapter()?.getRemoteDevice(device.value) ?: return null
        // autoConnect = background: a pending connection the controller completes when the device advertises (docs/adr/0040).
        val gatt =
            remote.connectGatt(context, background, GattCallback(listener), BluetoothDevice.TRANSPORT_LE) ?: return null
        return BluetoothGattShim(gatt)
    }
}
