package app.overdrive.core.ride

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A rider whose heart rate follows the power with a first-order lag, as it does to a first approximation: the steady-state
 * heart rate is `rest + gain * power`, and the heart rate closes `1 / tau` of the distance to it every second. It reads in whole
 * bpm, with a little noise. Synthetic: no recording of anyone is behind these numbers.
 */
internal class LagRider(
    private val gain: Double,
    private val tau: Double,
    private val rest: Double = 65.0,
    private val noiseBpm: Int = 2,
    seed: Int = 1,
) {
    private val random = Random(seed)
    var heartRate = rest
        private set

    fun reading(): Int = (heartRate + if (noiseBpm == 0) 0 else random.nextInt(-noiseBpm, noiseBpm + 1)).roundToInt()

    fun pedal(watts: Int) {
        heartRate += (rest + gain * watts - heartRate) / tau
    }
}
