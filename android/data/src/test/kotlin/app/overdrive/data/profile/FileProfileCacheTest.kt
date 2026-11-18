package app.overdrive.data.profile

import app.overdrive.core.profile.CachedProfile
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PhysicsPatch
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileApiJson
import app.overdrive.core.profile.ProfileCacheFormat
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfilePhysics
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

/** The profile's cache file (ADR 0021) on a real temporary directory: atomic writes, strict reads, damage is no cache. */
class FileProfileCacheTest {
    private val directory = Files.createTempDirectory("profile").toFile()
    private val file = File(directory, "profile/profile")
    private val cache = FileProfileCache(file)
    private val source = ProfileCacheFormat.sourceOf("https://overdrive.example.org")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun profile(ftp: Int? = 175) =
        Profile.default().copy(
            ftp = ftp,
            physics = ProfilePhysics.DEFAULT.copy(systemMassKg = 82.0),
            updatedAt = "2026-11-18T09:30:00Z",
        )

    private fun cached(
        ftp: Int? = 175,
        pending: ProfilePatch? = null,
    ) = CachedProfile(source, profile(ftp), pending)

    private fun read(): CachedProfile = checkNotNull(runBlocking { cache.read() })

    @Test
    fun `nothing is there before the first write`() {
        assertNull(runBlocking { cache.read() })
    }

    @Test
    fun `a profile is written and read back the same, the folder made on the way`() {
        runBlocking { cache.write(cached()) }
        assertTrue(file.isFile)
        assertEquals(cached(), read())
        assertEquals("no temporary file is left", listOf("profile"), file.parentFile.list().orEmpty().toList())
    }

    @Test
    fun `an unsent edit is kept with it`() {
        val edit = ProfilePatch(ftp = Change.Set(180), physics = PhysicsPatch(cdA = 0.31))
        runBlocking { cache.write(cached(pending = edit)) }
        val read = read()
        assertEquals(edit, read.pending)
        assertEquals(175, read.profile.ftp)
        assertEquals(180, read.effective().ftp)
    }

    @Test
    fun `a second write replaces the first whole`() {
        runBlocking {
            cache.write(cached(ftp = 175))
            cache.write(cached(ftp = 240))
        }
        assertEquals(240, read().profile.ftp)
    }

    @Test
    fun `a write that cannot finish leaves the old cache whole`() {
        runBlocking { cache.write(cached(ftp = 175)) }
        // A folder where the temporary file goes makes the write fail before the rename, as a broken disk would.
        File(file.parentFile, "profile.tmp").mkdirs()
        try {
            runBlocking { cache.write(cached(ftp = 240)) }
            throw AssertionError("expected the write to fail")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(175, read().profile.ftp)
    }

    @Test
    fun `a leftover temporary file from a write that was cut off is not the cache`() {
        runBlocking { cache.write(cached(ftp = 175)) }
        File(file.parentFile, "profile.tmp").writeText("overdrive-profile 1 half a wri")
        assertEquals(175, read().profile.ftp)
        runBlocking { cache.write(cached(ftp = 190)) }
        assertEquals(190, read().profile.ftp)
        assertFalse(File(file.parentFile, "profile.tmp").exists())
    }

    @Test
    fun `a damaged file reads as no cache, every way it can be damaged`() {
        runBlocking { cache.write(cached(pending = ProfilePatch(ftp = Change.Set(180)))) }
        val good = file.readText()
        val damaged =
            mapOf(
                "empty" to "",
                "garbage" to "\u0000\u0001 not a cache �",
                "truncated" to good.take(good.length / 2),
                "an edited profile" to good.replace("175", "176"),
                "an edited edit" to good.replace("180", "181"),
                "a new version" to good.replaceFirst("overdrive-profile 1", "overdrive-profile 2"),
                "the body alone" to good.substringAfter('\n'),
                "a profile that is not the contract's" to ProfileApiJson.encodeProfile(profile()),
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
    fun `the file holds the profile and an address checksum, and no address and no token`() {
        runBlocking { cache.write(cached(pending = ProfilePatch(ftp = Change.Set(180)))) }
        val text = file.readText()
        assertTrue(text.startsWith("overdrive-profile 1 $source "))
        assertFalse("overdrive.example.org" in text)
        assertFalse("Bearer" in text || "tok-" in text)
    }
}
