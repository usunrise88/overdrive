package app.overdrive.core.ble.fake

import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.codec.ControlPointCodec
import app.overdrive.core.ble.codec.ControlPointResult
import app.overdrive.core.ble.codec.IndoorBikeData
import app.overdrive.core.ble.codec.IndoorBikeFlagMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a [FakeFtmsTrainer] was written, decoded far enough to assert on. */
public sealed interface FakeTrainerCommand {
    /** When it arrived, on the world's clock, in nanoseconds. */
    public val timeNanos: Long

    public data class RequestControl(
        override val timeNanos: Long,
    ) : FakeTrainerCommand

    public data class Reset(
        override val timeNanos: Long,
    ) : FakeTrainerCommand

    public data class StartOrResume(
        override val timeNanos: Long,
    ) : FakeTrainerCommand

    public data class StopOrPause(
        override val timeNanos: Long,
        val parameter: Int,
    ) : FakeTrainerCommand

    public data class TargetPower(
        override val timeNanos: Long,
        val watts: Int,
    ) : FakeTrainerCommand

    /** Simulation with the raw wire values: wind in 0.001 m/s, grade in 0.01 %, Crr in 0.0001, Cw in 0.01 kg/m. */
    public data class Simulation(
        override val timeNanos: Long,
        val windRaw: Int,
        val gradeRaw: Int,
        val crrRaw: Int,
        val windResistanceRaw: Int,
    ) : FakeTrainerCommand {
        /** The grade in percent. */
        val gradePercent: Double get() = gradeRaw / GRADE_UNITS_PER_PERCENT

        private companion object {
            const val GRADE_UNITS_PER_PERCENT = 100.0
        }
    }

    /** Anything else, as received. */
    public class Unknown(
        override val timeNanos: Long,
        public val bytes: ByteArray,
    ) : FakeTrainerCommand
}

/** The knobs of a [FakeFtmsTrainer]; the defaults are a plausible trainer by the notes. */
public class FakeFtmsTrainerOptions(
    /** Which flag bits it uses for Average Speed and Instantaneous Cadence (docs/protocols/ftms-hr.md §2.4). */
    public val flagMap: IndoorBikeFlagMap = IndoorBikeFlagMap.DEFAULT,
    /** Time between Indoor Bike Data notifications. */
    public val notifyInterval: Duration = 1.seconds,
    /** Time from a Control Point write to its Response Code indication. */
    public val controlPointLatency: Duration = 50.milliseconds,
    /** Whether it sends Fitness Machine Status notifications after commands (FT 4). */
    public val sendsStatus: Boolean = true,
    /** Whether it includes the instantaneous speed field (otherwise "More Data" is set, FT 3). */
    public val includesSpeed: Boolean = true,
    /** The 8 bytes of the Fitness Machine Feature. Default: cadence, distance, HR, time, power; power target, simulation. */
    public val featureBytes: ByteArray = DEFAULT_FEATURE,
    /** How much of the gap to the target power the rider closes per notification: 1.0 is instant, 0.5 a ~2 s lag. */
    public val ergResponse: Double = 1.0,
    /**
     * True: every Request Control is followed by a Fitness Machine Status Reset (`01`) a few milliseconds **after** its
     * Response Code, as the Van Rysel D500 does (docs/bench-reports/2026-11-18-first-ride.md §2, FT 10). The Started status
     * (`04`) after Start or Resume is sent by every fake trainer.
     */
    public val resetsAfterRequestControl: Boolean = false,
    /**
     * Not null: the last status is sent again this often, as the D500 does about every 240 ms (§2); null (the default)
     * sends each status once.
     */
    public val statusRepeatInterval: Duration? = null,
) {
    public companion object {
        /** Vectors FMF1 and FMF2 of docs/protocols/ftms-hr.md §8.6, joined. */
        public val DEFAULT_FEATURE: ByteArray = byteArrayOf(0x06, 0x54, 0x00, 0x00, 0x08, 0x20, 0x00, 0x00)
    }
}

/**
 * A simulated FTMS trainer (spec §7.5): Indoor Bike Data every [FakeFtmsTrainerOptions.notifyInterval] with **power that
 * follows the commands** (ERG: the target; simulation: [simulationPowerWatts]; no pedalling: 0), a Control Point that
 * grants control and answers every command with a Response Code, Fitness Machine Status after commands, and Feature.
 * It is strict like a real trainer: commands other than Request Control need control, control ends with the link, and
 * values reach the central only if it subscribed.
 *
 * Failures are scripted with [faults] (`noControlPointResponse`, `controlPointResult`, `failNextConnects`…) and by
 * setting [cadenceRpm] to 0 or null.
 */
