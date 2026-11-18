package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.FakeHealth.Companion.DAY
import app.overdrive.companion.FakeHealth.Companion.SENTINEL_BPM
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.settings.OnboardingStep
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.RestingHeartRateReading
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.WellnessRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * The watch data through the real container (ADR 0027): a fake Health Connect on one side, the stand-in service on loopback on the
 * other, real files, real sockets, real threads in between. The first read in windows, what the service ends up holding, a service
 * that is down and comes back, an answer that is lost, a batch the service refuses, a token it rejects, a restart in the middle, the
 * figures the screens read afterwards, and the secrecy of the token and of every figure.
 *
 * **Every wait is for a condition**; where a test then closes the container or reads a file it waits for the disk as well (the
 * outbox and the state are written before the status that says so is published).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service
    private val end = TestClock.now

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun diagnostics(c: CompanionContainer): () -> String =
        {
            "status=${c.healthStatus.value}\nservice=${service.wellnessBodies.size} bodies, ${service.wellnessRecords.size} records\n"
        }

    private fun allowedHealth(days: Int = 60): FakeHealth =
        FakeHealth().also {
            it.allow()
            FakeHealth.sixtyDays(it, end, days)
        }

    private fun outboxFiles(): List<File> =
        File(world.cache, "watch-outbox").listFiles { f -> f.name.endsWith(".batch") }.orEmpty().toList()

    private fun stateText(): String = File(world.cache, "watch-state").takeIf { it.isFile }?.readText().orEmpty()

    // region the first read

    @Test
    fun `the first read sends the last 60 days once, keeps its state on disk and asks for the figures again`() {
        val health = allowedHealth()
        val c = world.container(health = health)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("the first read is done and everything sent", diagnostics = diagnostics(c)) {
            c.healthStatus.value.firstReadDone && c.healthStatus.value.waitingBatches == 0 &&
                service.wellnessRecords.size == FakeHealth.idsOf().size
        }
        assertEquals(FakeHealth.idsOf(), service.wellnessRecords.keys.toSet())
        val status = c.healthStatus.value
        assertEquals(null, status.progress)
        assertEquals(service.wellnessRecords.size, status.lastSent?.total)
        assertEquals(60, status.lastSent?.byType?.get("restingHeartRate"))
        assertNotNull(status.lastReadMillis)

        // On disk before anyone asks: the token, no backfill left, nothing waiting.
        await("the state is on disk") {
            stateText().contains("\"changesToken\"") &&
                !stateText().contains("\"backfill\":{")
        }
        assertEquals(0, outboxFiles().size)

        // What the service computes from it is asked for again: the rating, the notes, the charts.
        await("the rating arrives", diagnostics = diagnostics(c)) { c.recovery.state.value.value != null }
        assertTrue(service.requests("GET", "/v1/recovery").isNotEmpty())
    }

    @Test
    fun `heart rate outside a night and a workout is never sent, and the token is in no request body`() {
        val health = allowedHealth(days = 6)
        val c = world.container(health = health)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("sent") { c.healthStatus.value.firstReadDone && service.wellnessRecords.size == FakeHealth.idsOf(6).size }
        val allBodies = service.wellnessBodies.joinToString("\n")
        // 4 hours either side of each night are in the heart-rate records; only the night's own samples were sent.
        val sentSamples = Regex("\"bpm\":$SENTINEL_BPM").findAll(allBodies).count()
        // Six nights of one reading a minute, both ends included, and the workout's own 41 readings.
        assertEquals(6 * (7 * 60 + 1) + 41, sentSamples)
        assertFalse(allBodies.contains(service.token))
    }

    @Test
    fun `an app that is opened reads what is new and sends it, and nothing that was already sent`() {
        val health = allowedHealth(days = 3)
        val c = world.container(health = health)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("the first read") { c.healthStatus.value.firstReadDone && c.healthStatus.value.waitingBatches == 0 }
        val posts = service.wellnessBodies.size
        health.add(RestingHeartRateReading("rhr-new", end + 1_000, 55, end + 2_000))
        c.appOpened()
        await(
            "the new reading arrives",
            diagnostics = diagnostics(c),
        ) { service.wellnessRecords.containsKey("rhr-new") }
        val resent = service.wellnessBodies.drop(posts).count { it.contains("rhr-0\"") }
        assertEquals("what was sent is not read again", 0, resent)
    }

    // region offline and failures

    @Test
    fun `a service that is down keeps every batch on disk, a restart finds them, and they go when it is back`() {
        val health = allowedHealth(days = 12)
        service.down = true
        val first = world.container("watch-shared", health = health, moving = true)
        first.configure()
        first.setOnboarding(OnboardingStep.Reading)
        await("the batches wait and the failure is known", diagnostics = diagnostics(first)) {
            first.healthStatus.value.let { it.firstReadDone && it.waitingBatches > 0 && it.uploadProblem != null }
        }
        await("they are on disk") { outboxFiles().isNotEmpty() && stateText().contains("\"failures\":") }
        assertTrue(service.wellnessRecords.isEmpty())
        val waiting = outboxFiles().size
        world.stop(first)

        // A new process: nothing is read again, what waits is known, and it goes when the service answers.
        service.down = false
        val second = world.container("watch-shared", health = health, moving = true)
        await("what waits is known before anything runs") { second.healthStatus.value.waitingBatches == waiting }
        await("it is all sent", diagnostics = diagnostics(second)) {
            service.wellnessRecords.size == FakeHealth.idsOf(12).size && second.healthStatus.value.waitingBatches == 0
        }
        assertEquals(0, outboxFiles().size)
    }

    @Test
    fun `an answer that is lost keeps the batch, and the re-send changes nothing at the service`() {
        val health = allowedHealth(days = 4)
        service.wellnessLoseAnswers = 1
        val c = world.container(health = health, moving = true)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("every record is at the service and nothing waits", diagnostics = diagnostics(c)) {
            c.healthStatus.value.let { it.firstReadDone && it.waitingBatches == 0 } &&
                service.wellnessRecords.size == FakeHealth.idsOf(4).size
        }
        assertEquals("the same ids, once each", FakeHealth.idsOf(4), service.wellnessRecords.keys.toSet())
        assertTrue("the lost answer made one batch go twice", service.wellnessBodies.size > 1)
    }

    @Test
    fun `a batch the service refuses is set aside on disk and counted, and the rest goes`() {
        val health = allowedHealth(days = 12)
        service.wellnessScript +=
            422 to """{"error":{"code":"invalid_record","message":"/records/1/bpm must be <= 300"}}"""
        val c = world.container(health = health, moving = true)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("the others are sent", diagnostics = diagnostics(c)) {
            c.healthStatus.value.let { it.firstReadDone && it.waitingBatches == 0 && it.parkedBatches == 1 }
        }
        assertEquals(1, File(world.cache, "watch-outbox/parked").listFiles().orEmpty().size)
        assertTrue(service.wellnessRecords.isNotEmpty())
    }

    @Test
    fun `a rejected token stops the upload, says so, and Sync now tries it once more`() {
        val health = allowedHealth(days = 3)
        service.rejectToken = true
        val c = world.container(health = health, moving = true)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("the token is refused", diagnostics = diagnostics(c)) {
            c.healthStatus.value.uploadProblem?.kind == ProblemKind.TOKEN_REJECTED &&
                c.healthStatus.value.waitingBatches > 0
        }
        assertTrue(service.wellnessRecords.isEmpty())
        service.rejectToken = false
        c.syncNow()
        await("Sync now sends what waited", diagnostics = diagnostics(c)) {
            service.wellnessRecords.size == FakeHealth.idsOf(3).size && c.healthStatus.value.waitingBatches == 0
        }
    }

    // region access

    @Test
    fun `a declined Health Connect reads nothing and sends nothing, and the first run goes on without it`() {
        val health = FakeHealth()
        FakeHealth.sixtyDays(health, end, 3)
        val c = world.container(health = health)
        c.configure()
        c.appOpened()
        await("access is known") { c.healthStatus.value.checked }
        assertTrue(
            c.healthStatus.value.access.granted
                .isEmpty(),
        )
        assertTrue(service.wellnessBodies.isEmpty())
        assertEquals(0, health.pagesRead.get())
    }

    @Test
    fun `access given later starts the read, a permission taken away and a missing Health Connect are plain`() {
        val health = FakeHealth()
        FakeHealth.sixtyDays(health, end, 3)
        val c = world.container(health = health, moving = true)
        c.configure()
        c.appOpened()
        await("nothing allowed yet") { c.healthStatus.value.checked }
        health.allow()
        c.healthAccessChanged()
        c.appOpened()
        c.setOnboarding(OnboardingStep.Reading)
        await("read and sent", diagnostics = diagnostics(c)) {
            service.wellnessRecords.size == FakeHealth.idsOf(3).size
        }
        health.access = health.access.copy(granted = health.access.granted - HealthDataType.SLEEP)
        c.healthAccessChanged()
        await("the missing type is known") { HealthDataType.SLEEP in c.healthStatus.value.access.missingEssential }
        health.availability = HealthAvailability.UPDATE_REQUIRED
        c.healthAccessChanged()
        await("unavailable is known") { c.healthStatus.value.availability == HealthAvailability.UPDATE_REQUIRED }
    }

    // region the figures the screens read

    @Test
    fun `wellness, recovery and notes are fetched, kept on disk and shown from there with the service down`() {
        val first = world.container("watch-figures")
        first.configure()
        first.screenOpened(RefreshTarget.Wellness)
        first.screenOpened(RefreshTarget.Today)
        first.screenOpened(RefreshTarget.Notes)
        await("the three answers") {
            first.wellnessFor(WellnessRange.FOUR_WEEKS).state.value.value != null &&
                first.recovery.state.value.value != null &&
                first.insights.view.value.list.items
                    .isNotEmpty()
        }
        assertEquals(
            28,
            first
                .wellnessFor(WellnessRange.FOUR_WEEKS)
                .state.value.value
                ?.days
                ?.size,
        )
        val files =
            world.cache
                .walkTopDown()
                .filter { it.isFile }
                .map { it.name }
                .toSet()
        assertTrue(files.toString(), files.containsAll(setOf("wellness-4w-0", "recovery", "insights")))
        world.stop(first)

        service.down = true
        val second = world.container("watch-figures")
        await("shown from disk with the service down") {
            second.wellnessFor(WellnessRange.FOUR_WEEKS).state.value.value != null &&
                second.recovery.state.value.value != null &&
                second.insights.view.value.list.items
                    .isNotEmpty()
        }
    }

    @Test
    fun `a year is two requests the service can serve, and the older notes come 30 days at a time`() {
        val c = world.container()
        c.configure()
        c.setWellnessRange(WellnessRange.ONE_YEAR)
        await("the range is remembered") { c.settings.value?.wellnessRange == WellnessRange.ONE_YEAR }
        c.screenOpened(RefreshTarget.Wellness)
        await("both halves") {
            c
                .wellnessFor(WellnessRange.ONE_YEAR)
                .state.value.value
                ?.days
                ?.size == 365
        }
        val asked = service.requests("GET", "/v1/wellness").map { it.query.orEmpty() }
        assertTrue(asked.toString(), asked.count { it.contains("to=2026-11-18") } >= 1)
        c.screenOpened(RefreshTarget.Notes)
        await("the notes") {
            c.insights.view.value.list.items
                .isNotEmpty()
        }
        c.loadMoreNotes()
        await("the older window was asked") {
            service.requests("GET", "/v1/insights").size >= 2
        }
    }

    // region heart rate settings

    @Test
    fun `a heart-rate change goes on the profile at once and to the service once the rider stops tapping`() {
        val c = world.container()
        c.configure()
        await("the profile") { c.profile.state.value.profile != null }
        c.setMaxHeartRate(190)
        c.setMaxHeartRate(191)
        assertEquals(
            "the number moves as the rider taps",
            191,
            c.profile.state.value.profile
                ?.maxHeartRate,
        )
        c.setThresholdHeartRate(170)
        await("the service has both, in one or two patches", diagnostics = {
            service.received.joinToString("\n") { "${it.method} ${it.path}" }
        }) {
            service.profile?.maxHeartRate == 191 && service.profile?.thresholdHeartRate == 170
        }
        assertTrue("debounced: fewer requests than taps", service.requests("PATCH", "/v1/profile").size <= 2)
        await("the answer is taken in") { c.profile.state.value.pending == null }
    }

    // region secrecy

    @Test
    fun `the token is in no file the watch data keeps, and no figure and no token reaches a log`() {
        val health = allowedHealth(days = 4)
        val c = world.container(health = health, moving = true)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("sent", diagnostics = diagnostics(c)) {
            c.healthStatus.value.firstReadDone && service.wellnessRecords.size == FakeHealth.idsOf(4).size
        }
        await("recovery too") { c.recovery.state.value.value != null }
        val disk = world.diskText()
        assertFalse("the token is in a file", disk.contains(service.token))
        assertFalse(c.healthStatus.value.toString().contains(service.token))
        // The figures leave in the bodies and are kept as answers; no log line holds one, and none holds the token.
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg}" }
        assertFalse(logs, logs.contains(service.token))
        assertFalse(logs, logs.contains("\"bpm\""))
        assertFalse(logs, logs.contains(SENTINEL_BPM.toString()))
        // The state file holds tokens, instants and counts: no record, no figure.
        val state = stateText()
        assertFalse(state.contains("bpm") || state.contains("samples") || state.contains(service.token))
    }

    @Test
    fun `a state that is damaged on disk is a fresh start, which the service takes as duplicates`() {
        val health = allowedHealth(days = 3)
        val c = world.container("watch-damaged", health = health)
        c.configure()
        c.setOnboarding(OnboardingStep.Reading)
        await("sent") { c.healthStatus.value.firstReadDone && service.wellnessRecords.size == FakeHealth.idsOf(3).size }
        world.stop(c)
        File(world.cache, "watch-state").writeText("not a state at all")
        val again = world.container("watch-damaged", health = health)
        again.configure()
        val before = service.wellnessRecords.size
        again.appOpened()
        await(
            "it reads again without harm",
            diagnostics = diagnostics(again),
        ) { again.healthStatus.value.firstReadDone }
        assertEquals(before, service.wellnessRecords.size)
    }

    @Test
    fun `a first read that was stopped half way goes on from the next window after a restart`() {
        val health = allowedHealth(days = 30)
        // Health Connect fails after a few windows, the way a killed process or a stopped job does.
        health.failAfterPages = 30
        val first = world.container("watch-resume", health = health)
        first.configure()
        first.setOnboarding(OnboardingStep.Reading)
        await("some windows are done and the read stopped", diagnostics = diagnostics(first)) {
            Regex("\"chunksDone\":([1-9])").containsMatchIn(stateText()) && first.healthStatus.value.readIssue != null
        }
        assertFalse("no figure and no failure text in the state", stateText().contains("SENTINEL"))
        assertFalse(first.healthStatus.value.toString().contains("SENTINEL"))
        assertFalse(service.wellnessRecords.size == FakeHealth.idsOf(30).size)
        world.stop(first)
        health.failAfterPages = null
        val second = world.container("watch-resume", health = health)
        second.appOpened()
        await("everything is there", diagnostics = diagnostics(second)) {
            service.wellnessRecords.size == FakeHealth.idsOf(30).size && second.healthStatus.value.firstReadDone
        }
        assertEquals(FakeHealth.idsOf(30), service.wellnessRecords.keys.toSet())
    }

    @Test
    fun `Sync now reads and sends the watch data and asks for the figures again`() {
        val health = allowedHealth(days = 3)
        val c = world.container(health = health, moving = true)
        c.configure()
        c.appOpened()
        c.setOnboarding(OnboardingStep.Reading)
        await("first read") { c.healthStatus.value.firstReadDone }
        health.add(RestingHeartRateReading("rhr-later", end + 7_000, 52, end + 8_000))
        val requests = service.requests("GET", "/v1/recovery").size
        c.syncNow()
        await("synced", diagnostics = diagnostics(c)) { service.wellnessRecords.containsKey("rhr-later") }
        await("recovery asked again") { service.requests("GET", "/v1/recovery").size > requests }
    }
}
