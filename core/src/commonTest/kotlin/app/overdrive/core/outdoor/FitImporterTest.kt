package app.overdrive.core.outdoor

import app.overdrive.core.health.RetryPolicy
import app.overdrive.core.remote.ADDRESS
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ScriptedTransport
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.TOKEN
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The outdoor ride's way to the service (ADR 0028): a file kept before it is sent and removed only after the contract's answer, kept
 * when the service cannot be reached, retried with a backoff and by the rider, parked with its reason when the service says no for good,
 * and never queued twice. Every file here is synthetic.
 */
class FitImporterTest {
    private var wall = 1_700_000_000_000L
    private var config = SyncConfig(ADDRESS, TOKEN)
    private val outbox = InMemoryFitOutbox()
    private val retry = RetryPolicy(30_000, 1_800_000)

    private fun importer(
        transport: ScriptedFitTransport,
        store: FitOutbox = outbox,
        maxWaiting: Int = FitImporter.MAX_WAITING,
        cfg: () -> SyncConfig = { config },
    ) = FitImporter(ServiceClient(cfg, ScriptedTransport(), null, transport), store, { wall }, retry, maxWaiting)

    private val ride = FitSamples.bytes(1)

    // region the share

    @Test
    fun `a file is kept before it is sent sent as FIT bytes and removed on the contract's answer`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, FitSamples.imported()))
            var keptWhileSending = -1
            transport.onRequest = { keptWhileSending = outbox.pending().size }
            val importer = importer(transport)
            val outcome = importer.import(ride)
            val imported = assertIs<FitOutcome.Imported>(outcome)
            assertEquals("fit-0123456789abcdef", imported.id)
            assertEquals(1, keptWhileSending, "the file was on disk when the request left")
            val request = transport.posts.single()
            assertEquals(fitUrl(), request.url)
            assertEquals("application/vnd.ant.fit", request.contentType)
            assertTrue(ride.contentEquals(request.body))
            assertTrue(outbox.pending().isEmpty(), "removed after the service's 2xx")
            assertEquals(1, importer.status.value.accepted)
            assertEquals(0, importer.status.value.waiting)
        }

    @Test
    fun `a ride the service already has is reported as such and the kept copy goes`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(200, FitSamples.duplicate(ActivityImportReason.OVERLAP)))
            val outcome = importer(transport).import(ride)
            val duplicate = assertIs<FitOutcome.Duplicate>(outcome)
            assertEquals(ActivityImportReason.OVERLAP, duplicate.reason)
            assertTrue(outbox.pending().isEmpty())
        }

    // region offline

    @Test
    fun `no network keeps the file says why and the next try is after a backoff that doubles`() =
        runTest {
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport)
            val outcome = importer.import(ride)
            val waiting = assertIs<FitOutcome.Waiting>(outcome)
            assertTrue(assertNotNull(waiting.problem).isOffline)
            assertEquals(1, outbox.pending().size)
            val first = importer.status.value
            assertEquals(1, first.waiting)
            assertEquals(wall + 30_000, first.nextRetryMillis)

            // Inside the backoff an automatic run does not go out; the rider's does.
            assertEquals(1, transport.posts.size)
            val skipped = importer.flush(FitFlushReason.RETRY)
            assertEquals(1, transport.posts.size, "inside the backoff nothing is sent")
            assertTrue(skipped.failed)
            wall += 30_000
            importer.flush(FitFlushReason.RETRY)
            assertEquals(2, transport.posts.size)
            assertEquals(wall + 60_000, importer.status.value.nextRetryMillis, "the wait doubles")
            val manual = importer.flush(FitFlushReason.MANUAL)
            assertEquals(3, transport.posts.size, "Sync now does not wait")
            assertTrue(manual.failed)
            assertEquals(1, outbox.pending().size, "still kept")
        }

    @Test
    fun `a service that comes back gets the file and a lost answer is only a duplicate the second time`() =
        runTest {
            val transport =
                ScriptedFitTransport(
                    TransportException(TransportFailure.Dropped),
                    HttpAnswer(200, FitSamples.duplicate(ActivityImportReason.SAME_FILE)),
                )
            val importer = importer(transport)
            assertIs<FitOutcome.Waiting>(importer.import(ride))
            val flushed = importer.flush(FitFlushReason.MANUAL)
            assertEquals(1, flushed.sent)
            assertFalse(flushed.failed)
            assertTrue(outbox.pending().isEmpty())
            assertEquals(0, importer.status.value.waiting)
            assertNull(importer.status.value.problem)
            assertNull(importer.status.value.nextRetryMillis)
        }

    @Test
    fun `a restart finds what waits and sends it without the share screen`() =
        runTest {
            val offline = ScriptedFitTransport(TransportException(TransportFailure.Unreachable))
            val before = importer(offline)
            assertIs<FitOutcome.Waiting>(before.import(ride))
            assertIs<FitOutcome.Waiting>(before.import(FitSamples.bytes(2)))

            val after = importer(ScriptedFitTransport(HttpAnswer(201, FitSamples.imported())))
            assertEquals(0, after.status.value.waiting, "nothing is known before the outbox is read")
            after.load()
            assertEquals(2, after.status.value.waiting)
            val result = after.flush(FitFlushReason.APP_OPENED)
            assertEquals(2, result.sent)
            assertTrue(outbox.pending().isEmpty())
        }

    @Test
    fun `files go oldest first and a failure leaves the rest waiting in order`() =
        runTest {
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport)
            val a = FitSamples.bytes(1)
            val b = FitSamples.bytes(2)
            val c = FitSamples.bytes(3)
            for (file in listOf(a, b, c)) importer.import(file)
            transport.posts.clear()
            transport.then(HttpAnswer(201, FitSamples.imported()), TransportException(TransportFailure.TimedOut))
            val result = importer.flush(FitFlushReason.MANUAL)
            assertEquals(1, result.sent)
            assertTrue(result.failed)
            assertTrue(a.contentEquals(transport.posts[0].body), "the oldest first")
            assertTrue(b.contentEquals(transport.posts[1].body))
            assertEquals(2, transport.posts.size, "it stopped at the first failure")
            assertEquals(2, outbox.pending().size)
        }

    @Test
    fun `a server error a busy service a timeout and a service without the route keep the file`() =
        runTest {
            for (
            answer in
            listOf(
                FitSamples.error(500, "internal_error"),
                FitSamples.error(503, "internal_error"),
                FitSamples.error(408, "internal_error"),
                FitSamples.error(429, "internal_error"),
                FitSamples.error(404, "not_found"),
            )
            ) {
                val store = InMemoryFitOutbox()
                val importer = importer(ScriptedFitTransport(answer), store)
                assertIs<FitOutcome.Waiting>(importer.import(ride), "status ${answer.status}")
                assertEquals(1, store.pending().size)
                assertTrue(store.parked().isEmpty())
            }
        }

    @Test
    fun `a 2xx that is not the contract's answer is not an acceptance`() =
        runTest {
            val store = InMemoryFitOutbox()
            val importer = importer(ScriptedFitTransport(HttpAnswer(200, "<html>Sign in to the wifi</html>")), store)
            val outcome = importer.import(ride)
            val waiting = assertIs<FitOutcome.Waiting>(outcome)
            assertEquals(ProblemKind.NOT_THE_SERVICE, assertNotNull(waiting.problem).kind)
            assertEquals(1, store.pending().size)
            assertEquals(0, importer.status.value.accepted)
        }

    @Test
    fun `a refused token keeps the file is not asked again until the rider asks and then goes through`() =
        runTest {
            val transport = ScriptedFitTransport(FitSamples.error(401, "unauthorized"))
            val importer = importer(transport)
            val outcome = importer.import(ride)
            assertEquals(ProblemKind.TOKEN_REJECTED, assertNotNull(assertIs<FitOutcome.Waiting>(outcome).problem).kind)
            assertEquals(1, transport.posts.size)
            wall += 10 * 60_000
            importer.flush(FitFlushReason.BACKGROUND)
            assertEquals(1, transport.posts.size, "the service refused this token: it is not asked again")
            transport.then(HttpAnswer(201, FitSamples.imported()))
            val manual = importer.flush(FitFlushReason.MANUAL)
            assertEquals(2, transport.posts.size, "Sync now tries once more")
            assertEquals(1, manual.sent)
        }

    @Test
    fun `with no address or token the file is kept and nothing is sent and that is not a failure`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, FitSamples.imported()))
            config = SyncConfig("", "")
            val importer = importer(transport)
            val waiting = assertIs<FitOutcome.Waiting>(importer.import(ride))
            assertNull(waiting.problem)
            assertTrue(transport.posts.isEmpty())
            assertEquals(1, outbox.pending().size)
            assertNull(importer.status.value.nextRetryMillis)
            config = SyncConfig(ADDRESS, TOKEN)
            assertEquals(1, importer.flush(FitFlushReason.APP_OPENED).sent)
        }

    // region refusals

    @Test
    fun `each reason the service gives parks the file with that reason and is not tried again`() =
        runTest {
            val cases =
                listOf(
                    FitSamples.error(422, "invalid_fit") to FitRefusal.NOT_A_FIT_FILE,
                    FitSamples.error(422, "unsupported_sport") to FitRefusal.NOT_CYCLING,
                    FitSamples.error(422, "empty_activity") to FitRefusal.NO_RIDE,
                    FitSamples.error(413, "payload_too_large") to FitRefusal.TOO_LARGE,
                    FitSamples.error(415, "unsupported_media_type") to FitRefusal.WRONG_TYPE,
                    FitSamples.error(400, "invalid_query") to FitRefusal.SERVICE_REFUSED,
                    FitSamples.error(409, "fit_conflict") to FitRefusal.SERVICE_REFUSED,
                )
            for ((answer, expected) in cases) {
                val store = InMemoryFitOutbox()
                val transport = ScriptedFitTransport(answer)
                val importer = importer(transport, store)
                val outcome = importer.import(ride)
                val refused = assertIs<FitOutcome.Refused>(outcome, "status ${answer.status}")
                assertEquals(expected, refused.refusal)
                assertEquals(answer.status, refused.status)
                assertTrue(store.pending().isEmpty(), "out of the queue")
                assertEquals(listOf(expected), importer.status.value.parked)
                assertTrue(ride.contentEquals(store.parkedBytes(store.parked().single().seq)), "kept whole")
                importer.flush(FitFlushReason.MANUAL)
                assertEquals(1, transport.posts.size, "a refusal is not retried")
            }
        }

    @Test
    fun `a refused file does not stand in the way of the ones behind it`() =
        runTest {
            // Keep both while the service is away, then let it answer: the first is refused, the second goes.
            val keeper = importer(ScriptedFitTransport(TransportException(TransportFailure.NoNetwork)))
            keeper.import(FitSamples.bytes(1))
            keeper.import(FitSamples.bytes(2))
            val later =
                importer(
                    ScriptedFitTransport(
                        FitSamples.error(422, "unsupported_sport"),
                        HttpAnswer(201, FitSamples.imported()),
                    ),
                )
            later.load()
            val result = later.flush(FitFlushReason.MANUAL)
            assertEquals(1, result.sent)
            assertFalse(result.failed)
            assertEquals(listOf(FitRefusal.NOT_CYCLING), later.status.value.parked)
            assertEquals(0, later.status.value.waiting)
        }

    @Test
    fun `only the last few refused files are kept on disk`() =
        runTest {
            val importer = importer(ScriptedFitTransport(FitSamples.error(422, "unsupported_sport")))
            repeat(FitImporter.MAX_PARKED + 3) { importer.import(FitSamples.bytes(it + 1)) }
            assertEquals(FitImporter.MAX_PARKED, outbox.parked().size)
            assertEquals(FitImporter.MAX_PARKED, importer.status.value.parked.size)
            assertTrue(outbox.pending().isEmpty())
        }

    @Test
    fun `what is certainly not a FIT file is refused on the phone and never kept or sent`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, FitSamples.imported()))
            val importer = importer(transport)
            val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40)
            val cases =
                mapOf(
                    ByteArray(0) to FitRefusal.EMPTY_FILE,
                    zip to FitRefusal.ARCHIVE,
                    "<gpx></gpx> not a fit".encodeToByteArray().plus(ByteArray(20)) to FitRefusal.NOT_A_FIT_FILE,
                    FitSamples.bytes().also {
                        it[8] = '.'.code.toByte()
                        it[9] = 'F'.code.toByte()
                        it[10] =
                            'I'.code.toByte()
                        it[11] = 'X'.code.toByte()
                    } to
                        FitRefusal.NOT_A_FIT_FILE,
                    FitSamples.bytes().copyOf(13) to FitRefusal.NOT_A_FIT_FILE,
                    ByteArray(ACTIVITY_FIT_MAX_BYTES + 1) to FitRefusal.TOO_LARGE,
                )
            for ((bytes, expected) in cases) {
                assertEquals(FitOutcome.Refused(expected), importer.import(bytes))
            }
            assertTrue(transport.posts.isEmpty())
            assertTrue(outbox.pending().isEmpty())
            assertTrue(outbox.parked().isEmpty())
        }

    @Test
    fun `the largest file the service takes is not refused here`() {
        val atTheLimit = FitSamples.bytes(1, ACTIVITY_FIT_MAX_BYTES)
        assertNull(FitFileCheck.refusalOf(atTheLimit))
    }

    @Test
    fun `a file that is already waiting is not queued twice`() =
        runTest {
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport)
            importer.import(ride)
            importer.import(ride.copyOf())
            assertEquals(1, outbox.pending().size)
            assertEquals(1, importer.status.value.waiting)
            assertEquals(2, transport.posts.size, "the second share tried the one copy again")
            importer.import(FitSamples.bytes(2))
            assertEquals(2, outbox.pending().size)
        }

    @Test
    fun `only so many files wait and the next is refused with the reason`() =
        runTest {
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport, maxWaiting = 3)
            repeat(3) { importer.import(FitSamples.bytes(it + 1)) }
            val outcome = importer.import(FitSamples.bytes(9))
            assertEquals(FitOutcome.Refused(FitRefusal.QUEUE_FULL), outcome)
            assertEquals(3, outbox.pending().size)
        }

    @Test
    fun `a disk that refuses means nothing is sent and nothing is kept`() =
        runTest {
            val transport = ScriptedFitTransport(HttpAnswer(201, FitSamples.imported()))
            outbox.failAdds = true
            assertEquals(FitOutcome.NotKept, importer(transport).import(ride))
            assertTrue(transport.posts.isEmpty())
        }

    @Test
    fun `a saved copy that is damaged is set aside and is not sent`() =
        runTest {
            val offline = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(offline)
            importer.import(ride)
            outbox.damaged += outbox.pending().single().seq
            offline.posts.clear()
            offline.then(HttpAnswer(201, FitSamples.imported()))
            val result = importer.flush(FitFlushReason.MANUAL)
            assertEquals(0, result.sent)
            assertTrue(offline.posts.isEmpty())
            assertEquals(listOf(FitRefusal.DAMAGED), importer.status.value.parked)
            assertEquals(0, importer.status.value.waiting)
        }

    // region one at a time

    @Test
    fun `a second run while one is sending does nothing and a file shared meanwhile is sent once`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport)
            importer.import(FitSamples.bytes(1))
            transport.posts.clear()
            transport.then(HttpAnswer(201, FitSamples.imported()))
            transport.onRequest = {
                if (transport.posts.size == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
            }
            val running = launch { importer.flush(FitFlushReason.MANUAL) }
            entered.await()
            assertTrue(importer.flush(FitFlushReason.MANUAL).alreadyRunning)
            // The rider shares the same file again while the run is sending it: it is the run's, not a second request.
            val again = importer.import(FitSamples.bytes(1))
            assertIs<FitOutcome.Waiting>(again)
            assertEquals(1, transport.posts.size)
            gate.complete(Unit)
            running.join()
            assertEquals(1, transport.posts.size)
            assertTrue(outbox.pending().isEmpty())
        }

    // region privacy

    @Test
    fun `no outcome status or request line holds the token or a byte of the file`() =
        runTest {
            val transport = ScriptedFitTransport(TransportException(TransportFailure.NoNetwork))
            val importer = importer(transport)
            val outcome = importer.import(ride)
            val printed =
                listOf(outcome.toString(), importer.status.value.toString(), transport.posts.single().toString())
            for (text in printed) {
                assertFalse(text.contains(TOKEN), text)
                assertFalse(text.contains(ride.joinToString(",")), text)
            }
            assertFalse(transport.posts.single().toString().contains("tok-"))
        }
}
