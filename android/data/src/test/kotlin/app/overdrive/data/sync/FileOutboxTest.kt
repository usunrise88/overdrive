package app.overdrive.data.sync

import app.overdrive.core.sync.OutboxEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * The persistent outbox's guarantees (spec §4.9, ADR 0012), each as a test: it survives a restart, never loses an entry,
 * keeps the order, queues a ride once, and a process that dies at any byte of a write leaves a file the next start reads.
 */
class FileOutboxTest {
    private val directory: File = Files.createTempDirectory("outbox").toFile()
    private val file = File(directory, "sync/outbox.log")

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun outbox(
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        afterStep: (OutboxStep) -> Unit = {},
    ) = FileOutbox(file, dispatcher, afterStep)

    private fun entry(
        id: String,
        enqueued: Long = 1_000,
        title: String? = "Sweet spot 3×10",
    ) = OutboxEntry(id, enqueued, startedAtMillis = 5_000, durationSeconds = 3_900, title = title)

    private fun pendingIds(outbox: FileOutbox = outbox()): List<String> =
        runBlocking { outbox.pending().map { it.rideId } }

    // region the basics

    @Test
    fun `an empty outbox has nothing pending and no file`() {
        assertEquals(emptyList<OutboxEntry>(), runBlocking { outbox().pending() })
        assertFalse("reading creates nothing", file.exists())
    }

    @Test
    fun `an enqueued ride is on disk when enqueue returns and found by a new outbox over the same file`() {
        val ride = entry("ride-1", title = "Free ride")
        runBlocking { outbox().enqueue(ride) }
        assertTrue(file.isFile)
        // A "restart": nothing is shared with the first object but the file.
        assertEquals(listOf(ride), runBlocking { outbox().pending() })
    }

    @Test
    fun `every field survives, including a title that is awkward in a text file`() {
        val awkward =
            OutboxEntry(
                rideId = "ride-with\ttab",
                enqueuedAtMillis = 1_794_996_000_123,
                startedAtMillis = 1_794_995_000_000,
                durationSeconds = 7_321,
                title = "A\\B\ttab\nnewline\r and — unicode ✓",
            )
        val plain = OutboxEntry("ride-2", 5, 4, 3, title = null)
        val empty = OutboxEntry("ride-3", 6, 4, 3, title = "")
        runBlocking {
            outbox().apply {
                enqueue(awkward)
                enqueue(plain)
                enqueue(empty)
            }
        }
        assertEquals(listOf(plain, empty, awkward), runBlocking { outbox().pending() }.sortedBy { it.enqueuedAtMillis })
        assertEquals("one journal line per ride and the header", 4, file.readLines().size)
    }

    @Test
    fun `entries come back oldest first, by enqueue time and then by arrival`() {
        runBlocking {
            val outbox = outbox()
            outbox.enqueue(entry("late", enqueued = 3_000))
            outbox.enqueue(entry("early", enqueued = 1_000))
            outbox.enqueue(entry("same-a", enqueued = 2_000))
            outbox.enqueue(entry("same-b", enqueued = 2_000))
        }
        assertEquals(listOf("early", "same-a", "same-b", "late"), pendingIds())
    }

    @Test
    fun `a ride queued twice is queued once, before and after a restart`() {
        runBlocking {
            val outbox = outbox()
            outbox.enqueue(entry("ride-1"))
            outbox.enqueue(entry("ride-1", enqueued = 9_999, title = "Another title"))
        }
        assertEquals(listOf("ride-1"), pendingIds())
        runBlocking { outbox().enqueue(entry("ride-1")) }
        assertEquals(listOf("ride-1"), pendingIds())
        assertEquals("the first entry was kept", 1_000L, runBlocking { outbox().pending().single().enqueuedAtMillis })
        assertEquals("and no line was added", 2, file.readLines().size)
    }

