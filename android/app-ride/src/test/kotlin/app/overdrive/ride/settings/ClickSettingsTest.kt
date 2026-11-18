package app.overdrive.ride.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickSide
import app.overdrive.core.click.HandshakeReplyCheck
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarButton
import app.overdrive.ride.devices.InMemoryPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The Click's developer settings and the learned button mapping (ADR 0012): every default is the one `core` has, every
 * value is brought into a range the Click and the gesture layer accept, and a damaged settings file reads as defaults and
 * never reaches them.
 */
class ClickSettingsTest {
    private val hold = intPreferencesKey("dev_click_hold_ms")
    private val repeat = intPreferencesKey("dev_click_volume_repeat_ms")
    private val mapping = stringPreferencesKey("click_button_mapping")

    @Test
    fun theDefaultsAreTheOnesCoreHasSoAFreshInstallBehavesAsTheNotesRecommend() {
        val settings = ClickSettings()
        val core = ClickOptions()
        val options = settings.toOptions()
        assertEquals(core.sendPostHandshake, options.sendPostHandshake)
        assertEquals(core.keepAliveInterval, options.keepAliveInterval)
        assertEquals(core.noEventTimeout, options.noEventTimeout)
        assertEquals(core.requestedMtu, options.requestedMtu)
        assertEquals(core.writeWithResponse, options.writeWithResponse)
        assertEquals(core.requireAdvertisedService, options.requireAdvertisedService)
        assertEquals(core.acceptedSides, options.acceptedSides)
        assertEquals(core.handshakeReply, options.handshakeReply)
        val timing = settings.toTiming()
        val defaults = GestureTiming()
        assertEquals(defaults.holdThresholdMillis, timing.holdThresholdMillis)
        assertEquals(defaults.repeatIntervalMillis, timing.repeatIntervalMillis)
        assertEquals(defaults.bounceMillis, timing.bounceMillis)
        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun everyChoiceReachesTheOptionsCoreTakes() {
        val options =
            ClickSettings(
                sendPostHandshake = false,
                keepAlive = ClickKeepAlive.Every30,
                noEventTimeout = ClickSilence.Long,
                mtu = ClickMtu.System,
                writeWithResponse = true,
                requireAdvertisedService = false,
                pucks = ClickPucks.Either,
                replyCheck = ClickReplyCheck.Lenient,
            ).toOptions()
        assertFalse(options.sendPostHandshake)
        assertEquals(30.seconds, options.keepAliveInterval)
        assertEquals(60.seconds, options.noEventTimeout)
        assertNull(options.requestedMtu)
        assertTrue(options.writeWithResponse)
        assertFalse(options.requireAdvertisedService)
        assertEquals(setOf(ClickSide.RIGHT, ClickSide.LEFT), options.acceptedSides)
        assertEquals(HandshakeReplyCheck.ANY_RIDE_ON, options.handshakeReply)

        assertEquals(10.seconds, ClickSettings(keepAlive = ClickKeepAlive.Every10).toOptions().keepAliveInterval)
        assertEquals(247, ClickSettings(mtu = ClickMtu.Large).toOptions().requestedMtu)
        assertEquals(setOf(ClickSide.RIGHT), ClickSettings(pucks = ClickPucks.Right).toOptions().acceptedSides)
    }

    @Test
    fun theTimingsAreBroughtIntoTheirRangesAndOntoTheirSteps() {
        assertEquals(250, ClickRules.holdThreshold.normalize(0))
        assertEquals(250, ClickRules.holdThreshold.normalize(-40))
        assertEquals(1000, ClickRules.holdThreshold.normalize(10_000))
        assertEquals(500, ClickRules.holdThreshold.normalize(520))
        assertEquals(550, ClickRules.holdThreshold.normalize(530))
        assertEquals(50, ClickRules.volumeRepeat.normalize(1))
        assertEquals(500, ClickRules.volumeRepeat.normalize(99_999))
        assertEquals(160, ClickRules.volumeRepeat.normalize(157))

        val sanitized = ClickSettings(holdThresholdMillis = 3, volumeRepeatMillis = 4_000).sanitized()
        assertEquals(250, sanitized.holdThresholdMillis)
        assertEquals(500, sanitized.volumeRepeatMillis)
        // The spec's own values are on the grid.
        assertEquals(500, ClickRules.holdThreshold.normalize(500))
        assertEquals(150, ClickRules.volumeRepeat.normalize(150))
    }

    @Test
    fun noValueOnTheGridCanMakeCoreRefuseTheTiming() {
        var checked = 0
        var h = ClickRules.holdThreshold.min
        while (h <= ClickRules.holdThreshold.max) {
            var r = ClickRules.volumeRepeat.min
            while (r <= ClickRules.volumeRepeat.max) {
                // Throws (and fails the test) if the gesture layer would refuse it.
                ClickSettings(holdThresholdMillis = h, volumeRepeatMillis = r).sanitized().toTiming()
                checked++
                r += ClickRules.volumeRepeat.step
            }
            h += ClickRules.holdThreshold.step
        }
        assertTrue(checked > 500)
    }

    @Test
    fun theChoicesAreStoredAndReadBack() =
        runTest {
            val repository = SettingsRepository(InMemoryPreferences())
            val chosen =
                ClickSettings(
                    sendPostHandshake = false,
                    keepAlive = ClickKeepAlive.Every60,
                    noEventTimeout = ClickSilence.VeryLong,
                    mtu = ClickMtu.Large,
                    writeWithResponse = true,
                    requireAdvertisedService = false,
                    pucks = ClickPucks.Either,
                    replyCheck = ClickReplyCheck.Lenient,
                    holdThresholdMillis = 750,
                    volumeRepeatMillis = 90,
                )
            repository.update { it.copy(click = chosen) }
            assertEquals(chosen, repository.settings.first().click)
        }

    @Test
    fun aWriteIsSanitisedSoTheScreenNeedsNoChecksOfItsOwn() =
        runTest {
            val repository = SettingsRepository(InMemoryPreferences())
            repository.update { it.copy(click = it.click.copy(holdThresholdMillis = 3, volumeRepeatMillis = 12_345)) }
            val read = repository.settings.first().click
            assertEquals(250, read.holdThresholdMillis)
            assertEquals(500, read.volumeRepeatMillis)
        }

    @Test
    fun aDamagedSettingsFileReadsAsDefaultsAndNeverAsSomethingCoreRefuses() =
        runTest {
            val store = InMemoryPreferences()
            store.edit {
                it[hold] = -50
                it[repeat] = Int.MAX_VALUE
                it[stringPreferencesKey("dev_click_keep_alive")] = "EveryMillisecond"
                it[stringPreferencesKey("dev_click_no_event_timeout")] = ""
                it[stringPreferencesKey("dev_click_mtu")] = "Huge"
                it[stringPreferencesKey("dev_click_pucks")] = "Left"
                it[stringPreferencesKey("dev_click_reply_check")] = "none"
            }
            val read = SettingsRepository(store).settings.first().click
            val defaults = ClickSettings()
            assertEquals(250, read.holdThresholdMillis)
            assertEquals(500, read.volumeRepeatMillis)
            assertEquals(defaults.keepAlive, read.keepAlive)
            assertEquals(defaults.noEventTimeout, read.noEventTimeout)
            assertEquals(defaults.mtu, read.mtu)
            assertEquals(defaults.pucks, read.pucks)
            assertEquals(defaults.replyCheck, read.replyCheck)
            // And what is built from it is accepted by core.
            read.toOptions()
            read.toTiming()
        }

    @Test
    fun resetClickSettingsPutsEveryChoiceBackAndLeavesTheLearnedButtonsAndTheRestAlone() =
        runTest {
            val repository = SettingsRepository(InMemoryPreferences())
            val learned =
                checkNotNull(
                    ButtonMapping.of(
                        HandlebarButton.entries.zip(listOf(0x40, 0x80, 0x10, 0x20, 0x1000)).toMap(),
                    ),
                )
            repository.update {
                it.copy(
                    ftp = 260,
                    buttonMapping = learned,
                    click = ClickSettings(sendPostHandshake = false, holdThresholdMillis = 800),
                    syncUrl = "https://example.org/api",
                )
            }
            repository.update { it.copy(click = ClickSettings()) }
            val read = repository.settings.first()
            assertEquals(ClickSettings(), read.click)
            assertEquals(learned, read.buttonMapping)
            assertEquals(260, read.ftp)
            assertEquals("https://example.org/api", read.syncUrl)
        }

    // ---- The learned mapping in the settings file --------------------------------------------

    @Test
    fun aLearnedMappingIsStoredAndReadBack() =
        runTest {
            val repository = SettingsRepository(InMemoryPreferences())
            val learned =
                checkNotNull(
                    ButtonMapping.of(
                        HandlebarButton.entries.zip(listOf(0x40, 0x80, 0x10, 0x20, 0x1000)).toMap(),
                    ),
                )
            assertEquals(
                "nothing stored reads as the default",
                ButtonMapping.DEFAULT,
                repository.settings.first().buttonMapping,
            )
            repository.update { it.copy(buttonMapping = learned) }
            assertEquals(learned, repository.settings.first().buttonMapping)
        }

    @Test
    fun aCorruptStoredMappingFallsBackToTheDefaultWhateverTheDamage() =
        runTest {
            val damaged =
                listOf(
                    "garbage",
                    "",
                    ";;;",
                    // Only some roles.
                    "PLUS=00001000;BUTTON_2=00000020",
                    // Two roles on one button.
                    "PLUS=00001000;BUTTON_2=00001000;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                    // A role with no button.
                    "PLUS=00000000;BUTTON_2=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                    // Not hex, and a number cut short.
                    "PLUS=0000100G;BUTTON_2=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                    "PLUS=1000;BUTTON_2=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                    // A role that does not exist, and a role twice.
                    "PLUS=00001000;BUTTON_9=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                    "PLUS=00001000;PLUS=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
                )
            for (text in damaged) {
                val store = InMemoryPreferences()
                store.edit { it[mapping] = text }
                assertEquals(
                    "stored: '$text'",
                    ButtonMapping.DEFAULT,
                    SettingsRepository(store).settings.first().buttonMapping,
                )
            }
        }

    @Test
    fun theStoredTextIsTheMappingsOwnSerialisation() =
        runTest {
            val store = InMemoryPreferences()
            val repository = SettingsRepository(store)
            repository.update { it }
            assertEquals(ButtonMapping.DEFAULT.serialize(), store.data.first()[mapping])
            assertNull(ButtonMapping.parse("PLUS=00001000"))
        }
}
