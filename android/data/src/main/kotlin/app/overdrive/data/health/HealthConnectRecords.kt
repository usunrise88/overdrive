package app.overdrive.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import app.overdrive.core.health.ExerciseSessionReading
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthRecord
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
import kotlin.reflect.KClass

/**
 * The record classes and read permissions of the types the companion reads (spec §6.2, §12.5), and AndroidX Health Connect's
 * records in the neutral form of `core` (ADR 0027). Read only: no write permission is ever named. Pure, so a test builds real
 * Health Connect records and checks what comes out.
 */
object HealthPermissions {
    fun recordClass(type: HealthDataType): KClass<out Record> =
        when (type) {
            HealthDataType.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            HealthDataType.SLEEP -> SleepSessionRecord::class
            HealthDataType.RESPIRATORY_RATE -> RespiratoryRateRecord::class
            HealthDataType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
            HealthDataType.HEART_RATE -> HeartRateRecord::class
            HealthDataType.EXERCISE -> ExerciseSessionRecord::class
            HealthDataType.WEIGHT -> WeightRecord::class
            HealthDataType.HEART_RATE_VARIABILITY -> HeartRateVariabilityRmssdRecord::class
            HealthDataType.STEPS -> StepsRecord::class
        }

    /** The read permission of [type] (`android.permission.health.READ_SLEEP`, ...). */
    fun read(type: HealthDataType): String = HealthPermission.getReadPermission(recordClass(type))

    /** Reading older than 30 days before the app was first allowed: the first 60-day read. */
    val HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Reading while the app is not on screen: the daily read. */
    val BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** The type a record class belongs to, or null for a class the companion does not read. */
    fun typeOf(record: Record): HealthDataType? =
        when (record) {
            is RestingHeartRateRecord -> HealthDataType.RESTING_HEART_RATE
            is SleepSessionRecord -> HealthDataType.SLEEP
            is RespiratoryRateRecord -> HealthDataType.RESPIRATORY_RATE
            is OxygenSaturationRecord -> HealthDataType.OXYGEN_SATURATION
            is HeartRateRecord -> HealthDataType.HEART_RATE
            is ExerciseSessionRecord -> HealthDataType.EXERCISE
            is WeightRecord -> HealthDataType.WEIGHT
            is HeartRateVariabilityRmssdRecord -> HealthDataType.HEART_RATE_VARIABILITY
            is StepsRecord -> HealthDataType.STEPS
            else -> null
        }
}

/**
 * [Record] as a [HealthRecord], or null for a class the companion does not read. The id is Health Connect's `metadata.id` and
 * `lastModified` its `metadata.lastModifiedTime`. An exercise session's type is the lower-case token of
 * `ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP` (`biking`, `other_workout`, ...). The session's heart rate is not in the
 * record: the reader attaches it.
 */
