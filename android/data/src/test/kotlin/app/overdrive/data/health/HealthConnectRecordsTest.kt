package app.overdrive.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import app.overdrive.core.health.ExerciseSessionReading
import app.overdrive.core.health.HealthDataType
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Real Health Connect record objects (the client library's own classes, with synthetic values) turned into the neutral records of
 * `core`: every type the companion reads, the id and the zone, the sleep stages and the exercise type's token. The library's
 * `Metadata` stamps a last-modified time only when Health Connect did, so these records have none.
 */
class HealthConnectRecordsTest {
    private val at = Instant.parse("2026-11-18T06:30:00.250Z")
    private val offset = ZoneOffset.ofHours(1)

    private fun metadata(id: String) = Metadata.manualEntryWithId(id)

    @Test
    fun `resting heart rate, respiratory rate, oxygen saturation, weight, steps and heart rate variability`() {
        assertEquals(
            RestingHeartRateReading("rhr-1", at.toEpochMilli(), 52, null, 3600),
            RestingHeartRateRecord(at, offset, 52, metadata("rhr-1")).toHealthRecord(),
        )
        assertEquals(
            RespiratoryRateReading("rr-1", at.toEpochMilli(), 14.2, null, 3600),
            RespiratoryRateRecord(at, offset, 14.2, metadata("rr-1")).toHealthRecord(),
        )
        assertEquals(
            OxygenSaturationReading("sp-1", at.toEpochMilli(), 96.5, null, 3600),
            OxygenSaturationRecord(at, offset, Percentage(96.5), metadata("sp-1")).toHealthRecord(),
        )
        assertEquals(
            WeightReading("w-1", at.toEpochMilli(), 78.4, null, 3600),
            WeightRecord(at, offset, Mass.kilograms(78.4), metadata("w-1")).toHealthRecord(),
        )
        assertEquals(
            HeartRateVariabilityReading("hrv-1", at.toEpochMilli(), 62.5, null, 3600),
            HeartRateVariabilityRmssdRecord(at, offset, 62.5, metadata("hrv-1")).toHealthRecord(),
        )
        val end = at.plusSeconds(3600)
        assertEquals(
            StepsReading("st-1", at.toEpochMilli(), end.toEpochMilli(), 1234, null, 3600),
            StepsRecord(at, offset, end, offset, 1234, metadata("st-1")).toHealthRecord(),
        )
    }

    @Test
    fun `a heart rate record keeps its samples in time and no zone it does not have`() {
        val samples = listOf(HeartRateRecord.Sample(at, 51), HeartRateRecord.Sample(at.plusSeconds(30), 53))
        val mapped = HeartRateRecord(at, null, at.plusSeconds(60), null, samples, metadata("hr-1")).toHealthRecord()
        assertEquals(
            HeartRateSeries(
                "hr-1",
                at.toEpochMilli(),
                at.plusSeconds(60).toEpochMilli(),
                listOf(HeartRateTick(at.toEpochMilli(), 51), HeartRateTick(at.plusSeconds(30).toEpochMilli(), 53)),
                null,
                null,
            ),
            mapped,
        )
    }

    @Test
    fun `a sleep session keeps its stages by name, and one with no stages has none`() {
        val stages =
            listOf(
                SleepSessionRecord.Stage(at, at.plusSeconds(600), SleepSessionRecord.STAGE_TYPE_LIGHT),
                SleepSessionRecord.Stage(at.plusSeconds(600), at.plusSeconds(1200), SleepSessionRecord.STAGE_TYPE_DEEP),
                SleepSessionRecord.Stage(
                    at.plusSeconds(1200),
                    at.plusSeconds(1500),
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                ),
                SleepSessionRecord.Stage(at.plusSeconds(1500), at.plusSeconds(1800), 99),
            )
        val end = at.plusSeconds(1800)
        val mapped =
            SleepSessionRecord(
                at,
                offset,
                end,
                offset,
                metadata("sl-1"),
                "Night",
                null,
                stages,
            ).toHealthRecord()
        assertEquals(
            SleepSessionReading(
                "sl-1",
                at.toEpochMilli(),
                end.toEpochMilli(),
                "Night",
                listOf(
                    SleepStageReading(SleepStage.LIGHT, at.toEpochMilli(), at.plusSeconds(600).toEpochMilli()),
                    SleepStageReading(
                        SleepStage.DEEP,
                        at.plusSeconds(600).toEpochMilli(),
                        at.plusSeconds(1200).toEpochMilli(),
                    ),
                    SleepStageReading(
                        SleepStage.AWAKE_IN_BED,
                        at.plusSeconds(1200).toEpochMilli(),
                        at.plusSeconds(1500).toEpochMilli(),
                    ),
                    SleepStageReading(SleepStage.UNKNOWN, at.plusSeconds(1500).toEpochMilli(), end.toEpochMilli()),
                ),
                null,
                3600,
            ),
            mapped,
        )
        val plain =
            SleepSessionRecord(
                at,
                offset,
                end,
                offset,
                metadata("sl-2"),
            ).toHealthRecord() as SleepSessionReading
        assertNull(plain.stages)
    }

    @Test
    fun `an exercise session's type is the token of the library's own table, and an unknown one is other_workout`() {
        val end = at.plusSeconds(2520)

        fun type(code: Int) =
            (
                ExerciseSessionRecord(
                    at,
                    offset,
                    end,
                    offset,
                    metadata("ex"),
                    code,
                    "VR",
                ).toHealthRecord() as ExerciseSessionReading
            ).type
        assertEquals("other_workout", type(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
        assertEquals("biking", type(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals("biking_stationary", type(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY))
        assertEquals("other_workout", type(9999))
        val mapped =
            ExerciseSessionRecord(
                at,
                offset,
                end,
                offset,
                metadata("ex-1"),
                ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                "VR",
                null,
            )
        val reading = mapped.toHealthRecord() as ExerciseSessionReading
        assertEquals("VR", reading.title)
        assertNull(reading.heartRate)
        assertEquals("ex-1", reading.id)
    }

    @Test
    fun `every type has a record class, a read permission and a way back, and none is a write permission`() {
        val permissions = HealthDataType.entries.map { HealthPermissions.read(it) }
        assertEquals(HealthDataType.entries.size, permissions.toSet().size)
        assertTrue(permissions.all { it.startsWith("android.permission.health.READ_") })
        assertEquals("android.permission.health.READ_SLEEP", HealthPermissions.read(HealthDataType.SLEEP))
        assertEquals("android.permission.health.READ_HEALTH_DATA_HISTORY", HealthPermissions.HISTORY)
        assertEquals("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", HealthPermissions.BACKGROUND)
        val sample =
            mapOf(
                HealthDataType.RESTING_HEART_RATE to RestingHeartRateRecord(at, offset, 50, metadata("a")),
                HealthDataType.RESPIRATORY_RATE to RespiratoryRateRecord(at, offset, 14.0, metadata("b")),
                HealthDataType.WEIGHT to WeightRecord(at, offset, Mass.kilograms(70.0), metadata("c")),
            )
        for ((type, record) in sample) {
            assertEquals(type, HealthPermissions.typeOf(record))
            assertEquals(type, record.toHealthRecord()?.dataType)
            assertTrue(HealthPermissions.recordClass(type).isInstance(record))
        }
    }
}
