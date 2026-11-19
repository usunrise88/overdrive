package app.overdrive.core.live

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract (docs/adr/0038, 0039): the Kotlin models of the live coach must agree with `contracts/schema/live-*.schema.json`
 * on every fixture under `contracts/fixtures/live-coach`. The TypeScript tests of `contracts/` run the same files against the schemas.
 * The workout's own fields and the planned workouts that carry them are in `WorkoutContractTest` and `PlanApiContractTest`.
 */
class LiveCoachContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private class Kind<T>(
        val directory: String,
        val decode: (String) -> T,
        val encode: ((T) -> String)?,
        val minimumValid: Int,
        val minimumInvalid: Int,
    )

    private val kinds: List<Kind<*>> =
        listOf(
            Kind("session-start", LiveCoachJson::decodeSessionStart, LiveCoachJson::encodeSessionStart, 4, 10),
            Kind(
                "session-start-response",
                LiveCoachJson::decodeSessionStartResponse,
                LiveCoachJson::encodeSessionStartResponse,
                7,
                10,
            ),
            Kind("snapshot", LiveCoachJson::decodeSnapshot, LiveCoachJson::encodeSnapshot, 9, 30),
            Kind("cue", LiveCoachJson::decodeCue, LiveCoachJson::encodeCue, 7, 25),
            Kind("stream-end", LiveCoachJson::decodeStreamEnd, null, 3, 3),
            Kind("session-end", LiveCoachJson::decodeSessionEnd, LiveCoachJson::encodeSessionEnd, 5, 4),
            Kind("session-end-response", LiveCoachJson::decodeSessionEndResponse, null, 2, 5),
            Kind("cue-played", LiveCoachJson::decodeCuePlayed, LiveCoachJson::encodeCuePlayed, 2, 4),
        )

    private fun files(
        kind: Kind<*>,
        validity: String,
    ): List<File> =
        File(contracts, "fixtures/live-coach/${kind.directory}/$validity")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    /** What a fixture says, without its nulls: the writer leaves an absent reading out where a fixture may spell `null`. */
    private fun withoutNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.mapValues { withoutNulls(it.value) })
            is JsonArray -> JsonArray(element.map(::withoutNulls))
            is JsonNull -> element
            is JsonPrimitive -> element
        }

    @Test
    fun `finds the fixtures of every kind`() {
        for (kind in kinds) {
            assertTrue(files(kind, "valid").size >= kind.minimumValid, "too few valid ${kind.directory} fixtures")
            assertTrue(files(kind, "invalid").size >= kind.minimumInvalid, "too few invalid ${kind.directory} fixtures")
        }
    }

    @Test
    fun `decodes every valid fixture, and what it writes back says the same`() {
        for (kind in kinds) {
            @Suppress("UNCHECKED_CAST")
            kind as Kind<Any?>
            for (file in files(kind, "valid")) {
                val value = kind.decode(file.readText())
                val encode = kind.encode ?: continue

                @Suppress("UNCHECKED_CAST")
                val written = (encode as (Any?) -> String)(value)
                assertEquals(value, kind.decode(written), "${kind.directory}/${file.name} survives a round trip")
                assertEquals(
                    withoutNulls(Json.parseToJsonElement(file.readText())),
                    withoutNulls(Json.parseToJsonElement(written)),
                    "${kind.directory}/${file.name} is written as it was read",
                )
            }
        }
    }

    @Test
    fun `rejects every invalid fixture`() {
        for (kind in kinds) {
            for (file in files(kind, "invalid")) {
                assertFailsWith<IllegalArgumentException>(
                    "${kind.directory}/${file.name}",
                ) { kind.decode(file.readText()) }
            }
        }
    }

    @Test
    fun `a message about a bad snapshot names the path and never the value`() {
        val text = File(contracts, "fixtures/live-coach/snapshot/valid/steady-step.json").readText()
        val bad = text.replace("\"power\": 251", "\"power\": 3777")
        val error = assertFailsWith<IllegalArgumentException> { LiveCoachJson.decodeSnapshot(bad) }
        assertTrue(error.message.orEmpty().contains("power"), error.message)
        assertFalse(error.message.orEmpty().contains("3777"), "the rider's reading is not in the message")
    }

    @Test
    fun `a number or a boolean written as a text is not read`() {
        val snapshot = File(contracts, "fixtures/live-coach/snapshot/valid/steady-step.json").readText()
        assertFailsWith<IllegalArgumentException> {
            LiveCoachJson.decodeSnapshot(snapshot.replace("\"seq\": 41", "\"seq\": \"41\""))
        }
        assertFailsWith<IllegalArgumentException> {
            LiveCoachJson.decodeSnapshot(snapshot.replace("\"muted\": false", "\"muted\": \"false\""))
        }
        val answer = File(contracts, "fixtures/live-coach/session-start-response/valid/coach-on.json").readText()
        assertFailsWith<IllegalArgumentException> {
            LiveCoachJson.decodeSessionStartResponse(answer.replace("\"on\": true", "\"on\": \"true\""))
        }
    }

    @Test
    fun `a start is written with only what the service needs`() {
        val start =
            LiveSessionStart(
                rideId = "ride-2026-11-19-0742",
                workoutId = "icu-6001",
                structureHash = "833a9d4d7f4ebf35",
                variantRidden = false,
                clock = 1_795_000_000_000,
            )
        assertEquals(
            """{"rideId":"ride-2026-11-19-0742","workoutId":"icu-6001","structureHash":"833a9d4d7f4ebf35","variantRidden":false,"clock":1795000000000}""",
            LiveCoachJson.encodeSessionStart(start),
        )
        val bare = LiveSessionStart(rideId = "r1", clock = 5)
        assertEquals("""{"rideId":"r1","clock":5}""", LiveCoachJson.encodeSessionStart(bare))
        assertNull(LiveCoachJson.decodeSessionStart(LiveCoachJson.encodeSessionStart(bare)).workoutId)
    }

    @Test
    fun `an end with nothing to say is an empty object`() {
        assertEquals("{}", LiveCoachJson.encodeSessionEnd(LiveSessionEnd()))
        assertEquals(
            """{"reason":"stopped","clock":7}""",
            LiveCoachJson.encodeSessionEnd(LiveSessionEnd(LiveSessionEndReason.STOPPED, 7)),
        )
        assertEquals("""{"played":true}""", LiveCoachJson.encodeCuePlayed(LiveCuePlayed(true)))
    }

    @Test
    fun `a cue is what the stream carried, the speech decodes, and nothing of it is printed`() {
        val cue = LiveCoachJson.decodeCue(File(contracts, "fixtures/live-coach/cue/valid/a-model-line.json").readText())
        assertEquals(3, cue.id)
        assertEquals("step-start", cue.trigger)
        assertEquals(8_000, cue.ttlMs)
        assertEquals(LiveAudioFormat.MP3, cue.audio.format)
        assertEquals(10, cue.audio.byteCount())
        assertEquals(10, cue.audio.decode().size)
        assertFalse(cue.toString().contains(cue.text))
        assertFalse(cue.toString().contains(cue.audio.base64))
        assertFalse(cue.audio.toString().contains(cue.audio.base64))
    }

    @Test
    fun `a trigger a later service adds is a plain string`() {
        val cue =
            LiveCoachJson.decodeCue(
                File(contracts, "fixtures/live-coach/cue/valid/a-trigger-name-a-later-service-adds.json").readText(),
            )
        assertNotNull(cue.trigger)
    }

    @Test
    fun `a snapshot never prints the rider's readings`() {
        val snapshot =
            LiveCoachJson.decodeSnapshot(
                File(contracts, "fixtures/live-coach/snapshot/valid/steady-step.json").readText(),
            )
        assertEquals("LiveSnapshot(seq=41)", snapshot.toString())
    }

    @Test
    fun `an answer with the coach off has a reason and no session, and a reason of a newer service is not read`() {
        val off =
            LiveCoachJson.decodeSessionStartResponse(
                File(contracts, "fixtures/live-coach/session-start-response/valid/off-no-tts.json").readText(),
            )
        assertNull(off.sessionId)
        assertEquals(LiveCoachOffReason.NO_TTS, off.coach.reason)
        val unknown =
            File(
                contracts,
                "fixtures/live-coach/session-start-response/valid/off-no-tts.json",
            ).readText().replace("no-tts", "no-gpu")
        assertFailsWith<IllegalArgumentException> { LiveCoachJson.decodeSessionStartResponse(unknown) }
    }
}
