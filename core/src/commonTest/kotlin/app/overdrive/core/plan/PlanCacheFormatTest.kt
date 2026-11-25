package app.overdrive.core.plan

import app.overdrive.core.plan.PlanSamples.planned
import app.overdrive.core.plan.PlanSamples.response
import app.overdrive.core.record.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the plan cache file holds (ADR 0017): strict on read, so a damaged file is no cache and never half a list. */
class PlanCacheFormatTest {
    private val source = PlanCacheFormat.sourceOf("https://overdrive.example.org")
    private val plan =
        CachedPlan(
            source,
            1_700_000_000_123L,
            response(
                "2026-11-18",
                "2026-12-01",
                listOf(planned("icu-1", "2026-11-18"), planned("icu-2", "2026-11-20")),
            ),
        )

    @Test
    fun `writes a header and the answer and reads the same plan back`() {
        val text = PlanCacheFormat.encode(plan)
        assertTrue(text.startsWith("overdrive-plan 1 $source 1700000000123 "), text.take(60))
        val read = assertNotNull(PlanCacheFormat.decode(text))
        assertEquals(source, read.source)
        assertEquals(1_700_000_000_123L, read.fetchedAtMillis)
        assertEquals(plan.response, read.response)
    }

    @Test
    fun `the source is the address without its trailing slash and differs between addresses`() {
        assertEquals(
            PlanCacheFormat.sourceOf("https://a.example.org"),
            PlanCacheFormat.sourceOf("https://a.example.org/"),
        )
        assertNotEquals(
            PlanCacheFormat.sourceOf("https://a.example.org"),
            PlanCacheFormat.sourceOf("https://b.example.org"),
        )
        assertEquals(8, source.length)
    }

    @Test
    fun `reads nothing from a damaged file`() {
        val good = PlanCacheFormat.encode(plan)
        val body = good.substringAfter('\n')
        val header = good.substringBefore('\n')
        val damaged =
            mapOf(
                "empty" to "",
                "no body" to "$header\n",
                "no newline" to header,
                "truncated" to good.take(good.length - 10),
                "a flipped letter" to good.replace("Sweet", "Sweat"),
                "a changed value" to good.replace("\"stale\":false", "\"stale\":true"),
                "an old version" to good.replaceFirst("overdrive-plan 1", "overdrive-plan 0"),
                "a new version" to good.replaceFirst("overdrive-plan 1", "overdrive-plan 2"),
                "another magic" to good.replaceFirst("overdrive-plan", "overdrive-rides"),
                "a short header" to header.substringBeforeLast(' ') + "\n" + body,
                "a long header" to "$header extra\n$body",
                "a source that is not hex" to header.replace(source, "zzzzzzzz") + "\n" + body,
                "no fetch time" to header.replace("1700000000123", "soon") + "\n" + body,
                "a zero fetch time" to header.replace("1700000000123", "0") + "\n" + body,
                "a wrong checksum" to header.substringBeforeLast(' ') + " 00000000\n" + body,
            )
        for ((what, text) in damaged) assertNull(PlanCacheFormat.decode(text), what)
    }

    @Test
    fun `reads nothing when the checksum is right but the body is not the contract's answer`() {
        val body = """{"from":"2026-11-18","to":"2026-12-01","generatedAt":null,"stale":false,"configured":true}"""
        val crc = Crc32.of(body.encodeToByteArray()).toString(16).padStart(8, '0')
        assertNull(PlanCacheFormat.decode("overdrive-plan 1 $source 1700000000123 $crc\n$body"), "no workouts key")
        val good = PlanCacheFormat.encode(plan).substringAfter('\n')
        assertNotNull(
            PlanCacheFormat.decode(
                "overdrive-plan 1 $source 1700000000123 ${Crc32.of(
                    good.encodeToByteArray(),
                ).toString(16).padStart(8, '0')}\n$good",
            ),
            "the same construction with a real body does read",
        )
    }
}
