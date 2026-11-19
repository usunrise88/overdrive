package app.overdrive.ride.overlay

import app.overdrive.core.ride.RideState
import app.overdrive.uikit.overlay.OverlayUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * When the overlay window exists and when its service stops (ADR 0006). Pure functions and tiny state machines, so the
 * rules are unit-tested in virtual time without a service or a window.
 */
object OverlayPolicy {
    /** A rider who is riding or paused is on a live ride. */
    fun isLive(state: RideState): Boolean = state == RideState.RIDING || state == RideState.PAUSED

    /**
     * The window is shown while a ride is live **and** Overdrive itself is not on screen: the ride screen already shows
     * everything the overlay would, so the overlay only earns its place over another app.
     */
    fun windowWanted(
        state: RideState,
        appVisible: Boolean,
    ): Boolean = isLive(state) && !appVisible

    /**
     * How long the app must stay out of sight before the window appears. Recreating the activity (rotation, a
     * configuration change) stops and starts it within a few frames; without this wait the overlay would flash up and
     * go away again. Hiding is immediate: the rider has come back to the app.
     */
    const val SHOW_DELAY_MS: Long = 300

    /**
     * Without a live ride this long after the service started, the start was a mistake (the trainer was not
     * connected, the rider backed out): stop, so no notification is left behind.
     */
    const val START_GRACE_MS: Long = 15_000

    /** The shortest time between two updates of the displayed numbers (spec §4.6: at most once a second). */
    const val MIN_UPDATE_INTERVAL_MS: Long = 1_000

    /**
     * Whether the window should exist, given the ride's state and whether the app is visible: [windowWanted] with the
     * [SHOW_DELAY_MS] wait before appearing. Emits only changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun windowShown(
        wanted: Flow<Boolean>,
        showDelayMs: Long = SHOW_DELAY_MS,
    ): Flow<Boolean> =
        wanted
            .distinctUntilChanged()
            .transformLatest { want ->
                if (want) delay(showDelayMs)
                emit(want)
            }.distinctUntilChanged()

    /**
     * Whether a change of displayed state must bypass the once-a-second limit: what the rider just did (gear,
     * target, intensity, pause, and the target heart rate of a heart-rate step that the gear buttons move by 2 bpm) is shown at once,
     * because the overlay is the Click's feedback (design/system.md §5.2). The stall guard taking hold and letting go is a change of the
     * fifth cell too, so it is shown at once as well. Everything else, which the engine and the sensors change continuously, waits for
     * its turn.
     */
    fun isRiderAction(
        previous: OverlayUiState,
        next: OverlayUiState,
    ): Boolean =
        previous.control != next.control ||
            previous.paused != next.paused ||
            previous.hold?.targetBpm != next.hold?.targetBpm
}

/**
 * Decides when the overlay service stops itself: when the ride it was started for is over and gone (the host is back to
 * `READY` after the summary), when the summary is on screen, or when no ride ever started.
 */
class ServiceStopRule {
    private var sawRide = false

    /** True when the service should stop now, for the latest [state] and whether the app is on screen. */
    fun onUpdate(
        state: RideState,
        appVisible: Boolean,
    ): Boolean =
        when (state) {
            RideState.RIDING, RideState.PAUSED -> {
                sawRide = true
                false
            }

            // The summary is on screen: nothing left for the service to keep alive.
            RideState.FINISHED -> {
                sawRide = true
                appVisible
            }

            // The ride was dismissed (Done) or never existed: stop once one has been seen.
            RideState.IDLE, RideState.READY -> {
                sawRide
            }
        }

    /** The start grace period is over: stop if no ride ever showed up. */
    fun onGraceElapsed(): Boolean = !sawRide
}
