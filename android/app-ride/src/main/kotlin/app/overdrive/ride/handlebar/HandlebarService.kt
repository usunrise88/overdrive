package app.overdrive.ride.handlebar

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.GestureEvent
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The handlebar, running for as long as the app's process does (spec §4.7, ADR 0012): the Click's button changes go
 * through the **mapping** and the **gesture layer** (`core`'s `HandlebarControls`) into the **one router** (`ActionRouter`),
 * which presses the ride's buttons and the media keys. It lives in the container's scope, so it needs **no activity**: a
 * press with a video app in front reaches the ride exactly as one with the app on screen does. What keeps the process
 * alive behind another app is the foreground service of the overlay, which a live ride starts.
 *
 * - **The mapping** is read at every change (a learned one applies at once).
 * - **The timing** is a flow: a developer setting replaces the gesture layer with one that has the new timing. A press in
 *   progress at that moment is forgotten, which is what a change of the thresholds should do.
 * - **Suspended** (while the rider teaches the app the buttons, [ButtonLearning]): gestures are still recognised but
 *   nothing is carried out, so a press made to be learned never shifts a gear or pauses a ride.
 *
 * @param buttons the hot flow of button changes of whichever Click is paired (`PairedDevices.clickButtons`).
 * @param nowMillis a monotonic clock in milliseconds: `SystemClock.elapsedRealtime()`, or the scheduler's time in a test.
 */
class HandlebarService(
    private val scope: CoroutineScope,
    private val buttons: Flow<ButtonChange>,
    private val mapping: () -> ButtonMapping,
    private val timing: Flow<GestureTiming>,
    private val router: ActionRouter,
    private val nowMillis: () -> Long,
    private val suspended: () -> Boolean = { false },
) {
    private val mutableActions =
        MutableSharedFlow<GestureEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var job: Job? = null

    /** Every action that was carried out, for a haptic or the overlay's "what the rider just did". Hot, no replay. */
    val actions: SharedFlow<GestureEvent> = mutableActions.asSharedFlow()

    /** Starts listening. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                timing.collectLatest { gestureTiming ->
                    coroutineScope {
                        val controls =
                            HandlebarControls(
                                scope = this,
                                changes = buttons,
                                mapping = mapping,
                                onAction = ::carryOut,
                                timing = gestureTiming,
                                nowMillis = nowMillis,
                            )
                        controls.start()
                        launch { controls.actions.collect { if (!suspended()) mutableActions.tryEmit(it) } }
                        awaitCancellation()
                    }
                }
            }
    }

    /** Stops for good. */
    fun close() {
        job?.cancel()
        job = null
    }

    private fun carryOut(action: ClickAction) {
        if (!suspended()) router.dispatch(action)
    }

    private companion object {
        const val BUFFER = 64
    }
}
