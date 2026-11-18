package app.overdrive.data.plan

import app.overdrive.core.plan.CachedPlan
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.PlanCacheFormat
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The planned workouts' cache file (ADR 0017) on a real temporary directory: atomic writes, strict reads, damage is no cache. */
class FilePlanCacheTest {
    private val directory = Files.createTempDirectory("plan").toFile()
    private val file = File(directory, "plan/workouts")
    private val cache = FilePlanCache(file)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun plan(
        id: String = "icu-1",
        fetchedAt: Long = 1_700_000_000_000L,
    ): CachedPlan {
        val workout = Workout(1, id, "Steady", null, listOf(SteadyStep(duration = 1800, target = 0.7)))
        val response =
            WorkoutsResponse(
                "2026-11-18",
                "2026-12-01",
                "2026-11-18T06:30:00Z",
                false,
                true,
                listOf(PlannedWorkout(id, "2026-11-18", "Steady", PlanSource.INTERVALS_ICU, workout)),
            )
        return CachedPlan(PlanCacheFormat.sourceOf("https://overdrive.example.org"), fetchedAt, response)
    }

    private fun read(): CachedPlan = checkNotNull(runBlocking { cache.read() })

    @Test
    fun `nothing is there before the first write`() {
        assertNull(runBlocking { cache.read() })
    }

    @Test
    fun `a plan is written and read back the same, the folder made on the way`() {
        runBlocking { cache.write(plan()) }
        assertTrue(file.isFile)
        val read = read()
        assertEquals(plan().response, read.response)
        assertEquals(1_700_000_000_000L, read.fetchedAtMillis)
        assertEquals(plan().source, read.source)
        assertEquals("no temporary file is left", listOf("workouts"), file.parentFile.list().orEmpty().toList())
    }

    @Test
    fun `a second write replaces the first whole`() {
        runBlocking {
            cache.write(plan("icu-1"))
            cache.write(plan("icu-2", fetchedAt = 1_700_000_999_000L))
        }
        val read = read()
        assertEquals(listOf("icu-2"), read.response.workouts.map { it.id })
        assertEquals(1_700_000_999_000L, read.fetchedAtMillis)
    }

    @Test
    fun `a write that cannot finish leaves the old cache whole`() {
        runBlocking { cache.write(plan("icu-1")) }
        // A folder where the temporary file goes makes the write fail before the rename, as a broken disk would.
        File(file.parentFile, "workouts.tmp").mkdirs()
        try {
            runBlocking { cache.write(plan("icu-2")) }
            throw AssertionError("expected the write to fail")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(listOf("icu-1"), read().response.workouts.map { it.id })
    }

    @Test
    fun `a leftover temporary file from a write that was cut off is not the cache`() {
        runBlocking { cache.write(plan("icu-1")) }
        File(file.parentFile, "workouts.tmp").writeText("overdrive-plan 1 half a wri")
        assertEquals(listOf("icu-1"), read().response.workouts.map { it.id })
        runBlocking { cache.write(plan("icu-3")) }
        assertEquals(listOf("icu-3"), read().response.workouts.map { it.id })
        assertFalse(File(file.parentFile, "workouts.tmp").exists())
    }

    @Test
    fun `a damaged file reads as no cache, every way it can be damaged`() {
        runBlocking { cache.write(plan()) }
        val good = file.readText()
        val damaged =
            mapOf(
                "empty" to "",
                "garbage" to "\u0000\u0001 not a cache �",
                "truncated" to good.take(good.length / 2),
                "an edited body" to good.replace("Steady", "Steadx"),
                "a new version" to good.replaceFirst("overdrive-plan 1", "overdrive-plan 2"),
                "the body alone" to good.substringAfter('\n'),
                "an answer that is not the contract's" to PlanApiJson.encodeWorkoutsResponse(plan().response),
            )
        for ((what, text) in damaged) {
            file.writeText(text)
            assertNull(what, runBlocking { cache.read() })
        }
        file.writeBytes(good.encodeToByteArray().copyOf(60) + ByteArray(5) { 0xC3.toByte() })
        assertNull("bytes that are not text", runBlocking { cache.read() })
        file.delete()
        file.mkdirs()
        assertNull("a folder where the file should be", runBlocking { cache.read() })
    }

    @Test
    fun `the file holds the answer and an address checksum, and no token`() {
        runBlocking { cache.write(plan()) }
        val text = file.readText()
        assertTrue(text.startsWith("overdrive-plan 1 "))
        assertFalse("https://overdrive.example.org" in text)
        assertFalse("Bearer" in text || "tok-" in text)
    }
}
