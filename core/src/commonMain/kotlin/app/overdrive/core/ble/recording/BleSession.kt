package app.overdrive.core.ble.recording

import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.codec.Hex

/** The kind of event on one line of a recorded session (docs/protocols/ftms-hr.md §7.1). */
public enum class BleDirection(
    /** The letter on the line. */
    public val code: Char,
) {
    /** A value the peripheral pushed without acknowledgement. */
    NOTIFICATION('N'),

    /** A value the peripheral pushed and expects the central to acknowledge. */
    INDICATION('I'),

    /** A write to a characteristic, with its GATT status. */
    WRITE('W'),

    /** A read, with its status and the value. */
    READ('R'),

    /** A CCCD write (subscription): `0100` for notifications, `0200` for indications. */
    CCCD('C'),

    /** A connection state or link event, see [BleSessionEvent.data]. */
    STATE('S'),

    /** A characteristic found by service discovery, with its service and properties. Added by this implementation. */
    DISCOVERY('D'),
    ;

    public companion object {
        /** The direction for a line's letter, or null. */
        public fun of(code: Char): BleDirection? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One line of a recorded session: `t_ns dir char status data`, separated by single spaces.
 *
 * @property timeNanos nanoseconds **since the session recording began**, not since boot: the file carries no clock that
 * could identify a device or a day.
 * @property characteristic the characteristic's 16-bit form (`2AD2`) or full UUID, null (`-` on the line) for state events.
 * @property status the GATT status (0 is success), null (`-`) when there is none.
 * @property data upper-case hex of the exact bytes for N, I, W, R and C (`-` for none); for S one of the tokens of
 * [BleStates]; for D the service and the properties as `1826/WI` (letters R, W, w for write without response, N, I);
 * `*` for a value that was redacted (see [BleRecordingPolicy]).
 */
public data class BleSessionEvent(
    val timeNanos: Long,
    val direction: BleDirection,
    val characteristic: GattUuid?,
    val status: Int?,
    val data: String,
) {
    /** The bytes of [data] for the directions that carry some; empty for `-`, null for a redacted or malformed value. */
    public fun bytes(): ByteArray? =
        when (data) {
            NONE -> ByteArray(0)
            REDACTED -> null
            else -> Hex.decode(data)
        }

    public companion object {
        /** No value. */
        public const val NONE: String = "-"

        /** A value withheld because it could identify a unit. */
        public const val REDACTED: String = "*"
    }
}

/** The tokens of `S` lines. */
public object BleStates {
    public const val CONNECTING: String = "connecting"

    /** A connect that waits for the device to appear (`autoConnect`), docs/adr/0040. Replays like [CONNECTING]. */
    public const val CONNECTING_BACKGROUND: String = "connecting-background"
    public const val CONNECTED: String = "connected"
    public const val DISCONNECTED: String = "disconnected"
    public const val CLOSED: String = "closed"

    /** `mtu=64`: the negotiated MTU. */
    public const val MTU_PREFIX: String = "mtu="
}

/** A recorded or synthetic session: an optional advertised model name and the events in order. */
public class BleSession(
    /** The advertised model name such as `D500` (already sanitised), never a unit's own name or serial. */
    public val model: String?,
    public val events: List<BleSessionEvent>,
) {
    override fun toString(): String = "BleSession(model=$model, ${events.size} events)"
}
