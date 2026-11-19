package app.overdrive.core.ride

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateDevice
import app.overdrive.core.device.HeartRateReading
import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.device.TrainerReading
import app.overdrive.core.physics.FlatPhysicsSpeedModel
import app.overdrive.core.record.NoRideRecorder
import app.overdrive.core.record.RideEvent
import app.overdrive.core.record.RideEventType
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
import app.overdrive.core.route.RideRoutes
import app.overdrive.core.route.RouteCourse
import app.overdrive.core.route.RouteGrade
import app.overdrive.core.workout.PlayerTarget
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The real ride engine (spec §4.2): a [RideSession] on top of a [TrainerDevice], an optional [HeartRateDevice]
 * and the [WorkoutPlayer]. It is what the ride app runs; the same engine runs over the simulated devices in tests
 * (spec §7.5) and over BLE adapters on the tablet.
 *
 * **One loop, one thread of control.** Commands, device events and the 1 Hz tick all go through one queue and
 * are applied one at a time on a coroutine of [scope], so the engine needs no locks and, under a test dispatcher,
 * is fully deterministic. Commands to the trainer go out from a coroutine of their own, so a slow Bluetooth write
 * never delays the ride clock (spec §7.3). Give it a scope on a background dispatcher (`Dispatchers.Default`, never
 * the main one: the engine runs off the main thread) that you cancel when the app stops; the engine ends its own
 * coroutines when the ride is finished.
 *
 * **States** (spec §4.2, details in [RideSession]).
 * - `IDLE` until the trainer connects, then `READY`; `start` begins the ride. A strap is optional.
 * - `PAUSED` has three independent reasons ([PauseReason]); the ride runs only when none holds.
 *   **Auto-pause:** after [RideSettings.autoPauseAfterSeconds] consecutive seconds of zero cadence (counted at the 1 Hz
 *   tick). **Pedalling lifts it at once, on the trainer notification that shows it, not at the next tick**
 *   (docs/adr/0032): cadence above 0, or power of at least [RideSettings.autoResumePowerWatts] unless the trainer's
 *   flywheel is winding down, or the flywheel speeding up by [RideSettings.autoResumeFlywheelStepKmh]. The cadence field
 *   alone is not trusted, because a trainer without a crank sensor may show 0 while the rider is already pedalling.
 *   **Manual pause** overrides it: pedalling does not resume a manually paused ride, only `resume` does. **Trainer disconnect** pauses the ride at once and its reconnect resumes it, with nothing lost
 *   (acceptance criterion 4): the seconds in between are recorded as paused samples with no power or cadence.
 * - A **heart-rate dropout** never pauses or stops anything: the strap's link drop, a 0 bpm reading or silence for
 *   [RideSettings.heartRateTimeoutSeconds] seconds make the heart rate null for that second (a gap, spec §4.1).
 * - A workout ride finishes by itself after the workout's last second; a free ride ends with `finish`.
 *
 * **Clocks.** Every second of the ride, paused or not, is one [RideSample] (offsets 1, 2, 3, …), so the sample
 * count is the ride's duration. [RideSnapshot.elapsedSeconds] and the workout position count only the seconds
 * that were not paused.
 *
 * **What the trainer is told.**
 * - ERG step: the step's target at the current second times the intensity, rounded, limited to
 *   [RideSettings.maxTargetWatts]. After a pause it ramps in from the hold target of the pause in
 *   [RideSettings.rampInSeconds] seconds, and when the rider resumes by pedalling, the first step of the ramp is not
 *   delayed to the next tick (spec §4.5, docs/adr/0032).
 * - While paused or before the start: [RideSettings.pausedTargetWatts] in ERG steps, so the rider can spin up; during an
 *   auto-pause [RideSettings.autoPauseTargetWatts] instead (not above the step's target), so that the trainer has a little
 *   resistance with which to register the first stroke.
 * - Heart-rate step (spec §11.2): ERG at the power [HeartRateHold] chooses, within the step's limits, from the heart rate;
 *   the intensity does not apply, and the two gear buttons move the target heart rate by 2 bpm instead.
 * - **The stall guard** (spec §11.1, [StallGuard], docs/adr/0033) caps the target of any ERG step while it has hold: 70% of
 *   whatever the step, the ramp-in or the heart-rate controller asks for. It is not judged while paused, in the first seconds
 *   after a resume or before the rider has pedalled up to the floor, and at 0 rpm the auto-pause wins. Each time it takes hold
 *   an event is recorded ([RideRecorder.onEvent]).
 * - Free-ride step, or a ride without a workout: simulation parameters from the gear (spec §4.4) and the physics
 *   settings (spec §4.3), at 0% route grade.
 * - After the ride: simulation at the neutral gear, i.e. flat, so the trainer is not left holding an ERG target.
 * - After every (re)connect the engine starts over with `requestControl()` and the current command.
 *
 * **Readings.** The engine keeps the latest trainer and strap readings and samples them at each tick. A reading is
 * reused for up to the configured timeout when nothing new arrives (about a second between readings is normal); after
 * that it is a gap. The trainer's own speed is ignored (spec §4.3): speed and distance come from the [speedModel].
 *
 * @param workout the workout to play, or null for a free ride.
 * @param speedModel virtual speed from power, by default [FlatPhysicsSpeedModel] with the physics settings.
 * @param recorder receives the ride as it happens, see [RideRecorder].
 * @param tickInterval the length of one ride second; shorten it only to fast-forward in demos.
 * @param rideId the id of the ride record, by default derived from its start time.
 * @param clock wall-clock milliseconds since the Unix epoch, for the ride record's start and end times.
 * @param routes the routes the ride may follow (spec §11.4), only those the tablet has downloaded; none by default, the flat road.
 * @param variantRidden the workout is the planned workout's easier variant (spec §11.5): the record's header says so.
 */
public class RideEngine(
    scope: CoroutineScope,
    private val trainer: TrainerDevice,
    private val heartRate: HeartRateDevice? = null,
    private val workout: Workout? = null,
    private val settings: RideSettings = RideSettings(),
    private val speedModel: SpeedModel = FlatPhysicsSpeedModel(settings.physics),
    private val recorder: RideRecorder = NoRideRecorder,
    private val tickInterval: Duration = 1.seconds,
    private val rideId: String? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val routes: RideRoutes = RideRoutes.None,
    private val variantRidden: Boolean = false,
) : RideSession {
    private sealed interface Input {
        data object Start : Input

        data object Pause : Input

        data object Resume : Input

        data object Finish : Input

        data object Next : Input

        data object Previous : Input

        data object GearUp : Input

        data object GearDown : Input

        data object IntensityUp : Input

        data object IntensityDown : Input

        data object Tick : Input

        class TrainerLink(
            val state: ConnectionState,
        ) : Input

        class HeartRateLink(
            val state: ConnectionState,
        ) : Input

        class TrainerData(
            val reading: TrainerReading,
        ) : Input

        class HeartRateData(
            val reading: HeartRateReading,
        ) : Input
    }

    /** What the sender coroutine should make the trainer do; [last] is the final order of the ride. */
    private data class TrainerOrder(
        val command: TrainerCommand,
        val last: Boolean = false,
    )

    private val inputs = Channel<Input>(Channel.UNLIMITED)

    // Everything below is touched only by the coroutine that drains `inputs`, one input at a time.
    private val steps: List<FlatStep> = workout?.flatSteps().orEmpty()
    private val player: WorkoutPlayer? =
        if (steps.isEmpty()) null else WorkoutPlayer(steps, settings.ftp, settings.rampInSeconds)
    private val gears = settings.gears

    // The ERG stall guard (spec §11.1, docs/adr/0033) and the heart-rate step's controller (spec §11.2, docs/adr/0033).
    private val stallGuard = StallGuard(settings.stallGuard, zeroCadenceIsAStop = settings.autoPause)
    private var heartRateHold: HeartRateHold? = null
    private var holdStepIndex = -1
    private var holdStepElapsedSeconds = 0

    // The rider's adjustment of the target heart rate, kept for the rest of the ride like the intensity is.
    private var heartRateOffsetBpm = 0

    init {
        syncHeartRateStep()
    }

    private var started = false
    private var finished = false
    private var state = RideState.IDLE
    private var trainerConnected = false
    private var manualPause = false
    private var autoPaused = false
    private var zeroCadenceSeconds = 0
    private val flywheel = FlywheelWatch(settings.autoResumeFlywheelStepKmh)

    // The ERG target the trainer was last told while the ride was not running: where a ramp-in after a pause starts.
    private var pauseHoldWatts = settings.pausedTargetWatts
    private var secondsSinceResume: Int? = null

    private var gear = gears.neutralGear
    private var rideSeconds = 0
    private var movingSeconds = 0
    private var speedKmh = 0.0
    private var distanceMeters = 0.0

    // The route (spec §11.4): the grades by route id, the step whose distance along its route is counted, and that distance. It
    // starts again at 0 whenever the workout's step changes (a ride with no workout is one step), and counts only while riding.
    private val grades = HashMap<String, RouteGrade>()
    private var routeStepIndex = NO_ROUTE_STEP
    private var routeDistance = 0.0

    private val trainerReading = HeldReading<TrainerReading>(settings.trainerTimeoutSeconds)
    private val heartRateReading = HeldReading<HeartRateReading>(settings.heartRateTimeoutSeconds)
    private val pendingRrIntervals = mutableListOf<Int>()

    // The readings the snapshot shows: those of the last tick.
    private var power: Int? = null
    private var heartRateBpm: Int? = null
    private var cadence: Int? = null

    // The engine writes the order, the sender coroutine reads it; both sides are thread-safe flows and channels.
    private val order = MutableStateFlow(TrainerOrder(desiredCommand()))
    private val wakeSender = Channel<Unit>(Channel.CONFLATED)

    private val mutableSnapshots = MutableStateFlow(snapshot())

    override val snapshots: StateFlow<RideSnapshot> = mutableSnapshots.asStateFlow()

    init {
        scope.launch {
            // Forwarders only enqueue, so they run unconfined: an event emitted before a tick is queued before it.
            val forwarders =
                buildList {
                    add(
                        launch(Dispatchers.Unconfined) {
                            trainer.connection.collect {
                                inputs.trySend(Input.TrainerLink(it))
                                wakeSender.trySend(Unit)
                            }
                        },
                    )
                    add(
                        launch(
                            Dispatchers.Unconfined,
                        ) { trainer.data.collect { inputs.trySend(Input.TrainerData(it)) } },
                    )
                    heartRate?.let { strap ->
                        add(
                            launch(Dispatchers.Unconfined) {
                                strap.connection.collect { inputs.trySend(Input.HeartRateLink(it)) }
                            },
                        )
                        add(
                            launch(
                                Dispatchers.Unconfined,
                            ) { strap.data.collect { inputs.trySend(Input.HeartRateData(it)) } },
                        )
                    }
                }
            launch { runTrainerSender() }
            val ticker =
                launch(start = CoroutineStart.LAZY) {
                    while (true) {
                        delay(tickInterval)
                        inputs.trySend(Input.Tick)
                    }
                }
            try {
                for (input in inputs) {
                    apply(input)
                    mutableSnapshots.value = snapshot()
                    if (finished) break
                    if (isLive()) ticker.start()
                }
            } finally {
                ticker.cancel()
                forwarders.forEach { it.cancel() }
                inputs.close()
            }
        }
    }

    override fun start() {
        inputs.trySend(Input.Start)
    }

    override fun pause() {
        inputs.trySend(Input.Pause)
    }

    override fun resume() {
        inputs.trySend(Input.Resume)
    }

    override fun finish() {
        inputs.trySend(Input.Finish)
    }

    override fun nextInterval() {
        inputs.trySend(Input.Next)
    }

    override fun previousInterval() {
        inputs.trySend(Input.Previous)
    }

    override fun gearUp() {
        inputs.trySend(Input.GearUp)
    }

    override fun gearDown() {
        inputs.trySend(Input.GearDown)
    }

    override fun intensityUp() {
        inputs.trySend(Input.IntensityUp)
    }

    override fun intensityDown() {
        inputs.trySend(Input.IntensityDown)
    }

    private fun isLive() = state == RideState.RIDING || state == RideState.PAUSED

    private fun currentMode(): TrainerMode = player?.mode ?: TrainerMode.SIMULATION

    /** Gear and intensity can be adjusted once the trainer is connected, until the ride is over. */
    private fun isAdjustable() = state == RideState.READY || isLive()

    private fun apply(input: Input) {
        when (input) {
            Input.Start -> startRide()
            Input.Pause -> pauseRide()
            Input.Resume -> resumeRide()
            Input.Finish -> if (isLive()) endRide()
            Input.Next -> if (isLive()) player?.nextInterval()
            Input.Previous -> if (isLive()) player?.previousInterval()
            Input.GearUp -> shiftGear(+1)
            Input.GearDown -> shiftGear(-1)
            Input.IntensityUp -> if (isAdjustable()) adjustIntensity(+1)
            Input.IntensityDown -> if (isAdjustable()) adjustIntensity(-1)
            Input.Tick -> if (isLive()) tick()
            is Input.TrainerLink -> onTrainerLink(input.state == ConnectionState.CONNECTED)
            is Input.HeartRateLink -> onHeartRateLink(input.state == ConnectionState.CONNECTED)
            is Input.TrainerData -> if (trainerConnected) onTrainerData(input.reading)
            is Input.HeartRateData -> onHeartRateData(input.reading)
        }
        syncHeartRateStep()
        syncRoute()
        if (!finished) updateOrder()
    }

    /** The grade of the route behind the current step, or null on the flat road: an ERG step, a free step with no route or one the tablet does not have. */
    private fun activeGrade(): RouteGrade? {
        if (currentMode() != TrainerMode.SIMULATION) return null
        val route = routes.routeOf(steps, player?.stepIndex) ?: return null
        return grades.getOrPut(route.id) { RouteGrade(RouteCourse(route), routes.difficultyPercent, gears) }
    }

    /** The route's distance starts again at 0 whenever the step changes, so each step on a route begins at the start of it. */
    private fun syncRoute() {
        val index = player?.stepIndex ?: 0
        if (index != routeStepIndex) {
            routeStepIndex = index
            routeDistance = 0.0
        }
    }

    /** The route's own grade the trainer is told now: flat before the start and on the flat road, 0 once the route is over. */
    private fun routeGradePercent(): Double = if (!started) 0.0 else activeGrade()?.routePercentAt(routeDistance) ?: 0.0

    private fun routeProgress(): RouteProgress? {
        if (finished) return null
        val grade = activeGrade() ?: return null
        val length = grade.lengthMeters
        val done = grade.isFinished(routeDistance)
        return RouteProgress(
            routeId = grade.course.route.id,
            name = grade.course.route.name,
            distanceMeters = routeDistance.coerceIn(0.0, length),
            lengthMeters = length,
            gradePercent = if (done) 0.0 else grade.routePercentAt(routeDistance),
            finished = done,
        )
    }

    /**
     * The two gear buttons in an ERG step: the intensity up or down by 5 points, or in a heart-rate step (spec §11.2) the
     * target heart rate by [HeartRateHoldSettings.targetStepBpm] (2 bpm). The Click and the on-screen buttons both end here.
     */
    private fun adjustIntensity(direction: Int) {
        val active = player ?: return
        val hold = heartRateHold
        if (hold != null && active.step.heartRate != null) {
            if (hold.adjustTarget(direction)) heartRateOffsetBpm = hold.targetOffsetBpm
        } else if (direction > 0) {
            active.intensityUp()
        } else {
            active.intensityDown()
        }
    }

    /**
     * Keeps the heart-rate controller in step with the player: a controller for the step the workout is in when that is a
     * heart-rate step (a new one when the step changed or was restarted, so its start ramp runs again), none otherwise,
     * and the player told the power it asks for. Idempotent; it runs after every input.
     */
    private fun syncHeartRateStep() {
        val active = player ?: return
        val target = active.step.heartRate
        if (target == null) {
            heartRateHold = null
            holdStepIndex = -1
            return
        }
        val elapsed = active.stepElapsedSeconds
        var hold = heartRateHold
        if (hold == null || holdStepIndex != active.stepIndex || elapsed < holdStepElapsedSeconds) {
            hold = HeartRateHold(target, settings.heartRateHold, heartRateOffsetBpm)
            heartRateHold = hold
            holdStepIndex = active.stepIndex
        }
        holdStepElapsedSeconds = elapsed
        active.setHeartRateWatts(hold.watts)
    }

    private fun shiftGear(delta: Int) {
        if (isAdjustable() && currentMode() == TrainerMode.SIMULATION) gear = gears.clampGear(gear + delta)
    }

    private fun startRide() {
        if (state != RideState.READY) return
        val startMillis = clock()
        started = true
        // Whatever the devices were saying just before the start is the first reading of the ride.
        val latest = trainerReading.peek()
        power = latest?.power
        cadence = latest?.cadence
        heartRateBpm = heartRateReading.peek()?.bpm?.takeIf { it > 0 }
        pendingRrIntervals.clear()
        val route = routes.firstRoute(steps)
        recorder.onRideStarted(
            RideHeader(
                id = rideId ?: "ride-$startMillis",
                startEpochMillis = startMillis,
                workoutId = workout?.id,
                ftp = settings.ftp,
                devices = listOfNotNull(trainer.name, heartRate?.name),
                routeId = route?.id,
                routeName = route?.name,
                variantRidden = true.takeIf { variantRidden && workout != null },
            ),
        )
        refreshState()
    }

    private fun pauseRide() {
        if (state != RideState.RIDING) return
        manualPause = true
        refreshState()
    }

    private fun resumeRide() {
        if (state != RideState.PAUSED) return
        manualPause = false
        autoPaused = false
        refreshState()
    }

    private fun onTrainerLink(connected: Boolean) {
        if (connected == trainerConnected) return
        trainerConnected = connected
        trainerReading.clear()
        flywheel.reset()
        zeroCadenceSeconds = 0
        if (!connected && isLive()) {
            power = null
            cadence = null
            speedKmh = 0.0
        }
        refreshState()
    }

    /**
     * A trainer notification: it is the latest reading for the next tick, and, while the ride is auto-paused, it ends the
     * pause if it shows the rider pedalling. That is decided here, on arrival, not at the next tick: the ramp-in begins
     * and the trainer is told its new target within this one input (docs/adr/0032).
     */
    private fun onTrainerData(reading: TrainerReading) {
        trainerReading.offer(reading)
        flywheel.offer(reading.speedKmh)
        if (autoPaused && showsPedalling(reading)) {
            autoPaused = false
            refreshState()
            // The rider is turning the pedals right now: the first step of the ramp-in does not wait for the next second.
            if (state == RideState.RIDING) player?.stepRampIn()
        }
    }

    /**
     * Whether a reading shows the rider pedalling: a cadence above 0, a flywheel that speeds up, or power in a flywheel
     * that does not merely wind down. A missing field shows nothing.
     */
    private fun showsPedalling(reading: TrainerReading): Boolean {
        val cadenceNow = reading.cadence
        val powerNow = reading.power
        return (cadenceNow != null && cadenceNow > 0) ||
            flywheel.accelerated ||
            (
                settings.autoResumePowerWatts > 0 &&
                    powerNow != null &&
                    powerNow >= settings.autoResumePowerWatts &&
                    !flywheel.coasting
            )
    }

    private fun onHeartRateLink(connected: Boolean) {
        if (connected) return
        heartRateReading.clear()
        pendingRrIntervals.clear()
        if (isLive()) heartRateBpm = null
    }

    private fun onHeartRateData(reading: HeartRateReading) {
        heartRateReading.offer(reading)
        if (started && !finished) pendingRrIntervals += reading.rrIntervalsMs
    }

    /** Derives the state from the facts, and does what a change of state asks for. */
    private fun refreshState() {
        val before = state
        state =
            when {
                finished -> RideState.FINISHED
                !started -> if (trainerConnected) RideState.READY else RideState.IDLE
                manualPause || autoPaused || !trainerConnected -> RideState.PAUSED
                else -> RideState.RIDING
            }
        if (state != RideState.RIDING) {
            zeroCadenceSeconds = 0
            // Nothing is judged while the ride is not running; the guard starts again unarmed (docs/adr/0033).
            stallGuard.reset()
        }
        if (before == RideState.PAUSED && state == RideState.RIDING) {
            player?.beginRampIn(pauseHoldWatts)
            // The heart rate fell during the pause: what the controller had smoothed is stale.
            heartRateHold?.resume()
            secondsSinceResume = 0
        } else if (state != RideState.RIDING) {
            secondsSinceResume = null
        }
    }

    /** One second of the ride has passed (spec §4.2). */
    private fun tick() {
        rideSeconds++
        val wasRiding = state == RideState.RIDING

        val trainerNow = trainerReading.take()
        val strapNow = heartRateReading.take()
        val powerNow = trainerNow?.power
        val cadenceNow = trainerNow?.cadence
        val bpmNow = strapNow?.bpm?.takeIf { it > 0 }

        // The speed is the one the route's grade at the start of the second allows (spec §11.4); the flat road is grade 0.
        val grade = activeGrade()
        val gradePercent = grade?.routePercentAt(routeDistance) ?: 0.0
        speedKmh = if (wasRiding && powerNow != null) speedModel.speedKmh(powerNow.toDouble(), gradePercent) else 0.0
        if (wasRiding) {
            val travelled = speedKmh / KMH_PER_METER_PER_SECOND
            distanceMeters += travelled
            if (grade != null) routeDistance += travelled
        }

        // The sample describes the second that just ended: readings now, plan as it was during the second.
        val target = player?.target
        val sampleStepIndex = player?.stepIndex
        recorder.onSample(
            RideSample(
                timeOffsetSeconds = rideSeconds,
                power = powerNow,
                heartRate = bpmNow,
                cadence = cadenceNow,
                speedKmh = speedKmh,
                distanceMeters = distanceMeters,
                gear = if (currentMode() == TrainerMode.SIMULATION) gear else null,
                targetPower = (target as? PlayerTarget.Erg)?.nominalWatts,
                stepIndex = sampleStepIndex,
                paused = !wasRiding,
                // Only once the controller is in charge: the start ramp is not judged (spec §11.2).
                targetHeartRate = heartRateHold?.takeIf { !it.startRamp }?.targetBpm,
            ),
        )
        if (pendingRrIntervals.isNotEmpty()) {
            recorder.onRrIntervals(pendingRrIntervals.map { RrInterval(rideSeconds, it) })
            pendingRrIntervals.clear()
        }

        power = powerNow
        cadence = cadenceNow
        heartRateBpm = bpmNow

        if (wasRiding) {
            movingSeconds++
            secondsSinceResume = secondsSinceResume?.plus(1)
            // The controller belongs to the step the second was ridden in: it hears the heart rate before the step moves on.
            heartRateHold?.tick(bpmNow, frozen = stallGuard.active)
            player?.let {
                it.advance()
                if (it.isComplete) {
                    endRide()
                    return
                }
            }
            syncHeartRateStep()
            updateStallGuard(cadenceNow, sampleStepIndex)
        }
        updateAutoPause(cadenceNow, wasRiding)
        refreshState()
    }

    /**
     * The stall guard (spec §11.1) judges the second that just ended, if the next second is an ERG one: a free-ride step has no
     * target to hold, so there is nothing to limit. The tick it takes hold at is recorded as an event of the step it was
     * ridden in. Auto-pause is updated right after and wins at 0 rpm: the guard does not count a stop (docs/adr/0033).
     */
    private fun updateStallGuard(
        cadenceNow: Int?,
        sampleStepIndex: Int?,
    ) {
        if (!settings.stallGuard.enabled || currentMode() != TrainerMode.ERG) {
            stallGuard.reset()
        } else if (stallGuard.tick(cadenceNow, secondsSinceResume)) {
            recorder.onEvent(RideEvent(RideEventType.STALL_GUARD, rideSeconds, sampleStepIndex))
        }
    }

    /**
     * Spec §4.2: pause after 3 s of zero cadence. The pause ends in [onTrainerData], on the notification that shows
     * pedalling, not here.
     */
    private fun updateAutoPause(
        cadenceNow: Int?,
        wasRiding: Boolean,
    ) {
        if (!settings.autoPause) return
        if (wasRiding && cadenceNow == 0) {
            zeroCadenceSeconds++
            if (zeroCadenceSeconds >= settings.autoPauseAfterSeconds) autoPaused = true
        } else {
            zeroCadenceSeconds = 0
        }
    }

    private fun endRide() {
        finished = true
        refreshState()
        power = null
        heartRateBpm = null
        cadence = null
        speedKmh = 0.0
        pendingRrIntervals.clear()
        recorder.onRideFinished(clock())
        order.value = TrainerOrder(desiredCommand(), last = true)
        wakeSender.trySend(Unit)
    }

    /** What the trainer should be doing now. */
    private fun desiredCommand(): TrainerCommand {
        val target = player?.target
        return when {
            finished -> {
                simulationAt(gears.neutralGear)
            }

            target is PlayerTarget.Erg -> {
                // The stall guard caps whatever the player would send (step target, ramp-in, heart-rate controller); a ride
                // that is not running is never limited by it.
                val watts = if (state == RideState.RIDING) stallGuard.limit(target.watts) else holdWatts(target)
                TrainerCommand.TargetPower(watts.coerceIn(0, settings.maxTargetWatts))
            }

            else -> {
                simulationAt(gear, routeGradePercent())
            }
        }
    }

    /**
     * The ERG target while the ride is not running: the auto-pause hold during an auto-pause (never above what the step
     * asks for), the paused target otherwise: a pause by the rider, before the start, or with the trainer gone.
     */
    private fun holdWatts(target: PlayerTarget.Erg): Int =
        if (autoPaused && !manualPause && settings.autoPauseTargetWatts > 0) {
            minOf(settings.autoPauseTargetWatts, target.nominalWatts)
        } else {
            settings.pausedTargetWatts
        }

    private fun simulationAt(
        gear: Int,
        routeGradePercent: Double = 0.0,
    ) = TrainerCommand.Simulation(gears.simulationParameters(gear, settings.physics, routeGradePercent))

    private fun updateOrder() {
        val next = TrainerOrder(desiredCommand())
        if (state != RideState.RIDING) pauseHoldWatts = (next.command as? TrainerCommand.TargetPower)?.watts ?: 0
        if (next != order.value) {
            order.value = next
            wakeSender.trySend(Unit)
        }
    }

    /**
     * Delivers the latest [order] to the trainer from its own coroutine: Request Control first after every
     * (re)connect, then the command, and again whenever the order changes. Only the latest order matters, so a
     * slow trainer is never sent a queue of outdated targets. A command that fails is retried after a second.
     */
    private suspend fun runTrainerSender() {
        var hasControl = false
        var sent: TrainerCommand? = null
        var finalFailures = 0
        while (true) {
            wakeSender.receive()
            val wanted = order.value
            if (trainer.connection.value != ConnectionState.CONNECTED) {
                hasControl = false
                sent = null
                if (wanted.last) return
                continue
            }
            try {
                if (!hasControl) {
                    trainer.requestControl()
                    hasControl = true
                    sent = null
                }
                if (wanted.command != sent) {
                    when (val command = wanted.command) {
                        is TrainerCommand.TargetPower -> trainer.setTargetPower(command.watts)
                        is TrainerCommand.Simulation -> trainer.setSimulation(command.parameters)
                        TrainerCommand.RequestControl -> trainer.requestControl()
                    }
                    sent = wanted.command
                }
                if (wanted.last) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Start over with Request Control, whatever failed, in a moment; but give up on the last command of
                // a ride that is over after a few tries.
                if (wanted.last && ++finalFailures >= MAX_FINAL_ATTEMPTS) return
                hasControl = false
                sent = null
                delay(COMMAND_RETRY_DELAY)
                wakeSender.trySend(Unit)
            }
        }
    }

    private fun snapshot(): RideSnapshot {
        val mode = currentMode()
        val target = player?.target
        val intensity = player?.intensityPercent ?: RideSnapshot.DEFAULT_INTENSITY_PERCENT
        return RideSnapshot(
            state = state,
            ftp = settings.ftp,
            elapsedSeconds = movingSeconds,
            power = power,
            heartRate = heartRateBpm,
            cadence = cadence,
            speedKmh = speedKmh,
            distanceMeters = distanceMeters,
            mode = mode,
            gear = if (mode == TrainerMode.SIMULATION && !finished) gear else null,
            neutralGear = gears.neutralGear,
            gearCount = gears.gearCount,
            targetPower = if (finished) null else (target as? PlayerTarget.Erg)?.nominalWatts,
            intensityPercent = intensity,
            zone = power?.let { PowerZone.of(it, settings.ftp) },
            workout = workout?.let { player?.progress(it.name) },
            pauseReason = pauseReason(),
            secondsSinceResume = secondsSinceResume,
            stallGuardActive = stallGuard.active,
            stallGuardWatts =
                if (stallGuard.active) {
                    (target as? PlayerTarget.Erg)?.let {
                        stallGuard.limit(
                            it.watts,
                        )
                    }
                } else {
                    null
                },
            heartRateHold = if (finished) null else heartRateHold?.state(),
            route = routeProgress(),
        )
    }

    private fun pauseReason(): PauseReason? =
        if (state != RideState.PAUSED) {
            null
        } else {
            when {
                !trainerConnected -> PauseReason.TRAINER_DISCONNECTED
                manualPause -> PauseReason.MANUAL
                else -> PauseReason.AUTO
            }
        }

    /**
     * The latest reading of a device, reused for up to [timeoutSeconds] ticks when no newer one arrives.
     * A reading that arrived before tick *k* counts as age 0 at that tick.
     */
    private class HeldReading<T : Any>(
        private val timeoutSeconds: Int,
    ) {
        private var value: T? = null
        private var age = 0

        fun offer(reading: T) {
            value = reading
            age = 0
        }

        fun clear() {
            value = null
        }

        /** The reading to use at a tick, or null if there is none or it is too old; then ages it by a tick. */
        fun take(): T? {
            val reading = peek()
            if (age < Int.MAX_VALUE) age++
            return reading
        }

        fun peek(): T? = if (age < timeoutSeconds) value else null
    }

    private companion object {
        const val KMH_PER_METER_PER_SECOND = 3.6
        const val NO_ROUTE_STEP = -1
        val COMMAND_RETRY_DELAY = 1.seconds
        const val MAX_FINAL_ATTEMPTS = 3
    }
}
