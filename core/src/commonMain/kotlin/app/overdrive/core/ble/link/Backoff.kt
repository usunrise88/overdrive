package app.overdrive.core.ble.link

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long to wait before the next connection attempt (spec §4.1: "Each device reconnects on its own with backoff").
 * The wait after the *n*-th failure in a row is `initialDelay · factor^(n−1)`, capped at [maxDelay], then spread by
 * ±[jitter]. There is no attempt limit: a dropout never stops the ride, so a device is retried for as long as the ride
 * app runs.
 *
 * The defaults (1 s, ×2, 30 s cap, ±20 %) are a design choice, not a measurement: 1 s is long enough for Android's
 * stack to finish tearing a failed GATT object down, and 30 s is short enough that a trainer switched back on is found
 * within a breath. They are the values bench items 3.11 and 3.12 judge.
 */
public class BackoffPolicy(
    /** The wait after the first failure. */
    public val initialDelay: Duration = 1.seconds,
    /** How much longer each further failure waits, at least 1. */
    public val factor: Double = 2.0,
    /** The longest wait. Jitter never pushes a wait past it. */
    public val maxDelay: Duration = 30.seconds,
    /** The wait is multiplied by a random number in `1 ± jitter`; 0 turns it off, as tests do. */
    public val jitter: Double = DEFAULT_JITTER,
    /** A connection that held at least this long counts as healthy: the next failure starts the sequence over. */
    public val stableAfter: Duration = 30.seconds,
) {
    init {
        require(initialDelay.isPositive()) { "The initial delay must be positive, got $initialDelay" }
        require(factor >= 1.0) { "The factor must be at least 1, got $factor" }
        require(maxDelay >= initialDelay) { "The cap $maxDelay is below the initial delay $initialDelay" }
        require(jitter in 0.0..1.0) { "Jitter is a fraction 0 to 1, got $jitter" }
        require(!stableAfter.isNegative()) { "The stable time must not be negative, got $stableAfter" }
    }

    /**
     * The wait after [failures] failures in a row (1 or more), before jitter. Pure: the same input always gives the same
     * output.
     */
    public fun baseDelay(failures: Int): Duration {
        require(failures >= 1) { "Failures count from 1, got $failures" }
        var delay = initialDelay
        repeat(failures - 1) {
            delay *= factor
            if (delay >= maxDelay) return maxDelay
        }
        return minOf(delay, maxDelay)
    }

    /** [baseDelay] spread by jitter: [unit] in 0..1 picks the point (0.5 is no change), and the cap still holds. */
    public fun delayFor(
        failures: Int,
        unit: Double = JITTER_MIDPOINT,
    ): Duration {
        val spread = 1.0 + jitter * (2.0 * unit.coerceIn(0.0, 1.0) - 1.0)
        return minOf(baseDelay(failures) * spread, maxDelay)
    }

    public companion object {
        public const val DEFAULT_JITTER: Double = 0.2
        private const val JITTER_MIDPOINT = 0.5

        /** The policy without jitter, for tests that assert exact times. */
        public val EXACT: BackoffPolicy = BackoffPolicy(jitter = 0.0)
    }
}

/**
 * The state of the backoff for one device: counts consecutive failures, resets them once a connection has proved stable,
 * and answers how long to wait. It reads time through an injectable [TimeSource], so a test drives it with a fake clock.
 */
public class ReconnectBackoff(
    public val policy: BackoffPolicy = BackoffPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private var failures = 0
    private var connectedSince: TimeMark? = null

    /** Failures in a row since the last stable connection. */
    public val consecutiveFailures: Int get() = failures

    /** A connection attempt succeeded: starts the clock that decides whether it was stable. */
    public fun onConnected() {
        connectedSince = timeSource.markNow()
    }

    /** A connection attempt failed (never got to connected): the wait before the next attempt. */
    public fun onAttemptFailed(): Duration {
        connectedSince = null
        return next()
    }

    /**
     * A connected link dropped. If it had held for [BackoffPolicy.stableAfter] the failure count starts over, so the wait
     * is the initial one; a link that drops again within seconds keeps backing off.
     */
    public fun onLinkLost(): Duration {
        val held = connectedSince?.elapsedNow()
        connectedSince = null
        if (held != null && held >= policy.stableAfter) failures = 0
        return next()
    }

    /** Forget every failure: the rider pressed Retry, or Bluetooth came back. */
    public fun reset() {
        failures = 0
        connectedSince = null
    }

    private fun next(): Duration {
        failures++
        return policy.delayFor(failures, random.nextDouble())
    }
}
