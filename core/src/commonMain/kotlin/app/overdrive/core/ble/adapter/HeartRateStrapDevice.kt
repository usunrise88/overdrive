package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.codec.DecodeError
import app.overdrive.core.ble.codec.DecodeResult
import app.overdrive.core.ble.codec.HeartRateCodec
import app.overdrive.core.ble.codec.HeartRateContactRule
import app.overdrive.core.ble.codec.HeartRateMeasurement
import app.overdrive.core.ble.codec.SensorContact
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.LinkControls
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ble.link.LinkSupervisor
import app.overdrive.core.ble.link.PeripheralSession
import app.overdrive.core.ble.link.SessionOutcome
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateDevice
import app.overdrive.core.device.HeartRateReading
import app.overdrive.core.device.RrIntervals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The choices about the heart-rate strap that only the bench settles (docs/protocols/ftms-hr.md §6, §9).
 */
public class HeartRateOptions(
    /** How the two contact bits are read (§6.5). **FT 11.** */
    public val contactRule: HeartRateContactRule = HeartRateContactRule.DEFAULT,
    /**
     * Report 0 bpm, and no RR intervals, while the strap says it has contact support and no contact (status 2), so the
     * engine records a gap instead of the stale or noisy number a strap off the chest sends (ADR 0004: 0 bpm is "no
     * signal"). **FT 11.**
     */
    public val noSignalWithoutContact: Boolean = true,
    /** A reading above this is not a heart rate (a 16-bit field can say 65535) and is reported as 0, "no signal". */
    public val maxPlausibleBpm: Int = DEFAULT_MAX_BPM,
    /** How long any single GATT operation may take before it counts as failed. */
    public val operationTimeout: Duration = 10.seconds,
) {
    public companion object {
        /** Above any living rider's maximum. */
        public const val DEFAULT_MAX_BPM: Int = 250
    }
}

/** Something the strap's link reported. */
public sealed interface StrapEvent {
    /** A notification could not be decoded and was dropped. */
    public data class MalformedNotification(
        val error: DecodeError,
    ) : StrapEvent

    /** A notification had an odd byte after the last whole RR interval, which was ignored (§6.3). */
    public data object StrayRrByte : StrapEvent
}

/**
 * A heart-rate strap (Heart Rate Service) over a [GattTransportFactory]: a [HeartRateDevice] the ride engine uses
 * unchanged (spec §4.1, docs/protocols/ftms-hr.md §6). A strap that drops or is taken off is a gap in the record and
 * never stops the ride; it reconnects by itself with backoff after [start].
 *
 * Each Heart Rate Measurement becomes one [HeartRateReading]: the bpm, and the RR intervals converted from 1/1024 s to
 * whole milliseconds by [RrIntervals] (the domain layer owns the rounding rule). A strap that reports "no contact"
 * reads as 0 bpm, see [HeartRateOptions.noSignalWithoutContact].
 */
public class HeartRateStrapDevice(
    scope: CoroutineScope,
    device: DeviceId,
    transports: GattTransportFactory,
    environment: BleEnvironment,
    override val name: String,
    private val options: HeartRateOptions = HeartRateOptions(),
    reconnect: BackoffPolicy = BackoffPolicy(),
    timeSource: TimeSource = TimeSource.Monotonic,
    random: Random = Random.Default,
    establishTimeout: Duration = LinkSupervisor.DEFAULT_ESTABLISH_TIMEOUT,
) : HeartRateDevice,
    LinkControls {
    private val readings =
        MutableSharedFlow<HeartRateReading>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableEvents =
        MutableSharedFlow<StrapEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableContact = MutableStateFlow<SensorContact?>(null)

    private val supervisor =
        LinkSupervisor(
            scope = scope,
            device = device,
            transports = transports,
            environment = environment,
            sessions = { transport, sessionScope -> HeartRateSession(transport, sessionScope) },
            policy = reconnect,
            timeSource = timeSource,
            random = random,
            establishTimeout = establishTimeout,
        )

    override val connection: StateFlow<ConnectionState> get() = supervisor.connection

    override val data: Flow<HeartRateReading> = readings.asSharedFlow()

    override val status: StateFlow<LinkStatus> get() = supervisor.status

    /** The strap's last reported skin-contact state, or null before the first notification: "put the strap on". */
    public val contact: StateFlow<SensorContact?> = mutableContact.asStateFlow()

    /** What the link reported. A hot flow without replay. */
    public val events: SharedFlow<StrapEvent> = mutableEvents.asSharedFlow()

    override fun start() {
        supervisor.start()
    }

    override fun retryNow() {
        supervisor.retryNow()
    }

    override fun close() {
        supervisor.close()
    }

    /** One connection to the strap: subscribe to the Heart Rate Measurement and decode what arrives. */
    private inner class HeartRateSession(
        private val transport: GattTransport,
        private val scope: CoroutineScope,
    ) : PeripheralSession {
        private val io = GattIo(transport, options.operationTimeout)

        override suspend fun establish(): SessionOutcome {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.notifications.collect(::onNotification) }
            val services =
                when (val found = io.run { discoverServices() }) {
                    is GattResult.Ok -> found.value

                    is GattResult.Failed -> return SessionOutcome.Failed(
                        setupFailure("discover services", found.failure),
                    )
                }
            val measurement =
                services.find(HeartRateUuids.SERVICE, HeartRateUuids.MEASUREMENT)
                    ?: return SessionOutcome.Failed(
                        LinkFailure(
                            LinkFailureKind.SERVICE_MISSING,
                            detail = "The device has no Heart Rate Measurement",
                        ),
                    )
            return when (
                val enabled =
                    io.run {
                        enable(
                            measurement,
                            updateModeFor(measurement, ValueUpdate.NOTIFICATION),
                        )
                    }
            ) {
                is GattResult.Ok -> {
                    SessionOutcome.Ready
                }

                is GattResult.Failed -> {
                    SessionOutcome.Failed(
                        setupFailure("enable Heart Rate Measurement notifications", enabled.failure),
                    )
                }
            }
        }

        override fun linkLost() {
            // A strap that goes away has no contact to report; the next connection says again.
            mutableContact.value = null
        }

        private fun onNotification(notification: GattNotification) {
            if (notification.characteristic != HeartRateUuids.MEASUREMENT) return
            when (val parsed = HeartRateCodec.parse(notification.value, options.contactRule)) {
                is DecodeResult.Malformed -> {
                    mutableEvents.tryEmit(StrapEvent.MalformedNotification(parsed.error))
                }

                is DecodeResult.Ok -> {
                    if (parsed.value.strayRrByte) mutableEvents.tryEmit(StrapEvent.StrayRrByte)
                    mutableContact.value = parsed.value.contact
                    readings.tryEmit(toReading(parsed.value))
                }
            }
        }

        private fun toReading(measurement: HeartRateMeasurement): HeartRateReading {
            val offChest = options.noSignalWithoutContact && measurement.contact == SensorContact.NOT_DETECTED
            val implausible = measurement.bpm > options.maxPlausibleBpm
            if (offChest || implausible) return HeartRateReading(bpm = 0)
            return HeartRateReading(
                bpm = measurement.bpm,
                rrIntervalsMs = measurement.rrIntervalsRaw?.let(RrIntervals::toMillis).orEmpty(),
            )
        }
    }

    private companion object {
        const val BUFFER = 64
    }
}
