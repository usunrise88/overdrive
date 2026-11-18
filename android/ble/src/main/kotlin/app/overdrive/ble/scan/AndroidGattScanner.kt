package app.overdrive.ble.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.SparseArray
import app.overdrive.ble.gatt.BleLog
import app.overdrive.ble.gatt.logName
import app.overdrive.ble.gatt.toGattUuid
import app.overdrive.ble.gatt.toUuid
import app.overdrive.core.ble.Advertisement
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattScanner
import app.overdrive.core.ble.ManufacturerData
import app.overdrive.core.ble.ScanEvent
import app.overdrive.core.ble.ScanFailure
import app.overdrive.core.ble.ScanFilter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import android.bluetooth.le.ScanFilter as PlatformScanFilter

/** Scan results and errors in the terms of `core`, as plain functions. */
internal object ScanMapping {
    /**
     * One heard advertisement. The address becomes the opaque [DeviceId] here and nowhere else; the name is the one in
     * the advertisement (`ScanRecord.getDeviceName`, which needs no permission), blank counted as none.
     */
    fun advertisement(
        address: String?,
        name: String?,
        services: List<UUID>?,
        rssi: Int,
        manufacturer: List<ManufacturerData> = emptyList(),
    ): Advertisement? {
        if (address.isNullOrBlank()) return null
        return Advertisement(
            id = DeviceId(address),
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            serviceUuids = services.orEmpty().mapNotNull { it.toGattUuid() }.toSet(),
            rssi = rssi,
            manufacturerData = manufacturer,
        )
    }

    /**
     * The manufacturer-specific data of a scan record (`ScanRecord.getManufacturerSpecificData`: the 16-bit company
     * identifier, little-endian in the air and already decoded by the platform, mapped to the bytes after it). The Zwift
     * Click is recognised by company `0x094A` and the first byte of its payload (docs/protocols/click-v2.md §1), never by
     * name, so without this the Click could not be told from any other device. Ordered by company identifier; a null or
     * empty record is no data. Nothing here is printed: the payload may carry a unit's own identifier.
     */
    fun manufacturerData(records: SparseArray<ByteArray>?): List<ManufacturerData> {
        if (records == null) return emptyList()
        return (0 until records.size()).map { index ->
            ManufacturerData(records.keyAt(index), records.valueAt(index) ?: ByteArray(0))
        }
    }

    /**
     * Why the platform stopped a scan (`ScanCallback.SCAN_FAILED_*`). Every platform code is "refused" for the screen's
     * purposes (already started, registration failed, internal error, unsupported, out of resources, too frequent); a
     * radio that has gone off or lost its permission since is reported as that instead.
     */
    fun failure(availability: BleAvailability): ScanFailure = unavailable(availability) ?: ScanFailure.REFUSED

    /** Why a scan cannot start at all, from the environment, or null when it can. */
    fun unavailable(availability: BleAvailability): ScanFailure? =
        when (availability) {
            BleAvailability.READY -> null
            BleAvailability.PERMISSION_DENIED -> ScanFailure.PERMISSION_DENIED
            BleAvailability.BLUETOOTH_OFF, BleAvailability.UNSUPPORTED -> ScanFailure.BLUETOOTH_OFF
        }
}

/**
 * [GattScanner] over `BluetoothLeScanner`: **low-latency mode, only while the flow is collected**. The Devices screen
 * is the only collector, collects while it is open and for 30 seconds at most, and the scan stops when the collector
 * goes (the screen was left, the time ran out, the rider picked something).
 *
 * The scan filters by the services in [ScanFilter.serviceUuids] (the Fitness Machine, Heart Rate and Zwift Click
 * services; an empty set scans everything, which the Devices screen asks for when the Click's service is not required):
 * a peripheral that advertises none of them is not listed (unverified for the D500, the HR7 and the Click, ADR 0010, ADR
 * 0012). Each result carries its manufacturer-specific data, which is how a Click is told from any other device. It relies on
 * `neverForLocation` in the manifest, so no location permission is asked; Android may then drop some beacon
 * advertisements from the results, which does not matter for a trainer or a strap.
 *
 * A scan that cannot start says why as one [ScanEvent.Failed] and completes, like the fake does.
 */
public class AndroidGattScanner internal constructor(
    private val adapter: () -> BluetoothAdapter?,
    private val environment: BleEnvironment,
    private val log: BleLog,
) : GattScanner {
    @SuppressLint("MissingPermission")
    override fun scan(filter: ScanFilter): Flow<ScanEvent> =
        callbackFlow {
            val unavailable = ScanMapping.unavailable(environment.availability.value)
            if (unavailable != null) {
                fail(unavailable)
                return@callbackFlow
            }
            val scanner =
                try {
                    adapter()?.bluetoothLeScanner
                } catch (e: Exception) {
                    log.log("scan: no scanner (${e.logName()})")
                    null
                }
            if (scanner == null) {
                fail(ScanFailure.BLUETOOTH_OFF)
                return@callbackFlow
            }
            val callback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult?,
                    ) {
                        result?.let(::heard)
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                        results?.forEach(::heard)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        log.log("scan: the platform refused it, code $errorCode")
                        fail(ScanMapping.failure(environment.availability.value))
                    }

                    private fun heard(result: ScanResult) {
                        val advertisement =
                            ScanMapping.advertisement(
                                address = result.device?.address,
                                name = result.scanRecord?.deviceName,
                                services = result.scanRecord?.serviceUuids?.map { it.uuid },
                                rssi = result.rssi,
                                manufacturer =
                                    ScanMapping.manufacturerData(
                                        result.scanRecord?.manufacturerSpecificData,
                                    ),
                            ) ?: return
                        trySend(ScanEvent.Found(advertisement))
                    }
                }
            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            val filters =
                filter.serviceUuids.map {
                    PlatformScanFilter.Builder().setServiceUuid(ParcelUuid(it.toUuid())).build()
                }
            try {
                scanner.startScan(filters, settings, callback)
                log.log("scan: started, ${filters.size} service filters")
            } catch (e: SecurityException) {
                log.log("scan: needs a permission (${e.logName()})")
                fail(ScanFailure.PERMISSION_DENIED)
                return@callbackFlow
            } catch (e: Exception) {
                log.log("scan: could not start (${e.logName()})")
                fail(ScanFailure.REFUSED)
                return@callbackFlow
            }
            awaitClose {
                try {
                    scanner.stopScan(callback)
                    log.log("scan: stopped")
                } catch (_: Exception) {
                    // Bluetooth went off or the permission went: the scan is over either way.
                }
            }
        }.buffer(BUFFER, BufferOverflow.DROP_OLDEST)

    private fun ProducerScope<ScanEvent>.fail(reason: ScanFailure) {
        trySend(ScanEvent.Failed(reason))
        close()
    }

    private companion object {
        const val BUFFER = 128
    }
}
