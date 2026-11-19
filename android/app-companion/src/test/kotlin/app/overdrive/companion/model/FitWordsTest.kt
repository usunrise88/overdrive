package app.overdrive.companion.model

import app.overdrive.companion.RepeatSamples
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.metrics.RidesResponse
import app.overdrive.core.outdoor.ActivityImportReason
import app.overdrive.core.outdoor.FitOutcome
import app.overdrive.core.outdoor.FitRefusal
import app.overdrive.core.outdoor.FitStatus
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.RidesList
import app.overdrive.core.remote.RidesView
import app.overdrive.core.remote.ServiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sentence of the import screen and of Settings' row for the files that wait (spec §11.8, design/companion.md §4.11): what came of a
 * file, in plain words, for each thing that can come of it; the outdoor ride's row in the list and its page.
 */
class FitWordsTest {
    private val words = TestClock.words
    private val host = "overdrive.example.net"

    private fun screen(outcome: FitOutcome) = FitWords.importScreen(FitShown.Done(outcome), words, host)

    // region the import screen

    @Test
    fun `importing is a spinner and one word, with no button`() {
        val ui = FitWords.importScreen(FitShown.Importing, words, host)
        assertEquals(ImportMark.Working, ui.mark)
        assertEquals("Importing…", ui.heading)
        assertNull(ui.text)
        assertNull(ui.action)
    }

    @Test
    fun `an imported ride is told with its date, duration, distance, average heart rate and load`() {
        val ui = screen(FitOutcome.Imported(RepeatSamples.FIT_ID, RepeatSamples.summary()))
        assertEquals(ImportMark.Done, ui.mark)
        assertEquals("Imported", ui.heading)
        assertEquals("Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm, load 74 (approximate).", ui.text)
        assertEquals(ImportAction.Rides, ui.action)
    }

    @Test
    fun `a ride with no load yet says nothing of one, and an exact load is not called approximate`() {
        assertEquals(
            "Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm",
            FitWords.facts(RepeatSamples.summary(load = null), words),
        )
        assertEquals(
            "Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm, load 74",
            FitWords.facts(RepeatSamples.summary(approximate = false), words),
        )
        val bare =
            RepeatSamples.summary().copy(
                distanceMeters = null,
                averageHeartRate = null,
                load = null,
                loadApproximate = false,
            )
        assertEquals("Sun 15 Nov, 1:12:04", FitWords.facts(bare, words))
    }

    @Test
    fun `a ride the service had is already imported, for the reason it knew`() {
        for ((reason, text) in listOf(
            ActivityImportReason.SAME_FILE to "You shared this file before, so it counts once.",
            ActivityImportReason.SAME_START_AND_DURATION to
                "A ride with the same start and length is already in your history.",
            ActivityImportReason.OVERLAP to "It overlaps a ride already in your history, so it counts once.",
        )) {
            val ui = screen(FitOutcome.Duplicate(RepeatSamples.FIT_ID, reason, RepeatSamples.summary(load = null)))
            assertEquals("Already imported", ui.heading)
            assertEquals(text, ui.text)
            assertEquals("Sun 15 Nov, 1:12:04, 31.4 km, average heart rate 142 bpm", ui.detail)
            assertEquals(ImportMark.Done, ui.mark)
        }
    }

    @Test
    fun `each reason a file is refused is a plain cause that says what to do`() {
        val expected =
            mapOf(
                FitRefusal.EMPTY_FILE to "The file is empty.",
                FitRefusal.NOT_A_FIT_FILE to
                    "This is not a valid FIT file. In OnelapFit, export the ride as a FIT file and share that.",
                FitRefusal.ARCHIVE to "This is a zip archive, not a FIT file. Open it and share the .fit file inside.",
                FitRefusal.TOO_LARGE to "The file is larger than 16 MB, which is more than your service takes.",
                FitRefusal.NOT_CYCLING to "This FIT file is not a cycling ride, so it was not imported.",
                FitRefusal.NO_RIDE to "This FIT file has no ride in it.",
                FitRefusal.WRONG_TYPE to
                    "The service did not accept this kind of file. Update the service and this app, then share it again.",
                FitRefusal.QUEUE_FULL to
                    "20 files are already waiting for your service. Connect to it, then share this one again.",
                FitRefusal.DAMAGED to
                    "The saved copy of this file was damaged, so it was set aside. Share the file again.",
                FitRefusal.SERVICE_REFUSED to "The service refused the file.",
            )
        assertEquals("every refusal has words", FitRefusal.entries.toSet(), expected.keys)
        for ((refusal, text) in expected) {
            val ui = screen(FitOutcome.Refused(refusal))
            assertEquals(refusal.name, "Could not import", ui.heading)
            assertEquals(refusal.name, text, ui.text)
            assertEquals(ImportMark.Alert, ui.mark)
            assertNull(ui.action)
        }
        assertEquals(
            "The service refused the file (400).",
            screen(FitOutcome.Refused(FitRefusal.SERVICE_REFUSED, 400)).text,
        )
    }

