package app.overdrive.core.device

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Link state of a device (spec §4.1). A device reconnects on its own with backoff, so after a drop the
 * state goes `CONNECTED → DISCONNECTED → CONNECTING → CONNECTED` (or stays in `CONNECTING` while it
 * retries); the ride engine only cares whether it is [CONNECTED].
 */
public enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

/**
 * One notification of the FTMS Indoor Bike Data characteristic, already decoded. Every field is optional
 * in FTMS, and null means "not in this notification", never zero.
 */
public data class TrainerReading(
    /** Instantaneous power in watts. */
    val power: Int? = null,
    /** Instantaneous cadence in rpm, rounded from FTMS's half-rpm resolution. */
    val cadence: Int? = null,
    /**
     * The trainer's own speed in km/h. **Received but ignored** (spec §4.3): it reflects the flywheel,
     * which a single cog ties to cadence, not the virtual speed. Carried so recorded sessions stay complete.
     */
    val speedKmh: Double? = null,
) {
    init {
        require(power == null || power >= 0) { "Power must not be negative, got $power" }
        require(cadence == null || cadence >= 0) { "Cadence must not be negative, got $cadence" }
    }
}

/**
 * The parameters of FTMS "Set Indoor Bike Simulation Parameters" (spec §4.1, §4.4) apart from wind speed,
 * which is always 0 here. A BLE adapter encodes them as: grade in 0.01% (signed 16 bit), Crr in 0.0001
 * (8 bit) and the wind resistance coefficient in 0.01 kg/m (8 bit), which bounds the values below.
 */
public data class SimulationParameters(
    /** Road grade in percent; positive climbs. Negative grades descend. */
    val gradePercent: Double,
    /** Rolling resistance coefficient, 0 to 0.0255. */
    val crr: Double,
    /** Wind resistance coefficient in kg/m (`½ · ρ · CdA`), 0 to 2.55. */
    val windResistance: Double,
) {
    init {
        require(gradePercent.isFinite() && gradePercent in -MAX_GRADE_PERCENT..MAX_GRADE_PERCENT) {
            "Grade must be within ±$MAX_GRADE_PERCENT%, got $gradePercent"
        }
        require(crr in 0.0..MAX_CRR) { "Crr must be in 0..$MAX_CRR, got $crr" }
        require(windResistance in 0.0..MAX_WIND_RESISTANCE) {
            "Wind resistance must be in 0..$MAX_WIND_RESISTANCE kg/m, got $windResistance"
        }
    }

    public companion object {
        /** FTMS's range; the trainer's own limit (15% on the D500) is enforced by the gear settings. */
        public const val MAX_GRADE_PERCENT: Double = 200.0
        public const val MAX_CRR: Double = 0.0255
        public const val MAX_WIND_RESISTANCE: Double = 2.55
    }
}

/** A command the engine sent to a [TrainerDevice], as a simulated trainer logs it. */
public sealed interface TrainerCommand {
    /** FTMS Request Control. */
    public data object RequestControl : TrainerCommand

    /** FTMS Set Target Power, in watts. */
    public data class TargetPower(
        val watts: Int,
    ) : TrainerCommand

    /** FTMS Set Indoor Bike Simulation Parameters. */
    public data class Simulation(
        val parameters: SimulationParameters,
    ) : TrainerCommand
}

/**
 * A smart trainer, shaped after FTMS (Fitness Machine Service 1.0) so that a BLE adapter is mechanical:
 * notifications become [data], the Fitness Machine Control Point becomes the three commands.
 *
 * **Threading.** All members may be called from any coroutine. The ride engine calls the commands from
 * a coroutine of its own, never from its 1 Hz loop, so a slow write does not delay the ride clock.
 *
 * **Failure.** A command on a link that is not [ConnectionState.CONNECTED] may throw or be dropped, either
 * is fine: the engine only sends while connected, and after every reconnect it starts over with
 * [requestControl] and the current target. A command that throws (other than `CancellationException`) is
 * retried after a second.
 */
public interface TrainerDevice {
    /** A short name for the ride record's device list, such as the advertised name. */
    public val name: String

    /** The link state. */
    public val connection: StateFlow<ConnectionState>

    /**
     * Indoor Bike Data notifications, a hot flow: a collector sees the readings that arrive after it starts
     * collecting. It never completes and survives reconnects. Roughly one reading per second.
     */
    public val data: Flow<TrainerReading>

    /**
     * Takes control of the trainer (FTMS Request Control, plus Start or Resume where the trainer needs it).
     * Called first after every (re)connect, before any target is set.
     */
    public suspend fun requestControl()

    /** ERG: hold [watts] regardless of cadence (FTMS Set Target Power). */
    public suspend fun setTargetPower(watts: Int)

    /** Simulation: resist like a road with these [parameters] (FTMS Set Indoor Bike Simulation Parameters). */
    public suspend fun setSimulation(parameters: SimulationParameters)
}

/**
 * One notification of the Heart Rate Measurement characteristic, already decoded.
 */
public data class HeartRateReading(
    /** Beats per minute. A strap without skin contact reports 0, which the engine treats as no signal. */
    val bpm: Int,
    /**
     * RR intervals in milliseconds carried by this notification, oldest first, converted by the adapter
     * from the strap's 1/1024 s units. Empty when the strap sends none (spec §4.1, "expected, not yet verified").
     */
    val rrIntervalsMs: List<Int> = emptyList(),
) {
    init {
        require(bpm >= 0) { "Heart rate must not be negative, got $bpm" }
        require(rrIntervalsMs.all { it > 0 }) { "RR intervals must be positive, got $rrIntervalsMs" }
    }
}

/** A heart-rate strap (Bluetooth Heart Rate Service). A ride never depends on it (spec §4.1). */
public interface HeartRateDevice {
    /** A short name for the ride record's device list. */
    public val name: String

    /** The link state. */
    public val connection: StateFlow<ConnectionState>

    /** Heart Rate Measurement notifications, a hot flow like [TrainerDevice.data]. */
    public val data: Flow<HeartRateReading>
}
