package app.overdrive.ride.overlay

import app.overdrive.ride.host.RideMapping
import app.overdrive.ride.host.RideUi
import app.overdrive.uikit.overlay.OverlayUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * The overlay's input (ADR 0006): the ride mapped to the plain state the composables draw, with every update that
 * changes nothing the overlay shows dropped, and the rest spaced at least [OverlayPolicy.MIN_UPDATE_INTERVAL_MS] apart.
 *
 * The engine publishes a snapshot for every sensor reading, several a second on a real trainer, and most of a snapshot
 * (averages, the trace, the finished summary) is not on the overlay at all. Both are filtered here, off the main thread
 * (the caller collects on `Dispatchers.Default`), so the window recomposes at most once a second at rest (spec §4.6, §7.3).
 */
fun overlayStates(
    rides: Flow<RideUi>,
    now: () -> Long = ::monotonicMillis,
    minIntervalMs: Long = OverlayPolicy.MIN_UPDATE_INTERVAL_MS,
): Flow<OverlayUiState> =
    rides
        .map { RideMapping.overlayState(it.snapshot, it.profile) }
        .distinctUntilChanged()
        .rateLimited(minIntervalMs, now, OverlayPolicy::isRiderAction)

/** A monotonic clock in milliseconds that runs on the same time base as coroutine delays on the real dispatchers. */
fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Lets the first value through at once and then at most one value per [minIntervalMs], always the latest: a value that
 * arrives too early waits for its turn and is replaced if a newer one arrives meanwhile, so nothing is ever delayed by
 * more than [minIntervalMs] and the last value always arrives. A value for which [bypass] (given the last value that was
 * passed on and the new one) is true goes through immediately.
 *
 * [now] must be a monotonic clock in milliseconds that moves with the coroutine dispatcher's delays (virtual time in
 * tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Any> Flow<T>.rateLimited(
    minIntervalMs: Long,
    now: () -> Long,
    bypass: (previous: T, next: T) -> Boolean = { _, _ -> false },
): Flow<T> {
    val upstream = this
    return flow {
        var last: T? = null
        var lastAt = 0L
        emitAll(
            upstream
                .transformLatest { value ->
                    val before = last
                    if (before != null && !bypass(before, value)) {
                        val wait = lastAt + minIntervalMs - now()
                        if (wait > 0) delay(wait)
                    }
                    last = value
                    lastAt = now()
                    emit(value)
                }.distinctUntilChanged(),
        )
    }
}
