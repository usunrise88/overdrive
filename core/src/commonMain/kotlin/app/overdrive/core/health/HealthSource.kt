package app.overdrive.core.health

import app.overdrive.core.wellness.SleepStage

/*
 * The watch data as the phone reads it (spec §6.2, §6.7, §12.5, ADR 0027): the record types, the records themselves in a neutral
 * form that owes nothing to Android, and the one interface ([HealthSource]) behind which Health Connect sits. The real class is
 * a thin adapter in `android/data`; every test of the pipeline uses a fake, so nothing of the reading logic needs a phone.
 *
 * Nothing in this file, and nothing that handles these records, logs a value: the readings are health data.
 */

/**
 * The record types the companion reads, each one read permission. [essential] ones are what the recovery rating stands on: when one
 * is off, Today says it rates recovery with less. The others are read when the rider allows them and nothing is lost when not.
 *
 * @property label the plain name, as the permission list and Settings print it.
 * @property reason why it is asked, in one line (design/companion.md §4.10).
 */
public enum class HealthDataType(
    public val label: String,
    public val reason: String,
    public val essential: Boolean,
) {
    RESTING_HEART_RATE("Resting heart rate", "The main recovery signal", true),
    SLEEP("Sleep", "How long and when you sleep, for recovery", true),
    RESPIRATORY_RATE("Respiratory rate", "A recovery signal", true),
    OXYGEN_SATURATION("Oxygen saturation", "A recovery signal", true),
    HEART_RATE("Heart rate", "Heart rate in sleep, and the load of watch activities", true),
    EXERCISE("Exercise", "Watch activities count toward fatigue", true),
    WEIGHT("Weight", "Your weight and its trend, next to the other signals", false),
    HEART_RATE_VARIABILITY("Heart rate variability", "A recovery signal, when your watch writes it", false),
    STEPS("Steps", "Your daily steps, kept for your assistant", false),
}

/** Whether Health Connect can be used on this phone (`HealthConnectClient.getSdkStatus`). */
public enum class HealthAvailability {
    AVAILABLE,

    /** On Android 12 and 13 Health Connect is an app from the Play Store, and it is not there. */
    NOT_INSTALLED,

    /** It is there, and too old for this app. */
    UPDATE_REQUIRED,

    /** This phone has no Health Connect at all. */
    NOT_SUPPORTED,
}

/**
 * What the rider has allowed: the [granted] record types, whether the app may read older than 30 days before it was first allowed
 * ([history]: the first 60-day read) and whether it may read while it is not on screen ([background]: the daily read).
 * [backgroundNeeded] is false on a Health Connect that has no such permission, where a background read needs nothing.
 */
public data class HealthAccess(
    val granted: Set<HealthDataType>,
    val history: Boolean,
    val background: Boolean,
    val historyNeeded: Boolean = true,
    val backgroundNeeded: Boolean = true,
) {
    /** The types the recovery rating needs that are not allowed. */
    public val missingEssential: List<HealthDataType>
        get() = HealthDataType.entries.filter { it.essential && it !in granted }

    /** The daily read can run: allowed, or the platform does not ask. */
    public val backgroundOk: Boolean get() = background || !backgroundNeeded
}

/** One page of a read: the [records] and where the next page is ([nextPageToken] null on the last). */
public class HealthPage(
    public val records: List<HealthRecord>,
    public val nextPageToken: String?,
)

/**
 * What changed since a changes token: the [upserts] (new or edited records), how many records were [deletions] (the service has no
 * delete, so they are only counted), the [nextToken] to keep, whether there is [hasMore] and whether the token is [tokenExpired]
 * (Health Connect keeps one for 30 days): then the rest is empty and the reader falls back to a time window.
 */
public class HealthChanges(
    public val upserts: List<HealthRecord>,
    public val deletions: Int,
    public val nextToken: String,
    public val hasMore: Boolean,
    public val tokenExpired: Boolean,
)

