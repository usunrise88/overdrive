package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.codec.ControlPointCodec
import app.overdrive.core.ble.codec.ControlPointCommand
import app.overdrive.core.ble.codec.FitnessMachineFeatures
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.ConnectStrategy
import app.overdrive.core.ble.link.LinkControls
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ble.link.LinkSupervisor
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.device.TrainerReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A Fitness Machine Service trainer over a [GattTransportFactory]: a [TrainerDevice] the ride engine uses unchanged
 * (spec §4.1, docs/protocols/ftms-hr.md §4).
 *
 * **Lifecycle.** Construct it, then [start]: it connects, and from then on reconnects by itself with backoff until
 * [close] (a [LinkSupervisor] does that, see there). [connection] is [ConnectionState.CONNECTED] only once the
 * subscriptions are on and control has been requested, so the engine's `READY` means the trainer can be commanded.
 * [retryNow] is the Devices screen's Retry button.
 *
 * **Commands** ([requestControl], [setTargetPower], [setSimulation]) follow the engine's contract in [TrainerDevice]:
 * `requestControl` is cheap when control is already held, a command makes sure control is held first, and any command
 * that fails throws [TrainerCommandException], which the engine retries a second later starting with `requestControl`.
 * One Control Point command is in flight at a time.
 *
 * **Threading.** Safe to call from any coroutine; it uses no dispatcher of its own beyond [scope]'s.
 *
 * @param device the peripheral, as the device store remembers it.
 * @param name the short name for the ride record's device list.
 * @param options the bench-flippable choices, see [FtmsOptions].
 */
public class FtmsTrainerDevice(
    private val scope: CoroutineScope,
    device: DeviceId,
    transports: GattTransportFactory,
    environment: BleEnvironment,
    override val name: String,
    private val options: FtmsOptions = FtmsOptions(),
    reconnect: BackoffPolicy = BackoffPolicy(),
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
    establishTimeout: Duration = LinkSupervisor.DEFAULT_ESTABLISH_TIMEOUT,
    connectStrategy: () -> ConnectStrategy = { ConnectStrategy.LEGACY },
) : TrainerDevice,
    LinkControls {
    private val readings =
        MutableSharedFlow<TrainerReading>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableEvents =
        MutableSharedFlow<TrainerEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableFeatures = MutableStateFlow<FitnessMachineFeatures?>(null)

    private val listener =
        object : FtmsListener {
            override fun onReading(reading: TrainerReading) {
                readings.tryEmit(reading)
            }

            override fun onEvent(event: TrainerEvent) {
                mutableEvents.tryEmit(event)
            }

            override fun onFeatures(features: FitnessMachineFeatures) {
                mutableFeatures.value = features
            }
        }

    private val supervisor =
        LinkSupervisor(
            scope = scope,
            device = device,
            transports = transports,
            environment = environment,
            sessions = {
                transport,
                sessionScope,
                ->
                FtmsSession(transport, sessionScope, options, listener, timeSource)
            },
            policy = reconnect,
            timeSource = timeSource,
            random = random,
            establishTimeout = establishTimeout,
            strategy = connectStrategy,
        )

    override val connection: StateFlow<ConnectionState> get() = supervisor.connection

    override val data: Flow<TrainerReading> = readings.asSharedFlow()

    override val status: StateFlow<LinkStatus> get() = supervisor.status

    /** What the trainer and its link report, for the Devices screen and the log. A hot flow without replay. */
    public val events: SharedFlow<TrainerEvent> = mutableEvents.asSharedFlow()

    /** The trainer's Fitness Machine Feature as last read, or null before the first connection (docs/protocols/ftms-hr.md §3). */
    public val features: StateFlow<FitnessMachineFeatures?> = mutableFeatures.asStateFlow()

    override fun start() {
        supervisor.start()
    }

    override fun retryNow() {
        supervisor.retryNow()
    }

    override fun close() {
        supervisor.close()
    }

    override suspend fun requestControl() {
        session(ControlPointCodec.OPCODE_REQUEST_CONTROL).requestControl()
    }

    override suspend fun setTargetPower(watts: Int) {
        session(ControlPointCodec.OPCODE_SET_TARGET_POWER).send(ControlPointCommand.SetTargetPower(watts))
    }

    override suspend fun setSimulation(parameters: SimulationParameters) {
        session(ControlPointCodec.OPCODE_SET_SIMULATION).send(ControlPointCommand.SetSimulation(parameters))
    }

    private fun session(opcode: Int): FtmsSession =
        supervisor.session.value ?: throw TrainerCommandException(opcode, TrainerCommandException.Kind.NOT_CONNECTED)

    private companion object {
        const val BUFFER = 64
    }
}
