package app.overdrive.core.device

import app.overdrive.core.gear.GearSettings
import app.overdrive.core.ride.SignalDropout
import app.overdrive.core.ride.SimulatedRider
import app.overdrive.core.ride.SimulatedSignal
import app.overdrive.core.ride.SimulationScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two simulated devices a [SimulatedBike] drives. */
public enum class SimulatedDevice {
    TRAINER,
    HEART_RATE,
}

/**
 * Something that goes wrong (or just happens) during a [SimulatedBike] ride. Times are **sensor time**: the
 * number of seconds since [SimulatedBike.start], counting the first tick as second 1, like [SignalDropout].
 */
public sealed interface BikeEvent {
    /** First second the event is in effect. */
    public val startSecond: Int

    /** How many seconds it lasts, or null for good. */
    public val durationSeconds: Int?
}

/**
 * [device] loses its link at [startSecond] and reconnects by itself after [durationSeconds]. With a null
 * duration it never comes back: the device is powered off (the trainer's plug is pulled, the strap's battery dies).
 */
public data class DeviceDisconnect(
    val device: SimulatedDevice,
    override val startSecond: Int,
    override val durationSeconds: Int? = null,
) : BikeEvent {
    init {
        require(startSecond >= 1) { "An event starts at second 1 or later, got $startSecond" }
        require(
            durationSeconds == null || durationSeconds >= 1,
        ) { "An event lasts at least a second, got $durationSeconds" }
    }
}

/** [device] stays connected but sends no notifications for [durationSeconds] from [startSecond] on. */
public data class DeviceSilence(
    val device: SimulatedDevice,
    override val startSecond: Int,
    override val durationSeconds: Int,
) : BikeEvent {
    init {
        require(startSecond >= 1) { "An event starts at second 1 or later, got $startSecond" }
        require(durationSeconds >= 1) { "An event lasts at least a second, got $durationSeconds" }
    }
}

/** The rider stops pedalling for [durationSeconds] from [startSecond] on (a drink, a phone call), then carries on. */
public data class RiderStop(
    override val startSecond: Int,
    override val durationSeconds: Int,
) : BikeEvent {
    init {
        require(startSecond >= 1) { "An event starts at second 1 or later, got $startSecond" }
        require(durationSeconds >= 1) { "An event lasts at least a second, got $durationSeconds" }
    }
}

private fun BikeEvent.covers(second: Int): Boolean {
    val duration = durationSeconds
    return second >= startSecond && (duration == null || second - startSecond < duration)
}

/**
 * A simulated rider on a simulated trainer and strap: the hardware-free test bench for a ride (spec §7.5).
 * Once a second it advances a rider model (the signal logic of [app.overdrive.core.ride.SimulatedRideSession]:
 * power follows the target with a lag, heart rate follows power) and delivers the readings through [trainer]
 * and [heartRate], which a [app.overdrive.core.ride.RideEngine] consumes like real devices.
 *
 * **What the rider does with the trainer's commands.** In ERG the rider holds the last target power; in simulation it floats
 * around [SimulationScenario.freeRideFtpFraction] of FTP and, for each [GearSettings.gradePercentPerGear] of
 * grade away from the flat, produces [SimulationScenario.powerPerGear] more (or less) of that. With no
 * command the rider floats as in neutral gear. [SimulationScenario.gearCount] and `neutralGear` are not used here:
 * the gear lives in the engine, the bike only sees the grade.
 *
 * **What goes wrong.** [SimulationScenario.dropouts] blank one reading while the device stays connected (a
 * heart-rate dropout is a strap that reports 0 bpm), [BikeEvent]s disconnect or silence a device or stop the rider.
 *
 * **Time and order.** The bike ticks every [tickInterval] on [scope], starting [tickInterval] after [start].
 * Start it no later than the engine's ride: readings of second *k* are then delivered just before the engine's
 * tick *k*. Determinism: the same seed, scenario and events yield the same readings.
 *
 * @param clock stamps commands received by [trainer], see [SimulatedTrainerDevice].
 * @param heartRateSendsRr whether the strap sends RR intervals, which it does when it can.
 */
