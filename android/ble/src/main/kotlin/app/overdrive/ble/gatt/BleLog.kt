package app.overdrive.ble.gatt

import android.util.Log

/**
 * Where the Bluetooth code says what it is doing. **Privacy rule (`CLAUDE.md`, ADR 0010):** a message names operations,
 * statuses and [app.overdrive.core.ble.DeviceId] fingerprints (`#a1b2`), never an address, a device's name or a value
 * the device sent, and never the text of an exception (the platform puts the address into some of them): only its class
 * name.
 */
internal fun interface BleLog {
    fun log(message: String)

    companion object {
        /** Says nothing: tests that do not look at the log. */
        val None: BleLog = BleLog { }

        /** Logcat, tag `OverdriveBle`, debug level. */
        val Logcat: BleLog = BleLog { Log.d(TAG, it) }

        private const val TAG = "OverdriveBle"
    }
}

/** The class name of a throwable, for a log line: its message may hold an address, so it is never logged. */
internal fun Throwable.logName(): String = javaClass.simpleName.ifEmpty { "Throwable" }
