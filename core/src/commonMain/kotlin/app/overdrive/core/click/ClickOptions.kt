package app.overdrive.core.click

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How strictly the reply to the `RideOn` handshake is checked (docs/protocols/click-v2.md §3.1, §3.2, bench questions 2 to 4). */
public enum class HandshakeReplyCheck {
    /**
     * The reply must be the bare six bytes `RideOn` (the plain, unencrypted path: what the right puck of the first bench
     * answered, docs/bench-reports/2026-11-18-first-ride.md, with nothing after it) or `RideOn` followed by `02 03` (what the
     * notes expected; anything after that, the public key, is ignored). Any other version (`01 03`, Zwift Play's encrypted
     * variant), a reply of seven bytes (half a version) or other bytes fail the set-up with the bytes in the event, which is
     * how an encrypted variant would show itself instead of as garbage frames.
     */
    EXPECTED_VERSION,

    /** The reply must start with `RideOn`; whatever version bytes follow are accepted and reported. */
    ANY_RIDE_ON,

    /** Do not wait for a reply: write the handshake and go on. A reply that arrives is reported only. */
    NONE,
}

/**
 * Every choice about the Zwift Click that only the owner's bench can settle, in one place, each with the section of
 * `docs/protocols/click-v2.md` that justifies the default and the bench question of its §9 that settles it. The defaults are
 * the notes' recommendation; a bench result is applied by changing a default here, never by touching the adapter
 * (docs/adr/0011-click-gestures-and-sync-stub.md has the full table, the same way ADR 0009 has `FtmsOptions`'s). The first bench
 * (docs/bench-reports/2026-11-18-first-ride.md, ADR 0013) changed two of them: [handshakeReply] accepts a bare `RideOn`, and
 * [silentMeansLocked] is off.
 *
 * The notes support the **plain path on the right puck** only (§7); nothing here encrypts, and nothing here unlocks a
 * controller. What a locked controller looks like is reported, not worked around.
 */
