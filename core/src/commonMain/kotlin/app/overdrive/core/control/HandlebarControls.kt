package app.overdrive.core.control

import app.overdrive.core.click.ButtonChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The glue between the Click and the ride (spec §4.7): the Click's button changes go in, through the [ButtonMapping]
 * and the [GestureDetector], and the ten [ClickAction]s come out, on the clock the host gives. It owns one coroutine that
 * sleeps until the detector's next deadline (a hold, a repeated step) or the next change, whichever comes first.
 *
 * ```
 * val controls = HandlebarControls(
 *     scope, click.buttons, mapping = { settings.buttonMapping }, onAction = router::dispatch,
 *     nowMillis = { SystemClock.elapsedRealtime() },
 * )
 * controls.start()
 * ```
 *
 * - **The mapping is read at every change**, so it is a live setting; a different mapping from the last change **forgets the
 *   presses in progress** (their buttons are not the same roles any more).
 * - **A synthetic change** (the link dropped while buttons were held, [ButtonChange.synthetic]) forgets every press and fires
 *   nothing for them: the release was lost, not made.
 * - **A change is a whole state** ([ButtonChange.held]): releases are applied before presses, roles in enum order, so a
 *   frame that moves a finger from one button to the next is a release and then a press.
 * - **An action that throws is dropped**; the next one still runs. A ride never fails on the handlebar.
 * - **Time** is read when a change is *processed*, not from the change's own stamp: the transport's clock is a different one,
 *   and the few milliseconds of a dispatch do not matter at a 500 ms threshold. In a test pass the scheduler's time.
 *
 * @param nowMillis a monotonic clock in milliseconds, the clock of the coroutine delays: `SystemClock.elapsedRealtime()` on
 * Android, `{ testScheduler.currentTime }` under a test scheduler.
 * @param onAction receives every action, on the controls' coroutine. The router's [ActionRouter.dispatch] goes here.
 */
public class HandlebarControls(
    private val scope: CoroutineScope,
    private val changes: Flow<ButtonChange>,
    private val mapping: () -> ButtonMapping,
    private val onAction: (ClickAction) -> Unit,
    timing: GestureTiming = GestureTiming(),
    private val nowMillis: () -> Long,
) {
    private val detector = GestureDetector(timing)
    private val inbox = Channel<ButtonChange>(Channel.UNLIMITED)
    private val mutableActions =
        MutableSharedFlow<GestureEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var jobs: List<Job> = emptyList()

    /** Every action as it fired, for a haptic or the overlay's "what the rider just did". Hot, without replay. */
    public val actions: SharedFlow<GestureEvent> = mutableActions.asSharedFlow()

    /** Starts listening. Idempotent. */
    public fun start() {
        if (jobs.isNotEmpty()) return
        jobs =
            listOf(
                scope.launch(start = CoroutineStart.UNDISPATCHED) { changes.collect { inbox.trySend(it) } },
                scope.launch { loop() },
            )
    }

    /** Stops listening and forgets any press in progress. */
    public fun close() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        detector.cancelAll()
    }

    private suspend fun loop() {
        var current = mapping()
        var down = emptySet<HandlebarButton>()
        var coachDown = false
        while (true) {
            val deadline = detector.nextDeadlineMillis
            val change =
                if (deadline == null) {
                    inbox.receive()
                } else {
                    withTimeoutOrNull((deadline - nowMillis()).coerceAtLeast(0)) { inbox.receive() }
                }
            val now = nowMillis()
            if (change == null) {
                emit(detector.advance(now))
                continue
            }
            if (change.synthetic) {
                detector.cancelAll()
                down = emptySet()
                coachDown = false
                continue
            }
            val latest = mapping()
            if (latest != current) {
                detector.cancelAll()
                down = emptySet()
                coachDown = false
                current = latest
            }
            // The live coach's button, when it has one (spec §12.1): the action fires on the press, once, and there is no hold.
            val coachNowDown = current.coachMuteDown(change.held)
            if (coachNowDown && !coachDown) fireCoach()
            coachDown = coachNowDown
            val nowDown = current.rolesDown(change.held)
            for (button in HandlebarButton.entries) {
                if (button in down && button !in nowDown) emit(detector.release(button, now))
            }
            for (button in HandlebarButton.entries) {
                if (button in nowDown && button !in down) emit(detector.press(button, now))
            }
            down = nowDown
        }
    }

    /** The live coach's button was pressed. It is on no [HandlebarButton], so it is carried out and not announced as a gesture of one. */
    private fun fireCoach() {
        try {
            onAction(ClickAction.MUTE_COACH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dropped, like any action that fails: the ride goes on.
        }
    }

    private fun emit(events: List<GestureEvent>) {
        for (event in events) {
            mutableActions.tryEmit(event)
            try {
                onAction(event.action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failing action is dropped: the ride and the next gesture go on.
            }
        }
    }

    private companion object {
        const val BUFFER = 64
    }
}