public class FakeFtmsTrainer(
    private val options: FakeFtmsTrainerOptions = FakeFtmsTrainerOptions(),
    advertisedName: String? = "D500",
) : FakePeripheral(advertisedName, setOf(FtmsUuids.SERVICE)) {
    private val written = mutableListOf<FakeTrainerCommand>()
    private var currentPower = 0.0
    private var lastStatus: ByteArray? = null

    /** Every Control Point write received, oldest first, across connections. */
    public val commands: List<FakeTrainerCommand> get() = written

    /** Whether the central holds control on the current link. */
    public var hasControl: Boolean = false
        private set

    /** Whether Start or Resume was received on the current link. */
    public var started: Boolean = false
        private set

    /** The ERG target in force, or null in simulation or before any command. */
    public var targetPowerWatts: Int? = null
        private set

    /** The last simulation command, or null. */
    public var simulation: FakeTrainerCommand.Simulation? = null
        private set

    /** The cadence the simulated rider pedals at; null leaves the field out, 0 is "stopped". */
    public var cadenceRpm: Int? = DEFAULT_CADENCE

    /** The power the rider produces in simulation mode (no target). */
    public var simulationPowerWatts: Int = DEFAULT_SIMULATION_POWER

    /** The power of the last notification. */
    public val powerWatts: Int get() = currentPower.toInt()

    /**
     * Another party takes control away (docs/protocols/ftms-hr.md §5): the trainer forgets control and says so with
     * Fitness Machine Status `0xFF`, if the central subscribed.
     */
    public fun revokeControl() {
        hasControl = false
        connection?.let { status(it, byteArrayOf(STATUS_CONTROL_PERMISSION_LOST.toByte())) }
    }

    override val characteristics: List<GattCharacteristicInfo> =
        listOf(
            GattCharacteristicInfo(FtmsUuids.SERVICE, FtmsUuids.FEATURE, setOf(GattProperty.READ)),
            GattCharacteristicInfo(FtmsUuids.SERVICE, FtmsUuids.INDOOR_BIKE_DATA, setOf(GattProperty.NOTIFY)),
            GattCharacteristicInfo(
                FtmsUuids.SERVICE,
                FtmsUuids.CONTROL_POINT,
                setOf(GattProperty.WRITE, GattProperty.INDICATE),
            ),
            GattCharacteristicInfo(FtmsUuids.SERVICE, FtmsUuids.STATUS, setOf(GattProperty.NOTIFY)),
        )

    override fun onConnected(connection: FakeConnection) {
        hasControl = false
        started = false
        targetPowerWatts = null
        simulation = null
        lastStatus = null
        currentPower = 0.0
        connection.scope.launch {
            while (true) {
                delay(options.notifyInterval)
                sendBikeData(connection)
            }
        }
        options.statusRepeatInterval?.let { interval ->
            connection.scope.launch {
                while (true) {
                    delay(interval)
                    lastStatus?.let { status(connection, it) }
                }
            }
        }
    }

    override fun onDisconnected() {
        // Control, the target and the simulation end with the link.
        hasControl = false
        started = false
        targetPowerWatts = null
        simulation = null
        lastStatus = null
    }

    override fun onRead(characteristic: GattUuid): ByteArray? =
        if (characteristic == FtmsUuids.FEATURE) options.featureBytes else null

    override fun onWrite(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int {
        val link = connection ?: return GattStatus.UNKNOWN
        if (characteristic != FtmsUuids.CONTROL_POINT) return WRITE_NOT_PERMITTED
        val command = parse(value, link.nowNanos())
        written += command
        link.scope.launch {
            delay(options.controlPointLatency)
            respond(link, value, command)
        }
        return GattStatus.SUCCESS
    }

    private fun sendBikeData(link: FakeConnection) {
        val cadence = cadenceRpm
        val goal =
            when {
                cadence == null || cadence == 0 -> 0.0
                else -> (targetPowerWatts ?: simulationPowerWatts).toDouble()
            }
        currentPower =
            if (options.ergResponse >= 1.0 || goal == 0.0) {
                goal
            } else {
                currentPower + (goal - currentPower) * options.ergResponse
            }
        val data =
            IndoorBikeData(
                flags = 0,
                moreData = !options.includesSpeed,
                instantaneousSpeedKmh = if (options.includesSpeed) (cadence ?: 0) * SPEED_PER_RPM else null,
                averageSpeedKmh = null,
                instantaneousCadenceRpm = cadence?.toDouble(),
                averageCadenceRpm = null,
                totalDistanceMetres = null,
                resistanceLevel = null,
                instantaneousPowerWatts = currentPower.toInt(),
                averagePowerWatts = null,
                totalEnergyKcal = null,
                energyPerHourKcal = null,
                energyPerMinuteKcal = null,
                heartRateBpm = null,
                metabolicEquivalent = null,
                elapsedTimeSeconds = null,
                remainingTimeSeconds = null,
            )
        link.push(FtmsUuids.INDOOR_BIKE_DATA, IndoorBikeDataEncoder.encode(data, options.flagMap))
    }

    private fun respond(
        link: FakeConnection,
        raw: ByteArray,
        command: FakeTrainerCommand,
    ) {
        val opcode = raw.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: return
        when (val fault = faults.takeControlPointFault(opcode)) {
            FakeFaults.ControlPointOutcome.Silent -> {
                return
            }

            is FakeFaults.ControlPointOutcome.Result -> {
                answer(link, opcode, fault.code)
                return
            }

            null -> {
                Unit
            }
        }
        if (opcode != OP_REQUEST_CONTROL && !hasControl) {
            answer(link, opcode, ControlPointResult.CONTROL_NOT_PERMITTED.code)
            return
        }
        when (command) {
            is FakeTrainerCommand.RequestControl -> {
                hasControl = true
                if (options.resetsAfterRequestControl) {
                    // After the Response Code, so the adapter has already taken the control it is told it lost.
                    answer(link, opcode, ControlPointResult.SUCCESS.code)
                    link.scope.launch {
                        delay(RESET_AFTER_RESPONSE)
                        status(link, byteArrayOf(STATUS_RESET.toByte()))
                    }
                    return
                }
            }

            is FakeTrainerCommand.Reset -> {
                hasControl = false
                started = false
                targetPowerWatts = null
                simulation = null
                status(link, byteArrayOf(STATUS_RESET.toByte()))
            }

            is FakeTrainerCommand.StartOrResume -> {
                started = true
                status(link, byteArrayOf(STATUS_STARTED.toByte()))
            }

            is FakeTrainerCommand.StopOrPause -> {
                status(link, byteArrayOf(STATUS_STOPPED_OR_PAUSED.toByte(), command.parameter.toByte()))
            }

            is FakeTrainerCommand.TargetPower -> {
                targetPowerWatts = command.watts
                simulation = null
                val watts = command.watts
                status(link, byteArrayOf(STATUS_TARGET_POWER.toByte(), watts.toByte(), (watts shr BITS).toByte()))
            }

            is FakeTrainerCommand.Simulation -> {
                simulation = command
                targetPowerWatts = null
                status(link, raw.copyOf().also { it[0] = STATUS_SIMULATION.toByte() })
            }

            is FakeTrainerCommand.Unknown -> {
                answer(link, opcode, ControlPointResult.OP_CODE_NOT_SUPPORTED.code)
                return
            }
        }
        answer(link, opcode, ControlPointResult.SUCCESS.code)
    }

    private fun answer(
        link: FakeConnection,
        opcode: Int,
        result: Int,
    ) {
        link.push(
            FtmsUuids.CONTROL_POINT,
            byteArrayOf(ControlPointCodec.OPCODE_RESPONSE_CODE.toByte(), opcode.toByte(), result.toByte()),
        )
    }

    private fun status(
        link: FakeConnection,
        bytes: ByteArray,
    ) {
        if (!options.sendsStatus) return
        lastStatus = bytes
        link.push(FtmsUuids.STATUS, bytes)
    }

    private fun parse(
        raw: ByteArray,
        time: Long,
    ): FakeTrainerCommand {
        fun u8(at: Int) = raw[at].toInt() and BYTE_MASK

        fun s16(at: Int) = (u8(at) or (u8(at + 1) shl BITS)).toShort().toInt()
        val opcode = if (raw.isEmpty()) NO_OPCODE else u8(0)
        val size = raw.size
        return when {
            opcode == OP_REQUEST_CONTROL && size == 1 -> {
                FakeTrainerCommand.RequestControl(time)
            }

            opcode == OP_RESET && size == 1 -> {
                FakeTrainerCommand.Reset(time)
            }

            opcode == OP_START && size == 1 -> {
                FakeTrainerCommand.StartOrResume(time)
            }

            opcode == OP_STOP_OR_PAUSE && size == 2 -> {
                FakeTrainerCommand.StopOrPause(time, u8(1))
            }

            opcode == OP_TARGET_POWER && size == 3 -> {
                FakeTrainerCommand.TargetPower(time, s16(1))
            }

            opcode == OP_SIMULATION && size == SIMULATION_SIZE -> {
                FakeTrainerCommand.Simulation(time, s16(1), s16(3), u8(5), u8(6))
            }

            else -> {
                FakeTrainerCommand.Unknown(time, raw)
            }
        }
    }

    private companion object {
        val RESET_AFTER_RESPONSE = 20.milliseconds
        const val DEFAULT_CADENCE = 90
        const val DEFAULT_SIMULATION_POWER = 110
        const val SPEED_PER_RPM = 0.3
        const val BYTE_MASK = 0xFF
        const val BITS = 8
        const val WRITE_NOT_PERMITTED = 3
        const val SIMULATION_SIZE = 7
        const val NO_OPCODE = -1
        const val OP_REQUEST_CONTROL = ControlPointCodec.OPCODE_REQUEST_CONTROL
        const val OP_RESET = ControlPointCodec.OPCODE_RESET
        const val OP_START = ControlPointCodec.OPCODE_START_OR_RESUME
        const val OP_STOP_OR_PAUSE = ControlPointCodec.OPCODE_STOP_OR_PAUSE
        const val OP_TARGET_POWER = ControlPointCodec.OPCODE_SET_TARGET_POWER
        const val OP_SIMULATION = ControlPointCodec.OPCODE_SET_SIMULATION
        const val STATUS_RESET = 0x01
        const val STATUS_STOPPED_OR_PAUSED = 0x02
        const val STATUS_STARTED = 0x04
        const val STATUS_TARGET_POWER = 0x08
        const val STATUS_SIMULATION = 0x12
        const val STATUS_CONTROL_PERMISSION_LOST = 0xFF
    }
}
