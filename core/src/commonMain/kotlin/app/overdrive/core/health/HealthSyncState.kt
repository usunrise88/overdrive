package app.overdrive.core.health

import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the reader keeps between runs, in one small file (ADR 0027). **No measured value is in it**: tokens, instants, counts and the
 * kind of the last trouble. Written after the batches it describes are on disk, so a crash at any instant repeats work (the service is
 * idempotent) and never skips any.
 */
@Serializable
public data class HealthSyncState(
    val version: Int = 1,
    /** The Health Connect changes token: everything since it is still to be read. Null until the first read has finished. */
    val changesToken: String? = null,
    /** The [HealthDataType] names the token was made for. */
    val tokenTypes: List<String> = emptyList(),
    /** A read of a window of days under way (the first read, a new type, an expired token), resumable chunk by chunk. */
    val backfill: Backfill? = null,
    val firstReadDone: Boolean = false,
    val lastReadMillis: Long? = null,
    val lastSentMillis: Long? = null,
    val lastSent: SentCounts? = null,
    /** Failed uploads in a row, for the backoff. */
    val failures: Int = 0,
    val nextAttemptMillis: Long? = null,
    val uploadProblem: StoredProblem? = null,
    val readIssue: ReadIssue? = null,
) {
    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The state in [text], or a fresh one for anything that is not a state this version wrote (a read of the window follows). */
        public fun decode(text: String?): HealthSyncState =
            try {
                text?.let { json.decodeFromString(serializer(), it) }?.takeIf { it.version == 1 } ?: HealthSyncState()
            } catch (e: IllegalArgumentException) {
                HealthSyncState()
            }

        public fun encode(state: HealthSyncState): String = json.encodeToString(serializer(), state)
    }
}

/**
 * A read of a window of days, newest chunk first (so the most recent figures reach the service first), whose progress is kept
 * after each chunk.
 *
 * @property token the changes token made **before** the first chunk was read, so nothing between is missed.
 * @property tokenTypes the types the token covers (all that are allowed); [readTypes] the ones this window reads.
 */
@Serializable
public data class Backfill(
    val token: String,
    val tokenTypes: List<String>,
    val readTypes: List<String>,
    val anchorMillis: Long,
    val fromMillis: Long,
    val chunkMillis: Long,
    val chunksDone: Int,
    val chunksTotal: Int,
    val recordsRead: Int,
    val first: Boolean,
)

/** What the last upload that sent anything came to: [total] records sent and the service's own counts. */
@Serializable
public data class SentCounts(
    val total: Int,
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    /** Records sent by kind (`sleepSession`, `restingHeartRate`, ...). */
    val byType: Map<String, Int>,
)

/** A [ServiceProblem] as it is kept: its kind and plain words, no address and no token (a problem holds neither). */
@Serializable
public data class StoredProblem(
    val kind: String,
    val plain: String,
    val hint: String? = null,
    val status: Int? = null,
) {
    public fun toProblem(): ServiceProblem =
        ServiceProblem(
            ProblemKind.entries.firstOrNull { it.name == kind } ?: ProblemKind.NO_ANSWER,
            plain,
            hint,
            status,
        )

    public companion object {
        public fun of(problem: ServiceProblem): StoredProblem =
            StoredProblem(problem.kind.name, problem.plain, problem.hint, problem.status)
    }
}

/** Why the last read of the watch data did not happen or did not finish. */
@Serializable
public enum class ReadIssue {
    /** An allowed permission was taken away in Health Connect. */
    PERMISSION_REVOKED,

    /** Health Connect did not answer (updating, busy). */
    UNAVAILABLE,

    /** The daily read ran without the permission to read in the background. */
    BACKGROUND_NOT_ALLOWED,

    /** Anything else, with no detail: a read's failure must not name a value. */
    READ_FAILED,
}

/** How a run was asked for. */
public enum class RunReason(
    /** The run is the daily one, which Health Connect lets read only with the background permission. */
    public val background: Boolean,
    /** The rider asked: try the service even inside the backoff. */
    public val ignoresBackoff: Boolean,
) {
    APP_OPENED(false, false),
    DAILY(true, false),
    FIRST_READ(false, true),
    MANUAL(false, true),
    RETRY(false, false),
}

/** How long to wait after the n-th upload failure in a row: [baseMillis], doubled each time, never more than [maxMillis]. */
public class RetryPolicy(
    private val baseMillis: Long = 30_000,
    private val maxMillis: Long = 30 * 60_000L,
) {
    public fun delayAfter(failures: Int): Long {
        var delay = baseMillis
        repeat((failures - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(maxMillis)
        }
        return delay.coerceAtMost(maxMillis)
    }
}
