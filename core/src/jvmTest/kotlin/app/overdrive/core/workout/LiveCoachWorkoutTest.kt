package app.overdrive.core.workout

import app.overdrive.core.ride.flatSteps
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workout's live-coach fields (spec §12.1, docs/adr/0038): read strictly, written only when there is something to say, and kept
 * out of everything the ride and the screens use. The fixtures themselves run in [WorkoutContractTest] and `PlanApiContractTest`.
 */
class LiveCoachWorkoutTest {
    private val json = Json
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixture(name: String): Workout =
        json.decodeFromString(File(contracts, "fixtures/live-coach/workout/valid/$name.json").readText())

    @Test
    fun `a workout without the live coach's fields is written as it was`() {
        val plain = Workout(1, "icu-1", "Plain", steps = listOf(SteadyStep(duration = 600, target = 0.9)))
        val text = json.encodeToString(Workout.serializer(), plain)
        assertFalse(text.contains("live_coach"))
        assertFalse(text.contains("coach_brief"))
        assertFalse(text.contains("must_say"))
        assertEquals(plain, json.decodeFromString(Workout.serializer(), text))
        assertFalse(plain.wantsLiveCoach)
    }

    @Test
    fun `the flag, the brief and the lines are read from the fixtures and written back`() {
        val coached = fixture("brief-and-must-say-in-a-repeat")
        assertTrue(coached.wantsLiveCoach)
        assertTrue(coached.coachBrief!!.isNotEmpty())
        val repeat = coached.steps.filterIsInstance<RepeatStep>().single()
        val lines = repeat.steps.mapNotNull { it.mustSay }.flatten()
        assertTrue(lines.isNotEmpty())
        assertEquals(
            coached,
            json.decodeFromString(Workout.serializer(), json.encodeToString(Workout.serializer(), coached)),
        )

        assertFalse(fixture("live-coach-false").wantsLiveCoach)
        assertEquals(false, fixture("live-coach-false").liveCoach)
        assertTrue(fixture("live-coach-flag-only").wantsLiveCoach)
        assertNull(fixture("live-coach-flag-only").coachBrief)
    }

    @Test
    fun `a line's moment is start or a second, and writes back as it was read`() {
        val all = fixture("must-say-at-seconds-three-lines")
        val moments = all.steps.filterIsInstance<LeafStep>().flatMap { it.mustSay.orEmpty() }.map { it.at }
        assertEquals(3, moments.size)
        assertEquals(listOf(MustSayMoment.Start, MustSayMoment.Second(300), MustSayMoment.Second(600)), moments)
        val text =
            """{"type":"steady","duration":60,"target":0.5,"must_say":[{"at":"start","text":"Go."},{"at":30,"text":"Half."}]}"""
        val step = json.decodeFromString(Step.serializer(), text)
        assertEquals(
            listOf(MustSay(MustSayMoment.Start, "Go."), MustSay(MustSayMoment.Second(30), "Half.")),
            assertIs<SteadyStep>(step).mustSay,
        )
        assertEquals(text, json.encodeToString(Step.serializer(), step))
    }

    @Test
    fun `a line's moment is nothing else`() {
        for (bad in listOf("\"30\"", "\"Start\"", "1.5", "0", "-1", "true", "null", "[]", "{}")) {
            val text = """{"type":"steady","duration":60,"target":0.5,"must_say":[{"at":$bad,"text":"Go."}]}"""
            assertFailsWith<IllegalArgumentException>(bad) { json.decodeFromString(Step.serializer(), text) }
        }
    }

