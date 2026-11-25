package app.overdrive.core.fit

import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordBuilder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
import app.overdrive.core.record.SYNTHETIC_START_MILLIS
import app.overdrive.core.record.SyntheticRide
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val START_UNIX = SYNTHETIC_START_MILLIS / 1000

/** Reads [bytes] with the test reader and checks them against the profile facts. */
internal fun parseFit(bytes: ByteArray): FitFile {
    val file = FitReader.parse(bytes)
    assertEquals(emptyList(), FitFacts.problems(file))
    return file
}

internal fun FitFile.unixSeconds(
    message: FitMessage,
    field: String,
): Long = FitReader.unixSeconds(checkNotNull(message.raw(field)) { "no $field" })

/**
 * The structure and the content of the file written for the synthetic ride, read with the independent reader:
 * header, checksums, message order, and every value against the record it was written from.
 */
class FitWriterTest {
    private val record = SyntheticRide.record()
    private val bytes = FitWriter().write(record)
    private val file = parseFit(bytes)

    @Test
    fun `the header and the checksums are right`() {
        assertEquals(14, file.headerSize)
        assertEquals(0x20, file.protocolVersion)
        assertEquals(21222, file.profileVersion)
        assertEquals(bytes.size, file.headerSize + file.dataSize + 2)
        assertEquals(
            FitReader.crc16(bytes, 0, 12),
            (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8),
        )
        assertEquals(0, FitReader.crc16(bytes, 0, bytes.size))
    }

    @Test
    fun `messages come in the order of fit md section 5`() {
        val names = file.messages.map { it.name }
        assertEquals("file_id", names.first())
        assertEquals(listOf("session", "activity"), names.takeLast(2))
        assertEquals("event", names[1])
        // After the last record: the timer-stop event, the last lap, then session and activity.
        val lastRecord = names.indexOfLast { it == "record" }
        assertEquals(listOf("event", "lap", "session", "activity"), names.drop(lastRecord + 1).filter { it != "hrv" })
        assertEquals(
            mapOf(
                "file_id" to 1,
                "event" to 2,
                "record" to 300,
                "lap" to record.laps.size,
                "session" to 1,
                "activity" to 1,
            ).plus("hrv" to file.messages("hrv").size),
            file.messages.groupingBy { it.name }.eachCount(),
        )
        // A lap comes right after the last record of its step; the first definition of a message precedes its data.
        val laps = file.messages.withIndex().filter { it.value.name == "lap" }
        for ((lapIndex, entry) in laps.dropLast(1).withIndex()) {
            val previous = file.messages.subList(0, entry.index).last { it.name == "record" }
            assertEquals(
                START_UNIX + record.laps[lapIndex].endSeconds - 1,
                FitReader.unixSeconds(checkNotNull(previous.raw("timestamp"))),
            )
        }
    }

    @Test
    fun `the file id names an activity created at the start of the ride`() {
        val id = file.messages("file_id").single()
        assertEquals(4, id.raw("type"))
        assertEquals(255, id.raw("manufacturer"))
        assertEquals(1, id.raw("product"))
        assertEquals(1, id.raw("serial_number"))
        assertEquals(START_UNIX, file.unixSeconds(id, "time_created"))
    }

    @Test
    fun `every second is a record with its readings gaps as invalid values`() {
        val records = file.messages("record")
        assertEquals(300, records.size)
        for ((index, fit) in records.withIndex()) {
            val sample = record.samples[index]
            assertEquals(
                START_UNIX + index,
                file.unixSeconds(fit, "timestamp"),
                "timestamp of second ${sample.timeOffsetSeconds}",
            )
            assertEquals(sample.power?.toLong(), fit.raw("power"), "power at ${sample.timeOffsetSeconds}")
            assertEquals(sample.heartRate?.toLong(), fit.raw("heart_rate"), "heart rate at ${sample.timeOffsetSeconds}")
            assertEquals(sample.cadence?.toLong(), fit.raw("cadence"), "cadence at ${sample.timeOffsetSeconds}")
            // A gap is present in the message, as the base type's invalid value.
            assertTrue(fit.has("power") && fit.has("heart_rate") && fit.has("cadence"))
            assertEquals(sample.power == null, fit.field("power")!!.isInvalid)
            assertEquals(sample.heartRate == null, fit.field("heart_rate")!!.isInvalid)
            assertEquals(sample.cadence == null, fit.field("cadence")!!.isInvalid)
            val speed = checkNotNull(fit.scaled("speed"))
            assertEquals(speed, fit.scaled("enhanced_speed"))
            assertTrue(abs(speed - sample.speedKmh / 3.6) <= 0.0005 + 1e-9, "speed at ${sample.timeOffsetSeconds}")
            assertTrue(abs(checkNotNull(fit.scaled("distance")) - sample.distanceMeters) <= 0.005 + 1e-9)
        }
        // The synthetic ride really has every kind of gap, or the loop above proves less than it seems.
        assertTrue(records.any { it.field("heart_rate")!!.isInvalid })
        assertTrue(records.any { it.field("power")!!.isInvalid })
        assertTrue(records.any { it.field("cadence")!!.isInvalid })
    }

