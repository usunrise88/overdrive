package app.overdrive.ride.shots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.data.rides.StoredRide
import app.overdrive.data.rides.StoredRideStatus
import app.overdrive.ride.host.FinishedRide
import app.overdrive.ride.host.NoMedia
import app.overdrive.ride.host.RideActions
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.rides.RecoveryState
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.ui.FinishedFormat
import app.overdrive.ride.ui.FinishedScreen
import app.overdrive.ride.ui.ReadyActions
import app.overdrive.ride.ui.ReadyScreen
import app.overdrive.ride.ui.RideFileActions
import app.overdrive.ride.ui.RidingScreen
import app.overdrive.ride.ui.TopBarActions
import app.overdrive.ride.ui.fileName
import app.overdrive.ride.ui.recoveryText
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.UiFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/** Remembers what the screens asked the files to do. */
private class RecordingFiles : RideFileActions {
    val calls = mutableListOf<String>()

    override fun share(ride: StoredRide) {
        calls += "share ${ride.id}"
    }

    override fun saveToFolder(ride: StoredRide) {
        calls += "save ${ride.id}"
    }
}

/**
 * The storage states of the screens (ADR 0008): the recovery notice with its confirmation, the last-ride row, the finished
 * screen's saving, saved and failed states, and the riding screen's one notice. Also the screenshots of them, in
 * `build/shots/` (never committed): finished saved, finished with a failed save, ready with the recovery notice, ready with
 * the last-ride row.
 */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi")
class StorageUiTest : ShotTest() {
    private val topBar = TopBarActions({}, {}, {})
    private val settings = AppSettings(ftp = 250, massKg = 82.0, cdA = 0.32)
    private val workouts = TestData.library.workouts.value
    private val ride = TestData.storedRide()
    private val interrupted = TestData.storedRide(StoredRideStatus.Interrupted, 1937, null)
    private var zone: TimeZone = TimeZone.getDefault()
    private var locale: Locale = Locale.getDefault()

    @Before
    fun fixZoneAndLocale() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    private fun ready(
        recovery: RecoveryState = RecoveryState.None,
        lastRide: StoredRide? = null,
        files: RideFileActions = RideFileActions.None,
        fileMessage: String? = null,
        actions: ReadyActions = ReadyActions({}, {}, {}, {}),
        forceDiscardConfirm: Boolean = false,
    ): @Composable () -> Unit =
        {
            OverdriveTheme {
                ReadyScreen(
                    choice = RideChoice.Planned(TestData.sweetSpot),
                    workouts = workouts,
                    settings = settings,
                    devices = TestData.devicesOk,
                    startEnabled = true,
                    openError = null,
                    topBar = topBar,
                    actions = actions,
                    recovery = recovery,
                    lastRide = lastRide,
                    files = files,
                    fileMessage = fileMessage,
                    forceDiscardConfirm = forceDiscardConfirm,
                )
            }
        }

    private fun finished(
        save: SaveState,
        files: RideFileActions = RideFileActions.None,
        fileMessage: String? = null,
        onDone: () -> Unit = {},
        onRetry: () -> Unit = {},
        ride: FinishedRide = TestData.finishedRide().copy(save = save),
    ): @Composable () -> Unit =
        {
            OverdriveTheme {
                FinishedScreen(
                    ride,
                    TestData.devicesOk,
                    topBar,
                    onDone = onDone,
                    files = files,
                    fileMessage = fileMessage,
                    onRetrySave = onRetry,
                )
            }
        }

    // ---- Recovery notice -------------------------------------------------------------------------------------------

    @Test
    fun noNoticeWhenThereIsNothingToRecover() {
        rule.setContent(ready())
        rule.onNodeWithText("An interrupted ride was found").assertDoesNotExist()
    }

