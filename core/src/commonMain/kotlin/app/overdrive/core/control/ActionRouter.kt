package app.overdrive.core.control

import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode

/**
 * The three media keys the handlebar sends (spec §4.7: volume is the system media volume, play and pause is a media key that
 * reaches whichever app is playing). The Android side implements it with `AudioManager`; a refusal there must be swallowed,
 * because a ride never fails on a media key.
 */
public interface MediaKeys {
    /** Play or pause whatever app is playing. */
    public fun playPause()

    /** One step of system volume up. */
    public fun volumeUp()

    /** One step of system volume down. */
    public fun volumeDown()
}

/**
 * What each [ClickAction] does to the ride and to the media (spec §4.7). The rules are those of the app's `RideActions`
 * (android `RideHost.kt`), restated here in `core` so that they are proved with the whole chain and not only with
 * screenshots:
 *
 * | Action | Does |
 * |---|---|
 * | [ClickAction.GEAR_UP], [ClickAction.GEAR_DOWN] | in an **ERG** step (`RideSnapshot.mode`), intensity up or down 5 points; otherwise one gear |
 * | [ClickAction.NEXT_INTERVAL], [ClickAction.PREVIOUS_INTERVAL] | the session's own skip |
 * | [ClickAction.TOGGLE_RIDE_PAUSE] | pause while `RIDING`, resume while `PAUSED`, nothing in any other state |
 * | [ClickAction.PLAY_PAUSE_MEDIA] | [MediaKeys.playPause] |
 * | [ClickAction.VOLUME_UP], [ClickAction.VOLUME_UP_REPEAT] | [MediaKeys.volumeUp] |
 * | [ClickAction.VOLUME_DOWN], [ClickAction.VOLUME_DOWN_REPEAT] | [MediaKeys.volumeDown] |
 *
 * The session's commands are fire and forget and a command that does not apply is a no-op (ADR 0003), so nothing here checks
 * more than the two things it must read: the step's mode and, for the pause toggle, the state. The media actions need no
 * ride at all and work on the Ready screen too.
 *
 * @param session the ride to drive, read at every action: the ride app's session changes from ride to ride. Null means no
 * ride, and the ride actions do nothing.
 */
public class ActionRouter(
    private val session: () -> RideSession?,
    private val media: MediaKeys,
) {
    /** A router for one [session]. */
    public constructor(session: RideSession, media: MediaKeys) : this({ session }, media)

    /** Carries out [action]. Never throws for a state it does not apply in. */
    public fun dispatch(action: ClickAction) {
        when (action) {
            ClickAction.GEAR_UP -> levelUp()
            ClickAction.GEAR_DOWN -> levelDown()
            ClickAction.NEXT_INTERVAL -> session()?.nextInterval()
            ClickAction.PREVIOUS_INTERVAL -> session()?.previousInterval()
            ClickAction.TOGGLE_RIDE_PAUSE -> togglePause()
            ClickAction.PLAY_PAUSE_MEDIA -> media.playPause()
            ClickAction.VOLUME_UP, ClickAction.VOLUME_UP_REPEAT -> media.volumeUp()
            ClickAction.VOLUME_DOWN, ClickAction.VOLUME_DOWN_REPEAT -> media.volumeDown()
        }
    }

    private fun levelUp() {
        val ride = session() ?: return
        if (ride.snapshots.value.mode == TrainerMode.ERG) ride.intensityUp() else ride.gearUp()
    }

    private fun levelDown() {
        val ride = session() ?: return
        if (ride.snapshots.value.mode == TrainerMode.ERG) ride.intensityDown() else ride.gearDown()
    }

    private fun togglePause() {
        val ride = session() ?: return
        when (ride.snapshots.value.state) {
            RideState.RIDING -> ride.pause()
            RideState.PAUSED -> ride.resume()
            else -> Unit
        }
    }
}
