package app.overdrive.core.notebook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of `GET /v1/notebook` must agree with `contracts/schema/notebook-response.schema.json` on every
 * fixture under `contracts/fixtures/notebook/notebook-response`. The TypeScript tests of `contracts/` run the same fixtures.
 */
class NotebookContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(dir: String): List<File> =
        File(contracts, "fixtures/notebook/notebook-response/$dir")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (a fixture writes `12000`, Kotlin writes it the same, but a number may come back as a Double). */
    private fun normalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(element.mapValues { normalize(it.value) })
            }

            is JsonArray -> {
                JsonArray(element.map(::normalize))
            }

            is JsonNull -> {
                element
            }

            is JsonPrimitive -> {
                if (element.isString) element else element.content.toDoubleOrNull()?.let(::JsonPrimitive) ?: element
            }
        }

    @Test
    fun `reads every valid notebook and writes it back the same`() {
        val files = fixtures("valid")
        assertTrue(files.size >= 5, "at least five valid fixtures")
        for (file in files) {
            val value = NotebookApiJson.decodeNotebook(file.readText())
            val written = NotebookApiJson.encodeNotebook(value)
            assertEquals(value, NotebookApiJson.decodeNotebook(written), file.name)
            assertEquals(
                normalize(Json.parseToJsonElement(file.readText())),
                normalize(Json.parseToJsonElement(written)),
                "${file.name} written back",
            )
        }
    }

    @Test
    fun `rejects every invalid notebook`() {
        val files = fixtures("invalid")
        assertTrue(files.size >= 25, "at least 25 invalid fixtures")
        for (file in files) {
            assertFailsWith<IllegalArgumentException>("invalid/${file.name}") {
                NotebookApiJson.decodeNotebook(file.readText())
            }
        }
    }

    @Test
    fun `an empty notebook is the one with no section ever written`() {
        val empty = fixtures("valid").map { NotebookApiJson.decodeNotebook(it.readText()) }.filter { it.isEmpty }
        assertTrue(empty.isNotEmpty(), "a valid fixture is an empty notebook")
        for (notebook in empty) assertEquals(0, notebook.totalChars)
    }

    @Test
    fun `a rejection names the rule and never the text of a section`() {
        val text = fixtures("valid").single { it.name == "filled.json" }.readText()
        val secret = "SECRET-PLAN-TEXT-7731"
        val tampered = text.replace("\"version\": 3", "\"version\": -1").replace("Works full-time", secret)
        val error = assertFailsWith<IllegalArgumentException> { NotebookApiJson.decodeNotebook(tampered) }
        assertTrue(error.message.orEmpty().isNotEmpty())
        assertTrue(secret !in error.message.orEmpty(), error.message)
    }
}