public class ClickOptions(
    /** The first byte of the manufacturer data of the right puck (§1: `0x0A`, "unconfirmed"). **CK 1.** */
    public val rightSideByte: Int = DEFAULT_RIGHT_SIDE_BYTE,
    /** The first byte of the manufacturer data of the left puck (§1: `0x0B`, "unconfirmed"). **CK 1.** */
    public val leftSideByte: Int = DEFAULT_LEFT_SIDE_BYTE,
    /** Which pucks the app wants: the right one only (spec §4.7: "the right Click"). **CK 1.** */
    public val acceptedSides: Set<ClickSide> = setOf(ClickSide.RIGHT),
    /**
     * A scan result needs the `FC82` service (or the older one) in its advertisement (§1: checked before the manufacturer
     * data). Off: the manufacturer data alone is enough. **CK 12.**
     */
    public val requireAdvertisedService: Boolean = true,
    /** The older service `00000001-19CA-…` counts as the Click's, in a scan and in discovery (§2: BikeControl's fallback). */
    public val acceptLegacyService: Boolean = true,
    /** Ask for this ATT MTU at connect, or null to leave it: the handshake reply may carry a 64-byte key (§3.1). **CK 3.** */
    public val requestedMtu: Int? = DEFAULT_MTU,
    /**
     * Write every host-to-controller value **with** response. Off: without response, as the notes say BikeControl writes the
     * handshake (§3.1). **CK 2.**
     */
    public val writeWithResponse: Boolean = false,
    /**
     * How the handshake reply is checked. The default accepts the bare `RideOn` that the first bench's right puck answered
     * and the notes' `RideOn 02 03` (a key after it is ignored), and refuses any other version. **CK 2, CK 3, CK 4.**
     */
    public val handshakeReply: HandshakeReplyCheck = HandshakeReplyCheck.EXPECTED_VERSION,
    /** How long to wait for the reply after writing `RideOn` (unmeasured); then the set-up fails and backs off. **CK 2.** */
    public val handshakeTimeout: Duration = 5.seconds,
    /**
     * Write `FF 04 00` after the handshake (§3.1: trainer-control and BikeControl's newer code do, the licensed tag does
     * not; the bytes' meaning is unknown). On by default so that the first bench run has every chance of working; a
     * refused write is only reported. **CK 6.**
     */
    public val sendPostHandshake: Boolean = true,
    /** Read the firmware revision and the battery level once after the handshake (§2: standard services); optional steps. */
    public val readStandardCharacteristics: Boolean = true,
    /** How long any single GATT operation may take before it counts as failed. */
    public val operationTimeout: Duration = 10.seconds,
    /**
     * Write [keepAliveFrame] to the controller this often while connected, or null for never. **None by default**: the
     * licensed code has no keep-alive toward the controller (§5). The bench decides whether it needs one. **CK 7.**
     */
    public val keepAliveInterval: Duration? = null,
    /**
     * What the keep-alive writes: the neutral frame `23 08 FF FF FF FF 0F` (§4: what BikeControl sends to a host it
     * emulates a controller for, with an inferred type byte; not known to mean anything to the controller). **CK 7.**
     */
    keepAliveFrame: ByteArray = ClickCodec.neutralFrame(),
    /**
     * A controller whose Zwift service is missing from discovery counts as **locked by Zwift** (§6.1): the state is "needs
     * unlocking" and the set-up backs off and tries again. Off: it is an ordinary missing service. **CK 12.**
     */
    public val serviceHiddenMeansLocked: Boolean = true,
    /**
     * A controller that answered the handshake and then sent no frame at all (no button state, heartbeat, battery or any
     * other type) within [noEventTimeout] counts as locked. The link stays up and a late frame clears it. **Off by default:**
     * the first bench found that a healthy, unlocked right puck sends **nothing until a button is pressed** (sessions of 15,
     * 67 and 33 seconds had no frame before the first press), so this rule showed a false lock notice on an idle Click
     * (docs/bench-reports/2026-11-18-first-ride.md §1). With it off an answered handshake is [ClickState.Active] at once. On
     * it is an opt-in (Settings, Developer · Click, "Silence means locked") for a controller that is truly locked and
     * silent, which has not been seen. **CK 2, CK 14.**
     */
    public val silentMeansLocked: Boolean = false,
    /**
     * How long after the handshake with no frame at all before [silentMeansLocked] applies (only then). A healthy Click can
     * be silent for minutes, so this is generous. **CK 7.**
     */
    public val noEventTimeout: Duration = 30.seconds,
    /** Bits of the bitmap that always read as released, for a bit that turns out to be stuck at 0 (§4). **CK 5.** */
    public val ignoredBits: Int = 0,
) {
    private val frame: ByteArray = keepAliveFrame.copyOf()

    /** A copy of the keep-alive frame. */
    public val keepAliveFrameBytes: ByteArray get() = frame.copyOf()

    init {
        require(rightSideByte in BYTE_RANGE && leftSideByte in BYTE_RANGE) { "A device-type byte is 0 to 255" }
        require(rightSideByte != leftSideByte) { "The two pucks need different device-type bytes" }
        require(requestedMtu == null || requestedMtu >= MIN_MTU) { "The MTU request must be at least $MIN_MTU" }
        require(
            keepAliveInterval == null || keepAliveInterval.isPositive(),
        ) { "The keep-alive interval must be positive" }
        require(handshakeTimeout.isPositive() && noEventTimeout.isPositive() && operationTimeout.isPositive()) {
            "Timeouts must be positive"
        }
    }

    public companion object {
        /** §1: `0x0A`, right side, "unconfirmed". */
        public const val DEFAULT_RIGHT_SIDE_BYTE: Int = 0x0A

        /** §1: `0x0B`, left side, "unconfirmed". */
        public const val DEFAULT_LEFT_SIDE_BYTE: Int = 0x0B

        /** Room for `RideOn`, two version bytes and a 64-byte key (§3.1) with the ATT header. */
        public const val DEFAULT_MTU: Int = 128

        private const val MIN_MTU = 23
        private val BYTE_RANGE = 0..255
    }
}
