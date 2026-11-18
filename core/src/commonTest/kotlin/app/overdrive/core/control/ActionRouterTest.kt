package app.overdrive.core.control

import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** What each action does (spec §4.7), with the rules of the app's `RideActions` restated and tested in core. */
class ActionRouterTest {
    /** A session that records the commands it was given and shows whatever snapshot the test sets. */
    class RecordingSession(
        snapshot: RideSnapshot,
    ) : RideSession {
        val calls = mutableListOf<String>()
        private val state = MutableStateFlow(snapshot)
        override val snapshots: StateFlow<RideSnapshot> = state

        fun show(snapshot: RideSnapshot) {
            state.value = snapshot
        }

        override fun start() {
            calls += "start"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun finish() {
            calls += "finish"
        }

        override fun nextInterval() {
            calls += "nextInterval"
        }

        override fun previousInterval() {
            calls += "previousInterval"
        }

        override fun gearUp() {
            calls += "gearUp"
        }

        override fun gearDown() {
            calls += "gearDown"
        }

        override fun intensityUp() {
            calls += "intensityUp"
        }

        override fun intensityDown() {
            calls += "intensityDown"
        }
    }

    class RecordingMedia : MediaKeys {
        val calls = mutableListOf<String>()

        override fun playPause() {
            calls += "playPause"
        }

        override fun volumeUp() {
            calls += "volumeUp"
        }

        override fun volumeDown() {
            calls += "volumeDown"
        }
    }

    private fun snapshot(
        state: RideState = RideState.RIDING,
        mode: TrainerMode = TrainerMode.SIMULATION,
        pause: PauseReason? = null,
    ) = RideSnapshot(state = state, ftp = 200, mode = mode, pauseReason = pause)

    @Test
    fun `all ten actions do what the spec says in a simulation step`() {
        val session = RecordingSession(snapshot())
        val media = RecordingMedia()
        val router = ActionRouter(session, media)
        ClickAction.entries.forEach { router.dispatch(it) }
        assertEquals(
            listOf("gearUp", "gearDown", "nextInterval", "previousInterval", "pause"),
            session.calls,
            "gears, intervals and pause, in action order",
        )
        assertEquals(listOf("playPause", "volumeUp", "volumeUp", "volumeDown", "volumeDown"), media.calls)
    }

    @Test
    fun `in an ERG step the two gear actions change intensity instead`() {
        val session = RecordingSession(snapshot(mode = TrainerMode.ERG))
        val router = ActionRouter(session, RecordingMedia())
        router.dispatch(ClickAction.GEAR_UP)
        router.dispatch(ClickAction.GEAR_DOWN)
        assertEquals(listOf("intensityUp", "intensityDown"), session.calls)
        // The mode is read at every action: the next step is a simulation step.
        session.show(snapshot(mode = TrainerMode.SIMULATION))
        router.dispatch(ClickAction.GEAR_UP)
        router.dispatch(ClickAction.GEAR_DOWN)
        assertEquals(listOf("intensityUp", "intensityDown", "gearUp", "gearDown"), session.calls)
    }

    @Test
    fun `the pause toggle pauses while riding, resumes while paused and does nothing otherwise`() {
        val session = RecordingSession(snapshot())
        val router = ActionRouter(session, RecordingMedia())
        router.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        assertEquals(listOf("pause"), session.calls)
        for (paused in listOf(PauseReason.MANUAL, PauseReason.AUTO, PauseReason.TRAINER_DISCONNECTED)) {
            session.show(snapshot(RideState.PAUSED, pause = paused))
            router.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        }
        assertEquals(
            listOf("pause", "resume", "resume", "resume"),
            session.calls,
            "the session decides what a resume can lift",
        )
        for (state in listOf(RideState.IDLE, RideState.READY, RideState.FINISHED)) {
            session.show(snapshot(state))
            router.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        }
        assertEquals(4, session.calls.size, "no pause before the start or after the end")
    }

    @Test
    fun `the router matches the app's RideActions case by case`() {
        // The expectations of android InteractionTest.theClicksLongPressRoutesToPauseOrResumeAndGearButtonsFollowTheMode.
        val erg = RecordingSession(snapshot(mode = TrainerMode.ERG))
        val ergRouter = ActionRouter(erg, RecordingMedia())
        ergRouter.dispatch(ClickAction.GEAR_UP)
        ergRouter.dispatch(ClickAction.GEAR_DOWN)
        ergRouter.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        assertEquals(listOf("intensityUp", "intensityDown", "pause"), erg.calls)

        val free = RecordingSession(snapshot(mode = TrainerMode.SIMULATION))
        val freeRouter = ActionRouter(free, RecordingMedia())
        freeRouter.dispatch(ClickAction.GEAR_UP)
        freeRouter.dispatch(ClickAction.GEAR_DOWN)
        assertEquals(listOf("gearUp", "gearDown"), free.calls)

        val paused = RecordingSession(snapshot(RideState.PAUSED, TrainerMode.ERG, PauseReason.AUTO))
        ActionRouter(paused, RecordingMedia()).dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        assertEquals(listOf("resume"), paused.calls)
    }

    @Test
    fun `without a ride the media actions work and the ride actions do nothing`() {
        val media = RecordingMedia()
        val router = ActionRouter({ null }, media)
        ClickAction.entries.forEach { router.dispatch(it) }
        assertEquals(listOf("playPause", "volumeUp", "volumeUp", "volumeDown", "volumeDown"), media.calls)
    }

    @Test
    fun `the session is read at every action, because the ride app's session changes from ride to ride`() {
        val first = RecordingSession(snapshot())
        val second = RecordingSession(snapshot())
        var current: RideSession? = first
        val router = ActionRouter({ current }, RecordingMedia())
        router.dispatch(ClickAction.NEXT_INTERVAL)
        current = second
        router.dispatch(ClickAction.NEXT_INTERVAL)
        current = null
        router.dispatch(ClickAction.NEXT_INTERVAL)
        assertEquals(listOf("nextInterval"), first.calls)
        assertEquals(listOf("nextInterval"), second.calls)
    }

    @Test
    fun `the repeated volume steps are the same keys as the first step`() {
        val media = RecordingMedia()
        val router = ActionRouter({ null }, media)
        router.dispatch(ClickAction.VOLUME_UP)
        router.dispatch(ClickAction.VOLUME_UP_REPEAT)
        router.dispatch(ClickAction.VOLUME_DOWN)
        router.dispatch(ClickAction.VOLUME_DOWN_REPEAT)
        assertEquals(listOf("volumeUp", "volumeUp", "volumeDown", "volumeDown"), media.calls)
    }
}
