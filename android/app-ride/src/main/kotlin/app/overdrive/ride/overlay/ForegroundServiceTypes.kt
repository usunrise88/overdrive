package app.overdrive.ride.overlay

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground service types the overlay service starts with (ADR 0006, ADR 0010).
 *
 * The manifest declares `connectedDevice|specialUse`. **From Android 14 the system checks, when `startForeground` is
 * called with a type, that the app holds what that type needs**, and throws a `SecurityException` otherwise: for
 * `connectedDevice` that is the runtime permission `BLUETOOTH_CONNECT`. A rider who has not allowed "Nearby devices"
 * (or who rides with the simulated devices) must still get the overlay, so the service passes `connectedDevice` **only
 * while the permission is granted** and always passes `specialUse`, which needs nothing at run time. Before Android 14
 * the call has no type argument at all and the manifest's types apply.
 */
internal object ForegroundServiceTypes {
    /** No type argument: the call for Android 13 and earlier. */
    const val NONE = 0

    /** The type of the service: what [choose] answers when nothing is granted. */
    const val SPECIAL_USE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /** The type that tells the system the service keeps Bluetooth devices connected. */
    const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    /** The types to start with on [sdk] when `BLUETOOTH_CONNECT` is [bluetoothConnectGranted]. */
    fun choose(
        sdk: Int,
        bluetoothConnectGranted: Boolean,
    ): Int =
        when {
            sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> NONE
            bluetoothConnectGranted -> SPECIAL_USE or CONNECTED_DEVICE
            else -> SPECIAL_USE
        }

    /**
     * What to try when the system refused [tried]: the type that needs nothing, or null when [tried] already was that
     * (there is nothing weaker to start with, so the refusal is not ours to swallow).
     */
    fun fallback(
        sdk: Int,
        tried: Int,
    ): Int? = choose(sdk, bluetoothConnectGranted = false).takeIf { it != tried }
}
