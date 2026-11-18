package app.overdrive.core.ride

import app.overdrive.core.workout.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [RideSession] driven by a simulated rider, trainer and heart-rate strap, so the ride screen and the
 * overlay can be tried without a bike (spec §7.5). Behaviour of the commands: see [RideSession].
 *
 * **Time.** Every tick of [tickInterval] advances the simulation by one second. With the default
 * interval it runs in real time and snapshots arrive at 1 Hz; a shorter interval fast-forwards a
 * long workout. Ticks come from [delay] on [scope], so a test dispatcher with virtual time drives it
 * exactly. The session launches one coroutine on [scope] for its commands and one for the ticker, and
 * both end when the ride is finished or [scope] is cancelled. A session that is never finished keeps
 * waiting for commands, so give it a scope you cancel.
 *
 * **Determinism.** The same [seed], [workout], [scenario] and sequence of commands at the same ticks
 * yield identical snapshots. A different seed yields different noise.
 *
 * **The simulated rider.**
 * - In an ERG step power follows the target (the step's FTP fraction times [ftp] times the intensity)
 *   through a first-order lag with a time constant of 2 s, plus a little noise.
 * - In a simulation step, which includes a ride without a workout, it floats around
 *   [SimulationScenario.freeRideFtpFraction] of FTP with a slow random drift, and each gear away from
 *   the neutral one adds or removes [SimulationScenario.powerPerGear] of that power.
 * - Cadence settles around the step's cadence target, else [SimulationScenario.cadenceRpm], with noise.
 * - Heart rate follows power through a first-order lag with a time constant of 25 s.
 * - Speed and distance come from the [speedModel]: the placeholder by default, or pass
 *   `FlatPhysicsSpeedModel()` for the real physics (spec §4.3), which [RideEngine] uses.
 * - Paused: power, cadence and speed are 0 and the heart rate relaxes towards its resting value.
 *
 * Before the ride starts, and after it, the sensor values are null. A ride with a workout finishes by
 * itself when the workout's last second has passed; a free ride ends only with [finish].
 *
 * @param ftp FTP in watts, a manual setting in Phase 1 (spec §4.5).
 * @param startConnected `false` starts in [RideState.IDLE] (devices not connected) until [connect] is called.
 */
public class SimulatedRideSession(
    scope: CoroutineScope,
    private val workout: Workout? = null,
    private val ftp: Int = DEFAULT_FTP,
    seed: Long = DEFAULT_SEED,
    private val scenario: SimulationScenario = SimulationScenario(),
    private val speedModel: SpeedModel = PlaceholderSpeedModel,
    private val tickInterval: Duration = 1.seconds,
    startConnected: Boolean = true,
) : RideSession {
    private enum class Command {
        Connect,
        Start,
        Pause,
        Resume,
        Finish,
        Next,
        Previous,
        GearUp,
        GearDown,
        IntensityUp,
        IntensityDown,
        Tick,
    }

    init {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        require(tickInterval.isPositive()) { "The tick interval must be positive, got $tickInterval" }
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Everything below is touched only by the coroutine that drains `commands`, one command at a time.
    private val steps: List<FlatStep> = workout?.flatSteps().orEmpty()
    private val totalSeconds = steps.totalDurationSeconds()
    private val rider = SimulatedRider(ftp, scenario, seed)

    private var state = if (startConnected) RideState.READY else RideState.IDLE
    private var elapsed = 0
    private var position = 0
    private var stepIndex = 0
    private var sensorSecond = 0
    private var gear = scenario.neutralGear
    private var intensity = RideSnapshot.DEFAULT_INTENSITY_PERCENT

    private var speedKmh = 0.0
    private var distanceMeters = 0.0

    private var powerReading: Int? = null
    private var heartRateReading: Int? = null
    private var cadenceReading: Int? = null

    private val mutableSnapshots = MutableStateFlow(snapshot())

    override val snapshots: StateFlow<RideSnapshot> = mutableSnapshots.asStateFlow()

    init {
        scope.launch {
            val ticker =
                launch(start = CoroutineStart.LAZY) {
                    while (true) {
                        delay(tickInterval)
                        commands.trySend(Command.Tick)
                    }
                }
            try {
                for (command in commands) {
                    apply(command)
                    mutableSnapshots.value = snapshot()
                    if (state == RideState.FINISHED) break
                    if (isLive()) ticker.start()
                }
            } finally {
                ticker.cancel()
                commands.close()
            }
        }
    }

    /**
     * Simulates the devices connecting: [RideState.IDLE] becomes [RideState.READY]. A no-op in every
     * other state, and not needed unless the session was created with `startConnected = false`.
     */
    public fun connect() {
        commands.trySend(Command.Connect)
    }

    override fun start() {
        commands.trySend(Command.Start)
    }

    override fun pause() {
        commands.trySend(Command.Pause)
    }

    override fun resume() {
        commands.trySend(Command.Resume)
    }

    override fun finish() {
        commands.trySend(Command.Finish)
    }

    override fun nextInterval() {
        commands.trySend(Command.Next)
    }

    override fun previousInterval() {
        commands.trySend(Command.Previous)
    }

    override fun gearUp() {
        commands.trySend(Command.GearUp)
    }

    override fun gearDown() {
        commands.trySend(Command.GearDown)
    }

    override fun intensityUp() {
        commands.trySend(Command.IntensityUp)
    }

    override fun intensityDown() {
        commands.trySend(Command.IntensityDown)
    }

    private fun isLive() = state == RideState.RIDING || state == RideState.PAUSED

    private fun currentStep(): FlatStep? = steps.getOrNull(stepIndex)

    private fun currentMode(): TrainerMode = currentStep()?.mode ?: TrainerMode.SIMULATION

    /** Gear and intensity can be adjusted once the devices are connected, until the ride is over. */
    private fun isAdjustable() = state == RideState.READY || isLive()

    private fun apply(command: Command) {
        when (command) {
            Command.Connect -> connectDevices()
            Command.Start -> startRide()
            Command.Pause -> pauseRide()
            Command.Resume -> resumeRide()
            Command.Finish -> finishRide()
            Command.Next -> nextStep()
            Command.Previous -> previousStep()
            Command.GearUp -> shiftGear(+1)
            Command.GearDown -> shiftGear(-1)
            Command.IntensityUp -> changeIntensity(+RideSnapshot.INTENSITY_STEP_PERCENT)
            Command.IntensityDown -> changeIntensity(-RideSnapshot.INTENSITY_STEP_PERCENT)
            Command.Tick -> if (isLive()) tick()
        }
    }

    private fun connectDevices() {
        if (state == RideState.IDLE) state = RideState.READY
    }

    private fun shiftGear(delta: Int) {
        if (isAdjustable() && currentMode() == TrainerMode.SIMULATION) {
            gear = (gear + delta).coerceIn(1, scenario.gearCount)
        }
    }

    private fun changeIntensity(delta: Int) {
        if (isAdjustable() && currentMode() == TrainerMode.ERG) {
            intensity =
                (intensity + delta).coerceIn(RideSnapshot.MIN_INTENSITY_PERCENT, RideSnapshot.MAX_INTENSITY_PERCENT)
        }
    }

    private fun startRide() {
        if (state != RideState.READY) return
        state = RideState.RIDING
        powerReading = 0
        cadenceReading = 0
        heartRateReading = rider.heartRate.roundToInt()
    }

    private fun pauseRide() {
        if (state != RideState.RIDING) return
        state = RideState.PAUSED
        rider.power = 0.0
        speedKmh = 0.0
        powerReading = reading(SimulatedSignal.POWER, 0)
        cadenceReading = reading(SimulatedSignal.CADENCE, 0)
    }

    private fun resumeRide() {
        if (state == RideState.PAUSED) state = RideState.RIDING
    }

    private fun finishRide() {
        if (!isLive()) return
        endRide()
    }

    private fun endRide() {
        state = RideState.FINISHED
        rider.power = 0.0
        speedKmh = 0.0
        powerReading = null
        cadenceReading = null
        heartRateReading = null
    }

    private fun nextStep() {
        if (isLive() && stepIndex < steps.lastIndex) jumpTo(stepIndex + 1)
    }

    private fun jumpTo(index: Int) {
        stepIndex = index
        position = steps[index].startSeconds
    }

    private fun previousStep() {
        if (!isLive() || steps.isEmpty()) return
        val intoStep = position - steps[stepIndex].startSeconds
        val restart = stepIndex == 0 || intoStep > RideSession.PREVIOUS_INTERVAL_RESTART_AFTER_SECONDS
        jumpTo(if (restart) stepIndex else stepIndex - 1)
    }

    private fun tick() {
        sensorSecond++
        // Always draws the same four values in the same order, so dropouts and pauses never shift the noise.
        rider.drawNoise()

        if (state == RideState.RIDING) {
            elapsed++
            if (steps.isNotEmpty()) {
                position++
                if (position >= totalSeconds) {
                    endRide()
                    return
                }
                while (position >= steps[stepIndex].endSeconds) stepIndex++
            }
        }

        val riding = state == RideState.RIDING
        rider.advance(
            riding = riding,
            ergWatts = currentErgWatts(),
            gearFactor = (1.0 + scenario.powerPerGear * (gear - scenario.neutralGear)).coerceAtLeast(MIN_GEAR_FACTOR),
            cadenceBase = currentStep()?.cadence ?: scenario.cadenceRpm,
        )

        speedKmh = speedModel.speedKmh(rider.power)
        if (riding) distanceMeters += speedKmh / KMH_PER_METER_PER_SECOND

        powerReading = reading(SimulatedSignal.POWER, rider.powerReading(riding))
        cadenceReading = reading(SimulatedSignal.CADENCE, rider.cadenceReading(riding))
        heartRateReading = reading(SimulatedSignal.HEART_RATE, rider.heartRateReading())
    }

    /** What an ERG step asks the rider to hold in watts at the current workout position, null in a simulation step. */
    private fun currentErgWatts(): Double? {
        val step = currentStep()
        return step?.targetFractionAt(position - step.startSeconds)?.let(::ergWatts)
    }

    /** Watts asked for by an ERG target given as a fraction of FTP, after the rider's intensity setting. */
    private fun ergWatts(ftpFraction: Double): Double = ftpFraction * ftp * intensity / PERCENT

    private fun reading(
        signal: SimulatedSignal,
        value: Int,
    ): Int? = if (scenario.dropouts.any { it.covers(sensorSecond, signal) }) null else value

    private fun snapshot(): RideSnapshot {
        val finished = state == RideState.FINISHED
        val step = currentStep()
        val mode = currentMode()
        val targetFraction = step?.targetFractionAt(position - step.startSeconds)
        return RideSnapshot(
            state = state,
            ftp = ftp,
            elapsedSeconds = elapsed,
            power = powerReading,
            heartRate = heartRateReading,
            cadence = cadenceReading,
            speedKmh = speedKmh,
            distanceMeters = distanceMeters,
            mode = mode,
            gear = if (mode == TrainerMode.SIMULATION && !finished) gear else null,
            neutralGear = scenario.neutralGear,
            gearCount = scenario.gearCount,
            targetPower = if (finished) null else targetFraction?.let { ergWatts(it).roundToInt() },
            intensityPercent = intensity,
            zone = powerReading?.let { PowerZone.of(it, ftp) },
            workout = progress(),
            pauseReason = if (state == RideState.PAUSED) PauseReason.MANUAL else null,
        )
    }

    private fun progress(): WorkoutProgress? {
        val name = workout?.name ?: return null
        val step = steps[stepIndex]
        val intoStep = position - step.startSeconds
        return WorkoutProgress(
            workoutName = name,
            steps = steps,
            stepIndex = stepIndex,
            stepElapsedSeconds = intoStep,
            stepRemainingSeconds = step.durationSeconds - intoStep,
            workoutElapsedSeconds = position,
            workoutRemainingSeconds = totalSeconds - position,
        )
    }

    public companion object {
        public const val DEFAULT_FTP: Int = 200
        public const val DEFAULT_SEED: Long = 1L
    }
}

private const val MIN_GEAR_FACTOR = 0.1
private const val PERCENT = 100.0
private const val KMH_PER_METER_PER_SECOND = 3.6
