package app.overdrive.core.fit

import app.overdrive.core.record.Lap
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.requireConsistent
import kotlin.math.roundToLong

/**
 * Writes a [RideRecord] as a FIT activity file (spec §4.8): our own implementation of Garmin's published format,
 * following `docs/protocols/fit.md`. No part of Garmin's SDK is used or needed. The output is deterministic: the same
 * record and options always give the same bytes, since nothing in the file depends on the clock.
 *
 * ```kotlin
 * val bytes = FitWriter(FitOptions(utcOffsetSeconds = offsetOfTheTabletsTimeZone)).write(record)
 * ```
 *
 * **What the file holds**, in order: `file_id`, a timer-start `event`, one `record` per second of the ride (power, heart
 * rate, cadence, speed, distance), an `hrv` message after the record of each second in which RR intervals arrived, a
 * `lap` after the last record of each workout step, a timer-stop `event`, the last `lap`, one `session` (sport
 * cycling, sub-sport virtual activity) and one `activity`. A reading that was not measured is written as its base
 * type's invalid value, which readers show as a gap. The record's seconds are all written, paused ones too, as sampled.
 *
 * **Times.** The sample of second *k* is stamped with the start of that second (see [FitRecordStamp]); every lap and
 * the session cover whole seconds; elapsed and timer time are both the length of the ride, because pauses are recorded
 * rather than cut out. Distances and speeds are the model's, rounded to centimetres and millimetres per second.
 *
 * **What is computed here** because the record has no field for it: each lap's and the session's maximum heart rate,
 * average cadence and maximum power, over the seconds that were not paused and had a reading (the definition of the
 * record's own summary); each lap's distance. Averages of power and heart rate, normalised power, load and work come
 * from the record.
 *
 * Not verified against a real importer: see `docs/adr/0007-ride-log-and-fit.md`.
 */
public class FitWriter(
    private val options: FitOptions = FitOptions(),
) {
    /**
     * The FIT file for [record].
     *
     * @throws IllegalArgumentException if the record has no samples or is not self-consistent, or if its start time
     * cannot be a FIT date.
     */
    public fun write(record: RideRecord): ByteArray = write(record, FitRoutes.None)

    /**
     * The FIT file for [record] with the places the ride went (spec §11.4, fit.md 4.3): the seconds that [routes] says followed a route
     * get its altitude and, when it has coordinates, a position, found at the distance ridden along it. With [FitRoutes.None] this is
     * [write] of [record] alone, byte for byte.
     *
     * @throws IllegalArgumentException as [write].
     */
    public fun write(
        record: RideRecord,
        routes: FitRoutes,
    ): ByteArray {
        require(record.samples.isNotEmpty()) { "A ride without samples has nothing to export" }
        record.requireConsistent()
        return Encoder(record, options, routeFixes(record, routes)).encode()
    }
}

/** Seconds between the Unix epoch and the FIT epoch, 1989-12-31T00:00:00Z (fit.md section 3). */
private const val FIT_EPOCH_UNIX_SECONDS = 631_065_600L

/** The smallest `date_time` that is a date (fit.md section 3): 1998-07-01. */
private const val FIT_DATE_TIME_MIN = 0x1000_0000L

private const val FIT_DATE_TIME_MAX = 0xFFFF_FFFEL

private const val MILLIS_PER_SECOND = 1000L
private const val KMH_PER_MS = 3.6
private const val HEADER_SIZE = 14
private const val HEADER_CRC_OFFSET = 12
private const val DATA_SIZE_OFFSET = 4
private const val MAX_HRV_PER_MESSAGE = 100

/** The largest RR interval in milliseconds a 16-bit `hrv` element can hold; a longer one is written as invalid. */
private const val HRV_MAX_MS = 0xFFFE

// Message numbers and enum values of the profile (fit.md 4).
private const val MESG_FILE_ID = 0
private const val MESG_SESSION = 18
private const val MESG_LAP = 19
private const val MESG_RECORD = 20
private const val MESG_EVENT = 21
private const val MESG_ACTIVITY = 34
private const val MESG_HRV = 78
private const val FILE_TYPE_ACTIVITY = 4
private const val EVENT_TIMER = 0
private const val EVENT_ACTIVITY = 26
private const val EVENT_TYPE_START = 0
private const val EVENT_TYPE_STOP = 1
private const val EVENT_TYPE_STOP_ALL = 4
private const val LAP_TRIGGER_SESSION_END = 7
private const val SESSION_TRIGGER_ACTIVITY_END = 0
private const val ACTIVITY_TYPE_MANUAL = 0

private const val SPEED_SCALE = 1000.0
private const val DISTANCE_SCALE = 100.0
private const val TSS_SCALE = 10.0
private const val IF_SCALE = 1000.0
private const val JOULES_PER_KILOJOULE = 1000.0

