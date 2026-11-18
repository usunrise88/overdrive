package app.overdrive.core.ble.fake

import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import kotlin.time.Duration

/**
 * Scripted failures for a [FakePeripheral], so a test can say "the first connect fails with 133", "the Control Point
 * stays silent once" or "no notifications between 30 s and 40 s" and replay it deterministically on virtual time
 * (docs/adr/0009-ble-transport-and-adapters.md). Every `failNext…` counts down and then the peripheral behaves again.
 * Times are on the [FakeBle] clock, from the moment the clock started (test time 0).
 */
public class FakeFaults {
    private class Counted(
        var remaining: Int,
        val status: Int,
        val characteristic: GattUuid?,
    )

    private class Window(
        val characteristic: GattUuid?,
        val fromNanos: Long,
        val untilNanos: Long,
    )

    private class ControlPointFault(
        var remaining: Int,
        val opcode: Int?,
        /** Null: no response at all. */
        val result: Int?,
    )

    private val connects = mutableListOf<Counted>()
    private val discoveries = mutableListOf<Counted>()
    private val cccdWrites = mutableListOf<Counted>()
    private val writes = mutableListOf<Counted>()
    private val windows = mutableListOf<Window>()
    private val controlPoint = mutableListOf<ControlPointFault>()

    /** The next [count] connection attempts fail with [status] (133, Android's generic GATT error, by default). */
    public fun failNextConnects(
        count: Int = 1,
        status: Int = GattStatus.GATT_ERROR,
    ) {
        connects += Counted(count, status, null)
    }

    /** The next [count] service discoveries fail with [status]. */
    public fun failNextDiscoveries(
        count: Int = 1,
        status: Int = GattStatus.GATT_ERROR,
    ) {
        discoveries += Counted(count, status, null)
    }

    /** The next [count] CCCD writes (of [characteristic], or of any) fail with [status]. */
    public fun failNextCccdWrites(
        count: Int = 1,
        status: Int = GattStatus.GATT_ERROR,
        characteristic: GattUuid? = null,
    ) {
        cccdWrites += Counted(count, status, characteristic)
    }

    /** The next [count] writes to [characteristic] (or to any) fail with [status]. */
    public fun failNextWrites(
        count: Int = 1,
        status: Int = GattStatus.GATT_ERROR,
        characteristic: GattUuid? = null,
    ) {
        writes += Counted(count, status, characteristic)
    }

    /** No notification of [characteristic] (or of any) is delivered from [from] until [until]: a gap in the stream. */
    public fun silence(
        from: Duration,
        until: Duration,
        characteristic: GattUuid? = null,
    ) {
        windows += Window(characteristic, from.inWholeNanoseconds, until.inWholeNanoseconds)
    }

    /**
     * The next [count] Control Point commands with [opcode] (or any) get **no** Response Code indication, and are not
     * carried out: the trainer stays silent, so the adapter's timeout fires.
     */
    public fun noControlPointResponse(
        count: Int = 1,
        opcode: Int? = null,
    ) {
        controlPoint += ControlPointFault(count, opcode, null)
    }

    /**
     * The next [count] Control Point commands with [opcode] (or any) are answered with [result] (`0x02` not supported,
     * `0x03` invalid parameter, `0x04` operation failed, `0x05` control not permitted…) and not carried out.
     */
    public fun controlPointResult(
        result: Int,
        count: Int = 1,
        opcode: Int? = null,
    ) {
        controlPoint += ControlPointFault(count, opcode, result)
    }

    internal fun takeConnect(): Int? = take(connects, null)

    internal fun takeDiscovery(): Int? = take(discoveries, null)

    internal fun takeCccd(characteristic: GattUuid): Int? = take(cccdWrites, characteristic)

    internal fun takeWrite(characteristic: GattUuid): Int? = take(writes, characteristic)

    internal fun isSilenced(
        characteristic: GattUuid,
        nowNanos: Long,
    ): Boolean =
        windows.any {
            (it.characteristic == null || it.characteristic == characteristic) && nowNanos >= it.fromNanos &&
                nowNanos < it.untilNanos
        }

    /** What to do with a Control Point command: normal (null), no response, or a given result. */
    internal sealed interface ControlPointOutcome {
        data object Silent : ControlPointOutcome

        data class Result(
            val code: Int,
        ) : ControlPointOutcome
    }

    internal fun takeControlPointFault(opcode: Int): ControlPointOutcome? {
        val fault =
            controlPoint.firstOrNull { it.remaining > 0 && (it.opcode == null || it.opcode == opcode) } ?: return null
        fault.remaining--
        return fault.result?.let { ControlPointOutcome.Result(it) } ?: ControlPointOutcome.Silent
    }

    private fun take(
        list: MutableList<Counted>,
        characteristic: GattUuid?,
    ): Int? {
        val fault =
            list.firstOrNull {
                it.remaining > 0 &&
                    (it.characteristic == null || characteristic == null || it.characteristic == characteristic)
            } ?: return null
        fault.remaining--
        return fault.status
    }
}
