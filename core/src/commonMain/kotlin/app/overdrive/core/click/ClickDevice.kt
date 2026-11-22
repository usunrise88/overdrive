package app.overdrive.core.click

import app.overdrive.core.ble.BatteryUuids
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.DeviceInformationUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattServices
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.adapter.GattIo
import app.overdrive.core.ble.adapter.setupFailure
import app.overdrive.core.ble.adapter.updateModeFor
import app.overdrive.core.ble.codec.DecodeResult
import app.overdrive.core.ble.codec.Hex
import app.overdrive.core.ble.failureOrNull
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.ConnectStrategy
import app.overdrive.core.ble.link.LinkControls
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ble.link.LinkSupervisor
import app.overdrive.core.ble.link.PeripheralSession
import app.overdrive.core.ble.link.SessionOutcome
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A Zwift Click v2 over a [GattTransportFactory]: the same shape and lifecycle as `HeartRateStrapDevice` (a [LinkControls],
 * a connection flow, a hot flow of what the device says), reconnecting by itself with backoff after [start], and a ride
 * that never depends on it (spec §4.7: "continue without the Click").
 *
 * **Set-up, per connection** (docs/protocols/click-v2.md §3.1): discover; find the Zwift service (`FC82`, else the older
 * one) and its three characteristics; optionally ask for a bigger MTU; **subscribe to ASYNC (notifications), then to SYNC_TX
 * (indications)**; **write `RideOn` to SYNC_RX without response** and read the reply on SYNC_TX; optionally write
 * `FF 04 00`; optionally read the firmware revision and the battery. Only the plain path is implemented (no encryption,
 * §3.3), and nothing here unlocks a controller. Every guess is an option of [ClickOptions].
 *
 * **What the UI can explain** ([state]): connected and delivering ([ClickState.Active]), **needs unlocking through Zwift**
 * ([ClickState.NeedsUnlock]: the service is hidden, or, only with [ClickOptions.silentMeansLocked] on (off by default:
 * a real Click sends nothing until a button is pressed), the handshake was answered and then nothing came within
 * [ClickOptions.noEventTimeout]), not found, Bluetooth unavailable, set-up failed. An answered handshake is [ClickState.Active].
 *
 * **Buttons.** [buttons] is a hot flow of [ButtonChange]s: edges from successive bitmaps, repeats ignored, all five buttons
 * at once. When a link ends while buttons are down the adapter **releases them itself** with a synthetic change, so a
 * release lost in the drop never leaves a button held; after a reconnect the first frame is compared with "nothing down".
 *
 * **Threads.** Notifications are decoded and applied one at a time on one coroutine of [scope], in arrival order, whatever
 * thread the platform delivers them on; the supervisor's session end is queued on the same line, so the synthetic release
 * can never overtake a frame.
 */
