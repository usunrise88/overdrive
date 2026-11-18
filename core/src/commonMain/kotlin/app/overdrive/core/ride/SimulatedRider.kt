package app.overdrive.core.ride

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The signal model of the simulated rider, shared by [SimulatedRideSession] and the simulated devices
 * (`SimulatedBike`): how power follows a target, how cadence settles and how heart rate lags power, all
 * with seeded noise (spec §7.5).
 *
 * Call [drawNoise] once per simulated second, then [advance], then read the sensor values.
 * [drawNoise] always takes the same four random draws in the same order, so dropouts, pauses and
 * early returns never shift the noise of later seconds.
 */
internal class SimulatedRider(
    private val ftp: Int,
    private val scenario: SimulationScenario,
    seed: Long,
) {
    private val random = Random(seed)
    private val powerLagPerTick = 1.0 - exp(-1.0 / POWER_LAG_SECONDS)
    private val heartRateLagPerTick = 1.0 - exp(-1.0 / HEART_RATE_LAG_SECONDS)

    /** Smooth rider power in watts, before sensor noise. */
    var power = 0.0

    /** Smooth cadence in rpm. */
    var cadence = scenario.cadenceRpm
        private set

    /** Smooth heart rate in bpm. */
    var heartRate = scenario.restingHeartRate.toDouble()
        private set

    private var drift = 0.0
    private var powerNoise = 0.0
    private var cadenceNoise = 0.0
    private var heartRateNoise = 0.0

    /** Takes this second's random draws. */
    fun drawNoise() {
        val noise = scenario.noise
        powerNoise = noise * POWER_NOISE * gaussian()
        cadenceNoise = noise * CADENCE_NOISE * gaussian()
        heartRateNoise = noise * HEART_RATE_NOISE * gaussian()
        drift = DRIFT_DECAY * drift + noise * DRIFT_STEP * gaussian()
    }

    /**
     * Moves the rider one second on.
     *
     * @param riding whether the rider is pedalling; when not, power relaxes to 0.
     * @param ergWatts what the trainer holds in ERG, or null in simulation, where the rider floats around
     * [SimulationScenario.freeRideFtpFraction] of FTP, scaled by [gearFactor], with a slow drift.
     * @param cadenceBase the cadence the rider settles around, in rpm.
     */
    fun advance(
        riding: Boolean,
        ergWatts: Double?,
        gearFactor: Double,
        cadenceBase: Double,
    ) {
        val target =
            when {
                !riding -> 0.0
                ergWatts != null -> ergWatts
                else -> scenario.freeRideFtpFraction * ftp * gearFactor * (1.0 + drift)
            }
        power += (target - power) * powerLagPerTick
        cadence =
            (cadence + CADENCE_PULL * (cadenceBase - cadence) + cadenceNoise).coerceIn(MIN_CADENCE, MAX_CADENCE)
        val heartRateTarget =
            (scenario.restingHeartRate + (scenario.heartRateAtFtp - scenario.restingHeartRate) * power / ftp)
                .coerceAtMost(MAX_HEART_RATE)
        heartRate += (heartRateTarget - heartRate) * heartRateLagPerTick
    }

    /** The power sensor's reading in whole watts, with noise; 0 when the rider is not [riding]. */
    fun powerReading(riding: Boolean): Int =
        if (riding) (power * (1.0 + powerNoise)).roundToInt().coerceAtLeast(0) else 0

    /** The cadence sensor's reading in whole rpm; 0 when the rider is not [riding]. */
    fun cadenceReading(riding: Boolean): Int = if (riding) cadence.roundToInt() else 0

    /** The strap's reading in whole bpm, with noise. */
    fun heartRateReading(): Int = (heartRate + heartRateNoise).roundToInt()

    private fun gaussian(): Double {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private companion object {
        const val POWER_LAG_SECONDS = 2.0
        const val HEART_RATE_LAG_SECONDS = 25.0
        const val POWER_NOISE = 0.02
        const val CADENCE_NOISE = 1.2
        const val CADENCE_PULL = 0.3
        const val HEART_RATE_NOISE = 0.8
        const val DRIFT_DECAY = 0.95
        const val DRIFT_STEP = 0.02
        const val MIN_CADENCE = 40.0
        const val MAX_CADENCE = 140.0
        const val MAX_HEART_RATE = 200.0
    }
}
