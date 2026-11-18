package app.overdrive.ride.handlebar

import app.overdrive.core.click.ButtonChange
import app.overdrive.core.control.MediaKeys
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A session that records the commands it was given and shows whatever snapshot a test sets. */
internal class RecordingRideSession(
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

/** Media keys that record what they were asked. */
internal class RecordingMediaKeys : MediaKeys {
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

internal fun snapshotOf(
    state: RideState = RideState.RIDING,
    mode: TrainerMode = TrainerMode.SIMULATION,
    pause: PauseReason? = null,
) = RideSnapshot(state = state, ftp = 200, mode = mode, pauseReason = pause)

/** The change that puts [mask] down on top of [before]. */
internal fun pressing(
    mask: Int,
    before: Int = 0,
) = ButtonChange(pressed = mask, released = 0, held = before or mask, timestampNanos = 0L)

/** The change that lifts [mask] from [before]. */
internal fun releasing(
    mask: Int,
    before: Int = mask,
) = ButtonChange(pressed = 0, released = mask, held = before and mask.inv(), timestampNanos = 0L)
