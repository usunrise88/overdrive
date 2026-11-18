package app.overdrive.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The strictness the schemas have and kotlinx.serialization lacks: it reads `"12"` as the number 12 and `"false"` as false.
 * [parseStrictObject] parses a body that must be a JSON object and rejects a string where [numbers] names a number or
 * [booleans] a boolean, at any depth, with an [IllegalArgumentException] that names the key and never the value.
 */
internal fun parseStrictObject(
    text: String,
    numbers: Set<String>,
    booleans: Set<String> = emptySet(),
): JsonObject {
    val root = Json.parseToJsonElement(text)
    require(root is JsonObject) { "The body is a JSON object" }
    requireTypes(root, numbers, booleans)
    return root
}

private fun requireTypes(
    element: JsonElement,
    numbers: Set<String>,
    booleans: Set<String>,
) {
    when (element) {
        is JsonObject -> {
            for ((key, value) in element) {
                val isString = value is JsonPrimitive && value.isString
                require(key !in numbers || !isString) { "$key must be a number, not a string" }
                require(key !in booleans || !isString) { "$key must be true or false, not a string" }
                requireTypes(value, numbers, booleans)
            }
        }

        is JsonArray -> {
            element.forEach { requireTypes(it, numbers, booleans) }
        }

        else -> {}
    }
}
