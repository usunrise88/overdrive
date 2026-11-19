package app.overdrive.core.ride

import app.overdrive.core.device.SimulatedTrainerDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a [FlywheelTrainerModel] behaves, changeable while it runs; the defaults are the trainer the owner described (docs/adr/0032). */
internal class FlywheelTrainerOptions(
    /** Milliseconds between two notifications: the D500 sends about 4 a second (bench report 1, section 2). */
    var notifyMillis: Long = 250,
    /**
     * The commanded ERG target below which the cadence field reads 0 although the rider pedals: a trainer with no crank
     * sensor that infers cadence from how hard the flywheel is being worked. 0 is a trainer whose cadence is always right.
     * With no ERG target (simulation) the cadence is always right.
     */
    var cadenceNeedsWatts: Int = 40,
    /** Whether the notification carries power at all. */
    var reportsPower: Boolean = true,
    /** Whether the notification carries the flywheel's speed. */
    var reportsSpeed: Boolean = true,
    /**
     * Whether a flywheel that spins down by itself still reports power: an ERG trainer reports what it is regulating to,
     * so a coasting flywheel keeps showing the target until it stops.
     */
    var powerWhileCoasting: Boolean = true,
    /** The cadence the rider really pedals at. */
    var riderCadenceRpm: Int = 85,
    /** Flywheel speed at which the pedalling rider cruises. */
    var cruiseKmh: Double = 24.0,
    /** How fast the pedalling rider accelerates the flywheel. */
    var accelerationKmhPerSecond: Double = 8.0,
    /** How fast the flywheel winds down when nobody pedals. Slow on purpose: 0.4 km/h between two notifications. */
    var coastKmhPerSecond: Double = 1.6,
    /** The speed field is refreshed every this many notifications and repeated in between, as a slow sensor would. */
    var speedRefreshEvery: Int = 1,
)

/**
 * A trainer with a flywheel and no crank sensor, in virtual time, in front of a [SimulatedTrainerDevice]: the model of the
 * deadlock in docs/adr/0032 (a hypothesis until a Bluetooth log from the bike says so). It notifies every
 * [FlywheelTrainerOptions.notifyMillis] with cadence, power and flywheel speed that follow what the rider does
 * ([pedalling]) and what the engine commanded (`trainer.targetPowerWatts`):
 *
 * - a pedalling rider accelerates the flywheel to a cruising speed; a resting rider lets it wind down;
 * - **cadence reads 0 while the commanded ERG target is below [FlywheelTrainerOptions.cadenceNeedsWatts]**, which is
 *   what makes 0 W a deadlock: the rider pedals, the trainer shows no cadence, the engine keeps it at 0 W;
 * - power is the commanded target while pedalling and, if [FlywheelTrainerOptions.powerWhileCoasting], while the
 *   flywheel still turns after the rider stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FlywheelTrainerModel(
    private val scope: TestScope,
    private val trainer: SimulatedTrainerDevice,
    val options: FlywheelTrainerOptions = FlywheelTrainerOptions(),
) {
    /** Whether the rider turns the pedals right now. */
    var pedalling: Boolean = false

    /** The flywheel's speed, in km/h. */
    var speedKmh: Double = 0.0
        private set

    /** Notifications sent so far. */
    var notifications: Int = 0
        private set

    private var shownSpeed = 0.0

    init {
        require(options.speedRefreshEvery >= 1)
        scope.backgroundScope.launch {
            while (true) {
                delay(options.notifyMillis)
                sendNotification()
            }
        }
    }

    /** The rider starts pedalling (the first stroke happens at the current virtual instant). */
    fun pedal() {
        pedalling = true
    }

    /** The rider stops pedalling; the flywheel winds down. */
    fun rest() {
        pedalling = false
    }

    /** Lets [millis] of virtual time pass, with every notification and tick inside it. */
    fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    /** Lets time pass up to and including the next notification. */
    fun advanceOneNotification() = advance(options.notifyMillis)

    /** Lets [seconds] of virtual time pass. */
    fun advanceSeconds(seconds: Int) = advance(seconds * MILLIS)

    private fun sendNotification() {
        val dt = options.notifyMillis / MILLIS.toDouble()
        speedKmh =
            if (pedalling) {
                min(options.cruiseKmh, speedKmh + options.accelerationKmhPerSecond * dt)
            } else {
                max(0.0, speedKmh - options.coastKmhPerSecond * dt)
            }
        if (notifications % options.speedRefreshEvery == 0) shownSpeed = speedKmh
        notifications++

        val commanded = trainer.targetPowerWatts
        val cadenceShows = pedalling && (commanded == null || commanded >= options.cadenceNeedsWatts)
        val turning = pedalling || (options.powerWhileCoasting && speedKmh > 0.0)
        val power = if (turning) commanded ?: FREE_RIDE_WATTS else 0
        trainer.emit(
            power = if (options.reportsPower) power else null,
            cadence = if (cadenceShows) options.riderCadenceRpm else 0,
            speedKmh = if (options.reportsSpeed) (shownSpeed * HUNDRED).roundToInt() / HUNDRED else null,
        )
    }

    private companion object {
        const val MILLIS = 1000L
        const val FREE_RIDE_WATTS = 110
        const val HUNDRED = 100.0
    }
}
