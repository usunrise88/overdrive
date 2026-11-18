package app.overdrive.core.sync

import app.overdrive.core.ble.link.BackoffPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A failure of the disk or the network, as a test double: common code has no `IOException`. */
private class StorageFailure(
    message: String,
) : Exception(message)

/** The sync stub of spec §4.9: an outbox, a provider that does nothing, and a coordinator that never gets in the ride's way. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private fun entry(
        id: String,
        queuedAt: Long = 0,
    ) = OutboxEntry(
        id,
        enqueuedAtMillis = queuedAt,
        startedAtMillis = 1_700_000_000_000,
        durationSeconds = 600,
        title = "Ride $id",
    )

    /** A provider that follows a script of results and logs what it was asked. */
    private class ScriptedProvider(
        private val results: MutableList<SyncResult> = mutableListOf(),
        private val fallback: SyncResult = SyncResult.Acknowledged,
    ) : SyncProvider {
        override val name = "scripted"
        val asked = mutableListOf<String>()
        var throwOnce: Exception? = null

        fun then(vararg next: SyncResult) = apply { results += next }

        override suspend fun upload(entry: OutboxEntry): SyncResult {
            asked += entry.rideId
            throwOnce?.let {
                throwOnce = null
                throw it
            }
            return if (results.isEmpty()) fallback else results.removeAt(0)
        }
    }

    /** An outbox whose reads and writes fail while [broken]. */
    private class FlakyOutbox(
        val inner: Outbox = InMemoryOutbox(),
    ) : Outbox {
        var broken = false

        override suspend fun enqueue(entry: OutboxEntry) {
            if (broken) throw StorageFailure("disk full")
            inner.enqueue(entry)
        }

        override suspend fun pending(): List<OutboxEntry> {
            if (broken) throw StorageFailure("disk gone")
            return inner.pending()
        }

        override suspend fun markDone(rideId: String) {
            if (broken) throw StorageFailure("disk gone")
            inner.markDone(rideId)
        }
    }

    private val exact = BackoffPolicy(initialDelay = 5.seconds, factor = 2.0, maxDelay = 15.minutes, jitter = 0.0)

    private fun TestScope.coordinator(
        outbox: Outbox,
        provider: () -> SyncProvider,
        policy: BackoffPolicy = exact,
    ) = SyncCoordinator(backgroundScope, outbox, provider, policy, testTimeSource)

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    // region the outbox

    @Test
    fun `the in-memory outbox keeps entries oldest first, adds each ride once and forgets what is done`() =
        runTest {
            val outbox = InMemoryOutbox()
            outbox.enqueue(entry("b", queuedAt = 20))
            outbox.enqueue(entry("a", queuedAt = 10))
            outbox.enqueue(entry("a", queuedAt = 99))
            assertEquals(listOf("a", "b"), outbox.pending().map { it.rideId })
            assertEquals(10, outbox.pending().first().enqueuedAtMillis, "the second `a` did not replace the first")
            outbox.markDone("a")
            outbox.markDone("unknown")
            assertEquals(listOf("b"), outbox.pending().map { it.rideId })
        }

    @Test
    fun `an entry is made from a ride record`() {
        val record =
            app.overdrive.core.record.SyntheticRide
                .record()
        val made = OutboxEntry.of(record, enqueuedAtMillis = 5, title = "Sweet spot")
        assertEquals(record.ride.id, made.rideId)
        assertEquals(record.ride.startEpochMillis, made.startedAtMillis)
        assertEquals(record.summary.durationSeconds, made.durationSeconds)
        assertEquals(5, made.enqueuedAtMillis)
        assertEquals("Sweet spot", made.title)
    }

    // endregion

    // region the no-op provider

    @Test
    fun `the no-op provider acknowledges nothing, so every ride stays queued for Phase 2`() =
        runTest {
            val outbox = InMemoryOutbox()
            val sync = coordinator(outbox, { NoopSyncProvider })
            sync.start()
            sync.rideFinished(entry("one", 1))
            sync.rideFinished(entry("two", 2))
            advance(1_000)
            assertEquals(listOf("one", "two"), outbox.pending().map { it.rideId })
            assertEquals(SyncStatus.NotConfigured(2), sync.status.value)
            // Nothing keeps trying: an hour later it is still the same, with no backoff running.
            advance(60 * 60 * 1000L)
            assertEquals(SyncStatus.NotConfigured(2), sync.status.value)
            assertEquals(listOf("one", "two"), outbox.pending().map { it.rideId })
            assertEquals(SyncResult.NotConfigured, NoopSyncProvider.upload(entry("x")))
        }

    @Test
    fun `not configured asks once per kick and a provider that appears later takes everything`() =
        runTest {
            val outbox = InMemoryOutbox()
            val counting = ScriptedProvider(fallback = SyncResult.NotConfigured)
            var provider: SyncProvider = counting
            val sync = coordinator(outbox, { provider })
            sync.start()
            sync.rideFinished(entry("one", 1))
            advance(100)
            assertEquals(listOf("one"), counting.asked, "one try, no spinning")
            sync.kick()
            advance(100)
            assertEquals(listOf("one", "one"), counting.asked)

            provider = ScriptedProvider()
            sync.kick() // the settings now have a URL
            advance(100)
            assertEquals(emptyList(), outbox.pending())
            assertEquals(SyncStatus.Idle(0), sync.status.value)
        }

    // endregion

    // region draining

    @Test
    fun `a provider that acknowledges empties the outbox, oldest ride first`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider()
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("late", 30))
            outbox.enqueue(entry("early", 10))
            outbox.enqueue(entry("middle", 20))
            sync.start()
            advance(10)
            assertEquals(listOf("early", "middle", "late"), provider.asked)
            assertEquals(emptyList(), outbox.pending())
            assertEquals(SyncStatus.Idle(0), sync.status.value)
        }

    @Test
    fun `a ride that finishes later is drained too`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider()
            val sync = coordinator(outbox, { provider })
            sync.start()
            advance(10)
            assertEquals(SyncStatus.Idle(0), sync.status.value)
            sync.rideFinished(entry("fresh", 5))
            advance(10)
            assertEquals(listOf("fresh"), provider.asked)
            assertEquals(emptyList(), outbox.pending())
        }

    @Test
    fun `rides queued before the coordinator starts are not lost`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider()
            val sync = coordinator(outbox, { provider })
            sync.rideFinished(entry("early", 1))
            advance(10)
            assertEquals(
                listOf("early"),
                outbox.pending().map { it.rideId },
                "queued, not uploaded: nobody started the sync",
            )
            assertEquals(emptyList(), provider.asked)
            sync.start()
            advance(10)
            assertEquals(listOf("early"), provider.asked)
        }

    @Test
    fun `a failing upload backs off 5 s, 10 s, 20 s, keeps the ride, and the first success starts over`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider =
                ScriptedProvider().then(
                    SyncResult.RetryLater("offline"),
                    SyncResult.RetryLater("offline"),
                    SyncResult.RetryLater(null),
                )
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("a", 1))
            sync.start()
            advance(10)
            assertEquals(SyncStatus.WaitingToRetry(1, 5_000, "offline"), sync.status.value)
            advance(5_000)
            assertEquals(SyncStatus.WaitingToRetry(1, 10_000, "offline"), sync.status.value)
            advance(10_000)
            assertEquals(SyncStatus.WaitingToRetry(1, 20_000, null), sync.status.value)
            assertEquals(listOf("a"), outbox.pending().map { it.rideId }, "never dropped while it fails")
            advance(20_000)
            assertEquals(emptyList(), outbox.pending())
            assertEquals(SyncStatus.Idle(0), sync.status.value)

            // A new failure starts at 5 s again.
            provider.then(SyncResult.RetryLater("again"))
            sync.rideFinished(entry("b", 2))
            advance(10)
            assertEquals(SyncStatus.WaitingToRetry(1, 5_000, "again"), sync.status.value)
        }

    @Test
    fun `the wait is capped at the policy's maximum`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider(fallback = SyncResult.RetryLater("down"))
            val sync =
                coordinator(outbox, {
                    provider
                }, BackoffPolicy(initialDelay = 5.seconds, factor = 10.0, maxDelay = 1.minutes, jitter = 0.0))
            outbox.enqueue(entry("a", 1))
            sync.start()
            repeat(6) { advance(120_000) }
            val status = sync.status.value
            assertTrue(status is SyncStatus.WaitingToRetry && status.retryInMillis == 60_000L, "$status")
        }

    @Test
    fun `the default policy is for uploads - 5 seconds up to 15 minutes`() {
        val policy = SyncCoordinator.DEFAULT_POLICY
        assertEquals(5.seconds, policy.initialDelay)
        assertEquals(15.minutes, policy.maxDelay)
        assertEquals(5.seconds, policy.baseDelay(1))
        assertEquals(10.seconds, policy.baseDelay(2))
        assertEquals(15.minutes, policy.baseDelay(30))
    }

    @Test
    fun `a failing ride does not hold up the ones behind it forever, but the order is kept`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider().then(SyncResult.Acknowledged, SyncResult.RetryLater("x"))
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("a", 1))
            outbox.enqueue(entry("b", 2))
            outbox.enqueue(entry("c", 3))
            sync.start()
            advance(10)
            assertEquals(listOf("b", "c"), outbox.pending().map { it.rideId })
            assertEquals(SyncStatus.WaitingToRetry(2, 5_000, "x"), sync.status.value)
            advance(5_000)
            assertEquals(listOf("a", "b", "b", "c"), provider.asked)
            assertEquals(emptyList(), outbox.pending())
        }

    @Test
    fun `a kick cuts a backoff wait short and starts the schedule over`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider().then(SyncResult.RetryLater("x"), SyncResult.RetryLater("x"))
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("a", 1))
            sync.start()
            advance(10)
            assertEquals(SyncStatus.WaitingToRetry(1, 5_000, "x"), sync.status.value)
            advance(2_000)
            sync.kick()
            advance(10)
            assertEquals(2, provider.asked.size, "tried at once, not after 5 s")
            assertEquals(
                SyncStatus.WaitingToRetry(1, 5_000, "x"),
                sync.status.value,
                "and the schedule began again at 5 s",
            )
        }

    @Test
    fun `a provider that throws is a retry, not a crash`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider()
            provider.throwOnce = StorageFailure("connection reset")
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("a", 1))
            sync.start()
            advance(10)
            assertEquals(SyncStatus.WaitingToRetry(1, 5_000, "StorageFailure"), sync.status.value)
            advance(5_000)
            assertEquals(emptyList(), outbox.pending())
        }

    // endregion

    // region never in the ride's way

    @Test
    fun `finishing a ride returns at once, whatever the provider or the outbox is doing`() =
        runTest {
            val slow =
                object : SyncProvider {
                    override val name = "slow"

                    override suspend fun upload(entry: OutboxEntry): SyncResult {
                        delay(1.hours)
                        return SyncResult.Acknowledged
                    }
                }
            val outbox = InMemoryOutbox()
            val sync = coordinator(outbox, { slow })
            sync.start()
            sync.rideFinished(entry("one", 1))
            runCurrent()
            // The provider is busy for an hour. Another ride finishes: the call returns now and queues it behind.
            sync.rideFinished(entry("two", 2))
            runCurrent()
            assertEquals(setOf("one", "two"), outbox.pending().map { it.rideId }.toSet())
            assertTrue(sync.status.value is SyncStatus.Syncing)
        }

    @Test
    fun `an outbox that fails is a status, the ride is untouched, and a kick tries again when it heals`() =
        runTest {
            val outbox = FlakyOutbox()
            val provider = ScriptedProvider()
            val sync = coordinator(outbox, { provider })
            sync.start()
            advance(10)
            outbox.broken = true
            sync.rideFinished(entry("one", 1)) // does not throw
            advance(10)
            assertEquals(SyncStatus.OutboxUnavailable("StorageFailure"), sync.status.value)
            sync.kick()
            advance(10)
            assertEquals(SyncStatus.OutboxUnavailable("StorageFailure"), sync.status.value, "still broken: still said")
            outbox.broken = false
            sync.rideFinished(entry("two", 2))
            advance(10)
            assertEquals(SyncStatus.Idle(0), sync.status.value)
            assertEquals(
                listOf("two"),
                provider.asked,
                "the ride that could not be queued was the outbox's failure, not the ride's",
            )
        }

    @Test
    fun `an outbox that fails while marking done keeps the ride and reports it`() =
        runTest {
            val outbox = FlakyOutbox()
            val provider = ScriptedProvider()
            outbox.inner.enqueue(entry("a", 1))
            val breakingProvider =
                object : SyncProvider {
                    override val name = "breaking"

                    override suspend fun upload(entry: OutboxEntry): SyncResult {
                        outbox.broken = true
                        return provider.upload(entry)
                    }
                }
            val sync = coordinator(outbox, { breakingProvider })
            sync.start()
            advance(10)
            assertEquals(SyncStatus.OutboxUnavailable("StorageFailure"), sync.status.value)
            assertEquals(listOf("a"), outbox.inner.pending().map { it.rideId })
        }

    @Test
    fun `close stops it and keeps the outbox`() =
        runTest {
            val outbox = InMemoryOutbox()
            val provider = ScriptedProvider(fallback = SyncResult.RetryLater("x"))
            val sync = coordinator(outbox, { provider })
            outbox.enqueue(entry("a", 1))
            sync.start()
            advance(10)
            sync.close()
            assertEquals(SyncStatus.Stopped, sync.status.value)
            advance(60 * 60 * 1000L)
            assertEquals(1, provider.asked.size, "nothing was tried after the close")
            assertEquals(listOf("a"), outbox.pending().map { it.rideId })
        }

    @Test
    fun `a cancelled provider is not swallowed as a retry`() =
        runTest {
            val outbox = InMemoryOutbox()
            val cancelling =
                object : SyncProvider {
                    override val name = "cancelling"
                    val seen = MutableStateFlow(0)

                    override suspend fun upload(entry: OutboxEntry): SyncResult {
                        seen.value++
                        throw CancellationException("the scope ended")
                    }
                }
            val sync = coordinator(outbox, { cancelling })
            outbox.enqueue(entry("a", 1))
            sync.start()
            advance(60_000)
            assertEquals(1, cancelling.seen.value, "no backoff loop around a cancellation")
        }

    // endregion
}