    @Test
    fun `paused seconds are records too as sampled`() {
        val paused = record.samples.filter { it.paused }
        assertTrue(paused.isNotEmpty())
        val records = file.messages("record")
        for (sample in paused) {
            val fit = records[sample.timeOffsetSeconds - 1]
            assertEquals(0.0, fit.scaled("speed"))
            assertEquals(sample.power?.toLong(), fit.raw("power"))
        }
    }

    @Test
    fun `there is one lap per workout step covering exactly its seconds`() {
        val laps = file.messages("lap")
        assertEquals(listOf(0, 1, 2, 3), record.laps.map { it.stepIndex })
        assertEquals(record.laps.size, laps.size)
        for ((index, fit) in laps.withIndex()) {
            val lap = record.laps[index]
            assertEquals(index.toLong(), fit.raw("message_index"))
            assertEquals(START_UNIX + lap.startSeconds, file.unixSeconds(fit, "start_time"))
            assertEquals(START_UNIX + lap.endSeconds - 1, file.unixSeconds(fit, "timestamp"))
            assertEquals(lap.durationSeconds * 1000.0 / 1000, fit.scaled("total_elapsed_time"))
            assertEquals(fit.scaled("total_elapsed_time"), fit.scaled("total_timer_time"))
            assertEquals(lap.averagePower?.roundToLong(), fit.raw("avg_power"))
            assertEquals(lap.averageHeartRate?.roundToLong(), fit.raw("avg_heart_rate"))
            assertEquals(2, fit.raw("sport"))
            assertEquals(58, fit.raw("sub_sport"))
            assertEquals(if (index == laps.size - 1) 7L else 0L, fit.raw("lap_trigger"))
            val seconds = record.samples.subList(lap.startSeconds, lap.endSeconds).filter { !it.paused }
            assertEquals(seconds.mapNotNull { it.power }.maxOrNull()?.toLong(), fit.raw("max_power"))
            assertEquals(seconds.mapNotNull { it.heartRate }.maxOrNull()?.toLong(), fit.raw("max_heart_rate"))
            assertEquals(seconds.mapNotNull { it.cadence }.average().roundToLong(), fit.raw("avg_cadence"))
        }
        // The laps' distances add up to the ride's, within a centimetre each.
        val total = laps.sumOf { checkNotNull(it.scaled("total_distance")) }
        assertTrue(abs(total - record.summary.distanceMeters) <= 0.005 * laps.size + 1e-9)
    }

    @Test
    fun `the session is cycling virtual activity with the summary and the load`() {
        val session = file.messages("session").single()
        val summary = record.summary
        assertEquals(2, session.raw("sport"))
        assertEquals(58, session.raw("sub_sport"))
        assertEquals(START_UNIX, file.unixSeconds(session, "start_time"))
        assertEquals(START_UNIX + 299, file.unixSeconds(session, "timestamp"))
        assertEquals(300.0, session.scaled("total_elapsed_time"))
        assertEquals(300.0, session.scaled("total_timer_time"))
        assertEquals(summary.distanceMeters, checkNotNull(session.scaled("total_distance")), 0.005)
        assertEquals(summary.averagePower?.roundToLong(), session.raw("avg_power"))
        assertEquals(summary.maxPower?.toLong(), session.raw("max_power"))
        assertEquals(summary.averageHeartRate?.roundToLong(), session.raw("avg_heart_rate"))
        assertEquals(summary.maxHeartRate?.toLong(), session.raw("max_heart_rate"))
        assertEquals(0, session.raw("first_lap_index"))
        assertEquals(record.laps.size.toLong(), session.raw("num_laps"))
        assertEquals(0, session.raw("trigger"))
        assertEquals(checkNotNull(summary.normalizedPower).roundToLong(), session.raw("normalized_power"))
        assertEquals(
            checkNotNull(summary.intensityFactor) * 1000,
            checkNotNull(session.raw("intensity_factor")).toDouble(),
            0.5,
        )
        assertEquals(
            checkNotNull(summary.trainingStressScore),
            checkNotNull(session.scaled("training_stress_score")),
            0.05,
        )
        assertEquals(summary.workKilojoules * 1000, checkNotNull(session.raw("total_work")).toDouble(), 0.5)
    }

