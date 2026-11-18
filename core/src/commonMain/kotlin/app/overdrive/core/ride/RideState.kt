package app.overdrive.core.ride

/** Lifecycle of one ride (spec §4.2). A session moves forward through these; [FINISHED] is terminal. */
public enum class RideState {
    /** No ride is possible yet: the trainer is not connected. */
    IDLE,

    /** The trainer is connected and the ride has not started. The workout, if any, is loaded at step 0. */
    READY,

    /** The ride clock runs and sensor values are live. */
    RIDING,

    /**
     * The ride clock is stopped. Sensors keep reporting what they measure, which is 0 W and 0 rpm for a rider who
     * is not pedalling and null (no signal) for a trainer that is gone; see [RideSnapshot.pauseReason] for why.
     */
    PAUSED,

    /** The ride is over, because the workout ended or the rider finished it. Nothing runs any more. */
    FINISHED,
}

/** Why a ride is [RideState.PAUSED] (spec §4.2). Several can hold at once; the snapshot reports the first that does. */
public enum class PauseReason {
    /** The trainer's link is down. The ride resumes by itself when it is back, unless another reason still holds. */
    TRAINER_DISCONNECTED,

    /** The rider paused it. Only [RideSession.resume] ends this; pedalling does not. */
    MANUAL,

    /** The rider stopped pedalling for a few seconds. Pedalling again resumes the ride. */
    AUTO,
}
