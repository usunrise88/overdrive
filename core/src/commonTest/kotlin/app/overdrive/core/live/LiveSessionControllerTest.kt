package app.overdrive.core.live

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session controller (spec §12.1, docs/adr/0039) driven by a table of events in virtual time. */
class LiveSessionControllerTest {
    private val plan = LivePlan("ride-1795000000000", "icu-6001", "833a9d4d7f4ebf35", variantRidden = false)

    /** The controller with the time the test is at (milliseconds from the ride's start), and what it asked for, in order. */
    private class Rig(
        plan: LivePlan,
    ) {
        var now = 0L
        val controller = LiveSessionController(plan, { WALL + now })
        val effects = mutableListOf<LiveEffect>()
        private var second = 0

        fun at(millis: Long): Rig {
            now = millis
            return this
        }

        /** The ride has run to ride second [s] (the clock with the pauses in it), in the given state. */
        fun ride(
            s: Int = second + 1,
            state: RideState = RideState.RIDING,
            power: Int? = 238,
            muted: Boolean = false,
            step: Int = 1,
            elapsed: Int = 30,
        ): List<LiveEffect> {
            second = s
            return take(
                controller.onRide(
                    now,
                    LiveRides.ride(s, step = step, elapsedInStep = elapsed, state = state, power = power),
                ),
            )
        }

        fun time() = take(controller.onTime(now))

        fun started(id: String = "ls-1") = take(controller.onStartAnswer(now, LiveStartAnswer.Started(id)))

        fun take(list: List<LiveEffect>): List<LiveEffect> {
            effects += list
            return list
        }

        inline fun <reified T : LiveEffect> all(): List<T> = effects.filterIsInstance<T>()

        companion object {
            const val WALL = 1_795_000_000_000L
        }
    }

    // ---- Starting ----------------------------------------------------------------------------------

    @Test
    fun `nothing is asked before the ride is live, and the start is asked once, when it is`() {
        val rig = Rig(plan)
        assertEquals(emptyList(), rig.ride(0, state = RideState.IDLE))
        assertEquals(emptyList(), rig.ride(0, state = RideState.READY))
        assertEquals(emptyList(), rig.time())
        val start = rig.at(1_000).ride(1)
        assertEquals(
            listOf<LiveEffect>(
                LiveEffect.StartSession(
                    LiveSessionStart(
                        "ride-1795000000000",
                        "icu-6001",
                        "833a9d4d7f4ebf35",
                        variantRidden = false,
                        clock = Rig.WALL + 1_000,
                    ),
                ),
            ),
            start,
        )
        // More snapshots of the ride while the answer is on its way ask for nothing more.
        assertEquals(emptyList(), rig.at(2_000).ride(2))
        assertEquals(emptyList(), rig.at(3_000).time())
        assertEquals(LiveCoachState.Off, rig.controller.state)
    }

    @Test
    fun `a ride that starts paused counts as live, and the easier version is said`() {
        val rig = Rig(plan.copy(variantRidden = true, structureHash = "69140a858e5fc8af"))
        val start = assertIs<LiveEffect.StartSession>(rig.ride(1, state = RideState.PAUSED).single())
        assertEquals(true, start.request.variantRidden)
        assertEquals("69140a858e5fc8af", start.request.structureHash)
    }

    @Test
    fun `a session is on once it answers, with the stream opened and the first snapshot at once`() {
        val rig = Rig(plan)
        rig.at(1_000).ride(1)
        val effects = rig.at(1_400).started("ls-9")
        assertEquals(LiveEffect.OpenStream("ls-9"), effects[0])
        val snapshot = assertIs<LiveEffect.SendSnapshot>(effects[1])
        assertEquals("ls-9", snapshot.sessionId)
        assertEquals(0, snapshot.snapshot.seq)
        assertEquals(Rig.WALL + 1_400, snapshot.snapshot.clock)
        assertEquals(LiveCoachState.On(muted = false), rig.controller.state)
        assertEquals("ls-9", rig.controller.activeSessionId)
    }

    // ---- When the coach is off -----------------------------------------------------------------------

