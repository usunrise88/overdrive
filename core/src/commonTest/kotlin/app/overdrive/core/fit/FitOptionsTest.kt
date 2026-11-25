package app.overdrive.core.fit

import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.SYNTHETIC_START_MILLIS
import app.overdrive.core.record.SyntheticRide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val START_UNIX = SYNTHETIC_START_MILLIS / 1000

/**
 * Each open question of `docs/protocols/fit.md` section 8 is one option of [FitOptions]. These tests change one option at
 * a time and check that exactly that choice moves, so that a bench finding really is a one-line change of a default.
 */
class FitOptionsTest {
    private val record: RideRecord = SyntheticRide.record()

    private fun write(options: FitOptions): FitFile = parseFit(FitWriter(options).write(record))

    private val baseline = write(FitOptions())

    @Test
    fun `the defaults are what fit md recommends`() {
        val defaults = FitOptions()
        assertEquals(0x20, defaults.protocolVersion)
        assertEquals(58, defaults.subSport)
        assertEquals(2, defaults.sport)
        assertEquals(FitSpeedFields.BOTH, defaults.speedFields)
        assertEquals(FitHrvPlacement.AFTER_RECORD, defaults.hrvPlacement)
        assertEquals(255, defaults.manufacturer)
        assertTrue(defaults.timerEvents)
        assertEquals(0, defaults.stepLapTrigger)
        assertEquals(FitRecordStamp.SECOND_START, defaults.recordStamp)
        assertEquals(0, defaults.utcOffsetSeconds)
        assertTrue(defaults.loadFields)
    }

    @Test
    fun `8_1 protocol version is the header byte`() {
        val file = write(FitOptions(protocolVersion = 0x02))
        assertEquals(0x02, file.protocolVersion)
        assertEquals(baseline.messages.size, file.messages.size)
    }

    @Test
    fun `8_2 sub-sport and sport are those of the laps and the session`() {
        val file = write(FitOptions(subSport = FitOptions.SUB_SPORT_INDOOR_CYCLING))
        assertEquals(6, file.messages("session").single().raw("sub_sport"))
        assertTrue(file.messages("lap").all { it.raw("sub_sport") == 6L })
        assertEquals(2, file.messages("session").single().raw("sport"))
    }

    @Test
    fun `8_3 speed fields can be one or both`() {
        val both = baseline.messages("record").first()
        assertTrue(both.has("speed") && both.has("enhanced_speed"))
        val speedOnly = write(FitOptions(speedFields = FitSpeedFields.SPEED)).messages("record")
        assertTrue(speedOnly.all { it.has("speed") && !it.has("enhanced_speed") })
        val enhancedOnly = write(FitOptions(speedFields = FitSpeedFields.ENHANCED_SPEED)).messages("record")
        assertTrue(enhancedOnly.all { !it.has("speed") && it.has("enhanced_speed") })
        // Same speeds either way.
        assertEquals(baseline.messages("record").map { it.raw("speed") }, speedOnly.map { it.raw("speed") })
        assertEquals(
            baseline.messages("record").map {
                it.raw("enhanced_speed")
            },
            enhancedOnly.map { it.raw("enhanced_speed") },
        )
    }

    @Test
    fun `8_4 hrv goes before or after the record of its second`() {
        val before = write(FitOptions(hrvPlacement = FitHrvPlacement.BEFORE_RECORD))
        var second = 0
        val seconds = mutableListOf<Int>()
        for (message in before.messages) {
            when (message.name) {
                "record" -> second++
                "hrv" -> seconds += second + 1 // the record that follows
            }
        }
        assertEquals(record.rrIntervals.groupBy { it.timeOffsetSeconds }.map { it.key }, seconds.distinct())
        // The first hrv of the ride is at second 1; before the first record there is one, after it there is none.
        val names = before.messages.map { it.name }
        assertEquals(listOf("event", "hrv", "record"), names.subList(1, 4))
        assertEquals(listOf("event", "record"), baseline.messages.map { it.name }.subList(1, 3))
    }