    @Test
    fun `the activity closes the file with its time and the local time`() {
        val activity = file.messages("activity").single()
        assertEquals(1, activity.raw("num_sessions"))
        assertEquals(26, activity.raw("event"))
        assertEquals(1, activity.raw("event_type"))
        assertEquals(300.0, activity.scaled("total_timer_time"))
        assertEquals(START_UNIX + 299, file.unixSeconds(activity, "timestamp"))
        assertEquals(START_UNIX + 299, file.unixSeconds(activity, "local_timestamp")) // UTC by default
    }

    @Test
    fun `the timer starts at the start time and stops at the last record`() {
        val events = file.messages("event")
        assertEquals(listOf(0L, 0L), events.map { it.raw("event") })
        assertEquals(listOf(0L, 4L), events.map { it.raw("event_type") })
        assertEquals(START_UNIX, file.unixSeconds(events[0], "timestamp"))
        assertEquals(START_UNIX + 299, file.unixSeconds(events[1], "timestamp"))
    }

    @Test
    fun `RR intervals are hrv messages after the record of the second they arrived in`() {
        // Walk the stream: the RR intervals seen since the previous record belong to that record's second.
        val found = mutableListOf<RrInterval>()
        var second = 0
        for (message in file.messages) {
            when (message.name) {
                "record" -> second++
                "hrv" -> found += message.field("time")!!.elements.map { RrInterval(second, it.toInt()) }
            }
        }
        assertEquals(record.rrIntervals, found)
        assertTrue(found.map { it.timeOffsetSeconds }.toSet().size > 100)
    }

    @Test
    fun `an hrv definition is written only when the number of intervals changes`() {
        val hrvDefinitions = file.definitions.filter { it.globalNumber == 78 }
        val sizes = file.messages("hrv").map { it.field("time")!!.elements.size }
        val changes = 1 + sizes.zipWithNext().count { (a, b) -> a != b }
        assertEquals(changes, hrvDefinitions.size)
        assertEquals(setOf(1, 2, 3), sizes.toSet())
    }

    @Test
    fun `the same record gives the same bytes every time`() {
        assertContentEquals(bytes, FitWriter().write(SyntheticRide.record()))
        assertContentEquals(bytes, FitWriter(FitOptions()).write(record))
    }

    @Test
    fun `the file can be read back into the readings of the record`() {
        val rebuilt = rideFromFit(file)
        val quantised =
            record.samples.map {
                RideSample(
                    timeOffsetSeconds = it.timeOffsetSeconds,
                    power = it.power,
                    heartRate = it.heartRate,
                    cadence = it.cadence,
                    speedKmh = (it.speedKmh / 3.6 * 1000).roundToLong() / 1000.0 * 3.6,
                    distanceMeters = (it.distanceMeters * 100).roundToLong() / 100.0,
                )
            }
        assertEquals(quantised, rebuilt.samples)
        assertEquals(record.rrIntervals, rebuilt.rrIntervals)
        assertEquals(
            record.laps.map { it.startSeconds to it.endSeconds },
            rebuilt.laps.map {
                it.startSeconds to
                    it.endSeconds
            },
        )
        assertEquals(record.ride.startEpochMillis, rebuilt.ride.startEpochMillis)
        assertEquals(record.ride.startEpochMillis + 300_000, rebuilt.ride.endEpochMillis)
        assertEquals(record.summary.durationSeconds, rebuilt.summary.durationSeconds)
    }

    @Test
    fun `a ride that has been through JSON gives the same file`() {
        val viaJson =
            app.overdrive.core.record.RideRecordJson.decode(
                app.overdrive.core.record.RideRecordJson
                    .encode(record),
            )
        assertContentEquals(bytes, FitWriter().write(viaJson))
    }

