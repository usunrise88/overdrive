package app.overdrive.core.ble.link

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BackoffTest {
    @Test
    fun `the defaults double from one second up to a cap of thirty`() {
        val policy = BackoffPolicy()
        assertEquals(1.seconds, policy.initialDelay)
        assertEquals(2.0, policy.factor)
        assertEquals(30.seconds, policy.maxDelay)
        assertEquals(0.2, policy.jitter)
        assertEquals(30.seconds, policy.stableAfter)
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30), (1..8).map { policy.baseDelay(it).inWholeSeconds.toInt() })
    }

    @Test
    fun `a huge failure count stays at the cap without overflow`() {
        assertEquals(30.seconds, BackoffPolicy().baseDelay(10_000))
    }

    @Test
    fun `jitter spreads a wait by plus or minus a fraction and never past the cap`() {
        val policy = BackoffPolicy(jitter = 0.2)
        assertEquals(4.seconds, policy.delayFor(3, unit = 0.5))
        assertEquals(3.2.seconds, policy.delayFor(3, unit = 0.0))
        assertEquals(4.8.seconds, policy.delayFor(3, unit = 1.0))
        assertEquals(30.seconds, policy.delayFor(9, unit = 1.0), "the cap holds after jitter")
        assertEquals(24.seconds, policy.delayFor(9, unit = 0.0))
    }

    @Test
    fun `without jitter the unit does not matter`() {
        val policy = BackoffPolicy.EXACT
        assertEquals(policy.delayFor(4, 0.0), policy.delayFor(4, 1.0))
    }

    @Test
    fun `invalid policies are refused`() {
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(initialDelay = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(factor = 0.5) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(initialDelay = 10.seconds, maxDelay = 1.seconds) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(jitter = 1.5) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy().baseDelay(0) }
    }

    @Test
    fun `failed attempts back off, and a long-lived connection starts the sequence over`() {
        val clock = TestTimeSource()
        val backoff = ReconnectBackoff(BackoffPolicy.EXACT, clock)
        assertEquals(1.seconds, backoff.onAttemptFailed())
        assertEquals(2.seconds, backoff.onAttemptFailed())
        assertEquals(4.seconds, backoff.onAttemptFailed())
        assertEquals(3, backoff.consecutiveFailures)

        backoff.onConnected()
        clock += 31.seconds
        assertEquals(1.seconds, backoff.onLinkLost(), "held 31 s, longer than the stable time: start over")
        assertEquals(1, backoff.consecutiveFailures)
    }

    @Test
    fun `a link that drops again within seconds keeps backing off`() {
        val clock = TestTimeSource()
        val backoff = ReconnectBackoff(BackoffPolicy.EXACT, clock)
        backoff.onConnected()
        clock += 3.seconds
        assertEquals(1.seconds, backoff.onLinkLost())
        backoff.onConnected()
        clock += 3.seconds
        assertEquals(2.seconds, backoff.onLinkLost(), "flapping is not healthy")
        backoff.onConnected()
        clock += 29.seconds + 999.milliseconds
        assertEquals(4.seconds, backoff.onLinkLost(), "just under the stable time")
    }

    @Test
    fun `reset forgets every failure`() {
        val backoff = ReconnectBackoff(BackoffPolicy.EXACT, TestTimeSource())
        repeat(5) { backoff.onAttemptFailed() }
        backoff.reset()
        assertEquals(0, backoff.consecutiveFailures)
        assertEquals(1.seconds, backoff.onAttemptFailed())
    }

    @Test
    fun `seeded jitter is reproducible and inside its bounds`() {
        val a = ReconnectBackoff(BackoffPolicy(), TestTimeSource(), Random(3))
        val b = ReconnectBackoff(BackoffPolicy(), TestTimeSource(), Random(3))
        repeat(20) {
            val delay = a.onAttemptFailed()
            assertEquals(delay, b.onAttemptFailed())
            assertTrue(delay <= 30.seconds && delay >= 800.milliseconds)
        }
    }
}
