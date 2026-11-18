package app.overdrive.core.ride

/** Lifecycle of one ride (spec §4.2). A session moves forward through these; [FINISHED] is terminal. */
public enum class RideState {
    /** No ride is possible yet: the devices are not connected. */
    IDLE,

    /** Devices are connected and the ride has not started. The workout, if any, is loaded at step 0. */
    READY,

    /** The ride clock runs and sensor values are live. */
    RIDING,

    /** The ride clock is stopped. Sensors keep reporting, but the rider is not pedalling, so power and cadence read 0. */
    PAUSED,

    /** The ride is over, because the workout ended or the rider finished it. Nothing runs any more. */
    FINISHED,
}
