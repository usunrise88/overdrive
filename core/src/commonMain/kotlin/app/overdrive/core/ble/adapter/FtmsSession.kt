package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.adapter.TrainerCommandException.Kind
import app.overdrive.core.ble.codec.ControlPointCodec
import app.overdrive.core.ble.codec.ControlPointCommand
import app.overdrive.core.ble.codec.ControlPointResponse
import app.overdrive.core.ble.codec.ControlPointResult
import app.overdrive.core.ble.codec.DecodeResult
import app.overdrive.core.ble.codec.FitnessMachineFeatureCodec
import app.overdrive.core.ble.codec.FitnessMachineFeatures
import app.overdrive.core.ble.codec.FitnessMachineStatus
import app.overdrive.core.ble.codec.FitnessMachineStatusCodec
import app.overdrive.core.ble.codec.IndoorBikeData
import app.overdrive.core.ble.codec.IndoorBikeDataCodec
import app.overdrive.core.ble.failureOrNull
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.PeripheralSession
import app.overdrive.core.ble.link.SessionOutcome
import app.overdrive.core.device.TrainerReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

/** What the session tells the device object that owns it. */
internal interface FtmsListener {
    fun onReading(reading: TrainerReading)

    fun onEvent(event: TrainerEvent)

    fun onFeatures(features: FitnessMachineFeatures)
}

/**
 * One FTMS connection (docs/protocols/ftms-hr.md §4.1): set-up in the order of the notes, then the Control Point
 * procedure, one command in flight at a time, each answered by a Response Code indication.
 *
 * **Set-up.** Discover; require Indoor Bike Data and the Control Point; ask for an MTU; read Feature; enable the Control
 * Point (indications), Indoor Bike Data (notifications) and Status (notifications) **one CCCD write at a time**; take
 * control. A missing Status or a failed Feature read does not stop the connection; a missing Indoor Bike Data or
 * Control Point, or a failed subscription to either, does.
 *
 * **Control.** Request Control (and Start or Resume, see [FtmsOptions.startAfterRequestControl]) runs once per
 * connection, and again after the trainer says control is lost. Every command first makes sure control is held.
 * A Response Code other than success is handled as in §4.4: operation failed is retried a few times, control not
 * permitted takes control again once, the others fail the command.
 */