    @Test
    fun `lines are counted in characters and not in code units`() {
        // 80 emoji are 160 UTF-16 code units and 80 characters: the contract counts characters, like a cue.
        val eighty = "😀".repeat(80)
        MustSay(MustSayMoment.Start, eighty)
        assertFailsWith<IllegalArgumentException> { MustSay(MustSayMoment.Start, eighty + "x") }
        assertFailsWith<IllegalArgumentException> { MustSay(MustSayMoment.Start, "   ") }
        assertFailsWith<IllegalArgumentException> { MustSay(MustSayMoment.Start, "") }
        val brief = "b".repeat(MAX_COACH_BRIEF_CHARS)
        Workout(1, "w", "W", steps = listOf(FreeStep(60)), coachBrief = brief)
        assertFailsWith<IllegalArgumentException> {
            Workout(1, "w", "W", steps = listOf(FreeStep(60)), coachBrief = brief + "b")
        }
        assertFailsWith<IllegalArgumentException> {
            Workout(1, "w", "W", steps = listOf(FreeStep(60)), coachBrief = " ")
        }
    }

    @Test
    fun `the rules a schema cannot state are the step's`() {
        val line = MustSay(MustSayMoment.Second(59), "Almost.")
        SteadyStep(duration = 60, target = 0.5, mustSay = listOf(line))
        assertFailsWith<IllegalArgumentException>("a line is spoken inside its step") {
            SteadyStep(duration = 60, target = 0.5, mustSay = listOf(MustSay(MustSayMoment.Second(60), "Late.")))
        }
        assertFailsWith<IllegalArgumentException>("two lines at the same moment") {
            FreeStep(duration = 60, mustSay = listOf(line, MustSay(MustSayMoment.Second(59), "Again.")))
        }
        assertFailsWith<IllegalArgumentException>("two lines at the start") {
            RampStep(
                duration = 60,
                from = 0.4,
                to = 0.6,
                mustSay = listOf(MustSay(MustSayMoment.Start, "A."), MustSay(MustSayMoment.Start, "B.")),
            )
        }
        assertFailsWith<IllegalArgumentException>("at most three lines") {
            SteadyStep(
                duration = 600,
                target = 0.5,
                mustSay = List(4) { MustSay(MustSayMoment.Second(it + 1), "L$it") },
            )
        }
        assertFailsWith<IllegalArgumentException>("an empty list is left out instead") {
            HeartRateStep(duration = 600, targetBpm = 130, powerMin = 80, powerMax = 200, mustSay = emptyList())
        }
    }

    @Test
    fun `the flag is true or false and not a word, a number or a string`() {
        fun read(value: String): Workout =
            json.decodeFromString(
                """{"schema":1,"id":"w","name":"W","live_coach":$value,"steps":[{"type":"free","duration":60}]}""",
            )
        assertEquals(true, read("true").liveCoach)
        assertEquals(false, read("false").liveCoach)
        for (bad in listOf("\"true\"", "\"yes\"", "1", "0", "[]")) {
            assertFailsWith<IllegalArgumentException>(bad) { read(bad) }
        }
    }

    @Test
    fun `the ride never sees the lines, the brief or the flag`() {
        val coached = fixture("must-say-on-every-kind-of-leaf-step")
        val flat = coached.flatSteps()
        assertEquals(coached.steps.size, flat.size)
        // Flattening gives the same steps with or without the lines: the engine's steps carry none.
        assertEquals(flat, coached.withoutLiveCoach().flatSteps())
    }

    @Test
    fun `without the live coach a workout has none of it, in a repeat too`() {
        val coached = fixture("brief-and-must-say-in-a-repeat")
        val stripped = coached.withoutLiveCoach()
        assertNull(stripped.liveCoach)
        assertNull(stripped.coachBrief)
        val text = json.encodeToString(Workout.serializer(), stripped)
        assertFalse(text.contains("must_say"))
        assertFalse(text.contains("coach_brief"))
        assertFalse(text.contains("live_coach"))
        // Everything else is the same.
        assertEquals(coached.steps.size, stripped.steps.size)
        assertEquals(coached.name, stripped.name)
        val plain = fixture("live-coach-flag-only").withoutLiveCoach()
        assertEquals(plain, plain.withoutLiveCoach())
    }
}