    @Test
    fun `a ride marked done leaves the outbox for good, and is not accepted again`() {
        runBlocking {
            val outbox = outbox()
            outbox.enqueue(entry("a", 1))
            outbox.enqueue(entry("b", 2))
            outbox.markDone("a")
            assertEquals(listOf("b"), outbox.pending().map { it.rideId })
            outbox.enqueue(entry("a", 3))
            assertEquals(
                "a ride that was uploaded is never queued again",
                listOf("b"),
                outbox.pending().map { it.rideId },
            )
        }
        // The same after a restart: the journal remembers.
        assertEquals(listOf("b"), pendingIds())
        runBlocking {
            val restarted = outbox()
            restarted.enqueue(entry("a", 4))
            assertEquals(listOf("b"), restarted.pending().map { it.rideId })
            assertEquals(1, restarted.doneCount())
        }
    }

    @Test
    fun `marking an unknown ride done does nothing and writes nothing`() {
        runBlocking {
            val outbox = outbox()
            outbox.enqueue(entry("a"))
            outbox.markDone("nobody")
            assertEquals("nothing was written for an unknown ride", 2, file.readLines().size)
            outbox.markDone("a")
            outbox.markDone("a")
            assertEquals(emptyList<OutboxEntry>(), outbox.pending())
        }
        assertEquals("the header, the entry and one done line, not two", 3, file.readLines().size)
    }

    @Test
    fun `many coroutines enqueueing at once each leave one entry on disk`() {
        val ids = (1..60).map { "ride-$it" }
        runBlocking {
            val outbox = outbox(Dispatchers.IO)
            ids
                .map { id ->
                    async(Dispatchers.Default) { outbox.enqueue(entry(id, enqueued = id.length.toLong())) }
                }.awaitAll()
        }
        assertEquals(ids.toSet(), pendingIds().toSet())
        assertEquals(60, file.readLines().count { !it.startsWith("#") })
    }

    // endregion

    // region a process that dies

    @Test
    fun `a file cut at any byte reads as the rides whose lines were complete, and takes the next entry`() {
        val rides = (1..6).map { entry("ride-$it", enqueued = it * 10L, title = if (it % 2 == 0) null else "T$it") }
        runBlocking { outbox().apply { rides.forEach { enqueue(it) } } }
        val whole = file.readBytes()
        val lineEnds = whole.indices.filter { whole[it] == '\n'.code.toByte() }
        for (cut in 0..whole.size) {
            file.writeBytes(whole.copyOf(cut))
            // How many complete ride lines (not counting the header) are in the first `cut` bytes.
            val complete = lineEnds.count { it < cut } - 1
            val expected = rides.take(complete.coerceAtLeast(0)).map { it.rideId }
            val restarted = outbox()
            assertEquals("cut at $cut", expected, pendingIds(restarted))
            // The torn tail does not stop the next ride from being queued, and it is there after another restart.
            runBlocking { restarted.enqueue(entry("after-$cut", enqueued = 999)) }
            assertEquals("after a cut at $cut", expected + "after-$cut", pendingIds())
            assertTrue("repaired: every line is complete", file.readBytes().last() == '\n'.code.toByte())
        }
    }

    @Test
    fun `a garbage tail, a flipped byte and a wrong checksum cost only the line they touch`() {
        runBlocking { outbox().apply { (1..4).forEach { enqueue(entry("ride-$it", it.toLong())) } } }
        val lines = file.readLines()

        // Garbage after the last line.
        file.writeText(lines.joinToString("\n", postfix = "\n") + "\u0000\u0000garbage without a newline")
        assertEquals(listOf("ride-1", "ride-2", "ride-3", "ride-4"), pendingIds())

        // One line damaged in the middle: the others around it are kept.
        val damaged = lines.toMutableList().also { it[2] = it[2].replaceFirst('E', 'X') }
        file.writeText(damaged.joinToString("\n", postfix = "\n"))
        assertEquals(listOf("ride-1", "ride-3", "ride-4"), pendingIds())
        // And the file was compacted, so the damage is gone.
        assertTrue(file.readLines().none { it.contains('X') && it.contains("ride-2") })

        // A flipped checksum digit.
        val bad = lines.toMutableList().also { it[1] = (if (it[1][0] == '0') '1' else '0') + it[1].substring(1) }
        file.writeText(bad.joinToString("\n", postfix = "\n"))
        assertEquals(listOf("ride-2", "ride-3", "ride-4"), pendingIds())
    }

