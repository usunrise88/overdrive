package app.overdrive.core.ble.fake

import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.recording.BleDirection
import app.overdrive.core.ble.recording.BleSession
import app.overdrive.core.ble.recording.BleSessionEvent
import app.overdrive.core.ble.recording.BleStates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A peripheral that plays back a recorded or synthetic [BleSession] (spec §7.5, docs/protocols/ftms-hr.md §7.1), from
 * the device's side of the link.
 *
 * **Connections.** The session is cut into *attempts* at its `S connecting` lines. An attempt that reaches `S connected`
 * is a *segment*; one that goes straight to `S disconnected` is a failed connect, replayed as a connect that fails with
 * the recorded status. The *n*-th connect to this peripheral plays the *n*-th attempt, and a connect after the last one
 * gets an idle peripheral. A segment ends at the next `S disconnected` (the peripheral drops the link then, with the
 * recorded status) or `S closed` (the link stays up until the central closes it).
 *
 * **What is played.** Notifications and indications (`N`, `I`) are pushed at their recorded time **since the connection
 * came up**, when the central subscribed to that characteristic. A read (`R`) returns the next recorded value for its
 * characteristic; a write (`W`) is acknowledged with the next recorded status for its characteristic (success when none is
 * left); a CCCD write (`C`) fails with a recorded non-zero status. Service discovery (`D`) lists the recorded
 * characteristics. Timing therefore follows the recording, not the central's behaviour: a recorded Control Point
 * indication is played at its recorded moment, so a replay is exact when the central writes at the same moments it did
 * when recording, which a deterministic test on virtual time does. For tests that drive the trainer differently, use
 * [FakeFtmsTrainer], which answers commands.
 */
