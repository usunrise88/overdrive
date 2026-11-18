package app.overdrive.core.health

import app.overdrive.core.remote.MemoryTextStore
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.wellness.RecordCounts
import app.overdrive.core.wellness.SleepStage
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessRecord
import app.overdrive.core.wellness.WellnessUploadResponse

/** A distinctive figure the privacy tests look for; no real reading. */
internal const val SENTINEL_BPM = 177L

internal const val DAY = 86_400_000L
internal const val HOUR = 3_600_000L

/** 2026-11-18T07:00:00Z, the "now" of the tests. */
internal const val NOW = 1_794_985_200_000L

/** The phone's Health Connect, scripted: records it holds, pages, a changes log, expiring tokens and refusals. All values synthetic. */
internal class FakeHealthSource : HealthSource {
    var availability = HealthAvailability.AVAILABLE
    var access = HealthAccess(HealthDataType.entries.toSet(), history = true, background = true)
    var pageSize = 100
    var changesPageSize = 100

    /** Makes the next read throw it (once), like Health Connect refusing or failing. */
    var failNext: Exception? = null
    var failAccess: Exception? = null

    /** Fails the n-th call of [readPage] (1-based) with [failAt], to stop a first read half way. */
    var failOnPage: Int? = null
    var failAt: Exception = IllegalStateException("read failed at SENTINEL-$SENTINEL_BPM")

    private class Logged(
        val seq: Long,
        val record: HealthRecord,
    )

    private val held = ArrayList<HealthRecord>()
    private val log = ArrayList<Logged>()
    private var seq = 0L
    private val tokens = HashMap<String, Pair<Long, Set<HealthDataType>>>()
    private val expired = HashSet<String>()

    val reads = ArrayList<Triple<HealthDataType, Long, Long>>()
    var pagesRead = 0
    var tokensIssued = 0

    /** How many times a token was asked for with [newChangesToken] (the next tokens of [changes] are not counted). */
    var tokensRequested = 0

    /** Holds a read until it is completed, to keep a run in flight. [entered] says the read has begun. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun add(record: HealthRecord) {
        held += record
        log += Logged(++seq, record)
    }

    /** Replaces the record with the same id (an edit), logging it as a change. */
    fun edit(record: HealthRecord) {
        held.removeAll { it.id == record.id }
        held += record
        log += Logged(++seq, record)
    }

    fun expire(token: String) {
        expired += token
    }

    fun expireAll() {
        expired += tokens.keys
    }

    fun readsOf(type: HealthDataType) = reads.filter { it.first == type }

    private fun bounds(record: HealthRecord): Pair<Long, Long> =
        when (record) {
            is RestingHeartRateReading -> record.timeMillis to record.timeMillis
            is HeartRateSeries -> record.startMillis to record.endMillis
            is SleepSessionReading -> record.startMillis to record.endMillis
            is RespiratoryRateReading -> record.timeMillis to record.timeMillis
            is OxygenSaturationReading -> record.timeMillis to record.timeMillis
            is StepsReading -> record.startMillis to record.endMillis
            is ExerciseSessionReading -> record.startMillis to record.endMillis
            is HeartRateVariabilityReading -> record.timeMillis to record.timeMillis
            is WeightReading -> record.timeMillis to record.timeMillis
        }

    override suspend fun availability(): HealthAvailability = availability

    override suspend fun access(): HealthAccess {
        failAccess?.let {
            failAccess = null
            throw it
        }
        return access
    }

    override suspend fun readPage(
        type: HealthDataType,
        fromMillis: Long,
        toMillis: Long,
        pageToken: String?,
    ): HealthPage {
        failNext?.let {
            failNext = null
            throw it
        }
        gate?.let {
            entered.complete(Unit)
            it.await()
        }
        pagesRead++
        if (failOnPage == pagesRead) throw failAt
        check(type in access.granted) { "Read of a type that is not allowed" }
        if (pageToken == null) reads += Triple(type, fromMillis, toMillis)
        val all =
            held
                .filter { it.dataType == type }
                .filter {
                    val (start, end) = bounds(it)
                    if (start == end) start in fromMillis until toMillis else start < toMillis && end > fromMillis
                }.sortedBy { bounds(it).first }
        val offset = pageToken?.toInt() ?: 0
        val page = all.drop(offset).take(pageSize)
        val next = if (offset + page.size < all.size) (offset + page.size).toString() else null
        return HealthPage(page, next)
    }

    override suspend fun newChangesToken(types: Set<HealthDataType>): String {
        failNext?.let {
            failNext = null
            throw it
        }
        check(types.all { it in access.granted }) { "A token for a type that is not allowed" }
        tokensRequested++
        val token = "token-${++tokensIssued}-$seq"
        tokens[token] = seq to types
        return token
    }

