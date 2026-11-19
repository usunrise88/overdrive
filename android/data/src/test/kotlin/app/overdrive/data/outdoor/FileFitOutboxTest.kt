package app.overdrive.data.outdoor

import app.overdrive.core.outdoor.FitFileCheck
import app.overdrive.core.outdoor.FitRefusal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The outbox of FIT files on disk: order, durability, a damaged file, parking with the reason, a restart. Bytes here are synthetic. */
class FileFitOutboxTest {
    private val directory: File = Files.createTempDirectory("fit-outbox").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A file with a FIT header and every byte value in it (a text encoding would damage it, a line feed in it must not confuse the header). */
    private fun fit(
        seed: Int,
        size: Int = 3000,
    ): ByteArray =
        ByteArray(size) { (it * 13 + seed).toByte() }.also {
            it[0] = 12
            ".FIT".encodeToByteArray().copyInto(it, 8)
            it[20] = '\n'.code.toByte()
        }

    private fun files(folder: File = directory) = folder.listFiles { f -> f.isFile }.orEmpty().toList()

    @Test
    fun `files come back in the order they went in, with their sizes and checksums, and the bytes exactly`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val a = store.add(fit(1))
            val b = store.add(fit(2, 100))
            val c = store.add(fit(3, 70_000))
            assertEquals(listOf(a.seq, b.seq, c.seq), store.pending().map { it.seq })
            assertEquals(listOf(3000, 100, 70_000), store.pending().map { it.bytes })
            assertEquals(FitFileCheck.checksumOf(fit(2, 100)), store.pending()[1].checksum)
            assertArrayEquals(fit(1), store.bytes(a.seq))
            assertArrayEquals(fit(3, 70_000), store.bytes(c.seq))
            assertTrue(a.seq < b.seq && b.seq < c.seq)
        }

    @Test
    fun `the checksum is the one the importer works out, so a second copy is found`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val info = store.add(fit(5))
            assertEquals(FitFileCheck.checksumOf(fit(5)), info.checksum)
            assertEquals(info, store.pending().single())
        }

    @Test
    fun `a new process sees what the old one left, and goes on numbering after it`() =
        runBlocking {
            val first = FileFitOutbox(directory)
            val a = first.add(fit(1))
            val b = first.add(fit(2))
            first.remove(a.seq)
            val second = FileFitOutbox(directory)
            assertEquals(listOf(b.seq), second.pending().map { it.seq })
            val c = second.add(fit(3))
            assertTrue(c.seq > b.seq)
            assertEquals(listOf(b.seq, c.seq), second.pending().map { it.seq })
            assertArrayEquals(fit(2), second.bytes(b.seq))
        }

    @Test
    fun `a file is removed only by remove, and a parked one is kept aside whole with its reason and still counted`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val a = store.add(fit(1))
            val b = store.add(fit(2))
            store.park(a.seq, FitRefusal.NOT_CYCLING)
            assertEquals(listOf(b.seq), store.pending().map { it.seq })
            val parked = store.parked().single()
            assertEquals(a.seq, parked.seq)
            assertEquals(FitRefusal.NOT_CYCLING, parked.refusal)
            assertEquals(3000, parked.bytes)
            assertEquals(1, files(File(directory, "parked")).size)
            assertNull("a parked file is not in the queue", store.bytes(a.seq))
            // The reason survives a restart, and numbers are not reused, even for parked ones.
            val again = FileFitOutbox(directory)
            assertEquals(FitRefusal.NOT_CYCLING, again.parked().single().refusal)
            val c = again.add(fit(3))
            assertTrue(c.seq > b.seq)
            store.remove(b.seq)
            assertTrue(store.pending().none { it.seq == b.seq })
        }

    @Test
    fun `discarding a parked file deletes it, and nothing else`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val a = store.add(fit(1))
            val b = store.add(fit(2))
            store.park(a.seq, FitRefusal.NOT_A_FIT_FILE)
            store.park(b.seq, FitRefusal.NO_RIDE)
            store.discard(a.seq)
            assertEquals(listOf(b.seq), store.parked().map { it.seq })
            assertEquals(1, files(File(directory, "parked")).size)
        }

    @Test
    fun `damaged bytes read as no bytes and park as damaged without poisoning the queue`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val a = store.add(fit(1))
            val b = store.add(fit(2))
            val c = store.add(fit(3))
            val flipped = File(directory, "%012d.fit".format(a.seq))
            flipped.writeBytes(flipped.readBytes().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() })
            val cut = File(directory, "%012d.fit".format(b.seq))
            cut.writeBytes(cut.readBytes().copyOf(cut.length().toInt() - 10))
            assertNull(store.bytes(a.seq))
            assertNull(store.bytes(b.seq))
            assertArrayEquals(fit(3), store.bytes(c.seq))
            // A damaged file is still listed, so the importer meets it and sets it aside: it is parked as damaged.
            assertEquals(listOf(a.seq, b.seq, c.seq), store.pending().map { it.seq })
            store.park(a.seq, FitRefusal.NOT_CYCLING)
            store.park(b.seq, FitRefusal.DAMAGED)
            assertEquals(listOf(FitRefusal.DAMAGED, FitRefusal.DAMAGED), store.parked().map { it.refusal })
            assertEquals(listOf(0, 0), store.parked().map { it.bytes })
            assertEquals(listOf(c.seq), store.pending().map { it.seq })
        }

    @Test
    fun `a header that is not ours and files that are not ours are not listed`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            val a = store.add(fit(1))
            File(directory, "000000000099.fit").writeText("garbage\nbody")
            File(directory, "notes.txt").writeText("not a file of the queue")
            File(directory, "000000000098.fit").writeBytes(ByteArray(0))
            assertEquals(listOf(a.seq), store.pending().map { it.seq })
        }

    @Test
    fun `no temporary file is left after a write, a stale one is swept, and an unwritable folder fails loudly`() =
        runBlocking {
            File(directory, "000000000007.fit.tmp").writeText("half a file a crash left")
            val store = FileFitOutbox(directory)
            store.add(fit(1))
            assertFalse(files().any { it.name.endsWith(".tmp") })
            val blocked = File(directory, "blocked").also { it.writeText("a file where the folder should be") }
            val failing = FileFitOutbox(File(blocked, "inside"))
            assertTrue(runCatching { failing.add(fit(1)) }.isFailure)
            assertTrue("nothing is queued by a write that failed", failing.pending().isEmpty())
        }

    @Test
    fun `an empty store lists nothing, and the largest file the service takes is kept whole`() =
        runBlocking {
            val store = FileFitOutbox(File(directory, "never-made"))
            assertTrue(store.pending().isEmpty())
            assertTrue(store.parked().isEmpty())
            assertNull(store.bytes(1))
            val big = fit(9, 16 * 1024 * 1024)
            val info = store.add(big)
            assertEquals(big.size, store.pending().single().bytes)
            assertArrayEquals(big, store.bytes(info.seq))
        }

    @Test
    fun `the file name carries no name from the sharing app`() =
        runBlocking {
            val store = FileFitOutbox(directory)
            store.add(fit(1))
            val names = files().map { it.name }
            assertTrue(names.toString(), names.all { Regex("^[0-9]{12}\\.fit$").matches(it) })
        }
}
