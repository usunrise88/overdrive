package app.overdrive.core.live

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.sync.HttpAnswer

/** What the ride is, for the start of a session (`POST /v1/live/sessions`): the ride's id and the workout the tablet plays. */
public data class LivePlan(
    val rideId: String,
    /** The planned workout's id (also when its easier version is ridden), or null. */
    val workoutId: String?,
    /** The structure hash the service sent with the workout the tablet plays (the easier version's, when that is ridden). */
    val structureHash: String?,
    val variantRidden: Boolean,
)

/** What the overlay says about the live coach. Nothing is said about a coach that was never wanted or is not there. */
public sealed interface LiveCoachState {
    /** No session now: nothing is shown (not wanted, offline, between two sessions, over). */
    public data object Off : LiveCoachState

    /** The service said why the coach is not there: "Live coach unavailable", **once**, for a few seconds at the start, never a permanent element. */
    public data object Unavailable : LiveCoachState

    /** A session is on: the mute control shows, [muted] says whether the rider muted the coach for the rest of the ride. */
    public data class On(
        val muted: Boolean,
    ) : LiveCoachState
}

/** The answer to a start, as the controller needs it. */
public sealed interface LiveStartAnswer {
    /** The coach is on: [sessionId] names the session. */
    public data class Started(
        val sessionId: String,
    ) : LiveStartAnswer

    /** The service says the coach is off for this ride, and why. */
    public data class Off(
        val reason: LiveCoachOffReason,
    ) : LiveStartAnswer

    /** No answer (offline, a slow or failing service): the ride is unaffected and the start is tried again later. */
    public data object Failed : LiveStartAnswer

    /** An answer that will not change by asking again (a service without the live coach, a rejected token, nothing set up): no retry, nothing said. */
    public data object Unsupported : LiveStartAnswer

    public companion object {
        /** What the service's answer to a start, or the failure to get one, comes to. Never throws. */
        public fun of(outcome: Outcome<HttpAnswer>): LiveStartAnswer =
            when (outcome) {
                is Outcome.NotSetUp -> {
                    Unsupported
                }

                is Outcome.Failed -> {
                    if (outcome.problem.kind in RETRYABLE) Failed else Unsupported
                }

                is Outcome.Ok -> {
                    try {
                        val response = LiveCoachJson.decodeSessionStartResponse(outcome.body)
                        val id = response.sessionId
                        val reason = response.coach.reason
                        when {
                            id != null -> Started(id)
                            reason != null -> Off(reason)
                            else -> Unsupported
                        }
                    } catch (e: IllegalArgumentException) {
                        Unsupported
                    }
                }
            }
    }
}

/** The answer to a snapshot, as the controller needs it. */
public sealed interface LiveSnapshotAnswer {
    public data object Accepted : LiveSnapshotAnswer

    /** `404`: the service does not know the session any more. */
    public data object SessionGone : LiveSnapshotAnswer

    /** No answer, or one that says nothing the controller can act on: the next snapshot is the next try. */
    public data object Failed : LiveSnapshotAnswer

    public companion object {
        public fun of(outcome: Outcome<HttpAnswer>): LiveSnapshotAnswer =
            when (outcome) {
                is Outcome.Ok -> Accepted
                is Outcome.NotSetUp -> Failed
                is Outcome.Failed -> if (outcome.problem.kind == ProblemKind.NO_SUCH_ROUTE) SessionGone else Failed
            }
    }
}

private val RETRYABLE =
    setOf(
        ProblemKind.NO_NETWORK,
        ProblemKind.NAME_NOT_FOUND,
        ProblemKind.NETWORK_BLOCKED,
        ProblemKind.NO_ANSWER,
        ProblemKind.SECURE_CONNECTION,
        ProblemKind.SERVER_ERROR,
    )

/** What the controller asks its driver to do. The driver does it off the ride's thread and tells the controller how it went. */
public sealed interface LiveEffect {
    /** `POST /v1/live/sessions`; answer with [LiveSessionController.onStartAnswer]. */
    public data class StartSession(
        val request: LiveSessionStart,
    ) : LiveEffect

    /** Listen to the session's cue stream ([CueStream]); tell the controller how it ended with [LiveSessionController.onStreamEnd]. */
    public data class OpenStream(
        val sessionId: String,
    ) : LiveEffect

    /** Stop listening. */
    public data class CloseStream(
        val sessionId: String,
    ) : LiveEffect

