package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.click.ClickState
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.TimeSource

/** What one role's link is doing, for one row: the paired device, its link status and how long a retry wait has left. */
internal data class RoleState(
    val device: RememberedDevice?,
    val status: LinkStatus?,
    /** Seconds until the next attempt while [status] is a retry wait; null otherwise. */
    val retryInSeconds: Int?,
)

/** What the Click is doing, for its row: the paired puck, its state, its battery and the retry countdown. */
internal data class ClickRowState(
    val device: RememberedDevice?,
    val state: ClickState?,
    val battery: Int?,
    /** Seconds until the next attempt while [state] is a retry wait; null otherwise. */
    val retryInSeconds: Int?,
)

/** The link states of `core` in the words of the Devices screen (design/system.md §7.7). Pure, so every state is a test. */
object BleDeviceRows {
    /** Why Bluetooth cannot be used, or null when it can. */
    fun radioIssue(availability: BleAvailability): RadioIssue? =
        when (availability) {
            BleAvailability.READY -> null
            BleAvailability.BLUETOOTH_OFF -> RadioIssue.BluetoothOff
            BleAvailability.PERMISSION_DENIED -> RadioIssue.PermissionNeeded
            BleAvailability.UNSUPPORTED -> RadioIssue.Unsupported
        }

    /**
     * The row of [role]: [name] is the paired device (null: nothing paired), [status] its link, [retryInSeconds] the
     * seconds left of a retry wait. Bluetooth that cannot be used comes before everything about the link, because nothing
     * is tried meanwhile and the link status says nothing true.
     */
    fun row(
        role: DeviceRole,
        name: String?,
        status: LinkStatus?,
        availability: BleAvailability,
        retryInSeconds: Int? = null,
    ): DeviceRowUi {
        if (name == null) return DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
        blockedRow(name, availability)?.let { return it }
        return when (status) {
            LinkStatus.Connected -> {
                DeviceRowUi(DeviceStatus.Connected, name, canForget = true)
            }

            is LinkStatus.WaitingToRetry -> {
                DeviceRowUi(DeviceStatus.Lost, retryText(role, status, retryInSeconds), canForget = true)
            }

            // A sleeping device that the controller is waiting for (ADR 0040) is not a failure: no "lost", no countdown.
            LinkStatus.WaitingForDevice -> {
                DeviceRowUi(DeviceStatus.Searching, wakeText(role), canForget = true)
            }

            is LinkStatus.BluetoothUnavailable -> {
                // The environment normally says so first; this is the supervisor catching up.
                DeviceRowUi(DeviceStatus.Searching, "Connecting to $name…", canForget = true)
            }

            is LinkStatus.Connecting, LinkStatus.Stopped, null -> {
                DeviceRowUi(DeviceStatus.Searching, "Connecting to $name…", canForget = true)
            }
        }
    }

    /**
     * The Click's row (ADR 0012, spec §4.7), in plain words:
     *
     * | State | Row |
     * |---|---|
     * | not paired | Not paired, **Pair** |
     * | Bluetooth off, permission missing | the same blocked row as the others |
     * | connecting, or connected and waiting for a first message | searching: "Connecting to …" or "… · press any button" |
     * | connected and delivering | connected: the name, with "battery 87%" once the Click said |
     * | not found, or a link that dropped, or a set-up that failed | "Not found · retrying in 8 s", "Connection lost · …", "Could not connect · …" and **Retry now** |
     * | locked (the Zwift service is hidden, or it answers and says nothing) | locked: "… · locked" and the unlock notice below the row |
     *
     * Nothing about the Click is ever an error for the ride: every state but "connected" means "carry on with the on-screen
     * controls".
     */
    fun clickRow(
        name: String?,
        state: ClickState?,
        battery: Int?,
        availability: BleAvailability,
        retryInSeconds: Int? = null,
    ): DeviceRowUi {
        if (name == null) return DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
        blockedRow(name, availability)?.let { return it }
        val wait = waitText(retryInSeconds)
        return when (state) {
            ClickState.Active -> {
                val level = battery?.takeIf { it in 0..PERCENT }?.let { " · battery $it%" }.orEmpty()
                DeviceRowUi(DeviceStatus.Connected, "$name$level", canForget = true)
            }

            ClickState.Waiting -> {
                DeviceRowUi(DeviceStatus.Searching, "$name · press any button", canForget = true)
            }

            is ClickState.NotFound -> {
                val lead = if (state.wasConnected) "Connection lost" else "Not found"
                DeviceRowUi(DeviceStatus.Lost, "$lead · $wait", canForget = true)
            }

            is ClickState.SetupFailed -> {
                DeviceRowUi(DeviceStatus.Lost, "Could not connect · $wait", canForget = true)
            }

            is ClickState.NeedsUnlock -> {
                DeviceRowUi(DeviceStatus.Locked, "$name · locked", canForget = true, locked = state.signal)
            }

            ClickState.WaitingForDevice -> {
                DeviceRowUi(DeviceStatus.Searching, wakeText(DeviceRole.Click), canForget = true)
            }

            is ClickState.BluetoothUnavailable, is ClickState.Connecting, ClickState.Stopped, null -> {
                DeviceRowUi(DeviceStatus.Searching, "Connecting to $name…", canForget = true)
            }
        }
    }

    /** The row for a paired device while Bluetooth cannot be used, or null when it can. */
    private fun blockedRow(
        name: String,
        availability: BleAvailability,
    ): DeviceRowUi? {
        val text =
            when (availability) {
                BleAvailability.READY -> return null
                BleAvailability.BLUETOOTH_OFF -> "$name · Bluetooth is off"
                BleAvailability.PERMISSION_DENIED -> "$name · needs Nearby devices"
                BleAvailability.UNSUPPORTED -> "$name · no Bluetooth on this tablet"
            }
        return DeviceRowUi(DeviceStatus.Blocked, text, canForget = true)
    }

