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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What the sync is doing, for a line in the settings: the outbox's size is always the last count read. */
public sealed interface SyncStatus {
    /** Not started, or stopped. */
    public data object Stopped : SyncStatus

    /**
     * Nothing is left to try. [pending] is the outbox's size: 0 when everything was sent, more when the rest were refused
     * (see [SyncReport.refused]).
     */
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

    /** The provider has nowhere to upload (no address or token, or Phase 1's [NoopSyncProvider]): [pending] rides are kept. */
    public data class NotConfigured(
        val pending: Int,
    ) : SyncStatus

    /**
     * The service did not accept the token ([SyncResult.TokenRejected]): [pending] rides are kept and nothing is tried again
     * until the address or the token changes, or the rider asks ([SyncCoordinator.kick] with `retryRefused`).
     */
    public data class TokenRejected(
        val pending: Int,
    ) : SyncStatus

    /** The outbox could not be read or written (a full disk, a damaged file). The rides are untouched; a kick tries again. */
    public data class OutboxUnavailable(
        val reason: String?,
    ) : SyncStatus
}

/** A ride the destination refused for good ([SyncResult.Refused]): its id, and the destination's reason in plain words. */
public data class RefusedRide(
    val rideId: String,
    val message: String,
)

/**
 * What the sync remembers besides what it is doing, for the Settings line: when an upload last succeeded and which rides the
 * destination refused. A refused ride is still in the outbox; it leaves this list when it is uploaded after all.
 */
public data class SyncReport(
    /** Wall-clock milliseconds of the last acknowledged upload, or null if none is known. */
    val lastSuccessMillis: Long? = null,
    val refused: List<RefusedRide> = emptyList(),
)

/**
 * Drains the [Outbox] through a [SyncProvider], oldest ride first (spec §4.9), and **never blocks or fails a ride**: everything
 * runs on a coroutine of its own, an outbox or provider that throws is a status and a later retry, and the only thing the ride
 * code calls, [rideFinished], returns at once.
 *
 * - An upload the provider [SyncResult.Acknowledged] leaves the outbox. [SyncResult.RetryLater] (or a throw) keeps the ride and
 *   backs off the way the Bluetooth links do ([BackoffPolicy], here [DEFAULT_POLICY]: 5 s, doubling, 15 minutes at most, with
 *   jitter), and the first success starts the schedule over. [SyncResult.NotConfigured] stops without backing off, because
 *   nothing will change by itself: **the no-op provider acknowledges nothing, so every ride stays queued.**
 *   [SyncResult.TokenRejected] stops the same way: the credentials are wrong, so nothing is tried until they change.
 * - [SyncResult.Refused] is a ride the destination will not take as it is: it **stays in the outbox but is skipped** (for this
 *   process, and shown in [report]), so it neither loops nor holds up the rides behind it. A new process, a changed setting or
 *   [kick] with `retryRefused` tries it once more.
 * - [kick] tries now, for a changed setting or the app starting; it cuts a backoff wait short and starts the schedule over.
 * - The provider is asked for at every round, so a settings change that swaps it needs no new coordinator.
 *
 * There is no network code here and no payload: what an upload is, is the provider's ([HttpSyncProvider]).
 *
 * @param clock wall-clock time, for [SyncReport.lastSuccessMillis].
 * @param lastSuccessMillis the last success remembered from an earlier run (the app keeps it), or null.
 */
