package app.overdrive.core.outdoor

import app.overdrive.core.health.RetryPolicy
import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.sync.ApiErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What came of one file the rider shared (design/companion.md §4.11): the screen says it in words. */
public sealed interface FitOutcome {
    /** The service stored the ride. */
    public data class Imported(
        val id: String,
        val summary: ActivityImportSummary,
    ) : FitOutcome

    /** The service had this ride already ([reason] says how it knew); nothing was added. */
    public data class Duplicate(
        val id: String,
        val reason: ActivityImportReason,
        val summary: ActivityImportSummary,
    ) : FitOutcome

    /** The file cannot be imported, for [refusal]: not kept when the phone saw it, set aside when the service said it ([status]). */
    public data class Refused(
        val refusal: FitRefusal,
        val status: Int? = null,
    ) : FitOutcome

    /**
     * The file is **kept** on the phone and goes when the service can be reached: [problem] says why it did not go now, or is null when
     * the app has no service address and token yet.
     */
    public data class Waiting(
        val problem: ServiceProblem?,
    ) : FitOutcome

    /** The phone could not keep the file (its storage refused): nothing was sent and nothing is kept. */
    public data object NotKept : FitOutcome
}

/** What Settings and the retry timer know of the outbox of FIT files. */
public data class FitStatus(
    /** The outbox has been read from disk. */
    val loaded: Boolean = false,
    /** Files kept for the service. */
    val waiting: Int = 0,
    /** Files the service refused for good, set aside: why each. */
    val parked: List<FitRefusal> = emptyList(),
    val running: Boolean = false,
    /** Why the files that wait have not gone yet, or null. */
    val problem: ServiceProblem? = null,
    /** When the next attempt is due after a failure (the phone's wall clock), or null. */
    val nextRetryMillis: Long? = null,
    /** Files the service has accepted since the app started: a counter, so a screen that lists rides knows to ask again. */
    val accepted: Int = 0,
)

/** Why a run of the outbox was started: what it may ignore. */
public enum class FitFlushReason(
    /** Try the service even inside the backoff. */
    public val ignoresBackoff: Boolean,
    /** A token the service refused is tried once more (the rider asked). */
    public val retriesRefusedToken: Boolean,
) {
    APP_OPENED(false, false),
    RETRY(false, false),

    /** The system's job, run when the network came back: its own backoff is the system's. */
    BACKGROUND(true, false),

    /** **Sync now**: the rider asked. */
    MANUAL(true, true),
}

/** What a run came to, for the background job: [failed] is worth trying again later. */
public class FitFlush(
    public val sent: Int,
    public val failed: Boolean,
    public val alreadyRunning: Boolean = false,
)

/**
 * The outdoor ride's way to the service (spec §11.8 path A, ADR 0028): a FIT file the rider shares is checked for what is certainly
 * not a FIT file, **kept** in a [FitOutbox] (on disk before anything is sent), sent to `POST /v1/activities/fit` through the shared
 * [ServiceClient], and removed **only when the service answered 2xx and the answer is the contract's** (`activity-import-response`).
 *
 * - **Offline-safe.** A file that cannot go now (no network, the service down, a 5xx, a refused token, a service that does not know the
 *   route yet) stays, and goes when [flush] runs: on app open, on **Sync now**, by the system's job when the network is back, and after a
 *   backoff ([RetryPolicy], 30 s doubling to 30 minutes, kept in memory: a restart tries at once).
 * - **A refusal is parked, not retried.** A 4xx other than 401, 408, 429 and a missing route says the file is wrong, and will say it
 *   again: the file is set aside with the reason, counted, and never in the way of the ones behind it; the last [MAX_PARKED] are kept.
 * - **Idempotent.** The service answers `duplicate` for the same bytes, so a file whose answer was lost is sent again and simply
 *   reported as already imported; a file already waiting is not queued twice.
 * - **Bounded.** At most [maxWaiting] files wait; the next is refused with [FitRefusal.QUEUE_FULL] instead of filling the phone.
 * - **Private.** Nothing here logs, no state holds a figure of a ride, and no file name is kept: the status holds counts and the kind of
 *   trouble; the summary of an answer goes to the screen that asked and nowhere else.
 */
