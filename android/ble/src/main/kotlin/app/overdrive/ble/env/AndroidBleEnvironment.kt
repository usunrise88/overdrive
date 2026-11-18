package app.overdrive.ble.env

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the platform says about the radio right now. Three questions, so a test answers them by hand. */
internal interface RadioProbe {
    /** The tablet has Bluetooth LE and an adapter. */
    fun supported(): Boolean

    /** The adapter is fully on (not off, not turning on or off, not the BLE-only state). */
    fun adapterOn(): Boolean

    /** Both Nearby-devices permissions, `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`, are granted. */
    fun permissionsGranted(): Boolean
}

/** The answer of [BleEnvironment] from the three facts: the order is the order in which the rider can fix them. */
internal fun availabilityOf(
    supported: Boolean,
    adapterOn: Boolean,
    permissionsGranted: Boolean,
): BleAvailability =
    when {
        !supported -> BleAvailability.UNSUPPORTED
        !permissionsGranted -> BleAvailability.PERMISSION_DENIED
        !adapterOn -> BleAvailability.BLUETOOTH_OFF
        else -> BleAvailability.READY
    }

/** The real probe: `BluetoothManager`, the package manager and `checkSelfPermission`. Never throws. */
internal class SystemRadioProbe(
    private val context: Context,
) : RadioProbe {
    private fun adapter(): BluetoothAdapter? =
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: RuntimeException) {
            null
        }

    override fun supported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter() != null

    override fun adapterOn(): Boolean =
        try {
            adapter()?.isEnabled == true
        } catch (_: RuntimeException) {
            false
        }

    override fun permissionsGranted(): Boolean =
        REQUIRED.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        /** "Nearby devices" (Android 12 and later, the app's minimum): scanning and connecting. */
        val REQUIRED = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}

/**
 * Whether Bluetooth can be used, as [BleAvailability] (spec §4.1): Bluetooth off, permission missing, unsupported, or
 * ready. It listens to `ACTION_STATE_CHANGED` for the adapter and is told by [refresh] about everything that has no
 * broadcast: the app calls it when the Nearby-devices question has been answered and every time the screen resumes,
 * because the permission settings screen returns no result.
 */
public class AndroidBleEnvironment internal constructor(
    context: Context,
    private val probe: RadioProbe,
) : BleEnvironment {
    private val appContext = context.applicationContext ?: context
    private val state = MutableStateFlow(read())
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                refresh()
            }
        }
    private var registered = false

    /** Registers for adapter state changes. */
    public constructor(context: Context) : this(context, SystemRadioProbe(context.applicationContext ?: context))

    override val availability: StateFlow<BleAvailability> = state.asStateFlow()

    init {
        registered =
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, filter)
                }
                true
            } catch (_: RuntimeException) {
                false
            }
    }

    /** Reads the radio and the permissions again. Cheap; call it on every resume and after a permission answer. */
    public fun refresh() {
        state.value = read()
    }

    /** Stops listening. The environment keeps its last answer. */
    public fun close() {
        if (registered) {
            registered = false
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Not registered after all.
            }
        }
    }

    private fun read(): BleAvailability =
        availabilityOf(probe.supported(), probe.adapterOn(), probe.permissionsGranted())

    public companion object {
        /** The runtime permissions the rider is asked for: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. */
        public val REQUIRED_PERMISSIONS: Array<String> get() = SystemRadioProbe.REQUIRED.copyOf()
    }
}
