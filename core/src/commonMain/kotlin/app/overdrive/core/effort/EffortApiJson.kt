package app.overdrive.core.effort

import app.overdrive.core.sync.parseStrictObject
import kotlinx.serialization.json.Json

/**
 * Reads and writes the body of `GET /v1/effort` (spec §6.3, docs/adr/0042). Strict like the schema: unknown keys, a number or a
 * boolean written in quotes, a missing key and every rule of [EffortResponse] are rejected with an [IllegalArgumentException] that
 * names the rule and never a figure.
 */
public object EffortApiJson {
    private val json = Json.Default

    private val numbers =
        setOf("weeksNeeded", "weeksLogged", "load", "usualLow", "usualHigh", "share")
    private val booleans = setOf("approximate", "current")

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/effort` as the schema says. */
    public fun decodeEffort(text: String): EffortResponse =
        json.decodeFromJsonElement(EffortResponse.serializer(), parseStrictObject(text, numbers, booleans))

    public fun encodeEffort(response: EffortResponse): String =
        json.encodeToString(EffortResponse.serializer(), response)
}
