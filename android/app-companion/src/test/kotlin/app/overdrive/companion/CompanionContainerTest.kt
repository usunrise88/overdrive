package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.profile.ProfileProblem
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProblemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.ServerSocket

/**
 * The companion's real container (ADR 0024) against a local stand-in of the service: the first run, what is fetched and kept, the
 * offline restart, the answers to an FTP suggestion, the token's secrecy. Real threads, real files, real sockets on loopback.
 *
 * **Every wait is for a condition**, and where a test then closes the container or reads a file it waits for the **disk** as well:
 * the repositories write the cache before they publish, so a value seen in memory is already on disk (the race that cost a release
 * was a cache written on a coroutine that `close()` cancels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun diagnostics(c: CompanionContainer): () -> String =
        {
            "service requests=${service.received.map { "${it.method} ${it.path}" }}\n" +
                "metrics=${c.metricsFor(MetricsRange.DEFAULT).state.value}\nworkouts=${c.workouts.state.value}\n" +
                "profile=${c.profile.state.value}\nanswers=${c.ftpAnswers.state.value}\n"
        }

    // region first run

    @Test
    fun `the first run connects with one profile request and stores the address and token only then`() {
        val c = world.container()
        await("the settings are read") { c.settings.value != null }
        assertFalse(c.settings.value!!.isConfigured)
        val result = blocking { c.connect(service.baseUrl, service.token) }
        assertEquals(ConnectResult.Connected, result)
        assertEquals(1, service.requests("GET", "/v1/profile").size)
        await("the settings are stored") { c.settings.value?.isConfigured == true }
        assertEquals(service.baseUrl, c.settings.value!!.serviceUrl)
        await("the first figures arrive", diagnostics = diagnostics(c)) {
            c.metricsFor(MetricsRange.DEFAULT).state.value.value !=
                null
        }
        await("today's workout arrives") { c.workouts.state.value.value != null }
        await("the profile arrives") { c.profile.state.value.profile != null }
    }

    @Test
    fun `a wrong token stays on the connect step with the words for the token and stores nothing`() {
        val c = world.container()
        await("the settings are read") { c.settings.value != null }
        val result = blocking { c.connect(service.baseUrl, "tok-wrong") }
        assertEquals(ConnectResult.TokenProblem("The service did not accept this token (401)."), result)
        assertFalse(c.settings.value!!.isConfigured)
        assertEquals("", c.settings.value!!.serviceUrl)
    }

    @Test
    fun `an address nothing listens at says so under the address and stores nothing`() {
        val c = world.container()
        await("the settings are read") { c.settings.value != null }
        val closed = ServerSocket(0).use { it.localPort }
        val result = blocking { c.connect("http://127.0.0.1:$closed", "tok") } as ConnectResult.AddressProblem
        assertEquals("The service did not answer. It may be restarting; try again in a minute.", result.reason)
        assertFalse(c.settings.value!!.isConfigured)
        assertEquals(
            "a mistake in the address is said without asking",
            ConnectResult.AddressProblem("The address needs a host, like example.com."),
            blocking { c.connect("https://", "tok") },
        )
    }

    // region fetching and keeping

    @Test
    fun `every resource is fetched, kept on disk, and shown from there after a restart with the service down`() {
        val first = world.container("shared")
        first.configure()
        await("the figures, the workout and the profile", diagnostics = diagnostics(first)) {
            first.metricsFor(MetricsRange.DEFAULT).state.value.value != null &&
                first.workouts.state.value.value != null && first.profile.state.value.profile != null
        }
        first.screenOpened(RefreshTarget.Rides)
        await("the rides") {
            first.rides.view.value.list.items
                .isNotEmpty()
        }
        first.screenOpened(RefreshTarget.RideDetail("ride-1112"))
        await("the ride") { first.rideDetail("ride-1112").state.value.value != null }
        val files =
            world.cache
                .walkTopDown()
                .filter { it.isFile }
                .map { it.name }
                .toSet()
        assertTrue(
            files.toString(),
            files.containsAll(setOf("metrics-26w", "workouts", "rides", "ride-1112", "profile")),
        )
        world.stop(first)

        service.down = true
        val restarted = world.container("shared")
        await("the cache is shown with no service") {
            restarted.metricsFor(MetricsRange.DEFAULT).state.value.value !=
                null
        }
        await("the rides come from the disk") { restarted.rides.view.value.list.items.size == Samples.rideItems().size }
        restarted.screenOpened(RefreshTarget.RideDetail("ride-1112"))
        await("the ride detail comes from the disk") { restarted.rideDetail("ride-1112").state.value.value != null }
        await("the failed refresh is said next to the cache", diagnostics = diagnostics(restarted)) {
            restarted
                .metricsFor(MetricsRange.DEFAULT)
                .state.value.problem
                ?.kind == ProblemKind.SERVER_ERROR
        }
        val state = restarted.metricsFor(MetricsRange.DEFAULT).state.value
        assertNotNull("the answer stays under the problem", state.value)
        assertTrue(state.isStale)
        assertEquals(Samples.TODAY, state.value!!.to)
    }

    @Test
    fun `opening a screen twice in a minute asks once, pulling down asks again`() {
        val c = world.container()
        c.configure()
        await("the start refresh") { c.metricsFor(MetricsRange.DEFAULT).state.value.value != null }
        val before = service.requests("GET", "/v1/rides").size
        c.screenOpened(RefreshTarget.Rides)
        await("the first open") { service.requests("GET", "/v1/rides").size == before + 1 }
        await("it is done") {
            c.rides.newest.state.value
                .let { it.value != null && !it.refreshing }
        }
        c.screenOpened(RefreshTarget.Rides)
        c.refresh(RefreshTarget.Rides)
        await("the pull down") { service.requests("GET", "/v1/rides").size == before + 2 }
    }

    @Test
    fun `each range asks for its own days and the choice is remembered`() {
        val c = world.container()
        c.configure()
        c.setFitnessRange(MetricsRange.SIX_WEEKS)
        await("the range is stored") { c.settings.value?.fitnessRange == MetricsRange.SIX_WEEKS }
        c.screenOpened(RefreshTarget.Fitness)
        await("the six weeks arrive", diagnostics = diagnostics(c)) {
            c.metricsFor(MetricsRange.SIX_WEEKS).state.value.value !=
                null
        }
        val query = service.requests("GET", "/v1/metrics").map { it.query }.last { it!!.startsWith("from=2026-10-12") }
        assertEquals("from=2026-10-12&to=2026-11-18", query)
    }

    @Test
    fun `the rides list pages by cursor, and a restart keeps the newest page`() {
        service.rides =
            (0 until 120).map { i ->
                Samples.ride(
                    "ride-$i",
                    app.overdrive.core.plan.PlanCalendar
                        .addDays("2026-11-18", -i),
                    "Ride $i",
                    3600,
                    50.0,
                    180.0,
                )
            }
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Rides)
        await("the first page") { c.rides.view.value.list.items.size == 50 }
        assertTrue(c.rides.view.value.list.hasMore)
        c.loadMoreRides()
        await("the second page") { c.rides.view.value.list.items.size == 100 }
        c.loadMoreRides()
        await("the last page") { c.rides.view.value.list.items.size == 120 }
        assertFalse(c.rides.view.value.list.hasMore)
        assertEquals(
            120,
            c.rides.view.value.list.items
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `a rejected token is said, asked once, and recovers when the rider asks after fixing it`() {
        service.rejectToken = true
        val c = world.container()
        c.configure()
        await("the token is refused", diagnostics = diagnostics(c)) {
            c
                .metricsFor(MetricsRange.DEFAULT)
                .state.value.problem
                ?.kind == ProblemKind.TOKEN_REJECTED
        }
        // Everything the start asked for is in flight at once: wait until each has its answer before counting what the service saw.
        await("every resource of the start has been refused", diagnostics = diagnostics(c)) {
            val refused = ProblemKind.TOKEN_REJECTED
            c.workouts.state.value.problem?.kind == refused && c.recovery.state.value.problem?.kind == refused &&
                c.insights.newest.state.value.problem?.kind == refused &&
                c.profile.state.value.problem == ProfileProblem.TokenRejected
        }
        val asked = service.received.size
        c.screenOpened(RefreshTarget.Rides)
        await("the refused token is said for the rides too, without asking") {
            c.rides.newest.state.value.problem
                ?.kind == ProblemKind.TOKEN_REJECTED
        }
        assertEquals("a token the service refused is not sent again", asked, service.received.size)
        service.rejectToken = false
        c.refresh(RefreshTarget.Today)
        await("the rider's pull down gets through", diagnostics = diagnostics(c)) {
            c.metricsFor(MetricsRange.DEFAULT).state.value.value != null
        }
        assertNull(c.metricsFor(MetricsRange.DEFAULT).state.value.problem)
        assertTrue(service.received.size > asked)
    }

    @Test
    fun `a changed address is asked once it has settled, not once per letter`() {
        val other = LocalService()
        try {
            val c = world.container(settleMillis = 200)
            c.configure()
            await("the first service answers") { c.metricsFor(MetricsRange.DEFAULT).state.value.value != null }
            c.updateService(url = other.baseUrl)
            c.updateService(token = other.token)
            await(
                "the new service is asked",
                diagnostics = diagnostics(c),
            ) { other.requests("GET", "/v1/metrics").isNotEmpty() }
            assertEquals(other.baseUrl, c.settings.value!!.serviceUrl)
            // The first service's answer is not shown for the new address.
            await("the new answer") { c.metricsFor(MetricsRange.DEFAULT).state.value.value != null }
        } finally {
            other.close()
        }
    }

    // region the FTP suggestion

    @Test
    fun `Use puts the FTP on the profile at once and the service has it`() {
        val c = world.container()
        c.configure()
        await("the profile is in force", diagnostics = diagnostics(c)) {
            c.profile.state.value.profile
                ?.ftp == 250
        }
        c.useFtp(256)
        assertEquals(
            256,
            c.profile.state.value.profile
                ?.ftp,
        )
        assertEquals(256, c.justUsed.value)
        await("the service has the FTP", diagnostics = diagnostics(c)) { service.profile?.ftp == 256 }
        await("nothing is waiting") { c.profile.state.value.pending == null }
        c.screenOpened(RefreshTarget.Today)
        await("the line goes at the next visit") { c.justUsed.value == null }
    }

    @Test
    fun `Use while the service is down is kept as not sent and goes when Sync now can`() {
        val c = world.container()
        c.configure()
        await("the profile is in force") {
            c.profile.state.value.profile
                ?.ftp == 250
        }
        service.down = true
        c.useFtp(256)
        await("the edit is waiting", diagnostics = diagnostics(c)) { c.profile.state.value.pending != null }
        await("the first send has failed and is over", diagnostics = diagnostics(c)) {
            c.profile.state.value.let { it.problem != null && !it.refreshing }
        }
        await("the overview says one waits") { c.syncOverview.value.waiting == 1 }
        assertEquals(250, service.profile?.ftp)
        service.down = false
        c.syncNow()
        await("the service has it", diagnostics = diagnostics(c)) { service.profile?.ftp == 256 }
        await("the overview is clear", diagnostics = diagnostics(c)) {
            c.syncOverview.value.waiting == 0 &&
                !c.syncOverview.value.syncing
        }
    }

    @Test
    fun `Keep tells the service to dismiss the suggestion, and the metrics stop offering it`() {
        val c = world.container()
        c.configure()
        await("the suggestion is offered") {
            c
                .metricsFor(MetricsRange.DEFAULT)
                .state.value.value
                ?.ftp
                ?.suggestion !=
                null
        }
        c.keepFtp(7)
        await("the service was told", diagnostics = diagnostics(c)) { service.dismissed == listOf(7) }
        await("nothing waits") { c.ftpAnswers.state.value.waiting == 0 }
        assertTrue(7 in c.ftpAnswers.state.value.dismissed)
        c.refresh(RefreshTarget.Today)
        await("the next answer has no suggestion") {
            c
                .metricsFor(MetricsRange.DEFAULT)
                .state.value.value
                ?.ftp
                ?.suggestion ==
                null
        }
    }

    @Test
    fun `Keep offline is queued on disk, survives a restart and is sent when the service is back`() {
        val c = world.container("shared")
        c.configure()
        await("the suggestion is offered") {
            c
                .metricsFor(MetricsRange.DEFAULT)
                .state.value.value
                ?.ftp
                ?.suggestion !=
                null
        }
        service.down = true
        c.keepFtp(7)
        await("the answer waits", diagnostics = diagnostics(c)) { c.ftpAnswers.state.value.waiting == 1 }
        // The answer is written down before it is sent: the file is there as soon as the state says so.
        assertTrue(world.diskText(), "7 pending" in world.diskText())
        world.stop(c)

        service.down = false
        val restarted = world.container("shared")
        await("the answer is remembered", diagnostics = diagnostics(restarted)) {
            restarted.ftpAnswers.state.value.dismissed ==
                setOf(7)
        }
        await("and sent at the start", diagnostics = diagnostics(restarted)) { service.dismissed == listOf(7) }
    }

    // region secrecy

    @Test
    fun `the token is in no file, no state, no log line and no printed settings`() {
        val c = world.container()
        c.configure()
        await("everything is fetched", diagnostics = diagnostics(c)) {
            c.metricsFor(MetricsRange.DEFAULT).state.value.value != null && c.profile.state.value.profile != null
        }
        c.screenOpened(RefreshTarget.Rides)
        await("the rides") {
            c.rides.view.value.list.items
                .isNotEmpty()
        }
        c.keepFtp(7)
        await("the answer is sent") { service.dismissed == listOf(7) }
        val token = service.token
        assertFalse("a cache holds the token", token in world.diskText())
        assertFalse(c.settings.value.toString().contains(token))
        assertFalse(c.syncOverview.value.toString().contains(token))
        assertFalse(
            c
                .metricsFor(MetricsRange.DEFAULT)
                .state.value
                .toString()
                .contains(token),
        )
        assertEquals("••••", c.settings.value!!.token.toString())
        assertFalse(ShadowLog.getLogs().any { token in it.msg.orEmpty() })
        // The settings file itself holds it (it has to, to send it), and nothing else of the app's data does.
        assertTrue(java.io.File(context.filesDir, "datastore").walkTopDown().any { it.isFile })
    }

    @Test
    fun `Sync now asks for everything and says when it is done`() {
        val c = world.container()
        c.configure()
        await("the start refresh") { c.metricsFor(MetricsRange.DEFAULT).state.value.value != null }
        c.syncNow()
        await("a request for every range", diagnostics = diagnostics(c)) {
            c.metricsFor(MetricsRange.ONE_YEAR).state.value.value != null &&
                c.metricsFor(MetricsRange.SIX_WEEKS).state.value.value != null &&
                c.rides.view.value.list.items
                    .isNotEmpty()
        }
        await("it is over") { !c.syncOverview.value.syncing }
        assertNotNull(c.syncOverview.value.lastFetchedMillis)
        assertNull(c.syncOverview.value.problem)
    }
}