    @Test
    fun `a service that cannot be reached keeps the file and says why, in the design's sentences`() {
        val saved = "The file is saved on this phone and goes to your service when it can be reached."
        val offline = screen(FitOutcome.Waiting(ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")))
        assertEquals("Saved for later", offline.heading)
        assertEquals("There is no network connection. $saved", offline.text)
        assertNull(offline.action)
        val dns =
            screen(
                FitOutcome.Waiting(
                    ServiceProblem(
                        ProblemKind.NAME_NOT_FOUND,
                        "Cannot find the service address (DNS)",
                        "UnknownHostException",
                    ),
                ),
            )
        assertEquals(
            "The phone could not find $host. Check the address in Settings, or try again on another network. $saved",
            dns.text,
        )
        assertEquals("(UnknownHostException · DNS)", dns.detail)
        assertEquals(ImportAction.Settings, dns.action)
        val down =
            screen(FitOutcome.Waiting(ServiceProblem(ProblemKind.SERVER_ERROR, "Service error (503)", status = 503)))
        assertEquals("The service had a problem (503). Try again later. $saved", down.text)
        val silent =
            screen(FitOutcome.Waiting(ServiceProblem(ProblemKind.NO_ANSWER, "The service refused the connection")))
        assertEquals("The service did not answer. It may be restarting; try again in a minute. $saved", silent.text)
        for (ui in listOf(offline, dns, down, silent)) assertEquals(ImportMark.Alert, ui.mark)
    }

    @Test
    fun `a refused token says so, offers Settings, and says the file is kept`() {
        val ui = screen(FitOutcome.Waiting(ServiceProblem.TokenRejected))
        assertEquals("Saved, not sent", ui.heading)
        assertEquals(
            "The service did not accept the token. Check it in Settings. The file is saved on this phone and goes to your service when it can be reached.",
            ui.text,
        )
        assertEquals(ImportAction.Settings, ui.action)
    }

    @Test
    fun `with no service set up the screen says to connect, and the file is kept`() {
        val ui = screen(FitOutcome.Waiting(null))
        assertEquals("Saved for later", ui.heading)
        assertEquals(
            "Overdrive is not connected to your service yet. Connect it in the app; the file is saved and goes then.",
            ui.text,
        )
        assertEquals(ImportAction.Settings, ui.action)
    }

    @Test
    fun `a file the phone could not keep, a file it could not read and an empty screen are said too`() {
        assertEquals("Could not save the file", screen(FitOutcome.NotKept).heading)
        assertTrue(screen(FitOutcome.NotKept).text.orEmpty().contains("Nothing was sent"))
        val unreadable = FitWords.importScreen(FitShown.Unreadable, words, host)
        assertEquals("Could not read the file", unreadable.heading)
        assertEquals(ImportMark.Alert, unreadable.mark)
        val idle = FitWords.importScreen(FitShown.Idle, words, host)
        assertEquals("Nothing to import", idle.heading)
        assertEquals("Share a .fit file from OnelapFit to Overdrive.", idle.text)
    }

    // region Settings

    @Test
    fun `Settings counts the files that wait`() {
        assertEquals("No files waiting", FitWords.settingsValue(FitStatus()))
        assertEquals("1 file waiting", FitWords.settingsValue(FitStatus(waiting = 1)))
        assertEquals("3 files waiting", FitWords.settingsValue(FitStatus(waiting = 3)))
    }

    @Test
    fun `the note says what to do, why files wait, and what was refused`() {
        assertEquals(
            "Share a .fit file from OnelapFit to Overdrive to add an outdoor ride.",
            FitWords.settingsNote(FitStatus(), host),
        )
        assertEquals(
            "It goes to your service when it can be reached.",
            FitWords.settingsNote(FitStatus(waiting = 1), host),
        )
        assertEquals(
            "They go to your service when it can be reached.",
            FitWords.settingsNote(FitStatus(waiting = 2), host),
        )
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        assertEquals(
            "There is no network connection. They go to your service when it can be reached.",
            FitWords.settingsNote(FitStatus(waiting = 2, problem = offline), host),
        )
        assertEquals(
            "1 file was not imported: not a cycling ride.",
            FitWords.settingsNote(FitStatus(parked = listOf(FitRefusal.NOT_CYCLING)), host),
        )
        assertEquals(
            "3 files were not imported: not a cycling ride; not a valid FIT file.",
            FitWords.settingsNote(
                FitStatus(parked = listOf(FitRefusal.NOT_CYCLING, FitRefusal.NOT_CYCLING, FitRefusal.NOT_A_FIT_FILE)),
                host,
            ),
        )
        assertEquals(
            "It goes to your service when it can be reached. 1 file was not imported: no ride in it.",
            FitWords.settingsNote(FitStatus(waiting = 1, parked = listOf(FitRefusal.NO_RIDE)), host),
        )
    }

    // region the list and the page of an outdoor ride

    @Test
    fun `an outdoor ride is a row of its own kind, by icon and word and not by colour`() {
        val items = listOf(RepeatSamples.outdoorItem())
        val page = RidesResponse("2026-11-18T21:10:00Z", false, null, 50, false, null, items)
        val rows =
            RidesUi
                .content(
                    RidesView(fresh(page), RidesList(items)),
                    words,
                    host,
                ).entries
                .filterIsInstance<RidesEntry.Row>()
                .map {
                    it.row
                }
        val row = rows.single()
        assertEquals("Outdoor ride", row.title)
        assertEquals("Outdoor · Sun 15 · 1:12:04", row.subtitle)
        assertTrue(row.outdoor)
        assertFalse(row.watch)
        assertEquals("142 bpm", row.caption)
        assertEquals("74", row.load)
    }

    @Test
    fun `an outdoor ride's page says where it came from`() {
        val ui = ActivityWords.of(RepeatSamples.outdoorItem(), words)
        assertEquals("Outdoor ride", ui.name)
        assertEquals("Sun 15 Nov · from a bike computer file", ui.whenLine)
        assertEquals(
            "Sun 15 Nov · from the watch",
            ActivityWords.of(RepeatSamples.outdoorItem().copy(sport = "VirtualReality"), words).whenLine,
        )
    }
}
