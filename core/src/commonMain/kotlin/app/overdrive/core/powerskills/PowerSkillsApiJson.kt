package app.overdrive.core.powerskills

import app.overdrive.core.sync.parseStrictObject
import kotlinx.serialization.json.Json

/**
 * Reads and writes the body of `GET /v1/power-skills` (spec §6.3, docs/adr/0042). Strict like the schema: unknown keys, a number
 * written in quotes, a missing key and every rule of [PowerSkillsResponse] are rejected with an [IllegalArgumentException] that names
 * the rule and never a figure.
 */
public object PowerSkillsApiJson {
    private val json = Json.Default

    private val numbers =
        setOf(
            "watts",
            "wattsPerKg",
            "score",
            "seconds",
            "fromSeconds",
            "toSeconds",
            "index",
            "rideCount",
            "kg",
            "days",
        )

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/power-skills` as the schema says. */
    public fun decodePowerSkills(text: String): PowerSkillsResponse =
        json.decodeFromJsonElement(PowerSkillsResponse.serializer(), parseStrictObject(text, numbers))

    public fun encodePowerSkills(response: PowerSkillsResponse): String =
        json.encodeToString(PowerSkillsResponse.serializer(), response)
}
