package app.overdrive.ride.ui

import app.overdrive.core.sync.RefusedRide
import app.overdrive.core.sync.SyncReport
import app.overdrive.core.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The words of Settings, Sync: what each state of the sync says, and when the last upload was. */
class SyncUiTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 11, 18, 21, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(
        day: Int,
        hour: Int,
        minute: Int,
    ) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun ui(
        status: SyncStatus,
        report: SyncReport = SyncReport(),
    ) = SyncUi.of(status, report, now, zone, Locale.ENGLISH)

    @Test
    fun `everything sent says nothing, counts none and offers no send now`() {
        val sent = ui(SyncStatus.Idle(0), SyncReport(lastSuccessMillis = at(18, 18, 4)))
        assertEquals(SyncUi(waiting = 0, line = null, lastUpload = "Today 18:04", canSendNow = false), sent)
    }

    @Test
    fun `the last upload is a time today and a date and time on another day, or never`() {
        assertEquals("Today 18:04", ui(SyncStatus.Idle(0), SyncReport(at(18, 18, 4))).lastUpload)
        assertEquals("17 Nov 23:59", ui(SyncStatus.Idle(0), SyncReport(at(17, 23, 59))).lastUpload)
        assertEquals("1 Nov 07:05", ui(SyncStatus.Idle(0), SyncReport(at(1, 7, 5))).lastUpload)
        assertEquals("Never", ui(SyncStatus.Idle(0)).lastUpload)
    }

    @Test
    fun `waiting rides are counted in every state that has a queue and can be sent now unless sending`() {
        for (status in listOf(
            SyncStatus.Idle(3),
            SyncStatus.WaitingToRetry(3, 5_000, "x"),
            SyncStatus.NotConfigured(3),
            SyncStatus.TokenRejected(3),
        )) {
            val ui = ui(status)
            assertEquals("$status", 3, ui.waiting)
            assertTrue("$status", ui.canSendNow)
        }
        val sending = ui(SyncStatus.Syncing(3))
        assertEquals(3, sending.waiting)
        assertFalse("not while it is sending", sending.canSendNow)
        assertEquals(0, ui(SyncStatus.Stopped).waiting)
        assertEquals(0, ui(SyncStatus.OutboxUnavailable("IOException")).waiting)
    }

    @Test
    fun `not configured says what to add`() {
        assertEquals("Add the address and the token to send rides", ui(SyncStatus.NotConfigured(2)).line)
    }

    @Test
    fun `a rejected token says so and where to look`() {
        assertEquals(
            "The service did not accept the token. Check the token above",
            ui(SyncStatus.TokenRejected(1)).line,
        )
    }

    @Test
    fun `a failure that will be retried says why in the service's words`() {
        assertEquals(
            "Service unreachable. Trying again soon",
            ui(SyncStatus.WaitingToRetry(1, 5_000, "Service unreachable")).line,
        )
        assertEquals("Could not send. Trying again soon", ui(SyncStatus.WaitingToRetry(1, 5_000, null)).line)
    }

    @Test
    fun `a refused ride is named with the service's short message`() {
        val one =
            SyncReport(refused = listOf(RefusedRide("ride-1", "Ride ride-1 is already stored with different content")))
        assertEquals(
            "1 ride refused: Ride ride-1 is already stored with different content",
            ui(SyncStatus.Idle(1), one).line,
        )
        val two = SyncReport(refused = listOf(RefusedRide("a", "No"), RefusedRide("b", "Nope")))
        assertEquals("2 rides refused: No", ui(SyncStatus.Idle(2), two).line)
        assertEquals(1, ui(SyncStatus.Idle(1), one).waiting)
        assertTrue("send now retries a refused ride", ui(SyncStatus.Idle(1), one).canSendNow)
    }

    @Test
    fun `a problem that is happening now is said before a refusal that is not`() {
        val refused = SyncReport(refused = listOf(RefusedRide("a", "No")))
        assertEquals(
            "The service did not accept the token. Check the token above",
            ui(SyncStatus.TokenRejected(2), refused).line,
        )
        assertEquals("Sending…", ui(SyncStatus.Syncing(2), refused).line)
    }

    @Test
    fun `an unreadable outbox is said and nothing else`() {
        assertEquals("Could not read the list of rides to send", ui(SyncStatus.OutboxUnavailable("IOException")).line)
        assertNull(ui(SyncStatus.Stopped).line)
    }
}
