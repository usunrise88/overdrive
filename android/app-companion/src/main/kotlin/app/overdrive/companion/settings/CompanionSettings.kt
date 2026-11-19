package app.overdrive.companion.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.WellnessRange
import app.overdrive.core.sync.SyncConfig
import app.overdrive.data.settings.SecretText
import app.overdrive.data.settings.ServiceRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Where the first run is after the connect step (design/companion.md §4.10): the permission list, the background reading, the first
 * read of the last 60 days, and done. It is kept, so leaving the app mid-way comes back to the same step, and a phone that was set up
 * before the watch data existed meets the new steps once.
 */
enum class OnboardingStep {
    Health,
    Background,
    Reading,
    Done,
}

/**
 * What the companion remembers (design/companion.md §3.4, §4.9): the service address and token, the range the rider chose on Fitness,
 * Power and Wellness, and the step of the first run. The token is a [SecretText]: it prints as dots and never reaches a log, a cache
 * or a state dump.
 */
data class CompanionSettings(
    val serviceUrl: String = "",
    val token: SecretText = SecretText(),
    val fitnessRange: MetricsRange = MetricsRange.DEFAULT,
    val powerRange: MetricsRange = MetricsRange.DEFAULT,
    val wellnessRange: WellnessRange = WellnessRange.DEFAULT,
    val onboarding: OnboardingStep = OnboardingStep.Health,
) {
    /** The address and token the repositories send with. */
    val config: SyncConfig get() = SyncConfig(serviceUrl, token.value)

    /** There is a service to talk to: the tabs show, otherwise the first run. */
    val isConfigured: Boolean get() = serviceUrl.isNotEmpty() && !token.isEmpty

    /** Every value in its valid form: an address that is not one, or a token with control characters, is what a hand-edited file reads as. */
    fun sanitized(): CompanionSettings =
        copy(
            serviceUrl = ServiceRules.cleanAddress(serviceUrl),
            token = SecretText(ServiceRules.cleanToken(token.value)),
        )
}

/** The companion's settings in DataStore Preferences. Every read goes through [CompanionSettings.sanitized]. */
class CompanionSettingsRepository(
    private val store: DataStore<Preferences>,
) {
    /** The current settings and every later change. Emits defaults when nothing is stored or the file is unreadable. */
    val settings: Flow<CompanionSettings> =
        store.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { it.toSettings() }
            .distinctUntilChanged()

    /** Applies [change] to the stored settings atomically; the result is sanitized before it is written. */
    suspend fun update(change: (CompanionSettings) -> CompanionSettings) {
        store.edit { prefs ->
            val next = change(prefs.toSettings()).sanitized()
            if (next.serviceUrl.isEmpty()) prefs.remove(Keys.url) else prefs[Keys.url] = next.serviceUrl
            if (next.token.isEmpty) prefs.remove(Keys.token) else prefs[Keys.token] = next.token.value
            prefs[Keys.fitnessRange] = next.fitnessRange.name
            prefs[Keys.powerRange] = next.powerRange.name
            prefs[Keys.wellnessRange] = next.wellnessRange.name
            prefs[Keys.onboarding] = next.onboarding.name
        }
    }

    private object Keys {
        val url = stringPreferencesKey("service_url")
        val token = stringPreferencesKey("service_token")
        val fitnessRange = stringPreferencesKey("fitness_range")
        val powerRange = stringPreferencesKey("power_range")
        val wellnessRange = stringPreferencesKey("wellness_range")
        val onboarding = stringPreferencesKey("onboarding_step")
    }

    private fun Preferences.toSettings(): CompanionSettings =
        CompanionSettings(
            serviceUrl = this[Keys.url].orEmpty(),
            token = SecretText(this[Keys.token].orEmpty()),
            fitnessRange = rangeOf(this[Keys.fitnessRange]),
            powerRange = rangeOf(this[Keys.powerRange]),
            wellnessRange =
                WellnessRange.entries.firstOrNull { it.name == this[Keys.wellnessRange] } ?: WellnessRange.DEFAULT,
            onboarding =
                OnboardingStep.entries.firstOrNull { it.name == this[Keys.onboarding] } ?: OnboardingStep.Health,
        ).sanitized()

    private fun rangeOf(name: String?): MetricsRange =
        MetricsRange.entries.firstOrNull { it.name == name } ?: MetricsRange.DEFAULT
}