// Local message types, as in the worked example of fit.md section 6.
private const val LOCAL_FILE_ID = 0
private const val LOCAL_EVENT = 1
private const val LOCAL_RECORD = 2
private const val LOCAL_LAP = 3
private const val LOCAL_SESSION = 4
private const val LOCAL_ACTIVITY = 5
private const val LOCAL_HRV = 6

private val U8 = FitBaseType.UINT8
private val U16 = FitBaseType.UINT16
private val U32 = FitBaseType.UINT32
private val ENUM = FitBaseType.ENUM

private val FILE_ID =
    FitMessageType(
        LOCAL_FILE_ID,
        MESG_FILE_ID,
        listOf(
            FitField(0, ENUM), // type
            FitField(1, U16), // manufacturer
            FitField(2, U16), // product
            FitField(3, FitBaseType.UINT32Z), // serial_number
            FitField(4, U32), // time_created
        ),
    )

private val EVENT =
    FitMessageType(
        LOCAL_EVENT,
        MESG_EVENT,
        listOf(FitField(253, U32), FitField(0, ENUM), FitField(1, ENUM)), // timestamp, event, event_type
    )

private val LAP =
    FitMessageType(
        LOCAL_LAP,
        MESG_LAP,
        listOf(
            FitField(253, U32), // timestamp
            FitField(2, U32), // start_time
            FitField(7, U32), // total_elapsed_time
            FitField(8, U32), // total_timer_time
            FitField(9, U32), // total_distance
            FitField(15, U8), // avg_heart_rate
            FitField(16, U8), // max_heart_rate
            FitField(17, U8), // avg_cadence
            FitField(19, U16), // avg_power
            FitField(20, U16), // max_power
            FitField(24, ENUM), // lap_trigger
            FitField(25, ENUM), // sport
            FitField(39, ENUM), // sub_sport
            FitField(254, U16), // message_index
        ),
    )

private val SESSION_BASE =
    listOf(
        FitField(253, U32), // timestamp
        FitField(2, U32), // start_time
        FitField(5, ENUM), // sport
        FitField(6, ENUM), // sub_sport
        FitField(7, U32), // total_elapsed_time
        FitField(8, U32), // total_timer_time
        FitField(9, U32), // total_distance
        FitField(16, U8), // avg_heart_rate
        FitField(17, U8), // max_heart_rate
        FitField(18, U8), // avg_cadence
        FitField(20, U16), // avg_power
        FitField(21, U16), // max_power
        FitField(25, U16), // first_lap_index
        FitField(26, U16), // num_laps
        FitField(28, ENUM), // trigger
    )

private val SESSION_LOAD =
    listOf(
        FitField(34, U16), // normalized_power
        FitField(35, U16), // training_stress_score
        FitField(36, U16), // intensity_factor
        FitField(48, U32), // total_work
    )

private val ACTIVITY =
    FitMessageType(
        LOCAL_ACTIVITY,
        MESG_ACTIVITY,
        listOf(
            FitField(253, U32), // timestamp
            FitField(0, U32), // total_timer_time
            FitField(1, U16), // num_sessions
            FitField(2, ENUM), // type
            FitField(3, ENUM), // event
            FitField(4, ENUM), // event_type
            FitField(5, U32), // local_timestamp
        ),
    )

/** What the readings of a stretch of samples add up to that the record's laps and summary do not carry. */
private class Readings(
    samples: List<RideSample>,
    from: Int,
    to: Int,
) {
    var maxHeartRate: Int? = null
    var averageCadence: Double? = null
    var maxPower: Int? = null

    init {
        var cadenceCount = 0
        var cadenceSum = 0L
        for (index in from until to) {
            val sample = samples[index]
            if (sample.paused) continue
            sample.heartRate?.let { if (maxHeartRate?.let { max -> it > max } != false) maxHeartRate = it }
            sample.power?.let { if (maxPower?.let { max -> it > max } != false) maxPower = it }
            sample.cadence?.let {
                cadenceCount++
                cadenceSum += it
            }
        }
        if (cadenceCount > 0) averageCadence = cadenceSum.toDouble() / cadenceCount
    }
}

