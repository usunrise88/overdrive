package app.overdrive.core.ble.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How a [LinkSupervisor] connects to a device that may be asleep (docs/adr/0040-background-connect-for-sleepy-devices.md).
 *
 * **Background (hybrid, the default of the ride app).** The first attempt of a connection is **active**
 * (`ConnectMode.ACTIVE`, limited to [activeAttempt], 10 s): fast for a device that is awake. When it does not succeed
 * the supervisor switches to a **background connect** (`ConnectMode.BACKGROUND`, `autoConnect = true` on Android): no
 * limit of the app's own while Bluetooth is on and the device is wanted, so that the controller connects the moment a
 * woken Zwift Click, strap or trainer advertises. A link that drops (the Click going to sleep) goes straight to the
 * background connect. The known pitfalls of `autoConnect` are guarded: a pending connect is recycled after
 * [backgroundWatchdog] (the GATT object is closed and a new one opened), there is one outstanding connect per device, a
 * failed or dropped GATT object is closed and replaced and never reused, and a rider's **Retry** replaces a pending
 * background connect by an active attempt at once.
 *
 * **Legacy ([background] false).** Every attempt is active and ends after the transport's own limit; the wait between
 * attempts is the exponential backoff of [BackoffPolicy]. This is what 0.4.0 did, kept behind Settings, Developer,
 * "Connect in the background".
 */
public data class ConnectStrategy(
    /** Whether a connection that does not succeed at once becomes a background connect. */
    val background: Boolean = false,
    /** How long the first, active attempt may take before the supervisor hands over to the background connect. */
    val activeAttempt: Duration = DEFAULT_ACTIVE_ATTEMPT,
    /** How long one background connect may stay pending before it is closed and opened again (a stale pending connection). */
    val backgroundWatchdog: Duration = DEFAULT_BACKGROUND_WATCHDOG,
    /** The pause between closing an attempt and opening the next one, long enough for the stack to tear the old object down. */
    val settle: Duration = DEFAULT_SETTLE,
) {
    init {
        require(activeAttempt.isPositive()) { "The active attempt must be positive, got $activeAttempt" }
        require(backgroundWatchdog.isPositive()) { "The watchdog must be positive, got $backgroundWatchdog" }
        require(!settle.isNegative()) { "The settle time must not be negative, got $settle" }
    }

    public companion object {
        public val DEFAULT_ACTIVE_ATTEMPT: Duration = 10.seconds
        public val DEFAULT_BACKGROUND_WATCHDOG: Duration = 10.minutes
        public val DEFAULT_SETTLE: Duration = 1.seconds

        /** The 0.4.0 behaviour: active attempts only, with backoff between them. */
        public val LEGACY: ConnectStrategy = ConnectStrategy(background = false)

        /** Active first, then background. */
        public val HYBRID: ConnectStrategy = ConnectStrategy(background = true)
    }
}
