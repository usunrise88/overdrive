package app.overdrive.core.device

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The link of a simulated device: a connection state that can also be powered off for good. */
internal class SimulatedLink(
    initial: ConnectionState,
) {
    val state = MutableStateFlow(initial)

    var poweredOn = true
        private set

    val isConnected: Boolean get() = state.value == ConnectionState.CONNECTED

    fun connect() {
        if (poweredOn) state.value = ConnectionState.CONNECTED
    }

    fun startConnecting() {
        if (poweredOn) state.value = ConnectionState.CONNECTING
    }

    fun disconnect() {
        state.value = ConnectionState.DISCONNECTED
    }

    fun powerOff() {
        poweredOn = false
        state.value = ConnectionState.DISCONNECTED
    }

    fun powerOn() {
        poweredOn = true
        state.value = ConnectionState.CONNECTED
    }
}

private fun <T> newNotificationFlow() =
    MutableSharedFlow<T>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/**
 * A [TrainerDevice] without hardware (spec §7.5), driven by hand: a test or a scenario ([SimulatedBike])
 * connects it, feeds it readings with [emit] and reads back what the engine commanded.
 *
 * It behaves like a strict FTMS trainer: a command on a link that is not connected, or any target before
 * [requestControl], throws, and a disconnect drops control. So a test that sees the right commands has also
 * seen the engine start over with Request Control after every reconnect. Readings emitted while not
 * connected are dropped, as a real link would drop them.
 *
 * Not thread-safe: use it from one coroutine dispatcher, such as a test dispatcher.
 *
 * @param clock reads the time stamped on each received command, so a test can assert *when* the engine
 * sent a target; with a test scheduler pass `{ testScheduler.currentTime }`.
 */
public class SimulatedTrainerDevice(
    override val name: String = "Simulated trainer",
    initialConnection: ConnectionState = ConnectionState.DISCONNECTED,
    private val clock: () -> Long = { 0L },
) : TrainerDevice {
    /** A command the trainer accepted, with the [clock] time it arrived. */
    public data class Received(
        val timeMillis: Long,
        val command: TrainerCommand,
    )

    private val link = SimulatedLink(initialConnection)
    private val notifications = newNotificationFlow<TrainerReading>()
    private val log = mutableListOf<Received>()
    private var failures = 0

    override val connection: StateFlow<ConnectionState> = link.state.asStateFlow()
    override val data: Flow<TrainerReading> = notifications.asSharedFlow()

    /** Every command accepted so far, oldest first. */
    public val received: List<Received> get() = log

    /** The ERG target in force, or null in simulation mode, before any command and after a disconnect. */
    public var targetPowerWatts: Int? = null
        private set

    /** The simulation parameters in force, or null in ERG mode, before any command and after a disconnect. */
    public var simulation: SimulationParameters? = null
        private set

    /** Whether control was requested since the last connect. */
    public var hasControl: Boolean = false
        private set

    /** Number of commands that were refused: while disconnected, without control, or via [failNextCommands]. */
    public var refusedCommands: Int = 0
        private set

    /** The link comes up. Has no effect while the device is powered off. */
    public fun connect() {
        link.connect()
    }

    /** The link is being (re)established. Has no effect while the device is powered off. */
    public fun startConnecting() {
        link.startConnecting()
        resetSession()
    }

    /** The link drops. The device is still on and can [connect] again. Control and targets are lost. */
    public fun disconnect() {
        link.disconnect()
        resetSession()
    }

    /** The trainer loses power (the plug is pulled): disconnected, and [connect] does nothing until [powerOn]. */
    public fun powerOff() {
        link.powerOff()
        resetSession()
    }

    /** The trainer is powered again and connects. */
    public fun powerOn() {
        link.powerOn()
    }

    /** One Indoor Bike Data notification. Dropped unless connected. */
    public fun emit(
        power: Int? = null,
        cadence: Int? = null,
        speedKmh: Double? = null,
    ) {
        if (link.isConnected) notifications.tryEmit(TrainerReading(power, cadence, speedKmh))
    }

    /** The next [count] commands throw (after which commands work again), to try the engine's retry. */
    public fun failNextCommands(count: Int) {
        require(count >= 0) { "Count must not be negative, got $count" }
        failures = count
    }

    override suspend fun requestControl() {
        accept(TrainerCommand.RequestControl)
        hasControl = true
    }

    override suspend fun setTargetPower(watts: Int) {
        accept(TrainerCommand.TargetPower(watts))
        targetPowerWatts = watts
        simulation = null
    }

    override suspend fun setSimulation(parameters: SimulationParameters) {
        accept(TrainerCommand.Simulation(parameters))
        simulation = parameters
        targetPowerWatts = null
    }

    private fun accept(command: TrainerCommand) {
        val problem =
            when {
                failures > 0 -> {
                    failures--
                    "Simulated command failure"
                }

                !link.isConnected -> {
                    "The trainer is not connected"
                }

                command !is TrainerCommand.RequestControl && !hasControl -> {
                    "Control was not requested"
                }

                else -> {
                    null
                }
            }
        if (problem != null) {
            refusedCommands++
            throw IllegalStateException(problem)
        }
        log += Received(clock(), command)
    }

    private fun resetSession() {
        hasControl = false
        targetPowerWatts = null
        simulation = null
    }
}

/**
 * A [HeartRateDevice] without hardware (spec §7.5), driven by hand like [SimulatedTrainerDevice]. Readings
 * emitted while not connected are dropped.
 */
public class SimulatedHeartRateDevice(
    override val name: String = "Simulated heart-rate strap",
    initialConnection: ConnectionState = ConnectionState.DISCONNECTED,
) : HeartRateDevice {
    private val link = SimulatedLink(initialConnection)
    private val notifications = newNotificationFlow<HeartRateReading>()

    override val connection: StateFlow<ConnectionState> = link.state.asStateFlow()
    override val data: Flow<HeartRateReading> = notifications.asSharedFlow()

    /** The link comes up. Has no effect while the device is powered off. */
    public fun connect() {
        link.connect()
    }

    /** The link is being (re)established. Has no effect while the device is powered off. */
    public fun startConnecting() {
        link.startConnecting()
    }

    /** The link drops. The device is still on and can [connect] again. */
    public fun disconnect() {
        link.disconnect()
    }

    /** The strap's battery dies or it is taken off for good: disconnected until [powerOn]. */
    public fun powerOff() {
        link.powerOff()
    }

    /** The strap is on again and connects. */
    public fun powerOn() {
        link.powerOn()
    }

    /** One Heart Rate Measurement notification. Dropped unless connected. */
    public fun emit(
        bpm: Int,
        rrIntervalsMs: List<Int> = emptyList(),
    ) {
        if (link.isConnected) notifications.tryEmit(HeartRateReading(bpm, rrIntervalsMs))
    }
}
