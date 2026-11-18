package app.overdrive.core.wellness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads and writes the bodies of the wellness part of the sync API (spec §8.3, ADR 0023). Strict like the schemas in
 * `contracts/schema/`: unknown keys, wrong types (a number in quotes is not a number), values out of range and every rule
 * of the schemas' descriptions are rejected with an [IllegalArgumentException]. A message names a field and the rule, never
 * a measured value.
 */
public object WellnessApiJson {
    /** At most this many records in one upload (413 `payload_too_large` above). */
    public const val MAX_RECORDS: Int = 2000

    /** At most this many bytes in one upload (413 `payload_too_large` above). */
    public const val MAX_BYTES: Int = 4 * 1024 * 1024

    /** The longest range `GET /v1/wellness` serves, in days. */
    public const val MAX_RANGE_DAYS: Int = 186

    /** The kinds of record an upload may carry, as named by `kind`. */
    public val RECORD_KINDS: Set<String> =
        setOf(
            "restingHeartRate",
            "heartRateSamples",
            "sleepSession",
            "respiratoryRate",
            "oxygenSaturation",
            "steps",
            "exerciseSession",
            "heartRateVariabilityRmssd",
            "weight",
        )

    /** For reading: every key must be known and present. */
    private val strict = Json { classDiscriminator = "kind" }

    /** For writing an upload: a key that has no value is left out, as the schema wants. */
    private val writer =
        Json {
            classDiscriminator = "kind"
            explicitNulls = false
            encodeDefaults = true
        }

    private val uploadNumbers = setOf("bpm", "rate", "percent", "count", "ms", "kg", "zoneOffsetSeconds")
    private val uploadResponseNumbers = setOf("accepted", "duplicates", "rejected")
    private val wellnessNumbers =
        setOf(
            "value",
            "baselineLow",
            "baselineHigh",
            "streak",
            "low",
            "high",
            "steps",
            "hrvSdnn",
            "weightKg",
            "weightKg7d",
            "nightsLogged",
            "nightsNeeded",
            "baselineDays",
        )
    private val activityNumbers =
        setOf(
            "durationSeconds",
            "averageHeartRate",
            "maxHeartRate",
            "load",
            "thresholdHr",
            "maxHr",
            "samples",
            "medianGapSeconds",
            "maxGapSeconds",
            "coverage",
            "t",
            "heartRate",
            "distanceMeters",
            "elevationGainMeters",
        )

    /** kotlinx reads `"52"` as the number 52; the schemas do not. */
    private fun requireNumbers(
        element: JsonElement,
        keys: Set<String>,
    ) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    require(
                        key !in keys || value !is JsonPrimitive || !value.isString,
                    ) { "$key must be a number, not a string" }
                    requireNumbers(value, keys)
                }
            }

            is JsonArray -> {
                element.forEach { requireNumbers(it, keys) }
            }

            else -> {}
        }
    }

    private fun parse(
        text: String,
        numbers: Set<String>,
    ): JsonElement {
        val root = strict.parseToJsonElement(text)
        require(root is JsonObject) { "The body is a JSON object" }
        requireNumbers(root, numbers)
        return root
    }

    /** @throws IllegalArgumentException if [text] is not an upload of the schema. */
    public fun decodeUpload(text: String): WellnessUpload =
        strict.decodeFromJsonElement(WellnessUpload.serializer(), parse(text, uploadNumbers))

    /** The JSON of [upload] as `POST /v1/wellness` takes it: keys without a value left out, on one line. */
    public fun encodeUpload(upload: WellnessUpload): String = writer.encodeToString(WellnessUpload.serializer(), upload)

    /** @throws IllegalArgumentException if [text] is not an upload answer of the schema. */
    public fun decodeUploadResponse(text: String): WellnessUploadResponse =
        strict.decodeFromJsonElement(WellnessUploadResponse.serializer(), parse(text, uploadResponseNumbers))

    public fun encodeUploadResponse(response: WellnessUploadResponse): String =
        strict.encodeToString(WellnessUploadResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a wellness answer of the schema. */
    public fun decodeWellnessResponse(text: String): WellnessResponse =
        strict.decodeFromJsonElement(WellnessResponse.serializer(), parse(text, wellnessNumbers))

    public fun encodeWellnessResponse(response: WellnessResponse): String =
        strict.encodeToString(WellnessResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a recovery answer of the schema. */
    public fun decodeRecoveryResponse(text: String): RecoveryResponse =
        strict.decodeFromJsonElement(RecoveryResponse.serializer(), parse(text, wellnessNumbers))

    public fun encodeRecoveryResponse(response: RecoveryResponse): String =
        strict.encodeToString(RecoveryResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a watch activity of the schema. */
    public fun decodeActivity(text: String): Activity =
        strict.decodeFromJsonElement(Activity.serializer(), parse(text, activityNumbers))

    public fun encodeActivity(activity: Activity): String = strict.encodeToString(Activity.serializer(), activity)
}