    @Test
    fun `the coach off says unavailable once for every reason but no-live-coach, and stops`() {
        for (reason in LiveCoachOffReason.entries) {
            val rig = Rig(plan)
            rig.at(0).ride(1)
            assertEquals(
                emptyList(),
                rig.at(500).take(rig.controller.onStartAnswer(500, LiveStartAnswer.Off(reason))),
                reason.name,
            )
            val shown = reason != LiveCoachOffReason.NO_LIVE_COACH
            assertEquals(
                if (shown) LiveCoachState.Unavailable else LiveCoachState.Off,
                rig.controller.state,
                reason.name,
            )
            assertTrue(rig.controller.isDone, reason.name)
            // It is a notice, not a permanent element: gone after eight seconds, and nothing is asked again.
            assertEquals(if (shown) 8_500L else null, rig.controller.nextWakeMillis(), reason.name)
            assertEquals(emptyList(), rig.at(8_499).time())
            if (shown) assertEquals(LiveCoachState.Unavailable, rig.controller.state)
            rig.at(8_500).time()
            assertEquals(LiveCoachState.Off, rig.controller.state, reason.name)
            assertNull(rig.controller.nextWakeMillis())
            assertEquals(emptyList(), rig.at(3_600_000).ride(3_000))
            assertEquals(emptyList(), rig.time())
        }
    }

    @Test
    fun `an answer that will not change stops it without a word`() {
        val rig = Rig(plan)
        rig.ride(1)
        assertEquals(emptyList(), rig.take(rig.controller.onStartAnswer(10, LiveStartAnswer.Unsupported)))
        assertTrue(rig.controller.isDone)
        assertEquals(LiveCoachState.Off, rig.controller.state)
        assertEquals(emptyList(), rig.at(60_000).time())
    }

    // ---- Offline --------------------------------------------------------------------------------------

