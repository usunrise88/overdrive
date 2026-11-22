package app.overdrive.core.progress

import app.overdrive.core.sync.parseStrictObject
import kotlinx.serialization.json.Json

/**
 * Reads and writes the body of `GET /v1/progress` (spec §6.3 "Progress over time", §8.3, ADR 0043). Strict like the schema in
 * `contracts/schema/`: unknown keys, wrong types (a number in quotes is not a number), missing keys and every rule of the schema's
 * description are rejected with an [IllegalArgumentException]. A message names a field and the rule, never a figure.
 */
public object ProgressApiJson {
    private val json = Json.Default

    // `from` is left out on purpose: it is a number in a trend and a date in the range, and a date is a string.
    private val numbers =
        setOf(
            "points",
            "minPoints",
            "change",
            "difference",
            "latest",
            "steadyBand",
            "lines",
            "watts",
            "wattsPerKg",
            "weightKg",
            "ftpAtStart",
            "referencePower",
            "referenceHeartRate",
            "heartRateAtPower",
            "powerAtHeartRate",
            "stretches",
            "seconds",
            "value",
            "normalizedPower",
            "averageHeartRate",
            "driftPercent",
            "mean",
            "nights",
            "kg",
            "weighings",
            "ftp",
        )
    private val booleans = setOf("stale", "partial")

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/progress` as the schema says. */
    public fun decode(text: String): ProgressResponse =
        json.decodeFromJsonElement(ProgressResponse.serializer(), parseStrictObject(text, numbers, booleans))

    public fun encode(response: ProgressResponse): String = json.encodeToString(ProgressResponse.serializer(), response)
}
