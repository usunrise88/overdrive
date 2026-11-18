package app.overdrive.core.click.integration

import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickEvent
import app.overdrive.core.click.ClickState
import app.overdrive.core.click.UnlockSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Click session recorded through the decorator replays, through the real adapter, into what the live run showed (spec
 * §7.5): the same button changes at the same times, the same states, the same battery. The five scenarios are the ones of
 * `contracts/fixtures/ble/click-*.click.session.txt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickRecordReplayTest {
    private fun live(scenario: SyntheticClick): RecordedClick {
        lateinit var recorded: RecordedClick
        runTest { recorded = recordLiveClick(scenario) }
        return recorded
    }

    private fun replayed(
        scenario: SyntheticClick,
        session: String,
    ): ClickObservation {
        lateinit var observation: ClickObservation
        runTest { observation = replayClick(scenario, session) }
        return observation
    }

    @Test
    fun `every scenario replays into what the live run showed`() {
        for (scenario in SyntheticClick.entries) {
            val recorded = live(scenario)
            assertEquals(recorded.observation, replayed(scenario, recorded.session), scenario.stem)
        }
    }

    @Test
    fun `buttons, a chord, a long hold, the notice and an unknown frame`() {
        val recorded = live(SyntheticClick.BUTTONS)
        val observation = recorded.observation
        assertEquals(
            listOf(
                ClickBits.PLUS,
                0,
                ClickBits.Y,
                0,
                ClickBits.A or ClickBits.Z,
                0,
                ClickBits.A,
                0,
            ),
            observation.changes.map { it.held },
        )
        assertEquals(80, observation.battery)
        assertTrue(ClickEvent.DisconnectNoticeReceived in observation.events)
        assertTrue(ClickEvent.UnrecognisedFrame(0x3C, 2) in observation.events)
        assertEquals(ClickState.Active, observation.states.last())
        // The session is a text file of events in order: the handshake, then frames.
        val parsed = BleSessionFormat.parseWithDiagnostics(recorded.session)
        assertEquals(0, parsed.skippedLines)
        assertEquals("Click", parsed.session.model)
    }

    @Test
    fun `a drop with Plus held releases it with a synthetic change, the reconnect works`() {
        val observation = live(SyntheticClick.DROPOUT).observation
        assertEquals(listOf(ClickBits.PLUS, 0, ClickBits.B, 0), observation.changes.map { it.held })
        assertEquals(listOf(false, true, false, false), observation.changes.map { it.synthetic })
        assertTrue(observation.states.any { it is ClickState.NotFound && it.wasConnected })
        assertEquals(ClickState.Active, observation.states.last())
    }

    @Test
    fun `a locked controller never delivers and says why`() {
        val observation = live(SyntheticClick.LOCKED).observation
        assertEquals(emptyList(), observation.changes)
        assertFalse(ClickState.Active in observation.states)
        assertEquals(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN), observation.states.last())
    }

    @Test
    fun `unlocking through Zwift is picked up at the next attempt`() {
        val observation = live(SyntheticClick.UNLOCK).observation
        val hidden = observation.states.indexOf(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN))
        val active = observation.states.indexOf(ClickState.Active)
        assertTrue(hidden >= 0 && active > hidden, "hidden first, then active: ${observation.states}")
        assertEquals(listOf(ClickBits.PLUS, 0), observation.changes.map { it.held })
    }

    @Test
    fun `a controller that answers and then says nothing is reported after thirty seconds`() {
        val observation = live(SyntheticClick.SILENT).observation
        assertEquals(emptyList(), observation.changes)
        assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), observation.states.last())
        assertTrue(ClickState.Waiting in observation.states)
    }
}
