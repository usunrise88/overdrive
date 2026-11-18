package app.overdrive.ble.gatt

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The transport's own limits. Callers add shorter ones on top (the adapters give every operation 10 s). */
internal data class GattTimings(
    /** From `connectGatt` to a connected link; Android's own connect timeout is about 30 s (unverified). */
    val connect: Duration = 35.seconds,
    /** One discovery, read, write or CCCD write. A backstop: a caller's own timeout normally comes first. */
    val operation: Duration = 15.seconds,
    /** The optional MTU request. Short: Android 14 and later may negotiate it unasked and never answer a second one. */
    val mtu: Duration = 3.seconds,
    /** How long an operation whose caller gave up may stay unanswered before the link is declared dead. */
    val abandonedGrace: Duration = 5.seconds,
    /** How long `close()` waits for the platform to report the disconnect before it releases the object anyway. */
    val disconnectGrace: Duration = 2.seconds,
)

/** The platform operations that take the one operation slot. */
private enum class OpKind(
    /** Whether the platform itself refuses a new operation until this one is answered (Android's "device busy"). */
    val occupiesPlatform: Boolean,
) {
    CONNECT(true),
    DISCOVER(true),
    MTU(false),
    READ(true),
    WRITE(true),
    CCCD(true),
}

/**
 * The [GattTransport] of the ride app: **all the logic of a Bluetooth link on top of a [PlatformGatt]**, which is the
 * few dozen lines over `BluetoothGatt` (docs/adr/0010-android-ble-and-pairing.md). It meets the contract of core's
 * `FakeBle`
 * exactly: a single-use object, one operation at a time, results and never exceptions, a failed or dropped link is dead
 * and closed (never reused), `close()` is idempotent and safe while an operation is pending.
 *
 * **Threads.** Platform callbacks arrive on the platform's own threads ([onEvent]). They take a lock for a few
 * assignments, complete a deferred (which only schedules the waiting coroutine), emit into a flow with `tryEmit`, and
 * do nothing else: no blocking, no decoding, no allocation beyond the event. Everything else runs on the caller's
 * coroutine.
 *
 * **One operation at a time.** [slot] holds the outstanding operation. A second call while it is live answers `BUSY`.
 * An operation whose caller gave up (a timeout or a cancelled coroutine) keeps the slot, because Android still waits
 * for its callback and refuses everything else meanwhile: the next call waits for it, and if the callback never comes
 * the link is declared dead after [GattTimings.abandonedGrace] (the supervisor then opens a new transport). The one
 * exception is the MTU request, which the platform does not serialise.
 *
 * **Closing.** [close] marks the object closed and failed at once, answers a pending operation with `DISCONNECTED`,
 * asks the platform to disconnect, and releases the platform object when it reports the disconnect or after
 * [GattTimings.disconnectGrace]: releasing it in the same breath as the disconnect can leave the link up on some
 * stacks, so the next connect fails with 133 (unverified; ADR 0010).
 *
 * @param scope the long-lived scope of the Bluetooth stack: the watchdog and the delayed release run there.
 * @param nanoClock `SystemClock.elapsedRealtimeNanos()`; it stamps each notification at the moment it arrives.
 */