fun Record.toHealthRecord(): HealthRecord? {
    val id = metadata.id
    val modified = metadata.lastModifiedTime.toEpochMilli().takeIf { it > 0 }
    return when (this) {
        is RestingHeartRateRecord -> {
            RestingHeartRateReading(id, time.toEpochMilli(), beatsPerMinute, modified, zoneOffset?.totalSeconds)
        }

        is HeartRateRecord -> {
            HeartRateSeries(
                id,
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                samples.map { HeartRateTick(it.time.toEpochMilli(), it.beatsPerMinute) },
                modified,
                startZoneOffset?.totalSeconds,
            )
        }

        is SleepSessionRecord -> {
            SleepSessionReading(
                id,
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                title,
                stages
                    .map {
                        SleepStageReading(
                            stageOf(it.stage),
                            it.startTime.toEpochMilli(),
                            it.endTime.toEpochMilli(),
                        )
                    }.takeIf { it.isNotEmpty() },
                modified,
                startZoneOffset?.totalSeconds,
            )
        }

        is RespiratoryRateRecord -> {
            RespiratoryRateReading(id, time.toEpochMilli(), rate, modified, zoneOffset?.totalSeconds)
        }

        is OxygenSaturationRecord -> {
            OxygenSaturationReading(id, time.toEpochMilli(), percentage.value, modified, zoneOffset?.totalSeconds)
        }

        is StepsRecord -> {
            StepsReading(
                id,
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                count,
                modified,
                startZoneOffset?.totalSeconds,
            )
        }

        is ExerciseSessionRecord -> {
            ExerciseSessionReading(
                id,
                exerciseToken(exerciseType),
                startTime.toEpochMilli(),
                endTime.toEpochMilli(),
                title,
                heartRate = null,
                lastModifiedMillis = modified,
                zoneOffsetSeconds = startZoneOffset?.totalSeconds,
            )
        }

        is HeartRateVariabilityRmssdRecord -> {
            HeartRateVariabilityReading(
                id,
                time.toEpochMilli(),
                heartRateVariabilityMillis,
                modified,
                zoneOffset?.totalSeconds,
            )
        }

        is WeightRecord -> {
            WeightReading(id, time.toEpochMilli(), weight.inKilograms, modified, zoneOffset?.totalSeconds)
        }

        else -> {
            null
        }
    }
}

private const val OTHER_WORKOUT = "other_workout"

/**
 * The lower-case token of an exercise type, as the contract writes it (`biking`, `biking_stationary`, `other_workout`). The library
 * keeps its own table private to itself, so this is Health Connect's `ExerciseSessionRecord.EXERCISE_TYPE_*` numbering (a platform
 * constant) with the library's names, and type 0 is `other_workout`, as the contract and Health Connect's constant call it. A type
 * the table does not know is `other_workout`.
 */
internal fun exerciseToken(type: Int): String = EXERCISE_TOKENS[type] ?: OTHER_WORKOUT

private val EXERCISE_TOKENS: Map<Int, String> =
    mapOf(
        2 to "badminton",
        4 to "baseball",
        5 to "basketball",
        8 to "biking",
        9 to "biking_stationary",
        10 to "boot_camp",
        11 to "boxing",
        13 to "calisthenics",
        14 to "cricket",
        16 to "dancing",
        25 to "elliptical",
        26 to "exercise_class",
        27 to "fencing",
        28 to "football_american",
        29 to "football_australian",
        31 to "frisbee_disc",
        32 to "golf",
        33 to "guided_breathing",
        34 to "gymnastics",
        35 to "handball",
        36 to "high_intensity_interval_training",
        37 to "hiking",
        38 to "ice_hockey",
        39 to "ice_skating",
        44 to "martial_arts",
        46 to "paddling",
        47 to "para_gliding",
        48 to "pilates",
        50 to "racquetball",
        51 to "rock_climbing",
        52 to "roller_hockey",
        53 to "rowing",
        54 to "rowing_machine",
        55 to "rugby",
        56 to "running",
        57 to "running_treadmill",
        58 to "sailing",
        59 to "scuba_diving",
        60 to "skating",
        61 to "skiing",
        62 to "snowboarding",
        63 to "snowshoeing",
        64 to "soccer",
        65 to "softball",
        66 to "squash",
        68 to "stair_climbing",
        69 to "stair_climbing_machine",
        70 to "strength_training",
        71 to "stretching",
        72 to "surfing",
        73 to "swimming_open_water",
        74 to "swimming_pool",
        75 to "table_tennis",
        76 to "tennis",
        78 to "volleyball",
        79 to "walking",
        80 to "water_polo",
        81 to "weightlifting",
        82 to "wheelchair",
        83 to "yoga",
    )

/** A Health Connect sleep stage by name (`SleepSessionRecord.STAGE_TYPE_*`). */
internal fun stageOf(stage: Int): SleepStage =
    when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepStage.AWAKE
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStage.AWAKE_IN_BED
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStage.SLEEPING
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
        else -> SleepStage.UNKNOWN
    }
