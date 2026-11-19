package app.overdrive.ride.live

import androidx.compose.runtime.Immutable
import app.overdrive.core.control.CoachControl
import app.overdrive.core.live.CuePlayback
import app.overdrive.core.live.CuePlayer
import app.overdrive.core.live.CueStream
import app.overdrive.core.live.LiveCoachClient
import app.overdrive.core.live.LiveCoachState
import app.overdrive.core.live.LiveCuePlayed
import app.overdrive.core.live.LiveEffect
import app.overdrive.core.live.LivePlan
import app.overdrive.core.live.LiveSessionController
import app.overdrive.core.live.LiveSessionEnd
import app.overdrive.core.live.LiveSessionEndReason
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.wantsLiveCoach
import app.overdrive.ride.host.LiveCoachSink
import app.overdrive.ride.host.RideChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the overlay shows of the live coach (spec §12.1, docs/adr/0039): whether there is a session (the mute control, "Coach on" or "Coach muted"), the
 * one-time "Live coach unavailable", and the plate with the text of the cue being spoken. Immutable and tiny, so it changes only when one of the three does.
 */
@Immutable
data class LiveCoachUi(
    val state: LiveCoachState = LiveCoachState.Off,
    /** The text of the cue being spoken, and for a few seconds after; null when there is none. Never logged. */
    val plate: String? = null,
) {
    companion object {
        val None = LiveCoachUi()
    }
}

/**
 * The tablet's live coach (spec §12.1, docs/adr/0039): the one object that joins the ride to the service. It lives with the process, in the container,
 * and so does not depend on any activity (the video app is in front): the foreground service of a live ride is what keeps the process alive.
 *
 * It is a thin driver. **The decisions are `core`'s**: [LiveSessionController] (when to start, send, restart, stop; mute), [CuePlayback] (whether a cue is
 * played, in what order, with what plate), [CueStream] (the connection that listens and reconnects) and [LiveCoachClient] (the requests, through the one
 * authenticated service client). What is here is the plumbing: it feeds the ride's snapshots to the controller, carries out what the controller asks for
 * on the network, tells it how that went, and wakes it at the time it asks to be woken.
 *
 * - **A ride has a controller only if the coach is wanted**: the workout asks for it (`live_coach`), the rider has it on in Settings (read when the ride
 *   starts) and the service is set up. Otherwise nothing here runs for the ride and the overlay says nothing.
 * - **One thread.** Everything runs on [scope], which must be single-threaded: the controller, the playback and the plumbing are touched by nothing else,
 *   whichever thread the ride, the overlay, the Click or the network calls from.
 * - **No error reaches the riding screen**: every call into the controller is guarded, and a failure of any request is an answer the controller has a rule for.
 * - **Nothing here logs** a reading, a cue's text, an address or a token.
 *
 * @param scope a single-threaded scope owned by the container.
 * @param player speaks one cue ([AndroidCuePlayer]; a fake in a test).
 * @param enabled whether the rider has the live coach on in Settings, read when a ride starts.
 * @param nowMillis a monotonic clock in the time of `delay`.
 * @param wallMillis the wall clock, for the `clock` fields of the bodies.
 */
