package app.overdrive.data.remote

import app.overdrive.core.remote.CacheText
import app.overdrive.core.remote.CachedBody
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The cached answers on a real temporary directory: atomic writes, strict reads, damage is no cache, the folder is bounded. */
class FileTextStoreTest {
    private val directory = Files.createTempDirectory("remote").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun body(n: Int) =
        CachedBody(CacheText.sourceOf("https://overdrive.example.org"), 1_700_000_000_000L + n, """{"n":$n}""")

    @Test
    fun `nothing is there before the first write, and a write makes the folder and reads back whole`() {
        val store = FileTextStore(File(directory, "cache/metrics-26w"))
        assertNull(runBlocking { store.read() })
        val text = CacheText.encode("metrics-26w", body(1))
        runBlocking { store.write(text) }
        assertEquals(text, runBlocking { store.read() })
        assertEquals(
            1_700_000_000_001L,
            CacheText
                .decode(
                    "metrics-26w",
                    checkNotNull(
                        runBlocking {
                            store.read()
                        },
                    ),
                )?.fetchedAtMillis,
        )
    }

    @Test
    fun `a write replaces the file whole and leaves no temporary file`() {
        val file = File(directory, "rides")
        val store = FileTextStore(file)
        runBlocking {
            store.write(CacheText.encode("rides", body(1)))
            store.write(CacheText.encode("rides", body(2)))
        }
        assertTrue(checkNotNull(runBlocking { store.read() }).contains("""{"n":2}"""))
        assertEquals(listOf("rides"), directory.list()!!.toList())
    }

    @Test
    fun `a file that was cut or edited reads as no cache through the format`() {
        val file = File(directory, "rides")
        val store = FileTextStore(file)
        val text = CacheText.encode("rides", body(1))
        runBlocking { store.write(text) }
        file.writeText(text.dropLast(3))
        assertNull(CacheText.decode("rides", checkNotNull(runBlocking { store.read() })))
        file.writeText(text.replace("""{"n":1}""", """{"n":9}"""))
        assertNull(CacheText.decode("rides", checkNotNull(runBlocking { store.read() })))
    }

    @Test
    fun `a folder where the file should be reads as nothing, and a write to it fails with an IOException`() {
        val file = File(directory, "rides").also { it.mkdirs() }
        val store = FileTextStore(file)
        assertNull(runBlocking { store.read() })
        try {
            runBlocking { store.write("x") }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(File(directory, "rides.tmp").exists().not())
        }
    }

    @Test
    fun `the files of a folder are bounded, the oldest go first and the one just written stays`() {
        val stores = FileTextStores(File(directory, "rides"), maxFiles = 3)
        for (i in 1..5) {
            runBlocking { stores.store("ride-$i").write(CacheText.encode("ride-detail", body(i))) }
            File(directory, "rides/ride-$i").setLastModified(1_000_000L + i * 1000)
        }
        assertEquals(setOf("ride-3", "ride-4", "ride-5"), File(directory, "rides").list()!!.toSet())
        runBlocking { stores.store("ride-1").write(CacheText.encode("ride-detail", body(1))) }
        assertTrue(File(directory, "rides/ride-1").isFile)
        assertEquals(3, File(directory, "rides").list()!!.size)
    }

    @Test
    fun `a name that is not a plain file name is refused`() {
        val stores = FileTextStores(File(directory, "rides"))
        for (name in listOf("", "../x", "a/b", ".hidden", "a b", "x".repeat(200))) {
            try {
                stores.store(name)
                fail("expected a refusal for '$name'")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrEmpty())
            }
        }
    }
}
