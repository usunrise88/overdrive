package app.overdrive.core.ble.link

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How a device's set-up after the link came up ended. */
public sealed interface SessionOutcome {
    /** Services resolved, subscriptions on, the device usable: [ConnectionState.CONNECTED] may be published. */
    public data object Ready : SessionOutcome

    /** Set-up failed; the supervisor closes the transport and backs off. */
    public data class Failed(
        val failure: LinkFailure,
    ) : SessionOutcome
}

/**
 * What a device adapter does on one GATT connection: discovery, MTU, reads, CCCD writes, taking control. The supervisor
 * creates one per connection attempt over a **fresh** transport and drops it when the link ends.
 */
public interface PeripheralSession {
    /**
     * Runs the set-up after the link is up and returns when the device is usable, or says why it is not. Collect
     * [GattTransport.notifications] before enabling anything. The supervisor bounds the whole attempt with a timeout.
     */
    public suspend fun establish(): SessionOutcome

    /** The link is gone (or the supervisor stops): fail pending work and release anything the session holds. */
    public fun linkLost()
}

/**
 * Keeps one device connected for as long as it runs (spec §4.1: "Each device reconnects on its own with backoff. A
 * dropout never stops the ride"), and tells the ride and the Devices screen what is happening.
 *
 * One loop: wait until Bluetooth is usable, open a **new** transport from the [GattTransportFactory] (a transport that
 * failed or dropped, for example with status 133, is closed and never reused), connect, run the device's set-up, and
 * publish [ConnectionState.CONNECTED] and the [session]. When the link ends or an attempt fails the transport is closed
 * and the loop waits as [ReconnectBackoff] says. [retryNow] cuts the wait short.
 *
 * **Bluetooth off or permission revoked** is its own state ([LinkStatus.BluetoothUnavailable]): nothing is tried, no
 * backoff accrues, and when [BleEnvironment.availability] is ready again the next attempt starts at once.
 *
 * Everything the loop does is time-driven by `delay` and [TimeSource], so it runs deterministically under a test
 * scheduler.
 *
 * **Sleepy devices (docs/adr/0040).** With a [ConnectStrategy] that has `background` on, the first attempt is active and
 * limited to a few seconds; when it fails the supervisor opens a **background** transport (`ConnectMode.BACKGROUND`,
 * `autoConnect = true`) that the platform completes the moment the device advertises, and a link that drops goes straight
 * to one. A pending background connect is recycled after the strategy's watchdog; [retryNow] replaces it by an active
 * attempt; Bluetooth going off ends it; [close] (Forget) cancels it. The status while it waits is
 * [LinkStatus.WaitingForDevice], not a failure.
 *
 * @param establishTimeout the longest one attempt may take from `connect` to ready (with a background strategy: from
 * the link up to ready). Android's own connect timeout is about 30 s and ends with status 133; this is the backstop for a
 * stack that never answers.
 * @param strategy read at the start of every attempt, so a setting can be switched while the app runs.
 */