public class FitImporter(
    private val client: ServiceClient,
    private val outbox: FitOutbox,
    private val wallMillis: () -> Long,
    private val retry: RetryPolicy = RetryPolicy(),
    private val maxWaiting: Int = MAX_WAITING,
) {
    private val runLock = Mutex()

    /** Guards the queue's bookkeeping: adding a file and claiming one to send. */
    private val bookkeeping = Mutex()
    private val claimed = HashSet<Long>()
    private val mutable = MutableStateFlow(FitStatus())

    /** What the screens read. */
    public val status: StateFlow<FitStatus> = mutable.asStateFlow()

    @Volatile
    private var failures = 0

    @Volatile
    private var nextAttemptMillis: Long? = null

    @Volatile
    private var lastProblem: ServiceProblem? = null

    @Volatile
    private var accepted = 0

    /** Reads what the outbox holds, so Settings shows what waits before anything runs. */
    public suspend fun load() {
        publish(loaded = true)
    }

    /**
     * The rider shared [bytes]: checks them, keeps them, sends them now (the rider is looking at the screen: the backoff does not
     * apply) and says what came of it. Never throws for a failed request.
     */
    public suspend fun import(bytes: ByteArray): FitOutcome {
        FitFileCheck.refusalOf(bytes)?.let { return FitOutcome.Refused(it) }
        val checksum = FitFileCheck.checksumOf(bytes)
        val info =
            when (val kept = keep(bytes, checksum)) {
                is Kept.File -> kept.info
                is Kept.Refused -> return FitOutcome.Refused(kept.refusal)
                Kept.Busy -> return FitOutcome.Waiting(lastProblem)
                Kept.Failed -> return FitOutcome.NotKept
            }
        publish()
        try {
            return sendOne(info, bytes)
        } finally {
            release(info.seq)
            publish()
        }
    }

    private sealed interface Kept {
        class File(
            val info: FitFileInfo,
        ) : Kept

        class Refused(
            val refusal: FitRefusal,
        ) : Kept

        /** The same file is waiting and a run of [flush] is sending it this moment: that run's answer is the file's. */
        data object Busy : Kept

        data object Failed : Kept
    }

    /** Puts [bytes] in the outbox (or finds the same file already waiting) and claims it, so a run of [flush] leaves it to the caller. */
    private suspend fun keep(
        bytes: ByteArray,
        checksum: String,
    ): Kept =
        bookkeeping.withLock {
            val waiting = outbox.pending()
            val same = waiting.firstOrNull { it.bytes == bytes.size && it.checksum == checksum }
            if (same != null) {
                // The same file is already waiting: send that one, not a second copy. If a run holds it, it is the run's.
                return if (claimed.add(same.seq)) Kept.File(same) else Kept.Busy
            }
            if (waiting.size >= maxWaiting) return Kept.Refused(FitRefusal.QUEUE_FULL)
            val added =
                try {
                    // A write to disk is not cut short: the file is there or it is not.
                    withContext(NonCancellable) { outbox.add(bytes) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Kept.Failed
                }
            claimed += added.seq
            Kept.File(added)
        }

    private suspend fun release(seq: Long) {
        bookkeeping.withLock { claimed -= seq }
    }

    /**
     * Sends what waits, oldest first, and stops at the first failure with the rest waiting; a file the service refuses for good is
     * parked and the ones behind it go on. One run at a time (a second returns [FitFlush.alreadyRunning]); a file the rider is sending
     * from the share screen is left to that call.
     */
    public suspend fun flush(reason: FitFlushReason): FitFlush {
        if (!runLock.tryLock()) return FitFlush(0, failed = false, alreadyRunning = true)
        try {
            if (reason.retriesRefusedToken) client.forgetRejection()
            var sent = 0
            var failed = false
            publish(running = true)
            for (info in bookkeeping.withLock { outbox.pending() }) {
                val due = nextAttemptMillis
                if (!reason.ignoresBackoff && due != null && due > wallMillis()) {
                    failed = true
                    break
                }
                if (!bookkeeping.withLock { claimed.add(info.seq) }) continue
                val outcome =
                    try {
                        sendOne(info, null)
                    } finally {
                        release(info.seq)
                    }
                when (outcome) {
                    is FitOutcome.Imported, is FitOutcome.Duplicate -> {
                        sent++
                    }

                    is FitOutcome.Waiting -> {
                        // No service yet is not a failure to try again; anything else is.
                        failed = outcome.problem != null
                        break
                    }

                    is FitOutcome.Refused, FitOutcome.NotKept -> {
                        Unit
                    }
                }
            }
            return FitFlush(sent, failed)
        } finally {
            publish(running = false)
            runLock.unlock()
        }
    }

    /** One file to the service, and what came of it for the queue: removed, parked or kept. */
    private suspend fun sendOne(
        info: FitFileInfo,
        known: ByteArray?,
    ): FitOutcome {
        val bytes = known ?: outbox.bytes(info.seq)
        if (bytes == null) {
            // Damaged on disk (its checksum failed) or gone: it cannot be sent, and sending it again changes nothing.
            parkFor(info.seq, FitRefusal.DAMAGED)
            return FitOutcome.Refused(FitRefusal.DAMAGED)
        }
        return when (val outcome = client.postBytes(PATH, FIT_CONTENT_TYPE, bytes, MAX_ANSWER_BYTES)) {
            Outcome.NotSetUp -> {
                FitOutcome.Waiting(null)
            }

            is Outcome.Ok -> {
                val answer =
                    try {
                        ActivityImportJson.decode(outcome.body)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                if (answer == null) {
                    // A 2xx with something else in it (a portal, a proxy) is not an acceptance: the file stays.
                    noteFailure(ServiceProblem.NotTheService)
                    FitOutcome.Waiting(ServiceProblem.NotTheService)
                } else {
                    withContext(NonCancellable) { outbox.remove(info.seq) }
                    noteSuccess()
                    when (answer.status) {
                        ActivityImportStatus.IMPORTED -> {
                            FitOutcome.Imported(answer.id, answer.summary)
                        }

                        ActivityImportStatus.DUPLICATE -> {
                            FitOutcome.Duplicate(
                                answer.id,
                                checkNotNull(answer.reason),
                                answer.summary,
                            )
                        }
                    }
                }
            }

            is Outcome.Failed -> {
                val refusal = refusalOf(outcome.problem, outcome.code)
                if (refusal != null) {
                    parkFor(info.seq, refusal)
                    FitOutcome.Refused(refusal, outcome.problem.status)
                } else {
                    noteFailure(outcome.problem)
                    FitOutcome.Waiting(outcome.problem)
                }
            }
        }
    }

    /**
     * Whether the service looked at the file and said no for good: a 4xx other than a refused token (401), the transient 408 and 429 and
     * a missing route (a service that does not have this yet, which an update fixes: the file waits).
     */
    private fun refusalOf(
        problem: ServiceProblem,
        code: ApiErrorCode?,
    ): FitRefusal? {
        if (problem.kind != ProblemKind.REQUEST_REFUSED) return null
        return when (code) {
            ApiErrorCode.INVALID_FIT -> FitRefusal.NOT_A_FIT_FILE
            ApiErrorCode.UNSUPPORTED_SPORT -> FitRefusal.NOT_CYCLING
            ApiErrorCode.EMPTY_ACTIVITY -> FitRefusal.NO_RIDE
            ApiErrorCode.PAYLOAD_TOO_LARGE -> FitRefusal.TOO_LARGE
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE -> FitRefusal.WRONG_TYPE
            else -> FitRefusal.SERVICE_REFUSED
        }
    }

    private suspend fun parkFor(
        seq: Long,
        refusal: FitRefusal,
    ) {
        withContext(NonCancellable) {
            outbox.park(seq, refusal)
            // The rider still has the original in OnelapFit: only the last few are kept.
            val aside = outbox.parked()
            for (old in aside.dropLast(MAX_PARKED)) outbox.discard(old.seq)
        }
    }

    private fun noteSuccess() {
        accepted++
        failures = 0
        nextAttemptMillis = null
        lastProblem = null
    }

    private fun noteFailure(problem: ServiceProblem) {
        failures++
        lastProblem = problem
        nextAttemptMillis = wallMillis() + retry.delayAfter(failures)
    }

    private suspend fun publish(
        loaded: Boolean = true,
        running: Boolean? = null,
    ) {
        val waiting = outbox.pending()
        val parked = outbox.parked()
        // Nothing waits: the reason a file did not go is over.
        if (waiting.isEmpty()) {
            failures = 0
            nextAttemptMillis = null
            lastProblem = null
        }
        mutable.update {
            it.copy(
                loaded = loaded,
                waiting = waiting.size,
                parked = parked.mapNotNull { info -> info.refusal },
                running = running ?: it.running,
                problem = lastProblem,
                nextRetryMillis = nextAttemptMillis,
                accepted = accepted,
            )
        }
    }

    public companion object {
        /** The files the outbox keeps for a service that cannot be reached; the next is refused (a ride is a few hundred kilobytes). */
        public const val MAX_WAITING: Int = 20

        /** The files the service refused that stay on disk, newest. */
        public const val MAX_PARKED: Int = 5
        private const val PATH = "/v1/activities/fit"
        private const val MAX_ANSWER_BYTES = 64 * 1024
    }
}