    override suspend fun changes(token: String): HealthChanges {
        failNext?.let {
            failNext = null
            throw it
        }
        if (token in expired ||
            token !in tokens
        ) {
            return HealthChanges(emptyList(), 0, token, hasMore = false, tokenExpired = true)
        }
        val (since, types) = tokens.getValue(token)
        check(types.all { it in access.granted }) { "Changes for a type that is not allowed" }
        val pending = log.filter { it.seq > since && it.record.dataType in types }
        val page = pending.take(changesPageSize)
        val upTo = if (page.size < pending.size) page.last().seq else seq
        val next = "token-${++tokensIssued}-$upTo"
        tokens[next] = upTo to types
        return HealthChanges(page.map { it.record }, 0, next, hasMore = page.size < pending.size, tokenExpired = false)
    }
}

/** The service's `POST /v1/wellness`, idempotent by (source, id) and `lastModified`, with failures a test can switch on. */
internal class FakeWellnessService : HttpPostTransport {
    val bodies = ArrayList<String>()
    val stored = LinkedHashMap<String, String?>()
    var posts = 0

    /** Answers to give instead of storing, in order: an [HttpAnswer] or a [Throwable]; empty means serve normally. */
    val script = ArrayDeque<Any>()

    /** Stores the batch and then fails like a lost answer. */
    var loseAnswers = 0

    override suspend fun post(request: HttpPostRequest): HttpAnswer {
        posts++
        bodies += request.body
        script.removeFirstOrNull()?.let { entry ->
            when (entry) {
                is HttpAnswer -> return entry
                is Throwable -> throw entry
                else -> error("bad script entry")
            }
        }
        val upload = WellnessApiJson.decodeUpload(request.body)
        var accepted = 0
        var duplicates = 0
        val by = LinkedHashMap<String, MutableList<Int>>()
        for (record in upload.records) {
            val known = stored.containsKey(record.id)
            val newer =
                known && record.lastModified != null &&
                    (stored[record.id] == null || record.lastModified!! > stored[record.id]!!)
            val kind = record.kindName()
            val counts = by.getOrPut(kind) { mutableListOf(0, 0) }
            if (!known || newer) {
                stored[record.id] = record.lastModified
                accepted++
                counts[0]++
            } else {
                duplicates++
                counts[1]++
            }
        }
        if (loseAnswers > 0) {
            loseAnswers--
            throw TransportException(TransportFailure.Dropped)
        }
        val response =
            WellnessUploadResponse(
                accepted,
                duplicates,
                0,
                by.mapValues { RecordCounts(it.value[0], it.value[1], 0) },
                "2026-11-18T07:00:00Z",
            )
        return HttpAnswer(200, WellnessApiJson.encodeUploadResponse(response))
    }

    /** Every record id the service holds. */
    fun ids(): Set<String> = stored.keys

    fun records(): List<WellnessRecord> = bodies.flatMap { WellnessApiJson.decodeUpload(it).records }
}

/** Synthetic records: a night, a morning, a workout. Values are invented. */
internal object HealthSamples {
    fun resting(
        id: String,
        at: Long,
        bpm: Long = 52,
        modified: Long? = at + 60_000,
    ) = RestingHeartRateReading(id, at, bpm, modified)

    fun night(
        id: String,
        endsAt: Long,
        hours: Int = 7,
        stages: Boolean = true,
    ): SleepSessionReading {
        val start = endsAt - hours * HOUR
        return SleepSessionReading(
            id,
            start,
            endsAt,
            "Night",
            if (stages) {
                listOf(
                    SleepStageReading(SleepStage.LIGHT, start, start + 2 * HOUR),
                    SleepStageReading(SleepStage.DEEP, start + 2 * HOUR, start + 4 * HOUR),
                    SleepStageReading(SleepStage.REM, start + 4 * HOUR, endsAt),
                )
            } else {
                null
            },
            lastModifiedMillis = endsAt + 60_000,
        )
    }

    /** One heart rate a minute between [from] and [to]. */
    fun heartRate(
        id: String,
        from: Long,
        to: Long,
        bpm: Long = 55,
        everyMillis: Long = 60_000,
    ) = HeartRateSeries(
        id,
        from,
        to,
        generateSequence(from) { it + everyMillis }.takeWhile { it < to }.map { HeartRateTick(it, bpm) }.toList(),
        lastModifiedMillis = to,
    )

    fun workout(
        id: String,
        start: Long,
        minutes: Int = 40,
        type: String = "other_workout",
    ) = ExerciseSessionReading(
        id,
        type,
        start,
        start + minutes * 60_000L,
        "Synthetic session",
        lastModifiedMillis =
            start + 3_600_000,
    )
}

internal fun memoryState() = MemoryTextStore()
