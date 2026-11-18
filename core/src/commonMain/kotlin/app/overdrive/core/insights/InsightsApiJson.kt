package app.overdrive.core.insights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads and writes the bodies of the insights part of the sync API (spec §8.3, ADR 0025). Strict like the schemas in
 * `contracts/schema/`: unknown keys, wrong types (a number in quotes is not a number, a whole number is not fractional), missing keys
 * and every rule of the schemas' descriptions are rejected with an [IllegalArgumentException]. A message names a field and the rule,
 * never the text of a note.
 */
public object InsightsApiJson {
    /** Every key must be known and present; a key without a value is written as null, as the schemas want. */
    private val strict = Json.Default

    private val numbers = setOf("id")
    private val booleans = setOf("stale")

    /** kotlinx reads `"12"` as the number 12 and `"false"` as false; the schemas do not. */
    private fun requireNumbers(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    require(
                        key !in numbers || value !is JsonPrimitive || !value.isString,
                    ) { "$key must be a number, not a string" }
                    require(
                        key !in booleans || value !is JsonPrimitive || !value.isString,
                    ) { "$key must be true or false, not a string" }
                    requireNumbers(value)
                }
            }

            is JsonArray -> {
                element.forEach(::requireNumbers)
            }

            else -> {}
        }
    }

    private fun parse(text: String): JsonElement {
        val root = strict.parseToJsonElement(text)
        require(root is JsonObject) { "The body is a JSON object" }
        requireNumbers(root)
        return root
    }

    /** @throws IllegalArgumentException if [text] is not an insights answer of the schema. */
    public fun decodeResponse(text: String): InsightsResponse =
        strict.decodeFromJsonElement(InsightsResponse.serializer(), parse(text))

    public fun encodeResponse(response: InsightsResponse): String =
        strict.encodeToString(InsightsResponse.serializer(), response)

    /** @throws IllegalArgumentException if [text] is not a discussion topic of the schema. */
    public fun decodeTopic(text: String): DiscussionTopic =
        strict.decodeFromJsonElement(DiscussionTopic.serializer(), parse(text))

    public fun encodeTopic(topic: DiscussionTopic): String = strict.encodeToString(DiscussionTopic.serializer(), topic)
}
