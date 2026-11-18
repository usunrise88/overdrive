package app.overdrive.ride.host

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.device.TrainerReading
import app.overdrive.core.ride.RideEngine
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.PairedDevices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The stand-in for a trainer nobody paired: never connected, never sends a reading, refuses every command. The engine
 * over it stays `IDLE`, so a ride cannot start, and **nothing simulated ever takes its place**: a recorded ride of made-up
 * numbers would be worse than no ride (ADR 0010).
 */
internal object UnpairedTrainer : TrainerDevice {
    override val name: String = DeviceRole.Trainer.fallbackName

    override val connection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)

    override val data: Flow<TrainerReading> = emptyFlow()

    override suspend fun requestControl() = notPaired()

    override suspend fun setTargetPower(watts: Int) = notPaired()

    override suspend fun setSimulation(parameters: SimulationParameters) = notPaired()

    private fun notPaired(): Nothing = throw IllegalStateException("No trainer is paired")
}

/**
 * The ride session over the **real devices** (ADR 0010): the unchanged [RideEngine] over the trainer and the strap the
 * [PairedDevices] hold. A session only uses them. They outlive it: the host throws a ready session away and builds
 * another whenever a workout or a setting changes, and none of that touches a Bluetooth link.
 *
 * - **No trainer paired:** the engine runs over [UnpairedTrainer] and stays `IDLE`; the Ready screen says why.
 * - **A trainer paired, not connected:** the engine is `IDLE` until it connects (the adapter says `CONNECTED` only once the
 *   trainer can be commanded), then `READY`.
 * - **No strap paired:** the ride has no heart rate, which is a gap and not an error (spec §4.1).
 *
 * @param deviceRows the rows of the Devices screen and the top bar, from the same devices.
 */
class BleRideSessionFactory(
    private val devices: PairedDevices,
    private val deviceRows: Flow<DevicesUi>,
) : RideSessionFactory {
    override fun create(request: RideRequest): RideSessionHandle {
        val trainer = devices.trainer.value?.device
        val strap = devices.strap.value?.device
        val trainerDevice: TrainerDevice = trainer ?: UnpairedTrainer
        val engine =
            RideEngine(
                scope = request.scope,
                trainer = trainerDevice,
                heartRate = strap,
                workout = request.workout,
                settings = request.settings.toRideSettings(),
                recorder = request.recorder,
            )
        val controls =
            object : DeviceControls {
                // The devices are looked up at the time of the tap: pairing may have replaced them since this session
                // was built, and a Retry belongs to the device that is paired now.
                override fun retryTrainer() {
                    devices.controls(DeviceRole.Trainer)?.retryNow()
                }

                override fun retryHeartRate() {
                    devices.controls(DeviceRole.Strap)?.retryNow()
                }
            }
        return RideSessionHandle(engine, trainerDevice, strap, controls, deviceRows)
    }
}
