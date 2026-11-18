package app.overdrive.core.record

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ride record on the wire (spec §8.2): one JSON document, schema 1, described by
 * `contracts/schema/ride-record.schema.json` and checked against the shared fixtures by `RideRecordContractTest`
 * here and by the TypeScript tests of `contracts/`.
 *
 * ```json
 * { "schema": 1,
 *   "ride": { "id": "…", "startEpochMillis": 1794996000000, "ftp": 250, "devices": ["Trainer"] },
 *   "samples": [ { "timeOffsetSeconds": 1, "power": 180, "speedKmh": 31.2, "distanceMeters": 8.7, "paused": false } ],
 *   "rrIntervals": [ { "timeOffsetSeconds": 1, "intervalMs": 812 } ],
 *   "laps": [ { "stepIndex": 0, "startSeconds": 0, "endSeconds": 600, "averagePower": 180.5 } ],
 *   "summary": { "durationSeconds": 600, "movingSeconds": 600, "distanceMeters": 5200.0, "workKilojoules": 108.3 } }
 * ```
 *
 * **A gap is an absent key** (the writer never emits `null`; the reader accepts both). Everything else is always
 * written. The reader is strict like the schema: unknown keys, a wrong `schema` number, a value out of range, samples
 * that do not run 1, 2, 3, … and laps that do not tile the ride are all rejected with an [IllegalArgumentException].
 */
public object RideRecordJson {
    /** The `schema` number written, and the only one read. */
    public const val SCHEMA_VERSION: Int = 1

    internal val json: Json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    /** The record as compact JSON (one line, UTF-8 when written to a file). */
    public fun encode(record: RideRecord): String {
        record.requireConsistent()
        return json.encodeToString(RideRecordDocument.serializer(), RideRecordDocument(SCHEMA_VERSION, record))
    }

    /**
     * Reads a record written by [encode], or by any producer of the schema.
     *
     * @throws IllegalArgumentException if [text] is not valid JSON of the schema, or is not self-consistent.
     */
    public fun decode(text: String): RideRecord {
        val document = json.decodeFromString(RideRecordDocument.serializer(), text)
        require(document.schema == SCHEMA_VERSION) {
            "Unsupported ride record schema ${document.schema}, expected $SCHEMA_VERSION"
        }
        val record = RideRecord(document.ride, document.samples, document.rrIntervals, document.laps, document.summary)
        record.requireConsistent()
        return record
    }
}

/** The JSON object: the record plus the schema number. */
@Serializable
internal class RideRecordDocument(
    val schema: Int,
    val ride: RideHeader,
    val samples: List<RideSample>,
    val rrIntervals: List<RrInterval>,
    val laps: List<Lap>,
    val summary: RideSummary,
) {
    constructor(schema: Int, record: RideRecord) :
        this(schema, record.ride, record.samples, record.rrIntervals, record.laps, record.summary)
}

/**
 * Checks what the schema cannot say: the samples are the seconds 1, 2, 3, … in order, the laps follow each other
 * from 0 to the last second, and the summary's duration is the number of samples. A record built by
 * [RideRecordBuilder] always passes.
 *
 * @throws IllegalArgumentException with the first violation found.
 */
internal fun RideRecord.requireConsistent() {
    samples.forEachIndexed { index, sample ->
        require(sample.timeOffsetSeconds == index + 1) {
            "Expected the sample for second ${index + 1}, got ${sample.timeOffsetSeconds}"
        }
    }
    require(summary.durationSeconds == samples.size) {
        "The summary says ${summary.durationSeconds} s but there are ${samples.size} samples"
    }
    if (samples.isEmpty()) {
        require(laps.isEmpty()) { "A ride without samples has no laps" }
        return
    }
    require(laps.isNotEmpty()) { "A ride with samples has at least one lap" }
    require(laps.first().startSeconds == 0) { "The first lap starts at 0, not ${laps.first().startSeconds}" }
    laps.zipWithNext { previous, next ->
        require(next.startSeconds == previous.endSeconds) {
            "A lap starts where the previous one ends (${previous.endSeconds}), not at ${next.startSeconds}"
        }
    }
    require(laps.last().endSeconds == samples.size) {
        "The last lap ends at ${laps.last().endSeconds}, but the ride is ${samples.size} s long"
    }
}
