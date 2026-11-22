package app.overdrive.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import app.overdrive.ble.env.AndroidBleEnvironment
import app.overdrive.ble.gatt.AndroidGattTransport
import app.overdrive.ble.gatt.BleLog
import app.overdrive.ble.gatt.BluetoothGattOpener
import app.overdrive.ble.gatt.GattTimings
import app.overdrive.ble.gatt.PlatformGattOpener
import app.overdrive.ble.scan.AndroidGattScanner
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattScanner
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the ride app needs from Bluetooth, behind the four interfaces of `core` (ADR 0009, ADR 0010): the radio
 * and the permissions, the scanner, the factory of GATT transports, and the monotonic clock their timestamps use. The
 * container owns one; the tests give the container a fake one made of `FakeBle`.
 */
public interface BleStack {
    /** Bluetooth off, permission missing, ready. */
    public val environment: BleEnvironment

    /** Finds trainers and straps. */
    public val scanner: GattScanner

    /** Opens one single-use GATT transport per connection attempt. */
    public val transports: GattTransportFactory

    /** The clock of the notification timestamps (and of the session recorder), in nanoseconds. */
    public val nanoClock: () -> Long

    /** Reads the adapter state and the permissions again: on every resume and after a permission answer. */
    public fun refresh()

    /** Stops listening to the platform. The stack is not used afterwards. */
    public fun close()
}

/**
 * The real stack, over the platform's Bluetooth. Needs the Nearby-devices permissions at run time and says
 * [app.overdrive.core.ble.BleAvailability.PERMISSION_DENIED] until it has them. Everything it logs is privacy-redacted:
 * operations, statuses and device fingerprints only.
 *
 * @param scope the long-lived scope of the Bluetooth code: the transports' watchdogs and delayed releases run there.
 */
public class AndroidBle(
    context: Context,
    scope: CoroutineScope,
) : BleStack {
    private val appContext = context.applicationContext ?: context
    private val androidEnvironment = AndroidBleEnvironment(appContext)

    private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    override val environment: BleEnvironment get() = androidEnvironment

    override val scanner: GattScanner = AndroidGattScanner(::adapter, androidEnvironment, BleLog.Logcat)

    override val transports: GattTransportFactory =
        AndroidGattTransportFactory(
            BluetoothGattOpener(appContext, ::adapter),
            scope,
            SystemClock::elapsedRealtimeNanos,
            GattTimings(),
            BleLog.Logcat,
        )

    override val nanoClock: () -> Long = SystemClock::elapsedRealtimeNanos

    override fun refresh() {
        androidEnvironment.refresh()
    }

    override fun close() {
        androidEnvironment.close()
    }
}

/** Makes one [AndroidGattTransport] per attempt. Cheap and non-blocking: nothing happens on the air until `connect`. */
public class AndroidGattTransportFactory internal constructor(
    private val opener: PlatformGattOpener,
    private val scope: CoroutineScope,
    private val nanoClock: () -> Long,
    private val timings: GattTimings,
    private val log: BleLog,
) : GattTransportFactory {
    override fun open(device: DeviceId): GattTransport = open(device, ConnectMode.ACTIVE)

    override fun open(
        device: DeviceId,
        mode: ConnectMode,
    ): GattTransport = AndroidGattTransport(opener, device, scope, nanoClock, timings, log, mode)
}
