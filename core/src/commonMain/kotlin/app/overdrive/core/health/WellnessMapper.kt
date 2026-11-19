package app.overdrive.core.health

import app.overdrive.core.wellness.HeartRateSample
import app.overdrive.core.wellness.HeartRateSamplesRecord
import app.overdrive.core.wellness.OxygenSaturationRecord
import app.overdrive.core.wellness.RespiratoryRateRecord
import app.overdrive.core.wellness.RestingHeartRateRecord
import app.overdrive.core.wellness.SleepSessionRecord
import app.overdrive.core.wellness.SleepStageSpan
import app.overdrive.core.wellness.StepsRecord
import app.overdrive.core.wellness.WellnessRecord
import app.overdrive.core.wellness.WellnessTime
import app.overdrive.core.wellness.ExerciseSessionRecord as WireExerciseSession
import app.overdrive.core.wellness.HeartRateVariabilityRmssdRecord as WireHrv
import app.overdrive.core.wellness.WeightRecord as WireWeight

/**
 * Which heart-rate samples go to the service. The service uses them in two places only: the heart rate **in sleep** and the load of
 * a watch **activity**. [SLEEP_AND_EXERCISE] therefore sends the samples inside a sleep session (as a heart-rate record) and inside an
 * exercise session (attached to it), and nothing else: a watch writes a heart rate every few seconds all day, and sending it all
 * would be tens of megabytes of figures nothing reads. [ALL] sends every sample (a test's choice).
 */
public enum class HeartRatePolicy { SLEEP_AND_EXERCISE, ALL }

/** What a mapping made: the wire [records] and how many records were [skipped] because the contract would refuse them. */
public class Mapped(
    public val records: List<WellnessRecord>,
    public val skipped: Int,
)

/**
 * Health Connect records into the wire records of `POST /v1/wellness` (`wellness-upload.schema.json`, ADR 0023, ADR 0027). Pure.
 *
 * - **Nothing the service would refuse goes out.** The service checks a whole batch first and one bad record refuses all of it, so a
 *   record the contract's models reject (a weight of 0 kg, an end before its start) is [skipped][Mapped.skipped], counted and never
 *   described: a glitch in one record must not stop a phone from ever syncing. A sample outside 1 to 300 bpm is dropped.
 * - **Ids** are Health Connect's own `metadata.id`, made safe for the contract (`A-Z a-z 0-9 . _ -`, at most 100, starting with a
 *   letter or digit). The same record always gets the same id, so a re-send is a duplicate and an edited record (a newer
 *   `lastModified`) replaces.
 * - **Heart rate** follows the [HeartRatePolicy]. An exercise session gets the samples of the heart-rate records inside it; a
 *   heart-rate record with more samples than a record may hold is split (`id`, `id.1`, ...).
 * - Records of one id in one call are merged to one: the newest `lastModified` wins; heart-rate samples of one id are united.
 *
 * It holds and logs nothing: the figures pass through.
 */
public object WellnessMapper {
    private const val MAX_RECORD_SAMPLES = 20_000
    private const val MAX_BPM = 300
    private const val MAX_SESSION_SAMPLES = 50_000
    private const val MAX_STAGES = 2000
    private const val MAX_ID = 100
    private const val MAX_TITLE = 100
    private const val OTHER_WORKOUT = "other_workout"
    private val unsafe = Regex("[^A-Za-z0-9._-]")
    private val exerciseType = Regex("^[a-z][a-z0-9_]{0,39}$")