public class LinkSupervisor<S : PeripheralSession>(
    private val scope: CoroutineScope,
    private val device: DeviceId,
    private val transports: GattTransportFactory,
    private val environment: BleEnvironment,
    private val sessions: (GattTransport, CoroutineScope) -> S,
    policy: BackoffPolicy = BackoffPolicy(),
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
    private val establishTimeout: Duration = DEFAULT_ESTABLISH_TIMEOUT,
    private val strategy: () -> ConnectStrategy = { ConnectStrategy.LEGACY },
) : LinkControls {
    private val backoff = ReconnectBackoff(policy, timeSource, random)
    private val mutableConnection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val mutableStatus = MutableStateFlow<LinkStatus>(LinkStatus.Stopped)
    private val mutableSession = MutableStateFlow<S?>(null)
    private val retryRequests = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** The three-state view the ride engine reads. */
    public val connection: StateFlow<ConnectionState> = mutableConnection.asStateFlow()

    override val status: StateFlow<LinkStatus> = mutableStatus.asStateFlow()

    /** The session of the current connection: non-null exactly while [connection] is [ConnectionState.CONNECTED]. */
    public val session: StateFlow<S?> = mutableSession.asStateFlow()

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    override fun retryNow() {
        retryRequests.trySend(Unit)
    }

    override fun close() {
        job?.cancel()
        job = null
        mutableSession.value = null
        mutableConnection.value = ConnectionState.DISCONNECTED
        mutableStatus.value = LinkStatus.Stopped
    }

    private suspend fun run() {
        // Whether the next attempt is a background connect (only with a background strategy): the first one never is.
        var background = false
        while (true) {
            awaitBluetooth()
            retryRequests.tryReceive()
            val chosen = strategy()
            val mode = if (chosen.background && background) ConnectMode.BACKGROUND else ConnectMode.ACTIVE
            publish(
                ConnectionState.CONNECTING,
                if (mode == ConnectMode.BACKGROUND) {
                    LinkStatus.WaitingForDevice
                } else {
                    LinkStatus.Connecting(backoff.consecutiveFailures + 1)
                },
            )
            val ended = attempt(chosen, mode)
            val wait =
                when {
                    // Bluetooth is unusable now, whatever the failure looked like: wait for it in awaitBluetooth, without
                    // backoff. A transport that claims "unavailable" while the environment says ready is an ordinary
                    // failure, or this would spin.
                    environment.availability.value != BleAvailability.READY -> {
                        backoff.reset()
                        background = false
                        Duration.ZERO
                    }

                    // The rider pressed Retry on a pending background connect: an active attempt now.
                    ended.end == End.RETRIED -> {
                        backoff.reset()
                        background = false
                        Duration.ZERO
                    }

                    // The active attempt found nobody, or a background connect went stale: not a failure, no backoff.
                    ended.end == End.HANDOVER || ended.end == End.RECYCLE -> {
                        background = true
                        chosen.settle
                    }

                    ended.wasConnected -> {
                        background = chosen.background
                        backoff.onLinkLost()
                    }

                    else -> {
                        background = chosen.background
                        backoff.onAttemptFailed()
                    }
                }
            if (wait.isPositive()) {
                if (chosen.background && ended.isWaitingForDevice()) {
                    publish(ConnectionState.CONNECTING, LinkStatus.WaitingForDevice)
                } else {
                    publish(
                        ConnectionState.DISCONNECTED,
                        LinkStatus.WaitingToRetry(backoff.consecutiveFailures, wait.inWholeMilliseconds, ended.failure),
                    )
                }
                if (waitForRetry(wait)) background = false
            }
        }
    }

    /** How an attempt ended, beyond its failure. */
    private enum class End {
        /** A failure to count: the usual backoff follows. */
        FAILED,

        /** The active attempt of a background strategy ran out of time: not a failure, the background connect follows. */
        HANDOVER,

        /** A background connect was pending for the watchdog's time: closed, and opened again. */
        RECYCLE,

        /** The rider asked to try again now while a background connect was pending. */
        RETRIED,
    }

    /** What ended one attempt. */
    private class Ended(
        val failure: LinkFailure,
        val wasConnected: Boolean,
        val end: End = End.FAILED,
    ) {
        /** Whether the device simply was not there (asleep, out of range, gone), as opposed to a link that is wrong. */
        fun isWaitingForDevice(): Boolean =
            end == End.HANDOVER ||
                end == End.RECYCLE ||
                wasConnected ||
                failure.kind == LinkFailureKind.CONNECT_FAILED ||
                failure.kind == LinkFailureKind.TIMED_OUT ||
                failure.kind == LinkFailureKind.LINK_LOST
    }

    /** What the connect step of an attempt came to, with a background strategy. */
    private sealed interface ConnectPhase {
        class Done(
            val result: GattResult<Unit>,
        ) : ConnectPhase

        data object Limit : ConnectPhase

        data object Retried : ConnectPhase

        class Unavailable(
            val availability: BleAvailability,
        ) : ConnectPhase
    }

    /** One attempt on a fresh transport, from connect to the end of the link. The transport is always closed. */
    private suspend fun attempt(
        chosen: ConnectStrategy,
        mode: ConnectMode,
    ): Ended {
        val transport = transports.open(device, mode)
        val parent = coroutineContext[Job]
        val sessionScope = CoroutineScope(coroutineContext + Job(parent))
        val session = sessions(transport, sessionScope)
        var connected = false
        var end = End.FAILED
        try {
            suspend fun ready(): LinkFailure {
                connected = true
                backoff.onConnected()
                mutableSession.value = session
                publish(ConnectionState.CONNECTED, LinkStatus.Connected)
                return awaitLinkEnd(transport)
            }

            val failure =
                if (!chosen.background) {
                    when (val outcome = withTimeoutOrNull(establishTimeout) { establish(transport, session) }) {
                        null -> LinkFailure(LinkFailureKind.TIMED_OUT, detail = "No answer within $establishTimeout")
                        is SessionOutcome.Failed -> outcome.failure
                        SessionOutcome.Ready -> ready()
                    }
                } else {
                    when (val phase = awaitConnect(transport, mode, chosen)) {
                        ConnectPhase.Limit -> {
                            end = if (mode == ConnectMode.ACTIVE) End.HANDOVER else End.RECYCLE
                            LinkFailure(LinkFailureKind.TIMED_OUT, detail = "No connection in ${limitOf(mode, chosen)}")
                        }

                        ConnectPhase.Retried -> {
                            end = End.RETRIED
                            LinkFailure(LinkFailureKind.CONNECT_FAILED, detail = "Retry")
                        }

                        is ConnectPhase.Unavailable -> {
                            LinkFailure(LinkFailureKind.UNAVAILABLE, detail = phase.availability.name)
                        }

                        is ConnectPhase.Done -> {
                            val result = phase.result
                            if (result is GattResult.Failed) {
                                connectFailure(result)
                            } else {
                                when (val outcome = withTimeoutOrNull(establishTimeout) { session.establish() }) {
                                    null -> {
                                        LinkFailure(
                                            LinkFailureKind.TIMED_OUT,
                                            detail = "No answer within $establishTimeout",
                                        )
                                    }

                                    is SessionOutcome.Failed -> {
                                        outcome.failure
                                    }

                                    SessionOutcome.Ready -> {
                                        ready()
                                    }
                                }
                            }
                        }
                    }
                }
            return Ended(failure, connected, end)
        } finally {
            withContext(NonCancellable) {
                mutableSession.value = null
                mutableConnection.value = ConnectionState.DISCONNECTED
                session.linkLost()
                sessionScope.cancel()
                transport.close()
            }
        }
    }

    private fun limitOf(
        mode: ConnectMode,
        chosen: ConnectStrategy,
    ): Duration = if (mode == ConnectMode.ACTIVE) chosen.activeAttempt else chosen.backgroundWatchdog

    /**
     * The connect step of a background strategy: ends when the link is up or the connect failed, when the limit of its mode
     * passes (the active attempt's few seconds, or the background watchdog), when Bluetooth becomes unusable, or, for a
     * pending background connect, when the rider presses Retry.
     */
    private suspend fun awaitConnect(
        transport: GattTransport,
        mode: ConnectMode,
        chosen: ConnectStrategy,
    ): ConnectPhase =
        withTimeoutOrNull(limitOf(mode, chosen)) {
            coroutineScope {
                val connect = async { transport.connect() }
                val radio =
                    async<ConnectPhase> {
                        ConnectPhase.Unavailable(environment.availability.first { it != BleAvailability.READY })
                    }
                val phase =
                    select<ConnectPhase> {
                        connect.onAwait { ConnectPhase.Done(it) }
                        radio.onAwait { it }
                        if (mode == ConnectMode.BACKGROUND) retryRequests.onReceive { ConnectPhase.Retried }
                    }
                connect.cancel()
                radio.cancel()
                phase
            }
        } ?: ConnectPhase.Limit

    private fun connectFailure(connect: GattResult.Failed): LinkFailure {
        val kind =
            if (connect.failure.kind == GattFailureKind.UNAVAILABLE) {
                LinkFailureKind.UNAVAILABLE
            } else {
                LinkFailureKind.CONNECT_FAILED
            }
        return LinkFailure(kind, connect.failure.status.takeIf { it >= 0 })
    }

    private suspend fun establish(
        transport: GattTransport,
        session: S,
    ): SessionOutcome =
        when (val connect = transport.connect()) {
            is GattResult.Failed -> SessionOutcome.Failed(connectFailure(connect))
            is GattResult.Ok -> session.establish()
        }

    /** Returns why a connected link ended: the transport reports a drop, or Bluetooth became unusable. */
    private suspend fun awaitLinkEnd(transport: GattTransport): LinkFailure {
        val ended = CompletableDeferred<LinkFailure>()
        return coroutineScope {
            val watchers =
                listOf(
                    launch {
                        val state =
                            transport.state.first {
                                it is GattLinkState.Disconnected
                            } as GattLinkState.Disconnected
                        ended.complete(LinkFailure(LinkFailureKind.LINK_LOST, state.status))
                    },
                    launch {
                        val availability = environment.availability.first { it != BleAvailability.READY }
                        ended.complete(LinkFailure(LinkFailureKind.UNAVAILABLE, detail = availability.name))
                    },
                )
            ended.await().also { watchers.forEach { it.cancel() } }
        }
    }

    /** Blocks until Bluetooth is usable, publishing why it is not; no backoff accrues meanwhile. */
    private suspend fun awaitBluetooth() {
        while (true) {
            val availability = environment.availability.value
            if (availability == BleAvailability.READY) return
            publish(ConnectionState.DISCONNECTED, LinkStatus.BluetoothUnavailable(availability))
            environment.availability.first { it != availability }
        }
    }

    /** Waits [delay], or less if [retryNow] is called, in which case the backoff starts over. True when it was called. */
    private suspend fun waitForRetry(delay: Duration): Boolean {
        retryRequests.tryReceive()
        val pressed = withTimeoutOrNull(delay) { retryRequests.receive() }
        if (pressed != null) backoff.reset()
        return pressed != null
    }

    private fun publish(
        connection: ConnectionState,
        status: LinkStatus,
    ) {
        mutableConnection.value = connection
        mutableStatus.value = status
    }

    public companion object {
        /** Longest time from `connect` to ready: generous next to Android's own ~30 s connect timeout (unverified). */
        public val DEFAULT_ESTABLISH_TIMEOUT: Duration = 45.seconds
    }
}
