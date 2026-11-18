package app.overdrive.companion.model

import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Every date, time and problem sentence of the companion (design/companion.md §3.1, §7.5), as plain functions. */
class WordsTest {
    private val words = TestClock.words

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    @Test
    fun `updated says the time today, yesterday with the time, and the date otherwise`() {
        assertEquals("07:40", words.updated(millis("2026-11-18T07:40:00Z")))
        assertEquals("yesterday 21:10", words.updated(millis("2026-11-17T21:10:00Z")))
        assertEquals("16 Nov 21:10", words.updated(millis("2026-11-16T21:10:00Z")))
        assertEquals("Today 07:40", words.lastSync(millis("2026-11-18T07:40:00Z")))
        assertEquals("Yesterday 21:10", words.lastSync(millis("2026-11-17T21:10:00Z")))
    }

    @Test
    fun `the clock follows the phone's zone, not UTC`() {
        val berlin = Words(Clock({ TestClock.now }, java.time.ZoneId.of("Europe/Berlin"), java.util.Locale.ENGLISH))
        assertEquals("08:40", berlin.updated(millis("2026-11-18T07:40:00Z")))
        assertEquals("19:05", berlin.clockTime("2026-11-18T18:05:00Z"))
    }

    @Test
    fun `days are written the way the design shows them`() {
        assertEquals("Wednesday 18 November", words.todayLine())
        assertEquals("Wed 18 Nov", words.day("2026-11-18"))
        assertEquals("18 Nov", words.dayShort("2026-11-18"))
        assertEquals("2 Oct", words.dayShort("2026-10-02"))
        assertEquals("Wed 18", words.dayOfMonth("2026-11-18"))
        assertEquals("November", words.month("2026-11-01"))
        assertEquals("November 2025", words.month("2025-11-01"))
        assertEquals("Nov", words.monthShort("2026-11-01"))
    }

    @Test
    fun `a ride's when line has the start and the end`() {
        assertEquals("Thu 12 Nov · 18:02–18:58", words.rideWhen("2026-11-12T18:02:00Z", "2026-11-12T18:58:00Z"))
        assertEquals("Thu 12 Nov · 18:02", words.rideWhen("2026-11-12T18:02:00Z", null))
        assertEquals("18:58", words.clockTimeAfter("2026-11-12T18:02:00Z", 56 * 60))
        assertNull(words.clockTime("not an instant"))
    }

    @Test
    fun `numbers use the true minus, an explicit plus and the dash for nothing`() {
        assertEquals("−3", words.change(-3))
        assertEquals("+4", words.change(4))
        assertEquals("0", words.change(0))
        assertEquals("74", words.load(73.6))
        assertEquals("—", words.load(null))
        assertEquals("1:05:12", words.duration(3912))
        assertEquals("42:10", words.duration(2530))
    }

    @Test
    fun `power curve durations`() {
        assertEquals("5 s", words.curveDuration(5))
        assertEquals("1 min", words.curveDuration(60))
        assertEquals("1:30", words.curveDuration(90))
        assertEquals("20 min", words.curveDuration(1200))
        assertEquals("1 h", words.curveDuration(3600))
        assertEquals("90 min", words.curveDuration(5400))
        assertEquals("2 h", words.curveDuration(7200))
    }

    // region problems

    private fun problem(
        kind: ProblemKind,
        hint: String? = null,
        status: Int? = null,
        plain: String = "plain",
    ) = ServiceProblem(kind, plain, hint, status)

    @Test
    fun `the status line says Offline without a network and Out of date otherwise`() {
        val offline = ProblemWords.of(problem(ProblemKind.NO_NETWORK), "overdrive.example.net", "06:10")
        assertEquals("Offline · updated 06:10", offline.title)
        assertEquals("No network connection. Pull down to try again.", offline.cause)
        assertNull(offline.action)
        val stale = ProblemWords.of(problem(ProblemKind.TOKEN_REJECTED, status = 401), "x", "yesterday 21:10")
        assertEquals("Out of date · updated yesterday 21:10", stale.title)
        assertEquals("The service did not accept the token. Check it in Settings.", stale.cause)
        assertEquals(ProblemAction.Settings, stale.action)
    }

    @Test
    fun `with nothing cached the title is the error block's heading`() {
        assertEquals("Could not reach your service", ProblemWords.of(problem(ProblemKind.NO_ANSWER), "x", null).title)
    }

    @Test
    fun `every kind has a sentence, with the host in the DNS one`() {
        val host = "overdrive.example.net"
        assertEquals(
            "The phone could not find overdrive.example.net. Check the address in Settings, or try again on another network.",
            ProblemWords.causeOf(problem(ProblemKind.NAME_NOT_FOUND), host),
        )
        assertEquals(
            "The system blocks this app's network access. Allow it in the phone's app settings.",
            ProblemWords.causeOf(problem(ProblemKind.NETWORK_BLOCKED), host),
        )
        assertEquals(
            "The service did not answer. It may be restarting; try again in a minute.",
            ProblemWords.causeOf(problem(ProblemKind.NO_ANSWER), host),
        )
        assertEquals(
            "The service had a problem (500). Try again later.",
            ProblemWords.causeOf(problem(ProblemKind.SERVER_ERROR, status = 500), host),
        )
        assertEquals(
            "Service unreachable.",
            ProblemWords.causeOf(problem(ProblemKind.SECURE_CONNECTION, plain = "Service unreachable"), host),
        )
        assertEquals(
            ProblemAction.PhoneSettings,
            ProblemWords.of(problem(ProblemKind.NETWORK_BLOCKED), host, null).action,
        )
        assertEquals(ProblemAction.Settings, ProblemWords.of(problem(ProblemKind.NAME_NOT_FOUND), host, null).action)
    }

    @Test
    fun `the hint names the transport's class and says DNS for a name that did not resolve`() {
        assertEquals(
            "UnknownHostException · DNS",
            ProblemWords.hintOf(problem(ProblemKind.NAME_NOT_FOUND, "UnknownHostException")),
        )
        assertEquals("DNS", ProblemWords.hintOf(problem(ProblemKind.NAME_NOT_FOUND, null)))
        assertEquals(
            "SocketException · EACCES",
            ProblemWords.hintOf(problem(ProblemKind.NETWORK_BLOCKED, "SocketException · EACCES")),
        )
        assertNull(ProblemWords.hintOf(problem(ProblemKind.SERVER_ERROR)))
    }
}
