package app.overdrive.ride.devices

import androidx.compose.runtime.Immutable
import app.overdrive.core.ble.Advertisement
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattScanner
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.ScanEvent
import app.overdrive.core.ble.ScanFailure
import app.overdrive.core.ble.ScanFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One trainer or strap heard during a scan. [id] is opaque; nothing here prints an address. */
@Immutable
data class FoundDevice(
    val id: DeviceId,
    val role: DeviceRole,
    /** The advertised name, or the role's plain name when it sent none. */
    val name: String,
    /** Signal strength in dBm, in steps of 5 so the list does not flicker. */
    val rssi: Int,
    /** This is the device that is paired for [role] now. */
    val paired: Boolean,
)

/** What the scan panel of the Devices screen shows. */
@Immutable
data class PairingUi(
    val scanning: Boolean = false,
    val trainers: List<FoundDevice> = emptyList(),
    val straps: List<FoundDevice> = emptyList(),
    /** Why the scan ended by itself, if it did. */
    val failure: ScanFailure? = null,
    /** The scan ran out of time. With nothing found it is the screen's cue to say what to check. */
    val timedOut: Boolean = false,
    /** A scan has run since the screen was opened, so an empty list means "nothing found", not "not looked yet". */
    val scanned: Boolean = false,
) {
    val isEmpty: Boolean get() = trainers.isEmpty() && straps.isEmpty()
}

/**
 * Scans, lists and pairs: the Devices screen's side of spec §4.1 "Pairing". One scan at a time, low-latency, **only while the
 * screen asks for it**: [scan] starts it, [stop] (the screen was left) or the [scanTimeout] ends it, and a failure ends it
 * with a reason. Trainers (the Fitness Machine Service in the advertisement) and straps (the Heart Rate Service) are listed
 * by advertised name, each once, with the signal as it was last heard; a device not heard for [staleAfter] drops out.
 *
 * [pair] and [forget] go to [PairedDevices] and are ignored while [canChange] says a ride is live: a ride never changes
 * its devices.
 */
class PairingController(
    private val scope: CoroutineScope,
    private val scanner: GattScanner,
    private val devices: PairedDevices,
    private val canChange: () -> Boolean = { true },
    private val scanTimeout: Duration = 30.seconds,
    private val staleAfter: Duration = 12.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** An advertisement and when it was heard. */
    private class Heard(
        val advertisement: Advertisement,
        val at: TimeMark,
    )

    // Updated atomically (`update`), because the scan collector and the sweeper run on different threads of the scope.
    private val heard = MutableStateFlow<Map<DeviceId, Heard>>(emptyMap())
    private val progress = MutableStateFlow(Progress())
    private var job: Job? = null

    private data class Progress(
        val scanning: Boolean = false,
        val failure: ScanFailure? = null,
        val timedOut: Boolean = false,
        val scanned: Boolean = false,
    )

    /** The scan panel's state: what was heard, which of it is paired, and how the scan is doing. */
    val state: StateFlow<PairingUi> =
        combine(heard, progress, devices.trainer, devices.strap) { found, progress, trainer, strap ->
            val trainers = mutableListOf<FoundDevice>()
            val straps = mutableListOf<FoundDevice>()
            for (advertisement in found.values.map { it.advertisement }) {
                val role = roleOf(advertisement) ?: continue
                val pairedId = (if (role == DeviceRole.Trainer) trainer?.remembered else strap?.remembered)?.id
                val device =
                    FoundDevice(
                        id = advertisement.id,
                        role = role,
                        name = cleanDeviceName(advertisement.name, role),
                        rssi = (advertisement.rssi / RSSI_STEP.toDouble()).roundToInt() * RSSI_STEP,
                        paired = advertisement.id == pairedId,
                    )
                if (role == DeviceRole.Trainer) trainers += device else straps += device
            }
            PairingUi(
                scanning = progress.scanning,
                trainers = trainers.sortedWith(compareBy({ it.name }, { -it.rssi })),
                straps = straps.sortedWith(compareBy({ it.name }, { -it.rssi })),
                failure = progress.failure,
                timedOut = progress.timedOut,
                scanned = progress.scanned,
            )
        }.stateIn(scope, SharingStarted.Eagerly, PairingUi())

    /** Starts a scan unless one is running. The list starts empty. */
    fun scan() {
        if (job?.isActive == true) return
        heard.value = emptyMap()
        progress.value = Progress(scanning = true, scanned = true)
        job =
            scope.launch {
                var failure: ScanFailure? = null
                val completed =
                    withTimeoutOrNull(scanTimeout) {
                        coroutineScope {
                            // A device that has not been heard for a while has gone, also when nothing else is heard.
                            val sweeper =
                                launch {
                                    while (true) {
                                        delay(SWEEP_INTERVAL)
                                        heard.update { it.filterValues { entry -> entry.isAlive() } }
                                    }
                                }
                            scanner.scan(FILTER).collect { event ->
                                when (event) {
                                    is ScanEvent.Found -> {
                                        val entry = Heard(event.advertisement, timeSource.markNow())
                                        heard.update { current ->
                                            (current + (entry.advertisement.id to entry)).filterValues { it.isAlive() }
                                        }
                                    }

                                    is ScanEvent.Failed -> {
                                        failure = event.reason
                                    }
                                }
                            }
                            sweeper.cancel()
                        }
                        true
                    }
                progress.value =
                    Progress(scanning = false, failure = failure, timedOut = completed == null, scanned = true)
            }
    }

    private fun Heard.isAlive(): Boolean = at.elapsedNow() <= staleAfter

    /** Ends the scan (the screen was left, or the rider is done). The list stays until the next scan. */
    fun stop() {
        job?.cancel()
        job = null
        if (progress.value.scanning) progress.value = progress.value.copy(scanning = false)
    }

    /** Pairs [device] for its role, replacing the old one. */
    fun pair(device: FoundDevice) {
        if (!canChange() || device.paired) return
        scope.launch { devices.pair(device.role, RememberedDevice(device.id, device.name)) }
    }

    /** Forgets the device of [role]. */
    fun forget(role: DeviceRole) {
        if (!canChange()) return
        scope.launch { devices.forget(role) }
    }

    private fun roleOf(advertisement: Advertisement): DeviceRole? =
        when {
            FtmsUuids.SERVICE in advertisement.serviceUuids -> DeviceRole.Trainer
            HeartRateUuids.SERVICE in advertisement.serviceUuids -> DeviceRole.Strap
            else -> null
        }

    private companion object {
        /** The services of a trainer and of a strap; the scanner filters on them (docs/adr/0010). */
        val FILTER = ScanFilter(setOf(FtmsUuids.SERVICE, HeartRateUuids.SERVICE))
        const val RSSI_STEP = 5
        val SWEEP_INTERVAL = 2.seconds
    }
}