    @Test
    fun `a ride with nothing measured is all gaps`() {
        val samples = (1..40).map { RideSample(it, speedKmh = 0.0, distanceMeters = 0.0, paused = it > 20) }
        val ride = RideRecordBuilder.recover(SyntheticRide.header, samples)
        val fit = parseFit(FitWriter().write(ride))
        assertTrue(fit.messages("record").all { it.field("power")!!.isInvalid && it.field("heart_rate")!!.isInvalid })
        val lap = fit.messages("lap").single()
        assertNull(lap.raw("avg_power"))
        assertNull(lap.raw("avg_heart_rate"))
        assertNull(lap.raw("max_power"))
        assertNull(lap.raw("avg_cadence"))
        val session = fit.messages("session").single()
        assertNull(session.raw("avg_power"))
        assertFalse(session.has("normalized_power"))
    }

    @Test
    fun `a short ride has no load fields a longer one has them`() {
        val short = RideRecordBuilder.recover(SyntheticRide.header, SyntheticRide.samples().take(20))
        assertFalse(parseFit(FitWriter().write(short)).messages("session").single().has("normalized_power"))
        assertNotNull(file.messages("session").single().raw("normalized_power"))
    }

    @Test
    fun `a ride of one second can be exported`() {
        val one = RideRecordBuilder.recover(SyntheticRide.header, SyntheticRide.samples().take(1))
        val fit = parseFit(FitWriter().write(one))
        assertEquals(1, fit.messages("record").size)
        assertEquals(1.0, fit.messages("lap").single().scaled("total_elapsed_time"))
        assertEquals(START_UNIX, fit.unixSeconds(fit.messages("session").single(), "timestamp"))
    }

    @Test
    fun `values beyond what a field holds are held at its largest never the invalid value`() {
        val sample =
            RideSample(1, power = 70_000, heartRate = 300, cadence = 999, speedKmh = 900.0, distanceMeters = 5.0e7)
        val fit = parseFit(FitWriter().write(RideRecordBuilder.recover(SyntheticRide.header, listOf(sample))))
        val fitRecord = fit.messages("record").single()
        assertEquals(65_534, fitRecord.raw("power"))
        assertEquals(254, fitRecord.raw("heart_rate"))
        assertEquals(254, fitRecord.raw("cadence"))
        assertEquals(65_534, fitRecord.raw("speed"))
        assertEquals(250_000, fitRecord.raw("enhanced_speed")) // 900 km/h = 250 m/s, 32 bits hold it
        assertEquals(4_294_967_294L, fitRecord.raw("distance"))
    }

    @Test
    fun `RR intervals the field cannot hold are invalid and a crowd of them is split`() {
        val samples = SyntheticRide.samples().take(3)
        val intervals = listOf(RrInterval(2, 70_000)) + List(250) { RrInterval(3, 800 + it) } + RrInterval(9, 640)
        val fit = parseFit(FitWriter().write(RideRecordBuilder.recover(SyntheticRide.header, samples, intervals)))
        val hrv = fit.messages("hrv")
        assertTrue(hrv[0].field("time")!!.isInvalid)
        assertEquals(listOf(1, 100, 100, 50, 1), hrv.map { it.field("time")!!.elements.size })
        // The interval stamped after the last sample goes after the last record, before the timer stops.
        val afterLastRecord = fit.messages.map { it.name }.let { it.drop(it.lastIndexOf("record") + 1) }
        assertEquals(listOf("hrv", "hrv", "hrv", "hrv", "event"), afterLastRecord.take(5))
    }

    @Test
    fun `an empty ride or one that FIT cannot date is refused`() {
        assertFailsWith<IllegalArgumentException> {
            FitWriter().write(RideRecordBuilder.recover(SyntheticRide.header, emptyList()))
        }
        val early = SyntheticRide.header.copy(startEpochMillis = 1_000)
        assertFailsWith<IllegalArgumentException> {
            FitWriter().write(RideRecordBuilder.recover(early, SyntheticRide.samples().take(5)))
        }
    }

    @Test
    fun `a record that is not consistent is refused`() {
        val broken: RideRecord = record.copy(laps = record.laps.drop(1))
        assertFailsWith<IllegalArgumentException> { FitWriter().write(broken) }
    }
}
