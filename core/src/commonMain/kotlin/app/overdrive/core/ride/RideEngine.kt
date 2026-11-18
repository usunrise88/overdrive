package app.overdrive.core.ride

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateDevice
import app.overdrive.core.device.HeartRateReading
import app.overdrive.core.device.TrainerCommand
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.device.TrainerReading
import app.overdrive.core.physics.FlatPhysicsSpeedModel
import app.overdrive.core.record.NoRideRecorder
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
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
 * never delays the ride clock (spec §7.3). Give it a scope you cancel when the app stops; the engine ends its
 * own coroutines when the ride is finished.
 *
 * **States** (spec §4.2, details in [RideSession]).
 * - `IDLE` until the trainer connects, then `READY`; `start` begins the ride. A strap is optional.
 * - `PAUSED` has three independent reasons ([PauseReason]); the ride runs only when none holds.
 *   **Auto-pause:** after [RideSettings.autoPauseAfterSeconds] consecutive seconds of zero cadence; pedalling
 *   again lifts it. **Manual pause** overrides it: pedalling does not resume a manually paused ride, only `resume`
 *   does. **Trainer disconnect** pauses the ride at once and its reconnect resumes it, with nothing lost
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
 *   [RideSettings.maxTargetWatts]. After a pause it ramps in over [RideSettings.rampInSeconds] seconds (spec §4.5).
 * - While paused or before the start: [RideSettings.pausedTargetWatts] in ERG steps, so the rider can spin up.
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

    private var started = false
    private var finished = false
    private var state = RideState.IDLE
    private var trainerConnected = false
    private var manualPause = false
    private var autoPaused = false
    private var zeroCadenceSeconds = 0

    private var gear = gears.neutralGear
    private var rideSeconds = 0
    private var movingSeconds = 0
    private var speedKmh = 0.0
    private var distanceMeters = 0.0

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
            Input.IntensityUp -> if (isAdjustable()) player?.intensityUp()
            Input.IntensityDown -> if (isAdjustable()) player?.intensityDown()
            Input.Tick -> if (isLive()) tick()
            is Input.TrainerLink -> onTrainerLink(input.state == ConnectionState.CONNECTED)
            is Input.HeartRateLink -> onHeartRateLink(input.state == ConnectionState.CONNECTED)
            is Input.TrainerData -> if (trainerConnected) trainerReading.offer(input.reading)
            is Input.HeartRateData -> onHeartRateData(input.reading)
        }
        if (!finished) updateOrder()
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
        recorder.onRideStarted(
            RideHeader(
                id = rideId ?: "ride-$startMillis",
                startEpochMillis = startMillis,
                workoutId = workout?.id,
                ftp = settings.ftp,
                devices = listOfNotNull(trainer.name, heartRate?.name),
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
        zeroCadenceSeconds = 0
        if (!connected && isLive()) {
            power = null
            cadence = null
            speedKmh = 0.0
        }
        refreshState()
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
        if (state != RideState.RIDING) zeroCadenceSeconds = 0
        if (before == RideState.PAUSED && state == RideState.RIDING) player?.beginRampIn()
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

        speedKmh = if (wasRiding && powerNow != null) speedModel.speedKmh(powerNow.toDouble()) else 0.0
        if (wasRiding) distanceMeters += speedKmh / KMH_PER_METER_PER_SECOND

        // The sample describes the second that just ended: readings now, plan as it was during the second.
        val target = player?.target
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
                stepIndex = player?.stepIndex,
                paused = !wasRiding,
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
            player?.let {
                it.advance()
                if (it.isComplete) {
                    endRide()
                    return
                }
            }
        }
        updateAutoPause(cadenceNow, wasRiding)
        refreshState()
    }

    /** Spec §4.2: pause after 3 s of zero cadence, resume when pedalling starts. */
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
        if (autoPaused && cadenceNow != null && cadenceNow > 0) autoPaused = false
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
                val watts = if (state == RideState.RIDING) target.watts else settings.pausedTargetWatts
                TrainerCommand.TargetPower(watts.coerceIn(0, settings.maxTargetWatts))
            }

            else -> {
                simulationAt(gear)
            }
        }
    }

    private fun simulationAt(gear: Int) = TrainerCommand.Simulation(gears.simulationParameters(gear, settings.physics))

    private fun updateOrder() {
        val next = TrainerOrder(desiredCommand())
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
                // Start over with Request Control, whatever failed, in a moment.
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
        val COMMAND_RETRY_DELAY = 1.seconds
    }
}
