package app.overdrive.core.health

import app.overdrive.core.wellness.ExerciseSessionRecord
import app.overdrive.core.wellness.HeartRateSamplesRecord
import app.overdrive.core.wellness.HeartRateVariabilityRmssdRecord
import app.overdrive.core.wellness.OxygenSaturationRecord
import app.overdrive.core.wellness.RespiratoryRateRecord
import app.overdrive.core.wellness.RestingHeartRateRecord
import app.overdrive.core.wellness.SleepSessionRecord
import app.overdrive.core.wellness.StepsRecord
import app.overdrive.core.wellness.WeightRecord
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessRecord
import app.overdrive.core.wellness.WellnessUpload

/** The name of a record's kind in the upload (`kind`), also the key of the per-type counts. */
public fun WellnessRecord.kindName(): String =
    when (this) {
        is RestingHeartRateRecord -> "restingHeartRate"
        is HeartRateSamplesRecord -> "heartRateSamples"
        is SleepSessionRecord -> "sleepSession"
        is RespiratoryRateRecord -> "respiratoryRate"
        is OxygenSaturationRecord -> "oxygenSaturation"
        is StepsRecord -> "steps"
        is ExerciseSessionRecord -> "exerciseSession"
        is HeartRateVariabilityRmssdRecord -> "heartRateVariabilityRmssd"
        is WeightRecord -> "weight"
    }

/** One request body ready to send, with what it holds: [records] in all and [byType] by kind. */
public class WellnessBatch(
    public val body: String,
    public val records: Int,
    public val byType: Map<String, Int>,
)

/**
 * Cuts wire records into request bodies for `POST /v1/wellness` (ADR 0023: at most 2000 records and 4 MiB; ADR 0027: this side
 * stays under **3 MiB**, so the service's limit is never the one that decides). Records keep their order; a batch ends when the next
 * record would pass either limit. A record that is alone over the byte limit goes alone (the service answers 413 and the outbox
 * parks it). Every body is exactly what [WellnessApiJson.encodeUpload] writes.
 */
public object WellnessBatcher {
    public const val MAX_RECORDS: Int = WellnessApiJson.MAX_RECORDS
    public const val MAX_BYTES: Int = 3 * 1024 * 1024

    /** What `{"source":"healthconnect","device":"…","records":[]}` weighs beyond the records, with a little room. */
    private const val ENVELOPE_BYTES = 160

    public fun batches(
        records: List<WellnessRecord>,
        device: String? = null,
        maxRecords: Int = MAX_RECORDS,
        maxBytes: Int = MAX_BYTES,
    ): List<WellnessBatch> {
        val out = ArrayList<WellnessBatch>()
        var current = ArrayList<WellnessRecord>()
        var size = ENVELOPE_BYTES + (device?.length ?: 0)

        fun close() {
            if (current.isEmpty()) return
            out +=
                WellnessBatch(
                    body = WellnessApiJson.encodeUpload(WellnessUpload("healthconnect", current, device)),
                    records = current.size,
                    byType = current.groupingBy { it.kindName() }.eachCount(),
                )
            current = ArrayList()
            size = ENVELOPE_BYTES + (device?.length ?: 0)
        }
        for (record in records) {
            val bytes = WellnessApiJson.encodeRecord(record).encodeToByteArray().size + 1
            if (current.isNotEmpty() && (current.size >= maxRecords || size + bytes > maxBytes)) close()
            current += record
            size += bytes
        }
        close()
        return out
    }
}

/** What the outbox knows of one waiting batch without reading its body. */
public data class BatchInfo(
    /** The order it was queued in: batches are sent oldest first. */
    val seq: Long,
    val records: Int,
    val bytes: Int,
    val byType: Map<String, Int>,
)

/**
 * Where batches wait for the service (spec §6.2: "uploads them... through an outbox"). A batch is **removed only when the service
 * accepted it** ([remove]); one the service refuses for good (422, 413) is [park]ed, kept on disk and counted, never deleted and
 * never in the way of the ones behind it. The persistent one is `FileBatchStore` in `android/data` (it survives the process being
 * killed at any instant); [InMemoryBatchStore] is the tests' stand-in. Bodies hold health data: nothing here logs or prints one.
 */
public interface BatchStore {
    /** Queues a batch at the end and returns it. The batch is on disk when this returns. */
    public suspend fun add(batch: WellnessBatch): BatchInfo

    /** Everything waiting, oldest first. */
    public suspend fun pending(): List<BatchInfo>

    /** The body of batch [seq], or null when it cannot be read (the caller parks it). */
    public suspend fun body(seq: Long): String?

    /** The service accepted batch [seq]: forget it. */
    public suspend fun remove(seq: Long)

    /** Keeps batch [seq] aside, out of the queue, for the owner to look at. */
    public suspend fun park(seq: Long)

    /** The batches set aside. */
    public suspend fun parked(): List<BatchInfo>
}

/** A [BatchStore] in memory. Loses everything with the process; what the tests use. */
public class InMemoryBatchStore : BatchStore {
    private class Held(
        val info: BatchInfo,
        val body: String,
    )

    private val waiting = LinkedHashMap<Long, Held>()
    private val aside = LinkedHashMap<Long, Held>()
    private var next = 1L

    /** What a test sets to make [add] fail like a full disk. */
    public var failAdds: Boolean = false

    override suspend fun add(batch: WellnessBatch): BatchInfo {
        if (failAdds) throw IllegalStateException("The disk refuses")
        val info = BatchInfo(next++, batch.records, batch.body.length, batch.byType)
        waiting[info.seq] = Held(info, batch.body)
        return info
    }

    override suspend fun pending(): List<BatchInfo> = waiting.values.map { it.info }

    override suspend fun body(seq: Long): String? = waiting[seq]?.body

    override suspend fun remove(seq: Long) {
        waiting.remove(seq)
    }

    override suspend fun park(seq: Long) {
        waiting.remove(seq)?.let { aside[seq] = it }
    }

    override suspend fun parked(): List<BatchInfo> = aside.values.map { it.info }
}
