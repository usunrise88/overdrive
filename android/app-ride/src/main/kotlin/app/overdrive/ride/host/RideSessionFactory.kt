package app.overdrive.ride.host

import app.overdrive.core.device.DeviceDisconnect
import app.overdrive.core.device.HeartRateDevice
import app.overdrive.core.device.SimulatedBike
import app.overdrive.core.device.SimulatedDevice
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.gear.GearSettings
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.ride.RideEngine
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.ride.SignalDropout
import app.overdrive.core.ride.SimulatedSignal
import app.overdrive.core.ride.SimulationScenario
import app.overdrive.core.workout.Workout
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DeviceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** What a [RideSessionFactory] needs to build one ride. */
class RideRequest(
    /** Runs the ride and its devices; single-threaded, cancelled by the host when the ride is thrown away. */
    val scope: CoroutineScope,
    /** The workout to play, or null for a free ride. */
    val workout: Workout?,
    val settings: AppSettings,
    /** Where the engine writes the ride as it happens. */
    val recorder: RideRecorder,
    /** 1, 2, 3, … per ride in this process; seeds the simulation. */
    val rideNumber: Int,
)

/** The things the Devices screen can ask a device layer to do. Every call is fire and forget and returns at once. */
interface DeviceControls {
    /** Try the trainer's link again now instead of waiting for the next backoff step. */
    fun retryTrainer()

    /** Try the heart-rate strap's link again now. */
    fun retryHeartRate()
}

/**
 * One ride's session with the devices behind it, as the host and the Devices screen see them.
 *
 * @property devices the rows of the Devices screen when the device layer has richer ones than the three-state links of
 * [trainer] and [heartRate] can give (the Bluetooth factory: link states, names, Bluetooth off); null for the simulation.
 */
class RideSessionHandle(
    val session: RideSession,
    val trainer: TrainerDevice,
    val heartRate: HeartRateDevice?,
    val controls: DeviceControls,
    val devices: Flow<DevicesUi>? = null,
)

/**
 * Builds the ride session (ADR 0005). [SimulatedRideSessionFactory] is a `RideEngine` over `SimulatedBike`;
 * [BleRideSessionFactory] wires the same engine to the long-lived Bluetooth devices (ADR 0010), and nothing above this
 * interface changes. [DeviceSourceFactory] picks one by the developer setting.
 */
fun interface RideSessionFactory {
    fun create(request: RideRequest): RideSessionHandle
}

/** The gears of a ride from the settings: count, neutral gear and grade per gear. */
fun AppSettings.toGearSettings(): GearSettings =
    GearSettings(gearCount = gearCount, neutralGear = neutralGear, gradePercentPerGear = gradePerGear)

/** What the engine rides with, from the settings: FTP, the physics of speed and distance, and the gears. */
fun AppSettings.toRideSettings(): RideSettings =
    RideSettings(
        ftp = ftp,
        physics =
            PhysicsSettings(
                systemMassKg = massKg,
                cdA = cdA,
                crr = crr,
                airDensity = airDensity,
                drivetrainEfficiency = drivetrainEfficiency,
            ),
        gears = toGearSettings(),
    )

/**
 * Chooses the device layer of a ride by the developer setting "Devices" (ADR 0010): the real Bluetooth devices, which is
 * the default, or the simulation. **There is no fallback between them**: a ride over Bluetooth that finds no trainer waits
 * for one, it never quietly becomes a simulated ride.
 */
class DeviceSourceFactory(
    private val bluetooth: RideSessionFactory,
    private val simulated: RideSessionFactory,
) : RideSessionFactory {
    override fun create(request: RideRequest): RideSessionHandle =
        when (request.settings.deviceSource) {
            DeviceSource.Bluetooth -> bluetooth.create(request)
            DeviceSource.Simulated -> simulated.create(request)
        }
}

/**
 * A real [RideEngine] driven by a simulated rider on a simulated trainer and strap, with the rider's settings.
 * [RideSettings.ftp], the physics and the gears are the real ones, so what the owner feels and sees in the simulation
 * is what the engine will do with real devices; only the devices are fake.
 *
 * The developer settings change the clock (demo speed shortens one ride second) and add scripted failures
 * ([scriptedDropouts]) so the "—" and pause states can be seen without waiting.
 */
class SimulatedRideSessionFactory : RideSessionFactory {
    override fun create(request: RideRequest): RideSessionHandle {
        val settings = request.settings
        val tick = 1.seconds / settings.demoSpeed.factor
        val gears = settings.toGearSettings()
        val rideSettings = settings.toRideSettings()
        val bike =
            SimulatedBike(
                scope = request.scope,
                ftp = settings.ftp,
                seed = request.rideNumber.toLong(),
                scenario =
                    SimulationScenario(
                        dropouts = if (settings.scriptedDropouts) SCRIPTED_DROPOUTS else emptyList(),
                    ),
                events = if (settings.scriptedDropouts) SCRIPTED_EVENTS else emptyList(),
                gears = gears,
                tickInterval = tick,
            )
        val engine =
            RideEngine(
                scope = request.scope,
                trainer = bike.trainer,
                heartRate = bike.heartRate,
                workout = request.workout,
                settings = rideSettings,
                recorder = request.recorder,
                tickInterval = tick,
            )
        // The bike starts before the ride so its readings of second k reach the engine just before its tick k.
        bike.start()
        val controls =
            object : DeviceControls {
                // The simulated devices are single-threaded: touch them on the ride's own scope, not the caller's.
                override fun retryTrainer() {
                    request.scope.launch { bike.trainer.connect() }
                }

                override fun retryHeartRate() {
                    request.scope.launch { bike.heartRate.connect() }
                }
            }
        return RideSessionHandle(engine, bike.trainer, bike.heartRate, controls)
    }

    companion object {
        /** Seconds of sensor time. The heart rate drops first, then cadence, then power for a few seconds. */
        val SCRIPTED_DROPOUTS: List<SignalDropout> =
            listOf(
                SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 30, durationSeconds = 20),
                SignalDropout(SimulatedSignal.CADENCE, startSecond = 75, durationSeconds = 10),
                SignalDropout(SimulatedSignal.POWER, startSecond = 105, durationSeconds = 6),
            )

        /** The trainer's link drops for 12 s, which pauses the ride and resumes it by itself. */
        val SCRIPTED_EVENTS: List<DeviceDisconnect> =
            listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 150, durationSeconds = 12))
    }
}
