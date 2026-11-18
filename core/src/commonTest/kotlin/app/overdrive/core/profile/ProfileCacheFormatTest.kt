package app.overdrive.core.profile

import app.overdrive.core.profile.ProfileSamples.rider
import app.overdrive.core.record.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text the profile cache keeps (ADR 0021): what it holds, and that anything else reads as no cache. */
class ProfileCacheFormatTest {
    private val source = ProfileCacheFormat.sourceOf("https://overdrive.example.org")
    private val edit = ProfilePatch(ftp = Change.Set(180), physics = PhysicsPatch(crr = 0.0045))

    private fun cached(pending: ProfilePatch? = null) = CachedProfile(source, rider(), pending)

    private fun hex(text: String) = Crc32.of(text.encodeToByteArray()).toString(16).padStart(8, '0')

    /** A cache text with [body] and a header whose checksum is right for it: for damage the checksum cannot catch. */
    private fun sealed(body: String) = "overdrive-profile 1 $source ${hex(body)}\n$body"

    @Test
    fun `the source is the address without a trailing slash`() {
        assertEquals(source, ProfileCacheFormat.sourceOf("https://overdrive.example.org/"))
        assertEquals(8, source.length)
        assertTrue(source != ProfileCacheFormat.sourceOf("https://overdrive.example.com"))
    }

    @Test
    fun `a profile is written as a header and two lines, and read back the same`() {
        val text = ProfileCacheFormat.encode(cached())
        val lines = text.split('\n')
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("overdrive-profile 1 $source "))
        assertEquals(ProfileApiJson.encodeProfile(rider()), lines[1])
        assertEquals("", lines[2], "no unsent edit")
        val read = assertNotNull(ProfileCacheFormat.decode(text))
        assertEquals(cached(), read)
    }

    @Test
    fun `an unsent edit is the second line and is read back`() {
        val text = ProfileCacheFormat.encode(cached(edit))
        assertEquals("""{"ftp":180,"physics":{"crr":0.0045}}""", text.split('\n')[2])
        val read = assertNotNull(ProfileCacheFormat.decode(text))
        assertEquals(edit, read.pending)
        assertEquals(180, read.effective().ftp)
        assertEquals(0.0045, read.effective().physics.crr)
        assertEquals(175, read.profile.ftp, "the service's answer is kept apart from the edit")
    }

    @Test
    fun `an empty edit is no edit`() {
        assertFailsWith<IllegalArgumentException> { CachedProfile(source, rider(), ProfilePatch()) }
        assertNull(
            assertNotNull(ProfileCacheFormat.decode(sealed(ProfileApiJson.encodeProfile(rider()) + "\n{}"))).pending,
        )
    }

    @Test
    fun `anything that is not exactly what is written reads as no cache`() {
        val good = ProfileCacheFormat.encode(cached(edit))
        val profileLine = ProfileApiJson.encodeProfile(rider())
        val damaged =
            mapOf(
                "empty" to "",
                "garbage" to "\u0000\u0001 not a cache",
                "truncated" to good.take(good.length / 2),
                "an edited profile" to good.replace("175", "176"),
                "an edited edit" to good.replace("180", "181"),
                "a new version" to good.replaceFirst("overdrive-profile 1", "overdrive-profile 2"),
                "another name" to good.replaceFirst("overdrive-profile", "overdrive-plan"),
                "the body alone" to good.substringAfter('\n'),
                "no body" to good.substringBefore('\n') + "\n",
                "a source that is not hex" to good.replaceFirst(source, "zzzzzzzz"),
                "a source that is short" to good.replaceFirst(source, source.take(6)),
                "an extra header field" to good.replaceFirst("\n", " extra\n"),
                "a third line" to sealed("$profileLine\n\n"),
                "one line only" to sealed(profileLine),
                "a profile that is not a profile" to sealed("{}\n"),
                "a profile out of range" to sealed(profileLine.replace("\"ftp\":175", "\"ftp\":5") + "\n"),
                "an edit that is not a patch" to sealed("$profileLine\nnot json"),
                "an edit with a key the schema does not have" to sealed("$profileLine\n{\"mystery\":1}"),
                "an edit that makes an invalid profile" to sealed("$profileLine\n{\"ftp\":5}"),
                "an edit with a string for a number" to sealed("$profileLine\n{\"ftp\":\"180\"}"),
            )
        for ((what, text) in damaged) assertNull(ProfileCacheFormat.decode(text), what)
    }

    @Test
    fun `the file holds the profile and a checksum of the address, and no address and no token`() {
        val text = ProfileCacheFormat.encode(cached(edit))
        assertFalse("overdrive.example.org" in text)
        assertFalse("https://" in text)
        assertFalse("Bearer" in text || "tok-" in text)
    }
}
