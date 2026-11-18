package app.overdrive.companion.model

import app.overdrive.core.remote.ResourceState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The words of the design's mock-ups: English, UTC, Wednesday 18 November 2026, 07:42. */
object TestClock {
    val now: Long = Instant.parse("2026-11-18T07:42:00Z").toEpochMilli()
    val fetched: Long = Instant.parse("2026-11-18T07:40:00Z").toEpochMilli()
    val clock = Clock({ now }, ZoneId.of("UTC"), Locale.ENGLISH)
    val words = Words(clock)

    /** The same day, with time passing: what a test that waits for a backoff needs, since the backoff reads the clock. */
    val moving: Clock = movingClock()

    private fun movingClock(): Clock {
        val started = System.nanoTime()
        val from = now
        return Clock({ from + (System.nanoTime() - started) / 1_000_000 }, ZoneId.of("UTC"), Locale.ENGLISH)
    }

    fun <T : Any> fresh(value: T) = ResourceState(loaded = true, value = value, fetchedAtMillis = fetched, asked = true)
}
