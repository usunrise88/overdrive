package app.overdrive.core.click

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.codec.DecodeError
import app.overdrive.core.ble.link.LinkFailure

/** Why a Click is believed to be locked by Zwift (docs/protocols/click-v2.md §6, spec §4.7). Each signal is an option. */
public enum class UnlockSignal {
    /** The Zwift service is not in GATT discovery: the controller is hiding it (§6.1, `ClickOptions.serviceHiddenMeansLocked`). */
    SERVICE_HIDDEN,

    /**
     * The handshake was answered and then nothing at all came (`ClickOptions.silentMeansLocked`, `noEventTimeout`). **Opt-in:**
     * the first bench found a healthy Click that sends nothing until a button is pressed (docs/bench-reports/2026-11-18-first-ride.md).
     */
    NO_EVENTS,
}

/**
 * What the Click is doing, in the terms the Devices screen and the ride's notice explain (spec §4.7: "tell the user to
 * unlock it through Zwift once and continue without the Click"). The ride never depends on it: every state but [Active]
 * means "carry on with the on-screen controls".
 */
public sealed interface ClickState {
    /** Not started, or stopped. */
    public data object Stopped : ClickState

    /** Bluetooth cannot be used: "Turn Bluetooth on" or "Allow Nearby devices". Nothing is tried until it is fixed. */
    public data class BluetoothUnavailable(
        val availability: BleAvailability,
    ) : ClickState

    /** Attempt number [attempt] to connect and set up is under way. */
    public data class Connecting(
        val attempt: Int,
    ) : ClickState

    /**
     * The controller could not be reached: switched off or asleep, out of range, or held by another app. The next attempt
     * is in [retryInMillis] unless Retry is pressed. Also the state right after a link that dropped.
     */
    public data class NotFound(
        val failures: Int,
        val retryInMillis: Long,
        /** True when a link that was up has just been lost, rather than a controller that never answered. */
        val wasConnected: Boolean,
    ) : ClickState

    /**
     * Connected and set up, no frame yet, still inside the no-event time. **Only with `ClickOptions.silentMeansLocked` on**:
     * by default an answered handshake is [Active] at once, because a real Click is silent until a button is pressed.
     */
    public data object Waiting : ClickState

    /**
     * Connected and set up: the handshake was answered (by default), or, with the silence rule on, a frame has arrived since.
     * A healthy Click says nothing until a button is pressed.
     */
    public data object Active : ClickState

    /**
     * The controller looks locked by Zwift: tell the rider to unlock it through Zwift once. For [UnlockSignal.SERVICE_HIDDEN]
     * the app backs off and tries again; for [UnlockSignal.NO_EVENTS] the link stays up and a late frame turns this into
     * [Active] by itself.
     */
    public data class NeedsUnlock(
        val signal: UnlockSignal,
    ) : ClickState

    /** Something else in the set-up failed (the handshake reply, a subscription, a missing characteristic); retrying. */
    public data class SetupFailed(
        val failure: LinkFailure,
        val retryInMillis: Long,
    ) : ClickState
}

/** Something the Click or its link reported, for the log and the bench; the ride ignores them all. A hot flow without replay. */
public sealed interface ClickEvent {
    /** A frame could not be decoded and was dropped. */
    public data class MalformedFrame(
        val error: DecodeError,
    ) : ClickEvent

    /** A frame of a type this code does not know (`0x3C` among them, §4); only its type and length are reported. */
    public data class UnrecognisedFrame(
        val type: Int,
        val bodyLength: Int,
    ) : ClickEvent

    /** The controller sent the disconnect notice `0xFE` (§4). */
    public data object DisconnectNoticeReceived : ClickEvent

    /**
     * The handshake was answered. [version] is the two bytes after `RideOn` as the notes write them (`02 03`), null for a bare
     * `RideOn`; [tailLength] is the public key's length or 0 (bench question 3).
     */
    public data class HandshakeAnswered(
        val version: String?,
        val tailLength: Int,
    ) : ClickEvent

    /** The reply did not pass `ClickOptions.handshakeReply`; [prefix] is its first eight bytes as hex. */
    public data class HandshakeReplyRejected(
        val prefix: String,
    ) : ClickEvent

    /** No reply to the handshake within the timeout. */
    public data object HandshakeTimedOut : ClickEvent

    /** An optional set-up step failed and the connection went on without it (MTU, `FF 04 00`, a standard read). */
    public data class OptionalStepFailed(
        val step: String,
        val failure: GattFailure,
    ) : ClickEvent

    /** A keep-alive write failed. */
    public data class KeepAliveFailed(
        val failure: GattFailure,
    ) : ClickEvent
}