    @Test
    fun `a file of nothing but noise is an empty outbox, never an exception, and is writable again`() {
        file.parentFile.mkdirs()
        file.writeBytes(Random(7).nextBytes(4_000))
        assertEquals(emptyList<OutboxEntry>(), runBlocking { outbox().pending() })
        runBlocking { outbox().enqueue(entry("fresh")) }
        assertEquals(listOf("fresh"), pendingIds())
    }

    @Test
    fun `a process that dies after an append loses nothing, whichever step it stops at`() {
        runBlocking { outbox().enqueue(entry("first")) }
        val dying =
            outbox(afterStep = { throw DiedHere() })
        try {
            runBlocking { dying.enqueue(entry("second")) }
            fail("the hook should have stopped the process")
        } catch (_: DiedHere) {
            // Killed right after the line was forced to storage.
        }
        assertEquals(listOf("first", "second"), pendingIds())
    }

    @Test
    fun `a process that dies while compacting leaves the old file whole and no stray file`() {
        runBlocking { outbox().apply { (1..3).forEach { enqueue(entry("ride-$it", it.toLong())) } } }
        // Damage a line so the next start compacts.
        file.appendText("garbage line\n")
        val before = file.readBytes()
        for (step in listOf(OutboxStep.TempWritten)) {
            val dying = outbox(afterStep = { if (it == step) throw DiedHere() })
            try {
                runBlocking { dying.pending() }
                fail("the hook should have stopped the process at $step")
            } catch (_: DiedHere) {
                // Killed with the new file written and not renamed.
            }
            assertTrue("the old journal is untouched", before.contentEquals(file.readBytes()))
            assertEquals(listOf("ride-1", "ride-2", "ride-3"), pendingIds())
            assertFalse("the leftover temporary file is gone", File(file.parentFile, file.name + ".tmp").exists())
        }
    }

    @Test
    fun `a process that dies just after the rename has the compacted file`() {
        runBlocking { outbox().apply { (1..3).forEach { enqueue(entry("ride-$it", it.toLong())) } } }
        file.appendText("garbage line\n")
        val dying = outbox(afterStep = { if (it == OutboxStep.Renamed) throw DiedHere() })
        try {
            runBlocking { dying.pending() }
            fail("the hook should have stopped the process")
        } catch (_: DiedHere) {
            // Killed once the new file was in place.
        }
        assertEquals(listOf("ride-1", "ride-2", "ride-3"), pendingIds())
        assertFalse(file.readText().contains("garbage"))
    }

    @Test
    fun `a failed append is never followed by a line glued to its half`() {
        runBlocking { outbox().enqueue(entry("a")) }
        val outbox = outbox()
        runBlocking { outbox.pending() }
        // Make the next append fail halfway: the parent "directory" of the journal is replaced by a file.
        val blocked = File(directory, "blocked")
        blocked.writeText("x")
        val broken = FileOutbox(File(blocked, "outbox.log"), Dispatchers.Unconfined)
        try {
            runBlocking { broken.enqueue(entry("b")) }
            fail("a disk that cannot be written must say so")
        } catch (_: IOException) {
            // The coordinator turns this into a status; the ride is untouched.
        }
        // The healthy outbox is not affected and still accepts a ride.
        runBlocking { outbox.enqueue(entry("c", 5_000)) }
        assertEquals(listOf("a", "c"), pendingIds())
    }

    // endregion

    // region growth

    @Test
    fun `done rides are compacted to their ids, and the live ones are kept in order`() {
        runBlocking {
            val outbox = outbox()
            (1..450).forEach { outbox.enqueue(entry("ride-$it", it.toLong())) }
            (1..449).forEach { outbox.markDone("ride-$it") }
        }
        // Compaction ran along the way (once the superseded lines pass 200): 450 entries and 449 done lines uncompacted
        // would be 900 lines with the header.
        assertTrue("compacted on the way: ${file.readLines().size} lines", file.readLines().size < 900)
        assertEquals(listOf("ride-450"), pendingIds())
        assertTrue("a restart does not grow it", file.readLines().size < 900)
        runBlocking { assertEquals(449, outbox().doneCount()) }
    }

    @Test
    fun `an entry is small`() {
        runBlocking { outbox().enqueue(entry("ride-1794996000000")) }
        assertTrue("${file.length()} bytes", file.length() < 160)
    }

    // endregion

    private class DiedHere : RuntimeException("the process was killed here")
}
