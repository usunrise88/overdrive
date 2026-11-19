package app.overdrive.ride.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.overdrive.core.route.RouteGrade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The route difficulty (spec §11.4): its own setting, 0 to 100 percent, 50 by default, validated like every other and kept across a restart. */
class RouteDifficultySettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.store() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "difficulty.preferences_pb") }

    @Test
    fun theDefaultIs50AndIsTheEnginesOwn() {
        assertEquals(50, AppSettings().routeDifficultyPercent)
        assertEquals(RouteGrade.DEFAULT_DIFFICULTY_PERCENT, SettingsRules.DEFAULT_ROUTE_DIFFICULTY_PERCENT)
        assertEquals(AppSettings(), AppSettings().sanitized())
    }

    @Test
    fun theRuleIs0To100InStepsOfTenAndNeverLeavesIt() {
        assertEquals(0, SettingsRules.routeDifficulty.normalize(-20))
        assertEquals(100, SettingsRules.routeDifficulty.normalize(250))
        assertEquals(50, SettingsRules.routeDifficulty.normalize(50))
        assertEquals(40, SettingsRules.routeDifficulty.normalize(44))
        assertEquals(60, SettingsRules.routeDifficulty.normalize(55))
        assertEquals(60, SettingsRules.routeDifficulty.stepped(50, +1))
        assertEquals(40, SettingsRules.routeDifficulty.stepped(50, -1))
        assertFalse(SettingsRules.routeDifficulty.canStep(100, +1))
        assertTrue(SettingsRules.routeDifficulty.canStep(100, -1))
        assertFalse(SettingsRules.routeDifficulty.canStep(0, -1))
        assertEquals(100, SettingsRules.routeDifficulty.stepped(100, +1))
    }

    @Test
    fun sanitizedBringsAWildValueIntoRange() {
        assertEquals(100, AppSettings(routeDifficultyPercent = 9999).sanitized().routeDifficultyPercent)
        assertEquals(0, AppSettings(routeDifficultyPercent = -5).sanitized().routeDifficultyPercent)
        assertEquals(30, AppSettings(routeDifficultyPercent = 33).sanitized().routeDifficultyPercent)
    }

    @Test
    fun aRideIsBuiltFromItSoAChangeBeforeTheStartRebuildsTheReadyRide() {
        assertNotEquals(AppSettings().rideInputs(), AppSettings(routeDifficultyPercent = 80).rideInputs())
        assertEquals(AppSettings().rideInputs(), AppSettings(overlayBlur = true).rideInputs())
    }

    @Test
    fun itSurvivesARestartAndAValueOnDiskThatIsOutOfRangeReadsAsInRange() =
        runTest {
            val store = store()
            val repository = SettingsRepository(store)
            assertEquals(50, repository.settings.first().routeDifficultyPercent)
            repository.update { it.copy(routeDifficultyPercent = 80) }
            assertEquals(80, SettingsRepository(store).settings.first().routeDifficultyPercent)
            repository.update { it.copy(routeDifficultyPercent = 33) }
            assertEquals("a write is sanitized", 30, SettingsRepository(store).settings.first().routeDifficultyPercent)

            store.edit { it[intPreferencesKey("route_difficulty_percent")] = 5000 }
            assertEquals(
                "a file that was edited",
                100,
                SettingsRepository(store).settings.first().routeDifficultyPercent,
            )
        }

    @Test
    fun anOldSettingsFileWithoutItReadsAsTheDefault() =
        runTest {
            val store = store()
            val repository = SettingsRepository(store)
            repository.update { it.copy(ftp = 260) }
            store.edit { it.remove(intPreferencesKey("route_difficulty_percent")) }
            val read = repository.settings.first()
            assertEquals(260, read.ftp)
            assertEquals(50, read.routeDifficultyPercent)
        }
}
