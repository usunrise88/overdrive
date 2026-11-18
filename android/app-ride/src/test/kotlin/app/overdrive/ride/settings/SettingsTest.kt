package app.overdrive.ride.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Validation of every setting and the DataStore round trip (spec §4.6, design/system.md §6.6). */
class SettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.repository(name: String = "settings"): Pair<SettingsRepository, DataStore<Preferences>> {
        val store =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
            ) { File(folder.root, "$name.preferences_pb") }
        return SettingsRepository(store) to store
    }

    @Test
    fun defaultsAreInsideTheirRanges() {
        val s = AppSettings()
        assertEquals(s, s.sanitized())
        assertEquals(12, s.neutralGear)
    }

    @Test
    fun intRulesClampAndSnapToTheirStep() {
        assertEquals(50, SettingsRules.ftp.normalize(0))
        assertEquals(600, SettingsRules.ftp.normalize(10_000))
        assertEquals(255, SettingsRules.ftp.normalize(254))
        assertEquals(10, SettingsRules.gears.normalize(-3))
        assertEquals(30, SettingsRules.gears.normalize(99))
        assertEquals(90, SettingsRules.opacity.normalize(90))
        assertEquals(85, SettingsRules.opacity.normalize(50))
        assertEquals(100, SettingsRules.opacity.normalize(101))
    }

    @Test
    fun steppingMovesOneStepAndStopsAtTheEnds() {
        assertEquals(255, SettingsRules.ftp.stepped(250, +1))
        assertEquals(245, SettingsRules.ftp.stepped(250, -1))
        assertEquals(600, SettingsRules.ftp.stepped(600, +1))
        assertFalse(SettingsRules.ftp.canStep(600, +1))
        assertTrue(SettingsRules.ftp.canStep(600, -1))
        assertFalse(SettingsRules.gears.canStep(10, -1))
    }

    @Test
    fun decimalRulesStayOnTheirGridWithoutDrift() {
        // Ten steps of 0.1 from 1.0 is exactly 2.0, not 1.9999999999999998.
        var grade = 1.0
        repeat(10) { grade = SettingsRules.gradePerGear.stepped(grade, +1) }
        assertEquals(2.0, grade, 0.0)
        assertFalse(SettingsRules.gradePerGear.canStep(grade, +1))

        var crr = 0.004
        repeat(3) { crr = SettingsRules.crr.stepped(crr, +1) }
        assertEquals(0.0055, crr, 0.0)
        assertEquals(0.0010, SettingsRules.crr.normalize(0.0), 0.0)
        assertEquals(0.32, SettingsRules.cdA.stepped(0.31, +1), 0.0)
        assertEquals(0.10, SettingsRules.cdA.normalize(Double.NaN), 0.0)
        assertEquals(200.0, SettingsRules.mass.normalize(1e9), 0.0)
    }

    @Test
    fun sanitizedBringsEveryValueIntoRange() {
        val wild =
            AppSettings(
                ftp = 9999,
                gearCount = 1,
                gradePerGear = 7.0,
                massKg = -4.0,
                cdA = 5.0,
                crr = 0.0,
                overlayOpacityPercent = 12,
                overlayX = 4f,
                overlayY = Float.NaN,
            ).sanitized()
        assertEquals(600, wild.ftp)
        assertEquals(10, wild.gearCount)
        assertEquals(2.0, wild.gradePerGear, 0.0)
        assertEquals(40.0, wild.massKg, 0.0)
        assertEquals(1.0, wild.cdA, 0.0)
        assertEquals(0.001, wild.crr, 0.0)
        assertEquals(85, wild.overlayOpacityPercent)
        assertEquals(1f, wild.overlayX)
        assertNull(wild.overlayY)
    }

    @Test
    fun rideInputsIgnoreOverlayAndClickSettings() {
        val base = AppSettings()
        assertEquals(
            base.rideInputs(),
            base
                .copy(
                    overlayOpacityPercent = 90,
                    overlayBlur = true,
                    simulatedClick = SimulatedClick.Locked,
                ).rideInputs(),
        )
        assertFalse(base.rideInputs() == base.copy(ftp = 260).rideInputs())
        assertFalse(base.rideInputs() == base.copy(demoSpeed = DemoSpeed.Fast).rideInputs())
    }

    @Test
    fun repositoryStartsWithDefaultsAndRoundTripsEveryField() =
        runTest {
            val (repository, _) = repository()
            assertEquals(AppSettings(), repository.settings.first())

            val written =
                AppSettings(
                    ftp = 255,
                    gearCount = 22,
                    gradePerGear = 1.3,
                    massKg = 82.0,
                    cdA = 0.32,
                    crr = 0.0045,
                    overlayMode = OverlayStartMode.Detailed,
                    overlayOpacityPercent = 90,
                    overlayBlur = true,
                    overlayX = 0.25f,
                    overlayY = 0.5f,
                    demoSpeed = DemoSpeed.Faster,
                    scriptedDropouts = true,
                    simulatedClick = SimulatedClick.Locked,
                )
            repository.update { written }
            assertEquals(written, repository.settings.first())
        }

    @Test
    fun repositorySanitizesWhatItWritesAndWhatItReads() =
        runTest {
            val (repository, store) = repository()
            repository.update { it.copy(ftp = 12_345, overlayOpacityPercent = 3) }
            assertEquals(600, repository.settings.first().ftp)
            assertEquals(85, repository.settings.first().overlayOpacityPercent)

            // A value edited behind the repository's back, and an enum name from a future version.
            store.edit {
                it[intPreferencesKey("ftp")] = -7
                it[stringPreferencesKey("overlay_mode")] = "Gigantic"
            }
            val read = repository.settings.first()
            assertEquals(50, read.ftp)
            assertEquals(OverlayStartMode.Compact, read.overlayMode)
        }

    @Test
    fun theDevicesAreBluetoothByDefaultAndTheLogsAreRecorded() {
        val defaults = AppSettings()
        assertEquals(DeviceSource.Bluetooth, defaults.deviceSource)
        assertTrue(defaults.recordBleSessions)
        // A fresh install reads the same defaults from an empty store.
        runTest {
            val (repository, _) = repository("fresh")
            assertEquals(DeviceSource.Bluetooth, repository.settings.first().deviceSource)
            assertTrue(repository.settings.first().recordBleSessions)
        }
    }

    @Test
    fun theDeviceSourceAndTheRecordingSwitchSurviveARestartAndBadValuesFallBack() =
        runTest {
            val (repository, store) = repository()
            repository.update { it.copy(deviceSource = DeviceSource.Simulated, recordBleSessions = false) }
            assertEquals(DeviceSource.Simulated, repository.settings.first().deviceSource)
            assertFalse(repository.settings.first().recordBleSessions)

            // An enum name from a future version is the default, never a crash and never the simulation.
            store.edit { it[stringPreferencesKey("dev_device_source")] = "Satellite" }
            assertEquals(DeviceSource.Bluetooth, repository.settings.first().deviceSource)
        }

    @Test
    fun onlyTheDeviceSourceOfTheBluetoothSettingsRebuildsARide() {
        val base = AppSettings()
        assertTrue(base.rideInputs() != base.copy(deviceSource = DeviceSource.Simulated).rideInputs())
        assertEquals(base.rideInputs(), base.copy(recordBleSessions = false).rideInputs())
    }

    @Test
    fun resettingThePositionForgetsIt() =
        runTest {
            val (repository, _) = repository()
            repository.update { it.copy(overlayX = 0.4f, overlayY = 0.9f) }
            assertEquals(0.4f, repository.settings.first().overlayX)
            repository.resetOverlayPosition()
            assertNull(repository.settings.first().overlayX)
            assertNull(repository.settings.first().overlayY)
        }
}
