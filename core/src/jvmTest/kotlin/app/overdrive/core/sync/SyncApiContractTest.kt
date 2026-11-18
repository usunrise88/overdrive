package app.overdrive.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the upload response and the error body must agree with
 * `contracts/schema/sync-upload-response.schema.json` and `api-error.schema.json` on every fixture under
 * `contracts/fixtures/sync`. The TypeScript tests of `contracts/` run the same fixtures against the schemas.
 */
class SyncApiContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(
        set: String,
        kind: String,
    ): List<File> =
        File(contracts, "fixtures/sync/$set/$kind")
            .listFiles { f ->
                f.extension == "json"
            }.orEmpty()
            .sortedBy { it.name }

    @Test
    fun `finds fixtures`() {
        for (set in listOf("upload-response", "api-error")) {
            assertTrue(fixtures(set, "valid").size >= 3, "too few valid fixtures for $set")
            assertTrue(fixtures(set, "invalid").size >= 5, "too few invalid fixtures for $set")
        }
    }

    @Test
    fun `reads every valid upload response and writes it back the same`() {
        for (file in fixtures("upload-response", "valid")) {
            val response = SyncApiJson.decodeUploadResponse(file.readText())
            assertEquals(
                response,
                SyncApiJson.decodeUploadResponse(SyncApiJson.encodeUploadResponse(response)),
                file.name,
            )
            assertEquals(
                Json.parseToJsonElement(file.readText()),
                Json.parseToJsonElement(SyncApiJson.encodeUploadResponse(response)),
            )
        }
    }

    @Test
    fun `rejects every invalid upload response`() {
        for (file in fixtures("upload-response", "invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { SyncApiJson.decodeUploadResponse(file.readText()) }
        }
    }

    @Test
    fun `reads every valid error body and writes it back the same`() {
        for (file in fixtures("api-error", "valid")) {
            val error = SyncApiJson.decodeApiError(file.readText())
            assertEquals(error, SyncApiJson.decodeApiError(SyncApiJson.encodeApiError(error)), file.name)
            assertEquals(
                Json.parseToJsonElement(file.readText()),
                Json.parseToJsonElement(SyncApiJson.encodeApiError(error)),
            )
        }
    }

    @Test
    fun `rejects every invalid error body`() {
        for (file in fixtures("api-error", "invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { SyncApiJson.decodeApiError(file.readText()) }
        }
    }

    @Test
    fun `the error codes are the schema's, one for one`() {
        val schema = Json.parseToJsonElement(File(contracts, "schema/api-error.schema.json").readText()).jsonObject
        val codes =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("code")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        val kotlin =
            ApiErrorCode.entries.map {
                Json
                    .parseToJsonElement(SyncApiJson.encodeApiError(ApiError(it, "x")))
                    .jsonObject
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive
                    .content
            }
        assertEquals(codes.toSet(), kotlin.toSet())
        assertEquals(codes.size, kotlin.size)
    }

    @Test
    fun `the ride id syntax is the schema's`() {
        val schema =
            Json
                .parseToJsonElement(
                    File(contracts, "schema/sync-upload-response.schema.json").readText(),
                ).jsonObject
        val pattern =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("rideId")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive
                .content
        assertEquals("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", pattern)
        assertTrue(SyncApiJson.isValidRideId("ride-1794996000000"))
        assertTrue(
            !SyncApiJson.isValidRideId("../ride") && !SyncApiJson.isValidRideId("") &&
                !SyncApiJson.isValidRideId("x".repeat(129)),
        )
    }
}
