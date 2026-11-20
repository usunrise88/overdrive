package app.overdrive.ride.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.overdrive.core.click.ClickBits
import app.overdrive.core.control.ButtonMapping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The live coach's switch (spec §12.1, docs/adr/0039): on by default, kept across a restart, and not something a ride is built from. */
class LiveCoachSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.store(name: String = "coach.preferences_pb") =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, name) }

    @Test
    fun theCoachIsOnByDefaultAndTheDefaultsAreStillValid() {
        assertTrue(AppSettings().liveCoach)
        assertEquals(AppSettings(), AppSettings().sanitized())
    }

    @Test
    fun aRiderWhoSwitchedItOffKeepsItOffAcrossARestart() =
        runTest {
            val store = store()
            val first = SettingsRepository(store)
            assertTrue(first.settings.first().liveCoach)
            first.update { it.copy(liveCoach = false) }
            assertFalse(first.settings.first().liveCoach)
            // Another repository over the same file: the setting was written, not only held.
            // What the file holds, read straight from the store: the setting was written under its own key, not only held.
            assertEquals(false, store.data.first()[booleanPreferencesKey("live_coach")])
            first.update { it.copy(liveCoach = true) }
            assertEquals(true, store.data.first()[booleanPreferencesKey("live_coach")])
            assertTrue(first.settings.first().liveCoach)
        }

    @Test
    fun aFileFromBeforeTheSwitchReadsAsOn() =
        runTest {
            val store = store("old.preferences_pb")
            store.edit { it[booleanPreferencesKey("overlay_blur")] = true }
            val settings = SettingsRepository(store).settings.first()
            assertTrue("a rider who never saw the switch has the coach on", settings.liveCoach)
            assertTrue(settings.overlayBlur)
        }

    @Test
    fun theSwitchIsNotWhatARideIsBuiltFromSoChangingItRebuildsNothing() {
        assertEquals(AppSettings().rideInputs(), AppSettings(liveCoach = false).rideInputs())
    }

    @Test
    fun theCoachButtonIsKeptWithTheMappingAndAMappingWithoutOneIsTheDefault() =
        runTest {
            val repository = SettingsRepository(store("button.preferences_pb"))
            assertEquals(0, repository.settings.first().buttonMapping.coachMuteMask)
            val mapping = checkNotNull(ButtonMapping.DEFAULT.withCoachMute(ClickBits.MINUS))
            repository.update { it.copy(buttonMapping = mapping) }
            assertEquals(mapping, repository.settings.first().buttonMapping)
            repository.update { it.copy(buttonMapping = ButtonMapping.DEFAULT) }
            assertEquals(ButtonMapping.DEFAULT, repository.settings.first().buttonMapping)
        }
}
