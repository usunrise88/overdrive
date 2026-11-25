package app.overdrive.core.health

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.remote.TextStore
import app.overdrive.core.wellness.WellnessApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** How far the read of the last 60 days has come: [chunksDone] of [chunksTotal] windows, [records] read, [estimate] for all of them. */
public data class ReadProgress(
    val chunksDone: Int,
    val chunksTotal: Int,
    val records: Int,
    val estimate: Int,
)

/** What the screens know of the watch data (design/companion.md §4.9, §4.10). */
public data class HealthStatus(
    /** Availability and access have been asked at least once. */
    val checked: Boolean = false,
    val availability: HealthAvailability = HealthAvailability.AVAILABLE,
    val access: HealthAccess = HealthAccess(emptySet(), history = false, background = false),
    val running: Boolean = false,
    val firstReadDone: Boolean = false,
    /** The first read (or another window) is under way or half done. */
    val progress: ReadProgress? = null,
    val waitingRecords: Int = 0,
    val waitingBatches: Int = 0,
    val parkedBatches: Int = 0,
    val lastReadMillis: Long? = null,
    val lastSentMillis: Long? = null,
    val lastSent: SentCounts? = null,
    val uploadProblem: ServiceProblem? = null,
    val readIssue: ReadIssue? = null,
    val nextRetryMillis: Long? = null,
)

public enum class RunOutcome {
    DONE,
    NOTHING_ALLOWED,
    UNAVAILABLE,
    BACKGROUND_NOT_ALLOWED,
    PERMISSION_REVOKED,
    READ_FAILED,
    ALREADY_RUNNING,
}

/** What a run came to, for the background job: [uploadFailed] and [READ_FAILED][RunOutcome.READ_FAILED] are worth trying again. */
public class RunResult(
    public val outcome: RunOutcome,
    public val recordsQueued: Int,
    public val recordsSent: Int,
    public val waitingRecords: Int,
    public val uploadFailed: Boolean,
) {
    public val shouldRetry: Boolean get() = uploadFailed || outcome == RunOutcome.READ_FAILED
}

/**
 * The watch data's way to the service (spec §6.2, ADR 0027): reads Health Connect through a [HealthSource], queues what it read in a
 * [BatchStore] and sends the queue to `POST /v1/wellness` through the shared [ServiceClient].
 *
 * - **First read.** The last [historyDays] (60) days, newest window first, [chunkDays] (5) at a time, every type in turn and every
 *   type in pages. The changes token is made **before** the first window is read, so what changes while it runs is not missed. The
 *   progress is written after every window, so a killed process (or a stopped job) resumes with the next one.
 * - **After that**, the Changes API: what is new or edited since the token; the token is kept after the batches are on disk. A token
 *   that has expired (Health Connect keeps one for 30 days), or a permission that was added or taken away, is a new token and a window
 *   read for what is not covered.
 * - **Upload.** Batches go in order. One is removed only when the service answered 2xx **and the answer is the contract's**; a
 *   failure stops the run with the rest waiting, and the next try is after a backoff ([RetryPolicy]) unless the rider asked; a batch the
 *   service refuses for good (400, 413, 422) is parked, not lost, and the ones behind it go on.
 * - **Idempotent.** Every record carries Health Connect's id and `lastModified`, so reading a window twice, or sending a batch whose
 *   answer was lost, changes nothing at the service.
 * - **Single flight.** One run at a time; a second returns [RunOutcome.ALREADY_RUNNING].
 * - **Private.** Nothing here logs, and nothing it keeps or reports holds a measured value.
 */
