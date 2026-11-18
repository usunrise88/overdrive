package app.overdrive.ride.shots

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.overdrive.core.click.UnlockSignal
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnRejection
import app.overdrive.ride.handlebar.LearnUi
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.ClickKeepAlive
import app.overdrive.ride.settings.ClickSettings
import app.overdrive.ride.settings.SecretText
import app.overdrive.ride.ui.DevicesActions
import app.overdrive.ride.ui.DevicesScreen
import app.overdrive.ride.ui.LearnActions
import app.overdrive.ride.ui.LearnScreen
import app.overdrive.ride.ui.NOT_ENCRYPTED
import app.overdrive.ride.ui.SettingsActions
import app.overdrive.ride.ui.SettingsScreen
import app.overdrive.ride.ui.SyncUi
import app.overdrive.ride.ui.unlockText
import app.overdrive.ride.ui.unlockTitle
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.OverdriveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The Click on the screens of stage M4-2 (ADR 0012, design/system.md §6.5): every state of its Devices row, the notice that
 * tells a locked Click from a silent one, *Learn buttons* from the first role to the saved mapping, and the Sync and Click
 * developer groups of Settings. Each is a picture to look at (`build/shots/`, never committed) and, where there is
 * something to press, a tap that reaches the right action. No radio, no real names or addresses.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h1200dp-xhdpi")
class ClickUiTest : ShotTest() {
    private val trainerOk = DeviceRowUi(DeviceStatus.Connected, "D500", canForget = true)
    private val strapOk = DeviceRowUi(DeviceStatus.Connected, "HR7", canForget = true)

    private val connected =
        DeviceRowUi(DeviceStatus.Connected, "Zwift Click (right) · battery 87%", canForget = true)
    private val searching =
        DeviceRowUi(DeviceStatus.Searching, "Connecting to Zwift Click (right)…", canForget = true)
    private val checking =
        DeviceRowUi(DeviceStatus.Searching, "Zwift Click (right) · press any button", canForget = true)
    private val lost = DeviceRowUi(DeviceStatus.Lost, "Not found · retrying in 8 s", canForget = true)
    private val hidden =
        DeviceRowUi(
            DeviceStatus.Locked,
            "Zwift Click (right) · locked",
            canForget = true,
            locked = UnlockSignal.SERVICE_HIDDEN,
        )
    private val silent =
        DeviceRowUi(
            DeviceStatus.Locked,
            "Zwift Click (right) · locked",
            canForget = true,
            locked = UnlockSignal.NO_EVENTS,
        )
    private val blocked =
        DeviceRowUi(DeviceStatus.Blocked, "Zwift Click (right) · Bluetooth is off", canForget = true)
    private val notPaired = DeviceRowUi(DeviceStatus.NotPaired, "Not paired")

    private fun devices(
        click: DeviceRowUi,
        radio: RadioIssue? = null,
        canChange: Boolean = true,
        simulated: Boolean = false,
    ) = DevicesUi(trainerOk, strapOk, click, radio = radio, canChange = canChange, simulated = simulated)

    private val learnedMapping =
        checkNotNull(ButtonMapping.of(HandlebarButton.entries.zip(listOf(0x40, 0x80, 0x10, 0x20, 0x1000)).toMap()))

    private var shownDevices: MutableState<DevicesUi>? = null
    private var shownMapping: MutableState<ButtonMapping>? = null

    /** Puts [ui] on the Devices screen; the second call in a test switches what is shown, because content is set once. */
    private fun screen(
        ui: DevicesUi,
        actions: DevicesActions = DevicesActions({}, {}, {}, {}, {}),
        mapping: ButtonMapping = ButtonMapping.DEFAULT,
    ) {
        val devices = shownDevices
        val mapped = shownMapping
        if (devices != null && mapped != null) {
            devices.value = ui
            mapped.value = mapping
            rule.waitForIdle()
            return
        }
        val newDevices = mutableStateOf(ui)
        val newMapping = mutableStateOf(mapping)
        shownDevices = newDevices
        shownMapping = newMapping
        rule.setContent { OverdriveTheme { DevicesScreen(newDevices.value, actions, mapping = newMapping.value) } }
    }

    // ---- Pictures: the Click row ----------------------------------------------------------------

    @Test
    fun clickConnected() =
        shot("devices--click-connected") {
            OverdriveTheme { DevicesScreen(devices(connected), actions()) }
        }

