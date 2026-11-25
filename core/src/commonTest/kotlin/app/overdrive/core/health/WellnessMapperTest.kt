package app.overdrive.core.health

import app.overdrive.core.wellness.ExerciseSessionRecord
import app.overdrive.core.wellness.HeartRateSamplesRecord
import app.overdrive.core.wellness.RestingHeartRateRecord
import app.overdrive.core.wellness.SleepSessionRecord
import app.overdrive.core.wellness.SleepStage
import app.overdrive.core.wellness.StepsRecord
import app.overdrive.core.wellness.WeightRecord
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WellnessMapperTest {
    @Test
    fun `instants are written the way the service reads them to the millisecond`() {
        assertEquals("2026-11-18T07:00:00Z", WellnessTime.formatInstant(NOW))
        assertEquals("2026-11-18T07:00:00.123Z", WellnessTime.formatInstant(NOW + 123))
        assertEquals("1970-01-01T00:00:00Z", WellnessTime.formatInstant(0))
        assertEquals("1969-12-31T23:59:59.999Z", WellnessTime.formatInstant(-1))
        for (millis in listOf(0L, 1L, NOW, NOW + 999, 253_402_300_799_999L)) {
            assertEquals(millis, WellnessTime.parseInstant(WellnessTime.formatInstant(millis)))
        }
    }

    @Test
    fun `a Health Connect id the contract would refuse gets a safe id the same one every time`() {
        assertEquals("a1b2-C3_d.4", WellnessMapper.wireId("a1b2-C3_d.4"))
        assertEquals("a_b_c", WellnessMapper.wireId("a/b c"))
        assertEquals("h_leading", WellnessMapper.wireId("_leading"))
        assertEquals(100, WellnessMapper.wireId("x".repeat(300)).length)
        val mapped = WellnessMapper.map(listOf(HealthSamples.resting("id/with slash", NOW)))
        assertEquals("id_with_slash", mapped.records.single().id)
    }

    @Test
    fun `a record the contract would refuse is skipped and counted and the rest still go`() {
        val mapped =
            WellnessMapper.map(
                listOf(
                    WeightReading("w0", NOW, 0.0),
                    WeightReading("w1", NOW, 78.4),
                    StepsReading("s0", NOW, NOW - HOUR, 10),
                    RestingHeartRateReading("r0", NOW, 0),
                    RestingHeartRateReading("r1", NOW, 52),
                    SleepSessionReading("n0", NOW, NOW - HOUR),
                ),
            )
        assertEquals(listOf("w1", "r1"), mapped.records.map { it.id })
        assertEquals(4, mapped.skipped)
    }

    @Test
    fun `heart rate outside a night and an activity is not sent inside it is`() {
        val night = HealthSamples.night("sleep-1", NOW)
        val workout = HealthSamples.workout("ex-1", NOW + 2 * HOUR)
        val day = HealthSamples.heartRate("hr-day", NOW - 12 * HOUR, NOW + 4 * HOUR, bpm = SENTINEL_BPM)
        val mapped = WellnessMapper.map(listOf(night, workout, day))
        val series = mapped.records.filterIsInstance<HeartRateSamplesRecord>().single()
        assertEquals("hr-day", series.id)
        val inNight = series.samples.all { WellnessTime.parseInstant(it.t)!! in night.startMillis..night.endMillis }
        assertTrue(inNight, "only the night's samples")
        assertEquals(420 + 1, series.samples.size, "one a minute from the start to the end of the night, both included")
        val exercise = mapped.records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(41, assertNotNull(exercise.heartRateSamples).size, "the session carries its own samples")
        assertTrue(
            exercise.heartRateSamples!!.all {
                WellnessTime.parseInstant(it.t)!! in
                    workout.startMillis..workout.endMillis
            },
        )
    }

    @Test
    fun `heart rate with no session around it is not sent at all`() {
        val mapped = WellnessMapper.map(listOf(HealthSamples.heartRate("hr-day", NOW, NOW + 4 * HOUR)))
        assertTrue(mapped.records.isEmpty())
        assertEquals(0, mapped.skipped)
    }

    @Test
    fun `context explains a record without being sent`() {
        val night = HealthSamples.night("sleep-1", NOW)
        val hr = HealthSamples.heartRate("hr-1", NOW - 3 * HOUR, NOW - 2 * HOUR)
        val mapped = WellnessMapper.map(listOf(hr), context = listOf(night))
        assertEquals(listOf("hr-1"), mapped.records.map { it.id })
        assertEquals(
            60,
            mapped.records
                .filterIsInstance<HeartRateSamplesRecord>()
                .single()
                .samples.size,
        )
    }

    @Test
    fun `a heart rate record with more samples than a record holds is split under ids that stay the same`() {
        val night = HealthSamples.night("sleep-1", NOW, hours = 15)
        val many = HealthSamples.heartRate("hr-big", night.startMillis, night.endMillis, everyMillis = 1_000)
        val first = WellnessMapper.map(listOf(night, many)).records.filterIsInstance<HeartRateSamplesRecord>()
        val second = WellnessMapper.map(listOf(night, many)).records.filterIsInstance<HeartRateSamplesRecord>()
        assertEquals(listOf("hr-big", "hr-big.1", "hr-big.2"), first.map { it.id })
        assertTrue(first.all { it.samples.size <= 20_000 })
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `samples outside 1 to 300 are dropped not sent to refuse the batch`() {
        val night = HealthSamples.night("sleep-1", NOW)
        val at = night.startMillis
        val series =
            HeartRateSeries(
                "hr-1",
                at,
                night.endMillis,
                listOf(HeartRateTick(at + 1000, 0), HeartRateTick(at + 2000, 301), HeartRateTick(at + 3000, 60)),
            )
        val mapped =
            WellnessMapper
                .map(
                    listOf(night, series),
                ).records
                .filterIsInstance<HeartRateSamplesRecord>()
                .single()
        assertEquals(listOf(60), mapped.samples.map { it.bpm })
    }

    @Test
    fun `an exercise type that is not a token becomes other_workout a title is trimmed and an empty one dropped`() {
        val mapped =
            WellnessMapper
                .map(
                    listOf(
                        ExerciseSessionReading("e1", "Strength Training", NOW, NOW + HOUR, "  Legs  "),
                        ExerciseSessionReading("e2", "biking_stationary", NOW, NOW + HOUR, "   "),
                    ),
                ).records
                .filterIsInstance<ExerciseSessionRecord>()
        assertEquals("other_workout", mapped[0].type)
        assertEquals("Legs", mapped[0].title)
        assertEquals("biking_stationary", mapped[1].type)
        assertNull(mapped[1].title)
    }

    @Test
    fun `sleep stages are kept by name and in order and dropped when there are too many`() {
        val stages =
            SleepStage.entries.mapIndexed { index, stage ->
                SleepStageReading(stage, NOW + index * 1000L, NOW + (index + 1) * 1000L)
            }
        val ok = WellnessMapper.map(listOf(SleepSessionReading("n1", NOW, NOW + HOUR, null, stages))).records.single()
        assertEquals(SleepStage.entries.toList(), (ok as SleepSessionRecord).stages!!.map { it.stage })
        val tooMany = List(2001) { SleepStageReading(SleepStage.LIGHT, NOW, NOW + 1) }
        val dropped =
            WellnessMapper
                .map(
                    listOf(SleepSessionReading("n2", NOW, NOW + HOUR, null, tooMany)),
                ).records
                .single()
        assertNull((dropped as SleepSessionRecord).stages)
    }

    @Test
    fun `the same id twice in one call is one record the newer edit`() {
        val mapped =
            WellnessMapper.map(
                listOf(
                    RestingHeartRateReading("r1", NOW, 50, lastModifiedMillis = NOW + 1000),
                    RestingHeartRateReading("r1", NOW, 53, lastModifiedMillis = NOW + 2000),
                    RestingHeartRateReading("r1", NOW, 51, lastModifiedMillis = NOW),
                ),
            )
        assertEquals(53, (mapped.records.single() as RestingHeartRateRecord).bpm)
    }

    @Test
    fun `last modified and the zone offset go through and the kinds are named as the contract names them`() {
        val mapped =
            WellnessMapper
                .map(
                    listOf(
                        RestingHeartRateReading("r1", NOW, 52, NOW + 60_000, 3600),
                        StepsReading("s1", NOW, NOW + HOUR, 1234),
                        WeightReading("w1", NOW, 78.4),
                    ),
                ).records
        assertEquals("2026-11-18T07:01:00Z", mapped[0].lastModified)
        assertEquals(3600, mapped[0].zoneOffsetSeconds)
        assertEquals(listOf("restingHeartRate", "steps", "weight"), mapped.map { it.kindName() })
        assertEquals(1234, (mapped[1] as StepsRecord).count)
        assertEquals(78.4, (mapped[2] as WeightRecord).kg)
    }

    @Test
    fun `batches stop at the record and the byte limit keep the order and each is a valid upload`() {
        val records = WellnessMapper.map(List(12) { HealthSamples.resting("r$it", NOW + it * 1000L) }).records
        val byRecords = WellnessBatcher.batches(records, "Phone", maxRecords = 5)
        assertEquals(listOf(5, 5, 2), byRecords.map { it.records })
        assertEquals(
            records.map { it.id },
            byRecords.flatMap { WellnessApiJson.decodeUpload(it.body).records.map { r -> r.id } },
        )
        assertEquals(mapOf("restingHeartRate" to 5), byRecords.first().byType)
        val byBytes = WellnessBatcher.batches(records, null, maxBytes = 600)
        assertTrue(byBytes.size > 1 && byBytes.all { it.body.length <= 600 }, "each batch fits its limit")
        assertEquals(12, byBytes.sumOf { it.records })
        assertTrue(WellnessBatcher.batches(emptyList()).isEmpty())
        // One record alone over the limit still goes, alone.
        assertEquals(listOf(1, 1, 1), WellnessBatcher.batches(records.take(3), null, maxBytes = 10).map { it.records })
    }
}
