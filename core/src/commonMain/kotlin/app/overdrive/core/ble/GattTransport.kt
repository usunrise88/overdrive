package app.overdrive.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * GATT status codes that core gives a name. Statuses cross the transport boundary as plain `Int`s, exactly as the
 * platform reports them, so no value is lost; only these two mean something to core.
 */
public object GattStatus {
    /** `GATT_SUCCESS`. */
    public const val SUCCESS: Int = 0

    /**
     * Android's generic `GATT_ERROR`, the status of most failed or stale connections. The recovery is always the same:
     * close the GATT object and start again with a fresh one (docs/protocols/ftms-hr.md §7, unverified on the bench).
     */
    public const val GATT_ERROR: Int = 133

    /** Placeholder for "the platform gave no status", such as a timeout that core detected itself. */
    public const val UNKNOWN: Int = -1
}

/** What a characteristic allows, as discovered (GATT characteristic properties). */
public enum class GattProperty {
    READ,
    WRITE,
    WRITE_WITHOUT_RESPONSE,
    NOTIFY,
    INDICATE,
}

/**
 * One characteristic found by service discovery. It is also the handle every read, write and subscription takes, so a
 * transport can look it up by (service, characteristic) without searching, and two services may repeat a UUID.
 */
public data class GattCharacteristicInfo(
    val service: GattUuid,
    val characteristic: GattUuid,
    val properties: Set<GattProperty> = emptySet(),
)

/** The result of service discovery: every characteristic of every service the peripheral offers. */
public class GattServices(
    public val characteristics: List<GattCharacteristicInfo>,
) {
    /** The characteristic [characteristic] of [service], or null if the peripheral does not have it. */
    public fun find(
        service: GattUuid,
        characteristic: GattUuid,
    ): GattCharacteristicInfo? =
        characteristics.firstOrNull {
            it.service == service &&
                it.characteristic == characteristic
        }

    /** Whether the peripheral offers [service] at all. */
    public fun hasService(service: GattUuid): Boolean = characteristics.any { it.service == service }

    override fun toString(): String = "GattServices(${characteristics.size} characteristics)"
}

/** How a peripheral pushes values: CCCD value `0x0001` (notification) or `0x0002` (indication, acknowledged). */
public enum class ValueUpdate {
    NOTIFICATION,
    INDICATION,
}

/**
 * One value pushed by the peripheral, raw and untouched, stamped by the transport the moment it arrived.
 *
 * @property timestampNanos a monotonic clock in nanoseconds (`SystemClock.elapsedRealtimeNanos()` on Android). Only
 * differences matter. The session recorder makes them relative before writing a file.
 * @property kind [ValueUpdate.INDICATION] for a characteristic the transport enabled as indicating, else notification:
 * the platform delivers both through one callback, so the transport tags them from what it enabled.
 */
public class GattNotification(
    public val characteristic: GattUuid,
    public val value: ByteArray,
    public val timestampNanos: Long,
    public val kind: ValueUpdate = ValueUpdate.NOTIFICATION,
) {
    override fun equals(other: Any?): Boolean =
        other is GattNotification &&
            characteristic == other.characteristic &&
            timestampNanos == other.timestampNanos &&
            kind == other.kind &&
            value.contentEquals(other.value)

    override fun hashCode(): Int =
        ((characteristic.hashCode() * 31 + timestampNanos.hashCode()) * 31 + kind.hashCode()) * 31 +
            value.contentHashCode()

    override fun toString(): String = "GattNotification($characteristic, ${value.size} bytes, $kind, t=$timestampNanos)"
}

/** Why a GATT operation did not complete. */
public enum class GattFailureKind {
    /** The peripheral or the stack answered with a status other than success; see [GattFailure.status]. */
    STATUS,

    /** The link went away while the operation was waiting for its answer. */
    DISCONNECTED,

    /** No answer within the transport's own limit. Callers add their own timeouts on top. */
    TIMEOUT,

