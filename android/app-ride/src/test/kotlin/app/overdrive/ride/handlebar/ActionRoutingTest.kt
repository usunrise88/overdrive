package app.overdrive.ride.handlebar

import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ClickAction
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.host.NoMedia
import app.overdrive.ride.host.RideActions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **One routing rule** (spec §4.7, ADR 0012): the ride screen's buttons (`RideActions`) and the Click (core's
 * `ActionRouter`) must do the same to the ride in every state and in both kinds of step. The rule is restated here as a
 * table, and every combination is run through all three ways in: the screen's actions with a snapshot of their own, the
 * screen's actions reading the session's snapshot, and the router the container gives the Click.
 */
class ActionRoutingTest {
    /** The five actions that touch the ride, how the screen calls each, and what the rule says each does. */
    private class RideCase(
        val action: ClickAction,
        val onScreen: (RideActions) -> Unit,
        val expected: (RideSnapshot) -> List<String>,
    )

    private val rideCases =
        listOf(
            RideCase(
                ClickAction.GEAR_UP,
                { it.levelUp() },
                { listOf(if (it.mode == TrainerMode.ERG) "intensityUp" else "gearUp") },
            ),
            RideCase(
                ClickAction.GEAR_DOWN,
                { it.levelDown() },
                { listOf(if (it.mode == TrainerMode.ERG) "intensityDown" else "gearDown") },
            ),
            RideCase(ClickAction.NEXT_INTERVAL, { it.nextInterval() }, { listOf("nextInterval") }),
            RideCase(ClickAction.PREVIOUS_INTERVAL, { it.previousInterval() }, { listOf("previousInterval") }),
            RideCase(
                ClickAction.TOGGLE_RIDE_PAUSE,
                { it.togglePause() },
                {
                    when (it.state) {
                        RideState.RIDING -> listOf("pause")
                        RideState.PAUSED -> listOf("resume")
                        else -> emptyList()
                    }
                },
            ),
        )

    private fun snapshots() =
        buildList {
            for (state in RideState.entries) {
                for (mode in TrainerMode.entries) {
                    add(snapshotOf(state, mode))
                    if (state == RideState.PAUSED) {
                        for (reason in PauseReason.entries) add(snapshotOf(state, mode, reason))
                    }
                }
            }
        }

    @Test
    fun everyRideActionDoesTheSameFromTheScreenAndFromTheClickInEveryStateAndMode() {
        var combinations = 0
        for (snapshot in snapshots()) {
            for (case in rideCases) {
                val expected = case.expected(snapshot)

                val own = RecordingRideSession(snapshot)
                case.onScreen(RideActions({ own }, { snapshot }))

                val reading = RecordingRideSession(snapshot)
                case.onScreen(RideActions({ reading }))

                val click = RecordingRideSession(snapshot)
                ActionRouter({ click }, RecordingMediaKeys()).dispatch(case.action)

                val exposed = RecordingRideSession(snapshot)
                RideActions({ exposed }).router.dispatch(case.action)

                val where = "${case.action} in ${snapshot.state}/${snapshot.mode}/${snapshot.pauseReason}"
                assertEquals("screen, own snapshot: $where", expected, own.calls)
                assertEquals("screen, session's snapshot: $where", expected, reading.calls)
                assertEquals("Click: $where", expected, click.calls)
                assertEquals("the screen's router: $where", expected, exposed.calls)
                combinations++
            }
        }
        // Five actions, every state in both kinds of step, every pause reason on top: it cannot shrink unnoticed.
        val perAction =
            RideState.entries.size * TrainerMode.entries.size + TrainerMode.entries.size * PauseReason.entries.size
        assertEquals(5 * perAction, combinations)
    }

    @Test
    fun theModeIsReadAtEveryActionSoAStepChangeChangesTheRouting() {
        val session = RecordingRideSession(snapshotOf(mode = TrainerMode.SIMULATION))
        val router = ActionRouter({ session }, RecordingMediaKeys())
        router.dispatch(ClickAction.GEAR_UP)
        session.show(snapshotOf(mode = TrainerMode.ERG))
        router.dispatch(ClickAction.GEAR_UP)
        router.dispatch(ClickAction.GEAR_DOWN)
        session.show(snapshotOf(mode = TrainerMode.SIMULATION))
        router.dispatch(ClickAction.GEAR_DOWN)
        assertEquals(listOf("gearUp", "intensityUp", "intensityDown", "gearDown"), session.calls)
    }

    @Test
    fun theSessionIsReadAtEveryActionSoTheNextRideIsTheOneThatIsDriven() {
        val first = RecordingRideSession(snapshotOf())
        val second = RecordingRideSession(snapshotOf())
        var current = first
        val router = ActionRouter({ current }, RecordingMediaKeys())
        router.dispatch(ClickAction.GEAR_UP)
        current = second
        router.dispatch(ClickAction.GEAR_UP)
        assertEquals(listOf("gearUp"), first.calls)
        assertEquals(listOf("gearUp"), second.calls)
    }

    @Test
    fun theMediaActionsReachTheMediaKeysWithOrWithoutARide() {
        val withRide = RecordingMediaKeys()
        val session = RecordingRideSession(snapshotOf())
        val router = RideActions({ session }, null, withRide).router
        val without = RecordingMediaKeys()
        val idle = RideActions({ null }, null, without).router
        for (target in listOf(router, idle)) {
            target.dispatch(ClickAction.PLAY_PAUSE_MEDIA)
            target.dispatch(ClickAction.VOLUME_UP)
            target.dispatch(ClickAction.VOLUME_UP_REPEAT)
            target.dispatch(ClickAction.VOLUME_DOWN)
            target.dispatch(ClickAction.VOLUME_DOWN_REPEAT)
        }
        val expected = listOf("playPause", "volumeUp", "volumeUp", "volumeDown", "volumeDown")
        assertEquals(expected, withRide.calls)
        assertEquals(expected, without.calls)
        assertEquals("a media key is no ride command", emptyList<String>(), session.calls)
    }

    @Test
    fun theRideActionsDoNothingWithoutARideAndNeverThrow() {
        val actions = RideActions({ null }, null, NoMedia)
        for (case in rideCases) case.onScreen(actions)
        for (action in ClickAction.entries) actions.router.dispatch(action)
    }
}
