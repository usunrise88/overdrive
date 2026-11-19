package app.overdrive.core.notebook

import app.overdrive.core.sync.parseStrictObject
import kotlinx.serialization.json.Json

/**
 * Reads and writes the body of `GET /v1/notebook` (spec §11.6, ADR 0035). Strict like the schema: unknown keys, a number written in quotes,
 * a missing key and every rule of [NotebookResponse] are rejected with an [IllegalArgumentException] that names the rule and never a text.
 */
public object NotebookApiJson {
    private val json = Json.Default

    private val numbers = setOf("maxChars", "logMaxChars", "totalChars", "version")

    /** @throws IllegalArgumentException if [text] is not an answer of `GET /v1/notebook` as the schema says. */
    public fun decodeNotebook(text: String): NotebookResponse =
        json.decodeFromJsonElement(NotebookResponse.serializer(), parseStrictObject(text, numbers))

    public fun encodeNotebook(response: NotebookResponse): String =
        json.encodeToString(NotebookResponse.serializer(), response)
}
