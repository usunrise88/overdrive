package app.overdrive.core.ble.link

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/** Why a connection attempt or a connected link ended. */
public enum class LinkFailureKind {
    /** The transport could not open the link (`connect` failed, possibly with GATT status 133). */
    CONNECT_FAILED,

    /** The attempt took longer than the supervisor allows. */
    TIMED_OUT,

    /** A link that was up went away: out of range, powered off, or the stack dropped it. */
    LINK_LOST,

    /** The peripheral lacks a service or characteristic the device needs: it is not that kind of device. */
    SERVICE_MISSING,

    /** Discovery, a CCCD write or another step after the link came up failed. */
    SETUP_FAILED,

    /** Bluetooth was switched off or the permission was revoked. */
    UNAVAILABLE,
}

/** A failure with the platform's GATT [status] when there is one, and a short [detail] for the log (never an address). */
public data class LinkFailure(
    val kind: LinkFailureKind,
    val status: Int? = null,
    val detail: String? = null,
)

/**
 * What a device's link is doing, in the terms the Devices screen explains. [ConnectionState] stays the three-state view
 * the ride engine needs; this is the reason behind it (spec §4.1), and carries no Android types.
 */
public sealed interface LinkStatus {
    /** Not started, or stopped. Nothing is tried. */
    public data object Stopped : LinkStatus

    /**
     * Bluetooth cannot be used, so nothing is tried until [availability] is back to ready: "Turn Bluetooth on" or "Allow
     * Nearby devices". The wait ends by itself, with no backoff, the moment it is fixed.
     */
    public data class BluetoothUnavailable(
        val availability: BleAvailability,
    ) : LinkStatus

    /** Attempt number [attempt] (1 is the first since the last stable connection) is under way. */
    public data class Connecting(
        val attempt: Int,
    ) : LinkStatus

    /** The link is up and the device is set up. */
    public data object Connected : LinkStatus

    /** The last attempt or link ended with [failure]; the next attempt starts in [retryInMillis] unless Retry is pressed. */
    public data class WaitingToRetry(
        val failures: Int,
        val retryInMillis: Long,
        val failure: LinkFailure,
    ) : LinkStatus
}

/** What the Devices screen's **Retry** button and the host need from a device's link, beyond [ConnectionState]. */
public interface LinkControls {
    /** Why the link is as it is. */
    public val status: StateFlow<LinkStatus>

    /**
     * Try again now instead of waiting for the next backoff step, and start the backoff over. Fire and forget; does
     * nothing while an attempt is under way, while connected or while Bluetooth is unavailable.
     */
    public fun retryNow()

    /** Starts connecting, and keeps reconnecting on its own until [close]. Idempotent. */
    public fun start()

    /** Stops for good: closes the link and the transport. The device object is not used afterwards. */
    public fun close()
}