private class Encoder(
    private val record: RideRecord,
    private val options: FitOptions,
    private val fixes: Array<RouteFix?>,
) {
    private val samples = record.samples

    /** Whether the records carry a position: some second followed a route that has coordinates. */
    private val withPositions = options.positions && fixes.any { it?.position != null }

    /** Whether the records carry an altitude: some second followed a route. */
    private val withAltitude = options.altitude && fixes.any { it != null }
    private val startFit = fitTime(record.ride.startEpochMillis)
    private val stampShift = if (options.recordStamp == FitRecordStamp.SECOND_START) 1 else 0
    private val lastTimestamp = timestampOf(samples.size)
    private val elapsedMillis = samples.size * MILLIS_PER_SECOND
    private val out = FitBuffer()

    init {
        require(startFit in FIT_DATE_TIME_MIN..FIT_DATE_TIME_MAX && lastTimestamp <= FIT_DATE_TIME_MAX) {
            "FIT cannot date a ride that starts at ${record.ride.startEpochMillis} ms since 1970"
        }
    }

    fun encode(): ByteArray {
        repeat(HEADER_SIZE) { out.u8(0) } // patched below, once the size is known
        writeFileId()
        if (options.timerEvents) {
            out.definition(EVENT)
            out.data(EVENT, startFit, EVENT_TIMER.toLong(), EVENT_TYPE_START.toLong())
        }
        writeRecordsAndLaps()
        if (options.timerEvents) {
            out.data(EVENT, lastTimestamp, EVENT_TIMER.toLong(), EVENT_TYPE_STOP_ALL.toLong())
        }
        writeLap(record.laps.size - 1)
        writeSession()
        writeActivity()
        return finish()
    }

    private fun fitTime(epochMillis: Long): Long = epochMillis.floorDiv(MILLIS_PER_SECOND) - FIT_EPOCH_UNIX_SECONDS

    /** The timestamp of the sample or the lap end at offset [seconds]. */
    private fun timestampOf(seconds: Int): Long = startFit + seconds - stampShift

    private fun writeFileId() {
        out.definition(FILE_ID)
        out.data(
            FILE_ID,
            FILE_TYPE_ACTIVITY.toLong(),
            options.manufacturer.toLong(),
            options.product.toLong(),
            options.serialNumber,
            startFit,
        )
    }

    private fun writeRecordsAndLaps() {
        val recordType = recordType()
        out.definition(recordType)
        val hrv = HrvWriter()
        val inRide = record.rrIntervals.filter { it.timeOffsetSeconds <= samples.size }
        val intervalsBySecond = inRide.groupBy({ it.timeOffsetSeconds }, { it.intervalMs })
        val lastLapIndex = record.laps.size - 1
        var nextLap = 0
        for (sample in samples) {
            val second = sample.timeOffsetSeconds
            val intervals = intervalsBySecond[second].orEmpty()
            if (options.hrvPlacement == FitHrvPlacement.BEFORE_RECORD) hrv.write(intervals)
            writeRecord(recordType, sample)
            if (options.hrvPlacement == FitHrvPlacement.AFTER_RECORD) hrv.write(intervals)
            // A lap ends after its last record. The last lap comes after the timer-stop event instead.
            while (nextLap < lastLapIndex && record.laps[nextLap].endSeconds == second) writeLap(nextLap++)
        }
        // Intervals stamped after the last sample belong to no record; they go after the last one.
        hrv.write(record.rrIntervals.filter { it.timeOffsetSeconds > samples.size }.map { it.intervalMs })
    }

    private fun recordType(): FitMessageType {
        val fields = mutableListOf(FitField(253, U32), FitField(3, U8), FitField(4, U8), FitField(5, U32))
        if (options.speedFields != FitSpeedFields.ENHANCED_SPEED) fields += FitField(6, U16)
        fields += FitField(7, U16)
        if (options.speedFields != FitSpeedFields.SPEED) fields += FitField(73, U32)
        // The places of a ride on a route, after the fields every file has, so a ride without one is laid out as it always was.
        if (withPositions) fields += listOf(FitField(0, FitBaseType.SINT32), FitField(1, FitBaseType.SINT32))
        if (withAltitude) fields += listOf(FitField(2, U16), FitField(78, U32))
        return FitMessageType(LOCAL_RECORD, MESG_RECORD, fields)
    }

    private fun writeRecord(
        type: FitMessageType,
        sample: RideSample,
    ) {
        val speed = (sample.speedKmh / KMH_PER_MS * SPEED_SCALE).roundToLong()
        val values = mutableListOf<Long?>(timestampOf(sample.timeOffsetSeconds))
        values += sample.heartRate?.toLong()
        values += sample.cadence?.toLong()
        values += (sample.distanceMeters * DISTANCE_SCALE).roundToLong()
        if (options.speedFields != FitSpeedFields.ENHANCED_SPEED) values += speed
        values += sample.power?.toLong()
        if (options.speedFields != FitSpeedFields.SPEED) values += speed
        val fix = fixes[sample.timeOffsetSeconds - 1]
        if (withPositions) {
            values += fix?.position?.let { semicircles(it.latitude) }
            values += fix?.position?.let { semicircles(it.longitude) }
        }
        if (withAltitude) {
            val altitude = fix?.let { rawAltitude(it.altitudeMeters) }
            values += altitude
            values += altitude
        }
        out.data(type, *values.toTypedArray())
    }

    /** `hrv` messages, each with its own array length, so the definition is repeated whenever the length changes. */
    private inner class HrvWriter {
        private var definedLength = 0

        fun write(intervals: List<Int>) {
            for (chunk in intervals.chunked(MAX_HRV_PER_MESSAGE)) {
                val type = FitMessageType(LOCAL_HRV, MESG_HRV, listOf(FitField(0, U16, chunk.size)))
                if (chunk.size != definedLength) {
                    out.definition(type)
                    definedLength = chunk.size
                }
                out.dataArray(type, chunk.map { if (it > HRV_MAX_MS) null else it.toLong() })
            }
        }
    }

    private fun writeLap(index: Int) {
        val lap: Lap = record.laps[index]
        if (index == 0) out.definition(LAP)
        val readings = Readings(samples, lap.startSeconds, lap.endSeconds)
        val startDistance = if (lap.startSeconds == 0) 0.0 else samples[lap.startSeconds - 1].distanceMeters
        val distance = samples[lap.endSeconds - 1].distanceMeters - startDistance
        val millis = lap.durationSeconds * MILLIS_PER_SECOND
        out.data(
            LAP,
            timestampOf(lap.endSeconds),
            startFit + lap.startSeconds,
            millis,
            millis,
            (distance * DISTANCE_SCALE).roundToLong(),
            lap.averageHeartRate?.roundToLong(),
            readings.maxHeartRate?.toLong(),
            readings.averageCadence?.roundToLong(),
            lap.averagePower?.roundToLong(),
            readings.maxPower?.toLong(),
            (if (index == record.laps.size - 1) LAP_TRIGGER_SESSION_END else options.stepLapTrigger).toLong(),
            options.sport.toLong(),
            options.subSport.toLong(),
            index.toLong(),
        )
    }

    private fun writeSession() {
        val summary = record.summary
        val withLoad = options.loadFields && summary.normalizedPower != null
        val type =
            FitMessageType(LOCAL_SESSION, MESG_SESSION, if (withLoad) SESSION_BASE + SESSION_LOAD else SESSION_BASE)
        out.definition(type)
        val readings = Readings(samples, 0, samples.size)
        val values =
            mutableListOf<Long?>(
                lastTimestamp,
                startFit,
                options.sport.toLong(),
                options.subSport.toLong(),
                elapsedMillis,
                elapsedMillis,
                (summary.distanceMeters * DISTANCE_SCALE).roundToLong(),
                summary.averageHeartRate?.roundToLong(),
                summary.maxHeartRate?.toLong(),
                readings.averageCadence?.roundToLong(),
                summary.averagePower?.roundToLong(),
                summary.maxPower?.toLong(),
                0,
                record.laps.size.toLong(),
                SESSION_TRIGGER_ACTIVITY_END.toLong(),
            )
        if (withLoad) {
            values += summary.normalizedPower?.roundToLong()
            values += summary.trainingStressScore?.let { (it * TSS_SCALE).roundToLong() }
            values += summary.intensityFactor?.let { (it * IF_SCALE).roundToLong() }
            values += (summary.workKilojoules * JOULES_PER_KILOJOULE).roundToLong()
        }
        out.data(type, *values.toTypedArray())
    }

    private fun writeActivity() {
        out.definition(ACTIVITY)
        out.data(
            ACTIVITY,
            lastTimestamp,
            elapsedMillis,
            1,
            ACTIVITY_TYPE_MANUAL.toLong(),
            EVENT_ACTIVITY.toLong(),
            EVENT_TYPE_STOP.toLong(),
            lastTimestamp + options.utcOffsetSeconds,
        )
    }

    /** Fills in the header, whose size and CRC are known now, and appends the CRC of the whole file. */
    private fun finish(): ByteArray {
        val dataSize = out.size - HEADER_SIZE
        out.patch(0, HEADER_SIZE.toLong(), 1)
        out.patch(1, options.protocolVersion.toLong(), 1)
        out.patch(2, options.profileVersion.toLong(), 2)
        out.patch(DATA_SIZE_OFFSET, dataSize.toLong(), 4)
        for ((i, c) in FIT_SIGNATURE.withIndex()) out.patch(8 + i, c.code.toLong(), 1)
        out.patch(HEADER_CRC_OFFSET, out.crc(HEADER_CRC_OFFSET).toLong(), 2)
        val fileCrc = out.crc(out.size)
        out.uint(fileCrc.toLong(), 2)
        return out.toByteArray()
    }

    private companion object {
        const val FIT_SIGNATURE = ".FIT"
    }
}