    /** Another operation was still outstanding. Callers serialise operations, so this is a bug in the caller. */
    BUSY,

    /** The characteristic or service is not on the peripheral. */
    NOT_FOUND,

    /** The transport was closed, or never connected. A closed transport is never reused. */
    CLOSED,

    /** Bluetooth is off or the permission was revoked (on Android, a `SecurityException` or an adapter-off error). */
    UNAVAILABLE,
}

/** A failed GATT operation: [kind] says what went wrong and [status] carries the platform's code when there is one. */
public data class GattFailure(
    val kind: GattFailureKind,
    val status: Int = GattStatus.UNKNOWN,
) {
    /** Whether this is Android's generic `GATT_ERROR` (133): the GATT object is stale and must be replaced. */
    val isGattError: Boolean get() = status == GattStatus.GATT_ERROR
}

/** The outcome of a GATT operation, without exceptions: BLE failures are routine, not exceptional. */
public sealed interface GattResult<out T> {
    /** The operation succeeded with [value]. */
    public data class Ok<out T>(
        val value: T,
    ) : GattResult<T>

    /** The operation failed. */
    public data class Failed(
        val failure: GattFailure,
    ) : GattResult<Nothing>
}

/** The failure of this result, or null if it succeeded. */
public fun <T> GattResult<T>.failureOrNull(): GattFailure? = (this as? GattResult.Failed)?.failure

/** The value of this result, or null if it failed. */
public fun <T> GattResult<T>.valueOrNull(): T? = (this as? GattResult.Ok<T>)?.value

/** Whether this result is a success. */
public val GattResult<*>.isOk: Boolean get() = this is GattResult.Ok

/** The state of one GATT link. A transport only moves forward: it never leaves [Disconnected]. */
public sealed interface GattLinkState {
    /** Opened, [GattTransport.connect] not called yet. */
    public data object Idle : GattLinkState

    /** [GattTransport.connect] is in progress. */
    public data object Connecting : GattLinkState

    /** The link is up (services may not be discovered yet). */
    public data object Connected : GattLinkState

    /**
     * The link is gone for good: the peripheral went away ([status] is the platform's reason), the connect attempt
     * failed, or the transport was closed ([GattStatus.SUCCESS]). The object is dead; [GattTransportFactory.open] makes
     * a new one.
     */
    public data class Disconnected(
        val status: Int,
    ) : GattLinkState
}

/**
 * One GATT client session with one peripheral: **the only boundary between core and the Bluetooth stack**. On Android
 * it wraps one `BluetoothGatt`; the fake of `app.overdrive.core.ble.fake` implements it over a simulated peripheral.
 *
 * **Lifecycle.** A transport is single-use. [connect] once, then operations, then [close]. When the link drops or a
 * connect fails, [state] becomes [GattLinkState.Disconnected] and the transport is dead: a reconnect asks the
 * [GattTransportFactory] for a new one. This models the rule that a `BluetoothGatt` is never reused after status 133
 * (docs/protocols/ftms-hr.md §7): the code above the transport cannot reuse a failed object, because it has no way to
 * connect it again.
 *
 * **Operations.** Every suspending function returns a [GattResult] instead of throwing. At most one operation may be
 * outstanding at a time (the platform allows one, and starting a second one fails); a transport answers an overlapping
 * call with [GattFailureKind.BUSY] and callers serialise, in particular every CCCD write ([enable]). Operations on a
 * dead transport fail with [GattFailureKind.CLOSED] or [GattFailureKind.DISCONNECTED]; an operation that is waiting
 * when the link drops completes with [GattFailureKind.DISCONNECTED]. Operations may be called from any coroutine.
 *
 * **Threads.** Platform callbacks arrive on binder threads; implementations resume the waiting coroutine and emit into
 * [notifications] from there and do no other work (decoding happens above, off the callback thread).
 */
