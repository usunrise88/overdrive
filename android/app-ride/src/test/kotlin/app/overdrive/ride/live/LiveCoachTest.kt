package app.overdrive.ride.live

import app.overdrive.core.live.CuePlayResult
import app.overdrive.core.live.CuePlayer
import app.overdrive.core.live.LiveAudioFormat
import app.overdrive.core.live.LiveCoachClient
import app.overdrive.core.live.LiveCoachJson
import app.overdrive.core.live.LiveCoachState
import app.overdrive.core.live.LiveCue
import app.overdrive.core.live.LiveCueAudio
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.WorkoutProgress
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.MustSay
import app.overdrive.core.workout.MustSayMoment
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.data.sync.UrlConnectionTransport
import app.overdrive.ride.host.RideChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.encoding.Base64

/**
 * The live coach's driver on the tablet (docs/adr/0039), end to end on real sockets against a local service that writes `text/event-stream`, with a fake speech player and
 * **virtual time** for everything the controller waits for: a snapshot every 5 s, 30 s between starts, 8 s of "unavailable". The network is real, so the test waits a few
 * milliseconds at a time for the service to have heard what it should; no audio is played and every number and line is invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCoachTest {
    private val service = LiveService()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private var enabled = true

    private class Player : CuePlayer {
        val played = CopyOnWriteArrayList<Int>()
        var result: () -> CuePlayResult = { CuePlayResult.PLAYED }
        var throwing = false

        override suspend fun play(
            audio: ByteArray,
            format: LiveAudioFormat,
            onStarted: () -> Unit,
        ): CuePlayResult {
            played += audio[0].toInt()
            if (throwing) error("the speaker broke")
            onStarted()
            delay(2_000)
            return result()
        }
    }

    private val player = Player()

    private fun client(token: String = service.token): LiveCoachClient {
        val transport = UrlConnectionTransport()
        return LiveCoachClient(
            ServiceClient({ SyncConfig(service.baseUrl, token) }, transport, transport, streamer = transport),
        )
    }

    private val coach =
        LiveCoach(
            scope = scope,
            client = client(),
            player = player,
            enabled = { enabled },
            nowMillis = { scheduler.currentTime },
            wallMillis = { WALL + scheduler.currentTime },
        )

    @After
    fun cleanUp() {
        coach.close()
        scope.cancel()
        service.close()
    }

    // ---- The ride ---------------------------------------------------------------------------------

    private val workout =
        Workout(
            schema = 1,
            id = "icu-6001",
            name = "Threshold progression",
            steps =
                listOf(
                    SteadyStep(
                        duration = 600,
                        target = 0.95,
                        label = "Threshold",
                        mustSay = listOf(MustSay(MustSayMoment.Start, "Go.")),
                    ),
                    FreeStep(duration = 300, label = "Spin"),
                ),
            liveCoach = true,
            coachBrief = "Keep him smooth.",
        )
    private val flat =
        listOf(
            FlatStep("Threshold", 600, 0.95, 0.95, TrainerMode.ERG, startSeconds = 0),
            FlatStep("Spin", 300, null, null, TrainerMode.SIMULATION, startSeconds = 600),
        )
    private val planned = RideChoice.Planned(workout, structureHash = "833a9d4d7f4ebf35")

    private fun ride(
        second: Int,
        state: RideState = RideState.RIDING,
        remaining: Int = 570,
    ): RideSnapshot =
        RideSnapshot(
            state = state,
            ftp = 250,
            elapsedSeconds = second,
            rideSeconds = second,
            power = 238,
            heartRate = 150,
            cadence = 90,
            mode = TrainerMode.ERG,
            targetPower = 238,
            workout =
                WorkoutProgress(
                    workoutName = workout.name,
                    steps = flat,
                    stepIndex = 0,
                    stepElapsedSeconds = second.coerceAtLeast(1),
                    stepRemainingSeconds = 600 - second,
                    workoutElapsedSeconds = second,
                    workoutRemainingSeconds = remaining,
                ),
        )

    private fun feed(
        second: Int,
        state: RideState = RideState.RIDING,
        choice: RideChoice = planned,
        remaining: Int = 570,
    ) {
        coach.onSnapshot(1, choice, RIDE_ID, ride(second, state, remaining))
    }

    /** One tick of the ride a second, for [seconds] seconds from [from], in virtual time. */
    private fun rideFor(
        seconds: Int,
        from: Int,
    ): Int {
        for (i in 1..seconds) {
            scheduler.advanceTimeBy(1_000)
            feed(from + i)
            scheduler.runCurrent()
        }
        return from + seconds
    }

    /** Runs what is due and waits, a few milliseconds at a time, for the real network to have done [what]. */
    private fun settle(
        what: String,
        until: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            scheduler.runCurrent()
            if (until()) return
            Thread.sleep(5)
        }
        fail("Waited for $what")
    }

    /** Lets what the network has answered reach the driver: a few turns of the scheduler with a few milliseconds of real time between them. */
    private fun drain() {
        repeat(8) {
            scheduler.runCurrent()
            Thread.sleep(8)
        }
        scheduler.runCurrent()
    }

    /** The cue stream the driver opened, once it has. */
    private fun openedStream(): LiveService.Stream {
        settle("the stream") { service.streamsOpened.get() > 0 }
        return checkNotNull(service.awaitStream(1))
    }

    private fun startRide() {
        feed(0, RideState.READY)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(1_000)
        feed(1)
        settle("the start") { service.requestsTo("/v1/live/sessions").isNotEmpty() }
    }

    private fun cue(
        id: Int,
        text: String = "Cue $id",
        ttlMs: Int = 8_000,
    ): String {
        val body =
            LiveCoachJson.encodeCue(
                LiveCue(
                    id = id,
                    text = text,
                    trigger = "step-start",
                    audio = LiveCueAudio(LiveAudioFormat.MP3, Base64.encode(byteArrayOf(id.toByte(), 0, 0))),
                    createdAt = "2026-11-19T07:42:10.250Z",
                    expiresAt = "2026-11-19T07:42:18.250Z",
                    ttlMs = ttlMs,
                ),
            )
        return "id: $id\nevent: cue\ndata: $body\n\n"
    }

    // ---- A session -------------------------------------------------------------------------------

    @Test
    fun aRideWithTheCoachIsAWholeSessionFromTheStartToTheEnd() {
        startRide()
        val start = service.requestsTo("/v1/live/sessions").single()
        assertEquals("Bearer ${service.token}", start.authorization)
        val body = LiveCoachJson.decodeSessionStart(start.body)
        assertEquals(RIDE_ID, body.rideId)
        assertEquals("icu-6001", body.workoutId)
        assertEquals("833a9d4d7f4ebf35", body.structureHash)
        assertEquals(false, body.variantRidden)

        // The session is on: the stream is open and the first snapshot has gone, numbered 0.
        val stream = openedStream()
        settle("the first snapshot") { service.requestsTo("/snapshots").isNotEmpty() }
        drain()
        assertEquals(LiveCoachState.On(muted = false), coach.ui.value.state)
        val first = LiveCoachJson.decodeSnapshot(service.requestsTo("/snapshots").first().body)
        assertEquals(0, first.seq)
        assertEquals(2, first.stepCount)
        assertEquals(false, first.muted)
        assertEquals("/v1/live/sessions/ls-test-1/snapshots", service.requestsTo("/snapshots").first().path)

        // Five seconds on, the second snapshot, with the next number and the ride's clock.
        var second = rideFor(5, 1)
        settle("the second snapshot") { service.requestsTo("/snapshots").size >= 2 }
        val next = LiveCoachJson.decodeSnapshot(service.requestsTo("/snapshots")[1].body)
        assertEquals(1, next.seq)
        assertEquals(238, next.power)
        assertTrue("the ride clock of the moment it was taken: ${next.elapsedSeconds}", next.elapsedSeconds in 5..6)

        // A cue from the service is spoken, shown while it is spoken and for six seconds after, and reported when it was heard.
        stream.send(cue(1, "Smooth first two minutes."))
        settle("the plate") { coach.ui.value.plate == "Smooth first two minutes." }
        assertEquals(listOf(1), player.played.toList())
        scheduler.advanceTimeBy(2_000)
        settle("the report") { service.requestsTo("/cues/1/played").isNotEmpty() }
        assertTrue(LiveCoachJson.decodeCuePlayed(service.requestsTo("/cues/1/played").single().body).played)
        scheduler.advanceTimeBy(5_999)
        scheduler.runCurrent()
        assertEquals("Smooth first two minutes.", coach.ui.value.plate)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertNull(coach.ui.value.plate)

        // The rider mutes: the next snapshot says so at once, the state says so, and the cue that comes is not spoken.
        coach.mute()
        settle("a muted snapshot") { service.requestsTo("/snapshots").any { it.body.contains("\"muted\":true") } }
        assertEquals(LiveCoachState.On(muted = true), coach.ui.value.state)
        stream.send(cue(2, "Do not say this."))
        settle("the refusal reported") { service.requestsTo("/cues/2/played").isNotEmpty() }
        assertFalse(LiveCoachJson.decodeCuePlayed(service.requestsTo("/cues/2/played").single().body).played)
        assertEquals(listOf(1), player.played.toList())
        assertNull(coach.ui.value.plate)

        // The ride ends: the session ends, once, and the coach goes.
        second = rideFor(3, second)
        feed(second + 1, RideState.FINISHED, remaining = 100)
        settle("the end") { service.requestsTo("/end").isNotEmpty() }
        assertEquals(
            """{"reason":"stopped","clock":${WALL + scheduler.currentTime}}""",
            service.requestsTo("/end").single().body,
        )
        assertEquals(LiveCoachState.Off, coach.ui.value.state)
        assertEquals(LiveCoachUi.None, coach.ui.value)
        val snapshots = service.requestsTo("/snapshots").size
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals("nothing more is sent after the end", snapshots, service.requestsTo("/snapshots").size)
    }

    @Test
    fun aWorkoutFromAFileWhoseIdTheContractDoesNotTakeIsASessionWithNoWorkoutAndNeverAnError() {
        val odd = RideChoice.Planned(workout.copy(id = "my workout (copy 2)"), structureHash = "not a hash")
        feed(0, RideState.READY, odd)
        scheduler.advanceTimeBy(1_000)
        feed(1, choice = odd)
        settle("the start") { service.requestsTo("/v1/live/sessions").isNotEmpty() }
        val body = LiveCoachJson.decodeSessionStart(service.requestsTo("/v1/live/sessions").single().body)
        assertNull(body.workoutId)
        assertNull(body.structureHash)
        assertEquals(RIDE_ID, body.rideId)
    }

    @Test
    fun aWorkoutThatRanToItsEndEndsTheSessionAsFinished() {
        startRide()
        openedStream()
        settle("the first snapshot") { service.requestsTo("/snapshots").isNotEmpty() }
        drain()
        feed(900, RideState.FINISHED, remaining = 0)
        settle("the end") { service.requestsTo("/end").isNotEmpty() }
        assertTrue(service.requestsTo("/end").single().body.contains("\"finished\""))
    }

    // ---- The coach is not there ------------------------------------------------------------------

    @Test
    fun aServiceThatSaysWhyThereIsNoCoachGetsUnavailableOnceForEightSeconds() {
        service.startBody =
            """{"sessionId":null,"coach":{"on":false,"reason":"no-tts"},"serverTime":1795000000456,"snapshotEverySeconds":5}"""
        startRide()
        settle("the notice") { coach.ui.value.state == LiveCoachState.Unavailable }
        scheduler.advanceTimeBy(7_999)
        scheduler.runCurrent()
        assertEquals(LiveCoachState.Unavailable, coach.ui.value.state)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(LiveCoachUi.None, coach.ui.value)
        rideFor(60, 1)
        assertEquals("one start and nothing else, for the whole ride", 1, service.requests.size)
    }

    @Test
    fun aWorkoutThatDoesNotAskForTheCoachSaysNothingAtAll() {
        service.startBody =
            """{"sessionId":null,"coach":{"on":false,"reason":"no-live-coach"},"serverTime":1795000000456,"snapshotEverySeconds":5}"""
        startRide()
        drain()
        assertEquals(LiveCoachUi.None, coach.ui.value)
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals(LiveCoachUi.None, coach.ui.value)
        assertEquals(1, service.requests.size)
    }

    @Test
    fun nothingIsAskedWhenTheCoachIsNotWanted() {
        // The rider switched it off.
        enabled = false
        feed(0, RideState.READY)
        rideFor(30, 0)
        assertEquals(0, service.requests.size)
        assertEquals(LiveCoachUi.None, coach.ui.value)
    }

    @Test
    fun aWorkoutWithoutTheFlagAndAFreeRideAreNotAskedAboutEither() {
        val plain =
            RideChoice.Planned(
                workout.copy(liveCoach = null, coachBrief = null),
                structureHash = "833a9d4d7f4ebf35",
            )
        for (choice in listOf(plain, RideChoice.Free)) {
            val c =
                LiveCoach(scope, client(), player, { true }, { scheduler.currentTime }, { WALL })
            for (second in 0..20) {
                scheduler.advanceTimeBy(1_000)
                c.onSnapshot(7, choice, RIDE_ID, ride(second))
                scheduler.runCurrent()
            }
            assertEquals(LiveCoachUi.None, c.ui.value)
        }
        assertEquals(0, service.requests.size)
    }

    // ---- No connection -------------------------------------------------------------------------------

    @Test
    fun aStartThatGetsNoAnswerSaysNothingAndIsTriedAgainEveryThirtySecondsUntilTheCoachComes() {
        // Down for two starts, then up.
        service.startQueue += listOf(503, 503)
        startRide()
        settle("the failed start") { service.requestsTo("/v1/live/sessions").size == 1 }
        drain()
        assertEquals(LiveCoachUi.None, coach.ui.value)
        // Not before thirty seconds after the first try (at 1 s)...
        var second = rideFor(29, 1)
        drain()
        assertEquals(1, service.requestsTo("/v1/live/sessions").size)
        // ...then again, and thirty seconds after that.
        second = rideFor(2, second)
        settle("the second start") { service.requestsTo("/v1/live/sessions").size == 2 }
        drain()
        assertEquals(LiveCoachUi.None, coach.ui.value)
        rideFor(30, second)
        settle("the coach") { coach.ui.value.state == LiveCoachState.On(false) }
        assertEquals(3, service.requestsTo("/v1/live/sessions").size)
        openedStream()
    }

    @Test
    fun aSessionTheServiceForgotIsReplacedNoSoonerThanThirtySecondsAfterTheLastStart() {
        // The service forgets every session as soon as it hears of it.
        service.snapshotStatus = 404
        startRide()
        openedStream()
        settle("the first snapshot") { service.requestsTo("/snapshots").isNotEmpty() }
        drain()
        assertEquals("the control is gone while there is no session", LiveCoachState.Off, coach.ui.value.state)
        assertEquals(1, service.requestsTo("/v1/live/sessions").size)
        // The last start was at 1 s: nothing before 31 s.
        var second = rideFor(29, 1)
        drain()
        assertEquals(1, service.requestsTo("/v1/live/sessions").size)
        service.snapshotStatus = 204
        second = rideFor(2, second)
        settle("the new session") { service.requestsTo("/v1/live/sessions").size == 2 }
        settle("the new session's first snapshot") {
            service.requests.any {
                it.path ==
                    "/v1/live/sessions/ls-test-2/snapshots"
            }
        }
        assertEquals(
            0,
            LiveCoachJson
                .decodeSnapshot(
                    service.requests
                        .last {
                            it.path.endsWith("ls-test-2/snapshots")
                        }.body,
                ).seq,
        )
        assertTrue(second > 0)
    }

    // ---- Nothing reaches the riding screen ------------------------------------------------------------

    @Test
    fun aServiceThatAnswersGarbageOrAPlayerThatBreaksCostsTheCoachAndNothingElse() {
        service.startBody = "<html>not the service</html>"
        startRide()
        scheduler.runCurrent()
        rideFor(40, 1)
        assertEquals(LiveCoachUi.None, coach.ui.value)
        assertEquals("not asked again: the answer will not change", 1, service.requestsTo("/v1/live/sessions").size)
    }

    @Test
    fun aSpeakerThatBreaksDropsTheCueReportsItNotHeardAndTheNextOneIsStillSpoken() {
        startRide()
        val stream = openedStream()
        settle("the first snapshot") { service.requestsTo("/snapshots").isNotEmpty() }
        drain()
        player.throwing = true
        stream.send(cue(1))
        settle("the report") { service.requestsTo("/cues/1/played").isNotEmpty() }
        assertFalse(LiveCoachJson.decodeCuePlayed(service.requestsTo("/cues/1/played").single().body).played)
        assertNull(coach.ui.value.plate)
        player.throwing = false
        stream.send(cue(2, "Hold it steady."))
        settle("the plate") { coach.ui.value.plate == "Hold it steady." }
        assertEquals(LiveCoachState.On(false), coach.ui.value.state)
    }

    @Test
    fun aCueThatArrivesWhileTheCoachIsMutedBeforeASessionIsKept() {
        // Mute before the session exists: the first snapshot carries it.
        feed(0, RideState.READY)
        scheduler.advanceTimeBy(1_000)
        feed(1)
        coach.mute()
        settle("the start") { service.requestsTo("/v1/live/sessions").isNotEmpty() }
        settle(
            "the muted first snapshot",
        ) { service.requestsTo("/snapshots").any { it.body.contains("\"muted\":true") } }
        assertEquals(LiveCoachState.On(muted = true), coach.ui.value.state)
    }

    private companion object {
        const val WALL = 1_795_000_000_000L
        const val RIDE_ID = "ride-1795000000000"
    }
}