    @Test
    fun `no answer says nothing, and the start is tried again every thirty seconds while the ride lasts`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        assertEquals(emptyList(), rig.at(2_000).take(rig.controller.onStartAnswer(2_000, LiveStartAnswer.Failed)))
        assertEquals(LiveCoachState.Off, rig.controller.state)
        assertEquals(30_000L, rig.controller.nextWakeMillis(), "30 s after the start was asked, not after it failed")
        assertEquals(emptyList(), rig.at(29_999).time())
        val again = rig.at(30_000).time()
        assertIs<LiveEffect.StartSession>(again.single())
        // It failed again.
        rig.at(31_000).take(rig.controller.onStartAnswer(31_000, LiveStartAnswer.Failed))
        assertEquals(60_000L, rig.controller.nextWakeMillis())
        // The network is back: the session starts late and the coach is on.
        assertIs<LiveEffect.StartSession>(rig.at(60_000).time().single())
        val on = rig.at(61_000).started("ls-2")
        assertEquals(LiveEffect.OpenStream("ls-2"), on.first())
        assertEquals(LiveCoachState.On(false), rig.controller.state)
        assertEquals(3, rig.all<LiveEffect.StartSession>().size)
    }

    @Test
    fun `a failed start of a ride that has finished is not tried again`() {
        val rig = Rig(plan)
        rig.ride(1)
        rig.at(2_000).take(rig.controller.onStartAnswer(2_000, LiveStartAnswer.Failed))
        rig.at(10_000).ride(10, state = RideState.FINISHED)
        assertTrue(rig.controller.isDone)
        assertEquals(emptyList(), rig.at(40_000).time())
    }

    // ---- Snapshots ------------------------------------------------------------------------------------

    private fun Rig.snapshots(): List<LiveSnapshot> = all<LiveEffect.SendSnapshot>().map { it.snapshot }

    /** Runs a ride of one tick a second to [untilMillis] with the session on from the start, answering every snapshot. */
    private fun Rig.runWithSession(
        untilMillis: Long,
        answer: LiveSnapshotAnswer = LiveSnapshotAnswer.Accepted,
    ) {
        at(0).ride(1)
        at(0).started("ls-1")
        var t = 1_000L
        var s = 2
        while (t <= untilMillis) {
            at(t).ride(s++)
            time()
            if (all<LiveEffect.SendSnapshot>().size > answers) {
                answers = all<LiveEffect.SendSnapshot>().size
                take(controller.onSnapshotAnswer(t, "ls-1", answer))
            }
            t += 1_000
        }
    }

    private var answers = 0

    @Test
    fun `a snapshot goes out every five seconds, numbered from zero, the first at once`() {
        val rig = Rig(plan)
        rig.runWithSession(21_000)
        val snapshots = rig.snapshots()
        assertEquals(listOf(0, 1, 2, 3, 4), snapshots.map { it.seq })
        assertEquals(
            listOf(
                Rig.WALL,
                Rig.WALL + 5_000,
                Rig.WALL + 10_000,
                Rig.WALL + 15_000,
                Rig.WALL + 20_000,
            ),
            snapshots.map {
                it.clock
            },
        )
        assertEquals(LiveRides.steps.size, snapshots.first().stepCount)
        assertEquals(false, snapshots.first().muted)
    }

    @Test
    fun `the next wake-up is the next snapshot, and none while one is on its way`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started()
        assertEquals(1, rig.snapshots().size)
        assertNull(
            rig.controller.nextWakeMillis(),
            "the first one has not been answered: no wake-up that would spin on a time past",
        )
        rig.at(100).take(rig.controller.onSnapshotAnswer(100, "ls-1", LiveSnapshotAnswer.Accepted))
        assertEquals(5_000L, rig.controller.nextWakeMillis())
    }

    @Test
    fun `one snapshot is in flight at a time, so a slow network skips instead of piling up`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started()
        assertEquals(1, rig.snapshots().size)
        for (t in 1..20) {
            rig.at(t * 1_000L).ride(t + 1)
            rig.time()
        }
        assertEquals(1, rig.snapshots().size, "nothing went out while the first was unanswered")
        // The answer comes at 21 s: the next goes at once, with the next number.
        val next = rig.at(21_000).take(rig.controller.onSnapshotAnswer(21_000, "ls-1", LiveSnapshotAnswer.Failed))
        assertEquals(listOf(1), next.filterIsInstance<LiveEffect.SendSnapshot>().map { it.snapshot.seq })
    }

    @Test
    fun `a failed snapshot is the next one's problem`() {
        val rig = Rig(plan)
        rig.runWithSession(16_000, LiveSnapshotAnswer.Failed)
        assertEquals(listOf(0, 1, 2, 3), rig.snapshots().map { it.seq })
        assertEquals(LiveCoachState.On(false), rig.controller.state)
    }

    @Test
    fun `a paused ride goes on sending, and says so`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started()
        rig.at(100).take(rig.controller.onSnapshotAnswer(100, "ls-1", LiveSnapshotAnswer.Accepted))
        rig.at(4_000).ride(5, state = RideState.PAUSED)
        rig.at(5_000).ride(6, state = RideState.PAUSED)
        rig.at(5_000).time()
        val last = rig.snapshots().last()
        assertEquals(true, last.paused)
        assertEquals(6, last.elapsedSeconds, "the ride's clock with the pauses in it")
    }

    // ---- A session that is gone ----------------------------------------------------------------------

    @Test
    fun `a snapshot's 404 starts a new session, no sooner than thirty seconds after the last start`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        // The service forgot it at 12 s: the last start was at 0, so the next is at 30 s and not before.
        val gone = rig.at(12_000).take(rig.controller.onSnapshotAnswer(12_000, "ls-1", LiveSnapshotAnswer.SessionGone))
        assertEquals(listOf<LiveEffect>(LiveEffect.CloseStream("ls-1")), gone)
        assertEquals(LiveCoachState.Off, rig.controller.state, "no control while there is no session")
        assertEquals(30_000L, rig.controller.nextWakeMillis())
        assertEquals(emptyList(), rig.at(29_999).time())
        assertIs<LiveEffect.StartSession>(rig.at(30_000).time().single())
        rig.at(30_500).started("ls-2")
        assertEquals(LiveCoachState.On(false), rig.controller.state)
        // Numbering starts again in the new session.
        assertEquals(0, rig.snapshots().last().seq)
        assertEquals("ls-2", rig.all<LiveEffect.SendSnapshot>().last().sessionId)
    }

    @Test
    fun `a session gone long after the last start is replaced at once`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        val gone =
            rig
                .at(
                    100_000,
                ).take(rig.controller.onSnapshotAnswer(100_000, "ls-1", LiveSnapshotAnswer.SessionGone))
        assertEquals(LiveEffect.CloseStream("ls-1"), gone[0])
        assertIs<LiveEffect.StartSession>(gone[1])
    }

    @Test
    fun `a session that keeps being forgotten is asked for at most every thirty seconds`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-0")
        var id = 0
        var t = 1_000L
        while (t < 600_000) {
            // The service forgets each session as soon as it is told of it.
            val session = rig.controller.activeSessionId
            if (session != null) {
                rig.at(t).take(rig.controller.onSnapshotAnswer(t, session, LiveSnapshotAnswer.SessionGone))
            }
            rig.at(t).time()
            if (rig.effects.lastOrNull() is LiveEffect.StartSession) rig.at(t).started("ls-${++id}")
            t += 1_000
        }
        val starts = rig.all<LiveEffect.StartSession>().size
        assertTrue(starts in 18..21, "starts in ten minutes: $starts")
    }

    @Test
    fun `an answer of a session that is not the one on now is ignored`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        assertEquals(emptyList(), rig.take(rig.controller.onSnapshotAnswer(10, "ls-0", LiveSnapshotAnswer.SessionGone)))
        assertEquals(
            emptyList(),
            rig.take(rig.controller.onStreamEnd(10, "ls-0", CueStreamEnd.Ended(LiveStreamEndReason.ENDED))),
        )
        assertEquals("ls-1", rig.controller.activeSessionId)
        // An answer to a start that nobody asked for.
        assertEquals(emptyList(), rig.take(rig.controller.onStartAnswer(20, LiveStartAnswer.Started("ls-7"))))
    }

    // ---- The stream ends -----------------------------------------------------------------------------

    @Test
    fun `ended and superseded stop the coach for the ride`() {
        for (reason in listOf(LiveStreamEndReason.ENDED, LiveStreamEndReason.SUPERSEDED)) {
            val rig = Rig(plan)
            rig.at(0).ride(1)
            rig.at(0).started("ls-1")
            val effects = rig.at(50_000).take(rig.controller.onStreamEnd(50_000, "ls-1", CueStreamEnd.Ended(reason)))
            assertEquals(listOf<LiveEffect>(LiveEffect.CloseStream("ls-1")), effects, reason.name)
            assertTrue(rig.controller.isDone)
            assertEquals(LiveCoachState.Off, rig.controller.state)
            assertEquals(emptyList(), rig.at(200_000).time())
            // The ride's end then has nothing to end: the service already did.
            assertEquals(emptyList(), rig.at(300_000).ride(400, state = RideState.FINISHED))
        }
    }

    @Test
    fun `shutdown starts a new session, no sooner than thirty seconds after the last start`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        val effects =
            rig
                .at(
                    10_000,
                ).take(rig.controller.onStreamEnd(10_000, "ls-1", CueStreamEnd.Ended(LiveStreamEndReason.SHUTDOWN)))
        assertEquals(listOf<LiveEffect>(LiveEffect.CloseStream("ls-1")), effects)
        assertEquals(30_000L, rig.controller.nextWakeMillis())
        assertIs<LiveEffect.StartSession>(rig.at(30_000).time().single())
        val late = Rig(plan)
        late.at(0).ride(1)
        late.at(0).started("ls-1")
        val now =
            late
                .at(
                    90_000,
                ).take(late.controller.onStreamEnd(90_000, "ls-1", CueStreamEnd.Ended(LiveStreamEndReason.SHUTDOWN)))
        assertIs<LiveEffect.StartSession>(now[1])
    }

    @Test
    fun `a stream that lost its session is a 404`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        rig.at(5_000).take(rig.controller.onStreamEnd(5_000, "ls-1", CueStreamEnd.SessionGone))
        assertEquals(30_000L, rig.controller.nextWakeMillis())
    }

    @Test
    fun `a rejected token or nothing set up stops it for the ride`() {
        for (end in listOf(CueStreamEnd.TokenRejected, CueStreamEnd.NotSetUp)) {
            val rig = Rig(plan)
            rig.at(0).ride(1)
            rig.at(0).started("ls-1")
            rig.at(5_000).take(rig.controller.onStreamEnd(5_000, "ls-1", end))
            assertTrue(rig.controller.isDone, end.toString())
            assertEquals(emptyList(), rig.at(100_000).time())
        }
    }

    // ---- Mute ----------------------------------------------------------------------------------------

    @Test
    fun `mute is for the rest of the ride, and the next snapshot says so at once, and so does every one after`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        rig.at(100).take(rig.controller.onSnapshotAnswer(100, "ls-1", LiveSnapshotAnswer.Accepted))
        val effects = rig.at(2_000).take(rig.controller.mute(2_000))
        val snapshot = assertIs<LiveEffect.SendSnapshot>(effects.single()).snapshot
        assertEquals(true, snapshot.muted)
        assertEquals(1, snapshot.seq)
        assertTrue(rig.controller.muted)
        assertEquals(LiveCoachState.On(muted = true), rig.controller.state)
        // Again: nothing changes, nothing is sent, and it cannot be undone.
        assertEquals(emptyList(), rig.at(2_100).take(rig.controller.mute(2_100)))
        rig.at(2_200).take(rig.controller.onSnapshotAnswer(2_200, "ls-1", LiveSnapshotAnswer.Accepted))
        rig.at(7_100).ride(8)
        rig.at(7_100).time()
        assertTrue(rig.snapshots().drop(1).all { it.muted })
    }

    @Test
    fun `mute while a snapshot is on its way goes out with its answer`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        assertEquals(emptyList(), rig.at(500).take(rig.controller.mute(500)))
        val next = rig.at(900).take(rig.controller.onSnapshotAnswer(900, "ls-1", LiveSnapshotAnswer.Accepted))
        assertEquals(true, assertIs<LiveEffect.SendSnapshot>(next.single()).snapshot.muted)
    }

    @Test
    fun `mute before there is a session is kept, and the first snapshot carries it`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        assertEquals(emptyList(), rig.take(rig.controller.mute(100)))
        assertEquals(LiveCoachState.Off, rig.controller.state)
        val effects = rig.at(200).started("ls-1")
        assertEquals(true, assertIs<LiveEffect.SendSnapshot>(effects[1]).snapshot.muted)
        assertEquals(LiveCoachState.On(muted = true), rig.controller.state)
    }

    @Test
    fun `mute stays through a new session`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        rig.at(1_000).take(rig.controller.mute(1_000))
        rig.at(100_000).take(rig.controller.onSnapshotAnswer(100_000, "ls-1", LiveSnapshotAnswer.SessionGone))
        rig.at(100_500).started("ls-2")
        assertEquals(true, rig.snapshots().last().muted)
    }

    // ---- The end of the ride ---------------------------------------------------------------------------

    @Test
    fun `a workout that ran to its end ends the session as finished, and one the rider stopped as stopped`() {
        for ((remaining, reason) in listOf(0 to LiveSessionEndReason.FINISHED, 300 to LiveSessionEndReason.STOPPED)) {
            val rig = Rig(plan)
            rig.at(0).ride(1)
            rig.at(0).started("ls-1")
            val last = LiveRides.steps.lastIndex
            val duration = LiveRides.steps[last].durationSeconds
            val ride: RideSnapshot =
                LiveRides.ride(
                    second = 9_999,
                    step = last,
                    elapsedInStep = duration - remaining,
                    state = RideState.FINISHED,
                )
            val effects = rig.at(1_000_000).take(rig.controller.onRide(1_000_000, ride))
            assertEquals(
                listOf(LiveEffect.CloseStream("ls-1"), LiveEffect.EndSession("ls-1", reason)),
                effects,
                reason.name,
            )
            assertTrue(rig.controller.isDone)
            assertEquals(LiveCoachState.Off, rig.controller.state)
            // Once.
            assertEquals(emptyList(), rig.take(rig.controller.onRide(1_001_000, ride)))
        }
    }

    @Test
    fun `closing the controller ends the session, and a start answered after that is ended at once`() {
        val rig = Rig(plan)
        rig.at(0).ride(1)
        rig.at(0).started("ls-1")
        assertEquals(
            listOf(LiveEffect.CloseStream("ls-1"), LiveEffect.EndSession("ls-1", LiveSessionEndReason.ERROR)),
            rig.take(rig.controller.close(LiveSessionEndReason.ERROR)),
        )
        assertEquals(emptyList(), rig.take(rig.controller.close()))

        val early = Rig(plan)
        early.at(0).ride(1)
        early.take(early.controller.close())
        val answered = early.at(3_000).take(early.controller.onStartAnswer(3_000, LiveStartAnswer.Started("ls-5")))
        assertEquals(listOf<LiveEffect>(LiveEffect.EndSession("ls-5", LiveSessionEndReason.STOPPED)), answered)
        assertEquals(emptyList(), early.take(early.controller.onStartAnswer(3_100, LiveStartAnswer.Failed)))
    }

    @Test
    fun `a controller that never started has nothing to end`() {
        val rig = Rig(plan)
        assertEquals(emptyList(), rig.take(rig.controller.close()))
        assertEquals(emptyList(), rig.ride(1, state = RideState.FINISHED))
    }

    // ---- The answers ---------------------------------------------------------------------------------

    private fun answered(
        status: Int = 201,
        body: String = "",
    ): Outcome<HttpAnswer> = Outcome.Ok(HttpAnswer(status, body), body)

    private fun refused(status: Int): Outcome<HttpAnswer> =
        Outcome.Failed(ServiceProblem.of(HttpAnswer(status, """{"error":{"code":"not_found","message":"x"}}""")))

    private fun unreachable(failure: TransportFailure): Outcome<HttpAnswer> =
        Outcome.Failed(ServiceProblem.of(TransportException(failure)))

    @Test
    fun `what the service says to a start is read into an answer, and what is not the contract's is not a session`() {
        val on =
            """{"sessionId":"ls-3f9a2c41d0b84e6a","coach":{"on":true,"reason":null},""" +
                """"serverTime":1795000000456,"snapshotEverySeconds":5}"""
        assertEquals(LiveStartAnswer.Started("ls-3f9a2c41d0b84e6a"), LiveStartAnswer.of(answered(201, on)))
        val off =
            """{"sessionId":null,"coach":{"on":false,"reason":"no-tts"},""" +
                """"serverTime":1795000000456,"snapshotEverySeconds":5}"""
        assertEquals(LiveStartAnswer.Off(LiveCoachOffReason.NO_TTS), LiveStartAnswer.of(answered(201, off)))
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(answered(201, off.replace("no-tts", "no-gpu"))))
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(answered(201, "<html>")))
        val contradiction = on.replace("\"on\":true", "\"on\":false")
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(answered(201, contradiction)))
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(Outcome.NotSetUp))
    }

    @Test
    fun `what keeps a start from being answered is tried again only when asking again can help`() {
        for (failure in listOf(
            TransportFailure.NoNetwork,
            TransportFailure.Refused,
            TransportFailure.Dropped,
            TransportFailure.TimedOut,
        )) {
            assertEquals(LiveStartAnswer.Failed, LiveStartAnswer.of(unreachable(failure)), failure.name)
        }
        assertEquals(LiveStartAnswer.Failed, LiveStartAnswer.of(refused(503)))
        assertEquals(LiveStartAnswer.Failed, LiveStartAnswer.of(refused(429)))
        // A service without the route, one that rejects the token and one that does not speak it will say the same next time.
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(refused(404)))
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(refused(401)))
        assertEquals(LiveStartAnswer.Unsupported, LiveStartAnswer.of(refused(400)))
    }

    @Test
    fun `what the service says to a snapshot`() {
        assertEquals(LiveSnapshotAnswer.Accepted, LiveSnapshotAnswer.of(answered(204)))
        assertEquals(LiveSnapshotAnswer.SessionGone, LiveSnapshotAnswer.of(refused(404)))
        assertEquals(LiveSnapshotAnswer.Failed, LiveSnapshotAnswer.of(refused(503)))
        assertEquals(LiveSnapshotAnswer.Failed, LiveSnapshotAnswer.of(refused(401)))
        assertEquals(LiveSnapshotAnswer.Failed, LiveSnapshotAnswer.of(unreachable(TransportFailure.NoNetwork)))
        assertEquals(LiveSnapshotAnswer.Failed, LiveSnapshotAnswer.of(Outcome.NotSetUp))
    }
}