public class SimulatedBike(
    private val scope: CoroutineScope,
    private val ftp: Int = DEFAULT_FTP,
    seed: Long = 1L,
    private val scenario: SimulationScenario = SimulationScenario(),
    private val events: List<BikeEvent> = emptyList(),
    private val gears: GearSettings = GearSettings(),
    private val tickInterval: Duration = 1.seconds,
    private val heartRateSendsRr: Boolean = true,
    clock: () -> Long = { 0L },
) {
    init {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        require(tickInterval.isPositive()) { "The tick interval must be positive, got $tickInterval" }
    }

    /** The simulated trainer; hand it to the engine. */
    public val trainer: SimulatedTrainerDevice = SimulatedTrainerDevice(clock = clock)

    /** The simulated strap; hand it to the engine. */
    public val heartRate: SimulatedHeartRateDevice = SimulatedHeartRateDevice()

    /** Whether the rider is pedalling; set it to stop or restart the rider by hand, on top of [RiderStop] events. */
    public var pedalling: Boolean = true

    private val rider = SimulatedRider(ftp, scenario, seed)
    private val rrRandom = Random(seed xor RR_SEED_MIX)
    private var sensorSecond = 0
    private var job: Job? = null

    /**
     * Starts the clock. With [connect] both devices connect at once; without it they stay disconnected until
     * their `connect()` is called. A second call does nothing.
     */
    public fun start(connect: Boolean = true) {
        if (job != null) return
        if (connect) {
            trainer.connect()
            heartRate.connect()
        }
        job =
            scope.launch {
                while (true) {
                    delay(tickInterval)
                    tick()
                }
            }
    }

    private fun tick() {
        sensorSecond++
        applyConnectionEvents()
        rider.drawNoise()

        val riding = pedalling && events.none { it is RiderStop && it.covers(sensorSecond) }
        val gradeInGears = (trainer.simulation?.gradePercent ?: 0.0) / gears.gradePercentPerGear
        rider.advance(
            riding = riding,
            ergWatts = trainer.targetPowerWatts?.toDouble(),
            gearFactor = (1.0 + scenario.powerPerGear * gradeInGears).coerceAtLeast(MIN_GEAR_FACTOR),
            cadenceBase = scenario.cadenceRpm,
        )

        if (!silent(SimulatedDevice.TRAINER)) {
            val cadence = rider.cadenceReading(riding)
            trainer.emit(
                power = if (dropped(SimulatedSignal.POWER)) null else rider.powerReading(riding),
                cadence = if (dropped(SimulatedSignal.CADENCE)) null else cadence,
                // The trainer's own speed follows the flywheel, which a single cog ties to cadence. Never used.
                speedKmh = cadence * FLYWHEEL_KMH_PER_RPM,
            )
        }
        if (!silent(SimulatedDevice.HEART_RATE)) {
            val bpm = if (dropped(SimulatedSignal.HEART_RATE)) 0 else rider.heartRateReading()
            heartRate.emit(bpm, if (heartRateSendsRr) rrIntervals(bpm) else emptyList())
        }
    }

    private fun applyConnectionEvents() {
        for (device in SimulatedDevice.entries) {
            val now =
                events.filterIsInstance<DeviceDisconnect>().filter {
                    it.device == device &&
                        it.covers(sensorSecond)
                }
            val before =
                events.filterIsInstance<DeviceDisconnect>().filter {
                    it.device == device &&
                        it.covers(
                            sensorSecond - 1,
                        )
                }
            if (now.isNotEmpty() && before.isEmpty()) {
                val forGood = now.any { it.durationSeconds == null }
                when (device) {
                    SimulatedDevice.TRAINER -> if (forGood) trainer.powerOff() else trainer.disconnect()
                    SimulatedDevice.HEART_RATE -> if (forGood) heartRate.powerOff() else heartRate.disconnect()
                }
            } else if (now.isEmpty() && before.isNotEmpty()) {
                when (device) {
                    SimulatedDevice.TRAINER -> trainer.connect()
                    SimulatedDevice.HEART_RATE -> heartRate.connect()
                }
            }
        }
    }

    private fun silent(device: SimulatedDevice) =
        events.any { it is DeviceSilence && it.device == device && it.covers(sensorSecond) }

    private fun dropped(signal: SimulatedSignal) = scenario.dropouts.any { it.covers(sensorSecond, signal) }

    /** The beats of this second: about 60000/bpm ms each, with a little beat-to-beat variation. */
    private fun rrIntervals(bpm: Int): List<Int> {
        if (bpm <= 0) return emptyList()
        val beats = (bpm / SECONDS_PER_MINUTE).roundToInt().coerceAtLeast(1)
        return List(beats) {
            val variation = 1.0 + RR_VARIATION * (2.0 * rrRandom.nextDouble() - 1.0)
            (MILLIS_PER_MINUTE / bpm * variation).roundToInt()
        }
    }

    public companion object {
        public const val DEFAULT_FTP: Int = 200
        private const val RR_SEED_MIX = 0x5DEECE66DL
        private const val RR_VARIATION = 0.03
        private const val SECONDS_PER_MINUTE = 60.0
        private const val MILLIS_PER_MINUTE = 60_000.0
        private const val MIN_GEAR_FACTOR = 0.1
        private const val FLYWHEEL_KMH_PER_RPM = 0.4
    }
}
