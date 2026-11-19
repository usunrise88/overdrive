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
import app.overdrive.core.control.ButtonMapping
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
        val overlayExpandsAtSteps = booleanPreferencesKey("overlay_expands_at_steps")
        val overlayX = floatPreferencesKey("overlay_x")
        val overlayY = floatPreferencesKey("overlay_y")
        val demoSpeed = stringPreferencesKey("dev_demo_speed")
        val scriptedDropouts = booleanPreferencesKey("dev_scripted_dropouts")
        val simulatedClick = stringPreferencesKey("dev_simulated_click")
        val deviceSource = stringPreferencesKey("dev_device_source")
        val recordBleSessions = booleanPreferencesKey("dev_record_ble_sessions")
        val buttonMapping = stringPreferencesKey("click_button_mapping")
        val clickPostHandshake = booleanPreferencesKey("dev_click_post_handshake")
        val clickKeepAlive = stringPreferencesKey("dev_click_keep_alive")
        val clickSilentMeansLocked = booleanPreferencesKey("dev_click_silent_means_locked")
        val clickNoEventTimeout = stringPreferencesKey("dev_click_no_event_timeout")
        val clickMtu = stringPreferencesKey("dev_click_mtu")
        val clickWriteWithResponse = booleanPreferencesKey("dev_click_write_with_response")
        val clickRequireService = booleanPreferencesKey("dev_click_require_service")
        val clickPucks = stringPreferencesKey("dev_click_pucks")
        val clickReplyCheck = stringPreferencesKey("dev_click_reply_check")
        val clickHold = intPreferencesKey("dev_click_hold_ms")
        val clickVolumeRepeat = intPreferencesKey("dev_click_volume_repeat_ms")
        val syncUrl = stringPreferencesKey("sync_url")
        val syncToken = stringPreferencesKey("sync_token")
        val routeDifficulty = intPreferencesKey("route_difficulty_percent")
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
            overlayExpandsAtSteps = this[Keys.overlayExpandsAtSteps] ?: defaults.overlayExpandsAtSteps,
            overlayX = this[Keys.overlayX],
            overlayY = this[Keys.overlayY],
            demoSpeed = enumOr(this[Keys.demoSpeed], defaults.demoSpeed),
            scriptedDropouts = this[Keys.scriptedDropouts] ?: defaults.scriptedDropouts,
            simulatedClick = enumOr(this[Keys.simulatedClick], defaults.simulatedClick),
            deviceSource = enumOr(this[Keys.deviceSource], defaults.deviceSource),
            recordBleSessions = this[Keys.recordBleSessions] ?: defaults.recordBleSessions,
            // A stored mapping that is not a valid one (cut, edited, two roles on one button) is the default, and never
            // reaches the gesture layer.
            buttonMapping = this[Keys.buttonMapping]?.let { ButtonMapping.parse(it) } ?: defaults.buttonMapping,
            click = clickSettings(defaults.click),
            tuning = tuning(),
            syncUrl = this[Keys.syncUrl].orEmpty(),
            syncToken = SecretText(this[Keys.syncToken].orEmpty()),
            routeDifficultyPercent = this[Keys.routeDifficulty] ?: defaults.routeDifficultyPercent,
        ).sanitized()
    }

    /**
     * The tuning numbers, each under `tune_<id>`. A value that is missing is the default; one outside its rule is put on it by
     * [AppSettings.sanitized], so nothing the file holds can reach the engine (docs/adr/0034).
     */
    private fun Preferences.tuning(): RideTuning {
        var tuning = RideTuning()
        for (field in TuningFields.all) {
            tuning =
                when (field) {
                    is SwitchTuning -> this[booleanPreferencesKey(TUNE + field.id)]?.let { field.write(tuning, it) }
                    is IntTuning -> this[intPreferencesKey(TUNE + field.id)]?.let { field.write(tuning, it) }
                    is DecimalTuning -> this[doublePreferencesKey(TUNE + field.id)]?.let { field.write(tuning, it) }
                } ?: tuning
        }
        return tuning
    }

    private fun MutablePreferences.writeTuning(tuning: RideTuning) {
        for (field in TuningFields.all) {
            when (field) {
                is SwitchTuning -> this[booleanPreferencesKey(TUNE + field.id)] = field.read(tuning)
                is IntTuning -> this[intPreferencesKey(TUNE + field.id)] = field.read(tuning)
                is DecimalTuning -> this[doublePreferencesKey(TUNE + field.id)] = field.read(tuning)
            }
        }
    }

    private fun Preferences.clickSettings(defaults: ClickSettings) =
        ClickSettings(
            sendPostHandshake = this[Keys.clickPostHandshake] ?: defaults.sendPostHandshake,
            keepAlive = enumOr(this[Keys.clickKeepAlive], defaults.keepAlive),
            // Absent in the settings of v0.1.0: off, as on a fresh install.
            silentMeansLocked = this[Keys.clickSilentMeansLocked] ?: defaults.silentMeansLocked,
            noEventTimeout = enumOr(this[Keys.clickNoEventTimeout], defaults.noEventTimeout),
            mtu = enumOr(this[Keys.clickMtu], defaults.mtu),
            writeWithResponse = this[Keys.clickWriteWithResponse] ?: defaults.writeWithResponse,
            requireAdvertisedService = this[Keys.clickRequireService] ?: defaults.requireAdvertisedService,
            pucks = enumOr(this[Keys.clickPucks], defaults.pucks),
            replyCheck = enumOr(this[Keys.clickReplyCheck], defaults.replyCheck),
            holdThresholdMillis = this[Keys.clickHold] ?: defaults.holdThresholdMillis,
            volumeRepeatMillis = this[Keys.clickVolumeRepeat] ?: defaults.volumeRepeatMillis,
        )

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
        this[Keys.overlayExpandsAtSteps] = settings.overlayExpandsAtSteps
        settings.overlayX?.let { this[Keys.overlayX] = it } ?: remove(Keys.overlayX)
        settings.overlayY?.let { this[Keys.overlayY] = it } ?: remove(Keys.overlayY)
        this[Keys.demoSpeed] = settings.demoSpeed.name
        this[Keys.scriptedDropouts] = settings.scriptedDropouts
        this[Keys.simulatedClick] = settings.simulatedClick.name
        this[Keys.deviceSource] = settings.deviceSource.name
        this[Keys.recordBleSessions] = settings.recordBleSessions
        this[Keys.buttonMapping] = settings.buttonMapping.serialize()
        writeTuning(settings.tuning)
        val click = settings.click
        this[Keys.clickPostHandshake] = click.sendPostHandshake
        this[Keys.clickKeepAlive] = click.keepAlive.name
        this[Keys.clickSilentMeansLocked] = click.silentMeansLocked
        this[Keys.clickNoEventTimeout] = click.noEventTimeout.name
        this[Keys.clickMtu] = click.mtu.name
        this[Keys.clickWriteWithResponse] = click.writeWithResponse
        this[Keys.clickRequireService] = click.requireAdvertisedService
        this[Keys.clickPucks] = click.pucks.name
        this[Keys.clickReplyCheck] = click.replyCheck.name
        this[Keys.clickHold] = click.holdThresholdMillis
        this[Keys.clickVolumeRepeat] = click.volumeRepeatMillis
        if (settings.syncUrl.isEmpty()) remove(Keys.syncUrl) else this[Keys.syncUrl] = settings.syncUrl
        if (settings.syncToken.isEmpty) remove(Keys.syncToken) else this[Keys.syncToken] = settings.syncToken.value
        this[Keys.routeDifficulty] = settings.routeDifficultyPercent
    }

    private companion object {
        /** The prefix of every tuning key (docs/adr/0034). */
        const val TUNE = "tune_"
    }

    private inline fun <reified E : Enum<E>> enumOr(
        name: String?,
        fallback: E,
    ): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