internal class FtmsSession(
    private val transport: GattTransport,
    private val scope: CoroutineScope,
    private val options: FtmsOptions,
    private val listener: FtmsListener,
) : PeripheralSession {
    private class Pending(
        val opcode: Int,
        val response: CompletableDeferred<ControlPointResponse>,
    )

    private val io = GattIo(transport, options.operationTimeout)
    private val controlPointLock = Mutex()
    private val control = MutableStateFlow(false)
    private val pending = MutableStateFlow<Pending?>(null)
    private var controlPoint: GattCharacteristicInfo? = null
    private var features: FitnessMachineFeatures? = null

    // Touched only while [controlPointLock] is held.
    private val unsupported = mutableSetOf<Int>()
    private var lastOrder: ControlPointCommand? = null

    // The target power most recently put on the wire, before its answer: the status echo can arrive first.
    private var sentTargetWatts: Int? = null
    private var recovery: Job? = null

    override suspend fun establish(): SessionOutcome {
        // Collect before enabling anything: the first indication can arrive before the CCCD write is acknowledged.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.notifications.collect(::onNotification) }

        val services =
            when (val found = io.run { discoverServices() }) {
                is GattResult.Ok -> found.value
                is GattResult.Failed -> return SessionOutcome.Failed(setupFailure("discover services", found.failure))
            }
        val bike =
            services.find(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA)
                ?: return missing("Indoor Bike Data")
        val point =
            services.find(FtmsUuids.SERVICE, FtmsUuids.CONTROL_POINT)
                ?: return missing("Control Point")
        controlPoint = point

        when (val mtu = io.run { requestMtu(options.requestedMtu) }) {
            is GattResult.Failed -> listener.onEvent(TrainerEvent.OptionalStepFailed("request MTU", mtu.failure))
            is GattResult.Ok -> Unit
        }
        services.find(FtmsUuids.SERVICE, FtmsUuids.FEATURE)?.let { readFeatures(it) }

        subscribe(point, ValueUpdate.INDICATION)?.let {
            return SessionOutcome.Failed(setupFailure("enable Control Point indications", it))
        }
        subscribe(bike, ValueUpdate.NOTIFICATION)?.let {
            return SessionOutcome.Failed(setupFailure("enable Indoor Bike Data notifications", it))
        }
        services.find(FtmsUuids.SERVICE, FtmsUuids.STATUS)?.let { status ->
            subscribe(status, ValueUpdate.NOTIFICATION)?.let {
                listener.onEvent(TrainerEvent.OptionalStepFailed("enable Status notifications", it))
            }
        }

        // The engine asks again, but "after every (re)connect" is the adapter's duty, not the engine's habit.
        try {
            requestControl()
        } catch (e: TrainerCommandException) {
            listener.onEvent(TrainerEvent.ControlRequestFailed(e))
        }
        return SessionOutcome.Ready
    }

    override fun linkLost() {
        control.value = false
        val waiting = pending.value
        waiting?.response?.completeExceptionally(
            TrainerCommandException(waiting.opcode, Kind.NOT_CONNECTED),
        )
        recovery?.cancel()
    }

    private fun missing(what: String) =
        SessionOutcome.Failed(LinkFailure(LinkFailureKind.SERVICE_MISSING, detail = "The trainer has no $what"))

    private suspend fun readFeatures(info: GattCharacteristicInfo) {
        when (val read = io.run { read(info) }) {
            is GattResult.Failed -> {
                listener.onEvent(TrainerEvent.OptionalStepFailed("read Fitness Machine Feature", read.failure))
            }

            is GattResult.Ok -> {
                when (val parsed = FitnessMachineFeatureCodec.parse(read.value)) {
                    is DecodeResult.Malformed -> {
                        listener.onEvent(TrainerEvent.MalformedNotification(FtmsUuids.FEATURE, parsed.error))
                    }

                    is DecodeResult.Ok -> {
                        features = parsed.value
                        listener.onFeatures(parsed.value)
                        val missing = parsed.value.missingForRide
                        if (missing.isNotEmpty()) listener.onEvent(TrainerEvent.FeaturesMissing(missing))
                    }
                }
            }
        }
    }

    /** One CCCD write; returns the failure if it failed. The lock inside [io] keeps these strictly one at a time. */
    private suspend fun subscribe(
        info: GattCharacteristicInfo,
        preferred: ValueUpdate,
    ): GattFailure? = io.run { enable(info, updateModeFor(info, preferred)) }.failureOrNull()

    // region Notifications

    private fun onNotification(notification: GattNotification) {
        when (notification.characteristic) {
            FtmsUuids.INDOOR_BIKE_DATA -> onBikeData(notification.value)
            FtmsUuids.CONTROL_POINT -> onControlPointResponse(notification.value)
            FtmsUuids.STATUS -> onStatus(notification.value)
            else -> Unit
        }
    }

    private fun onBikeData(bytes: ByteArray) {
        when (val parsed = IndoorBikeDataCodec.parse(bytes, options.flagMap)) {
            is DecodeResult.Malformed -> {
                listener.onEvent(TrainerEvent.MalformedNotification(FtmsUuids.INDOOR_BIKE_DATA, parsed.error))
            }

            is DecodeResult.Ok -> {
                if (parsed.value.moreData) listener.onEvent(TrainerEvent.MoreDataSeen)
                listener.onReading(toReading(parsed.value))
            }
        }
    }

    private fun onControlPointResponse(bytes: ByteArray) {
        when (val parsed = ControlPointCodec.parseResponse(bytes)) {
            is DecodeResult.Malformed -> {
                listener.onEvent(TrainerEvent.MalformedNotification(FtmsUuids.CONTROL_POINT, parsed.error))
            }

            is DecodeResult.Ok -> {
                val waiting = pending.value
                if (waiting != null && waiting.opcode == parsed.value.requestOpcode) {
                    waiting.response.complete(parsed.value)
                }
            }
        }
    }

    private fun onStatus(bytes: ByteArray) {
        when (val parsed = FitnessMachineStatusCodec.parse(bytes, options.simulationStatusSigned)) {
            is DecodeResult.Malformed -> {
                listener.onEvent(TrainerEvent.MalformedNotification(FtmsUuids.STATUS, parsed.error))
            }

            is DecodeResult.Ok -> {
                val status = parsed.value
                listener.onEvent(TrainerEvent.StatusReceived(status))
                when (status) {
                    FitnessMachineStatus.Reset, FitnessMachineStatus.ControlPermissionLost -> {
                        control.value = false
                        listener.onEvent(TrainerEvent.ControlLost(status))
                        scheduleRecovery()
                    }

                    is FitnessMachineStatus.TargetPowerChanged -> {
                        val sent = sentTargetWatts
                        if (sent != status.watts) listener.onEvent(TrainerEvent.TargetPowerAltered(sent, status.watts))
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    // endregion

    // region Control Point

    /** Takes control unless this connection already holds it. Throws [TrainerCommandException] when it cannot. */
    suspend fun requestControl() {
        controlPointLock.withLock {
            if (!control.value) requestControlLocked()
        }
    }

    /** Sends [command] once control is held, handling the trainer's answer as §4.4 says. */
    suspend fun send(command: ControlPointCommand) {
        if (options.enforceFeatureBits) checkFeatures(command)
        controlPointLock.withLock { executeLocked(command) }
    }

    private fun checkFeatures(command: ControlPointCommand) {
        val known = features ?: return
        val lacking =
            when (command) {
                is ControlPointCommand.SetTargetPower -> !known.powerTargetSettingSupported
                is ControlPointCommand.SetSimulation -> !known.indoorBikeSimulationSupported
                else -> false
            }
        if (lacking) throw TrainerCommandException(command.opcode, Kind.FEATURE_MISSING)
    }

    private suspend fun requestControlLocked() {
        val response = transact(ControlPointCommand.RequestControl)
        if (!response.isSuccess) {
            listener.onEvent(TrainerEvent.CommandRejected(response.requestOpcode, response.resultCode))
            throw TrainerCommandException(response.requestOpcode, Kind.REJECTED, response.resultCode)
        }
        control.value = true
        listener.onEvent(TrainerEvent.ControlGranted)
        if (options.startAfterRequestControl && ControlPointCodec.OPCODE_START_OR_RESUME !in unsupported) {
            try {
                val started = transact(ControlPointCommand.StartOrResume)
                if (!started.isSuccess) {
                    if (started.result == ControlPointResult.OP_CODE_NOT_SUPPORTED) {
                        unsupported += ControlPointCodec.OPCODE_START_OR_RESUME
                    }
                    listener.onEvent(TrainerEvent.CommandRejected(started.requestOpcode, started.resultCode))
                }
            } catch (e: TrainerCommandException) {
                // A silent or broken link is a failure of the whole procedure; the engine starts over.
                control.value = false
                throw e
            }
        }
    }

    private suspend fun executeLocked(command: ControlPointCommand) {
        if (command.opcode in unsupported) {
            throw TrainerCommandException(command.opcode, Kind.UNSUPPORTED)
        }
        if (!control.value) requestControlLocked()
        var controlRetried = false
        var operationFailures = 0
        while (true) {
            val response = transact(command)
            when (response.result) {
                ControlPointResult.SUCCESS -> {
                    remember(command)
                    return
                }

                ControlPointResult.CONTROL_NOT_PERMITTED -> {
                    control.value = false
                    listener.onEvent(TrainerEvent.CommandRejected(command.opcode, response.resultCode))
                    if (controlRetried) {
                        throw TrainerCommandException(
                            command.opcode,
                            Kind.NOT_UNDER_CONTROL,
                            response.resultCode,
                        )
                    }
                    controlRetried = true
                    requestControlLocked()
                }

                ControlPointResult.OPERATION_FAILED -> {
                    listener.onEvent(TrainerEvent.CommandRejected(command.opcode, response.resultCode))
                    if (operationFailures++ >= options.operationFailedRetries) {
                        throw TrainerCommandException(command.opcode, Kind.REJECTED, response.resultCode)
                    }
                    delay(options.operationFailedBackoff)
                }

                ControlPointResult.OP_CODE_NOT_SUPPORTED -> {
                    unsupported += command.opcode
                    listener.onEvent(TrainerEvent.CommandRejected(command.opcode, response.resultCode))
                    throw TrainerCommandException(command.opcode, Kind.UNSUPPORTED, response.resultCode)
                }

                ControlPointResult.INVALID_PARAMETER, ControlPointResult.UNKNOWN -> {
                    listener.onEvent(TrainerEvent.CommandRejected(command.opcode, response.resultCode))
                    throw TrainerCommandException(command.opcode, Kind.REJECTED, response.resultCode)
                }
            }
        }
    }

    private fun remember(command: ControlPointCommand) {
        if (command is ControlPointCommand.SetTargetPower || command is ControlPointCommand.SetSimulation) {
            lastOrder = command
        }
    }

    /**
     * One request and its response, with [controlPointLock] held by the caller: the write, then the matching Response Code
     * indication within [FtmsOptions.controlPointTimeout]. A late response for an earlier, timed-out command is ignored
     * unless it names the opcode now pending (a limit of the protocol, see the ADR).
     */
    private suspend fun transact(command: ControlPointCommand): ControlPointResponse {
        val point = controlPoint ?: throw TrainerCommandException(command.opcode, Kind.NOT_CONNECTED)
        val waiting = Pending(command.opcode, CompletableDeferred())
        pending.value = waiting
        if (command is ControlPointCommand.SetTargetPower) sentTargetWatts = command.watts
        try {
            when (val write = io.run { write(point, ControlPointCodec.encode(command)) }) {
                is GattResult.Ok -> {
                    Unit
                }

                is GattResult.Failed -> {
                    control.value = false
                    val kind =
                        when (write.failure.kind) {
                            GattFailureKind.DISCONNECTED, GattFailureKind.CLOSED -> Kind.NOT_CONNECTED
                            else -> Kind.WRITE_FAILED
                        }
                    throw TrainerCommandException(command.opcode, kind)
                }
            }
            return withTimeoutOrNull(options.controlPointTimeout) { waiting.response.await() }
                ?: run {
                    // The state of the trainer is unknown: the next command starts over with Request Control.
                    control.value = false
                    throw TrainerCommandException(command.opcode, Kind.TIMEOUT)
                }
        } finally {
            pending.value = null
        }
    }

    /** After a Status that says control is gone: take it back and re-send the last target, once at a time. */
    private fun scheduleRecovery() {
        if (recovery?.isActive == true) return
        recovery =
            scope.launch {
                delay(options.controlRecoveryDelay)
                try {
                    controlPointLock.withLock {
                        if (!control.value) requestControlLocked()
                        lastOrder?.let { executeLocked(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TrainerCommandException) {
                    listener.onEvent(TrainerEvent.ControlRequestFailed(e))
                }
            }
    }

    // endregion

    private companion object {
        fun toReading(data: IndoorBikeData): TrainerReading =
            TrainerReading(
                // A trainer may report a small negative power while coasting; the reading does not allow it.
                power = data.instantaneousPowerWatts?.coerceAtLeast(0),
                cadence = data.instantaneousCadenceRpm?.let { floor(it + HALF).toInt() },
                speedKmh = data.instantaneousSpeedKmh,
            )

        const val HALF = 0.5
    }
}
