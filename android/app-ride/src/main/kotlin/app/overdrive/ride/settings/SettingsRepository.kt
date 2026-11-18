package app.overdrive.ride.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The rider's settings in DataStore Preferences (spec §4.6, §7.1). Every read goes through
 * [AppSettings.sanitized], so a value that was edited or corrupted on disk can never reach the ride engine
 * outside its valid range, and every write is sanitized too, so the Settings screen needs no checks of its own.
 */
class SettingsRepository(
    private val store: DataStore<Preferences>,
) {
    /** The current settings and every later change. Emits defaults when nothing is stored or the file is unreadable. */
    val settings: Flow<AppSettings> =
        store.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }.map { it.toSettings() }
            .distinctUntilChanged()

    /** Applies [change] to the stored settings atomically; the result is sanitized before it is written. */
    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = change(prefs.toSettings()).sanitized()
            prefs.write(next)
        }
    }

    /** Forgets the overlay position, so the overlay starts at its default corner again. */
    suspend fun resetOverlayPosition() = update { it.copy(overlayX = null, overlayY = null) }

    private object Keys {
        val ftp = intPreferencesKey("ftp")
        val gearCount = intPreferencesKey("gear_count")
        val gradePerGear = doublePreferencesKey("grade_per_gear")
        val massKg = doublePreferencesKey("mass_kg")
        val cdA = doublePreferencesKey("cda")
        val crr = doublePreferencesKey("crr")
        val overlayMode = stringPreferencesKey("overlay_mode")
        val overlayOpacity = intPreferencesKey("overlay_opacity_percent")
        val overlayBlur = booleanPreferencesKey("overlay_blur")
        val overlayX = floatPreferencesKey("overlay_x")
        val overlayY = floatPreferencesKey("overlay_y")
        val demoSpeed = stringPreferencesKey("dev_demo_speed")
        val scriptedDropouts = booleanPreferencesKey("dev_scripted_dropouts")
        val simulatedClick = stringPreferencesKey("dev_simulated_click")
        val deviceSource = stringPreferencesKey("dev_device_source")
        val recordBleSessions = booleanPreferencesKey("dev_record_ble_sessions")
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            ftp = this[Keys.ftp] ?: defaults.ftp,
            gearCount = this[Keys.gearCount] ?: defaults.gearCount,
            gradePerGear = this[Keys.gradePerGear] ?: defaults.gradePerGear,
            massKg = this[Keys.massKg] ?: defaults.massKg,
            cdA = this[Keys.cdA] ?: defaults.cdA,
            crr = this[Keys.crr] ?: defaults.crr,
            overlayMode = enumOr(this[Keys.overlayMode], defaults.overlayMode),
            overlayOpacityPercent = this[Keys.overlayOpacity] ?: defaults.overlayOpacityPercent,
            overlayBlur = this[Keys.overlayBlur] ?: defaults.overlayBlur,
            overlayX = this[Keys.overlayX],
            overlayY = this[Keys.overlayY],
            demoSpeed = enumOr(this[Keys.demoSpeed], defaults.demoSpeed),
            scriptedDropouts = this[Keys.scriptedDropouts] ?: defaults.scriptedDropouts,
            simulatedClick = enumOr(this[Keys.simulatedClick], defaults.simulatedClick),
            deviceSource = enumOr(this[Keys.deviceSource], defaults.deviceSource),
            recordBleSessions = this[Keys.recordBleSessions] ?: defaults.recordBleSessions,
        ).sanitized()
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.ftp] = settings.ftp
        this[Keys.gearCount] = settings.gearCount
        this[Keys.gradePerGear] = settings.gradePerGear
        this[Keys.massKg] = settings.massKg
        this[Keys.cdA] = settings.cdA
        this[Keys.crr] = settings.crr
        this[Keys.overlayMode] = settings.overlayMode.name
        this[Keys.overlayOpacity] = settings.overlayOpacityPercent
        this[Keys.overlayBlur] = settings.overlayBlur
        settings.overlayX?.let { this[Keys.overlayX] = it } ?: remove(Keys.overlayX)
        settings.overlayY?.let { this[Keys.overlayY] = it } ?: remove(Keys.overlayY)
        this[Keys.demoSpeed] = settings.demoSpeed.name
        this[Keys.scriptedDropouts] = settings.scriptedDropouts
        this[Keys.simulatedClick] = settings.simulatedClick.name
        this[Keys.deviceSource] = settings.deviceSource.name
        this[Keys.recordBleSessions] = settings.recordBleSessions
    }

    private inline fun <reified E : Enum<E>> enumOr(
        name: String?,
        fallback: E,
    ): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
