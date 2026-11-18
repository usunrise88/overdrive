package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.codec.DecodeError
import app.overdrive.core.ble.codec.FitnessMachineStatus
import app.overdrive.core.ble.codec.FitnessMachineStatusCodec
import app.overdrive.core.ble.codec.IndoorBikeFlagMap
import app.overdrive.core.ble.codec.RequiredFeature
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every choice about the FTMS trainer that only the owner's bench can settle, in one place, each with the bench question
 * of docs/protocols/ftms-hr.md §9 that decides it. The defaults are the notes' reading; a bench result is applied by
 * changing a default here (or the constant it points at), never by touching the adapter. The full table, with the
 * evidence for each default, is in `docs/adr/0009-ble-transport-and-adapters.md`.
 */
public class FtmsOptions(
    /** Which flag bits announce Average Speed and Instantaneous Cadence (§2.4). **FT 1.** */
    public val flagMap: IndoorBikeFlagMap = IndoorBikeFlagMap.DEFAULT,
    /** Whether the simulation status echo is signed (§5). **FT 8.** */
    public val simulationStatusSigned: Boolean = FitnessMachineStatusCodec.DEFAULT_SIMULATION_SIGNED,
    /**
     * Send Start or Resume (`0x07`) right after Request Control succeeds (§4.2). It is harmless on a trainer that does not
     * need it (a rejection is only logged) and required on one that does. **FT 7** (bench 3.5).
     */
    public val startAfterRequestControl: Boolean = true,
    /**
     * Refuse ERG or simulation commands, without sending them, when the Fitness Machine Feature says the trainer lacks
     * the feature (§3). Off by default: the trainer's Response Code is the authority, and a wrong feature word must not
     * stop a ride. **Bench 3.2.**
     */
    public val enforceFeatureBits: Boolean = false,
    /** The ATT MTU asked for at connect (§7): a 30-byte Indoor Bike Data needs more than the default 23. **FT 12.** */
    public val requestedMtu: Int = 64,
    /**
     * How long to wait for a Response Code indication after a Control Point write (§4.4); then the command fails and the
     * engine starts over a second later. A guess until **FT 6** gives the median and the 95th percentile.
     */
    public val controlPointTimeout: Duration = 3.seconds,
    /** How long any single GATT operation may take before it counts as failed. */
    public val operationTimeout: Duration = 10.seconds,
    /** How often a command answered "operation failed" (`0x04`) is sent again before giving up (§4.4). */
    public val operationFailedRetries: Int = 2,
    /** The wait between those retries. */
    public val operationFailedBackoff: Duration = 500.milliseconds,
    /** The wait before taking control back after the trainer reports it lost (Status `0x01` or `0xFF`, §5). */
    public val controlRecoveryDelay: Duration = 1.seconds,
    /**
     * A Reset status (`0x01`) that arrives within this long after a Request Control **this connection just completed** is
     * the trainer's own answer to it, not a loss of control, and is ignored. The Van Rysel D500 follows every Request
     * Control with a Reset, which made the adapter take control again at once: five Request Control and Start pairs in the
     * first 1.5 seconds of the first bench ride (docs/bench-reports/2026-11-18-first-ride.md §2, FT 10). A Reset outside the
     * window, and a Control Permission Lost (`0xFF`) at any time, still count as lost control and are recovered; a real
     * loss is also caught by the next command failing and the engine's retry. Zero turns the grace off. **FT 10.**
     */
    public val selfResetGrace: Duration = 1500.milliseconds,
) {
    init {
        require(requestedMtu >= MIN_MTU) { "The MTU request must be at least $MIN_MTU, got $requestedMtu" }
        require(operationFailedRetries >= 0) { "Retries must not be negative, got $operationFailedRetries" }
        require(!selfResetGrace.isNegative()) { "The self-reset grace must not be negative, got $selfResetGrace" }
    }

    private companion object {
        const val MIN_MTU = 23
    }
}

/** Something the trainer's link or the trainer itself reported, for the Devices screen and the log. */
public sealed interface TrainerEvent {
    /** Control of the trainer was granted. */
    public data object ControlGranted : TrainerEvent

    /**
     * The trainer says control is gone (Status `0xFF`, or `0x01` outside [FtmsOptions.selfResetGrace] of our own Request
     * Control); the adapter takes it back and re-sends the last target.
     */
    public data class ControlLost(
        val status: FitnessMachineStatus,
    ) : TrainerEvent

    /** Request Control itself failed during set-up or recovery. */
    public data class ControlRequestFailed(
        val error: TrainerCommandException,
    ) : TrainerEvent

    /** The trainer answered a command with a code other than success. */
    public data class CommandRejected(
        val opcode: Int,
        val resultCode: Int,
    ) : TrainerEvent

    /** Any Fitness Machine Status notification, decoded. The ride engine does not consume these yet. */
    public data class StatusReceived(
        val status: FitnessMachineStatus,
    ) : TrainerEvent

    /** The trainer's target power differs from the one last sent: it altered or ignored our command (Status `0x08`). */
    public data class TargetPowerAltered(
        val sent: Int?,
        val reported: Int,
    ) : TrainerEvent

    /** The Feature word says the trainer lacks something the ride needs (§3). Informational unless `enforceFeatureBits`. */
    public data class FeaturesMissing(
        val missing: List<RequiredFeature>,
    ) : TrainerEvent

    /** A notification could not be decoded and was dropped. */
    public data class MalformedNotification(
        val characteristic: GattUuid,
        val error: DecodeError,
    ) : TrainerEvent

    /** An Indoor Bike Data notification had "More Data" set (§2.3): the speed was absent; splitting is unverified (FT 3). */
    public data object MoreDataSeen : TrainerEvent

    /** An optional set-up step failed and the connection went on without it (Status subscription, MTU, Feature read). */
    public data class OptionalStepFailed(
        val step: String,
        val failure: GattFailure,
    ) : TrainerEvent
}

/** What a Control Point command failed with; thrown by the trainer's command functions, which the engine retries. */
public class TrainerCommandException(
    /** The opcode of the command that failed. */
    public val opcode: Int,
    public val kind: Kind,
    /** The trainer's result code for [Kind.REJECTED] and [Kind.UNSUPPORTED] and [Kind.NOT_UNDER_CONTROL]. */
    public val resultCode: Int? = null,
) : Exception(
        "Control Point opcode 0x${opcode.toString(
            16,
        )} failed: $kind${resultCode?.let { " (result 0x${it.toString(16)})" } ?: ""}",
    ) {
    /** The ways a command can fail. */
    public enum class Kind {
        /** There is no live connection. The engine only sends while connected and retries after reconnects. */
        NOT_CONNECTED,

        /** The write to the Control Point failed (GATT status, link lost while writing, operation timeout). */
        WRITE_FAILED,

        /** No Response Code indication arrived within `controlPointTimeout`. */
        TIMEOUT,

        /** The trainer answered with an error result (invalid parameter, operation failed after the retries…). */
        REJECTED,

        /** The trainer does not support the opcode (`0x02`); further tries of it fail at once, without traffic. */
        UNSUPPORTED,

        /** The trainer says control is not ours (`0x05`) even after taking it again. */
        NOT_UNDER_CONTROL,

        /** The Feature word lacks the feature and `enforceFeatureBits` is on. */
        FEATURE_MISSING,
    }
}
