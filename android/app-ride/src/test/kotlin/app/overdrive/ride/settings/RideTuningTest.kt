package app.overdrive.ride.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.overdrive.core.ride.HeartRateHoldSettings
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.ride.StallGuardSettings
import app.overdrive.ride.host.toRideSettings
import app.overdrive.ride.ui.tuningNote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The engine's tuning numbers on the tablet (docs/adr/0034): the mapping to `RideSettings`, the rules that keep an invalid value away from
 * the engine, the reasons a value is refused, the reset and the persistence.
 */
class RideTuningTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.repository(name: String = "tuning"): Pair<SettingsRepository, DataStore<Preferences>> {
        val store =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
            ) { File(folder.root, "$name.preferences_pb") }
        return SettingsRepository(store) to store
    }

    // ---- The defaults are the engine's -------------------------------------------------------------------------------

    @Test
    fun theDefaultsAreTheEnginesOwnNumbers() {
        val engine = RideSettings()
        val mapped = AppSettings().toRideSettings()
        assertEquals(engine.autoPauseTargetWatts, mapped.autoPauseTargetWatts)
        assertEquals(engine.autoResumePowerWatts, mapped.autoResumePowerWatts)
        assertEquals(engine.autoResumeFlywheelStepKmh, mapped.autoResumeFlywheelStepKmh, 0.0)
        assertEquals(engine.rampInSeconds, mapped.rampInSeconds)
        assertEquals(engine.pausedTargetWatts, mapped.pausedTargetWatts)
        assertEquals(StallGuardSettings(), mapped.stallGuard)
        assertEquals(HeartRateHoldSettings(), mapped.heartRateHold)
    }

    @Test
    fun theStatedDefaultsAreWhatTheAdrsSay() {
        val t = RideTuning()
        assertEquals(25, t.autoPauseTargetWatts)
        assertEquals(10, t.autoResumePowerWatts)
        assertEquals(0.5, t.autoResumeFlywheelStepKmh, 0.0)
        assertEquals(3, t.rampInSeconds)
        assertEquals(0, t.pausedTargetWatts)
        assertTrue(t.stallGuardEnabled)
        assertEquals(55, t.stallGuardFloorRpm)
        assertEquals(70, t.stallGuardTargetPercent)
        assertEquals(180, t.hrStartRampSeconds)
        assertEquals(30, t.hrStartRampPercent)
        assertEquals(15, t.hrSignalHoldSeconds)
        assertEquals(60, t.hrSignalDropSeconds)
        assertEquals(2, t.hrTargetStepBpm)
    }

    @Test
    fun everyFieldHasAPlainLabelANoteAUnitAndADefaultInsideItsRule() {
        val ids = TuningFields.all.map { it.id }
        assertEquals("ids are unique", ids.size, ids.toSet().size)
        for (field in TuningFields.all) {
            assertTrue("${field.id} has a label", field.label.isNotBlank())
            assertTrue("${field.id} has a note", field.note.length > 10)
            assertFalse("${field.id} note is one line", field.note.contains('\n'))
            assertTrue("${field.id} default is stated", field.defaultText().isNotBlank())
            assertNull("${field.id} default is valid", field.check(RideTuning()))
            assertTrue("${field.id} is at its default", field.isDefault(RideTuning()))
            if (field !is SwitchTuning) {
                assertTrue(
                    "${field.id} states its unit",
                    field.defaultText().endsWith(field.unit),
                )
            }
        }
        for (group in TuningGroup.entries) assertTrue("$group has fields", TuningFields.of(group).isNotEmpty())
    }

    // ---- The mapping --------------------------------------------------------------------------------------------------

    @Test
    fun everyNumberReachesTheEngineWhereItBelongs() {
        val t =
            RideTuning(
                autoPauseTargetWatts = 40,
                autoResumePowerWatts = 15,
                autoResumeFlywheelStepKmh = 0.8,
                rampInSeconds = 5,
                pausedTargetWatts = 20,
                stallGuardEnabled = false,
                stallGuardFloorRpm = 60,
                stallGuardTriggerSeconds = 4,
                stallGuardTargetPercent = 60,
                stallGuardReleaseMarginRpm = 12,
                stallGuardReleaseSeconds = 6,
                stallGuardRampBackSeconds = 8,
                stallGuardResumeGraceSeconds = 7,
                stallGuardArmTimeoutSeconds = 45,
                hrStartRampSeconds = 240,
                hrStartRampPercent = 20,
                hrGainWattsPerBpm = 0.7,
                hrLeadSeconds = 40,
                hrMaxStepUpWatts = 4,
                hrMaxStepDownWatts = 12,
                hrDeadbandBpm = 1.5,
                hrSmoothingSeconds = 20,
                hrTrendWindowSeconds = 40,
                hrSignalHoldSeconds = 20,
                hrSignalDropSeconds = 90,
                hrTargetStepBpm = 3,
                hrMaxTargetOffsetBpm = 10,
            )
        val engine = AppSettings(tuning = t).toRideSettings()
        assertEquals(40, engine.autoPauseTargetWatts)
        assertEquals(15, engine.autoResumePowerWatts)
        assertEquals(0.8, engine.autoResumeFlywheelStepKmh, 1e-9)
        assertEquals(5, engine.rampInSeconds)
        assertEquals(20, engine.pausedTargetWatts)
        val guard = engine.stallGuard
        assertFalse(guard.enabled)
        assertEquals(60, guard.cadenceFloorRpm)
        assertEquals(4, guard.triggerSeconds)
        assertEquals(0.60, guard.targetFraction, 1e-9)
        assertEquals(12, guard.releaseMarginRpm)
        assertEquals(6, guard.releaseSeconds)
        assertEquals(8, guard.rampBackSeconds)
        assertEquals(7, guard.resumeGraceSeconds)
        assertEquals(45, guard.armTimeoutSeconds)
        val hold = engine.heartRateHold
        assertEquals(240, hold.startRampSeconds)
        assertEquals(0.20, hold.startRampFraction, 1e-9)
        assertEquals(0.7, hold.gainWattsPerBpm, 1e-9)
        assertEquals(40, hold.leadSeconds)
        assertEquals(4, hold.maxStepUpWatts)
        assertEquals(12, hold.maxStepDownWatts)
        assertEquals(1.5, hold.deadbandBpm, 1e-9)
        assertEquals(20, hold.smoothingSeconds)
        assertEquals(40, hold.trendWindowSeconds)
        assertEquals(20, hold.signalHoldSeconds)
        assertEquals(90, hold.signalDropSeconds)
        assertEquals(3, hold.targetStepBpm)
        assertEquals(10, hold.maxTargetOffsetBpm)
        // What the engine had from FTP, the physics and the gears is untouched.
        assertEquals(AppSettings().ftp, engine.ftp)
    }

    @Test
    fun anExtremeValueOfAnyFieldAtTheEdgeOfItsRuleIsAcceptedByTheEngine() {
        for (field in TuningFields.all) {
            val lows =
                when (field) {
                    is IntTuning -> field.write(RideTuning(), field.rule.min)
                    is DecimalTuning -> field.write(RideTuning(), field.rule.min)
                    is SwitchTuning -> field.write(RideTuning(), !field.default)
                }
            val highs =
                when (field) {
                    is IntTuning -> field.write(RideTuning(), field.rule.max)
                    is DecimalTuning -> field.write(RideTuning(), field.rule.max)
                    is SwitchTuning -> field.write(RideTuning(), field.default)
                }
            for (t in listOf(lows, highs)) {
                // Never throws: RideSettings' requires hold for every value the rules allow, with the strap times put in order.
                assertNotNull(field.id, AppSettings(tuning = t).toRideSettings())
            }
        }
    }

    @Test
    fun everyFieldAtItsLowestAtOnceAndAtItsHighestAtOnceIsAcceptedToo() {
        var low = RideTuning()
        var high = RideTuning()
        for (field in TuningFields.all) {
            when (field) {
                is IntTuning -> {
                    low = field.write(low, field.rule.min)
                    high = field.write(high, field.rule.max)
                }

                is DecimalTuning -> {
                    low = field.write(low, field.rule.min)
                    high = field.write(high, field.rule.max)
                }

                is SwitchTuning -> {
                    low = field.write(low, false)
                    high = field.write(high, true)
                }
            }
        }
        AppSettings(tuning = low).toRideSettings()
        AppSettings(tuning = high).toRideSettings()
        // The strap: hold at its highest (60) with the drop at its highest (300), and hold at its lowest with the drop at its lowest (15).
        assertTrue(high.sanitized().hrSignalDropSeconds >= high.sanitized().hrSignalHoldSeconds)
        assertTrue(low.sanitized().hrSignalDropSeconds >= low.sanitized().hrSignalHoldSeconds)
    }

    @Test
    fun aValueOutsideItsRuleNeverReachesTheEngine() {
        val wild =
            RideTuning(
                autoPauseTargetWatts = -50,
                autoResumePowerWatts = 99_999,
                autoResumeFlywheelStepKmh = Double.NaN,
                rampInSeconds = -3,
                pausedTargetWatts = 5_000,
                stallGuardFloorRpm = 0,
                stallGuardTriggerSeconds = 0,
                stallGuardTargetPercent = 100,
                stallGuardReleaseMarginRpm = -1,
                stallGuardReleaseSeconds = 0,
                hrStartRampPercent = 400,
                hrGainWattsPerBpm = -2.0,
                hrDeadbandBpm = Double.POSITIVE_INFINITY,
                hrSmoothingSeconds = 0,
                hrTrendWindowSeconds = 0,
                hrSignalHoldSeconds = 500,
                hrSignalDropSeconds = 3,
            )
        // The engine would have thrown on every one of these: it is built from the sanitized copy.
        val engine = AppSettings(tuning = wild).toRideSettings()
        assertEquals(0, engine.autoPauseTargetWatts)
        assertEquals(100, engine.autoResumePowerWatts)
        assertEquals(0.0, engine.autoResumeFlywheelStepKmh, 0.0)
        assertEquals(0, engine.rampInSeconds)
        assertEquals(100, engine.pausedTargetWatts)
        assertEquals(30, engine.stallGuard.cadenceFloorRpm)
        assertEquals(1, engine.stallGuard.triggerSeconds)
        assertEquals(0.95, engine.stallGuard.targetFraction, 1e-9)
        assertEquals(1, engine.stallGuard.releaseSeconds)
        assertEquals(1.0, engine.heartRateHold.startRampFraction, 1e-9)
        assertEquals(0.0, engine.heartRateHold.gainWattsPerBpm, 0.0)
        assertEquals("not a number: the lowest", 0.0, engine.heartRateHold.deadbandBpm, 0.0)
        assertTrue(engine.heartRateHold.signalDropSeconds >= engine.heartRateHold.signalHoldSeconds)
    }

    // ---- Refusals, with their reasons ---------------------------------------------------------------------------------

    private fun int(id: String) = TuningFields.all.filterIsInstance<IntTuning>().first { it.id == id }

    private fun decimal(id: String) = TuningFields.all.filterIsInstance<DecimalTuning>().first { it.id == id }

    @Test
    fun aValueOutsideItsRuleIsRefusedWithTheReason() {
        val floor = int("stall_guard_floor_rpm")
        assertEquals("Must be 30 to 90 rpm", floor.refusal(10, RideTuning()))
        assertEquals("Must be 30 to 90 rpm", floor.refusal(91, RideTuning()))
        assertNull(floor.refusal(55, RideTuning()))
        val target = int("stall_guard_target_percent")
        assertEquals("Goes in steps of 5 %", target.refusal(72, RideTuning()))
        val gain = decimal("hr_gain_watts_per_bpm")
        assertEquals("Must be 0.0 to 2.0 W/bpm", gain.refusal(2.5))
        assertEquals("Must be a number", gain.refusal(Double.NaN))
        assertNull(gain.refusal(0.5))
    }

    @Test
    fun theStrapTimesRefuseEachOtherWithTheNumberTheyRunInto() {
        val hold = int("hr_signal_hold_seconds")
        val drop = int("hr_signal_drop_seconds")
        val tuning = RideTuning(hrSignalHoldSeconds = 30, hrSignalDropSeconds = 30)
        assertEquals("Not above 30 s", hold.refusal(31, tuning))
        assertEquals("Not below 30 s", drop.refusal(25, tuning))
        assertNull(hold.stepped(tuning, +1))
        assertNull(drop.stepped(tuning, -1))
        assertEquals(29, hold.stepped(tuning, -1))
        assertEquals(35, drop.stepped(tuning, +1))
    }

    @Test
    fun theSettingsScreenSaysWhyAStepIsRefused() {
        val tuning = RideTuning(hrSignalHoldSeconds = 30, hrSignalDropSeconds = 30)
        val note = tuningNote(int("hr_signal_hold_seconds"), tuning)
        assertTrue(note, note.startsWith("Not above 30 s (the other strap time)."))
        assertTrue("and the default is still said", note.contains("Default 15 s"))
        val plain = tuningNote(int("stall_guard_floor_rpm"), RideTuning())
        assertTrue(plain, plain.endsWith("Default 55 rpm"))
        assertFalse(plain.contains("other strap time"))
    }

    @Test
    fun problemsListsEverythingThatIsWrongInPlainWords() {
        assertTrue(RideTuning().problems().isEmpty())
        val bad = RideTuning(stallGuardFloorRpm = 5, hrSignalHoldSeconds = 50, hrSignalDropSeconds = 20)
        val problems = bad.problems()
        assertTrue(problems.toString(), problems.any { it.startsWith("Cadence floor: Must be 30 to 90 rpm") })
        assertTrue(problems.toString(), problems.any { it.contains("the power drops after it is held") })
        // And sanitizing fixes all of it.
        assertTrue(bad.sanitized().problems().isEmpty())
    }

    @Test
    fun steppingMovesOneStepAndStopsAtTheRule() {
        val floor = int("stall_guard_floor_rpm")
        assertEquals(56, floor.stepped(RideTuning(), +1))
        assertEquals(54, floor.stepped(RideTuning(), -1))
        assertNull(floor.stepped(RideTuning(stallGuardFloorRpm = 90), +1))
        assertNull(floor.stepped(RideTuning(stallGuardFloorRpm = 30), -1))
        val gain = decimal("hr_gain_watts_per_bpm")
        assertEquals(0.6, gain.rule.stepped(0.5, +1), 1e-9)
        assertFalse(gain.rule.canStep(2.0, +1))
    }

    // ---- Reset --------------------------------------------------------------------------------------------------------

    @Test
    fun resetPutsOneGroupBackAndLeavesTheOthers() {
        val changed =
            RideTuning(
                stallGuardEnabled = false,
                stallGuardFloorRpm = 70,
                autoPauseTargetWatts = 50,
                hrGainWattsPerBpm = 1.2,
                stallGuardTargetPercent = 50,
            )
        val ride = changed.resetGroup(TuningGroup.Ride)
        assertEquals(RideTuning().stallGuardEnabled, ride.stallGuardEnabled)
        assertEquals(RideTuning().stallGuardFloorRpm, ride.stallGuardFloorRpm)
        assertEquals("another group is left alone", 50, ride.autoPauseTargetWatts)
        assertEquals(1.2, ride.hrGainWattsPerBpm, 0.0)
        assertEquals(
            RideTuning(),
            changed
                .resetGroup(TuningGroup.Ride)
                .resetGroup(TuningGroup.ResumeAfterPause)
                .resetGroup(
                    TuningGroup.StallGuard,
                ).resetGroup(TuningGroup.HeartRateHold),
        )
    }

    // ---- Persistence --------------------------------------------------------------------------------------------------

    private fun everythingChanged(): RideTuning {
        var t = RideTuning()
        for (field in TuningFields.all) {
            t =
                when (field) {
                    is SwitchTuning -> {
                        field.write(t, !field.default)
                    }

                    is IntTuning -> {
                        field.write(t, field.stepped(t, +1) ?: field.stepped(t, -1) ?: field.default)
                    }

                    is DecimalTuning -> {
                        field.write(
                            t,
                            field.rule.stepped(
                                field.default,
                                if (field.default <
                                    field.rule.max
                                ) {
                                    +1
                                } else {
                                    -1
                                },
                            ),
                        )
                    }
                }
        }
        return t.sanitized()
    }

    @Test
    fun everyNumberIsKeptAndComesBack() =
        runTest {
            val (repository, _) = repository()
            val changed = everythingChanged()
            assertTrue("every field moved", TuningFields.all.none { it.isDefault(changed) })
            repository.update { it.copy(tuning = changed, overlayExpandsAtSteps = false) }
            val read = repository.settings.first()
            assertEquals(changed, read.tuning)
            assertFalse(read.overlayExpandsAtSteps)
        }

    @Test
    fun theWeightsFromTheProfileAreNeverStored() =
        runTest {
            val (repository, _) = repository("weights")
            repository.update {
                it.copy(bikeWeightKg = 9.0, riderWeight = app.overdrive.core.profile.RiderWeight(78.4, "2026-11-18", 5))
            }
            val read = repository.settings.first()
            assertNull(read.bikeWeightKg)
            assertNull(read.riderWeight)
        }

    @Test
    fun aFreshInstallHasTheDefaultsAndTheOverlayExpanding() =
        runTest {
            val (repository, _) = repository("fresh")
            val read = repository.settings.first()
            assertEquals(RideTuning(), read.tuning)
            assertTrue(read.overlayExpandsAtSteps)
        }

    @Test
    fun aSettingsFileFromBeforeTheTuningReadsAsTheDefaults() =
        runTest {
            val (repository, store) = repository("old")
            store.edit { it[intPreferencesKey("ftp")] = 230 }
            val read = repository.settings.first()
            assertEquals(230, read.ftp)
            assertEquals(RideTuning(), read.tuning)
        }

    @Test
    fun aStoredValueOutsideItsRuleIsReadOnTheRuleAndNeverReachesTheEngine() =
        runTest {
            val (repository, store) = repository("damaged")
            store.edit {
                it[intPreferencesKey("tune_stall_guard_floor_rpm")] = 4
                it[intPreferencesKey("tune_auto_pause_target_watts")] = 90_000
                it[doublePreferencesKey("tune_hr_gain_watts_per_bpm")] = -3.0
                it[doublePreferencesKey("tune_auto_resume_flywheel_step_kmh")] = Double.NaN
                it[intPreferencesKey("tune_hr_signal_hold_seconds")] = 60
                it[intPreferencesKey("tune_hr_signal_drop_seconds")] = 15
                it[booleanPreferencesKey("tune_stall_guard_enabled")] = false
            }
            val read = repository.settings.first()
            assertEquals(30, read.tuning.stallGuardFloorRpm)
            assertEquals(300, read.tuning.autoPauseTargetWatts)
            assertEquals(0.0, read.tuning.hrGainWattsPerBpm, 0.0)
            assertEquals(0.0, read.tuning.autoResumeFlywheelStepKmh, 0.0)
            assertTrue(read.tuning.hrSignalDropSeconds >= read.tuning.hrSignalHoldSeconds)
            assertFalse(read.tuning.stallGuardEnabled)
            assertTrue(read.tuning.problems().isEmpty())
            read.toRideSettings() // does not throw
        }

    @Test
    fun anUpdateWithAnInvalidValueIsStoredOnTheRule() =
        runTest {
            val (repository, _) = repository("update")
            repository.update { it.copy(tuning = it.tuning.copy(stallGuardFloorRpm = 500, rampInSeconds = -9)) }
            val read = repository.settings.first()
            assertEquals(90, read.tuning.stallGuardFloorRpm)
            assertEquals(0, read.tuning.rampInSeconds)
        }

    @Test
    fun aChangeOfTuningRebuildsAReadyRideButTheOverlaySwitchDoesNot() {
        val base = AppSettings()
        assertTrue(base.rideInputs() != base.copy(tuning = RideTuning(stallGuardFloorRpm = 60)).rideInputs())
        assertTrue(base.rideInputs() != base.copy(tuning = RideTuning(stallGuardEnabled = false)).rideInputs())
        assertEquals(base.rideInputs(), base.copy(overlayExpandsAtSteps = false).rideInputs())
    }
}
