package app.overdrive.core.ble.recording

import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattServices
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.ValueUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * A [GattTransport] that records everything crossing it into a [BleSessionRecorder] and otherwise behaves exactly like
 * [delegate] (docs/protocols/ftms-hr.md §7.1). Wrap the transport the device uses and the session file has every
 * connection event, discovered characteristic, read, write, CCCD write and notification, with the bytes unchanged.
 *
 * It is a decorator at the one boundary, so the recorder needs no cooperation from the adapters, the same decorator
 * records a real session on the tablet and a fake one in a test, and a recorded file replays through the fake of
 * `app.overdrive.core.ble.fake`.
 *
 * Notifications are relayed through a hot flow of their own, subscribed to the delegate at construction, so a collector
 * that starts before the first `enable` misses nothing. The two helper coroutines run in [scope] until [close].
 */
public class RecordingGattTransport(
    private val delegate: GattTransport,
    private val recorder: BleSessionRecorder,
    scope: CoroutineScope,
    private val mode: ConnectMode = ConnectMode.ACTIVE,
) : GattTransport {
    private val relayed =
        MutableSharedFlow<GattNotification>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val helpers: List<Job>
    private var lastState: GattLinkState = GattLinkState.Idle
    private var closed = false

    override val state: StateFlow<GattLinkState> get() = delegate.state

    override val notifications: Flow<GattNotification> = relayed.asSharedFlow()

    init {
        helpers =
            listOf(
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    delegate.notifications.collect { notification ->
                        val direction =
                            if (notification.kind ==
                                ValueUpdate.INDICATION
                            ) {
                                BleDirection.INDICATION
                            } else {
                                BleDirection.NOTIFICATION
                            }
                        recorder.recordBytes(
                            direction,
                            notification.characteristic,
                            null,
                            notification.value,
                            notification.timestampNanos,
                        )
                        relayed.emit(notification)
                    }
                },
                scope.launch(start = CoroutineStart.UNDISPATCHED) { delegate.state.collect(::recordState) },
            )
    }

    override suspend fun connect(): GattResult<Unit> = delegate.connect()

    override suspend fun discoverServices(): GattResult<GattServices> {
        val result = delegate.discoverServices()
        if (result is GattResult.Ok) {
            for (info in result.value.characteristics) {
                recorder.record(
                    BleDirection.DISCOVERY,
                    info.characteristic,
                    null,
                    "${info.service.shortForm}/${properties(info)}",
                )
            }
        }
        return result
    }

    override suspend fun requestMtu(mtu: Int): GattResult<Int> {
        val result = delegate.requestMtu(mtu)
        if (result is GattResult.Ok) {
            recorder.record(
                BleDirection.STATE,
                null,
                GattStatus.SUCCESS,
                "${BleStates.MTU_PREFIX}${result.value}",
            )
        }
        return result
    }

    override suspend fun read(characteristic: GattCharacteristicInfo): GattResult<ByteArray> {
        val result = delegate.read(characteristic)
        recorder.recordBytes(
            BleDirection.READ,
            characteristic.characteristic,
            statusOf(result),
            (result as? GattResult.Ok)?.value,
        )
        return result
    }

    override suspend fun write(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> {
        val result = delegate.write(characteristic, value)
        recorder.recordBytes(BleDirection.WRITE, characteristic.characteristic, statusOf(result), value)
        return result
    }

    override suspend fun writeWithoutResponse(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> {
        val result = delegate.writeWithoutResponse(characteristic, value)
        recorder.recordBytes(BleDirection.WRITE, characteristic.characteristic, statusOf(result), value)
        return result
    }

    override suspend fun enable(
        characteristic: GattCharacteristicInfo,
        mode: ValueUpdate,
    ): GattResult<Unit> {
        val result = delegate.enable(characteristic, mode)
        val cccd = if (mode == ValueUpdate.INDICATION) CCCD_INDICATE else CCCD_NOTIFY
        recorder.recordBytes(BleDirection.CCCD, characteristic.characteristic, statusOf(result), cccd)
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
        // The state collector may not have seen the final state yet: say it here, then stop.
        recordState(delegate.state.value)
        recorder.record(BleDirection.STATE, null, null, BleStates.CLOSED)
        helpers.forEach { it.cancel() }
    }

    private fun recordState(state: GattLinkState) {
        if (state == lastState) return
        lastState = state
        when (state) {
            GattLinkState.Idle -> {
                Unit
            }

            GattLinkState.Connecting -> {
                recorder.record(
                    BleDirection.STATE,
                    null,
                    null,
                    if (mode == ConnectMode.BACKGROUND) BleStates.CONNECTING_BACKGROUND else BleStates.CONNECTING,
                )
            }

            GattLinkState.Connected -> {
                recorder.record(
                    BleDirection.STATE,
                    null,
                    GattStatus.SUCCESS,
                    BleStates.CONNECTED,
                )
            }

            is GattLinkState.Disconnected -> {
                recorder.record(
                    BleDirection.STATE,
                    null,
                    state.status,
                    BleStates.DISCONNECTED,
                )
            }
        }
    }

    private fun statusOf(result: GattResult<*>): Int? =
        when (result) {
            is GattResult.Ok -> GattStatus.SUCCESS
            is GattResult.Failed -> result.failure.status.takeIf { it >= 0 }
        }

    private fun properties(info: GattCharacteristicInfo): String =
        info.properties
            .sortedBy { it.ordinal }
            .joinToString("") {
                when (it) {
                    GattProperty.READ -> "R"
                    GattProperty.WRITE -> "W"
                    GattProperty.WRITE_WITHOUT_RESPONSE -> "w"
                    GattProperty.NOTIFY -> "N"
                    GattProperty.INDICATE -> "I"
                }
            }.ifEmpty { "-" }

    private companion object {
        const val BUFFER = 256
        val CCCD_NOTIFY = byteArrayOf(0x01, 0x00)
        val CCCD_INDICATE = byteArrayOf(0x02, 0x00)
    }
}

/**
 * Wraps every transport this factory opens so it records into [recorder]. One recorder spans the reconnects of a device:
 * its `S` lines mark where each connection began and ended. The factory forgets which [DeviceId] a transport is for: the
 * recorder never learns it.
 */
public fun GattTransportFactory.recording(
    recorder: BleSessionRecorder,
    scope: CoroutineScope,
): GattTransportFactory =
    object : GattTransportFactory {
        override fun open(device: DeviceId): GattTransport = open(device, ConnectMode.ACTIVE)

        override fun open(
            device: DeviceId,
            mode: ConnectMode,
        ): GattTransport = RecordingGattTransport(this@recording.open(device, mode), recorder, scope, mode)
    }