    @Test
    fun theNoticeNamesTheRideAndOffersSaveAndDiscard() {
        rule.setContent(ready(RecoveryState.Found(interrupted, more = 0)))
        rule.onNodeWithText("An interrupted ride was found").assertIsDisplayed()
        rule.onNode(hasText("Wednesday 18 November · 10:00 · 32:17 ridden.", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun theTextCountsTheRidesBehindThisOne() {
        assertEquals(
            "Wednesday 18 November · 10:00 · 32:17 ridden. Save it to keep the ride, or discard it.",
            recoveryText(interrupted, 0),
        )
        assertEquals(
            "Wednesday 18 November · 10:00 · 32:17 ridden. 2 more after this one.",
            recoveryText(interrupted, 2),
        )
    }

    @Test
    fun saveCallsTheActionAtOnce() {
        var saved = 0
        rule.setContent(
            ready(
                RecoveryState.Found(interrupted, 0),
                actions =
                    ReadyActions({
                    }, {}, {}, {}, onRecoverySave = { saved++ }),
            ),
        )
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun discardAsksFirstAndKeepItLeavesTheRideAlone() {
        var discarded = 0
        val actions = ReadyActions({}, {}, {}, {}, onRecoveryDiscard = { discarded++ })
        rule.setContent(ready(RecoveryState.Found(interrupted, 0), actions = actions))
        rule.onNodeWithText("Discard this ride?").assertDoesNotExist()
        rule.onNodeWithText("Discard").performClick()
        rule.onNodeWithText("Discard this ride?").assertIsDisplayed()
        assertEquals("nothing is deleted by the first tap", 0, discarded)
        rule.onNodeWithText("Keep it").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Discard this ride?").assertDoesNotExist()
        assertEquals(0, discarded)
    }

    @Test
    fun confirmingTheDiscardCallsTheAction() {
        var discarded = 0
        val actions = ReadyActions({}, {}, {}, {}, onRecoveryDiscard = { discarded++ })
        rule.setContent(ready(RecoveryState.Found(interrupted, 0), actions = actions))
        rule.onNodeWithText("Discard").performClick()
        // The notice's own button and the sheet's: the sheet comes later in the tree.
        rule.onAllNodesWithText("Discard")[1].performClick()
        assertEquals(1, discarded)
        rule.waitForIdle()
        rule.onNodeWithText("Discard this ride?").assertDoesNotExist()
    }

    @Test
    fun whileTheRideIsStoredTheNoticeHasNoButtons() {
        rule.setContent(ready(RecoveryState.Saving(interrupted, 0)))
        rule.onNodeWithText("Saving the ride…").assertIsDisplayed()
        rule.onNodeWithText("Save").assertDoesNotExist()
        rule.onNodeWithText("Discard").assertDoesNotExist()
    }

    @Test
    fun aFailedSaveOffersRetryAndDiscard() {
        var saved = 0
        rule.setContent(
            ready(
                RecoveryState.Failed(interrupted, 0, retryable = true),
                actions = ReadyActions({}, {}, {}, {}, onRecoverySave = { saved++ }),
            ),
        )
        rule.onNodeWithText("The ride could not be saved").assertIsDisplayed()
        rule.onNodeWithText("Free up some space on the tablet and try again.").assertIsDisplayed()
        rule.onNodeWithText("Discard").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, saved)
    }

    @Test
    fun aDamagedRideCanOnlyBeDiscarded() {
        rule.setContent(ready(RecoveryState.Failed(interrupted, 0, retryable = false)))
        rule.onNodeWithText("Retry").assertDoesNotExist()
        rule.onNodeWithText("Discard").assertIsDisplayed()
    }

    // ---- Last ride -------------------------------------------------------------------------------------------------

    @Test
    fun noLastRideRowWithoutARide() {
        rule.setContent(ready())
        rule.onNodeWithText("Last ride").assertDoesNotExist()
    }

    @Test
    fun theLastRideRowNamesTheRideAndShareAndSaveGoToTheFiles() {
        val files = RecordingFiles()
        rule.setContent(ready(lastRide = ride, files = files))
        rule.onNodeWithText("Last ride").assertIsDisplayed()
        rule.onNodeWithText("Sweet spot 3×10 · 1:05:00").assertIsDisplayed()
        rule.onNodeWithText("Share").performClick()
        rule.onNodeWithText("Save to folder").performClick()
        assertEquals(listOf("share ${ride.id}", "save ${ride.id}"), files.calls)
    }

    @Test
    fun theLastRideRowShowsWhatTheLastShareOrSaveSaid() {
        rule.setContent(ready(lastRide = ride, fileMessage = "Saved to the folder you chose."))
        rule.onNodeWithText("Saved to the folder you chose.").assertIsDisplayed()
    }

    // ---- Finished screen -------------------------------------------------------------------------------------------

    @Test
    fun whileSavingTheScreenIsBusyAndTheFileActionsWait() {
        rule.setContent(finished(SaveState.Saving))
        rule.onNodeWithText("Ride finished").assertIsDisplayed()
        rule.onNodeWithText("Saving the ride…").assertIsDisplayed()
        rule.onNodeWithText("Share FIT file").assertIsNotEnabled()
        rule.onNodeWithText("Save to folder").assertIsNotEnabled()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun onceSavedTheScreenNamesTheFileAndOffersShareAndSave() {
        val files = RecordingFiles()
        rule.setContent(finished(SaveState.Saved(ride), files))
        rule.onNodeWithText("Ride saved").assertIsDisplayed()
        rule.onNodeWithText("Saved on this tablet · 2026-11-18 10-00 Sweet spot 3×10.fit · 312 kB").assertIsDisplayed()
        rule.onNodeWithText("Share FIT file").performClick()
        rule.onNodeWithText("Save to folder").performClick()
        assertEquals(listOf("share ${ride.id}", "save ${ride.id}"), files.calls)
        assertEquals("2026-11-18 10-00 Sweet spot 3×10.fit", fileName(ride))
    }

    @Test
    fun doneWhenSavedLeavesAtOnce() {
        var done = 0
        rule.setContent(finished(SaveState.Saved(ride), onDone = { done++ }))
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, done)
        rule.onNodeWithText("Leave without saving this ride?").assertDoesNotExist()
    }

    @Test
    fun aFailedSaveSaysSoAndOffersRetry() {
        var retried = 0
        val message = "The ride could not be saved. Free up some space on the tablet and try again."
        rule.setContent(finished(SaveState.Failed(message, retryable = true), onRetry = { retried++ }))
        rule.onNodeWithText("Ride finished").assertIsDisplayed()
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.onNodeWithText("Share FIT file").assertDoesNotExist()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun aFailureThatCannotBeRetriedOffersNoRetry() {
        rule.setContent(finished(SaveState.Failed("This ride could not be saved as a FIT file.", retryable = false)))
        rule.onNodeWithText("Retry").assertDoesNotExist()
        rule.onNodeWithText("This ride could not be saved as a FIT file.").assertIsDisplayed()
    }

    @Test
    fun leavingAnUnsavedRideAsksFirst() {
        var done = 0
        rule.setContent(finished(SaveState.Failed("Not saved.", retryable = true), onDone = { done++ }))
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Leave without saving this ride?").assertIsDisplayed()
        assertEquals(0, done)
        rule.onNodeWithText("Stay").performClick()
        rule.waitForIdle()
        assertEquals(0, done)
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Leave").performClick()
        assertEquals(1, done)
    }

    @Test
    fun theFileMessageShowsUnderTheStatus() {
        rule.setContent(
            finished(SaveState.Saved(ride), fileMessage = "The file could not be saved. Try another folder."),
        )
        rule.onNodeWithText("The file could not be saved. Try another folder.").assertIsDisplayed()
    }

    @Test
    fun fileSizesAreWrittenForPeople() {
        assertEquals("0 B", FinishedFormat.fileSize(0))
        assertEquals("999 B", FinishedFormat.fileSize(999))
        assertEquals("1 kB", FinishedFormat.fileSize(1_000))
        assertEquals("312 kB", FinishedFormat.fileSize(312_480))
        assertEquals("1.4 MB", FinishedFormat.fileSize(1_400_000))
    }

    // ---- Riding screen ---------------------------------------------------------------------------------------------

    private fun riding(logFailed: Boolean): @Composable () -> Unit =
        {
            val snapshot: RideSnapshot = TestData.riding()
            OverdriveTheme {
                RidingScreen(
                    TestData.ui(snapshot, RideChoice.Planned(TestData.sweetSpot)).copy(logFailed = logFailed),
                    TestData.devicesOk,
                    topBar,
                    RideActions({ null }, { snapshot }),
                    NoMedia,
                )
            }
        }

    @Test
    fun aRideThatIsNotBeingSavedGetsOneNoticeWithoutTheCause() {
        rule.setContent(riding(logFailed = true))
        rule.onNodeWithText("This ride is not being saved").assertIsDisplayed()
        rule.onNodeWithText("Keep riding. Overdrive tries to save it again when you finish.").assertIsDisplayed()
        rule.onAllNodesWithText("This ride is not being saved").assertCountEquals(1)
    }

    @Test
    fun aHealthyRideShowsNoNotice() {
        rule.setContent(riding(logFailed = false))
        rule.onNodeWithText("This ride is not being saved").assertDoesNotExist()
        assertTrue(UiFormat.elapsed(1937) == "32:17")
    }

    // ---- Screenshots (build/shots/, never committed) ---------------------------------------------------------------

    @Test
    fun shotFinishedSaved() = shot("ride--finished-saved", finished(SaveState.Saved(ride)))

    @Test
    fun shotFinishedSaving() = shot("ride--finished-saving", finished(SaveState.Saving))

    @Test
    fun shotFinishedSavingFailed() =
        shot(
            "ride--finished-failed",
            finished(
                SaveState.Failed("The ride could not be saved. Free up some space on the tablet and try again.", true),
            ),
        )

    @Test
    fun shotFinishedLeaveConfirm() {
        val ride = TestData.finishedRide().copy(save = SaveState.Failed("Not saved.", true))
        shot(
            "ride--finished-leave",
            {
                OverdriveTheme {
                    FinishedScreen(ride, TestData.devicesOk, topBar, onDone = {}, forceLeaveConfirm = true)
                }
            },
        )
    }

    @Test
    fun shotReadyWithRecoveryNotice() = shot("ride--ready-recovery", ready(RecoveryState.Found(interrupted, more = 1)))

    @Test
    fun shotReadyWithRecoverySaving() = shot("ride--ready-recovery-saving", ready(RecoveryState.Saving(interrupted, 0)))

    @Test
    fun shotReadyWithFailedRecovery() =
        shot("ride--ready-recovery-failed", ready(RecoveryState.Failed(interrupted, 0, true)))

    @Test
    fun shotReadyDiscardConfirm() =
        shot("ride--ready-discard", ready(RecoveryState.Found(interrupted, 0), forceDiscardConfirm = true))

    @Test
    fun shotReadyWithLastRide() = shot("ride--ready-last-ride", ready(lastRide = ride))

    @Test
    fun shotReadyWithEverythingAtOnce() =
        shot(
            "ride--ready-recovery-and-last-ride",
            ready(RecoveryState.Found(interrupted, 0), lastRide = ride, fileMessage = "Saved to the folder you chose."),
        )

    @Test
    fun shotRidingNotSaved() = shot("ride--riding-not-saved", riding(logFailed = true))
}
