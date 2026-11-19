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
import app.overdrive.core.wellness.WellnessTime
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mapping against the contract's own fixtures (`contracts/fixtures/wellness/upload/valid`): every valid upload is read as
 * the wire records the service accepts, turned back into the neutral records Health Connect would give, and mapped again; the
 * result must be the same records, so no field, kind or edge value (bpm 1 and 300, an id of 100 characters, a zone offset of
 * ±18 h) is lost or changed on the way. Instants beyond milliseconds are read to milliseconds, which is all the mapping writes.
 */
class WellnessMapperFixturesTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))
    private val instant = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")

    private fun valid(): List<File> =
        File(
            contracts,
            "fixtures/wellness/upload/valid",
        ).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun millis(text: String): Long = checkNotNull(WellnessTime.parseInstant(text))

    /** The fixture's text with every instant at millisecond precision, the form the mapping writes. */
    private fun atMillis(text: String): String = instant.replace(text) { WellnessTime.formatInstant(millis(it.value)) }

    private fun ticks(samples: List<app.overdrive.core.wellness.HeartRateSample>?) =
        samples?.map { HeartRateTick(millis(it.t), it.bpm.toLong()) }

    private fun neutral(record: WellnessRecord): HealthRecord {
        val modified = record.lastModified?.let(::millis)
        val zone = record.zoneOffsetSeconds
        return when (record) {
            is RestingHeartRateRecord -> {
                RestingHeartRateReading(record.id, millis(record.time), record.bpm.toLong(), modified, zone)
            }

            is HeartRateSamplesRecord -> {
                HeartRateSeries(
                    record.id,
                    millis(record.start),
                    millis(record.end),
                    ticks(record.samples).orEmpty(),
                    modified,
                    zone,
                )
            }

            is SleepSessionRecord -> {
                SleepSessionReading(
                    record.id,
                    millis(record.start),
                    millis(record.end),
                    record.title,
                    record.stages?.map { SleepStageReading(it.stage, millis(it.start), millis(it.end)) },
                    modified,
                    zone,
                )
            }

            is RespiratoryRateRecord -> {
                RespiratoryRateReading(record.id, millis(record.time), record.rate, modified, zone)
            }

            is OxygenSaturationRecord -> {
                OxygenSaturationReading(record.id, millis(record.time), record.percent, modified, zone)
            }

            is StepsRecord -> {
                StepsReading(record.id, millis(record.start), millis(record.end), record.count.toLong(), modified, zone)
            }

            is ExerciseSessionRecord -> {
                ExerciseSessionReading(
                    record.id,
                    record.type,
                    millis(record.start),
                    millis(record.end),
                    record.title,
                    ticks(record.heartRateSamples),
                    modified,
                    zone,
                )
            }

            is HeartRateVariabilityRmssdRecord -> {
                HeartRateVariabilityReading(record.id, millis(record.time), record.ms, modified, zone)
            }

            is WeightRecord -> {
                WeightReading(record.id, millis(record.time), record.kg, modified, zone)
            }
        }
    }

    @Test
    fun `every valid fixture maps back to the same wire records`() {
        val files = valid()
        assertTrue(files.size >= 7, "the fixtures are there")
        for (file in files) {
            val expected = WellnessApiJson.decodeUpload(atMillis(file.readText())).records
            val mapped = WellnessMapper.map(expected.map(::neutral), policy = HeartRatePolicy.ALL)
            assertEquals(0, mapped.skipped, "${file.name}: nothing is skipped")
            assertEquals(expected, mapped.records, file.name)
        }
    }

    @Test
    fun `the default policy keeps the heart rate the fixtures place in the night and in the session`() {
        val file = File(contracts, "fixtures/wellness/upload/valid/every-kind.json")
        val expected = WellnessApiJson.decodeUpload(file.readText()).records
        val mapped = WellnessMapper.map(expected.map(::neutral))
        assertEquals(expected, mapped.records)
    }

    @Test
    fun `a mapped batch is a valid upload of the contract and reads back the same`() {
        val file = File(contracts, "fixtures/wellness/upload/valid/every-kind.json")
        val expected = WellnessApiJson.decodeUpload(file.readText())
        val batches =
            WellnessBatcher.batches(
                WellnessMapper.map(expected.records.map(::neutral)).records,
                expected.device,
            )
        assertEquals(1, batches.size)
        assertEquals(expected, WellnessApiJson.decodeUpload(batches.single().body))
        assertEquals(expected.records.size, batches.single().records)
    }
}
