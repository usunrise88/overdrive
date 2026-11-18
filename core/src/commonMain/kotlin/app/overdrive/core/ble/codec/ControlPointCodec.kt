package app.overdrive.core.ble.codec

import app.overdrive.core.device.SimulationParameters
import kotlin.math.roundToInt

/** A Fitness Machine Control Point request (docs/protocols/ftms-hr.md §4.2). */
public sealed interface ControlPointCommand {
    /** The opcode byte. */
    public val opcode: Int

    /** `0x00`: take control; every other command waits for its success. */
    public data object RequestControl : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_REQUEST_CONTROL
    }

    /** `0x01`: reset, which drops control. */
    public data object Reset : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_RESET
    }

    /** `0x05`: hold [watts] (ERG), sint16 with a resolution of 1 W. Negative targets make no sense and are refused. */
    public data class SetTargetPower(
        val watts: Int,
    ) : ControlPointCommand {
        init {
            require(watts in 0..Short.MAX_VALUE) { "Target power must be 0 to ${Short.MAX_VALUE} W, got $watts" }
        }

        override val opcode: Int get() = ControlPointCodec.OPCODE_SET_TARGET_POWER
    }

    /** `0x07`: start or resume. Whether the D500 needs it before a target is bench question FT 7. */
    public data object StartOrResume : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_START_OR_RESUME
    }

    /** `0x08` with parameter `0x01`. */
    public data object Stop : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_STOP_OR_PAUSE
    }

    /** `0x08` with parameter `0x02`. */
    public data object Pause : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_STOP_OR_PAUSE
    }

    /**
     * `0x11`: simulate a road. The parameters' ranges (grade ±200 % at 0.01 %, Crr 0 to 0.0255 at 0.0001, wind resistance
     * 0 to 2.55 kg/m at 0.01) are exactly those [SimulationParameters] validates, so a value that exists is encodable.
     * Wind speed is always 0 (spec §4.4).
     */
    public data class SetSimulation(
        val parameters: SimulationParameters,
    ) : ControlPointCommand {
        override val opcode: Int get() = ControlPointCodec.OPCODE_SET_SIMULATION
    }
}

/** Result codes of a Response Code indication (docs/protocols/ftms-hr.md §4.4). */
public enum class ControlPointResult(
    /** The code on the wire, or -1 for [UNKNOWN]. */
    public val code: Int,
) {
    SUCCESS(0x01),
    OP_CODE_NOT_SUPPORTED(0x02),
    INVALID_PARAMETER(0x03),
    OPERATION_FAILED(0x04),
    CONTROL_NOT_PERMITTED(0x05),

    /** Any other code; [ControlPointResponse.resultCode] keeps the raw value. */
    UNKNOWN(-1),
    ;

    public companion object {
        /** The result for a wire [code], [UNKNOWN] for one the notes do not list. */
        public fun of(code: Int): ControlPointResult = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** A Response Code indication: `0x80`, the opcode being answered, the result. */
public data class ControlPointResponse(
    /** The opcode of the request this answers. */
    val requestOpcode: Int,
    /** The raw result code. */
    val resultCode: Int,
    /** Extra parameter bytes after the result code, empty for every command we send. */
    val parameter: List<Int> = emptyList(),
) {
    /** [resultCode] as an enum. */
    val result: ControlPointResult get() = ControlPointResult.of(resultCode)

    /** Whether the trainer accepted the request. */
    val isSuccess: Boolean get() = resultCode == ControlPointResult.SUCCESS.code
}

/** Encodes Control Point requests and decodes their responses (docs/protocols/ftms-hr.md §4). Pure. */
public object ControlPointCodec {
    public const val OPCODE_REQUEST_CONTROL: Int = 0x00
    public const val OPCODE_RESET: Int = 0x01
    public const val OPCODE_SET_TARGET_POWER: Int = 0x05
    public const val OPCODE_START_OR_RESUME: Int = 0x07
    public const val OPCODE_STOP_OR_PAUSE: Int = 0x08
    public const val OPCODE_SET_SIMULATION: Int = 0x11
    public const val OPCODE_RESPONSE_CODE: Int = 0x80

    private const val PARAMETER_STOP = 0x01
    private const val PARAMETER_PAUSE = 0x02
    private const val RESPONSE_HEADER_SIZE = 3
    private const val GRADE_RESOLUTION = 0.01
    private const val CRR_RESOLUTION = 0.0001
    private const val WIND_RESISTANCE_RESOLUTION = 0.01
    private const val MAX_UINT8 = 255
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** The bytes to write to the Control Point for [command]. */
    public fun encode(command: ControlPointCommand): ByteArray =
        when (command) {
            ControlPointCommand.RequestControl -> byteArrayOf(OPCODE_REQUEST_CONTROL.toByte())
            ControlPointCommand.Reset -> byteArrayOf(OPCODE_RESET.toByte())
            ControlPointCommand.StartOrResume -> byteArrayOf(OPCODE_START_OR_RESUME.toByte())
            ControlPointCommand.Stop -> byteArrayOf(OPCODE_STOP_OR_PAUSE.toByte(), PARAMETER_STOP.toByte())
            ControlPointCommand.Pause -> byteArrayOf(OPCODE_STOP_OR_PAUSE.toByte(), PARAMETER_PAUSE.toByte())
            is ControlPointCommand.SetTargetPower -> int16(OPCODE_SET_TARGET_POWER, command.watts)
            is ControlPointCommand.SetSimulation -> encodeSimulation(command.parameters)
        }

    private fun int16(
        opcode: Int,
        value: Int,
    ): ByteArray =
        byteArrayOf(
            opcode.toByte(),
            (value and BYTE_MASK).toByte(),
            ((value shr BITS_PER_BYTE) and BYTE_MASK).toByte(),
        )

    private fun encodeSimulation(parameters: SimulationParameters): ByteArray {
        val grade =
            (parameters.gradePercent / GRADE_RESOLUTION).roundToInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            )
        val crr = (parameters.crr / CRR_RESOLUTION).roundToInt().coerceIn(0, MAX_UINT8)
        val windResistance =
            (parameters.windResistance / WIND_RESISTANCE_RESOLUTION).roundToInt().coerceIn(
                0,
                MAX_UINT8,
            )
        return byteArrayOf(
            OPCODE_SET_SIMULATION.toByte(),
            0, // wind speed, sint16 at 0.001 m/s: always still air
            0,
            (grade and BYTE_MASK).toByte(),
            ((grade shr BITS_PER_BYTE) and BYTE_MASK).toByte(),
            crr.toByte(),
            windResistance.toByte(),
        )
    }

    /**
     * Parses a Response Code indication. Anything that is not `0x80`, a request opcode and a result code (at least three
     * bytes) is [DecodeResult.Malformed].
     */
    public fun parseResponse(bytes: ByteArray): DecodeResult<ControlPointResponse> {
        if (bytes.isEmpty()) return emptyPayload()
        val reader = ByteReader(bytes)
        if (!reader.has(RESPONSE_HEADER_SIZE)) return reader.truncated("response code", RESPONSE_HEADER_SIZE)
        val marker = reader.u8()
        if (marker != OPCODE_RESPONSE_CODE) {
            return DecodeResult.Malformed(
                DecodeError(DecodeErrorKind.UNEXPECTED_MARKER, "response marker", OPCODE_RESPONSE_CODE, marker),
            )
        }
        val request = reader.u8()
        val result = reader.u8()
        val parameter = ArrayList<Int>(reader.remaining)
        while (reader.has(1)) parameter += reader.u8()
        return DecodeResult.Ok(ControlPointResponse(request, result, parameter))
    }
}
