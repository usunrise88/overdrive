package app.overdrive.ride.settings

import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickSide
import app.overdrive.core.click.HandshakeReplyCheck
import app.overdrive.core.control.GestureTiming
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often the app writes the neutral frame to the Click while it is connected (developer setting; `ClickOptions
 * .keepAliveInterval`). **Off by default**: the licensed code has no keep-alive toward the controller, and whether the right
 * puck needs one is bench question CK 7 (docs/protocols/click-v2.md §9).
 */
enum class ClickKeepAlive(
    val label: String,
    val interval: Duration?,
) {
    Off("Off", null),
    Every10("10 s", 10.seconds),
    Every30("30 s", 30.seconds),
    Every60("60 s", 60.seconds),
}

/** How long a Click that answered the handshake may send nothing before it counts as locked (`ClickOptions.noEventTimeout`). */
enum class ClickSilence(
    val label: String,
    val timeout: Duration,
) {
    Short("15 s", 15.seconds),
    Normal("30 s", 30.seconds),
    Long("60 s", 60.seconds),
    VeryLong("2 min", 120.seconds),
}

/** The ATT MTU asked for at connect (`ClickOptions.requestedMtu`); "System" asks for nothing (CK 3). */
enum class ClickMtu(
    val label: String,
    val mtu: Int?,
) {
    System("System", null),
    Medium("128", ClickOptions.DEFAULT_MTU),
    Large("247", 247),
}

/** Which pucks a scan lists (`ClickOptions.acceptedSides`). The app drives the ride from the right one; "Either" is for CK 1. */
enum class ClickPucks(
    val label: String,
    val sides: Set<ClickSide>,
) {
    Right("Right", setOf(ClickSide.RIGHT)),
    Either("Either", setOf(ClickSide.RIGHT, ClickSide.LEFT)),
}

/** How strictly the handshake reply is checked (`ClickOptions.handshakeReply`, CK 2 to 4). */
enum class ClickReplyCheck(
    val label: String,
    val check: HandshakeReplyCheck,
) {
    Strict("Exact", HandshakeReplyCheck.EXPECTED_VERSION),
    Lenient("Any RideOn", HandshakeReplyCheck.ANY_RIDE_ON),
}

/**
 * What the owner flips at the bench **without a rebuild** (developer settings, ADR 0012): the Click's connection options
 * that the bench questions of docs/protocols/click-v2.md §9 can only settle by trying, and the two gesture timings
 * (spec §4.7). Every default is the one `ClickOptions` and `GestureTiming` have, so a fresh install behaves as the notes
 * recommend; every value is brought into its range by [sanitized] (a damaged or edited settings file can never hand the
 * Click or the gesture layer something they refuse), and [toOptions] and [toTiming] build what `core` takes.
 *
 * A change reaches the Click by replacing its device (a short reconnect), and the gesture layer by replacing its detector.
 */
data class ClickSettings(
    /** Write `FF 04 00` after the handshake (CK 6). */
    val sendPostHandshake: Boolean = true,
    val keepAlive: ClickKeepAlive = ClickKeepAlive.Off,
    val noEventTimeout: ClickSilence = ClickSilence.Normal,
    val mtu: ClickMtu = ClickMtu.Medium,
    /** Write the handshake with a response instead of without (CK 2). */
    val writeWithResponse: Boolean = false,
    /** A scan result needs the Zwift service in its advertisement (CK 12). */
    val requireAdvertisedService: Boolean = true,
    val pucks: ClickPucks = ClickPucks.Right,
    val replyCheck: ClickReplyCheck = ClickReplyCheck.Strict,
    /** A press held at least this long is a hold, in milliseconds (spec §4.7: 500). */
    val holdThresholdMillis: Int = GestureTiming.HOLD_THRESHOLD_MILLIS.toInt(),
    /** One volume step this often while a volume button is held, in milliseconds (spec §4.7: 150). */
    val volumeRepeatMillis: Int = GestureTiming.VOLUME_REPEAT_MILLIS.toInt(),
) {
    /** The same settings with the two timings inside their ranges and on their steps. */
    fun sanitized(): ClickSettings =
        copy(
            holdThresholdMillis = ClickRules.holdThreshold.normalize(holdThresholdMillis),
            volumeRepeatMillis = ClickRules.volumeRepeat.normalize(volumeRepeatMillis),
        )

    /** The connection options of `core`'s `ClickDevice`. */
    fun toOptions(): ClickOptions =
        ClickOptions(
            requestedMtu = mtu.mtu,
            writeWithResponse = writeWithResponse,
            handshakeReply = replyCheck.check,
            sendPostHandshake = sendPostHandshake,
            keepAliveInterval = keepAlive.interval,
            noEventTimeout = noEventTimeout.timeout,
            requireAdvertisedService = requireAdvertisedService,
            acceptedSides = pucks.sides,
        )

    /** The gesture timings of `core`'s `HandlebarControls`. */
    fun toTiming(): GestureTiming =
        GestureTiming(
            holdThresholdMillis = holdThresholdMillis.toLong(),
            repeatIntervalMillis = volumeRepeatMillis.toLong(),
        )
}

/** The safe ranges of the two gesture timings (design/system.md §6.6). The spec's values are 500 ms and 150 ms. */
object ClickRules {
    /** Hold threshold in ms, 250 to 1000 in steps of 50: below a quarter second a firm tap is a hold. */
    val holdThreshold = IntRule(min = 250, max = 1000, step = 50)

    /** Volume repeat in ms, 50 to 500 in steps of 10: faster than 50 ms is a burst of steps, slower than half a second is not a repeat. */
    val volumeRepeat = IntRule(min = 50, max = 500, step = 10)
}
