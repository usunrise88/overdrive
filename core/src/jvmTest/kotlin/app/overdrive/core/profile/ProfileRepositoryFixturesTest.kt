package app.overdrive.core.profile

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchRequest
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tablet's side of the profile against the files of `contracts/fixtures/profile`, the same ones the service's tests, the
 * TypeScript contracts and `ProfileContractTest` use: every valid profile is taken in from the service and kept; every invalid one
 * is "not the service" and changes nothing; every valid patch is written by [ProfileApiJson.encodePatch] as the schema says, read
 * back as the same patch, and sent through the repository to a service that applies it and answers `after`.
 */
class ProfileRepositoryFixturesTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))
    private val config = SyncConfig("https://overdrive.example.org", "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7")

    private fun fixtures(path: String): List<File> =
        File(contracts, "fixtures/profile/$path")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** Numbers compare by value (the fixtures write `80`, Kotlin writes a Double as `80.0`). */
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
                if (element.isString) {
                    element
                } else {
                    element.content.toDoubleOrNull()?.let(::JsonPrimitive)
                        ?: element
                }
            }
        }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private class Answering(
        var get: String,
        var patch: String = "{}",
    ) : HttpGetTransport,
        HttpPatchTransport {
        val sentPatches = mutableListOf<String>()

        override suspend fun get(request: HttpGetRequest) = HttpAnswer(200, get)

        override suspend fun patch(request: HttpPatchRequest): HttpAnswer {
            sentPatches += request.body
            return HttpAnswer(200, patch)
        }
    }

    private fun repository(
        service: Answering,
        cache: MemoryProfileCache = MemoryProfileCache(),
    ) = ProfileRepository(
        config = { config },
        reader = service,
        writer = service,
        cache = cache,
        elapsedMillis = { 1L },
    )

    @Test
    fun `every valid profile is taken in and kept, and a restart reads it back the same`() =
        runTest {
            val files = fixtures("profile/valid")
            assertTrue(files.size >= 4)
            for (file in files) {
                val cache = MemoryProfileCache()
                val repository = repository(Answering(file.readText()), cache)
                repository.refresh(ProfileRefresh.START)
                val expected = ProfileApiJson.decodeProfile(file.readText())
                assertEquals(expected, repository.state.value.profile, file.name)
                assertNull(repository.state.value.problem, file.name)
                val restarted = repository(Answering("offline"), cache)
                restarted.load()
                assertEquals(expected, restarted.state.value.profile, file.name)
            }
        }

    @Test
    fun `every invalid profile is not the service and changes nothing`() =
        runTest {
            val files = fixtures("profile/invalid")
            assertTrue(files.size >= 30)
            for (file in files) {
                val cache = MemoryProfileCache()
                val repository = repository(Answering(file.readText()), cache)
                repository.refresh(ProfileRefresh.START)
                assertNull(repository.state.value.profile, file.name)
                assertEquals(
                    ProfileProblem.Failed("That is not the Overdrive service"),
                    repository.state.value.problem,
                    file.name,
                )
                assertNull(cache.text, file.name)
            }
        }

    @Test
    fun `every valid patch is written as the schema says and read back as the same patch`() {
        val files = fixtures("patch/valid")
        assertTrue(files.size >= 12)
        for (file in files) {
            val fixture = parse(file.readText()).jsonObject
            val patchJson = fixture.getValue("patch").toString()
            val patch = ProfileApiJson.decodePatch(patchJson)
            val written = ProfileApiJson.encodePatch(patch)
            assertEquals(patch, ProfileApiJson.decodePatch(written), file.name)
            assertEquals(normalize(parse(patchJson)), normalize(parse(written)), file.name)
            assertEquals(!patch.isEmpty, written != "{}", file.name)
        }
    }

    @Test
    fun `every valid patch, made on the tablet, is sent and the service's answer is the profile`() =
        runTest {
            for (file in fixtures("patch/valid")) {
                val fixture = parse(file.readText()).jsonObject
                val before = fixture.getValue("before").toString()
                val patch = ProfileApiJson.decodePatch(fixture.getValue("patch").toString())
                val after = fixture.getValue("after").toString()
                if (patch.isEmpty) continue
                val service = Answering(get = before, patch = after)
                val repository = repository(service)
                repository.refresh(ProfileRefresh.START)

                assertTrue(repository.edit(patch), file.name)
                assertEquals(
                    ProfileApiJson.decodeProfile(after),
                    repository.state.value.profile,
                    "${file.name}: in force at once",
                )
                repository.refresh(ProfileRefresh.EDITED)
                assertEquals(1, service.sentPatches.size, file.name)
                assertEquals(patch, ProfileApiJson.decodePatch(service.sentPatches.single()), file.name)
                assertEquals(ProfileApiJson.decodeProfile(after), repository.state.value.profile, file.name)
                assertNull(repository.state.value.pending, file.name)
            }
        }

    @Test
    fun `the patches of every fixture merge the way they apply, one after the other`() {
        val fixtureList = fixtures("patch/valid").map { parse(it.readText()).jsonObject }
        val patches = fixtureList.map { ProfileApiJson.decodePatch(it.getValue("patch").toString()) }
        val start = ProfileApiJson.decodeProfile(fixtureList.first().getValue("before").toString())
        for (first in patches) {
            for (second in patches) {
                val inTurn =
                    try {
                        second.applyTo(first.applyTo(start))
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                assertEquals(inTurn, first.andThen(second).applyTo(start))
            }
        }
    }
}
