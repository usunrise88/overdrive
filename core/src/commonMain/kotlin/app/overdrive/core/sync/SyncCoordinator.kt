package app.overdrive.core.sync

import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.ReconnectBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the sync is doing, for a line in the settings: the outbox's size is always the last count read. */
public sealed interface SyncStatus {
    /** Not started, or stopped. */
    public data object Stopped : SyncStatus

    /** Nothing waits to be uploaded. */
    public data class Idle(
        val pending: Int,
    ) : SyncStatus

    /** An upload is under way; [pending] rides are still queued. */
    public data class Syncing(
        val pending: Int,
    ) : SyncStatus

    /** The last upload did not go through; the next try is in [retryInMillis] unless [SyncCoordinator.kick] cuts it short. */
    public data class WaitingToRetry(
        val pending: Int,
        val retryInMillis: Long,
        val reason: String?,
    ) : SyncStatus

    /** The provider has nowhere to upload (Phase 1's [NoopSyncProvider]): [pending] rides are kept for later. */
    public data class NotConfigured(
        val pending: Int,
    ) : SyncStatus

    /** The outbox could not be read or written (a full disk, a damaged file). The rides are untouched; a kick tries again. */
    public data class OutboxUnavailable(
        val reason: String?,
    ) : SyncStatus
}

/**
 * Drains the [Outbox] through a [SyncProvider], oldest ride first (spec §4.9), and **never blocks or fails a ride**: everything
 * runs on a coroutine of its own, an outbox or provider that throws is a status and a later retry, and the only thing the ride
 * code calls, [rideFinished], returns at once.
 *
 * - An upload the provider [SyncResult.Acknowledged] leaves the outbox. [SyncResult.RetryLater] (or a throw) keeps the ride and
 *   backs off the way the Bluetooth links do ([BackoffPolicy], here [DEFAULT_POLICY]: 5 s, doubling, 15 minutes at most, with
 *   jitter), and the first success starts the schedule over. [SyncResult.NotConfigured] stops without backing off, because
 *   nothing will change by itself: **the no-op provider acknowledges nothing, so every ride stays queued for Phase 2.**
 * - [kick] tries now, for a changed setting or the app starting; it cuts a backoff wait short and starts the schedule over.
 * - The provider is asked for at every round, so a settings change that swaps it needs no new coordinator.
 *
 * There is no network code here and no payload: what an upload is, is Phase 2's.
 */
public class SyncCoordinator(
    private val scope: CoroutineScope,
    private val outbox: Outbox,
    private val provider: () -> SyncProvider = { NoopSyncProvider },
    policy: BackoffPolicy = DEFAULT_POLICY,
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
) {
    private val backoff = ReconnectBackoff(policy, timeSource, random)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Stopped)
    private var job: Job? = null

    /** What the sync is doing. */
    public val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** Starts draining, once now and then whenever kicked. Idempotent. */
    public fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                while (true) {
                    wake.receive()
                    drain()
                }
            }
        kick()
    }

    /** Stops for good. The outbox keeps everything it has. */
    public fun close() {
        job?.cancel()
        job = null
        mutableStatus.value = SyncStatus.Stopped
    }

    /** Try to upload now. Fire and forget; harmless when nothing is queued. */
    public fun kick() {
        wake.trySend(Unit)
    }

    /**
     * A ride is finished and stored: queue it. Returns at once, whatever the outbox does; a failing outbox shows in [status]
     * and the ride is not touched. Queues even before [start].
     */
    public fun rideFinished(entry: OutboxEntry) {
        scope.launch {
            try {
                outbox.enqueue(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableStatus.value = SyncStatus.OutboxUnavailable(e::class.simpleName)
                return@launch
            }
            kick()
        }
    }

    private suspend fun drain() {
        while (true) {
            val pending =
                try {
                    outbox.pending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableStatus.value = SyncStatus.OutboxUnavailable(e::class.simpleName)
                    return
                }
            if (pending.isEmpty()) {
                backoff.reset()
                mutableStatus.value = SyncStatus.Idle(0)
                return
            }
            val target = provider()
            var retry: SyncResult.RetryLater? = null
            for ((index, entry) in pending.withIndex()) {
                mutableStatus.value = SyncStatus.Syncing(pending.size - index)
                when (val result = upload(target, entry)) {
                    SyncResult.Acknowledged -> {
                        try {
                            outbox.markDone(entry.rideId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            mutableStatus.value = SyncStatus.OutboxUnavailable(e::class.simpleName)
                            return
                        }
                        backoff.reset()
                    }

                    SyncResult.NotConfigured -> {
                        mutableStatus.value = SyncStatus.NotConfigured(pending.size - index)
                        return
                    }

                    is SyncResult.RetryLater -> {
                        retry = result
                    }
                }
                if (retry != null) {
                    val remaining = pending.size - index
                    val wait = backoff.onAttemptFailed()
                    mutableStatus.value = SyncStatus.WaitingToRetry(remaining, wait.inWholeMilliseconds, retry.reason)
                    waitForKick(wait.inWholeMilliseconds)
                    break
                }
            }
        }
    }

    private suspend fun upload(
        target: SyncProvider,
        entry: OutboxEntry,
    ): SyncResult =
        try {
            target.upload(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.RetryLater(e::class.simpleName)
        }

    /** Waits [millis], or less if kicked, in which case the schedule starts over. */
    private suspend fun waitForKick(millis: Long) {
        wake.tryReceive()
        val kicked = withTimeoutOrNull(millis) { wake.receive() }
        if (kicked != null) backoff.reset()
    }

    public companion object {
        /** Uploads are rare and a server can be down for an evening: 5 s, doubling, at most 15 minutes, ±20 %. */
        public val DEFAULT_POLICY: BackoffPolicy =
            BackoffPolicy(initialDelay = 5.seconds, factor = 2.0, maxDelay = 15.minutes)
    }
}
