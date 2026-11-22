package app.overdrive.core.ble.fake

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattServices
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ValueUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The simulated GATT client of [FakeBle]: one connection attempt to one [FakePeripheral], with the contract of
 * [GattTransport] enforced (see [FakeBle]). Single-use, like the real thing.
 */
public class FakeGattTransport internal constructor(
    internal val world: FakeBle,
    /** The device this transport was opened for. */
    public val device: DeviceId,
    /** 1 for the first transport the world opened, 2 for the second… */
    public val index: Int,
    private val peripheral: FakePeripheral,
    /** How the supervisor asked it to connect: a [ConnectMode.BACKGROUND] connect to an absent peripheral waits for it, with no limit. */
    public val mode: ConnectMode = ConnectMode.ACTIVE,
) : GattTransport {
    private val mutableState = MutableStateFlow<GattLinkState>(GattLinkState.Idle)
    private val flow =
        MutableSharedFlow<GattNotification>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val subscriptions = mutableMapOf<GattUuid, ValueUpdate>()
    private var busy = false
    private var connection: FakeConnection? = null

    /** Whether [close] was called. */
    public var isClosed: Boolean = false
        private set

    override val state: StateFlow<GattLinkState> = mutableState.asStateFlow()

    override val notifications: Flow<GattNotification> = flow.asSharedFlow()

    internal val isLinkUp: Boolean get() = mutableState.value == GattLinkState.Connected

    override suspend fun connect(): GattResult<Unit> {
        if (isClosed || mutableState.value != GattLinkState.Idle) {
            world.violate("connect() on transport #$index in state ${mutableState.value}, closed=$isClosed")
            return GattResult.Failed(GattFailure(GattFailureKind.CLOSED))
        }
        mutableState.value = GattLinkState.Connecting
        world.record("connect#$index")
        if (world.availability.value != BleAvailability.READY) {
            return failConnect(GattStatus.SUCCESS, GattFailureKind.UNAVAILABLE)
        }
        // The controller of a background connect completes it the moment the device advertises, however long that takes.
        if (mode == ConnectMode.BACKGROUND) peripheral.poweredState.first { it }
        delay(world.connectLatency)
        if (!peripheral.powered) {
            val after = peripheral.absentFailsAfter
            if (after == null) awaitCancellation()
            delay(after)
            return failConnect(GattStatus.GATT_ERROR)
        }
        peripheral.onConnectAttempt()?.let { return failConnect(it) }
        peripheral.faults.takeConnect()?.let { return failConnect(it) }
        if (peripheral.connection != null) return failConnect(GattStatus.GATT_ERROR)
        if (isClosed) return GattResult.Failed(GattFailure(GattFailureKind.CLOSED))

        val link = CoroutineScope(world.scope.coroutineContext + SupervisorJob(world.scope.coroutineContext[Job]))
        val live = FakeConnection(this, link)
        connection = live
        peripheral.connection = live
        mutableState.value = GattLinkState.Connected
        world.record("connected#$index")
        peripheral.onConnected(live)
        return GattResult.Ok(Unit)
    }

    private fun failConnect(
        status: Int,
        kind: GattFailureKind = GattFailureKind.STATUS,
    ): GattResult<Unit> {
        world.record("connect#$index failed($status)")
        mutableState.value = GattLinkState.Disconnected(status)
        return GattResult.Failed(GattFailure(kind, status))
    }

    override suspend fun discoverServices(): GattResult<GattServices> =
        operation("discoverServices") {
            peripheral.faults.takeDiscovery()?.let { return@operation statusFailure(it) }
            GattResult.Ok(GattServices(peripheral.characteristics))
        }

    override suspend fun requestMtu(mtu: Int): GattResult<Int> =
        operation("requestMtu") { GattResult.Ok(peripheral.negotiatedMtu ?: mtu) }

    override suspend fun read(characteristic: GattCharacteristicInfo): GattResult<ByteArray> =
        operation("read ${characteristic.characteristic}") {
            peripheral.onRead(characteristic.characteristic)?.let { GattResult.Ok(it) }
                ?: statusFailure(READ_NOT_PERMITTED)
        }

    override suspend fun write(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> =
        operation("write ${characteristic.characteristic}") {
            peripheral.faults.takeWrite(characteristic.characteristic)?.let { return@operation statusFailure(it) }
            val status = peripheral.onWrite(characteristic.characteristic, value)
            if (status == GattStatus.SUCCESS) GattResult.Ok(Unit) else statusFailure(status)
        }

    override suspend fun writeWithoutResponse(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> =
        operation("writeWithoutResponse ${characteristic.characteristic}") {
            peripheral.faults.takeWrite(characteristic.characteristic)?.let { return@operation statusFailure(it) }
            val offered = peripheral.characteristics.firstOrNull { it.characteristic == characteristic.characteristic }
            if (offered == null || GattProperty.WRITE_WITHOUT_RESPONSE !in offered.properties) {
                return@operation statusFailure(WRITE_NOT_PERMITTED)
            }
            val status = peripheral.onWriteWithoutResponse(characteristic.characteristic, value)
            if (status == GattStatus.SUCCESS) GattResult.Ok(Unit) else statusFailure(status)
        }

    override suspend fun enable(
        characteristic: GattCharacteristicInfo,
        mode: ValueUpdate,
    ): GattResult<Unit> =
        operation("enable ${characteristic.characteristic}") {
            peripheral.faults.takeCccd(characteristic.characteristic)?.let { return@operation statusFailure(it) }
            val offered = peripheral.characteristics.firstOrNull { it.characteristic == characteristic.characteristic }
            val needed = if (mode == ValueUpdate.INDICATION) GattProperty.INDICATE else GattProperty.NOTIFY
            if (offered == null || needed !in offered.properties) return@operation statusFailure(WRITE_NOT_PERMITTED)
            subscriptions[characteristic.characteristic] = mode
            peripheral.onSubscribed(characteristic.characteristic, mode)
            GattResult.Ok(Unit)
        }

    override fun close() {
        if (isClosed) return
        isClosed = true
        world.record("close#$index")
        endLink(GattStatus.SUCCESS)
    }

    internal fun dropLink(status: Int) {
        if (mutableState.value != GattLinkState.Connected) return
        world.record("drop#$index($status)")
        endLink(status)
    }

    /** The world ends this link (Bluetooth went off). */
    internal fun dropFromWorld() {
        if (mutableState.value == GattLinkState.Connected) dropLink(GattStatus.SUCCESS)
    }

    private fun endLink(status: Int) {
        val wasUp = mutableState.value == GattLinkState.Connected
        if (mutableState.value !is GattLinkState.Disconnected) mutableState.value = GattLinkState.Disconnected(status)
        if (wasUp) {
            connection?.scope?.cancel()
            if (peripheral.connection === connection) peripheral.connection = null
            peripheral.onDisconnected()
        }
        connection = null
    }

    internal fun deliver(
        characteristic: GattUuid,
        value: ByteArray,
    ): Boolean {
        if (!isLinkUp) return false
        val mode = subscriptions[characteristic] ?: return false
        val now = world.nanoClock()
        if (peripheral.faults.isSilenced(characteristic, now)) return false
        flow.tryEmit(GattNotification(characteristic, value, now, mode))
        return true
    }

    private fun statusFailure(status: Int): GattResult<Nothing> =
        GattResult.Failed(GattFailure(GattFailureKind.STATUS, status))

    /** One GATT operation: contract checks, the latency, then [body] if the link is still up. */
    private suspend fun <T> operation(
        name: String,
        body: () -> GattResult<T>,
    ): GattResult<T> {
        if (isClosed) {
            world.violate("$name on closed transport #$index")
            return GattResult.Failed(GattFailure(GattFailureKind.CLOSED))
        }
        when (val current = mutableState.value) {
            GattLinkState.Idle, GattLinkState.Connecting -> {
                world.violate("$name on transport #$index before it was connected ($current)")
                return GattResult.Failed(GattFailure(GattFailureKind.CLOSED))
            }

            is GattLinkState.Disconnected -> {
                return GattResult.Failed(GattFailure(GattFailureKind.DISCONNECTED, current.status))
            }

            GattLinkState.Connected -> {
                Unit
            }
        }
        if (busy) {
            world.violate("$name on transport #$index while another GATT operation was outstanding")
            return GattResult.Failed(GattFailure(GattFailureKind.BUSY))
        }
        busy = true
        val link = connection
        try {
            delay(world.opLatency)
            val after = mutableState.value
            if (after != GattLinkState.Connected || connection !== link) {
                val status = (after as? GattLinkState.Disconnected)?.status ?: GattStatus.UNKNOWN
                return GattResult.Failed(GattFailure(GattFailureKind.DISCONNECTED, status))
            }
            return body()
        } finally {
            busy = false
        }
    }

    private companion object {
        const val BUFFER = 256
        const val READ_NOT_PERMITTED = 2
        const val WRITE_NOT_PERMITTED = 3
    }
}
