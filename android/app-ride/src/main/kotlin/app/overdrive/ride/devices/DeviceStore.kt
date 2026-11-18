package app.overdrive.ride.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.core.ble.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The three Bluetooth roles: the trainer and the strap of a ride, and the Click on the handlebar (ADR 0012). */
enum class DeviceRole(
    /** What the rider reads on the Devices screen. */
    val title: String,
    /** The name used when a device advertised none, and for the ride record's device list. */
    val fallbackName: String,
) {
    Trainer("Trainer", "Trainer"),
    Strap("Heart rate", "Heart-rate strap"),
    Click("Click", "Zwift Click"),
}

/**
 * One device the rider paired: the opaque [id] the transport understands and the name it advertised.
 *
 * **Privacy.** The id is a hardware address underneath. It is kept only in this app's private storage (the store below),
 * given to the transport and never anywhere else: `DeviceId.toString` prints a fingerprint, so this class is safe in a
 * log, and the ride record's device list holds [name], never the address (ADR 0010).
 */
data class RememberedDevice(
    val id: DeviceId,
    val name: String,
)

/** What is remembered: at most one trainer, one strap and one Click. */
data class RememberedDevices(
    val trainer: RememberedDevice? = null,
    val strap: RememberedDevice? = null,
    val click: RememberedDevice? = null,
) {
    operator fun get(role: DeviceRole): RememberedDevice? =
        when (role) {
            DeviceRole.Trainer -> trainer
            DeviceRole.Strap -> strap
            DeviceRole.Click -> click
        }
}

/** A name as the Devices screen and the ride record show it: no control characters, one space between words, at most 40. */
fun cleanDeviceName(
    raw: String?,
    role: DeviceRole,
): String {
    val cleaned =
        raw
            .orEmpty()
            .filter { !it.isISOControl() }
            .split(' ', '\t')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_NAME_LENGTH)
            .trim()
    return cleaned.ifEmpty { role.fallbackName }
}

private const val MAX_NAME_LENGTH = 40

/**
 * The remembered trainer, strap and Click in DataStore Preferences, in the app's private files. Reading is forgiving, like the
 * settings: a value that is blank or damaged is "nothing paired", never a crash, and an unreadable file is the same as an
 * empty one. Pairing a device replaces the old one of that role; forgetting removes it.
 */
class DeviceStore(
    private val store: DataStore<Preferences>,
) {
    /** What is remembered now and after every change. */
    val devices: Flow<RememberedDevices> =
        store.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }.map { it.toDevices() }
            .distinctUntilChanged()

    /** What is remembered right now (the first value of [devices]). */
    suspend fun read(): RememberedDevices = devices.first()

    /** Remembers [device] for [role], replacing whatever was there. */
    suspend fun remember(
        role: DeviceRole,
        device: RememberedDevice,
    ) {
        store.edit { prefs ->
            prefs[keys(role).id] = device.id.value
            prefs[keys(role).name] = cleanDeviceName(device.name, role)
        }
    }

    /** Forgets the device of [role]. */
    suspend fun forget(role: DeviceRole) {
        store.edit { prefs ->
            prefs.remove(keys(role).id)
            prefs.remove(keys(role).name)
        }
    }

    private class Keys(
        prefix: String,
    ) {
        val id = stringPreferencesKey("${prefix}_id")
        val name = stringPreferencesKey("${prefix}_name")
    }

    private val trainerKeys = Keys("trainer")
    private val strapKeys = Keys("strap")
    private val clickKeys = Keys("click")

    private fun keys(role: DeviceRole) =
        when (role) {
            DeviceRole.Trainer -> trainerKeys
            DeviceRole.Strap -> strapKeys
            DeviceRole.Click -> clickKeys
        }

    private fun Preferences.toDevices(): RememberedDevices =
        RememberedDevices(
            trainer = deviceOf(DeviceRole.Trainer),
            strap = deviceOf(DeviceRole.Strap),
            click = deviceOf(DeviceRole.Click),
        )

    private fun Preferences.deviceOf(role: DeviceRole): RememberedDevice? {
        val id = this[keys(role).id]?.takeIf { it.isNotBlank() } ?: return null
        return RememberedDevice(DeviceId(id), cleanDeviceName(this[keys(role).name], role))
    }
}