public class ClickDevice(
    private val scope: CoroutineScope,
    device: DeviceId,
    transports: GattTransportFactory,
    environment: BleEnvironment,
    /** The device's display name, for the Devices screen. */
    public val name: String,
    private val options: ClickOptions = ClickOptions(),
    reconnect: BackoffPolicy = BackoffPolicy(),
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
    establishTimeout: Duration = LinkSupervisor.DEFAULT_ESTABLISH_TIMEOUT,
    connectStrategy: () -> ConnectStrategy = { ConnectStrategy.LEGACY },
) : LinkControls {
    /** What the set-up and the first frames have shown about the current connection. */
    private enum class Probe { OFFLINE, HANDSHAKING, WAITING, SILENT, ACTIVE }

    private sealed interface Inbound {
        class Frame(
            val session: Int,
            val characteristic: GattUuid,
            val bytes: ByteArray,
            val timestampNanos: Long,
        ) : Inbound

        class Lost(
            val session: Int,
        ) : Inbound
    }

    private val mutableButtons =
        MutableSharedFlow<ButtonChange>(
            extraBufferCapacity = BUTTON_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableEvents =
        MutableSharedFlow<ClickEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableBattery = MutableStateFlow<Int?>(null)
    private val mutableFirmware = MutableStateFlow<String?>(null)
    private val probe = MutableStateFlow(Probe.OFFLINE)
    private val currentSession = MutableStateFlow(0)
    private val inbound = Channel<Inbound>(Channel.UNLIMITED)
    private var processor: Job? = null

    // Touched only by the processor coroutine.
    private val edges = ButtonEdges(options.ignoredBits)
    private var lastTimestampNanos = 0L

    private val supervisor =
        LinkSupervisor(
            scope = scope,
            device = device,
            transports = transports,
            environment = environment,
            sessions = { transport, sessionScope ->
                ClickSession(transport, sessionScope, currentSession.updateAndGet { it + 1 })
            },
            policy = reconnect,
            timeSource = timeSource,
            random = random,
            establishTimeout = establishTimeout,
            strategy = connectStrategy,
        )

    /** The three-state view of the link, like every device's. */
    public val connection: StateFlow<ConnectionState> get() = supervisor.connection

    override val status: StateFlow<LinkStatus> get() = supervisor.status

    /** What the Click is doing, in the terms the Devices screen explains. See [ClickState]. */
    public val state: StateFlow<ClickState> =
        combine(supervisor.status, probe) { link, found -> stateOf(link, found) }
            .stateIn(scope, SharingStarted.Eagerly, ClickState.Stopped)

    /**
     * The button changes, hot and without replay: collect before [start] to miss nothing. A change carries the whole
     * state ([ButtonChange.held]), so a collector that missed one recovers from the next.
     */
    public val buttons: SharedFlow<ButtonChange> = mutableButtons.asSharedFlow()

    /** What the link and the controller reported besides buttons: for the log and the bench. */
    public val events: SharedFlow<ClickEvent> = mutableEvents.asSharedFlow()

    /** The battery percentage from a `0x19` frame or the battery characteristic, or null until one arrives. Kept across reconnects. */
    public val battery: StateFlow<Int?> = mutableBattery.asStateFlow()

    /** The firmware revision read at set-up (spec §9: never update it, note it), or null. Never written to a recorded session. */
    public val firmware: StateFlow<String?> = mutableFirmware.asStateFlow()

    override fun start() {
        if (processor == null) {
            processor =
                scope.launch {
                    for (item in inbound) handle(item)
                }
        }
        supervisor.start()
    }

    /**
     * Retry now. For a controller that is connected but silent ([UnlockSignal.NO_EVENTS]) this also drops the link, so the
     * rider who has just unlocked it through Zwift starts over without waiting for a frame that is not coming.
     */
    override fun retryNow() {
        val silent = (state.value as? ClickState.NeedsUnlock)?.signal == UnlockSignal.NO_EVENTS
        if (silent) supervisor.session.value?.disconnect()
        supervisor.retryNow()
    }

    override fun close() {
        supervisor.close()
        processor?.cancel()
        processor = null
    }

    // region the one line that touches button state

    private fun handle(item: Inbound) {
        when (item) {
            is Inbound.Lost -> {
                // Always applies: a lost link releases whatever was held, even if a newer session has begun.
                edges.releaseAll(lastTimestampNanos)?.let { mutableButtons.tryEmit(it) }
            }

            is Inbound.Frame -> {
                if (item.session != currentSession.value) return
                lastTimestampNanos = item.timestampNanos
                onFrame(item)
            }
        }
    }

    private fun onFrame(frame: Inbound.Frame) {
        when (val parsed = ClickCodec.parse(frame.bytes)) {
            is DecodeResult.Malformed -> {
                mutableEvents.tryEmit(ClickEvent.MalformedFrame(parsed.error))
            }

            is DecodeResult.Ok -> {
                markAlive()
                when (val message = parsed.value) {
                    is ClickMessage.ButtonState -> {
                        edges.update(message.bitmap, frame.timestampNanos)?.let { mutableButtons.tryEmit(it) }
                    }

                    is ClickMessage.Battery -> {
                        if (message.percent in PERCENT_RANGE) mutableBattery.value = message.percent
                    }

                    ClickMessage.Heartbeat -> {
                        Unit
                    }

                    ClickMessage.DisconnectNotice -> {
                        mutableEvents.tryEmit(ClickEvent.DisconnectNoticeReceived)
                    }

                    is ClickMessage.Unrecognised -> {
                        mutableEvents.tryEmit(ClickEvent.UnrecognisedFrame(message.type, message.body.size))
                    }
                }
            }
        }
    }

    /** Any well-formed frame proves the controller is talking (spec §4.7: "delivering events"). */
    private fun markAlive() {
        probe.update { if (it == Probe.HANDSHAKING || it == Probe.WAITING || it == Probe.SILENT) Probe.ACTIVE else it }
    }

    // endregion

    private fun stateOf(
        link: LinkStatus,
        found: Probe,
    ): ClickState =
        when (link) {
            LinkStatus.Stopped -> {
                ClickState.Stopped
            }

            is LinkStatus.BluetoothUnavailable -> {
                ClickState.BluetoothUnavailable(link.availability)
            }

            is LinkStatus.Connecting -> {
                ClickState.Connecting(link.attempt)
            }

            LinkStatus.WaitingForDevice -> {
                ClickState.WaitingForDevice
            }

            LinkStatus.Connected -> {
                when (found) {
                    Probe.ACTIVE -> ClickState.Active
                    Probe.SILENT -> ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS)
                    else -> ClickState.Waiting
                }
            }

            is LinkStatus.WaitingToRetry -> {
                val failure = link.failure
                when {
                    failure.detail == SERVICE_HIDDEN_DETAIL -> {
                        ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN)
                    }

                    failure.kind == LinkFailureKind.LINK_LOST -> {
                        ClickState.NotFound(link.failures, link.retryInMillis, wasConnected = true)
                    }

                    failure.kind == LinkFailureKind.CONNECT_FAILED ||
                        failure.kind == LinkFailureKind.TIMED_OUT ||
                        failure.kind == LinkFailureKind.UNAVAILABLE -> {
                        ClickState.NotFound(link.failures, link.retryInMillis, wasConnected = false)
                    }

                    else -> {
                        ClickState.SetupFailed(failure, link.retryInMillis)
                    }
                }
            }
        }

    /** One connection to the Click. */
    private inner class ClickSession(
        private val transport: GattTransport,
        private val sessionScope: CoroutineScope,
        private val id: Int,
    ) : PeripheralSession {
        private val io = GattIo(transport, options.operationTimeout)
        private val reply = CompletableDeferred<ByteArray>()
        private var replyTaken = false // only the collector coroutine touches it

        /** Closes this link from outside, for [retryNow]. The supervisor sees the drop and takes it from there. */
        fun disconnect() {
            transport.close()
        }

        override suspend fun establish(): SessionOutcome {
            probe.value = Probe.HANDSHAKING
            sessionScope.launch(
                start = CoroutineStart.UNDISPATCHED,
            ) { transport.notifications.collect(::onNotification) }

            val services =
                when (val found = io.run { discoverServices() }) {
                    is GattResult.Ok -> found.value

                    is GattResult.Failed -> return SessionOutcome.Failed(
                        setupFailure("discover services", found.failure),
                    )
                }
            val characteristics =
                when (val resolved = resolve(services)) {
                    is Resolved.Found -> resolved
                    is Resolved.Failed -> return SessionOutcome.Failed(resolved.failure)
                }

            options.requestedMtu?.let { mtu ->
                io.run { requestMtu(mtu) }.failureOrNull()?.let {
                    mutableEvents.tryEmit(ClickEvent.OptionalStepFailed("request MTU", it))
                }
            }

            val async = characteristics.async
            when (val enabled = io.run { enable(async, updateModeFor(async, ValueUpdate.NOTIFICATION)) }) {
                is GattResult.Failed -> return SessionOutcome.Failed(
                    setupFailure("enable ASYNC notifications", enabled.failure),
                )

                is GattResult.Ok -> Unit
            }
            val syncTx = characteristics.syncTx
            when (val enabled = io.run { enable(syncTx, updateModeFor(syncTx, ValueUpdate.INDICATION)) }) {
                is GattResult.Failed -> return SessionOutcome.Failed(
                    setupFailure("enable SYNC_TX indications", enabled.failure),
                )

                is GattResult.Ok -> Unit
            }

            when (val written = writeTo(characteristics.syncRx, ClickCodec.handshake())) {
                is GattResult.Failed -> return SessionOutcome.Failed(
                    setupFailure("write the handshake", written.failure),
                )

                is GattResult.Ok -> Unit
            }
            handshakeFailure()?.let { return SessionOutcome.Failed(it) }

            if (options.silentMeansLocked) {
                // Opt-in: no frame within the time counts as locked (a real Click is silent until a press, bench report 1).
                probe.update { if (it == Probe.HANDSHAKING) Probe.WAITING else it }
                sessionScope.launch {
                    delay(options.noEventTimeout)
                    probe.update { if (it == Probe.WAITING) Probe.SILENT else it }
                }
            } else {
                // An answered handshake is a working link: the Click sends nothing until a button is pressed.
                probe.update { if (it == Probe.HANDSHAKING) Probe.ACTIVE else it }
            }

            if (options.sendPostHandshake) {
                writeTo(characteristics.syncRx, ClickCodec.postHandshake()).failureOrNull()?.let {
                    mutableEvents.tryEmit(ClickEvent.OptionalStepFailed("post-handshake write", it))
                }
            }
            if (options.readStandardCharacteristics) readStandard(services)
            options.keepAliveInterval?.let { startKeepAlive(characteristics.syncRx, it) }
            return SessionOutcome.Ready
        }

        override fun linkLost() {
            inbound.trySend(Inbound.Lost(id))
        }

        private suspend fun writeTo(
            characteristic: GattCharacteristicInfo,
            bytes: ByteArray,
        ): GattResult<Unit> =
            io.run {
                if (options.writeWithResponse) {
                    write(
                        characteristic,
                        bytes,
                    )
                } else {
                    writeWithoutResponse(characteristic, bytes)
                }
            }

        /** Null when the handshake was answered the way [ClickOptions.handshakeReply] wants, else the failure. */
        private suspend fun handshakeFailure(): LinkFailure? {
            if (options.handshakeReply == HandshakeReplyCheck.NONE) return null
            val bytes = withTimeoutOrNull(options.handshakeTimeout) { reply.await() }
            if (bytes == null) {
                mutableEvents.tryEmit(ClickEvent.HandshakeTimedOut)
                return LinkFailure(
                    LinkFailureKind.SETUP_FAILED,
                    detail = "No handshake reply within ${options.handshakeTimeout}",
                )
            }
            val parsed = ClickCodec.parseHandshakeReply(bytes)
            val accepted =
                parsed is DecodeResult.Ok &&
                    (
                        options.handshakeReply == HandshakeReplyCheck.ANY_RIDE_ON ||
                            parsed.value.version == null ||
                            parsed.value.version == EXPECTED_VERSION
                    )
            if (accepted && parsed is DecodeResult.Ok) {
                mutableEvents.tryEmit(ClickEvent.HandshakeAnswered(parsed.value.versionText, parsed.value.tailLength))
                return null
            }
            mutableEvents.tryEmit(
                ClickEvent.HandshakeReplyRejected(Hex.encode(bytes.copyOf(minOf(bytes.size, PREFIX_BYTES)))),
            )
            return LinkFailure(
                LinkFailureKind.SETUP_FAILED,
                detail = "The handshake reply was not what the Click answers",
            )
        }

        private suspend fun readStandard(services: GattServices) {
            services.find(DeviceInformationUuids.SERVICE, IdentityUuids.FIRMWARE_REVISION)?.let { info ->
                when (val result = io.run { read(info) }) {
                    is GattResult.Ok -> {
                        mutableFirmware.value = printable(result.value)
                    }

                    is GattResult.Failed -> {
                        mutableEvents.tryEmit(
                            ClickEvent.OptionalStepFailed("read firmware", result.failure),
                        )
                    }
                }
            }
            services.find(BatteryUuids.SERVICE, BatteryUuids.LEVEL)?.let { info ->
                when (val result = io.run { read(info) }) {
                    is GattResult.Ok -> {
                        val percent = result.value.firstOrNull()?.toInt()?.and(BYTE_MASK)
                        if (percent != null && percent in PERCENT_RANGE) mutableBattery.value = percent
                    }

                    is GattResult.Failed -> {
                        mutableEvents.tryEmit(ClickEvent.OptionalStepFailed("read battery", result.failure))
                    }
                }
            }
        }

        private fun startKeepAlive(
            syncRx: GattCharacteristicInfo,
            interval: Duration,
        ) {
            sessionScope.launch {
                while (true) {
                    delay(interval)
                    writeTo(syncRx, options.keepAliveFrameBytes).failureOrNull()?.let {
                        mutableEvents.tryEmit(ClickEvent.KeepAliveFailed(it))
                    }
                }
            }
        }

        /** Runs on the collector coroutine: the first SYNC_TX value is the handshake reply, everything else is a frame. */
        private fun onNotification(notification: GattNotification) {
            if (notification.characteristic == ClickUuids.SYNC_TX && !replyTaken) {
                when (options.handshakeReply) {
                    HandshakeReplyCheck.NONE -> {
                        val parsed = ClickCodec.parseHandshakeReply(notification.value)
                        if (parsed is DecodeResult.Ok) {
                            replyTaken = true
                            mutableEvents.tryEmit(
                                ClickEvent.HandshakeAnswered(parsed.value.versionText, parsed.value.tailLength),
                            )
                            return
                        }
                    }

                    else -> {
                        replyTaken = true
                        reply.complete(notification.value)
                        return
                    }
                }
            }
            inbound.trySend(
                Inbound.Frame(id, notification.characteristic, notification.value, notification.timestampNanos),
            )
        }

        private fun resolve(services: GattServices): Resolved {
            val candidates =
                buildList {
                    add(ClickUuids.SERVICE)
                    if (options.acceptLegacyService) add(ClickUuids.LEGACY_SERVICE)
                }
            val offered = candidates.filter { services.hasService(it) }
            if (offered.isEmpty()) {
                val detail = if (options.serviceHiddenMeansLocked) SERVICE_HIDDEN_DETAIL else NO_SERVICE_DETAIL
                return Resolved.Failed(LinkFailure(LinkFailureKind.SERVICE_MISSING, detail = detail))
            }
            for (service in offered) {
                val async = services.find(service, ClickUuids.ASYNC)
                val syncRx = services.find(service, ClickUuids.SYNC_RX)
                val syncTx = services.find(service, ClickUuids.SYNC_TX)
                if (async != null && syncRx != null && syncTx != null) return Resolved.Found(async, syncRx, syncTx)
            }
            return Resolved.Failed(
                LinkFailure(
                    LinkFailureKind.SERVICE_MISSING,
                    detail = "The Zwift service lacks ASYNC, SYNC_RX or SYNC_TX",
                ),
            )
        }
    }

    private sealed interface Resolved {
        class Found(
            val async: GattCharacteristicInfo,
            val syncRx: GattCharacteristicInfo,
            val syncTx: GattCharacteristicInfo,
        ) : Resolved

        class Failed(
            val failure: LinkFailure,
        ) : Resolved
    }

    private companion object {
        const val BUTTON_BUFFER = 256
        const val EVENT_BUFFER = 64
        const val PREFIX_BYTES = 8
        const val BYTE_MASK = 0xFF
        const val EXPECTED_VERSION = 0x0203
        const val MAX_FIRMWARE_LENGTH = 32
        val PERCENT_RANGE = 0..100

        /** The marker [LinkFailure.detail] carries for a Zwift service that discovery did not list (§6.1). */
        const val SERVICE_HIDDEN_DETAIL = "The Zwift service is hidden: the controller looks locked"

        /** What [LinkFailure.detail] says when the service is missing and that is not taken for a lock. */
        const val NO_SERVICE_DETAIL = "The device has no Zwift Click service"

        fun printable(bytes: ByteArray): String? =
            bytes
                .decodeToString()
                .filter { it in ' '..'~' }
                .trim()
                .take(MAX_FIRMWARE_LENGTH)
                .ifEmpty { null }
    }
}
