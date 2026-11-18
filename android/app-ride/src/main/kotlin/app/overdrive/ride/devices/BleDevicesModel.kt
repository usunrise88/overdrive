package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DeviceRows
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.ride.settings.SimulatedClick
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
        val blocked =
            when (availability) {
                BleAvailability.READY -> null
                BleAvailability.BLUETOOTH_OFF -> "$name · Bluetooth is off"
                BleAvailability.PERMISSION_DENIED -> "$name · needs Nearby devices"
                BleAvailability.UNSUPPORTED -> "$name · no Bluetooth on this tablet"
            }
        if (blocked != null) return DeviceRowUi(DeviceStatus.Blocked, blocked, canForget = true)
        return when (status) {
            LinkStatus.Connected -> {
                DeviceRowUi(DeviceStatus.Connected, name, canForget = true)
            }

            is LinkStatus.WaitingToRetry -> {
                DeviceRowUi(DeviceStatus.Lost, retryText(role, status, retryInSeconds), canForget = true)
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

    private fun retryText(
        role: DeviceRole,
        status: LinkStatus.WaitingToRetry,
        seconds: Int?,
    ): String {
        val wait = if (seconds != null && seconds > 0) "retrying in $seconds s" else "retrying…"
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
                if (role ==
                    DeviceRole.Trainer
                ) {
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
}

/**
 * The Devices screen's and the top bar's rows for Bluetooth mode (ADR 0010): the paired devices' link states and the radio,
 * as [DevicesUi]. A link that is waiting to retry counts its seconds down once a second, only while it waits; at rest
 * nothing ticks.
 *
 * @param click the Click row is the simulated one until M4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleDevicesModel(
    private val devices: PairedDevices,
    private val environment: BleEnvironment,
    private val click: Flow<SimulatedClick>,
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
            environment.availability,
            click,
            devices.loaded,
        ) { trainer, strap, availability, clickState, loaded ->
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
                    click = DeviceRows.clickRow(clickState),
                    radio = BleDeviceRows.radioIssue(availability),
                )
            }
        }

    /** One role's link as a [RoleState], with a once-a-second countdown while it waits to retry. */
    private fun roleState(link: Flow<Pair<RememberedDevice, LinkStatus>?>): Flow<RoleState> =
        link.flatMapLatest { pair ->
            when {
                pair == null -> {
                    flowOf(RoleState(null, null, null))
                }

                pair.second is LinkStatus.WaitingToRetry -> {
                    countdown(
                        pair.first,
                        pair.second as LinkStatus.WaitingToRetry,
                    )
                }

                else -> {
                    flowOf(RoleState(pair.first, pair.second, null))
                }
            }
        }

    private fun countdown(
        device: RememberedDevice,
        status: LinkStatus.WaitingToRetry,
    ): Flow<RoleState> =
        flow {
            val begun = timeSource.markNow()
            while (true) {
                val left = status.retryInMillis - begun.elapsedNow().inWholeMilliseconds
                val seconds = ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)
                emit(RoleState(device, status, seconds))
                if (left <= 0) break
                // Until the displayed second changes: the milliseconds above the next whole second, and one more.
                delay(left - (seconds - 1) * MILLIS_PER_SECOND + 1)
            }
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
