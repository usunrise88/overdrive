package app.overdrive.core.ride

import app.overdrive.core.gear.GearSettings
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.workout.WorkoutPlayer

/**
 * Everything a [RideEngine] is configured with. These are the rider's settings (spec §4.3, §4.4, §4.5), plus
 * a few engine constants the spec leaves open; the defaults are the spec's where it has one.
 */
public data class RideSettings(
    /** FTP in watts, a manual setting in Phase 1 (spec §4.5). */
    val ftp: Int = DEFAULT_FTP,
    /** System mass, CdA and Crr for the virtual speed (spec §4.3). */
    val physics: PhysicsSettings = PhysicsSettings(),
    /** Gear count, grade per gear and the trainer's limit (spec §4.4). */
    val gears: GearSettings = GearSettings(),
    /** Pause by itself when the rider stops pedalling (spec §4.2). Turn off for a ride that must not pause. */
    val autoPause: Boolean = true,
    /** Zero-cadence seconds in a row before the ride auto-pauses (spec §4.2). */
    val autoPauseAfterSeconds: Int = DEFAULT_AUTO_PAUSE_SECONDS,
    /** Seconds over which the ERG target ramps back in after a pause (spec §4.5). 0 turns the ramp off. */
    val rampInSeconds: Int = WorkoutPlayer.DEFAULT_RAMP_IN_SECONDS,
    /**
     * The ERG target commanded while the ride is paused or has not started. With the flywheel stopped a trainer
     * holding the workout's target would be impossible to restart against; 0 W lets the rider spin up freely, and the
     * ramp-in then brings the target back.
     */
    val pausedTargetWatts: Int = 0,
    /** The most the trainer can hold (spec §2: 1500 W); ERG targets are limited to it. */
    val maxTargetWatts: Int = DEFAULT_MAX_TARGET_WATTS,
    /**
     * How long a trainer reading is reused when no new one arrives, in seconds; after that the trainer's power and
     * cadence are gaps. A trainer notifies about once a second.
     */
    val trainerTimeoutSeconds: Int = DEFAULT_TRAINER_TIMEOUT_SECONDS,
    /** The same for the heart-rate strap, which gets a little longer because chest straps drop beats now and then. */
    val heartRateTimeoutSeconds: Int = DEFAULT_HEART_RATE_TIMEOUT_SECONDS,
) {
    init {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        require(autoPauseAfterSeconds >= 1) { "Auto-pause needs at least 1 second, got $autoPauseAfterSeconds" }
        require(rampInSeconds >= 0) { "The ramp-in must not be negative, got $rampInSeconds" }
        require(pausedTargetWatts in 0..maxTargetWatts) {
            "The paused target must be within 0..$maxTargetWatts W, got $pausedTargetWatts"
        }
        require(maxTargetWatts >= 1) { "The target limit must be positive, got $maxTargetWatts" }
        require(
            trainerTimeoutSeconds >= 1,
        ) { "The trainer timeout must be at least 1 second, got $trainerTimeoutSeconds" }
        require(heartRateTimeoutSeconds >= 1) {
            "The heart-rate timeout must be at least 1 second, got $heartRateTimeoutSeconds"
        }
    }

    public companion object {
        public const val DEFAULT_FTP: Int = 200

        /** Spec §4.2. */
        public const val DEFAULT_AUTO_PAUSE_SECONDS: Int = 3

        /** Spec §2. */
        public const val DEFAULT_MAX_TARGET_WATTS: Int = 1500
        public const val DEFAULT_TRAINER_TIMEOUT_SECONDS: Int = 3
        public const val DEFAULT_HEART_RATE_TIMEOUT_SECONDS: Int = 5
    }
}