    /** What a row says while a background connect waits for a sleeping device (ADR 0040). */
    internal fun wakeText(role: DeviceRole): String =
        when (role) {
            DeviceRole.Trainer -> "Waiting for the trainer to wake up"
            DeviceRole.Strap -> "Waiting for the strap to wake up"
            DeviceRole.Click -> "Waiting for the Click to wake up"
        }

    private fun waitText(seconds: Int?): String =
        if (seconds != null &&
            seconds > 0
        ) {
            "retrying in $seconds s"
        } else {
            "retrying…"
        }

    private fun retryText(
        role: DeviceRole,
        status: LinkStatus.WaitingToRetry,
        seconds: Int?,
    ): String {
        val wait = waitText(seconds)
        return when (status.failure.kind) {
            LinkFailureKind.LINK_LOST -> {
                "Connection lost · $wait"
            }

            LinkFailureKind.CONNECT_FAILED, LinkFailureKind.TIMED_OUT -> {
                "Not found · $wait"
            }

            LinkFailureKind.SETUP_FAILED -> {
                "Could not connect · $wait"
            }

            LinkFailureKind.SERVICE_MISSING -> {
                if (role == DeviceRole.Trainer) {
                    "Not a trainer · check the pairing"
                } else {
                    "Not a heart-rate strap · check the pairing"
                }
            }

            LinkFailureKind.UNAVAILABLE -> {
                "Waiting for Bluetooth · $wait"
            }
        }
    }

    private const val PERCENT = 100
}

/**
 * The Devices screen's and the top bar's rows for Bluetooth mode (ADR 0010, ADR 0012): the paired devices' link states and
 * the radio, as [DevicesUi]. A link that is waiting to retry counts its seconds down once a second, only while it waits; at
 * rest nothing ticks. The Click's row is its own [app.overdrive.core.click.ClickState] in plain words (and **a locked Click
 * is a row and a notice, never a failure of the ride**).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleDevicesModel(
    private val devices: PairedDevices,
    private val environment: BleEnvironment,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val ui: Flow<DevicesUi> =
        combine(
            roleState(
                devices.trainer.flatMapLatest { t ->
                    t?.device?.status?.map { t.remembered to it } ?: flowOf(null)
                },
            ),
            roleState(
                devices.strap.flatMapLatest { s ->
                    s?.device?.status?.map { s.remembered to it } ?: flowOf(null)
                },
            ),
            clickState(),
            environment.availability,
            devices.loaded,
        ) { trainer, strap, click, availability, loaded ->
            if (!loaded) {
                DevicesUi.Unknown
            } else {
                DevicesUi(
                    trainer =
                        BleDeviceRows.row(
                            DeviceRole.Trainer,
                            trainer.device?.name,
                            trainer.status,
                            availability,
                            trainer.retryInSeconds,
                        ),
                    heartRate =
                        BleDeviceRows.row(
                            DeviceRole.Strap,
                            strap.device?.name,
                            strap.status,
                            availability,
                            strap.retryInSeconds,
                        ),
                    click =
                        BleDeviceRows.clickRow(
                            click.device?.name,
                            click.state,
                            click.battery,
                            availability,
                            click.retryInSeconds,
                        ),
                    radio = BleDeviceRows.radioIssue(availability),
                )
            }
        }

    /** One role's link as a [RoleState], with a once-a-second countdown while it waits to retry. */
    private fun roleState(link: Flow<Pair<RememberedDevice, LinkStatus>?>): Flow<RoleState> =
        link.flatMapLatest { pair ->
            val status = pair?.second
            when {
                pair == null -> {
                    flowOf(RoleState(null, null, null))
                }

                status is LinkStatus.WaitingToRetry -> {
                    countdown(status.retryInMillis).map { RoleState(pair.first, status, it) }
                }

                else -> {
                    flowOf(RoleState(pair.first, status, null))
                }
            }
        }

    /** The Click's state, its battery and the countdown of a retry wait, for its row. */
    private fun clickState(): Flow<ClickRowState> =
        devices.click.flatMapLatest { paired ->
            if (paired == null) {
                flowOf(ClickRowState(null, null, null, null))
            } else {
                // The battery is combined after the countdown, so a battery report never restarts a retry wait.
                val counting: Flow<Pair<ClickState, Int?>> =
                    paired.device.state.flatMapLatest { state ->
                        val wait =
                            when (state) {
                                is ClickState.NotFound -> state.retryInMillis
                                is ClickState.SetupFailed -> state.retryInMillis
                                else -> null
                            }
                        if (wait == null) flowOf(state to null) else countdown(wait).map { state to it }
                    }
                combine(counting, paired.device.battery) { (state, seconds), battery ->
                    ClickRowState(paired.remembered, state, battery, seconds)
                }
            }
        }

    /** The whole seconds left of [retryInMillis], once a second, down to 0; it ends there. */
    private fun countdown(retryInMillis: Long): Flow<Int> =
        flow {
            val begun = timeSource.markNow()
            while (true) {
                val left = retryInMillis - begun.elapsedNow().inWholeMilliseconds
                val seconds = ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)
                emit(seconds)
                if (left <= 0) break
                // Until the displayed second changes: the milliseconds above the next whole second, and one more.
                delay(left - (seconds - 1) * MILLIS_PER_SECOND + 1)
            }
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