    @Test
    fun clickSearching() =
        shot("devices--click-searching") {
            OverdriveTheme { DevicesScreen(devices(searching), actions()) }
        }

    @Test
    fun clickNotFound() =
        shot("devices--click-not-found") { OverdriveTheme { DevicesScreen(devices(lost), actions()) } }

    @Test
    fun clickLockedServiceHidden() =
        shot("devices--click-locked-hidden") { OverdriveTheme { DevicesScreen(devices(hidden), actions()) } }

    @Test
    fun clickLockedSilent() =
        shot("devices--click-locked-silent") { OverdriveTheme { DevicesScreen(devices(silent), actions()) } }

    @Test
    fun clickNotPaired() =
        shot("devices--click-not-paired") {
            OverdriveTheme { DevicesScreen(devices(notPaired), actions()) }
        }

    @Test
    fun clickBluetoothOff() =
        shot("devices--click-bluetooth-off") {
            OverdriveTheme { DevicesScreen(devices(blocked, radio = RadioIssue.BluetoothOff), actions()) }
        }

    @Test
    fun clickDuringARide() =
        shot(
            "devices--click-ride-live",
        ) { OverdriveTheme { DevicesScreen(devices(connected, canChange = false), actions()) } }

    @Test
    fun clickWithLearnedButtons() =
        shot("devices--click-learned") {
            OverdriveTheme { DevicesScreen(devices(connected), actions(), mapping = learnedMapping) }
        }

    private fun actions() = DevicesActions({}, {}, {}, {}, {})

    // ---- The Click row in words and taps --------------------------------------------------------

    @Test
    fun everyStateOfTheClickRowIsSaidInPlainWords() {
        val cases =
            listOf(
                connected to "Zwift Click (right) · battery 87%",
                searching to "Connecting to Zwift Click (right)…",
                checking to "Zwift Click (right) · press any button",
                lost to "Not found · retrying in 8 s",
                hidden to "Zwift Click (right) · locked",
                blocked to "Zwift Click (right) · Bluetooth is off",
                notPaired to "Not paired",
            )
        for ((row, text) in cases) {
            screen(devices(row, radio = if (row === blocked) RadioIssue.BluetoothOff else null))
            rule.onAllNodesWithText(text).assertCountEquals(1)
        }
    }

    @Test
    fun aLockedClickGetsTheNoticeForItsSignalAndNoOtherRowDoes() {
        screen(devices(hidden))
        rule.onNodeWithText(unlockTitle(UnlockSignal.SERVICE_HIDDEN)).assertIsDisplayed()
        rule.onNodeWithText(unlockText(UnlockSignal.SERVICE_HIDDEN)).assertIsDisplayed()
        rule.onAllNodesWithText(unlockTitle(UnlockSignal.NO_EVENTS)).assertCountEquals(0)
        screen(devices(connected))
        rule.onAllNodesWithText(unlockTitle(UnlockSignal.SERVICE_HIDDEN)).assertCountEquals(0)
    }

    @Test
    fun theTwoSignalsOfALockHaveTheirOwnWordsAndTheSamePromise() {
        val hiddenText = unlockText(UnlockSignal.SERVICE_HIDDEN)
        val silentText = unlockText(UnlockSignal.NO_EVENTS)
        assertNotEquals(hiddenText, silentText)
        assertNotEquals(unlockTitle(UnlockSignal.SERVICE_HIDDEN), unlockTitle(UnlockSignal.NO_EVENTS))
        for (text in listOf(hiddenText, silentText)) {
            assertTrue(text, text.contains("unlock it through Zwift once", ignoreCase = true))
            assertTrue(text, text.contains("the ride continues without it"))
            assertEquals("one sentence-ish, not a paragraph", 2, text.count { it == '.' })
        }
        assertTrue("hidden says Zwift has it locked", hiddenText.startsWith("Zwift has it locked."))
        assertTrue("silent says to press a button first", silentText.startsWith("Press any button on it."))

        screen(devices(silent))
        rule.onNodeWithText(silentText).assertIsDisplayed()
        rule.onAllNodesWithText(hiddenText).assertCountEquals(0)
    }

