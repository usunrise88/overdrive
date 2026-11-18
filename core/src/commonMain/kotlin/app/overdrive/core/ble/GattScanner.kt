package app.overdrive.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One advertisement heard during a scan. [id] is the opaque handle the transport understands; there is no address, and
 * nothing here is written to a recorded session.
 */
public data class Advertisement(
    val id: DeviceId,
    /** The advertised local name, such as `D500`, or null when the device did not send one. */
    val name: String?,
    /** Service UUIDs in the advertisement; a trainer lists the Fitness Machine Service, a strap the Heart Rate Service. */
    val serviceUuids: Set<GattUuid>,
    /** Signal strength in dBm, for sorting the list nearest first. */
    val rssi: Int,
)

/** What to scan for. An empty set means everything. */
public data class ScanFilter(
    val serviceUuids: Set<GattUuid> = emptySet(),
) {
    /** Whether an advertisement with [advertised] services passes: any listed service is enough. */
    public fun accepts(advertised: Set<GattUuid>): Boolean =
        serviceUuids.isEmpty() || advertised.any { it in serviceUuids }
}

/** Why a scan stopped by itself. */
public enum class ScanFailure {
    /** Bluetooth is off. */
    BLUETOOTH_OFF,

    /** The permission to scan was refused or revoked. */
    PERMISSION_DENIED,

    /** The platform refused the scan (too many scans started, the stack is busy…). */
    REFUSED,
}

/** What a scan reports: advertisements, and at most one failure which ends the flow. */
public sealed interface ScanEvent {
    /** A device was heard. The same device is reported again for every advertisement. */
    public data class Found(
        val advertisement: Advertisement,
    ) : ScanEvent

    /** The scan failed and the flow completes. */
    public data class Failed(
        val reason: ScanFailure,
    ) : ScanEvent
}

/** Finds peripherals for the Devices screen (spec §4.1 "Pairing"). Implemented on Android by `BluetoothLeScanner`. */
public interface GattScanner {
    /**
     * A cold flow: collecting it starts a scan, cancelling the collector stops it. The flow ends after a
     * [ScanEvent.Failed]. It does not deduplicate; the caller keeps the latest advertisement per [Advertisement.id].
     */
    public fun scan(filter: ScanFilter = ScanFilter()): Flow<ScanEvent>
}

/**
 * Whether Bluetooth can be used right now, in the terms the Devices screen explains (spec §4.1): no Android types.
 */
public enum class BleAvailability {
    /** Bluetooth is on and the permissions are granted. */
    READY,

    /** The adapter is switched off (or turning on). */
    BLUETOOTH_OFF,

    /** "Nearby devices" (Android 12+) was refused or revoked. */
    PERMISSION_DENIED,

    /** The tablet has no Bluetooth LE. */
    UNSUPPORTED,
}

/** The state of the radio and the permissions. The transport side implements it; the reconnect supervisor reads it. */
public interface BleEnvironment {
    /** Changes when the user switches Bluetooth off or on, or grants or revokes the permission. */
    public val availability: StateFlow<BleAvailability>
}