    /** `POST /v1/live/sessions/{id}/snapshots`; answer with [LiveSessionController.onSnapshotAnswer]. */
    public data class SendSnapshot(
        val sessionId: String,
        val snapshot: LiveSnapshot,
    ) : LiveEffect

    /** `POST /v1/live/sessions/{id}/end`. Nothing comes back that matters. */
    public data class EndSession(
        val sessionId: String,
        val reason: LiveSessionEndReason,
    ) : LiveEffect
}

/**
 * The tablet's side of one ride's live-coach session (spec §12.1, docs/adr/0039): **when** to ask for a session, to send a snapshot, to ask
 * again and to stop, as a state machine over **virtual time**. It owns no clock, no thread and no network: every call is given `nowMillis`
 * (any monotonic clock) and returns the [LiveEffect]s the driver is to carry out; the driver answers each through an `on…Answer` call. It
 * is unit-tested with a table. One controller serves one ride and is touched by one thread (the ride's).
 *
 * **The rules**
 *
 * - **Start.** At the first moment the ride is live (riding or paused) it asks for a session with the ride's id, the workout's id, the structure hash it
 *   was served and whether the easier version is ridden. The ride never waits for the answer.
 * - **The coach is off** (`coach.on` false): it stops for the ride. For every reason except `no-live-coach` the state is [LiveCoachState.Unavailable] for
 *   [UNAVAILABLE_SHOWN_MILLIS] (the overlay says "Live coach unavailable" once), then [LiveCoachState.Off]; `no-live-coach` says nothing.
 * - **No answer** (offline, a failing service): nothing is said or shown, and the start is tried again every [RESTART_INTERVAL_MILLIS] while the ride
 *   lasts, so a tablet whose wifi comes back after the start gets its coach. An answer that will not change (a service without the live coach, a rejected token)
 *   is not asked again.
 * - **Snapshots** every [SNAPSHOT_INTERVAL_MILLIS] from the moment the session is on (the first at once), with `seq` from 0 in each session, `muted` and `paused`; one is
 *   in flight at a time (a slow network skips one instead of piling them up).
 * - **A snapshot's `404`** (or a stream's) means the service forgot the session: a new one is started, **at most one start every [RESTART_INTERVAL_MILLIS]**.
 * - **The stream ends:** `ended` and `superseded` stop the coach for the ride; `shutdown` starts a new session (under the same limit); a rejected token or
 *   nothing set up stops it.
 * - **Mute** is for the rest of the ride and cannot be undone: the snapshots carry `muted: true` from then on (at once), and the driver ignores every cue.
 * - **The end of the ride** (finished, or [close]) ends the session; a session whose start answer arrives after that is ended at once.
 *
 * **Nothing here can raise an error on the riding screen**: a failure is an answer, and every answer has a rule.
 */