    @Test
    fun theButtonsOfTheClickRowReachTheirActions() {
        var checked = 0
        var retried = 0
        var forgotten = 0
        var scanned = 0
        val actions =
            DevicesActions(
                onBack = {},
                onRetryTrainer = {},
                onRetryHeartRate = {},
                onPairClick = {},
                onCheckClick = { checked++ },
                onScan = { scanned++ },
                onRetryClick = { retried++ },
                onForgetClick = { forgotten++ },
            )
        screen(devices(lost), actions)
        rule.onNodeWithText("Retry now").performClick()
        assertEquals(1, retried)
        // The strap and the trainer are connected, so the only Forget buttons are the three rows': the Click's is the last.
        rule.onAllNodesWithText("Forget")[2].performClick()
        assertEquals(1, forgotten)

        screen(devices(silent), actions)
        rule.onNodeWithText("Check again").performClick()
        assertEquals(1, checked)

        screen(devices(notPaired), actions)
        rule.onNodeWithText("Pair").performClick()
        assertEquals("Pair is a scan, like the other rows'", 1, scanned)
    }

    @Test
    fun noRowOfAClickIsOfferedForgetDuringARide() {
        screen(devices(connected, canChange = false))
        rule.onAllNodesWithText("Forget").assertCountEquals(0)
        rule.onNodeWithText("Devices cannot be changed during a ride.").assertIsDisplayed()
    }

    // ---- Learn buttons offered and refused ------------------------------------------------------

    @Test
    fun learningIsOfferedWithAConnectedClickAndNoLiveRide() {
        var learn = 0
        var reset = 0
        val actions = DevicesActions({}, {}, {}, {}, {}, onLearnButtons = { learn++ }, onResetButtons = { reset++ })
        screen(devices(connected), actions)
        rule.onNodeWithText("Learn buttons").assertIsEnabled().performClick()
        assertEquals(1, learn)
        rule.onNodeWithText("Reset to default").assertIsNotEnabled()

        screen(devices(connected), actions, mapping = learnedMapping)
        rule.onNodeWithText("Reset to default").assertIsEnabled().performClick()
        assertEquals(1, reset)
    }

    @Test
    fun learningIsRefusedWithAPlainReasonWhenItCannotWork() {
        val reasons =
            listOf(
                devices(connected, canChange = false) to "Not during a ride.",
                devices(lost) to "Connect the Click to learn its buttons.",
                devices(notPaired) to "Connect the Click to learn its buttons.",
                devices(hidden) to "Connect the Click to learn its buttons.",
                devices(connected, simulated = true) to
                    "Learning needs the real Click: switch to Bluetooth under Settings, Developer.",
            )
        for ((ui, reason) in reasons) {
            screen(ui)
            rule.onNodeWithText("Learn buttons").assertIsNotEnabled()
            rule.onNodeWithText(reason).assertIsDisplayed()
        }
    }

    @Test
    fun theButtonMapShowsTheClickNamesOfTheLearnedButtons() {
        screen(devices(connected), mapping = learnedMapping)
        // Plus is now Y, Button 2 is Z, and so on: the Click's own names, by role.
        for (name in listOf("Y", "Z", "A", "B")) rule.onAllNodesWithText(name).assertCountEquals(1)
        // "Plus" is read twice: as the first role's name and as the button that is the last role now.
        rule.onAllNodesWithText("Plus").assertCountEquals(2)
    }

    // ---- Learn screen ---------------------------------------------------------------------------

    private val asking1 = LearnUi(HandlebarButton.PLUS, step = 1, total = 5, learned = emptyMap())
    private val asking3 =
        LearnUi(
            HandlebarButton.BUTTON_3,
            step = 3,
            total = 5,
            learned = mapOf(HandlebarButton.PLUS to listOf("Y"), HandlebarButton.BUTTON_2 to listOf("Z")),
        )
    private val combo = asking3.copy(rejection = LearnRejection.COMBO)
    private val duplicate = asking3.copy(rejection = LearnRejection.DUPLICATE, clashesWith = HandlebarButton.PLUS)
    private val done =
        LearnUi(
            HandlebarButton.BUTTON_5,
            step = 5,
            total = 5,
            learned =
                mapOf(
                    HandlebarButton.PLUS to listOf("Y"),
                    HandlebarButton.BUTTON_2 to listOf("Z"),
                    HandlebarButton.BUTTON_3 to listOf("A"),
                    HandlebarButton.BUTTON_4 to listOf("B"),
                    HandlebarButton.BUTTON_5 to listOf("PLUS"),
                ),
            done = true,
        )

