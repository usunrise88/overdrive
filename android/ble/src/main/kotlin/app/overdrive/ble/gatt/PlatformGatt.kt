package app.overdrive.ble.gatt

import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ValueUpdate

/**
 * The thin shim between [AndroidGattTransport] (all the logic, plain Kotlin, tested on the JVM) and `BluetoothGatt` (a
 * few dozen lines, [BluetoothGattShim]). It models the platform as it is: **starting** an operation answers at once
 * with whether the platform accepted it ([Start]), and the **result** arrives later, on a thread the platform chooses,
 * as a [PlatformEvent] given to the [PlatformListener]. It carries no Android type, so a test scripts it by hand.
 *
 * Implementations never throw: every platform failure (`SecurityException`, `DeadObjectException`,
 * `IllegalStateException`, a null where an object was expected) becomes a [Start] value, and `disconnect` and `close`
 * swallow whatever the platform throws.
 */
internal interface PlatformGatt {
    /** `discoverServices()`; the answer is [PlatformEvent.ServicesDiscovered]. */
    fun discoverServices(): Start

    /** `requestMtu(mtu)`; the answer is [PlatformEvent.MtuChanged]. */
    fun requestMtu(mtu: Int): Start

    /** `readCharacteristic`; the answer is [PlatformEvent.CharacteristicRead]. */
    fun read(
        service: GattUuid,
        characteristic: GattUuid,
    ): Start

    /** `writeCharacteristic` with response; the answer is [PlatformEvent.CharacteristicWritten]. */
    fun write(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start

    /**
     * `writeCharacteristic` **without response** (`WRITE_TYPE_NO_RESPONSE`); the answer is the same
     * [PlatformEvent.CharacteristicWritten], which the platform sends once the stack has taken the value (nothing says the
     * peripheral saw it). The Zwift Click's handshake goes out this way (docs/protocols/click-v2.md §3.1).
     *
     * **The default is a write with response**, so a platform written before this existed keeps compiling and, on a
     * characteristic that offers both, keeps working; [BluetoothGattShim] overrides it.
     */
    fun writeWithoutResponse(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start = write(service, characteristic, value)

    /**
     * `setCharacteristicNotification(…, true)` and the write of the Client Characteristic Configuration descriptor
     * (`0x0001` for a notification, `0x0002` for an indication); the answer is [PlatformEvent.CccdWritten].
     */
    fun enable(
        service: GattUuid,
        characteristic: GattUuid,
        mode: ValueUpdate,
    ): Start

    /** Asks the platform to end the link (or to stop connecting). The answer is a [PlatformEvent.ConnectionChanged]. */
    fun disconnect()

    /** Releases the platform object. After it nothing more arrives. Idempotent. */
    fun close()
}

/** Opens the platform connection (`connectGatt`) and says where its events go. */
internal fun interface PlatformGattOpener {
    /**
     * Starts connecting to [device] (`autoConnect = false`, LE) and returns the platform handle, or null if the
     * platform gave none. Events, the first of which is the connection result, go to [listener]; they may arrive before
     * this call returns. May throw (`SecurityException` without the permission, `IllegalArgumentException` for an id
     * that is not an address): the transport turns that into a failed connect.
     */
    fun open(
        device: DeviceId,
        listener: PlatformListener,
    ): PlatformGatt?
}

/** Where the platform's callbacks go. Called on whatever thread the platform uses; it never blocks. */
internal fun interface PlatformListener {
    fun onEvent(event: PlatformEvent)
}

/** The platform's answer to "start this operation" (the callback with its result follows only after [STARTED]). */
internal enum class Start {
    /** Accepted: the result will arrive as a [PlatformEvent]. */
    STARTED,

    /** The platform said no without a reason (`false` from `readCharacteristic`, …). */
    REFUSED,

    /** The platform has another operation outstanding. */
    BUSY,

    /** The platform says the link is not up. */
    NOT_CONNECTED,

    /** The service, characteristic or descriptor is not on the device. */
    NOT_FOUND,

    /** A runtime permission is missing (`SecurityException`, or the platform's "missing permission" code). */
    PERMISSION,

    /** Bluetooth is off or the stack is gone (`DeadObjectException`, `IllegalStateException`, a null adapter). */
    UNAVAILABLE,

    /** Anything else that went wrong: the call threw something unexpected. */
    ERROR,
}

/** What the platform reports. Statuses are the platform's own `Int`s, unchanged. */
internal sealed interface PlatformEvent {
    /** `onConnectionStateChange`: [connected] is the new state, [status] the platform's reason (133 included). */
    data class ConnectionChanged(
        val status: Int,
        val connected: Boolean,
    ) : PlatformEvent

    /** `onServicesDiscovered`, with everything the device offers. */
    class ServicesDiscovered(
        val status: Int,
        val characteristics: List<GattCharacteristicInfo>,
    ) : PlatformEvent

    /** `onMtuChanged`. It also arrives unasked when either side starts an exchange. */
    data class MtuChanged(
        val status: Int,
        val mtu: Int,
    ) : PlatformEvent

    /** `onCharacteristicRead`. */
    class CharacteristicRead(
        val characteristic: GattUuid,
        val status: Int,
        val value: ByteArray,
    ) : PlatformEvent

    /** `onCharacteristicWrite`. */
    data class CharacteristicWritten(
        val characteristic: GattUuid,
        val status: Int,
    ) : PlatformEvent

    /** `onDescriptorWrite` of the Client Characteristic Configuration descriptor. */
    data class CccdWritten(
        val characteristic: GattUuid,
        val status: Int,
    ) : PlatformEvent

    /** `onCharacteristicChanged`: a notification or an indication, value already copied. */
    class Changed(
        val characteristic: GattUuid,
        val value: ByteArray,
    ) : PlatformEvent
}