    @Test
    fun `8_5 manufacturer product and serial number are the file id`() {
        val id =
            write(
                FitOptions(manufacturer = 1, product = 3_000, serialNumber = 4_000_000_000L),
            ).messages("file_id").single()
        assertEquals(1, id.raw("manufacturer"))
        assertEquals(3_000, id.raw("product"))
        assertEquals(4_000_000_000L, id.raw("serial_number"))
        assertFailsWith<IllegalArgumentException> { FitOptions(serialNumber = 0) }
    }

    @Test
    fun `8_6 the timer events can be left out the activity stays`() {
        val file = write(FitOptions(timerEvents = false))
        assertEquals(0, file.messages("event").size)
        assertFalse(file.definitions.any { it.globalNumber == 21 })
        assertEquals(1, file.messages("activity").size)
        assertEquals("record", file.messages[1].name)
    }

    @Test
    fun `the lap trigger of the step laps is a choice the last lap always ends the session`() {
        val file = write(FitOptions(stepLapTrigger = 1))
        val triggers = file.messages("lap").map { it.raw("lap_trigger") }
        assertEquals(listOf(1L, 1L, 1L, 7L), triggers)
    }

    @Test
    fun `a record can be stamped at the end of its second`() {
        val file = write(FitOptions(recordStamp = FitRecordStamp.SECOND_END))
        val records = file.messages("record")
        assertEquals(START_UNIX + 1, file.unixSeconds(records.first(), "timestamp"))
        assertEquals(START_UNIX + 300, file.unixSeconds(records.last(), "timestamp"))
        // Laps, session, activity and the stop event end at the end of the ride, and start where the ride started.
        val lap = file.messages("lap")[1]
        assertEquals(START_UNIX + record.laps[1].endSeconds, file.unixSeconds(lap, "timestamp"))
        assertEquals(START_UNIX + record.laps[1].startSeconds, file.unixSeconds(lap, "start_time"))
        assertEquals(START_UNIX + 300, file.unixSeconds(file.messages("session").single(), "timestamp"))
        assertEquals(START_UNIX, file.unixSeconds(file.messages("session").single(), "start_time"))
        assertEquals(START_UNIX + 300, file.unixSeconds(file.messages("event").last(), "timestamp"))
        // Timestamp minus start is the elapsed time then.
        assertEquals(300.0, file.messages("session").single().scaled("total_elapsed_time"))
    }

    @Test
    fun `local time is the end timestamp plus the offset the caller gives`() {
        val file = write(FitOptions(utcOffsetSeconds = 3_600))
        val activity = file.messages("activity").single()
        assertEquals(START_UNIX + 299 + 3_600, file.unixSeconds(activity, "local_timestamp"))
        assertEquals(START_UNIX + 299, file.unixSeconds(activity, "timestamp"))
        // West of UTC works too.
        val west = write(FitOptions(utcOffsetSeconds = -5 * 3_600)).messages("activity").single()
        assertEquals(START_UNIX + 299 - 5 * 3_600, FitReader.unixSeconds(checkNotNull(west.raw("local_timestamp"))))
    }

    @Test
    fun `the load fields of the session can be left out`() {
        assertTrue(baseline.messages("session").single().has("normalized_power"))
        val session = write(FitOptions(loadFields = false)).messages("session").single()
        for (field in listOf("normalized_power", "training_stress_score", "intensity_factor", "total_work")) {
            assertFalse(session.has(field), field)
        }
    }

    @Test
    fun `options that FIT cannot hold are refused`() {
        assertFailsWith<IllegalArgumentException> { FitOptions(protocolVersion = 256) }
        assertFailsWith<IllegalArgumentException> { FitOptions(subSport = -1) }
        assertFailsWith<IllegalArgumentException> { FitOptions(profileVersion = 70_000) }
    }
}
