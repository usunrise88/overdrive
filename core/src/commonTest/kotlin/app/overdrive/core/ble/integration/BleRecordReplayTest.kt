package app.overdrive.core.ble.integration

import app.overdrive.core.ble.recording.BleDirection
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.record.RideRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ride recorded through the decorator replays, through a fake peripheral that only plays the recording, into the same
 * ride record: the format loses nothing the engine can see, and the replay is deterministic (spec §7.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleRecordReplayTest {
    private fun live(scenario: SyntheticRide): RecordedRide {
        lateinit var recorded: RecordedRide
        runTest { recorded = recordLiveRide(scenario) }
        return recorded
    }

    private fun replay(
        scenario: SyntheticRide,
        recorded: RecordedRide,
    ): RideRecord {
        lateinit var record: RideRecord
        runTest { record = replayRide(scenario, recorded.trainerSession, recorded.strapSession) }
        return record
    }

    @Test
    fun `an ERG workout replays into the same ride record`() {
        val recorded = live(SyntheticRide.ERG_WORKOUT)
        assertEquals(90, recorded.record.samples.size)
        val replayed = replay(SyntheticRide.ERG_WORKOUT, recorded)
        assertEquals(recorded.record, replayed)
    }

    @Test
    fun `a ride with a 133 drop, a failed reconnect and a strap off the chest replays into the same record`() {
        val recorded = live(SyntheticRide.DROPOUTS)
        val live = recorded.record
        assertTrue(live.samples.any { it.paused }, "the scenario did interrupt the ride")
        assertTrue(live.samples.any { it.heartRate == null }, "and the strap")
        assertEquals(live, replay(SyntheticRide.DROPOUTS, recorded))
    }

    @Test
    fun `recording the same scenario twice gives byte-identical files`() {
        val first = live(SyntheticRide.DROPOUTS)
        val second = live(SyntheticRide.DROPOUTS)
        assertEquals(first.trainerSession, second.trainerSession)
        assertEquals(first.strapSession, second.strapSession)
    }

    @Test
    fun `the recorded files are well formed, short, and mark every connection`() {
        for (scenario in SyntheticRide.entries) {
            val recorded = live(scenario)
            for (text in listOf(recorded.trainerSession, recorded.strapSession)) {
                val parsed = BleSessionFormat.parseWithDiagnostics(text)
                assertEquals(1, parsed.version)
                assertEquals(0, parsed.skippedLines)
                assertTrue(text.length < 30_000, "a fixture is small: ${text.length} characters")
                assertEquals(text, BleSessionFormat.write(parsed.session), "the writer reproduces the file")
            }
            val trainer = BleSessionFormat.parse(recorded.trainerSession)
            assertEquals("D500", trainer.model)
            assertEquals("HR7", BleSessionFormat.parse(recorded.strapSession).model)
            val connects = trainer.events.count { it.direction == BleDirection.STATE && it.data == "connected" }
            assertEquals(if (scenario == SyntheticRide.DROPOUTS) 2 else 1, connects)
        }
    }
}