public class ReplayPeripheral(
    session: BleSession,
    advertisedName: String? = session.model,
) : FakePeripheral(advertisedName, servicesOf(session)) {
    private class Pushed(
        val offsetNanos: Long,
        val characteristic: GattUuid,
        val bytes: ByteArray,
    )

    /** One connection attempt of the recording: it failed with [failedStatus], or it connected and played [segment]. */
    private class Attempt(
        val failedStatus: Int?,
        val segment: Segment?,
    )

    private class Segment(
        val pushes: List<Pushed>,
        val reads: Map<GattUuid, List<ByteArray>>,
        val writeStatuses: Map<GattUuid, List<Int>>,
        val cccdFailures: List<Pair<GattUuid, Int>>,
        val dropOffsetNanos: Long?,
        val dropStatus: Int,
    )

    private val attempts: List<Attempt> = attemptsOf(session)
    private var attemptIndex = 0
    private var current: Segment? = null
    private val readQueues = mutableMapOf<GattUuid, ArrayDeque<ByteArray>>()
    private val writeQueues = mutableMapOf<GattUuid, ArrayDeque<Int>>()

    override val characteristics: List<GattCharacteristicInfo> = characteristicsOf(session)

    /** How many connections that came up the session holds. */
    public val segmentCount: Int get() = attempts.count { it.segment != null }

    /** How many connection attempts the session holds, the failed ones included. */
    public val attemptCount: Int get() = attempts.size

    override fun onConnectAttempt(): Int? {
        val attempt = attempts.getOrNull(attemptIndex++)
        current = attempt?.segment
        return attempt?.failedStatus
    }

    override fun onConnected(connection: FakeConnection) {
        val segment = current ?: return
        readQueues.clear()
        writeQueues.clear()
        segment.reads.forEach { (key, values) -> readQueues[key] = ArrayDeque(values) }
        segment.writeStatuses.forEach { (key, values) -> writeQueues[key] = ArrayDeque(values) }
        segment.cccdFailures.forEach { (key, status) -> faults.failNextCccdWrites(1, status, key) }
        connection.scope.launch {
            var played = 0L
            for (push in segment.pushes) {
                delay((push.offsetNanos - played).coerceAtLeast(0).nanoseconds)
                played = maxOf(played, push.offsetNanos)
                connection.push(push.characteristic, push.bytes)
            }
        }
        segment.dropOffsetNanos?.let { offset ->
            connection.scope.launch {
                delay(offset.nanoseconds)
                connection.drop(segment.dropStatus)
            }
        }
    }

    override fun onRead(characteristic: GattUuid): ByteArray? = readQueues[characteristic]?.removeFirstOrNull()

    override fun onWrite(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int = writeQueues[characteristic]?.removeFirstOrNull() ?: GattStatus.SUCCESS

    private companion object {
        const val DEFAULT_DROP_STATUS = 8

        fun servicesOf(session: BleSession): Set<GattUuid> = characteristicsOf(session).map { it.service }.toSet()

        fun characteristicsOf(session: BleSession): List<GattCharacteristicInfo> {
            val discovered =
                session.events
                    .filter { it.direction == BleDirection.DISCOVERY }
                    .mapNotNull { event ->
                        val characteristic = event.characteristic ?: return@mapNotNull null
                        val (service, properties) = parseDiscovery(event.data) ?: return@mapNotNull null
                        GattCharacteristicInfo(service, characteristic, properties)
                    }.distinct()
            if (discovered.isNotEmpty()) return discovered
            // A hand-written session without discovery lines: infer what the known services would offer.
            return session.events
                .mapNotNull { it.characteristic }
                .distinct()
                .mapNotNull { inferred(it) }
        }

        fun inferred(characteristic: GattUuid): GattCharacteristicInfo? =
            when (characteristic) {
                FtmsUuids.INDOOR_BIKE_DATA, FtmsUuids.STATUS -> {
                    GattCharacteristicInfo(FtmsUuids.SERVICE, characteristic, setOf(GattProperty.NOTIFY))
                }

                FtmsUuids.CONTROL_POINT -> {
                    GattCharacteristicInfo(
                        FtmsUuids.SERVICE,
                        characteristic,
                        setOf(GattProperty.WRITE, GattProperty.INDICATE),
                    )
                }

                FtmsUuids.FEATURE -> {
                    GattCharacteristicInfo(FtmsUuids.SERVICE, characteristic, setOf(GattProperty.READ))
                }

                HeartRateUuids.MEASUREMENT -> {
                    GattCharacteristicInfo(HeartRateUuids.SERVICE, characteristic, setOf(GattProperty.NOTIFY))
                }

                else -> {
                    null
                }
            }

        /** `1826/WI` into the service and the properties. */
        fun parseDiscovery(data: String): Pair<GattUuid, Set<GattProperty>>? {
            val parts = data.split('/')
            if (parts.size != 2) return null
            val service = GattUuid.parse(parts[0]) ?: return null
            val properties =
                parts[1]
                    .mapNotNull {
                        when (it) {
                            'R' -> GattProperty.READ
                            'W' -> GattProperty.WRITE
                            'w' -> GattProperty.WRITE_WITHOUT_RESPONSE
                            'N' -> GattProperty.NOTIFY
                            'I' -> GattProperty.INDICATE
                            else -> null
                        }
                    }.toSet()
            return service to properties
        }

        fun attemptsOf(session: BleSession): List<Attempt> {
            val attempts = mutableListOf<Attempt>()
            var builder: SegmentBuilder? = null
            var connecting = false
            for (event in session.events.sortedBy { it.timeNanos }) {
                if (event.direction != BleDirection.STATE) {
                    builder?.add(event)
                    continue
                }
                when (event.data) {
                    BleStates.CONNECTING -> {
                        builder?.let { attempts += Attempt(null, it.build(null, 0)) }
                        builder = null
                        connecting = true
                    }

                    BleStates.CONNECTED -> {
                        builder?.let { attempts += Attempt(null, it.build(null, 0)) }
                        builder = SegmentBuilder(event.timeNanos)
                        connecting = false
                    }

                    BleStates.DISCONNECTED -> {
                        val open = builder
                        if (open != null) {
                            attempts +=
                                Attempt(
                                    null,
                                    open.build(
                                        event.timeNanos - open.startNanos,
                                        event.status ?: DEFAULT_DROP_STATUS,
                                    ),
                                )
                            builder = null
                        } else if (connecting) {
                            attempts += Attempt(event.status ?: GattStatus.GATT_ERROR, null)
                        }
                        connecting = false
                    }

                    BleStates.CLOSED -> {
                        builder?.let { attempts += Attempt(null, it.build(null, 0)) }
                        builder = null
                        connecting = false
                    }

                    else -> {
                        Unit
                    }
                }
            }
            builder?.let { attempts += Attempt(null, it.build(null, 0)) }
            return attempts
        }
    }

    private class SegmentBuilder(
        val startNanos: Long,
    ) {
        private val pushes = mutableListOf<Pushed>()
        private val reads = mutableMapOf<GattUuid, MutableList<ByteArray>>()
        private val writes = mutableMapOf<GattUuid, MutableList<Int>>()
        private val cccd = mutableListOf<Pair<GattUuid, Int>>()

        fun add(event: BleSessionEvent) {
            val characteristic = event.characteristic ?: return
            val offset = event.timeNanos - startNanos
            when (event.direction) {
                BleDirection.NOTIFICATION, BleDirection.INDICATION -> {
                    event.bytes()?.let { pushes += Pushed(offset, characteristic, it) }
                }

                BleDirection.READ -> {
                    reads.getOrPut(characteristic) { mutableListOf() } +=
                        (event.bytes() ?: ByteArray(0))
                }

                BleDirection.WRITE -> {
                    writes.getOrPut(characteristic) { mutableListOf() } +=
                        (event.status ?: GattStatus.SUCCESS)
                }

                BleDirection.CCCD -> {
                    val status = event.status ?: GattStatus.SUCCESS
                    if (status != GattStatus.SUCCESS) cccd += characteristic to status
                }

                BleDirection.STATE, BleDirection.DISCOVERY -> {
                    Unit
                }
            }
        }

        fun build(
            dropOffset: Long?,
            dropStatus: Int,
        ): Segment = Segment(pushes.sortedBy { it.offsetNanos }, reads, writes, cccd, dropOffset, dropStatus)
    }
}
