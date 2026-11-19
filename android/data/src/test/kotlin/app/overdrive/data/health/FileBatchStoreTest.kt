package app.overdrive.data.health

import app.overdrive.core.health.WellnessBatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The outbox of watch data on disk: order, durability, a damaged file, parking, a restart. Bodies here are synthetic. */
class FileBatchStoreTest {
    private val directory: File = Files.createTempDirectory("batches").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun batch(
        records: Int,
        body: String = """{"source":"healthconnect","records":[$records]}""",
    ) = WellnessBatch(body, records, mapOf("restingHeartRate" to records))

    @Test
    fun `batches come back in the order they went in, with their counts, and the body exactly`() =
        runBlocking {
            val store = FileBatchStore(directory)
            val a = store.add(batch(1))
            val b = store.add(batch(2))
            val c = store.add(batch(3, "{\"title\":\"Nuit — é\"}"))
            assertEquals(listOf(a.seq, b.seq, c.seq), store.pending().map { it.seq })
            assertEquals(listOf(1, 2, 3), store.pending().map { it.records })
            assertEquals(mapOf("restingHeartRate" to 2), store.pending()[1].byType)
            assertEquals("{\"title\":\"Nuit — é\"}", store.body(c.seq))
            assertTrue(a.seq < b.seq && b.seq < c.seq)
        }

    @Test
    fun `a new process sees what the old one left, and goes on numbering after it`() =
        runBlocking {
            val first = FileBatchStore(directory)
            val a = first.add(batch(1))
            val b = first.add(batch(2))
            first.remove(a.seq)
            val second = FileBatchStore(directory)
            assertEquals(listOf(b.seq), second.pending().map { it.seq })
            val c = second.add(batch(3))
            assertTrue(c.seq > b.seq)
            assertEquals(listOf(b.seq, c.seq), second.pending().map { it.seq })
        }

    @Test
    fun `a batch is removed only by remove, and a parked one is kept aside and still counted`() =
        runBlocking {
            val store = FileBatchStore(directory)
            val a = store.add(batch(1))
            val b = store.add(batch(2))
            store.park(a.seq)
            assertEquals(listOf(b.seq), store.pending().map { it.seq })
            assertEquals(listOf(a.seq), store.parked().map { it.seq })
            assertTrue("a parked batch is a file on disk", File(directory, "parked").listFiles().orEmpty().isNotEmpty())
            // Numbers are not reused, even for parked ones.
            val c = FileBatchStore(directory).add(batch(3))
            assertTrue(c.seq > b.seq)
            store.remove(b.seq)
            assertTrue(store.pending().none { it.seq == b.seq })
        }

    @Test
    fun `a body that fails its checksum reads as no body and a header that is not ours is not listed`() =
        runBlocking {
            val store = FileBatchStore(directory)
            val a = store.add(batch(1))
            val file = directory.listFiles { f -> f.name.endsWith(".batch") }!!.single()
            file.writeText(file.readText().replace("records", "recordz"))
            assertNull(store.body(a.seq))
            File(directory, "000000000099.batch").writeText("garbage\nbody")
            File(directory, "notes.txt").writeText("not a batch")
            assertEquals(listOf(a.seq), store.pending().map { it.seq })
        }

    @Test
    fun `no temporary file is left after a write and an unwritable folder fails loudly`() =
        runBlocking {
            val store = FileBatchStore(directory)
            store.add(batch(1))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
            val blocked = File(directory, "blocked").also { it.writeText("a file where the folder should be") }
            val failing = FileBatchStore(File(blocked, "inside"))
            val failed = runCatching { failing.add(batch(1)) }
            assertTrue(failed.isFailure)
        }

    @Test
    fun `an empty store lists nothing`() =
        runBlocking {
            val store = FileBatchStore(File(directory, "never-made"))
            assertTrue(store.pending().isEmpty())
            assertTrue(store.parked().isEmpty())
            assertNull(store.body(1))
        }
}