internal class AndroidGattTransport(
    private val opener: PlatformGattOpener,
    private val device: DeviceId,
    private val scope: CoroutineScope,
    private val nanoClock: () -> Long,
    private val timings: GattTimings = GattTimings(),
    private val log: BleLog = BleLog.None,
) : GattTransport,
    PlatformListener {
    private class Op(
        val kind: OpKind,
    ) {
        val result = CompletableDeferred<PlatformEvent>()

        /** Completes when the operation no longer holds the slot, however that came about. */
        val settled = CompletableDeferred<Unit>()
        var abandoned = false
    }

    /** What taking the slot came to. [Wait] only exists inside [acquire]: it loops until it is [Go] or [Fail]. */
    private sealed interface Acquired {
        class Go(
            val op: Op,
            val gatt: PlatformGatt,
        ) : Acquired

        class Fail(
            val failure: GattFailure,
        ) : Acquired

        class Wait(
            val op: Op,
        ) : Acquired
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow<GattLinkState>(GattLinkState.Idle)
    private val flow =
        MutableSharedFlow<GattNotification>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val subscriptions = ConcurrentHashMap<GattUuid, ValueUpdate>()

    /** Completes when the platform reported the link down: only then may its object be released without waiting. */
    private val platformDown = CompletableDeferred<Unit>()
    private val platformReleased = AtomicBoolean(false)

    // Guarded by [lock].
    private var gatt: PlatformGatt? = null
    private var slot: Op? = null
    private var closed = false

    override val state: StateFlow<GattLinkState> = mutableState.asStateFlow()

    override val notifications: Flow<GattNotification> = flow.asSharedFlow()

    // region connect

    override suspend fun connect(): GattResult<Unit> {
        val connecting =
            synchronized(lock) {
                if (closed || mutableState.value != GattLinkState.Idle) {
                    null
                } else {
                    Op(OpKind.CONNECT).also {
                        slot = it
                        mutableState.value = GattLinkState.Connecting
                    }
                }
            }
        if (connecting == null) {
            log.log("$device: connect() on a transport that was used or closed")
            return failed(GattFailureKind.CLOSED)
        }
        val handle =
            try {
                opener.open(device, this)
            } catch (e: SecurityException) {
                log.log("$device: connectGatt needs a permission (${e.logName()})")
                return failConnect(connecting, GattFailure(GattFailureKind.UNAVAILABLE))
            } catch (e: Exception) {
                log.log("$device: connectGatt failed (${e.logName()})")
                return failConnect(connecting, GattFailure(GattFailureKind.STATUS, GattStatus.UNKNOWN))
            }
        if (handle == null) {
            log.log("$device: connectGatt returned nothing")
            return failConnect(connecting, GattFailure(GattFailureKind.UNAVAILABLE))
        }
        val keep =
            synchronized(lock) {
                // A callback may already have ended the link (or close() ran) before connectGatt returned.
                if (mutableState.value is GattLinkState.Disconnected) {
                    false
                } else {
                    gatt = handle
                    true
                }
            }
        if (!keep) shutdown(handle)
        val event =
            try {
                withTimeoutOrNull(timings.connect) { connecting.result.await() }
            } catch (cancelled: CancellationException) {
                log.log("$device: connect cancelled; the transport is dead")
                endLink(GattStatus.UNKNOWN)
                throw cancelled
            }
        if (event == null) {
            log.log("$device: no connection result within ${timings.connect}")
            endLink(GattStatus.UNKNOWN)
            return failed(GattFailureKind.TIMEOUT)
        }
        val change = event as? PlatformEvent.ConnectionChanged
        return when {
            change == null -> failed(GattFailureKind.STATUS, GattStatus.UNKNOWN)

            change.connected && change.status == GattStatus.SUCCESS -> GattResult.Ok(Unit)

            // A clean disconnect while connecting (we asked for it, or the stack gave up without a reason).
            change.status == GattStatus.SUCCESS -> failed(GattFailureKind.DISCONNECTED, GattStatus.SUCCESS)

            else -> failed(GattFailureKind.STATUS, change.status)
        }
    }

    private fun failConnect(
        connecting: Op,
        failure: GattFailure,
    ): GattResult<Unit> {
        synchronized(lock) {
            if (slot === connecting) slot = null
            if (mutableState.value !is GattLinkState.Disconnected) {
                mutableState.value = GattLinkState.Disconnected(GattStatus.SUCCESS)
            }
        }
        connecting.settled.complete(Unit)
        return GattResult.Failed(failure)
    }

    // endregion

    // region operations

    override suspend fun discoverServices(): GattResult<GattServices> =
        operation(OpKind.DISCOVER, timings.operation, { it.discoverServices() }) { event ->
            when {
                event !is PlatformEvent.ServicesDiscovered -> unexpected()
                event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                else -> GattResult.Ok(GattServices(event.characteristics))
            }
        }

    override suspend fun requestMtu(mtu: Int): GattResult<Int> =
        operation(OpKind.MTU, timings.mtu, { it.requestMtu(mtu) }) { event ->
            when {
                event !is PlatformEvent.MtuChanged -> unexpected()
                event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                else -> GattResult.Ok(event.mtu)
            }
        }

    override suspend fun read(characteristic: GattCharacteristicInfo): GattResult<ByteArray> =
        operation(
            OpKind.READ,
            timings.operation,
            { it.read(characteristic.service, characteristic.characteristic) },
        ) { event ->
            when {
                event !is PlatformEvent.CharacteristicRead -> unexpected()
                event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                else -> GattResult.Ok(event.value)
            }
        }

    override suspend fun write(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> {
        // The caller may reuse its array while the platform still holds ours.
        val copy = value.copyOf()
        return operation(
            OpKind.WRITE,
            timings.operation,
            { it.write(characteristic.service, characteristic.characteristic, copy) },
        ) { event ->
            when {
                event !is PlatformEvent.CharacteristicWritten -> unexpected()
                event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                else -> GattResult.Ok(Unit)
            }
        }
    }

    /**
     * A write **without response** (`WRITE_TYPE_NO_RESPONSE`): the Click's handshake goes out this way, and a Click may
     * reject the same bytes written with a response (docs/protocols/click-v2.md §3.1). The result arrives as the same
     * write callback, once the stack has taken the value, so it takes the same slot as a write with response.
     */
    override suspend fun writeWithoutResponse(
        characteristic: GattCharacteristicInfo,
        value: ByteArray,
    ): GattResult<Unit> {
        // The platform would send a write command to a characteristic that does not take one and never say so; the fake
        // radio answers "write not permitted", so this does too, and the caller learns why nothing happened.
        if (GattProperty.WRITE_WITHOUT_RESPONSE !in characteristic.properties) {
            log.log("$device: ${characteristic.characteristic} does not offer write without response")
            return statusFailure(WRITE_NOT_PERMITTED)
        }
        val copy = value.copyOf()
        return operation(
            OpKind.WRITE,
            timings.operation,
            { it.writeWithoutResponse(characteristic.service, characteristic.characteristic, copy) },
        ) { event ->
            when {
                event !is PlatformEvent.CharacteristicWritten -> unexpected()
                event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                else -> GattResult.Ok(Unit)
            }
        }
    }

    override suspend fun enable(
        characteristic: GattCharacteristicInfo,
        mode: ValueUpdate,
    ): GattResult<Unit> {
        // Registered before the write: a peripheral may push the first value the moment the descriptor is written, and
        // the callback must already know how to tag it.
        val previous = subscriptions.put(characteristic.characteristic, mode)
        val result =
            operation(
                OpKind.CCCD,
                timings.operation,
                { it.enable(characteristic.service, characteristic.characteristic, mode) },
            ) { event ->
                when {
                    event !is PlatformEvent.CccdWritten -> unexpected()
                    event.status != GattStatus.SUCCESS -> statusFailure(event.status)
                    else -> GattResult.Ok(Unit)
                }
            }
        if (result is GattResult.Failed) {
            if (previous ==
                null
            ) {
                subscriptions.remove(characteristic.characteristic)
            } else {
                subscriptions[characteristic.characteristic] =
                    previous
            }
        }
        return result
    }

    /**
     * One operation: take the slot (or fail), start it on the platform, wait for the callback or the timeout. A caller
     * that gives up leaves the operation abandoned, see the class comment.
     */
    private suspend fun <T> operation(
        kind: OpKind,
        timeout: Duration,
        start: (PlatformGatt) -> Start,
        interpret: (PlatformEvent) -> GattResult<T>,
    ): GattResult<T> {
        val go =
            when (val acquired = acquire(kind)) {
                is Acquired.Go -> acquired
                is Acquired.Fail -> return GattResult.Failed(acquired.failure)
                is Acquired.Wait -> error("acquire() waits for an abandoned operation itself")
            }
        val started =
            try {
                start(go.gatt)
            } catch (e: SecurityException) {
                log.log("$device: $kind needs a permission (${e.logName()})")
                Start.PERMISSION
            } catch (e: Exception) {
                log.log("$device: $kind threw ${e.logName()}")
                Start.ERROR
            }
        if (started != Start.STARTED) {
            release(go.op)
            log.log("$device: $kind not started: $started")
            return GattResult.Failed(startFailure(started))
        }
        val event =
            try {
                withTimeoutOrNull(timeout) { go.op.result.await() }
            } catch (cancelled: CancellationException) {
                abandon(go.op)
                throw cancelled
            }
        if (event == null) {
            log.log("$device: $kind not answered within $timeout")
            abandon(go.op)
            return failed(GattFailureKind.TIMEOUT)
        }
        if (event is PlatformEvent.ConnectionChanged) return failed(GattFailureKind.DISCONNECTED, event.status)
        return interpret(event)
    }

    /** Takes the operation slot, waiting for an abandoned operation to clear first, or says why not. */
    private suspend fun acquire(kind: OpKind): Acquired {
        while (true) {
            val acquired =
                synchronized(lock) {
                    val current = slot
                    when {
                        closed -> {
                            Acquired.Fail(GattFailure(GattFailureKind.CLOSED))
                        }

                        else -> {
                            when (val link = mutableState.value) {
                                GattLinkState.Idle, GattLinkState.Connecting -> {
                                    Acquired.Fail(GattFailure(GattFailureKind.CLOSED))
                                }

                                is GattLinkState.Disconnected -> {
                                    Acquired.Fail(GattFailure(GattFailureKind.DISCONNECTED, link.status))
                                }

                                GattLinkState.Connected -> {
                                    val handle = gatt
                                    when {
                                        current != null && current.abandoned -> Acquired.Wait(current)
                                        current != null -> Acquired.Fail(GattFailure(GattFailureKind.BUSY))
                                        handle == null -> Acquired.Fail(GattFailure(GattFailureKind.CLOSED))
                                        else -> Acquired.Go(Op(kind).also { slot = it }, handle)
                                    }
                                }
                            }
                        }
                    }
                }
            if (acquired is Acquired.Wait) acquired.op.settled.await() else return acquired
        }
    }

    /** The caller stopped waiting for [op]. */
    private fun abandon(op: Op) {
        val watch =
            synchronized(lock) {
                when {
                    slot !== op || op.result.isCompleted -> {
                        false
                    }

                    op.kind.occupiesPlatform -> {
                        op.abandoned = true
                        true
                    }

                    else -> {
                        slot = null
                        false
                    }
                }
            }
        if (watch) {
            scope.launch {
                delay(timings.abandonedGrace)
                val stuck = synchronized(lock) { slot === op }
                if (stuck) {
                    log.log("$device: ${op.kind} was never answered; the link is declared dead")
                    endLink(GattStatus.UNKNOWN)
                }
            }
        } else {
            op.settled.complete(Unit)
        }
    }

    private fun release(op: Op) {
        synchronized(lock) { if (slot === op) slot = null }
        op.settled.complete(Unit)
    }

    // endregion

    // region platform callbacks

    /** Every platform callback arrives here, on a thread of the platform's choosing. Never blocks. */
    override fun onEvent(event: PlatformEvent) {
        when (event) {
            is PlatformEvent.ConnectionChanged -> onConnection(event)
            is PlatformEvent.Changed -> onChanged(event)
            is PlatformEvent.ServicesDiscovered -> complete(OpKind.DISCOVER, event)
            is PlatformEvent.MtuChanged -> complete(OpKind.MTU, event)
            is PlatformEvent.CharacteristicRead -> complete(OpKind.READ, event)
            is PlatformEvent.CharacteristicWritten -> complete(OpKind.WRITE, event)
            is PlatformEvent.CccdWritten -> complete(OpKind.CCCD, event)
        }
    }

    private fun complete(
        kind: OpKind,
        event: PlatformEvent,
    ) {
        val matched =
            synchronized(lock) {
                val current = slot
                if (current != null && current.kind == kind) {
                    slot = null
                    current
                } else {
                    null
                }
            }
        if (matched == null) {
            log.log("$device: unasked $kind result ignored")
            return
        }
        matched.result.complete(event)
        matched.settled.complete(Unit)
    }

    private fun onConnection(event: PlatformEvent.ConnectionChanged) {
        var finished: Op? = null
        var gone: PlatformGatt? = null
        synchronized(lock) {
            val current = mutableState.value
            when {
                current is GattLinkState.Disconnected -> {
                    // Late word about a link that is already dead (after close(), or a repeated callback).
                    if (!event.connected) platformDown.complete(Unit)
                }

                event.connected && event.status == GattStatus.SUCCESS -> {
                    if (current == GattLinkState.Connecting) {
                        mutableState.value = GattLinkState.Connected
                        finished = slot?.takeIf { it.kind == OpKind.CONNECT }?.also { slot = null }
                    }
                }

                else -> {
                    mutableState.value = GattLinkState.Disconnected(event.status)
                    finished = slot.also { slot = null }
                    gone = gatt
                    platformDown.complete(Unit)
                }
            }
        }
        finished?.let {
            it.result.complete(event)
            it.settled.complete(Unit)
        }
        if (gone != null) {
            log.log("$device: link down, status ${event.status}")
            releasePlatform(gone)
        }
    }

    private fun onChanged(event: PlatformEvent.Changed) {
        val mode = subscriptions[event.characteristic] ?: return
        if (closed || mutableState.value != GattLinkState.Connected) return
        flow.tryEmit(GattNotification(event.characteristic, event.value, nanoClock(), mode))
    }

    // endregion

    // region ending

    override fun close() {
        var finished: Op? = null
        var toStop: PlatformGatt? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            val before = mutableState.value
            if (before !is GattLinkState.Disconnected) {
                mutableState.value =
                    GattLinkState.Disconnected(GattStatus.SUCCESS)
            }
            finished = slot.also { slot = null }
            toStop = gatt.takeIf { before !is GattLinkState.Disconnected }
        }
        finished?.let {
            it.result.complete(PlatformEvent.ConnectionChanged(GattStatus.SUCCESS, connected = false))
            it.settled.complete(Unit)
        }
        toStop?.let { shutdown(it) }
    }

    /** The link is over for good, for a reason the platform did not report ([status] is ours): end it and release it. */
    private fun endLink(status: Int) {
        var finished: Op? = null
        var toStop: PlatformGatt? = null
        synchronized(lock) {
            if (mutableState.value is GattLinkState.Disconnected) return
            mutableState.value = GattLinkState.Disconnected(status)
            finished = slot.also { slot = null }
            toStop = gatt
        }
        finished?.let {
            it.result.complete(PlatformEvent.ConnectionChanged(status, connected = false))
            it.settled.complete(Unit)
        }
        toStop?.let { shutdown(it) }
    }

    /**
     * Asks the platform to disconnect, then releases it when the platform says it did, or after the grace period. The
     * release runs even when the scope is cancelled (an app that is going away), so the object is never left behind.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun shutdown(handle: PlatformGatt) {
        try {
            handle.disconnect()
        } catch (e: Exception) {
            log.log("$device: disconnect threw ${e.logName()}")
        }
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                withTimeoutOrNull(timings.disconnectGrace) { platformDown.await() }
            } finally {
                releasePlatform(handle)
            }
        }
    }

    /** Releases the platform object once, whatever it throws (the shim never throws; a test double might). */
    private fun releasePlatform(handle: PlatformGatt) {
        if (platformReleased.compareAndSet(false, true)) {
            try {
                handle.close()
            } catch (e: Exception) {
                log.log("$device: close threw ${e.logName()}")
            }
        }
    }

    // endregion

    private fun <T> failed(
        kind: GattFailureKind,
        status: Int = GattStatus.UNKNOWN,
    ): GattResult<T> = GattResult.Failed(GattFailure(kind, status))

    private fun statusFailure(status: Int): GattResult.Failed =
        GattResult.Failed(GattFailure(GattFailureKind.STATUS, status))

    private fun unexpected(): GattResult.Failed = statusFailure(GattStatus.UNKNOWN)

    private fun startFailure(start: Start): GattFailure =
        when (start) {
            Start.STARTED, Start.REFUSED, Start.ERROR -> GattFailure(GattFailureKind.STATUS, GattStatus.UNKNOWN)
            Start.BUSY -> GattFailure(GattFailureKind.BUSY)
            Start.NOT_CONNECTED -> GattFailure(GattFailureKind.DISCONNECTED)
            Start.NOT_FOUND -> GattFailure(GattFailureKind.NOT_FOUND)
            Start.PERMISSION, Start.UNAVAILABLE -> GattFailure(GattFailureKind.UNAVAILABLE)
        }

    private companion object {
        const val BUFFER = 256

        /** `GATT_WRITE_NOT_PERMIT`: what the fake radio answers a write the characteristic does not offer. */
        const val WRITE_NOT_PERMITTED = 3
    }
}
