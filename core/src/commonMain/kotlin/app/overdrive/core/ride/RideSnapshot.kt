package app.overdrive.core.ride

/**
 * Everything the overlay and the ride screen show at one instant (spec §4.6, §4.7), as plain data.
 *
 * A snapshot is immutable and carries no UI concerns: no colours, no formatted strings. A [RideSession]
 * publishes a new one at 1 Hz while riding and immediately after any command that changes it.
 *
 * **Missing data.** [power], [heartRate] and [cadence] are null when the sensor has no signal
 * (spec §4.1, "missing values are recorded as gaps"), and before and after the ride. Render a null as a
 * gap, never as 0: a rider who is not pedalling reports 0.
 */
public data class RideSnapshot(
    val state: RideState,
    /** FTP in watts that the zone and the ERG targets are based on. */
    val ftp: Int,
    /** Ride time in seconds. It runs only while [RideState.RIDING], so pauses are not counted. */
    val elapsedSeconds: Int = 0,
    /** Watts, or null for no signal. */
    val power: Int? = null,
    /** Beats per minute, or null for no signal. */
    val heartRate: Int? = null,
    /** Revolutions per minute, or null for no signal. */
    val cadence: Int? = null,
    /** Virtual speed from the physics model (spec §4.3), never the trainer's own value. 0 when not riding. */
    val speedKmh: Double = 0.0,
    /** Virtual distance travelled in metres. */
    val distanceMeters: Double = 0.0,
    /** How the trainer is driven in the current step. [TrainerMode.SIMULATION] when there is no workout. */
    val mode: TrainerMode = TrainerMode.SIMULATION,
    /**
     * The selected virtual gear, **1-based**: 1 is the easiest and [gearCount] the hardest (spec §4.4).
     * Null while the gear does not apply, that is in an ERG step and after the ride. Show it as
     * "gear / [gearCount]"; [neutralGear] is the default, which equals the flat.
     */
    val gear: Int? = null,
    val neutralGear: Int = DEFAULT_NEUTRAL_GEAR,
    val gearCount: Int = DEFAULT_GEAR_COUNT,
    /**
     * Target power in watts for the current ERG step, already scaled by [intensityPercent]. Null in a
     * free-ride step, without a workout and after the ride. In a ramp it moves with the step.
     */
    val targetPower: Int? = null,
    /**
     * Workout intensity in percent, 50 to 150 in steps of 5 (spec §4.5). It scales ERG targets and has
     * no effect in simulation steps. 100 until the rider changes it.
     */
    val intensityPercent: Int = DEFAULT_INTENSITY_PERCENT,
    /** The zone of [power], null when there is no power signal. */
    val zone: PowerZone? = null,
    /** Progress through the workout, null for a free ride without a workout. */
    val workout: WorkoutProgress? = null,
    /**
     * Why the ride is paused, so a pause is never a mystery (spec §4.2). Null whenever [state] is not
     * [RideState.PAUSED]; a session that is paused for no reason it can name leaves it null too.
     */
    val pauseReason: PauseReason? = null,
    /**
     * Whole ride seconds (ticks of the 1 Hz clock) since the ride last resumed from a pause of any kind, for a guard that
     * must not judge the rider for a few seconds after a restart (docs/adr/0032): `secondsSinceResume < 5` means "just
     * resumed". 0 from the moment of the resume until the next tick. Null while paused, before the start, after the end,
     * and until the first resume: the start of a ride is not a resume. A resume part-way through a second makes the first
     * tick come early, so a guard of *n* seconds covers between *n* - 1 and *n* real seconds.
     */
    val secondsSinceResume: Int? = null,
    /**
     * The ERG stall guard is lowering the target (spec §11.1, docs/adr/0033): cadence fell below the floor, so the trainer is
     * held at a fraction of [targetPower] until the rider is pedalling again, and then the target climbs back for a few
     * seconds, during which this stays true. False in every other case. The overlay says that the guard is active.
     */
    val stallGuardActive: Boolean = false,
    /**
     * The power in watts the trainer is held at while [stallGuardActive], else null: [targetPower] scaled by the guard. It
     * is below [targetPower] while the guard holds and climbs to it as the guard lets go.
     */
    val stallGuardWatts: Int? = null,
    /**
     * What the overlay shows of a heart-rate step (spec §11.2): the target heart rate, the band, whether the heart rate is in
     * it, and whether the strap was lost. Null outside a heart-rate step. In such a step [targetPower] is the controller's
     * current power and [intensityPercent] does not apply.
     */
    val heartRateHold: HeartRateHoldState? = null,
) {
    init {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        require(elapsedSeconds >= 0) { "Elapsed time must not be negative, got $elapsedSeconds" }
        require(secondsSinceResume == null || secondsSinceResume >= 0) {
            "Seconds since the resume must not be negative, got $secondsSinceResume"
        }
        require(stallGuardActive || stallGuardWatts == null) { "A guard target needs an active guard" }
        require(gearCount >= 1) { "Gear count must be at least 1, got $gearCount" }
        require(neutralGear in 1..gearCount) { "Neutral gear $neutralGear is outside 1..$gearCount" }
        require(gear == null || gear in 1..gearCount) { "Gear $gear is outside 1..$gearCount" }
        require(
            intensityPercent in MIN_INTENSITY_PERCENT..MAX_INTENSITY_PERCENT &&
                intensityPercent % INTENSITY_STEP_PERCENT == 0,
        ) {
            "Intensity must be ${MIN_INTENSITY_PERCENT}..$MAX_INTENSITY_PERCENT in steps of $INTENSITY_STEP_PERCENT, got $intensityPercent"
        }
    }

    public companion object {
        public const val MIN_INTENSITY_PERCENT: Int = 50
        public const val MAX_INTENSITY_PERCENT: Int = 150
        public const val INTENSITY_STEP_PERCENT: Int = 5
        public const val DEFAULT_INTENSITY_PERCENT: Int = 100

        /** Spec §4.4: 24 gears by default. */
        public const val DEFAULT_GEAR_COUNT: Int = 24

        /** The neutral gear when none is configured: the middle one, with the extra gear on the hard side. */
        public const val DEFAULT_NEUTRAL_GEAR: Int = (DEFAULT_GEAR_COUNT + 1) / 2
    }
}