/** Health Connect refused a read the rider had allowed: the permission was taken away, or the background read is not allowed. */
public class HealthPermissionException(
    message: String = "Health Connect refused the read",
) : Exception(message)

/** Health Connect could not answer (it is updating, it is busy, the phone is locked): worth trying again later. */
public class HealthUnavailableException(
    message: String = "Health Connect did not answer",
) : Exception(message)

/**
 * The one door to the watch data (spec §6.2). Reading only: the app never writes to Health Connect. A real implementation reads
 * with the phone's Health Connect client; the tests' fake serves scripted records, pages, changes and an expiring token.
 *
 * Functions throw [HealthPermissionException] when a read is refused and [HealthUnavailableException] when Health Connect cannot
 * answer; anything else is a bug the caller reports as a failure without a word of the data.
 */
public interface HealthSource {
    public suspend fun availability(): HealthAvailability

    /** The permissions in force now (re-read at every use: the rider can change them in Health Connect at any time). */
    public suspend fun access(): HealthAccess

    /** The records of [type] that overlap `[fromMillis, toMillis)`, a page at a time; [pageToken] null is the first. */
    public suspend fun readPage(
        type: HealthDataType,
        fromMillis: Long,
        toMillis: Long,
        pageToken: String?,
    ): HealthPage

    /** A token to ask [changes] with: everything that changes from now on, for [types]. */
    public suspend fun newChangesToken(types: Set<HealthDataType>): String

    public suspend fun changes(token: String): HealthChanges
}

// ---- Records -----------------------------------------------------------------------------------------------

/**
 * A record as Health Connect has it, with the instants in milliseconds. [id] is Health Connect's own (`metadata.id`), what the
 * service is idempotent by; [lastModifiedMillis] is `metadata.lastModifiedTime`.
 */
public sealed interface HealthRecord {
    public val id: String
    public val dataType: HealthDataType
    public val lastModifiedMillis: Long?
    public val zoneOffsetSeconds: Int?
}

public data class HeartRateTick(
    val timeMillis: Long,
    val bpm: Long,
)

public data class RestingHeartRateReading(
    override val id: String,
    val timeMillis: Long,
    val bpm: Long,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.RESTING_HEART_RATE
}

/** `HeartRateRecord`: the samples between [startMillis] and [endMillis]. */
public data class HeartRateSeries(
    override val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val samples: List<HeartRateTick>,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.HEART_RATE
}

public data class SleepStageReading(
    val stage: SleepStage,
    val startMillis: Long,
    val endMillis: Long,
)

public data class SleepSessionReading(
    override val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String? = null,
    val stages: List<SleepStageReading>? = null,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.SLEEP
}

public data class RespiratoryRateReading(
    override val id: String,
    val timeMillis: Long,
    val rate: Double,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.RESPIRATORY_RATE
}

public data class OxygenSaturationReading(
    override val id: String,
    val timeMillis: Long,
    val percent: Double,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.OXYGEN_SATURATION
}

public data class StepsReading(
    override val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val count: Long,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.STEPS
}

/**
 * `ExerciseSessionRecord`. [type] is the exercise type as a lower-case token (`biking`, `other_workout`: the names of
 * `ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP`). The session's heart rate is not in the record: the reader attaches the
 * samples of the heart-rate records inside it ([heartRate]).
 */
public data class ExerciseSessionReading(
    override val id: String,
    val type: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String? = null,
    val heartRate: List<HeartRateTick>? = null,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.EXERCISE
}

public data class HeartRateVariabilityReading(
    override val id: String,
    val timeMillis: Long,
    val ms: Double,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.HEART_RATE_VARIABILITY
}

public data class WeightReading(
    override val id: String,
    val timeMillis: Long,
    val kg: Double,
    override val lastModifiedMillis: Long? = null,
    override val zoneOffsetSeconds: Int? = null,
) : HealthRecord {
    override val dataType: HealthDataType get() = HealthDataType.WEIGHT
}
