package app.overdrive.core.ble.codec

/**
 * A Fitness Machine Status notification (characteristic `0x2ADA`, docs/protocols/ftms-hr.md §5): what the trainer says
 * happened to it. The opcode table is single-source (pycycling) until the bench (FT 4).
 */
public sealed interface FitnessMachineStatus {
    /** `0x01`: the machine was reset. Control is lost. */
    public data object Reset : FitnessMachineStatus

    /** `0x02` with parameter `0x01`: stopped by the user. */
    public data object StoppedByUser : FitnessMachineStatus

    /** `0x02` with parameter `0x02`: paused by the user. */
    public data object PausedByUser : FitnessMachineStatus

    /** `0x03`: stopped by the safety key. */
    public data object StoppedBySafetyKey : FitnessMachineStatus

    /** `0x04`: started or resumed by the user. */
    public data object StartedByUser : FitnessMachineStatus

    /** `0x08`: the target power is now [watts]; a value other than the one we sent means the trainer altered it. */
    public data class TargetPowerChanged(
        val watts: Int,
    ) : FitnessMachineStatus

    /** `0x12`: the simulation parameters are now these. The grade is signed unless the dispute of §5 goes the other way. */
    public data class SimulationChanged(
        val windSpeedMetresPerSecond: Double,
        val gradePercent: Double,
        val crr: Double,
        val windResistanceKgPerMetre: Double,
    ) : FitnessMachineStatus

    /** `0xFF`: another party took control, or the machine dropped ours. Request control again. */
    public data object ControlPermissionLost : FitnessMachineStatus

    /** Any other opcode (spin down, targeted cadence, speed and incline target changes…): not used, kept for the log. */
    public data class Other(
        val opcode: Int,
        val parameters: List<Int>,
    ) : FitnessMachineStatus
}

/** Decodes Fitness Machine Status notifications. Pure and exception-free. */
public object FitnessMachineStatusCodec {
    private const val OP_RESET = 0x01
    private const val OP_STOPPED_OR_PAUSED = 0x02
    private const val OP_SAFETY_KEY = 0x03
    private const val OP_STARTED = 0x04
    private const val OP_TARGET_POWER = 0x08
    private const val OP_SIMULATION = 0x12
    private const val OP_CONTROL_PERMISSION_LOST = 0xFF
    private const val PARAM_STOPPED = 0x01
    private const val PARAM_PAUSED = 0x02
    private const val SIMULATION_SIZE = 6
    private const val WIND_RESOLUTION = 0.001
    private const val GRADE_RESOLUTION = 0.01
    private const val CRR_RESOLUTION = 0.0001
    private const val WIND_RESISTANCE_RESOLUTION = 0.01

    /**
     * Whether the wind speed and grade of the simulation status are signed 16-bit values, as in the Control Point request
     * (§5). One decoder reads them unsigned, which turns a −2.00 % grade into 653.36 %. **The one-line flip for bench
     * question FT 8.**
     */
    public const val DEFAULT_SIMULATION_SIGNED: Boolean = true

    /** Parses one notification; an opcode this object does not know is [FitnessMachineStatus.Other], not an error. */
    public fun parse(
        bytes: ByteArray,
        simulationSigned: Boolean = DEFAULT_SIMULATION_SIGNED,
    ): DecodeResult<FitnessMachineStatus> {
        if (bytes.isEmpty()) return emptyPayload()
        val reader = ByteReader(bytes)
        val opcode = reader.u8()
        return when (opcode) {
            OP_RESET -> {
                DecodeResult.Ok(FitnessMachineStatus.Reset)
            }

            OP_STOPPED_OR_PAUSED -> {
                parseStoppedOrPaused(reader)
            }

            OP_SAFETY_KEY -> {
                DecodeResult.Ok(FitnessMachineStatus.StoppedBySafetyKey)
            }

            OP_STARTED -> {
                DecodeResult.Ok(FitnessMachineStatus.StartedByUser)
            }

            OP_CONTROL_PERMISSION_LOST -> {
                DecodeResult.Ok(FitnessMachineStatus.ControlPermissionLost)
            }

            OP_TARGET_POWER -> {
                if (reader.has(
                        2,
                    )
                ) {
                    DecodeResult.Ok(
                        FitnessMachineStatus.TargetPowerChanged(reader.s16()),
                    )
                } else {
                    reader.truncated("target power", 2)
                }
            }

            OP_SIMULATION -> {
                parseSimulation(reader, simulationSigned)
            }

            else -> {
                DecodeResult.Ok(FitnessMachineStatus.Other(opcode, remainingBytes(reader)))
            }
        }
    }

    private fun parseStoppedOrPaused(reader: ByteReader): DecodeResult<FitnessMachineStatus> {
        if (!reader.has(1)) return reader.truncated("stop or pause parameter", 1)
        return when (val parameter = reader.u8()) {
            PARAM_STOPPED -> DecodeResult.Ok(FitnessMachineStatus.StoppedByUser)
            PARAM_PAUSED -> DecodeResult.Ok(FitnessMachineStatus.PausedByUser)
            else -> DecodeResult.Ok(FitnessMachineStatus.Other(OP_STOPPED_OR_PAUSED, listOf(parameter)))
        }
    }

    private fun parseSimulation(
        reader: ByteReader,
        signed: Boolean,
    ): DecodeResult<FitnessMachineStatus> {
        if (!reader.has(SIMULATION_SIZE)) return reader.truncated("simulation parameters", SIMULATION_SIZE)
        val wind = if (signed) reader.s16() else reader.u16()
        val grade = if (signed) reader.s16() else reader.u16()
        val crr = reader.u8()
        val windResistance = reader.u8()
        return DecodeResult.Ok(
            FitnessMachineStatus.SimulationChanged(
                windSpeedMetresPerSecond = wind * WIND_RESOLUTION,
                gradePercent = grade * GRADE_RESOLUTION,
                crr = crr * CRR_RESOLUTION,
                windResistanceKgPerMetre = windResistance * WIND_RESISTANCE_RESOLUTION,
            ),
        )
    }

    private fun remainingBytes(reader: ByteReader): List<Int> {
        val out = ArrayList<Int>(reader.remaining)
        while (reader.has(1)) out += reader.u8()
        return out
    }
}