public interface GattTransport {
    /** The link state, starting at [GattLinkState.Idle]. */
    public val state: StateFlow<GattLinkState>

    /**
     * Every value the peripheral pushes, in arrival order: a hot flow that sees what arrives after a collector starts,
     * so collect it **before** the first [enable]. Values arrive only for characteristics that were enabled.
     */
    public val notifications: Flow<GattNotification>

    /**
     * Opens the link (`autoConnect = false`) and returns when it is up or has failed. A failure is final for this
     * object. The caller bounds the wait with its own timeout and then calls [close].
     */
    public suspend fun connect(): GattResult<Unit>

    /** Discovers services and characteristics. Call once per link, after [connect]. */
    public suspend fun discoverServices(): GattResult<GattServices>

    /** Asks for an ATT MTU of [mtu] bytes and returns the negotiated size. Optional: callers ignore a failure. */
    public suspend fun requestMtu(mtu: Int): GattResult<Int>

    /** Reads a characteristic. */
    public suspend fun read(characteristic: GattCharacteristicInfo): GattResult<ByteArray>

    /** Writes a characteristic **with response** (`WRITE_TYPE_DEFAULT`), returning when the peripheral acknowledged. */
    public suspend fun write(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit>

    /**
     * Writes a characteristic **without response** (`WRITE_TYPE_NO_RESPONSE`), returning when the stack has taken the
     * value (on Android, the write callback; nothing says the peripheral saw it). The Zwift Click's handshake is written
     * this way (docs/protocols/click-v2.md §3.1).
     *
     * **The default falls back to [write]**, a write with response, so a transport written before this existed keeps
     * compiling and, on a characteristic that offers both (the Click's does), keeps working. A transport that can do it
     * overrides this; M4-2's Android transport does. The fake, the recorder and the replay already do.
     */
    public suspend fun writeWithoutResponse(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> = write(characteristic, value)

    /**
     * Subscribes to a characteristic: enables local delivery and writes its CCCD with `0x0001` or `0x0002` according to
     * [mode], returning when the write was acknowledged.
     */
    public suspend fun enable(
        characteristic: GattCharacteristicInfo,
        mode: ValueUpdate,
    ): GattResult<Unit>

    /**
     * Disconnects if connected and releases every resource. Idempotent and never throws; afterwards [state] is
     * [GattLinkState.Disconnected] and every operation fails with [GattFailureKind.CLOSED].
     */
    public fun close()
}

/**
 * How a transport connects (docs/adr/0040-background-connect-for-sleepy-devices.md). On Android this is the `autoConnect` flag of
 * `connectGatt`.
 */
public enum class ConnectMode {
    /**
     * Connect now (`autoConnect = false`): the controller scans for the device at full duty and the attempt ends with an
     * error after the platform's own limit (about 30 s, status 147 or 133) unless the caller gives up first. Fast when the
     * device is awake, and nothing when it is asleep.
     */
    ACTIVE,

    /**
     * Connect whenever the device appears (`autoConnect = true`): the controller keeps a pending connection and completes
     * it the moment the device advertises, which is what a device woken by a button press needs. It has **no limit of its
     * own**: the call stays pending for hours. The caller bounds it with a watchdog and closes the transport to cancel it.
     */
    BACKGROUND,
}

/** Makes transports. The reconnect supervisor asks for a new one for every attempt and never reuses a dead one. */
public fun interface GattTransportFactory {
    /** A new, unconnected transport to [device] that connects actively. Cheap and non-blocking; nothing happens on the air until `connect`. */
    public fun open(device: DeviceId): GattTransport

    /**
     * A new, unconnected transport to [device] that connects as [mode] says. **The default ignores the mode** (every
     * connect is active), so a factory written before background connects keeps working; the Android factory, the fake
     * and the recorder override it.
     */
    public fun open(
        device: DeviceId,
        mode: ConnectMode,
    ): GattTransport = open(device)
}