    /**
     * Maps [emit] to wire records. [context] are records that are not sent but explain the others: the sessions that decide which
     * heart-rate samples count, and the heart-rate records inside an exercise session.
     */
    public fun map(
        emit: List<HealthRecord>,
        context: List<HealthRecord> = emptyList(),
        policy: HeartRatePolicy = HeartRatePolicy.SLEEP_AND_EXERCISE,
    ): Mapped {
        var skipped = 0
        val out = LinkedHashMap<String, Pair<WellnessRecord, Long>>()

        fun add(
            make: () -> WellnessRecord,
            lastModified: Long?,
        ) {
            val record =
                try {
                    make()
                } catch (e: IllegalArgumentException) {
                    skipped++
                    return
                }
            val known = out[record.id]
            val stamp = lastModified ?: Long.MIN_VALUE
            if (known == null || stamp > known.second) out[record.id] = record to stamp
        }

        val everything = emit + context
        val sleepSpans = everything.filterIsInstance<SleepSessionReading>().map { it.startMillis to it.endMillis }
        val heartRate = everything.filterIsInstance<HeartRateSeries>().let(::mergeSeries)
        val doneSeries = HashSet<String>()

        for (record in emit) {
            when (record) {
                is RestingHeartRateReading -> {
                    add({
                        RestingHeartRateRecord(
                            wireId(record.id),
                            instant(record.timeMillis),
                            record.bpm.toInt(),
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is SleepSessionReading -> {
                    add({ sleep(record) }, record.lastModifiedMillis)
                }

                is RespiratoryRateReading -> {
                    add({
                        RespiratoryRateRecord(
                            wireId(record.id),
                            instant(record.timeMillis),
                            record.rate,
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is OxygenSaturationReading -> {
                    add({
                        OxygenSaturationRecord(
                            wireId(record.id),
                            instant(record.timeMillis),
                            record.percent,
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is StepsReading -> {
                    add({
                        StepsRecord(
                            wireId(record.id),
                            instant(record.startMillis),
                            instant(record.endMillis),
                            record.count.toIntSafe(),
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is ExerciseSessionReading -> {
                    add({ exercise(record, heartRate) }, record.lastModifiedMillis)
                }

                is HeartRateVariabilityReading -> {
                    add({
                        WireHrv(
                            wireId(record.id),
                            instant(record.timeMillis),
                            record.ms,
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is WeightReading -> {
                    add({
                        WireWeight(
                            wireId(record.id),
                            instant(record.timeMillis),
                            record.kg,
                            stamp(record),
                            record.zoneOffsetSeconds,
                        )
                    }, record.lastModifiedMillis)
                }

                is HeartRateSeries -> {
                    // One record per id, in the place of its first occurrence.
                    if (doneSeries.add(record.id)) {
                        val merged = heartRate.first { it.id == record.id }
                        for (make in seriesRecords(merged, sleepSpans, policy)) add(make, merged.lastModifiedMillis)
                    }
                }
            }
        }
        return Mapped(out.values.map { it.first }, skipped)
    }

    /**
     * The records one heart-rate series becomes: the samples that count under [policy], cleaned, in parts of at most
     * [MAX_RECORD_SAMPLES] (`id`, `id.1`, ...). A series that is sent whole keeps its own start and end; one that was cut starts
     * and ends with its first and last sample.
     */
    private fun seriesRecords(
        series: HeartRateSeries,
        sleepSpans: List<Pair<Long, Long>>,
        policy: HeartRatePolicy,
    ): List<() -> WellnessRecord> {
        val kept =
            when (policy) {
                HeartRatePolicy.ALL -> {
                    series.samples
                }

                HeartRatePolicy.SLEEP_AND_EXERCISE -> {
                    series.samples.filter { s ->
                        sleepSpans.any {
                            s.timeMillis in
                                it.first..it.second
                        }
                    }
                }
            }.filter { it.bpm in 1..MAX_BPM }
        if (kept.isEmpty()) return emptyList()
        val whole = kept.size == series.samples.size && kept.size <= MAX_RECORD_SAMPLES
        return kept.chunked(MAX_RECORD_SAMPLES).mapIndexed { index, part ->
            {
                HeartRateSamplesRecord(
                    wireId(if (index == 0) series.id else "${series.id}.$index"),
                    instant(if (whole) series.startMillis else part.first().timeMillis),
                    instant(if (whole) series.endMillis else part.last().timeMillis),
                    part.map { HeartRateSample(instant(it.timeMillis), it.bpm.toInt()) },
                    stamp(series),
                    series.zoneOffsetSeconds,
                )
            }
        }
    }

    private fun stamp(record: HealthRecord): String? = record.lastModifiedMillis?.let(::instant)

    private fun instant(millis: Long): String = WellnessTime.formatInstant(millis)

    private fun Long.toIntSafe(): Int = if (this in 0..Int.MAX_VALUE) toInt() else -1

    /** A Health Connect id made safe for the contract; an id with nothing usable in it stays unusable (the record is skipped). */
    internal fun wireId(id: String): String {
        val safe = id.replace(unsafe, "_").take(MAX_ID)
        require(safe.isNotEmpty()) { "A record has no id" }
        return if (safe[0].isLetterOrDigit() && safe[0].code < 128) safe else "h${safe.take(MAX_ID - 1)}"
    }

    private fun title(text: String?): String? = text?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() }

    private fun sleep(record: SleepSessionReading): WellnessRecord {
        val stages =
            record.stages
                ?.filter { it.endMillis >= it.startMillis }
                ?.map { SleepStageSpan(it.stage, instant(it.startMillis), instant(it.endMillis)) }
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_STAGES }
        return SleepSessionRecord(
            wireId(record.id),
            instant(record.startMillis),
            instant(record.endMillis),
            title(record.title),
            stages,
            stamp(record),
            record.zoneOffsetSeconds,
        )
    }

    private fun exercise(
        record: ExerciseSessionReading,
        heartRate: List<HeartRateSeries>,
    ): WellnessRecord {
        val own =
            record.heartRate
                ?: heartRate
                    .flatMap { it.samples }
                    .filter { it.timeMillis in record.startMillis..record.endMillis }
        val samples =
            own
                .filter { it.bpm in 1..300 }
                .distinctBy { it.timeMillis }
                .sortedBy { it.timeMillis }
                .let { if (it.size > MAX_SESSION_SAMPLES) thin(it, MAX_SESSION_SAMPLES) else it }
        return WireExerciseSession(
            wireId(record.id),
            record.type.takeIf { exerciseType.matches(it) } ?: OTHER_WORKOUT,
            instant(record.startMillis),
            instant(record.endMillis),
            title(record.title),
            samples.takeIf { it.isNotEmpty() }?.map { HeartRateSample(instant(it.timeMillis), it.bpm.toInt()) },
            stamp(record),
            record.zoneOffsetSeconds,
        )
    }

    /** Every n-th sample so that no more than [limit] remain, the first and the last kept. */
    private fun thin(
        samples: List<HeartRateTick>,
        limit: Int,
    ): List<HeartRateTick> {
        val step = (samples.size + limit - 1) / limit
        return samples.filterIndexed { index, _ -> index % step == 0 || index == samples.lastIndex }.take(limit)
    }

    /** Series of one id united into one (a record read twice, or read whole and clipped), samples ascending and unique in time. */
    private fun mergeSeries(all: List<HeartRateSeries>): List<HeartRateSeries> =
        all.groupBy { it.id }.map { (_, same) ->
            if (same.size == 1) {
                same.first()
            } else {
                val newest = same.maxBy { it.lastModifiedMillis ?: Long.MIN_VALUE }
                newest.copy(
                    startMillis = same.minOf { it.startMillis },
                    endMillis = same.maxOf { it.endMillis },
                    samples = same.flatMap { it.samples }.distinctBy { it.timeMillis }.sortedBy { it.timeMillis },
                )
            }
        }
}
