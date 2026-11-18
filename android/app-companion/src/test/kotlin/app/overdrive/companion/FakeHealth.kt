package app.overdrive.companion

import app.overdrive.core.health.ExerciseSessionReading
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthChanges
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthPage
import app.overdrive.core.health.HealthRecord
import app.overdrive.core.health.HealthSource
import app.overdrive.core.health.HeartRateSeries
import app.overdrive.core.health.HeartRateTick
import app.overdrive.core.health.HeartRateVariabilityReading
import app.overdrive.core.health.OxygenSaturationReading
import app.overdrive.core.health.RespiratoryRateReading
import app.overdrive.core.health.RestingHeartRateReading
import app.overdrive.core.health.SleepSessionReading
import app.overdrive.core.health.SleepStageReading
import app.overdrive.core.health.StepsReading
import app.overdrive.core.health.WeightReading
import app.overdrive.core.wellness.SleepStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's Health Connect for the container tests: records it holds, pages, a changes log and an access a test can change while
 * the container runs. Thread-safe, since the container reads on its own threads. All figures are synthetic.
 */
class FakeHealth : HealthSource {
    @Volatile
    var availability = HealthAvailability.AVAILABLE

    @Volatile
    var access = HealthAccess(emptySet(), history = false, background = false)

    private class Logged(
        val seq: Int,
        val record: HealthRecord,
    )

    private val held = CopyOnWriteArrayList<HealthRecord>()
    private val log = CopyOnWriteArrayList<Logged>()
    private val seq = AtomicInteger()
    private val tokenAt = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val tokens = AtomicInteger()
    val pagesRead = AtomicInteger()

    /** Makes every read after this many pages fail, with a message that holds a figure (which no state may keep). */
    @Volatile
    var failAfterPages: Int? = null

    fun allow(types: Set<HealthDataType> = HealthDataType.entries.toSet()) {
        access = HealthAccess(types, history = true, background = true)
    }

    fun add(record: HealthRecord) {
        held += record
        log += Logged(seq.incrementAndGet(), record)
    }

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

    override suspend fun access(): HealthAccess = access

    override suspend fun readPage(
        type: HealthDataType,
        fromMillis: Long,
        toMillis: Long,
        pageToken: String?,
    ): HealthPage {
        val read = pagesRead.incrementAndGet()
        failAfterPages?.let { if (read > it) throw IllegalStateException("read failed at SENTINEL-$SENTINEL_BPM") }
        val all =
            held
                .filter { it.dataType == type }
                .filter {
                    val (start, end) = bounds(it)
                    if (start == end) start in fromMillis until toMillis else start < toMillis && end > fromMillis
                }.sortedBy { bounds(it).first }
        val offset = pageToken?.toInt() ?: 0
        val page = all.drop(offset).take(PAGE)
        return HealthPage(page, if (offset + page.size < all.size) (offset + page.size).toString() else null)
    }

    override suspend fun newChangesToken(types: Set<HealthDataType>): String {
        val token = "token-${tokens.incrementAndGet()}"
        tokenAt[token] = seq.get()
        return token
    }

    override suspend fun changes(token: String): HealthChanges {
        val since = tokenAt[token] ?: return HealthChanges(emptyList(), 0, token, hasMore = false, tokenExpired = true)
        val now = seq.get()
        val upserts = log.filter { it.seq > since && it.record.dataType in access.granted }.map { it.record }
        val next = "token-${tokens.incrementAndGet()}"
        tokenAt[next] = now
        return HealthChanges(upserts, 0, next, hasMore = false, tokenExpired = false)
    }

    companion object {
        private const val PAGE = 50
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        /** A distinctive figure the privacy tests look for in files, states and logs; no real reading. */
        const val SENTINEL_BPM = 177L

        /** Sixty nights and mornings ending at [end], each with a resting heart rate, a night and a day of heart rate under it. */
        fun sixtyDays(
            health: FakeHealth,
            end: Long,
            days: Int = 60,
        ) {
            for (d in 0 until days) {
                val morning = end - d * DAY - 30 * 60_000
                health.add(RestingHeartRateReading("rhr-$d", morning, 50L + d % 5, morning + 60_000))
                val start = morning - 7 * HOUR
                val stages =
                    listOf(
                        SleepStageReading(SleepStage.LIGHT, start, start + 3 * HOUR),
                        SleepStageReading(SleepStage.DEEP, start + 3 * HOUR, morning),
                    )
                health.add(SleepSessionReading("sleep-$d", start, morning, "Night", stages, morning + 60_000))
                val samples =
                    generateSequence(start - 4 * HOUR) { it + 60_000 }
                        .takeWhile { it < morning + 4 * HOUR }
                        .map { HeartRateTick(it, SENTINEL_BPM) }
                        .toList()
                health.add(HeartRateSeries("hr-$d", start - 4 * HOUR, morning + 4 * HOUR, samples, morning))
            }
            val session = end - 3 * DAY
            health.add(
                ExerciseSessionReading(
                    "ex-1",
                    "other_workout",
                    session,
                    session + 40 * 60_000,
                    "Synthetic session",
                    null,
                    session,
                ),
            )
        }

        /** The ids the service ends up holding for [sixtyDays]. */
        fun idsOf(days: Int = 60): Set<String> =
            buildSet {
                for (d in 0 until days) {
                    add("rhr-$d")
                    add("sleep-$d")
                    add("hr-$d")
                }
                add("ex-1")
            }
    }
}
