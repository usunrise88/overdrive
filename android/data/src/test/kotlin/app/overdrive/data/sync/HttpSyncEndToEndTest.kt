package app.overdrive.data.sync

import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.sync.HttpSyncProvider
import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.SyncCoordinator
import app.overdrive.core.sync.SyncReport
import app.overdrive.core.sync.SyncResult
import app.overdrive.core.sync.SyncStatus
import app.overdrive.data.rides.RideStore
import app.overdrive.data.rides.StoreResult
import app.overdrive.data.rides.TestRides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * The whole upload with nothing faked but the service: real rides stored by [RideStore], the persistent [FileOutbox], the
 * [SyncCoordinator], the [HttpSyncProvider] and the real `HttpURLConnection` transport, against [FakeService]. Every status
 * path of ADR 0014 section 6, idempotent retry, the order of the queue, and the token staying out of everything but the
 * Authorization header.
 */
class HttpSyncEndToEndTest {
    private val directory: File = Files.createTempDirectory("e2e").toFile()
    private val store = RideStore(File(directory, "rides"))
    private val outbox = FileOutbox(File(directory, "sync/outbox.log"))
    private val service = FakeService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var config = SyncConfig(service.baseUrl, service.token)
    private val provider = HttpSyncProvider({ config }, StoredRidePayloads(store), UrlConnectionTransport())
    private val fast =
        BackoffPolicy(initialDelay = 40.milliseconds, factor = 2.0, maxDelay = 200.milliseconds, jitter = 0.0)
    private val sync = SyncCoordinator(scope, outbox, { provider }, fast)

    @After
    fun cleanUp() {
        sync.close()
        scope.cancel()
        service.close()
        directory.deleteRecursively()
    }

    /** Stores a ride like the app does and queues it, oldest first. */
    private fun ride(
        number: Int,
        queue: Boolean = true,
    ): String {
        val id = "ride-${TestRides.START + number * 86_400_000L}"
        val result =
            store.finalize(
                TestRides.record(90, id, TestRides.START + number * 86_400_000L),
                "Ride $number",
                3600,
            )
        check(result is StoreResult.Ok)
        if (queue) {
            runBlocking {
                outbox.enqueue(
                    OutboxEntry(id, number.toLong(), TestRides.START + number * 86_400_000L, 90, "Ride $number"),
                )
            }
        }
        return id
    }

    private fun await(
        what: String,
        timeoutMs: Long = 15_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what (status ${sync.status.value})")
    }

    private fun queued() = runBlocking { outbox.pending() }.map { it.rideId }

    private fun paths() = service.received.map { "${it.method} ${it.path}" }

    @Test
    fun `two rides go oldest first, record then FIT, byte for byte, and the outbox empties`() {
        val old = ride(1)
        val new = ride(2)
        sync.start()
        await("everything is uploaded") { sync.status.value == SyncStatus.Idle(0) && queued().isEmpty() }
        assertEquals(
            listOf("PUT /v1/rides/$old", "PUT /v1/rides/$old/fit", "PUT /v1/rides/$new", "PUT /v1/rides/$new/fit"),
            paths(),
        )
        assertTrue(service.received.all { it.authorization == "Bearer ${service.token}" })
        assertEquals(
            listOf("application/json", "application/vnd.ant.fit", "application/json", "application/vnd.ant.fit"),
            service.received.map { it.contentType },
        )
        for (id in listOf(old, new)) {
            assertArrayEquals(store.recordFile(id).readBytes(), service.storedRecord(id))
            assertArrayEquals(store.fitFile(id).readBytes(), service.storedFit(id))
        }
        assertTrue("the success is dated", sync.report.value.lastSuccessMillis != null)
    }

    @Test
    fun `a server that is down keeps the ride and the retry sends it again, idempotently`() {
        val id = ride(1)
        // The record is stored, the FIT is answered with a 503, and the retry sends both again: the service says it has the
        // record already (200) and takes the FIT (201).
        service.script += FakeService.PASS
        service.script += FakeService.Reply(503, "<html>maintenance</html>")
        sync.start()
        await("it went through") { queued().isEmpty() }
        assertEquals(
            listOf("PUT /v1/rides/$id", "PUT /v1/rides/$id/fit", "PUT /v1/rides/$id", "PUT /v1/rides/$id/fit"),
            paths(),
        )
        assertArrayEquals("the same bytes the second time", service.received[0].body, service.received[2].body)
        assertArrayEquals(store.fitFile(id).readBytes(), service.storedFit(id))
        await("idle") { sync.status.value == SyncStatus.Idle(0) }
    }

