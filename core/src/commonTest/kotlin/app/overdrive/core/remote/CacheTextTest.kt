package app.overdrive.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CacheTextTest {
    private val cached =
        CachedBody(CacheText.sourceOf("https://overdrive.example.org"), 1_700_000_000_000L, "{\"a\":1}\n{\"b\":2}")

    @Test
    fun `a body round-trips exactly, newlines included`() {
        val text = CacheText.encode("metrics-26w", cached)
        val back = assertNotNull(CacheText.decode("metrics-26w", text))
        assertEquals(cached.body, back.body)
        assertEquals(cached.source, back.source)
        assertEquals(cached.fetchedAtMillis, back.fetchedAtMillis)
    }

    @Test
    fun `the source ignores a trailing slash and tells two services apart`() {
        assertEquals(CacheText.sourceOf("https://a.example"), CacheText.sourceOf("https://a.example/"))
        assertNotEquals(CacheText.sourceOf("https://a.example"), CacheText.sourceOf("https://b.example"))
        assertEquals(8, CacheText.sourceOf("https://a.example").length)
    }

    @Test
    fun `another kind, another version, a changed body and a damaged header are all nothing`() {
        val text = CacheText.encode("rides", cached)
        assertNull(CacheText.decode("metrics-26w", text))
        assertNull(CacheText.decode("rides", text.replace("overdrive-cache 1", "overdrive-cache 9")))
        assertNull(CacheText.decode("rides", text.replace("{\"a\":1}", "{\"a\":2}")))
        assertNull(CacheText.decode("rides", text.replace(" 1700000000000 ", " 0 ")))
        assertNull(CacheText.decode("rides", text.replace(" 1700000000000 ", " x ")))
        assertNull(CacheText.decode("rides", text.substringAfter('\n')))
        assertNull(CacheText.decode("rides", text.substringBefore('\n')))
        assertNull(CacheText.decode("rides", ""))
    }

    @Test
    fun `a kind is one word`() {
        assertFailsWith<IllegalArgumentException> { CacheText.encode("two words", cached) }
        assertFailsWith<IllegalArgumentException> { CacheText.encode("", cached) }
    }
}
