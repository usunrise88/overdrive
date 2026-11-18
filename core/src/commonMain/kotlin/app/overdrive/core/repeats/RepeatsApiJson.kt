package app.overdrive.core.repeats

import app.overdrive.core.sync.parseStrictObject
import kotlinx.serialization.json.Json

/**
 * Reads and writes the bodies of the repeated-sessions part of the sync API (spec §11.7, §8.3, ADR 0026). Strict like the schemas in
 * `contracts/schema/`: unknown keys, wrong types (a number in quotes is not a number), missing keys and every rule of the schemas'
 * descriptions are rejected with an [IllegalArgumentException]. A message names a field and the rule, never a figure.
 */
public object RepeatsApiJson {
    private val json = Json.Default

    private val numbers =
        setOf(
            "latest",
            "previous",
            "difference",
            "attempts",
            "value",
            "seconds",
            "targetWatts",
            "averagePower",
            "averageHeartRate",
            "ftp",
            "number",
            "durationSeconds",
            "effortRating",
            "targetFraction",
            "step",
            "percent",
            "stepsCompleted",
            "stepsPlanned",
            "powerDeltaPercent",
            "slopePerAttempt",
            "change",
            "steadyBelow",
            "minAttempts",
            "startSeconds",
            "endSeconds",
            "attempt",
            "heartRateAtPower",
            "powerTolerancePercent",
            "skipSeconds",
            "minStepSeconds",
            "minTrendAttempts",
        )

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/repeats` as the schema says. */
    public fun decodeRepeats(text: String): RepeatsResponse =
        json.decodeFromJsonElement(RepeatsResponse.serializer(), parseStrictObject(text, numbers))

    public fun encodeRepeats(response: RepeatsResponse): String =
        json.encodeToString(RepeatsResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/repeats/{hash}` as the schema says. */
    public fun decodeDetail(text: String): RepeatDetailResponse =
        json.decodeFromJsonElement(RepeatDetailResponse.serializer(), parseStrictObject(text, numbers))

    public fun encodeDetail(response: RepeatDetailResponse): String =
        json.encodeToString(RepeatDetailResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/repeats/{hash}/previous` as the schema says. */
    public fun decodePrevious(text: String): RepeatPreviousResponse =
        json.decodeFromJsonElement(RepeatPreviousResponse.serializer(), parseStrictObject(text, numbers))

    public fun encodePrevious(response: RepeatPreviousResponse): String =
        json.encodeToString(RepeatPreviousResponse.serializer(), response)
}
