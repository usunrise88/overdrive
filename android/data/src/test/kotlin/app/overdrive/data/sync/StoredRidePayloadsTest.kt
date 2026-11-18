package app.overdrive.data.sync

import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.RideUnavailableException
import app.overdrive.data.rides.RideStore
import app.overdrive.data.rides.StoreResult
import app.overdrive.data.rides.TestRides
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** What an upload reads: the record and the FIT the tablet already keeps for a ride, and the ways a ride is not there. */
class StoredRidePayloadsTest {
    private val directory: File = Files.createTempDirectory("payloads").toFile()
    private val store = RideStore(directory)
    private val payloads = StoredRidePayloads(store)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun entryFor(id: String) =
        OutboxEntry(RideStore.idFor(id), enqueuedAtMillis = 1, startedAtMillis = TestRides.START, durationSeconds = 120)

    private fun store(
        id: String,
        seconds: Int = 120,
    ) {
        val result = store.finalize(TestRides.record(seconds, id), "Test ride", utcOffsetSeconds = 3600)
        check(result is StoreResult.Ok) { "$result" }
    }

    private fun unavailable(entry: OutboxEntry): String {
        try {
            runBlocking { payloads.payload(entry) }
        } catch (e: RideUnavailableException) {
            return e.message.orEmpty()
        }
        fail("expected the ride to be unavailable")
        return ""
    }

    @Test
    fun `a stored ride is its record and its FIT, as they are on disk`() {
        store("ride-1700000000000")
        val payload = runBlocking { payloads.payload(entryFor("ride-1700000000000")) }
        assertEquals("ride-1700000000000", payload.rideId)
        val record =
            java.io.ByteArrayOutputStream().also { out ->
                payload.record.writeTo { b, o, l -> out.write(b, o, l) }
            }
        val fit = java.io.ByteArrayOutputStream().also { out -> payload.fit.writeTo { b, o, l -> out.write(b, o, l) } }
        assertArrayEquals(store.recordFile("ride-1700000000000").readBytes(), record.toByteArray())
        assertArrayEquals(store.fitFile("ride-1700000000000").readBytes(), fit.toByteArray())
        assertEquals(record.size().toLong(), payload.record.length)
        assertEquals(fit.size().toLong(), payload.fit.length)
    }

    @Test
    fun `the ride's own id is read from the record, not taken from the file name`() {
        // An id with a dot is valid for the service but not for a file name: the files are named by a safe form of it.
        store("ride.1")
        val entry = entryFor("ride.1")
        assertTrue("the file name is not the id: ${entry.rideId}", entry.rideId != "ride.1")
        assertEquals("ride.1", runBlocking { payloads.payload(entry) }.rideId)
    }

    @Test
    fun `a ride that is not on the tablet is unavailable`() {
        assertEquals("The ride's files are no longer on this tablet", unavailable(entryFor("ride-nothing")))
    }

    @Test
    fun `a ride without its FIT is unavailable until it is stored again`() {
        store("ride-1")
        assertTrue(store.fitFile("ride-1").delete())
        assertEquals("The ride's files are no longer on this tablet", unavailable(entryFor("ride-1")))
    }

    @Test
    fun `a damaged record is unavailable`() {
        store("ride-1")
        store.recordFile("ride-1").writeText("""{"schema":1,"ride":""")
        assertEquals("The ride's record on this tablet is damaged", unavailable(entryFor("ride-1")))
        store.recordFile("ride-1").writeText("not json at all")
        assertEquals("The ride's record on this tablet is damaged", unavailable(entryFor("ride-1")))
    }

    @Test
    fun `a ride larger than the service takes is unavailable without being read`() {
        store("ride-1")
        RandomAccessFile(store.recordFile("ride-1"), "rw").use { it.setLength(StoredRidePayloads.MAX_RECORD_BYTES + 1) }
        assertEquals("The ride is larger than the service accepts", unavailable(entryFor("ride-1")))
        store("ride-2")
        RandomAccessFile(store.fitFile("ride-2"), "rw").use { it.setLength(StoredRidePayloads.MAX_FIT_BYTES + 1) }
        assertEquals("The ride is larger than the service accepts", unavailable(entryFor("ride-2")))
    }

    @Test
    fun `an entry cannot reach a file outside the rides folder`() {
        val outside = File(directory.parentFile, "outside.json").also { it.writeText("{}") }
        try {
            assertEquals(
                "The ride's files are no longer on this tablet",
                unavailable(entryFor("../outside")),
            )
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `the last upload is remembered across restarts and a missing or damaged file reads as never`() {
        val file = File(directory, "sync/last-upload")
        assertEquals(null, LastUploadFile(file).read())
        LastUploadFile(file).write(1_794_996_123_456)
        assertEquals(1_794_996_123_456, LastUploadFile(file).read())
        file.writeText("garbage")
        assertEquals(null, LastUploadFile(file).read())
        file.writeText("-5")
        assertEquals(null, LastUploadFile(file).read())
        LastUploadFile(file).write(7)
        assertEquals(7L, LastUploadFile(file).read())
        assertEquals("no temporary file is left", listOf("last-upload"), file.parentFile.list().orEmpty().toList())
    }
}