    private var shownLearn: MutableState<LearnUi>? = null
    private var shownReady: MutableState<Boolean>? = null

    /** Puts [ui] on the Learn screen; a second call in a test switches what is shown. */
    private fun learn(
        ui: LearnUi,
        onCancel: () -> Unit = {},
        onReset: () -> Unit = {},
        clickReady: Boolean = true,
    ) {
        val state = shownLearn
        val ready = shownReady
        if (state != null && ready != null) {
            state.value = ui
            ready.value = clickReady
            rule.waitForIdle()
            return
        }
        val newState = mutableStateOf(ui)
        val newReady = mutableStateOf(clickReady)
        shownLearn = newState
        shownReady = newReady
        rule.setContent {
            OverdriveTheme { LearnScreen(newState.value, LearnActions(onCancel, onReset), clickReady = newReady.value) }
        }
    }

    @Test
    fun learnFirstRole() = shot("learn--asking-first") { OverdriveTheme { LearnScreen(asking1, LearnActions({}, {})) } }

    @Test
    fun learnThirdRoleWithTwoSettled() =
        shot("learn--asking-third") { OverdriveTheme { LearnScreen(asking3, LearnActions({}, {})) } }

    @Test
    fun learnComboRefused() = shot("learn--combo") { OverdriveTheme { LearnScreen(combo, LearnActions({}, {})) } }

    @Test
    fun learnDuplicateRefused() =
        shot("learn--duplicate") {
            OverdriveTheme { LearnScreen(duplicate, LearnActions({}, {})) }
        }

    @Test
    fun learnDone() = shot("learn--done") { OverdriveTheme { LearnScreen(done, LearnActions({}, {})) } }

    @Test
    fun learnClickDropped() =
        shot(
            "learn--click-dropped",
        ) { OverdriveTheme { LearnScreen(asking3, LearnActions({}, {}), clickReady = false) } }

    @Test
    fun theLearnScreenAsksForOneRoleAtATimeInPlainWords() {
        learn(asking1)
        rule.onNodeWithText("Step 1 of 5").assertIsDisplayed()
        rule.onNodeWithText("Press the button you want as Plus").assertIsDisplayed()
        rule.onNodeWithText("Press now").assertIsDisplayed()
        // Every role is listed with what it does.
        for (role in HandlebarButton.entries) rule.onNodeWithText(role.label).assertExists()

        learn(asking3)
        rule.onNodeWithText("Step 3 of 5").assertIsDisplayed()
        rule.onNodeWithText("Press the button you want as Button 3").assertIsDisplayed()
        rule.onNodeWithText("Y").assertIsDisplayed()
        rule.onNodeWithText("Z").assertIsDisplayed()
    }

    @Test
    fun aRefusedPressSaysWhyAndNamesTheRoleThatHasTheButton() {
        learn(combo)
        rule.onNodeWithText("One button at a time").assertIsDisplayed()
        rule
            .onNodeWithText(
                "Two buttons were pressed together. Press just the one you want as Button 3.",
            ).assertIsDisplayed()

        learn(duplicate)
        rule.onNodeWithText("That button is taken").assertIsDisplayed()
        rule.onNodeWithText("It is already Plus. Press a different one for Button 3.").assertIsDisplayed()
    }

    @Test
    fun theLearnScreenCanBeCancelledAndResetAtAnyTime() {
        var cancelled = 0
        var reset = 0
        learn(asking3, onCancel = { cancelled++ }, onReset = { reset++ })
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Reset to default").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, reset)
        rule.onAllNodesWithText("Done").assertCountEquals(0)
    }

