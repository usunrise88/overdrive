package app.overdrive.ble.gatt

import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.fake.FakeBle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **A whole simulated radio behind the platform shim.** Core's `FakeBle` plays the peripherals (the fake trainer, the
 * fake strap, scripted faults, replays); this turns one of its transports into what `BluetoothGatt` looks like to
 * [AndroidGattTransport]: `open` returns at once and the connection result arrives later as an event, every start
 * answers "accepted" or, as Android does, "busy" while another operation is outstanding, and results arrive as
 * callbacks on a coroutine. Under a test scheduler the whole chain runs in virtual time, so core's scenarios (a dropped
 * link, status 133, Bluetooth off, a silent Control Point) can be replayed through **our** transport and compared with
 * what the fake itself does (docs/adr/0010-android-ble-and-pairing.md).
 */
internal class CorePlatform(
    private val world: FakeBle,
    private val scope: CoroutineScope,
) : PlatformGattOpener {
    /** Every platform handle opened, oldest first. */
    val handles = mutableListOf<CoreGatt>()

    override fun open(
        device: DeviceId,
        listener: PlatformListener,
    ): PlatformGatt {
        val handle = CoreGatt(world.open(device), listener, scope)
        handles += handle
        handle.begin()
        return handle
    }
}

/** One `BluetoothGatt`: a core transport and the callbacks of the platform. */
internal class CoreGatt(
    private val core: GattTransport,
    private val listener: PlatformListener,
    private val scope: CoroutineScope,
) : PlatformGatt {
    private var busy = false
    private var characteristics = emptyList<GattCharacteristicInfo>()
    private var wasConnected = false
    var released = false
        private set

    fun begin() {
        scope.launch {
            val result = core.connect()
            when (result) {
                is GattResult.Ok -> {
                    wasConnected = true
                    listener.onEvent(PlatformEvent.ConnectionChanged(GattStatus.SUCCESS, connected = true))
                }

                is GattResult.Failed -> {
                    listener.onEvent(
                        PlatformEvent.ConnectionChanged(result.failure.status.coerceAtLeast(0), connected = false),
                    )
                }
            }
        }
        scope.launch {
            core.notifications.collect { listener.onEvent(PlatformEvent.Changed(it.characteristic, it.value)) }
        }
        scope.launch {
            core.state.collect { state ->
                if (state is GattLinkState.Disconnected && wasConnected) {
                    wasConnected = false
                    listener.onEvent(PlatformEvent.ConnectionChanged(state.status.coerceAtLeast(0), connected = false))
                }
            }
        }
    }

    /** One platform operation: refused while another is outstanding, like `mDeviceBusy`; the result is a callback later. */
    private fun operation(block: suspend () -> PlatformEvent?): Start {
        if (busy) return Start.BUSY
        busy = true
        scope.launch {
            val event = block()
            busy = false
            // A link that dropped under the operation delivers its own connection event, not an operation result.
            if (event != null) listener.onEvent(event)
        }
        return Start.STARTED
    }

    override fun discoverServices(): Start =
        operation {
            when (val result = core.discoverServices()) {
                is GattResult.Ok -> {
                    characteristics = result.value.characteristics
                    PlatformEvent.ServicesDiscovered(GattStatus.SUCCESS, characteristics)
                }

                is GattResult.Failed -> {
                    result
                        .takeUnless { it.failure.kind == GattFailureKind.DISCONNECTED }
                        ?.let { PlatformEvent.ServicesDiscovered(it.failure.status, emptyList()) }
                }
            }
        }

    override fun requestMtu(mtu: Int): Start =
        operation {
            when (val result = core.requestMtu(mtu)) {
                is GattResult.Ok -> PlatformEvent.MtuChanged(GattStatus.SUCCESS, result.value)
                is GattResult.Failed -> PlatformEvent.MtuChanged(result.failure.status, 23)
            }
        }

    override fun read(
        service: GattUuid,
        characteristic: GattUuid,
    ): Start {
        val info = find(service, characteristic) ?: return Start.NOT_FOUND
        return operation {
            when (val result = core.read(info)) {
                is GattResult.Ok -> {
                    PlatformEvent.CharacteristicRead(characteristic, GattStatus.SUCCESS, result.value)
                }

                is GattResult.Failed -> {
                    result
                        .takeUnless { it.failure.kind == GattFailureKind.DISCONNECTED }
                        ?.let { PlatformEvent.CharacteristicRead(characteristic, it.failure.status, ByteArray(0)) }
                }
            }
        }
    }

    override fun write(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start {
        val info = find(service, characteristic) ?: return Start.NOT_FOUND
        return operation {
            when (val result = core.write(info, value)) {
                is GattResult.Ok -> {
                    PlatformEvent.CharacteristicWritten(characteristic, GattStatus.SUCCESS)
                }

                is GattResult.Failed -> {
                    result
                        .takeUnless { it.failure.kind == GattFailureKind.DISCONNECTED }
                        ?.let { PlatformEvent.CharacteristicWritten(characteristic, it.failure.status) }
                }
            }
        }
    }

    override fun writeWithoutResponse(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ): Start {
        val info = find(service, characteristic) ?: return Start.NOT_FOUND
        return operation {
            when (val result = core.writeWithoutResponse(info, value)) {
                is GattResult.Ok -> {
                    PlatformEvent.CharacteristicWritten(characteristic, GattStatus.SUCCESS)
                }

                is GattResult.Failed -> {
                    result
                        .takeUnless { it.failure.kind == GattFailureKind.DISCONNECTED }
                        ?.let { PlatformEvent.CharacteristicWritten(characteristic, it.failure.status) }
                }
            }
        }
    }

    override fun enable(
        service: GattUuid,
        characteristic: GattUuid,
        mode: ValueUpdate,
    ): Start {
        val info = find(service, characteristic) ?: return Start.NOT_FOUND
        return operation {
            when (val result = core.enable(info, mode)) {
                is GattResult.Ok -> {
                    PlatformEvent.CccdWritten(characteristic, GattStatus.SUCCESS)
                }

                is GattResult.Failed -> {
                    result
                        .takeUnless { it.failure.kind == GattFailureKind.DISCONNECTED }
                        ?.let { PlatformEvent.CccdWritten(characteristic, it.failure.status) }
                }
            }
        }
    }

    /** The platform ends the link (the fake's peripheral lets go at once) and reports it, as Android does a moment later. */
    override fun disconnect() {
        core.close()
        scope.launch { listener.onEvent(PlatformEvent.ConnectionChanged(GattStatus.SUCCESS, connected = false)) }
    }

    /** `BluetoothGatt.close()`: the client interface is released, which is what the fake counts as closed. */
    override fun close() {
        released = true
        core.close()
    }

    private fun find(
        service: GattUuid,
        characteristic: GattUuid,
    ): GattCharacteristicInfo? =
        characteristics.firstOrNull {
            it.service == service &&
                it.characteristic == characteristic
        }
}