public class HealthSync(
    private val source: HealthSource,
    private val client: ServiceClient,
    private val stateStore: TextStore?,
    private val batches: BatchStore,
    private val wallMillis: () -> Long,
    private val device: String? = null,
    private val retry: RetryPolicy = RetryPolicy(),
    private val historyDays: Int = HISTORY_DAYS,
    private val chunkDays: Int = CHUNK_DAYS,
    private val maxRecords: Int = WellnessBatcher.MAX_RECORDS,
    private val maxBytes: Int = WellnessBatcher.MAX_BYTES,
    private val pageGuard: Int = PAGE_GUARD,
) {
    private val lock = Mutex()
    private val loading = Mutex()
    private val mutable = MutableStateFlow(HealthStatus())

    /** What the screens read. */
    public val status: StateFlow<HealthStatus> = mutable.asStateFlow()

    // Written by a run (holding [lock]) and by the first load (holding [loading]); read by the status.
    @Volatile
    private var state = HealthSyncState()

    @Volatile
    private var loaded = false
    private var queued = 0

    /** Reads what was kept on disk, so a screen shows what waits before the first run. */
    public suspend fun load() {
        // Not under the run's lock: a run that begins while the files are being read must not be turned away as "already running".
        ensureLoaded()
        publish()
    }

    /** Asks Health Connect what is available and allowed now, for Settings and the first run's steps; reads nothing. */
    public suspend fun refreshAccess() {
        val availability = source.availability()
        val access =
            if (availability == HealthAvailability.AVAILABLE) {
                try {
                    source.access()
                } catch (e: HealthPermissionException) {
                    HealthAccess(emptySet(), history = false, background = false)
                } catch (e: HealthUnavailableException) {
                    HealthAccess(emptySet(), history = false, background = false)
                }
            } else {
                HealthAccess(emptySet(), history = false, background = false)
            }
        mutable.update { it.copy(checked = true, availability = availability, access = access) }
    }

    /** Reads what is new, queues it and sends the queue. See the class. */
    public suspend fun run(reason: RunReason): RunResult {
        if (!lock.tryLock()) return RunResult(RunOutcome.ALREADY_RUNNING, 0, 0, mutable.value.waitingRecords, false)
        try {
            return runLocked(reason)
        } finally {
            mutable.update { it.copy(running = false) }
            lock.unlock()
        }
    }

    private suspend fun runLocked(reason: RunReason): RunResult {
        ensureLoaded()
        queued = 0
        tally = Tally()
        // The rider asked: a token the service refused is tried once more.
        if (reason.ignoresBackoff) client.forgetRejection()
        mutable.update { it.copy(running = true) }
        val availability = source.availability()
        var access = HealthAccess(emptySet(), history = false, background = false)
        var outcome = RunOutcome.DONE
        // What is allowed is published the moment it is known, not at the end: a refresh the rider triggers while the read goes on
        // must not be overwritten by what this run saw when it began.
        if (availability != HealthAvailability.AVAILABLE) {
            outcome = RunOutcome.UNAVAILABLE
        } else {
            var denied = false
            try {
                access = source.access()
            } catch (e: HealthPermissionException) {
                denied = true
            }
            mutable.update { it.copy(checked = true, availability = availability, access = access) }
            when {
                denied -> {
                    outcome = RunOutcome.PERMISSION_REVOKED
                }

                access.granted.isEmpty() -> {
                    outcome = RunOutcome.NOTHING_ALLOWED
                }

                reason.background && !access.backgroundOk -> {
                    outcome = RunOutcome.BACKGROUND_NOT_ALLOWED
                    state = state.copy(readIssue = ReadIssue.BACKGROUND_NOT_ALLOWED)
                }

                else -> {
                    outcome = readNew(access)
                }
            }
        }
        if (outcome == RunOutcome.PERMISSION_REVOKED) state = state.copy(readIssue = ReadIssue.PERMISSION_REVOKED)
        if (outcome == RunOutcome.UNAVAILABLE && availability == HealthAvailability.AVAILABLE) {
            state = state.copy(readIssue = ReadIssue.UNAVAILABLE)
        }
        if (outcome == RunOutcome.DONE) state = state.copy(readIssue = null)
        save()
        if (availability != HealthAvailability.AVAILABLE) {
            mutable.update { it.copy(checked = true, availability = availability, access = access) }
        }
        val sent = flush(reason.ignoresBackoff)
        publish()
        return RunResult(outcome, queued, sent.records, mutable.value.waitingRecords, sent.failed)
    }

    // ---- Reading -------------------------------------------------------------------------------------------

    private suspend fun readNew(access: HealthAccess): RunOutcome {
        val granted = access.granted
        try {
            val resumed = state.backfill
            when {
                resumed != null -> {
                    backfill(resumed, granted)
                    incremental(granted)
                }

                state.changesToken == null || !state.firstReadDone -> {
                    startBackfill(granted, granted, wallMillis() - days(historyDays), first = true)
                    incremental(granted)
                }

                else -> {
                    val covered =
                        state.tokenTypes
                            .mapNotNull { name ->
                                HealthDataType.entries.firstOrNull {
                                    it.name ==
                                        name
                                }
                            }.toSet()
                    val added = granted - covered
                    when {
                        added.isNotEmpty() -> {
                            startBackfill(granted, added, wallMillis() - days(historyDays), first = false)
                        }

                        covered != granted -> {
                            // A permission was taken away: the token still names it, so make a new one and read what the old one
                            // could have missed since the last read.
                            val from = (state.lastReadMillis ?: wallMillis()) - days(OVERLAP_DAYS)
                            startBackfill(
                                granted,
                                granted,
                                maxOf(from, wallMillis() - days(historyDays)),
                                first = false,
                            )
                        }
                    }
                    incremental(granted)
                }
            }
            state = state.copy(lastReadMillis = wallMillis(), readIssue = null)
            return RunOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthPermissionException) {
            return RunOutcome.PERMISSION_REVOKED
        } catch (e: HealthUnavailableException) {
            return RunOutcome.UNAVAILABLE
        } catch (e: Exception) {
            // The message of an exception from a read could carry a value: it is dropped, only the fact is kept.
            state = state.copy(readIssue = ReadIssue.READ_FAILED)
            return RunOutcome.READ_FAILED
        }
    }

    private fun days(count: Int): Long = count * MILLIS_PER_DAY

    private suspend fun startBackfill(
        tokenTypes: Set<HealthDataType>,
        readTypes: Set<HealthDataType>,
        fromMillis: Long,
        first: Boolean,
    ) {
        // The token first: what changes while the window is read is then still to be read next time.
        val token = source.newChangesToken(tokenTypes)
        val anchor = wallMillis()
        val chunk = days(chunkDays)
        val total = ((anchor - fromMillis + chunk - 1) / chunk).toInt().coerceAtLeast(1)
        val next =
            Backfill(
                token = token,
                tokenTypes = tokenTypes.map { it.name },
                readTypes = readTypes.map { it.name },
                anchorMillis = anchor,
                fromMillis = fromMillis,
                chunkMillis = chunk,
                chunksDone = 0,
                chunksTotal = total,
                recordsRead = 0,
                first = first,
            )
        state = state.copy(backfill = next)
        save()
        backfill(next, tokenTypes)
    }

    private suspend fun backfill(
        start: Backfill,
        granted: Set<HealthDataType>,
    ) {
        var now = start
        val types =
            now.readTypes
                .mapNotNull { name -> HealthDataType.entries.firstOrNull { it.name == name } }
                .filter {
                    it in
                        granted
                }.toSet()
        val seen = HashSet<String>()
        publishProgress(now)
        while (now.chunksDone < now.chunksTotal) {
            val newest = now.anchorMillis - now.chunksDone * now.chunkMillis
            val to = newest + if (now.chunksDone == 0) FIRST_CHUNK_MARGIN_MILLIS else 0L
            val from = maxOf(now.fromMillis, newest - now.chunkMillis)
            val read = readWindow(types, from, to)
            enqueue(read, seen)
            now = now.copy(chunksDone = now.chunksDone + 1, recordsRead = now.recordsRead + read.emit.size)
            state = state.copy(backfill = now)
            save()
            publishProgress(now)
            // Sending as it goes keeps the disk small and the progress true; a failure is the end's to report.
            if (now.chunksDone < now.chunksTotal) flush(ignoreBackoff = false)
        }
        state =
            state.copy(
                changesToken = now.token,
                tokenTypes = now.tokenTypes,
                backfill = null,
                firstReadDone = true,
                lastReadMillis = wallMillis(),
            )
        save()
        mutable.update { it.copy(progress = null) }
    }

    private fun publishProgress(now: Backfill) {
        val estimate =
            if (now.chunksDone ==
                0
            ) {
                0
            } else {
                (now.recordsRead.toLong() * now.chunksTotal / now.chunksDone).toInt()
            }
        mutable.update { it.copy(progress = ReadProgress(now.chunksDone, now.chunksTotal, now.recordsRead, estimate)) }
    }

    /** What a window or a page of changes held: the records to send and the ones that only explain them. */
    private class Read(
        val emit: List<HealthRecord>,
        val context: List<HealthRecord>,
    )

    private suspend fun readWindow(
        types: Set<HealthDataType>,
        fromMillis: Long,
        toMillis: Long,
    ): Read {
        val sessions = ArrayList<HealthRecord>()
        val others = ArrayList<HealthRecord>()
        for (type in types) {
            if (type == HealthDataType.HEART_RATE) continue
            readAll(type, fromMillis, toMillis) { page ->
                if (type == HealthDataType.SLEEP ||
                    type == HealthDataType.EXERCISE
                ) {
                    sessions += page
                } else {
                    others += page
                }
            }
        }
        val series = ArrayList<HealthRecord>()
        if (HealthDataType.HEART_RATE in types) {
            val spans = spansOf(sessions)
            readAll(HealthDataType.HEART_RATE, fromMillis, toMillis) { page -> series += keepInside(page, spans) }
        }
        return Read(sessions + others + series, emptyList())
    }

    private suspend fun readAll(
        type: HealthDataType,
        fromMillis: Long,
        toMillis: Long,
        onPage: (List<HealthRecord>) -> Unit,
    ) {
        var token: String? = null
        var pages = 0
        do {
            val page = source.readPage(type, fromMillis, toMillis, token)
            onPage(page.records)
            token = page.nextPageToken
            check(++pages < pageGuard) { "A read does not end" }
        } while (token != null)
    }

    private suspend fun incremental(granted: Set<HealthDataType>) {
        var token = checkNotNull(state.changesToken) { "No token" }
        val seen = HashSet<String>()
        var rounds = 0
        while (true) {
            val changes = source.changes(token)
            if (changes.tokenExpired) {
                val from = maxOf((state.lastReadMillis ?: 0L) - days(OVERLAP_DAYS), wallMillis() - days(historyDays))
                startBackfill(granted, granted, from, first = false)
                return
            }
            val relevant = changes.upserts.filter { it.dataType in granted }
            if (relevant.isNotEmpty()) enqueue(explain(relevant, granted), seen)
            token = changes.nextToken
            state = state.copy(changesToken = token, lastReadMillis = wallMillis())
            save()
            if (!changes.hasMore) return
            check(++rounds < pageGuard) { "The changes do not end" }
        }
    }

    /**
     * What a heart-rate record or a session needs to be understood. A changed session brings the heart rate that overlaps it (the
     * night's samples, the activity's own); changed heart rate brings the sessions that overlap it. Either way the samples that are
     * sent are those inside **all** the sleep sessions around, so a series is the same whichever change made it be looked at.
     */
    private suspend fun explain(
        upserts: List<HealthRecord>,
        granted: Set<HealthDataType>,
    ): Read {
        val emit = ArrayList<HealthRecord>(upserts)
        val sessions = upserts.filter { it is SleepSessionReading || it is ExerciseSessionReading }
        if (HealthDataType.HEART_RATE in granted && sessions.isNotEmpty()) {
            val spans = spansOf(sessions)
            readAll(HealthDataType.HEART_RATE, spans.minOf { it.first }, spans.maxOf { it.second }) { page ->
                emit += page.filterIsInstance<HeartRateSeries>()
            }
        }
        val series = emit.filterIsInstance<HeartRateSeries>()
        val context = ArrayList<HealthRecord>()
        if (series.isNotEmpty()) {
            val from = series.minOf { it.startMillis }
            val to = series.maxOf { it.endMillis }
            for (type in listOf(HealthDataType.SLEEP, HealthDataType.EXERCISE)) {
                if (type in granted) readAll(type, from, to) { page -> context += page }
            }
        }
        val spans = spansOf(emit + context)
        return Read(
            emit.mapNotNull { if (it is HeartRateSeries) keepInside(listOf(it), spans).firstOrNull() else it },
            context,
        )
    }

    private fun spansOf(records: List<HealthRecord>): List<Pair<Long, Long>> =
        records.mapNotNull {
            when (it) {
                is SleepSessionReading -> it.startMillis to it.endMillis
                is ExerciseSessionReading -> it.startMillis to it.endMillis
                else -> null
            }
        }

    /** Heart-rate series cut to the samples inside [spans]; a series with none left is dropped, other records pass. */
    private fun keepInside(
        records: List<HealthRecord>,
        spans: List<Pair<Long, Long>>,
    ): List<HealthRecord> =
        records.mapNotNull { record ->
            if (record !is HeartRateSeries) {
                record
            } else {
                val inside = record.samples.filter { sample -> spans.any { sample.timeMillis in it.first..it.second } }
                if (inside.isEmpty()) null else record.copy(samples = inside)
            }
        }

    private suspend fun enqueue(
        read: Read,
        seen: MutableSet<String>,
    ) {
        if (read.emit.isEmpty()) return
        val mapped = WellnessMapper.map(read.emit, read.context)
        val fresh = mapped.records.filter { seen.add(it.id + "@" + (it.lastModified ?: "")) }
        // A reading's disk write must not be cut short: the batch is on disk or not at all.
        withContext(NonCancellable) {
            for (batch in WellnessBatcher.batches(fresh, device, maxRecords, maxBytes)) {
                batches.add(batch)
                queued += batch.records
            }
        }
    }

    // ---- Sending -------------------------------------------------------------------------------------------

    private class Flushed(
        val records: Int,
        val failed: Boolean,
    )

    /** What this run has sent so far; [lastSent] is made from it, so a run that sent in several flushes says one total. */
    private class Tally {
        var sent = 0
        var accepted = 0
        var duplicates = 0
        var rejected = 0
        val byType = LinkedHashMap<String, Int>()

        fun counts() = SentCounts(sent, accepted, duplicates, rejected, byType.toMap())
    }

    private var tally = Tally()

    /**
     * Sends the queue in order and stops at the first failure with the rest waiting. A flush that sends nothing because the backoff
     * has not run out changes nothing: it is not a success and not a second failure.
     */
    private suspend fun flush(ignoreBackoff: Boolean): Flushed {
        val pending = batches.pending()
        if (pending.isEmpty()) {
            if (state.failures > 0 || state.nextAttemptMillis != null) {
                state = state.copy(failures = 0, nextAttemptMillis = null, uploadProblem = null)
                save()
            }
            publish()
            return Flushed(tally.sent, false)
        }
        var attempted = false
        var problem: ServiceProblem? = null
        var refusal: ServiceProblem? = null
        for (info in pending) {
            if (!ignoreBackoff && (state.nextAttemptMillis ?: 0L) > wallMillis()) break
            val body = batches.body(info.seq)
            if (body == null) {
                batches.park(info.seq)
                continue
            }
            attempted = true
            var stop = false
            when (val outcome = client.post(UPLOAD_PATH, body, MAX_ANSWER_BYTES)) {
                Outcome.NotSetUp -> {
                    attempted = false
                    stop = true
                }

                is Outcome.Failed -> {
                    if (refusedForGood(outcome.problem)) {
                        batches.park(info.seq)
                        refusal = outcome.problem
                    } else {
                        problem = outcome.problem
                        stop = true
                    }
                }

                is Outcome.Ok -> {
                    val answer =
                        try {
                            WellnessApiJson.decodeUploadResponse(outcome.body)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    if (answer == null) {
                        // 2xx with something else in it (a portal, a proxy): not an acceptance, so the batch stays.
                        problem = ServiceProblem.NotTheService
                        stop = true
                    } else {
                        batches.remove(info.seq)
                        tally.sent += info.records
                        tally.accepted += answer.accepted
                        tally.duplicates += answer.duplicates
                        tally.rejected += answer.rejected
                        for ((kind, n) in info.byType) tally.byType[kind] = (tally.byType[kind] ?: 0) + n
                    }
                }
            }
            if (stop) break
        }
        if (!attempted) {
            publish()
            return Flushed(tally.sent, failed = state.nextAttemptMillis != null)
        }
        val now = wallMillis()
        val failed = problem != null
        state =
            state.copy(
                lastSentMillis = if (tally.sent > 0) now else state.lastSentMillis,
                lastSent = if (tally.sent > 0) tally.counts() else state.lastSent,
                failures = if (failed) state.failures + 1 else 0,
                nextAttemptMillis = if (failed) now + retry.delayAfter(state.failures + 1) else null,
                uploadProblem = (problem ?: refusal)?.let(StoredProblem::of),
            )
        save()
        publish()
        return Flushed(tally.sent, failed)
    }

    /** The service looked at the batch and said no, and will say it again: 400, 413 or 422. */
    private fun refusedForGood(problem: ServiceProblem): Boolean =
        problem.kind == ProblemKind.REQUEST_REFUSED && problem.status in REFUSALS

    // ---- Persistence and the status --------------------------------------------------------------------------

    private suspend fun ensureLoaded() {
        if (loaded) return
        loading.withLock {
            if (loaded) return
            state = HealthSyncState.decode(runCatchingStore { stateStore?.read() })
            loaded = true
        }
    }

    private suspend fun <T> runCatchingStore(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun save() {
        val target = stateStore ?: return
        withContext(NonCancellable) {
            try {
                target.write(HealthSyncState.encode(state))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The state is a convenience for the next run: without it the window is read again, which the service takes as duplicates.
            }
        }
    }

    private suspend fun publish() {
        val waiting = batches.pending()
        val parked = batches.parked()
        mutable.update {
            it.copy(
                firstReadDone = state.firstReadDone,
                waitingRecords = waiting.sumOf { info -> info.records },
                waitingBatches = waiting.size,
                parkedBatches = parked.size,
                lastReadMillis = state.lastReadMillis,
                lastSentMillis = state.lastSentMillis,
                lastSent = state.lastSent,
                uploadProblem = state.uploadProblem?.toProblem(),
                readIssue = state.readIssue,
                nextRetryMillis = state.nextAttemptMillis,
                progress = state.backfill?.let { b -> it.progress ?: progressOf(b) },
            )
        }
    }

    private fun progressOf(b: Backfill): ReadProgress {
        val estimate = if (b.chunksDone == 0) 0 else (b.recordsRead.toLong() * b.chunksTotal / b.chunksDone).toInt()
        return ReadProgress(b.chunksDone, b.chunksTotal, b.recordsRead, estimate)
    }

    public companion object {
        public const val HISTORY_DAYS: Int = 60
        public const val CHUNK_DAYS: Int = 5
        private const val OVERLAP_DAYS = 2

        /** The newest window reaches a second past the token, so a record stamped at the very instant of the token is not between the two. */
        private const val FIRST_CHUNK_MARGIN_MILLIS = 1000L
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val PAGE_GUARD = 10_000
        private const val UPLOAD_PATH = "/v1/wellness"
        private const val MAX_ANSWER_BYTES = 64 * 1024
        private val REFUSALS = setOf(400, 413, 422)
    }
}
