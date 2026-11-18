package app.overdrive.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a successful upload answered (`contracts/schema/sync-upload-response.schema.json`). */
@Serializable
public enum class UploadStatus {
    /** This request stored it (HTTP 201). */
    @SerialName("stored")
    STORED,

    /** It was stored before with exactly this body; nothing changed (HTTP 200). */
    @SerialName("already-stored")
    ALREADY_STORED,
}

/**
 * The service's answer to a successful `PUT /v1/rides/{rideId}` or `PUT /v1/rides/{rideId}/fit` (spec §8.3, ADR 0014).
 * A ride counts as uploaded only when both requests have succeeded.
 */
@Serializable
public data class UploadResponse(
    val rideId: String,
    val status: UploadStatus,
) {
    init {
        require(SyncApiJson.isValidRideId(rideId)) { "Not a ride id: '$rideId'" }
    }
}

/** The stable error codes of the `/v1` API, each with the HTTP status it comes with (`contracts/schema/api-error.schema.json`). */
@Serializable
public enum class ApiErrorCode(
    public val httpStatus: Int,
) {
    @SerialName("unauthorized")
    UNAUTHORIZED(401),

    @SerialName("invalid_json")
    INVALID_JSON(400),

    @SerialName("invalid_ride_id")
    INVALID_RIDE_ID(400),

    @SerialName("invalid_range")
    INVALID_RANGE(400),

    @SerialName("invalid_query")
    INVALID_QUERY(400),

    @SerialName("invalid_record")
    INVALID_RECORD(422),

    @SerialName("invalid_profile")
    INVALID_PROFILE(422),

    @SerialName("ride_id_mismatch")
    RIDE_ID_MISMATCH(422),

    @SerialName("invalid_fit")
    INVALID_FIT(422),

    @SerialName("ride_not_found")
    RIDE_NOT_FOUND(404),

    @SerialName("ride_conflict")
    RIDE_CONFLICT(409),

    @SerialName("start_time_conflict")
    START_TIME_CONFLICT(409),

    @SerialName("fit_conflict")
    FIT_CONFLICT(409),

    @SerialName("payload_too_large")
    PAYLOAD_TOO_LARGE(413),

    @SerialName("unsupported_media_type")
    UNSUPPORTED_MEDIA_TYPE(415),

    @SerialName("not_found")
    NOT_FOUND(404),

    @SerialName("internal_error")
    INTERNAL_ERROR(500),
}

/** The `error` object of a non-2xx answer: a stable [code] for programs, a [message] for people. */
@Serializable
public data class ApiError(
    val code: ApiErrorCode,
    val message: String,
) {
    init {
        require(message.isNotEmpty()) { "An API error needs a message" }
    }
}

/** The JSON body of a non-2xx answer: `{ "error": { "code", "message" } }`. */
@Serializable
internal class ApiErrorDocument(
    val error: ApiError,
)

/**
 * Reads the bodies the service answers with (spec §8.3). Strict like the schemas: unknown keys, an unknown status or error
 * code and a ride id outside the accepted syntax are all rejected with an [IllegalArgumentException].
 */
public object SyncApiJson {
    private val json = Json { encodeDefaults = true }

    private val rideIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    /** Ride ids the service accepts: 1 to 128 characters from `A-Z a-z 0-9 . _ -`, starting with a letter or digit. */
    public fun isValidRideId(id: String): Boolean = rideIdPattern.matches(id)

    /** @throws IllegalArgumentException if [text] is not an upload response of the schema. */
    public fun decodeUploadResponse(text: String): UploadResponse =
        json.decodeFromString(UploadResponse.serializer(), text)

    public fun encodeUploadResponse(response: UploadResponse): String =
        json.encodeToString(UploadResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not an error body of the schema. */
    public fun decodeApiError(text: String): ApiError = json.decodeFromString(ApiErrorDocument.serializer(), text).error

    public fun encodeApiError(error: ApiError): String =
        json.encodeToString(ApiErrorDocument.serializer(), ApiErrorDocument(error))
}