class LiveCoach(
    private val scope: CoroutineScope,
    private val client: LiveCoachClient,
    private val player: CuePlayer,
    private val enabled: () -> Boolean,
    private val nowMillis: () -> Long,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : LiveCoachSink,
    CoachControl {
    private class Ride(
        val number: Int,
        val controller: LiveSessionController,
    ) {
        var playback: CuePlayback? = null
        var stream: Job? = null
        var wake: Job? = null
        var session: String? = null
    }

    private val mutableUi = MutableStateFlow(LiveCoachUi.None)

    /** What the overlay shows of the coach; changes only when the state or the plate does. */
    val ui: StateFlow<LiveCoachUi> = mutableUi.asStateFlow()

    private var ride: Ride? = null

    /** The rides this driver has decided about, by number: a ride that is not eligible is decided once and never looked at again. */
    private var decidedNumber = 0
    private var plate: String? = null

    // ---- From the ride ---------------------------------------------------------------------------

    override fun onSnapshot(
        number: Int,
        choice: RideChoice,
        rideId: String?,
        snapshot: RideSnapshot,
    ) {
        scope.launch { guarded { handleRide(number, choice, rideId, snapshot) } }
    }

    private fun handleRide(
        number: Int,
        choice: RideChoice,
        rideId: String?,
        snapshot: RideSnapshot,
    ) {
        val current = ride
        if (current != null && current.number != number) {
            // A new ride is prepared: the old one is over, whatever state it was left in.
            apply(current, current.controller.close())
            ride = null
        }
        var active = ride
        if (active == null) {
            if (decidedNumber == number) return
            val live = snapshot.state == RideState.RIDING || snapshot.state == RideState.PAUSED
            if (!live) return
            decidedNumber = number
            active = startRide(number, choice, rideId) ?: return
            ride = active
        }
        apply(active, active.controller.onRide(nowMillis(), snapshot))
    }

    /** A controller for the ride, or null when the coach is not wanted for it (the workout does not ask, the rider has it off, nothing is set up). */
    private fun startRide(
        number: Int,
        choice: RideChoice,
        rideId: String?,
    ): Ride? {
        val planned = choice as? RideChoice.Planned ?: return null
        if (!planned.workout.wantsLiveCoach || !enabled() || !client.isConfigured) return null
        val id = rideId?.takeIf { it.isNotEmpty() } ?: return null
        val plan =
            LivePlan(
                rideId = id,
                workoutId = planned.workout.id,
                structureHash = planned.structureHash,
                variantRidden = planned.variant,
            )
        return Ride(number, LiveSessionController(plan, wallMillis))
    }

    // ---- From the rider --------------------------------------------------------------------------

    /** The overlay's tap, or the Click's button: the coach is muted for the rest of the ride. A ride without a coach ignores it. */
    override fun mute() {
        scope.launch {
            guarded {
                val active = ride ?: return@guarded
                active.playback?.mute()
                apply(active, active.controller.mute(nowMillis()))
            }
        }
    }

    // ---- The controller's wishes -----------------------------------------------------------------

    private fun apply(
        active: Ride,
        effects: List<LiveEffect>,
    ) {
        for (effect in effects) {
            when (effect) {
                is LiveEffect.StartSession -> {
                    scope.launch {
                        guarded {
                            val answer = client.start(effect.request)
                            apply(active, active.controller.onStartAnswer(nowMillis(), answer))
                        }
                    }
                }

                is LiveEffect.OpenStream -> {
                    openStream(active, effect.sessionId)
                }

                is LiveEffect.CloseStream -> {
                    closeStream(active)
                }

                is LiveEffect.SendSnapshot -> {
                    scope.launch {
                        guarded {
                            val answer = client.snapshot(effect.sessionId, effect.snapshot)
                            apply(active, active.controller.onSnapshotAnswer(nowMillis(), effect.sessionId, answer))
                        }
                    }
                }

                is LiveEffect.EndSession -> {
                    scope.launch {
                        guarded {
                            client.end(
                                effect.sessionId,
                                LiveSessionEnd(effect.reason, wallMillis()),
                            )
                        }
                    }
                }
            }
        }
        schedule(active)
        publish(active)
    }

    /** Listens to the session's cues, with a playback of its own: cue numbers start again in every session. */
    private fun openStream(
        active: Ride,
        sessionId: String,
    ) {
        closeStream(active)
        val playback =
            CuePlayback(
                scope = scope,
                player = player,
                nowMillis = nowMillis,
                report = { cueId, played ->
                    scope.launch { guarded { client.played(sessionId, cueId, LiveCuePlayed(played, wallMillis())) } }
                },
                onPlate = {
                    plate = it
                    publish(active)
                },
            )
        if (active.controller.muted) playback.mute()
        active.playback = playback
        active.session = sessionId
        active.stream =
            scope.launch {
                guarded {
                    val end = CueStream(client.cues(sessionId), onCue = playback::offer).run()
                    // The stream stopped by itself: the controller decides what that means (a new session, or the end of the coach).
                    apply(active, active.controller.onStreamEnd(nowMillis(), sessionId, end))
                }
            }
    }

    private fun closeStream(active: Ride) {
        active.stream?.cancel()
        active.stream = null
        active.playback?.close()
        active.playback = null
        active.session = null
    }

    /** Wakes the controller when it asks to be: the next snapshot, a new start, the end of the "unavailable" notice. */
    private fun schedule(active: Ride) {
        active.wake?.cancel()
        active.wake = null
        val at = active.controller.nextWakeMillis() ?: return
        active.wake =
            scope.launch {
                delay((at - nowMillis()).coerceAtLeast(0))
                guarded { apply(active, active.controller.onTime(nowMillis())) }
            }
    }

    private fun publish(active: Ride) {
        mutableUi.value =
            LiveCoachUi(active.controller.state, plate.takeIf { active.controller.state is LiveCoachState.On })
        if (active.controller.isDone && active.session == null && active.controller.state == LiveCoachState.Off) {
            // Nothing more to come from this ride: let go of it, so the next one is decided afresh.
            if (ride === active) ride = null
            active.wake?.cancel()
            plate = null
        }
    }

    /** No failure here may reach the ride: a request that threw, a body that was not the contract's, a bug. */
    private suspend inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is said: the ride goes on and the coach is simply off until the controller's rules start it again.
        }
    }

    /** Stops everything for good. Only tests and the container's close call it. */
    fun close() {
        scope.launch {
            ride?.let { apply(it, it.controller.close(LiveSessionEndReason.STOPPED)) }
        }
    }
}
