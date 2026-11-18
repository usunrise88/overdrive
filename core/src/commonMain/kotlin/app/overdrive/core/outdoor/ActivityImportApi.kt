package app.overdrive.core.outdoor

import app.overdrive.core.sync.parseStrictObject
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
 * Outdoor rides from a FIT file (spec §11.8, §8.3, ADR 0026): the answer of `POST /v1/activities/fit`, as
 * `contracts/schema/activity-import-response.schema.json` says. The request is the FIT file's bytes
 * (`application/vnd.ant.fit`), not JSON. The model is as strict as its schema and checks the rules the schema cannot state, so an
 * unknown key, a missing one, a value out of range or an answer that breaks a rule is an [IllegalArgumentException] when it is
 * read. Messages name a field and the rule, never a figure.
 */

/** What the models check; the limits are those of the schema. */
internal object ImportChecks {
    const val MAX_NAME = 100
    const val MAX_HR = 300.0
    const val MILLIS_PER_SECOND = 1000
    val ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
}

/** The largest FIT file `POST /v1/activities/fit` takes, in bytes (413 `payload_too_large` above). */
public const val ACTIVITY_FIT_MAX_BYTES: Int = 16 * 1024 * 1024

/** The `sport` of an outdoor ride in the rides list and the activity's `type`. */
public const val OUTDOOR_RIDE_SPORT: String = "cycling-outdoor"

/** Whether this request stored the ride (HTTP 201) or the service had it already (HTTP 200). */
@Serializable
public enum class ActivityImportStatus {
    @SerialName("imported")
    IMPORTED,

    @SerialName("duplicate")
    DUPLICATE,
}

/** What the stored thing named by `id` is: an activity (an imported ride, a workout from the watch) or a ride recorded on the tablet. */
@Serializable
public enum class ActivityImportKind {
    @SerialName("activity")
    ACTIVITY,

    @SerialName("ride")
    RIDE,
}

/** Why a file is the same ride as one the service already has. */
@Serializable
public enum class ActivityImportReason {
    /** The very same bytes were imported before. */
    @SerialName("same-file")
    SAME_FILE,

    /** The start is within 60 seconds and the length within 2% of one already stored. */
    @SerialName("same-start-and-duration")
    SAME_START_AND_DURATION,

    /** The file overlaps a ride on the tablet or a workout from the watch by half of its length or more. */
    @SerialName("overlap")
    OVERLAP,
}

/** What the file says of the ride; [load] only for an imported one (null for a duplicate, and when no threshold heart rate is known). */
@Serializable
public data class ActivityImportSummary(
    val name: String,
    val start: String,
    val end: String,
    val startDate: String,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val elevationGainMeters: Double?,
    val averageHeartRate: Double?,
    val maxHeartRate: Int?,
    val load: Double?,
    val loadApproximate: Boolean,
) {
    init {
        require(name.length in 1..ImportChecks.MAX_NAME) { "name must have 1 to 100 characters" }
        require(WellnessTime.isUtcInstant(start)) { "start is not a real UTC instant" }
        require(WellnessTime.isUtcInstant(end)) { "end is not a real UTC instant" }
        val from = requireNotNull(WellnessTime.parseInstant(start))
        val to = requireNotNull(WellnessTime.parseInstant(end))
        require(to > from) { "end is not after start" }
        require(durationSeconds >= 1) { "durationSeconds is below 1" }
        require(abs(durationSeconds - (to - from) / ImportChecks.MILLIS_PER_SECOND.toDouble()) <= 1.0) {
            "durationSeconds is not the difference of start and end"
        }
        require(WellnessTime.isDate(startDate)) { "startDate is not a real calendar date" }
        require(distanceMeters == null || distanceMeters >= 0.0) { "distanceMeters is negative" }
        require(elevationGainMeters == null || elevationGainMeters >= 0.0) { "elevationGainMeters is negative" }
        require(averageHeartRate == null || averageHeartRate in 1.0..ImportChecks.MAX_HR) {
            "averageHeartRate is not within 1..300"
        }
        require(
            maxHeartRate == null || maxHeartRate in 1..ImportChecks.MAX_HR.toInt(),
        ) { "maxHeartRate is not within 1..300" }
        require(load == null || load >= 0.0) { "load is negative" }
    }
}

/** The answer to `POST /v1/activities/fit`: 201 [ActivityImportStatus.IMPORTED], 200 [ActivityImportStatus.DUPLICATE]. */
@Serializable
public data class ActivityImportResponse(
    val status: ActivityImportStatus,
    val id: String,
    val kind: ActivityImportKind,
    val reason: ActivityImportReason?,
    val summary: ActivityImportSummary,
) {
    init {
        require(ImportChecks.ID.matches(id)) { "id is not a ride or activity id" }
        when (status) {
            ActivityImportStatus.IMPORTED -> {
                require(kind == ActivityImportKind.ACTIVITY) { "kind must be activity for an imported ride" }
                require(reason == null) { "reason must be null for an imported ride" }
                require(id.startsWith("fit-")) { "id must start with fit- for an imported ride" }
            }

            ActivityImportStatus.DUPLICATE -> {
                require(reason != null) { "reason is needed for a duplicate" }
                require(summary.load == null) { "load must be null for a duplicate" }
                require(!summary.loadApproximate) { "loadApproximate must be false for a duplicate" }
            }
        }
    }
}

/** Reads and writes the answer of `POST /v1/activities/fit`, strict like its schema. */
public object ActivityImportJson {
    private val json = Json.Default

    private val numbers =
        setOf("durationSeconds", "distanceMeters", "elevationGainMeters", "averageHeartRate", "maxHeartRate", "load")
    private val booleans = setOf("loadApproximate")

    /** @throws IllegalArgumentException if [text] is not an answer of the schema. */
    public fun decode(text: String): ActivityImportResponse =
        json.decodeFromJsonElement(ActivityImportResponse.serializer(), parseStrictObject(text, numbers, booleans))

    public fun encode(response: ActivityImportResponse): String =
        json.encodeToString(ActivityImportResponse.serializer(), response)
}