public class SyncCoordinator(
    private val scope: CoroutineScope,
    private val outbox: Outbox,
    private val provider: () -> SyncProvider = { NoopSyncProvider },
    policy: BackoffPolicy = DEFAULT_POLICY,
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
    private val clock: Clock = Clock.System,
    lastSuccessMillis: Long? = null,
) {
    private val backoff = ReconnectBackoff(policy, timeSource, random)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Stopped)
    private val mutableReport = MutableStateFlow(SyncReport(lastSuccessMillis))
    private var job: Job? = null

    /** Rides the provider refused, oldest first. Touched only by the drain coroutine. */
    private val refused = LinkedHashMap<String, String>()

    /** Set by [kick] with `retryRefused`; the drain forgets the refusals the next time it looks. */
    private val forgetRefusals = MutableStateFlow(false)

    /** What the sync is doing. */
    public val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** What the sync remembers: the last success and the refused rides. */
    public val report: StateFlow<SyncReport> = mutableReport.asStateFlow()

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

    /**
     * Try to upload now. Fire and forget; harmless when nothing is queued. With [retryRefused] the rides the provider refused
     * are tried once more as well, and a wait that was under way ends: for a changed setting and the rider's **Send now**. A
     * plain kick (a ride finished, the app started) leaves the refused rides skipped.
     */
    public fun kick(retryRefused: Boolean = false) {
        if (retryRefused) forgetRefusals.value = true
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
            if (forgetRefusals.getAndUpdate { false } && refused.isNotEmpty()) {
                refused.clear()
                publishRefused()
            }
            val pending =
                try {
                    outbox.pending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableStatus.value = SyncStatus.OutboxUnavailable(e::class.simpleName)
                    return
                }
            // A refusal is about a ride that is still queued; one that left the outbox is no longer news.
            val queued = pending.mapTo(HashSet()) { it.rideId }
            if (refused.keys.retainAll(queued)) publishRefused()
            if (pending.isEmpty()) {
                backoff.reset()
                mutableStatus.value = SyncStatus.Idle(0)
                return
            }
            val todo = pending.filter { it.rideId !in refused }
            if (todo.isEmpty()) {
                mutableStatus.value = SyncStatus.Idle(pending.size)
                return
            }
            val target = provider()
            var done = 0
            for (entry in todo) {
                val outstanding = pending.size - done
                mutableStatus.value = SyncStatus.Syncing(outstanding)
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
                        done++
                        backoff.reset()
                        refused.remove(entry.rideId)
                        mutableReport.update {
                            it.copy(lastSuccessMillis = clock.now().toEpochMilliseconds(), refused = refusedRides())
                        }
                    }

                    SyncResult.NotConfigured -> {
                        mutableStatus.value = SyncStatus.NotConfigured(outstanding)
                        return
                    }

                    SyncResult.TokenRejected -> {
                        mutableStatus.value = SyncStatus.TokenRejected(outstanding)
                        return
                    }

                    is SyncResult.Refused -> {
                        refused[entry.rideId] = result.message
                        publishRefused()
                    }

                    is SyncResult.RetryLater -> {
                        val wait = backoff.onAttemptFailed()
                        mutableStatus.value =
                            SyncStatus.WaitingToRetry(outstanding, wait.inWholeMilliseconds, result.reason)
                        waitForKick(wait.inWholeMilliseconds)
                        break
                    }
                }
            }
        }
    }

    private fun refusedRides(): List<RefusedRide> = refused.map { (id, message) -> RefusedRide(id, message) }

    private fun publishRefused() {
        mutableReport.update { it.copy(refused = refusedRides()) }
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

    /**
     * Waits [millis], or less if kicked, in which case the schedule starts over. A rider's kick (`retryRefused`) that came while
     * the upload was under way is not lost: it ends the wait at once.
     */
    private suspend fun waitForKick(millis: Long) {
        wake.tryReceive()
        if (forgetRefusals.value) {
            backoff.reset()
            return
        }
        val kicked = withTimeoutOrNull(millis) { wake.receive() }
        if (kicked != null) backoff.reset()
    }

    public companion object {
        /** Uploads are rare and a server can be down for an evening: 5 s, doubling, at most 15 minutes, ±20 %. */
        public val DEFAULT_POLICY: BackoffPolicy =
            BackoffPolicy(initialDelay = 5.seconds, factor = 2.0, maxDelay = 15.minutes)
    }
}