/**
 * Where the ride is in its workout, enough to draw the interval name, the countdown, the next step
 * and the miniature profile (spec §4.6).
 */
public data class WorkoutProgress(
    val workoutName: String,
    /** The workout with repeats expanded; draw the profile from this. The same list instance in every snapshot. */
    val steps: List<FlatStep>,
    /** Index into [steps] of the current step. After the last step ends it stays on the last step. */
    val stepIndex: Int,
    /** Seconds spent in the current step. */
    val stepElapsedSeconds: Int,
    /** Seconds left in the current step. */
    val stepRemainingSeconds: Int,
    /** Seconds of the workout done so far. Skipping an interval moves this to the start of the target step. */
    val workoutElapsedSeconds: Int,
    /** Seconds of the workout left. */
    val workoutRemainingSeconds: Int,
) {
    init {
        require(stepIndex in steps.indices) { "Step index $stepIndex is outside the ${steps.size} steps" }
    }

    /** The current step. */
    public val step: FlatStep get() = steps[stepIndex]

    /** Label of the current step. */
    public val stepLabel: String get() = step.label

    /** The next step, or null during the last one. */
    public val nextStep: FlatStep? get() = steps.getOrNull(stepIndex + 1)

    /** Label of the next step, or null during the last one. */
    public val nextStepLabel: String? get() = nextStep?.label

    /** Length of the whole workout in seconds. */
    public val workoutTotalSeconds: Int get() = workoutElapsedSeconds + workoutRemainingSeconds
}
