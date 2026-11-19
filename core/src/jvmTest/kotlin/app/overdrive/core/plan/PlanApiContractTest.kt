package app.overdrive.core.plan

import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.HeartRateStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Step
import app.overdrive.core.workout.Workout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the planned workouts answer must agree with
 * `contracts/schema/workouts-response.schema.json` on every fixture under `contracts/fixtures/plan/workouts-response`.
 * The TypeScript tests of `contracts/` run the same fixtures against the schema.
 */
class PlanApiContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    /** The plan fixtures, and those of the easier variant and the route ids of stage A2b-1 under `fixtures/routes/workouts-response`. */
    private fun fixtures(kind: String): List<File> =
        listOf(
            File(contracts, "fixtures/plan/workouts-response/$kind"),
            File(contracts, "fixtures/routes/workouts-response/$kind"),
        ).flatMap { dir -> dir.listFiles { f -> f.extension == "json" }.orEmpty().toList() }
            .sortedBy { it.name }

    /**
     * The one rule of the schema's description that only the service enforces: a variant's `structureHash` is the `workoutHash` of the
     * primary with the variant's steps. The tablet never computes it (it only groups sessions on the service), so this fixture, whose hash
     * is the primary's own, is the one the strict decoder accepts; [the hash of a variant is the service's one] checks the others
     * against a port of the algorithm.
     */
    private val serviceOnly = setOf("variant-hash-of-the-primary.json")

    /** Numbers compare by value (the fixtures write `90`, Kotlin writes a Double as `90.0`). */
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
    fun `finds fixtures`() {
        assertTrue(fixtures("valid").size >= 5, "too few valid fixtures")
        assertTrue(fixtures("invalid").size >= 15, "too few invalid fixtures")
    }

    @Test
    fun `reads every valid fixture and writes it back the same`() {
        for (file in fixtures("valid")) {
            val response = PlanApiJson.decodeWorkoutsResponse(file.readText())
            val written = PlanApiJson.encodeWorkoutsResponse(response)
            assertEquals(response, PlanApiJson.decodeWorkoutsResponse(written), file.name)
            assertEquals(
                normalize(Json.parseToJsonElement(file.readText())),
                normalize(Json.parseToJsonElement(written)),
                file.name,
            )
        }
    }

    @Test
    fun `rejects every invalid fixture`() {
        for (file in fixtures("invalid").filter { it.name !in serviceOnly }) {
            assertFailsWith<IllegalArgumentException>(file.name) { PlanApiJson.decodeWorkoutsResponse(file.readText()) }
        }
    }

    @Test
    fun `the hash of a variant is the service's one`() {
        // A port of `workoutHash` of contracts/src/repeats.ts, test-only: the tablet does not compute it.
        fun token(step: Step): List<String> =
            when (step) {
                is SteadyStep -> {
                    listOf("s${step.duration}@${(step.target * 1000).roundToLong()}")
                }

                is RampStep -> {
                    listOf("r${step.duration}@${(step.from * 1000).roundToLong()}-${(step.to * 1000).roundToLong()}")
                }

                is FreeStep -> {
                    listOf("f${step.duration}")
                }

                is HeartRateStep -> {
                    listOf(
                        "h${step.duration}@" +
                            (step.targetBpm?.toString() ?: "l${((step.targetLthr ?: 0.0) * 1000).roundToLong()}"),
                    )
                }

                is RepeatStep -> {
                    List(step.count) { step.steps.flatMap(::token) }.flatten()
                }
            }

        fun hash(workout: Workout): String {
            val text = "overdrive.workout-structure.v1\n" + workout.steps.flatMap(::token).joinToString(",")
            return MessageDigest
                .getInstance("SHA-256")
                .digest(text.encodeToByteArray())
                .joinToString("") {
                    "%02x".format(it)
                }.take(16)
        }
        val all = fixtures("valid")
        val withVariants =
            all.flatMap { PlanApiJson.decodeWorkoutsResponse(it.readText()).workouts }.filter {
                it.variant !=
                    null
            }
        assertTrue(withVariants.size >= 2, "the fixtures have variants")
        for (planned in withVariants) {
            assertEquals(hash(checkNotNull(planned.variantWorkout())), planned.variant?.structureHash, planned.id)
        }
        // The invalid one carries the hash of the primary, which is not the variant's.
        val wrong = fixtures("invalid").single { it.name == "variant-hash-of-the-primary.json" }
        val entry = PlanApiJson.decodeWorkoutsResponse(wrong.readText()).workouts.single()
        assertEquals(hash(entry.workout), entry.variant?.structureHash)
        assertTrue(hash(checkNotNull(entry.variantWorkout())) != entry.variant?.structureHash)
    }

    @Test
    fun `an entry with a variant is the planned workout and the easier version, and one without is as it was`() {
        val response =
            PlanApiJson.decodeWorkoutsResponse(
                File(contracts, "fixtures/routes/workouts-response/valid/with-variant.json").readText(),
            )
        val planned = response.workouts.single()
        val variant = assertNotNull(planned.variant)
        assertEquals("Two efforts at 80%, about 25 minutes shorter.", variant.note)
        assertEquals("7b1d651527c24b0c", variant.structureHash)
        // The tablet plays the planned workout with the variant's steps; the id, and so the ride's workoutId, stays the planned one.
        val easier = assertNotNull(planned.variantWorkout())
        assertEquals(planned.id, easier.id)
        assertEquals(planned.workout.name, easier.name)
        assertEquals(variant.steps, easier.steps)
        assertTrue(easier.steps != planned.workout.steps)

        val noNote =
            PlanApiJson
                .decodeWorkoutsResponse(
                    File(contracts, "fixtures/routes/workouts-response/valid/variant-without-note.json").readText(),
                ).workouts
                .single()
        assertNull(noNote.variant?.note)

        val before =
            PlanApiJson.decodeWorkoutsResponse(
                File(contracts, "fixtures/plan/workouts-response/valid/week.json").readText(),
            )
        assertTrue(before.workouts.all { it.variant == null && it.variantWorkout() == null })
        // A variant that is absent is not written: an answer without one is written exactly as it was.
        assertTrue(!PlanApiJson.encodeWorkoutsResponse(before).contains("variant"))
    }

    @Test
    fun `reads the structure hash and keeps it, and an answer without one has none`() {
        val hashed =
            PlanApiJson.decodeWorkoutsResponse(
                File(contracts, "fixtures/plan/workouts-response/valid/week-with-structure-hashes.json").readText(),
            )
        assertEquals(3, hashed.workouts.size)
        assertTrue(hashed.workouts.all { it.structureHash != null && it.structureHash.length == 16 })
        val plain =
            PlanApiJson.decodeWorkoutsResponse(
                File(contracts, "fixtures/plan/workouts-response/valid/week.json").readText(),
            )
        assertTrue(plain.workouts.all { it.structureHash == null })
        // The tablet never works a hash out: what it reads is what it writes back.
        assertEquals(hashed, PlanApiJson.decodeWorkoutsResponse(PlanApiJson.encodeWorkoutsResponse(hashed)))
    }

    @Test
    fun `the week fixture has the expected shape`() {
        val week =
            PlanApiJson.decodeWorkoutsResponse(
                File(contracts, "fixtures/plan/workouts-response/valid/week.json").readText(),
            )
        assertEquals(listOf("icu-1001", "icu-1002", "icu-1003"), week.workouts.map { it.id })
        assertEquals("2026-11-19T07:30:12Z", week.generatedAt)
        assertEquals(false, week.stale)
        assertEquals(true, week.configured)
        assertEquals(PlanSource.INTERVALS_ICU, week.workouts[0].source)
        assertIs<RepeatStep>(week.workouts[0].workout.steps[1])
        assertEquals(FreeStep(duration = 3600), week.workouts[2].workout.steps.single())
    }

    @Test
    fun `an answer that was never refreshed has no generatedAt`() {
        val file = File(contracts, "fixtures/plan/workouts-response/valid/never-refreshed.json")
        val response = PlanApiJson.decodeWorkoutsResponse(file.readText())
        assertNull(response.generatedAt)
        assertTrue(response.stale)
        // The key is written even when null: the schema requires it.
        assertNotNull(Json.parseToJsonElement(PlanApiJson.encodeWorkoutsResponse(response)).jsonObject["generatedAt"])
    }

    @Test
    fun `the source and the id syntax are the schema's`() {
        val schema =
            Json.parseToJsonElement(File(contracts, "schema/workouts-response.schema.json").readText()).jsonObject
        val entry =
            schema
                .getValue(
                    "\$defs",
                ).jsonObject
                .getValue("plannedWorkout")
                .jsonObject
                .getValue("properties")
                .jsonObject
        val sources =
            entry
                .getValue("source")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        assertEquals(listOf("intervals.icu"), sources)
        assertEquals(
            sources,
            PlanSource.entries.map {
                Json
                    .encodeToString(PlanSource.serializer(), it)
                    .trim('"')
            },
        )
        val pattern =
            entry
                .getValue("id")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive.content
        assertEquals("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\$", pattern)
        assertTrue(PlanApiJson.isValidPlannedWorkoutId("icu-123"))
        assertTrue(
            !PlanApiJson.isValidPlannedWorkoutId("../icu-1") && !PlanApiJson.isValidPlannedWorkoutId("") &&
                !PlanApiJson.isValidPlannedWorkoutId("x".repeat(129)),
        )
        val instant =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("generatedAt")
                .jsonObject
                .getValue("pattern")
                .jsonPrimitive
                .content
        assertEquals("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z\$", instant)
        assertTrue(
            PlanApiJson.isValidInstant("2026-11-19T07:30:12Z") && PlanApiJson.isValidInstant("2026-11-19T07:30:12.5Z"),
        )
        assertTrue(!PlanApiJson.isValidInstant("2026-11-19T07:30:12+01:00"))
    }

    @Test
    fun `the longest range is the schema's`() {
        val schema =
            Json.parseToJsonElement(File(contracts, "schema/workouts-response.schema.json").readText()).jsonObject
        assertTrue(
            schema.getValue("description").jsonPrimitive.content.contains("at most ${PlanApiJson.MAX_RANGE_DAYS} days"),
        )
        assertEquals(62, PlanApiJson.MAX_RANGE_DAYS)
    }

    @Test
    fun `calendar days agree with java time over the years`() {
        var date = LocalDate.of(1999, 12, 25)
        repeat(12_000) {
            assertEquals(date.toEpochDay(), PlanDates.epochDay(date.toString()), date.toString())
            date = date.plusDays(1)
        }
        for (bad in listOf(
            "2026-02-30",
            "2027-02-29",
            "2026-13-01",
            "2026-00-10",
            "2026-11-31",
            "0026-11-18",
            "2026-1-18",
            "",
        )) {
            assertNull(PlanDates.epochDay(bad), bad)
        }
        assertNotNull(PlanDates.epochDay("2028-02-29"))
    }
}
