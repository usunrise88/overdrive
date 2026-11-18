package app.overdrive.core.ble.link

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * @param establishTimeout the longest one attempt may take from `connect` to ready. Android's own connect timeout is
 * about 30 s and ends with status 133; this is the backstop for a stack that never answers.
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
        while (true) {
            awaitBluetooth()
            retryRequests.tryReceive()
            publish(ConnectionState.CONNECTING, LinkStatus.Connecting(backoff.consecutiveFailures + 1))
            val ended = attempt()
            val wait =
                when {
                    // Bluetooth is unusable now, whatever the failure looked like: wait for it in awaitBluetooth, without
                    // backoff. A transport that claims "unavailable" while the environment says ready is an ordinary
                    // failure, or this would spin.
                    environment.availability.value != BleAvailability.READY -> {
                        backoff.reset()
                        Duration.ZERO
                    }

                    ended.wasConnected -> {
                        backoff.onLinkLost()
                    }

                    else -> {
                        backoff.onAttemptFailed()
                    }
                }
            if (wait.isPositive()) {
                publish(
                    ConnectionState.DISCONNECTED,
                    LinkStatus.WaitingToRetry(backoff.consecutiveFailures, wait.inWholeMilliseconds, ended.failure),
                )
                waitForRetry(wait)
            }
        }
    }

    /** What ended one attempt. */
    private class Ended(
        val failure: LinkFailure,
        val wasConnected: Boolean,
    )

    /** One attempt on a fresh transport, from connect to the end of the link. The transport is always closed. */
    private suspend fun attempt(): Ended {
        val transport = transports.open(device)
        val parent = coroutineContext[Job]
        val sessionScope = CoroutineScope(coroutineContext + Job(parent))
        val session = sessions(transport, sessionScope)
        var connected = false
        try {
            val failure =
                when (val outcome = withTimeoutOrNull(establishTimeout) { establish(transport, session) }) {
                    null -> {
                        LinkFailure(LinkFailureKind.TIMED_OUT, detail = "No answer within $establishTimeout")
                    }

                    is SessionOutcome.Failed -> {
                        outcome.failure
                    }

                    SessionOutcome.Ready -> {
                        connected = true
                        backoff.onConnected()
                        mutableSession.value = session
                        publish(ConnectionState.CONNECTED, LinkStatus.Connected)
                        awaitLinkEnd(transport)
                    }
                }
            return Ended(failure, connected)
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

    private suspend fun establish(
        transport: GattTransport,
        session: S,
    ): SessionOutcome =
        when (val connect = transport.connect()) {
            is GattResult.Failed -> {
                val kind =
                    if (connect.failure.kind == GattFailureKind.UNAVAILABLE) {
                        LinkFailureKind.UNAVAILABLE
                    } else {
                        LinkFailureKind.CONNECT_FAILED
                    }
                SessionOutcome.Failed(LinkFailure(kind, connect.failure.status.takeIf { it >= 0 }))
            }

            is GattResult.Ok -> {
                session.establish()
            }
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

    /** Waits [delay], or less if [retryNow] is called, in which case the backoff starts over. */
    private suspend fun waitForRetry(delay: Duration) {
        retryRequests.tryReceive()
        val pressed = withTimeoutOrNull(delay) { retryRequests.receive() }
        if (pressed != null) backoff.reset()
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