    @Test
    fun `an unreachable server is a wait with a plain reason, and the ride goes when it is back`() {
        val id = ride(1)
        val real = config
        config = SyncConfig("http://127.0.0.1:1", service.token) // nothing listens on port 1
        sync.start()
        await("it is waiting to retry") { sync.status.value is SyncStatus.WaitingToRetry }
        val waiting = sync.status.value as SyncStatus.WaitingToRetry
        assertEquals("Service unreachable", waiting.reason)
        assertEquals(listOf(id), queued())
        config = real
        sync.kick()
        await("it was uploaded") { queued().isEmpty() }
    }

    @Test
    fun `a rejected token is reported once, not asked again, and tried again when it changes`() {
        val id = ride(1)
        config = SyncConfig(service.baseUrl, "tok-wrong-0123456789abcdef0123456789abcdef")
        sync.start()
        await("the token is rejected") { sync.status.value == SyncStatus.TokenRejected(1) }
        assertEquals(1, service.received.size)
        sync.kick()
        sync.kick()
        Thread.sleep(300)
        assertEquals("not asked again with the same token", 1, service.received.size)
        assertEquals(listOf(id), queued())

        config = SyncConfig(service.baseUrl, service.token)
        sync.kick(retryRefused = true)
        await("the right token works") { queued().isEmpty() }
    }

    @Test
    fun `a ride the service refuses is recorded and skipped, and the rides behind it still go`() {
        val first = ride(1)
        val second = ride(2)
        service.script +=
            FakeService.error(409, "start_time_conflict", "A ride that started at 1 is already stored as x")
        sync.start()
        await("the second ride is uploaded") { queued() == listOf(first) && service.storedFit(second) != null }
        await("the refusal is reported") { sync.report.value.refused.isNotEmpty() }
        assertEquals(
            "A ride that started at 1 is already stored as x",
            sync.report.value.refused
                .single()
                .message,
        )
        await("nothing is left to try") { sync.status.value == SyncStatus.Idle(1) }
        val requests = service.received.size
        Thread.sleep(400)
        assertEquals("not retried in a loop", requests, service.received.size)
        assertEquals("never dropped from the outbox", listOf(first), queued())

        // The rider presses Send now, the service takes it this time.
        sync.kick(retryRefused = true)
        await("the refused ride goes") { queued().isEmpty() && sync.report.value.refused.isEmpty() }
        assertEquals(SyncReport(sync.report.value.lastSuccessMillis, emptyList()), sync.report.value)
    }

    @Test
    fun `a ride whose files were deleted is refused, not retried, and the others go`() {
        val gone = ride(1)
        val kept = ride(2)
        assertTrue(store.fitFile(gone).delete())
        sync.start()
        await("the other ride is uploaded") { service.storedFit(kept) != null && queued() == listOf(gone) }
        await("it is reported") { sync.report.value.refused.isNotEmpty() }
        assertEquals(
            "The ride's files are no longer on this tablet",
            sync.report.value.refused
                .single()
                .message,
        )
        assertFalse("nothing was sent for it", paths().any { it.contains(gone) })
    }

    @Test
    fun `the token is in the Authorization header only`() {
        val token = service.token
        ride(1)
        service.script += FakeService.error(422, "invalid_record", "samples[3].t: expected 3")
        sync.start()
        await("a refusal") { sync.report.value.refused.isNotEmpty() }
        val everything =
            listOf(sync.status.value.toString(), sync.report.value.toString(), config.toString(), provider.toString()) +
                service.received.flatMap {
                    listOf(
                        it.path,
                        it.contentType.orEmpty(),
                        String(it.body, Charsets.ISO_8859_1),
                    )
                }
        for (text in everything) assertFalse("the token leaked: $text", text.contains(token))
        assertTrue(service.received.all { it.authorization == "Bearer $token" })
        assertTrue(SyncResult.TokenRejected.toString().isNotEmpty())
    }
}