    @Test
    fun whenAllFiveAreSetTheMappingIsSavedAndTheOneButtonIsDone() {
        var left = 0
        learn(done, onCancel = { left++ })
        rule.onNodeWithText("All five set").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, left)
        rule.onAllNodesWithText("Cancel").assertCountEquals(0)
        // The Click's name for the button, as it is read: Plus is the first role's name and the last role's button.
        rule.onAllNodesWithText("Plus").assertCountEquals(2)
    }

    @Test
    fun aClickThatDroppedMeanwhileSaysToReconnectIt() {
        learn(asking3, clickReady = false)
        rule.onNodeWithText("The Click is not connected. Reconnect it to go on.").assertIsDisplayed()
    }

    // ---- Settings: Sync and the Click's developer group -----------------------------------------

    private val settings = AppSettings(ftp = 250, massKg = 82.0, cdA = 0.32)

    @Test
    fun syncSettingsEmpty() =
        shot("settings--sync-empty") { OverdriveTheme { SettingsScreen(settings, SettingsActions({}, {}, {})) } }

    @Test
    fun syncSettingsFilled() =
        shot("settings--sync-filled") {
            OverdriveTheme {
                SettingsScreen(
                    settings.copy(syncUrl = "https://overdrive.example.org", syncToken = SecretText("a-long-token")),
                    SettingsActions({}, {}, {}),
                    sync = SyncUi(3, "Service unreachable. Trying again soon", "Today 18:04", canSendNow = true),
                )
            }
        }

    @Test
    fun syncSettingsPlainAddressRejectedToken() =
        shot("settings--sync-plain-http") {
            OverdriveTheme {
                SettingsScreen(
                    settings.copy(syncUrl = "http://192.168.1.20:8080", syncToken = SecretText("a-long-token")),
                    SettingsActions({}, {}, {}),
                    sync =
                        SyncUi(
                            1,
                            "The service did not accept the token. Check the token above",
                            "18 Nov 09:12",
                            true,
                        ),
                )
            }
        }

    @Test
    fun clickDeveloperSettingsChanged() =
        shot("settings--developer-click") {
            OverdriveTheme {
                SettingsScreen(
                    settings.copy(
                        click =
                            ClickSettings(
                                sendPostHandshake = false,
                                keepAlive = ClickKeepAlive.Every30,
                                holdThresholdMillis = 650,
                            ),
                    ),
                    SettingsActions({}, {}, {}),
                )
            }
        }

    private fun settingsScreen(
        initial: AppSettings,
        onChange: (AppSettings) -> Unit,
        sync: SyncUi = SyncUi(),
        onSendNow: () -> Unit = {},
    ) {
        val current = mutableStateOf(initial)
        rule.setContent {
            OverdriveTheme {
                SettingsScreen(
                    current.value,
                    SettingsActions(
                        onBack = {},
                        onChange = { change ->
                            current.value = change(current.value)
                            onChange(current.value)
                        },
                        onResetOverlayPosition = {},
                        onSendNow = onSendNow,
                    ),
                    sync = sync,
                )
            }
        }
    }

    @Test
    fun theSyncGroupCountsWhatWaitsAndSaysWhenTheLastUploadWas() {
        settingsScreen(settings, {}, sync = SyncUi(waiting = 1, lastUpload = "Today 18:04", canSendNow = true))
        rule.onNodeWithText("Sync").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Where finished rides are sent").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sent only to this address").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 ride").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Last upload").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Today 18:04").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Not used yet").assertCountEquals(0)
    }

    @Test
    fun theLineSaysInPlainWordsWhatWentWrong() {
        val line = "1 ride refused: Ride ride-1 is already stored with different content"
        settingsScreen(settings, {}, sync = SyncUi(waiting = 1, line = line, canSendNow = true))
        rule.onNodeWithText(line).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Never").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sendNowIsOfferedOnlyWhileSomethingWaitsAndSaysSo() {
        var sent = 0
        settingsScreen(settings, {}, sync = SyncUi(waiting = 2, canSendNow = true), onSendNow = { sent++ })
        rule.onNodeWithText("Send now").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun sendNowIsOffWhenNothingWaitsOrAnUploadIsUnderWay() {
        settingsScreen(settings, {}, sync = SyncUi(waiting = 0, canSendNow = false))
        rule.onNodeWithText("Send now").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aPlainHttpAddressIsAcceptedAndFlaggedNotEncrypted() {
        var latest = settings
        settingsScreen(settings, { latest = it })
        rule.onAllNodesWithText(NOT_ENCRYPTED).assertCountEquals(0)
        rule
            .onNodeWithContentDescription("Service address")
            .performScrollTo()
            .performTextInput("http://192.168.1.20:8080")
        assertEquals("accepted and stored", "http://192.168.1.20:8080", latest.syncUrl)
        rule.onNodeWithText(NOT_ENCRYPTED).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anHttpsAddressIsNotFlagged() {
        settingsScreen(settings.copy(syncUrl = "https://overdrive.example.org"), {})
        rule.onAllNodesWithText(NOT_ENCRYPTED).assertCountEquals(0)
    }

    @Test
    fun aGoodAddressIsStoredInItsCleanFormAndABadOneIsExplainedAndNotStored() {
        var latest = settings
        settingsScreen(settings, { latest = it })
        rule
            .onNodeWithContentDescription(
                "Service address",
            ).performScrollTo()
            .performTextInput("HTTPS://Example.org/api")
        assertEquals("https://example.org/api", latest.syncUrl)

        // Spaces and a missing scheme are refused with a reason, and nothing is stored for them.
        rule.onNodeWithContentDescription("Service address").performTextInput(" x")
        rule.onNodeWithText("An address has no spaces.").assertIsDisplayed()
        assertEquals("the last good address is kept", "https://example.org/api", latest.syncUrl)
    }

    @Test
    fun anAddressWithACredentialInItIsRefusedWithAPlainReason() {
        var latest = settings
        settingsScreen(settings, { latest = it })
        rule
            .onNodeWithContentDescription("Service address")
            .performScrollTo()
            .performTextInput("https://rider:secret@example.org")
        rule.onNodeWithText("Leave the user name and password out; the token goes below.").assertIsDisplayed()
        assertEquals("", latest.syncUrl)
    }

    /** What a screen reader and the screen get from the field: the text it shows, not the one that was typed. */
    private fun shownText(description: String): String =
        rule
            .onNodeWithContentDescription(description)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    @Test
    fun theTokenIsHiddenWhileTypedAndShownOnlyOnRequest() {
        var latest = settings
        settingsScreen(settings, { latest = it })
        rule.onNodeWithContentDescription("Token").performScrollTo().performTextInput("t0ken-value")
        assertEquals("t0ken-value", latest.syncToken.value)
        assertFalse("it never prints as itself", latest.toString().contains("t0ken-value"))
        assertEquals("only bullets are shown", "•".repeat("t0ken-value".length), shownText("Token"))
        rule
            .onNodeWithContentDescription("Token")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        rule.onNodeWithText("Show").performScrollTo().performClick()
        assertEquals("t0ken-value", shownText("Token"))
        rule.onNodeWithText("Hide").performClick()
        assertEquals("•".repeat("t0ken-value".length), shownText("Token"))
    }

    @Test
    fun theClickDeveloperSettingsChangeAndResetPutsThemAllBack() {
        var latest = settings
        settingsScreen(settings, { latest = it })
        rule.onNodeWithText("Developer · Click").performScrollTo().assertIsDisplayed()
        // The Click's is the last "Reset" on the screen: the overlay's position has one too, in the left column.
        rule.onAllNodesWithText("Reset").onLast().performScrollTo().assertIsNotEnabled()

        rule.onNodeWithContentDescription("Setup message").performScrollTo().performClick()
        assertFalse(latest.click.sendPostHandshake)
        rule.onNodeWithContentDescription("Handshake with response").performScrollTo().performClick()
        assertTrue(latest.click.writeWithResponse)
        assertFalse("off by default: a healthy Click is silent until a press", latest.click.silentMeansLocked)
        rule.onNodeWithContentDescription("Silence means locked").performScrollTo().performClick()
        assertTrue(latest.click.silentMeansLocked)
        rule.onNodeWithContentDescription("Increase hold threshold").performScrollTo().performClick()
        assertEquals(550, latest.click.holdThresholdMillis)
        rule.onNodeWithContentDescription("Decrease volume repeat").performScrollTo().performClick()
        assertEquals(140, latest.click.volumeRepeatMillis)

        rule
            .onAllNodesWithText("Reset")
            .onLast()
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(ClickSettings(), latest.click)
    }

    @Test
    fun theGestureTimingsStopAtTheirSafeRanges() {
        var latest = settings.copy(click = ClickSettings(holdThresholdMillis = 1000, volumeRepeatMillis = 50))
        settingsScreen(latest, { latest = it })
        rule.onNodeWithContentDescription("Increase hold threshold").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease volume repeat").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease hold threshold").performScrollTo().assertIsEnabled()
        rule.onNodeWithContentDescription("Increase volume repeat").performScrollTo().assertIsEnabled()
    }
}