public class LiveSessionController(
    private val plan: LivePlan,
    private val wallClockMillis: () -> Long,
    private val builder: LiveSnapshotBuilder = LiveSnapshotBuilder(),
) {
    private enum class Phase { WAITING, STARTING, ACTIVE, GAP, DONE }

    private var phase = Phase.WAITING
    private var sessionId: String? = null
    private var seq = 0
    private var nextSnapshotAt = 0L
    private var snapshotInFlight = false
    private var lastStartAt = Long.MIN_VALUE
    private var restartAt = 0L
    private var unavailableUntil = Long.MIN_VALUE
    private var rideLive = false
    private var rideFinished: LiveSessionEndReason? = null

    /** The rider muted the coach for the rest of the ride. */
    public var muted: Boolean = false
        private set

    /** What the overlay says. */
    public var state: LiveCoachState = LiveCoachState.Off
        private set

    /** Nothing more will happen: the ride is over or the coach is off for it. */
    public val isDone: Boolean get() = phase == Phase.DONE

    /** The session that is on now, or null. */
    public val activeSessionId: String? get() = if (phase == Phase.ACTIVE) sessionId else null

    /** The ride's latest snapshot. Returns the effects it causes (the start, the end). */
    public fun onRide(
        nowMillis: Long,
        snapshot: RideSnapshot,
    ): List<LiveEffect> {
        builder.accept(snapshot)
        if (phase == Phase.DONE) return emptyList()
        rideLive = snapshot.state == RideState.RIDING || snapshot.state == RideState.PAUSED
        if (snapshot.state == RideState.FINISHED) {
            val progress = snapshot.workout
            val reason =
                if (progress != null &&
                    progress.workoutRemainingSeconds == 0
                ) {
                    LiveSessionEndReason.FINISHED
                } else {
                    LiveSessionEndReason.STOPPED
                }
            return finish(reason)
        }
        if (rideLive && phase == Phase.WAITING) return start(nowMillis)
        return emptyList()
    }

    /** The time is [nowMillis]: a snapshot may be due, a start may be tried again, the notice may have been shown long enough. */
    public fun onTime(nowMillis: Long): List<LiveEffect> {
        if (state == LiveCoachState.Unavailable && nowMillis >= unavailableUntil) state = LiveCoachState.Off
        return when (phase) {
            Phase.GAP -> if (rideLive && nowMillis >= restartAt) start(nowMillis) else emptyList()
            Phase.ACTIVE -> snapshotIfDue(nowMillis)
            Phase.WAITING, Phase.STARTING, Phase.DONE -> emptyList()
        }
    }

    /** The moment [onTime] is next worth calling, or null when nothing is due. */
    public fun nextWakeMillis(): Long? {
        val times = ArrayList<Long>(2)
        if (state == LiveCoachState.Unavailable) times += unavailableUntil
        when (phase) {
            // While a snapshot is on its way the next one waits for its answer, which asks again: no wake-up spins on a time already past.
            Phase.ACTIVE -> if (rideLive && !snapshotInFlight) times += nextSnapshotAt

            Phase.GAP -> times += restartAt

            Phase.WAITING, Phase.STARTING, Phase.DONE -> Unit
        }
        return times.minOrNull()
    }

    /** The answer to a [LiveEffect.StartSession]. */
    public fun onStartAnswer(
        nowMillis: Long,
        answer: LiveStartAnswer,
    ): List<LiveEffect> {
        if (phase == Phase.DONE) {
            // The ride ended while the start was on its way: do not leave the session behind.
            return if (answer is LiveStartAnswer.Started) {
                listOf(
                    LiveEffect.EndSession(answer.sessionId, endReason()),
                )
            } else {
                emptyList()
            }
        }
        if (phase != Phase.STARTING) return emptyList()
        return when (answer) {
            is LiveStartAnswer.Started -> {
                phase = Phase.ACTIVE
                sessionId = answer.sessionId
                seq = 0
                snapshotInFlight = false
                nextSnapshotAt = nowMillis
                state = LiveCoachState.On(muted)
                listOf<LiveEffect>(LiveEffect.OpenStream(answer.sessionId)) + snapshotIfDue(nowMillis)
            }

            is LiveStartAnswer.Off -> {
                phase = Phase.DONE
                if (answer.reason != LiveCoachOffReason.NO_LIVE_COACH) {
                    state = LiveCoachState.Unavailable
                    unavailableUntil = nowMillis + UNAVAILABLE_SHOWN_MILLIS
                }
                emptyList()
            }

            LiveStartAnswer.Failed -> {
                phase = Phase.GAP
                restartAt = lastStartAt + RESTART_INTERVAL_MILLIS
                emptyList()
            }

            LiveStartAnswer.Unsupported -> {
                phase = Phase.DONE
                emptyList()
            }
        }
    }

    /** The answer to a [LiveEffect.SendSnapshot] of [session]. An answer of a session that is not the one on now is of no interest. */
    public fun onSnapshotAnswer(
        nowMillis: Long,
        session: String,
        answer: LiveSnapshotAnswer,
    ): List<LiveEffect> {
        if (phase != Phase.ACTIVE || session != sessionId) return emptyList()
        snapshotInFlight = false
        return when (answer) {
            LiveSnapshotAnswer.SessionGone -> goneOrShutdown(nowMillis, session)
            LiveSnapshotAnswer.Accepted, LiveSnapshotAnswer.Failed -> snapshotIfDue(nowMillis)
        }
    }

    /** The cue stream of [session] ended by itself ([CueStream.run] returned). */
    public fun onStreamEnd(
        nowMillis: Long,
        session: String,
        end: CueStreamEnd,
    ): List<LiveEffect> {
        if (phase != Phase.ACTIVE || session != sessionId) return emptyList()
        return when (end) {
            is CueStreamEnd.Ended -> {
                if (end.reason ==
                    LiveStreamEndReason.SHUTDOWN
                ) {
                    goneOrShutdown(nowMillis, session)
                } else {
                    stopForTheRide(session)
                }
            }

            CueStreamEnd.SessionGone -> {
                goneOrShutdown(nowMillis, session)
            }

            CueStreamEnd.NotSetUp, CueStreamEnd.TokenRejected -> {
                stopForTheRide(session)
            }
        }
    }

    /** The rider muted the coach: for the rest of the ride. Asking again changes nothing. */
    public fun mute(nowMillis: Long): List<LiveEffect> {
        if (muted) return emptyList()
        muted = true
        if (phase == Phase.DONE) return emptyList()
        if (state is LiveCoachState.On) state = LiveCoachState.On(muted = true)
        if (phase != Phase.ACTIVE) return emptyList()
        // The service hears it at once, so a cue in the making is dropped.
        nextSnapshotAt = nowMillis
        return snapshotIfDue(nowMillis)
    }

    /** The ride is abandoned (the app is closing it): ends the session if there is one. */
    public fun close(reason: LiveSessionEndReason = LiveSessionEndReason.STOPPED): List<LiveEffect> =
        if (phase ==
            Phase.DONE
        ) {
            emptyList()
        } else {
            finish(reason)
        }

    // ---- Steps ---------------------------------------------------------------------------------

    private fun start(nowMillis: Long): List<LiveEffect> {
        phase = Phase.STARTING
        lastStartAt = nowMillis
        return listOf(
            LiveEffect.StartSession(
                LiveSessionStart(
                    rideId = plan.rideId,
                    workoutId = plan.workoutId,
                    structureHash = plan.structureHash,
                    variantRidden = plan.variantRidden,
                    clock = wallClockMillis().coerceAtLeast(0),
                ),
            ),
        )
    }

    private fun snapshotIfDue(nowMillis: Long): List<LiveEffect> {
        val id = sessionId
        if (phase != Phase.ACTIVE || id == null || !rideLive || nowMillis < nextSnapshotAt) return emptyList()
        if (snapshotInFlight) {
            // A slow network: this one is skipped, the next is as soon as the answer is in or the interval has passed.
            return emptyList()
        }
        val snapshot = builder.build(seq, wallClockMillis().coerceAtLeast(0), muted)
        nextSnapshotAt = nowMillis + SNAPSHOT_INTERVAL_MILLIS
        if (snapshot == null) return emptyList()
        seq++
        snapshotInFlight = true
        return listOf(LiveEffect.SendSnapshot(id, snapshot))
    }

    /** A session that is gone (a `404`) or going (`shutdown`): a new one, no sooner than [RESTART_INTERVAL_MILLIS] after the last start. */
    private fun goneOrShutdown(
        nowMillis: Long,
        session: String,
    ): List<LiveEffect> {
        phase = Phase.GAP
        sessionId = null
        snapshotInFlight = false
        state = LiveCoachState.Off
        restartAt = maxOf(nowMillis, lastStartAt + RESTART_INTERVAL_MILLIS)
        val effects = listOf<LiveEffect>(LiveEffect.CloseStream(session))
        return effects + if (rideLive && nowMillis >= restartAt) start(nowMillis) else emptyList()
    }

    private fun stopForTheRide(session: String): List<LiveEffect> {
        phase = Phase.DONE
        sessionId = null
        state = LiveCoachState.Off
        return listOf(LiveEffect.CloseStream(session))
    }

    private fun endReason(): LiveSessionEndReason = rideFinished ?: LiveSessionEndReason.STOPPED

    private fun finish(reason: LiveSessionEndReason): List<LiveEffect> {
        val id = if (phase == Phase.ACTIVE) sessionId else null
        rideFinished = reason
        phase = Phase.DONE
        sessionId = null
        rideLive = false
        if (state is LiveCoachState.On) state = LiveCoachState.Off
        return if (id == null) emptyList() else listOf(LiveEffect.CloseStream(id), LiveEffect.EndSession(id, reason))
    }

    public companion object {
        /** A snapshot every 5 seconds (the service's `snapshotEverySeconds`). */
        public const val SNAPSHOT_INTERVAL_MILLIS: Long = LiveCoachJson.SNAPSHOT_EVERY_SECONDS * 1_000L

        /** A new session (after a failed start, a `404` or a shutdown) no sooner than this after the last start. */
        public const val RESTART_INTERVAL_MILLIS: Long = 30_000

        /** "Live coach unavailable" is on the overlay for this long, once. */
        public const val UNAVAILABLE_SHOWN_MILLIS: Long = 8_000
    }
}
