package app.overdrive.ride

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.PlanCacheFormat
import app.overdrive.core.plan.PlanProblem
import app.overdrive.core.plan.PlanRefresh
import app.overdrive.core.plan.PlanSource
import app.overdrive.core.plan.PlanState
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.plan.PlanMark
import app.overdrive.ride.plan.PlanUi
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.DemoSpeed
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.ride.settings.SecretText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * The planned workouts in the real container (spec §5.7, ADR 0017), against a local stand-in of the service: nothing is asked
 * until an address and token are set, then the fortnight is, the list survives a restart with no service, a rejected token and
 * "the service has no plan" are said or not said as they should be, and a planned workout ridden to its end is a ride that
 * carries the plan's id, is stored and uploaded with it and shows as done. Real threads, real files, real sockets on loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerPlanTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val extra = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()
    private val names = mutableMapOf<AppContainer, String>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = LocalService()
    private val today = "2026-11-18"

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        worldScope.cancel()
        service.close()
        directory.deleteRecursively()
        extra.deleteRecursively()
    }

    private fun container(name: String): AppContainer =
        AppContainer(
            context,
            directory,
            settingsName = name,
            devicesName = "devices-$name",
            bleLogDirectory = File(extra, "ble-$name"),
            bleStack = { _, _ -> FakeBleStack(FakeBle(worldScope, nanoClock = System::nanoTime)) },
            outboxFile = File(extra, "sync/outbox.log"),
            planFile = File(extra, "plan/workouts"),
            today = { today },
            syncSettleMillis = 50,
        ).also {
            containers += it
            names[it] = name
        }

    /**
     * What was going on when a wait ran out, for a CI log that has nothing else to go on: the plan's state and the settings of
     * each container, what the service saw, the files on disk and what the default dispatcher's threads were doing. Never the
     * token or the address.
     */
    private fun diagnostics(): String =
        buildString {
            appendLine()
            appendLine(
                "service: plan requests=${service.planRequests.size} ${service.planRequests.map { it.query }}, " +
                    "ride requests=${service.received.size}, down=${service.down}, planStatus=${service.planStatus}",
            )
            for (c in containers) {
                val state = c.plan.state.value
                val now = c.settings.value
                appendLine(
                    "container ${names[c]}: visible=${state.visible} loaded=${state.loaded} stale=${state.stale} " +
                        "refreshing=${state.refreshing} problem=${state.problem} workouts=${state.workouts.map {
                            it.id
                        }} " +
                        "settings: address set=${now.syncUrl.isNotEmpty()} token set=${!now.syncToken.isEmpty}",
                )
            }
            val datastore = File(context.filesDir, "datastore")
            appendLine(
                "datastore files: " +
                    datastore.listFiles().orEmpty().sortedBy { it.name }.joinToString { "${it.name}=${it.length()}" },
            )
            val plan = File(extra, "plan/workouts")
            appendLine("plan cache: exists=${plan.isFile} size=${if (plan.isFile) plan.length() else -1}")
            val workers = Thread.getAllStackTraces().entries.filter { it.key.name.startsWith("DefaultDispatcher") }
            appendLine(
                "default dispatcher threads: ${workers.size}, " +
                    workers.groupingBy { it.key.state }.eachCount().entries.joinToString { "${it.key}=${it.value}" },
            )
            for ((thread, stack) in workers.filter { it.key.state == Thread.State.RUNNABLE }.take(6)) {
                appendLine("  ${thread.name} RUNNABLE at ${stack.take(3).joinToString(" < ")}")
            }
        }

    /** The ids of the planned workouts in the cache file as it reads now, or null while it is missing or not whole. */
    private fun cachedIds(): List<String>? =
        try {
            File(extra, "plan/workouts")
                .takeIf { it.isFile }
                ?.readText()
                ?.let(PlanCacheFormat::decode)
                ?.response
                ?.workouts
                ?.map { it.id }
        } catch (e: java.io.IOException) {
            null
        }

    private fun await(
        what: String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what" + diagnostics())
    }

    private fun AppContainer.configure(
        url: String = service.baseUrl,
        token: String = service.token,
    ) {
        updateSettings { it.copy(syncUrl = url, syncToken = SecretText(token)) }
    }

    private fun workout(
        id: String,
        name: String,
    ) = Workout(1, id, name, "Test workout $name.", listOf(SteadyStep(duration = 600, target = 0.6)))

    private fun planned(
        id: String,
        date: String,
    ) = workout(id, "Workout $id").let { PlannedWorkout(id, date, it.name, PlanSource.INTERVALS_ICU, it) }

    private fun serve(
        vararg workouts: PlannedWorkout,
        stale: Boolean = false,
        configured: Boolean = true,
    ) {
        service.plan =
            PlanApiJson.encodeWorkoutsResponse(
                WorkoutsResponse(
                    "2026-11-18",
                    "2026-12-01",
                    if (configured) "2026-11-18T06:30:00Z" else null,
                    stale,
                    configured,
                    if (configured) workouts.toList() else emptyList(),
                ),
            )
        service.planStatus = 200
    }

    private val AppContainer.planState: PlanState get() = plan.state.value

    // ---- Fetching -----------------------------------------------------------------------------

    @Test
    fun nothingIsAskedAndNothingShownUntilAnAddressAndATokenAreSet() {
        serve(planned("icu-1", today))
        val c = container("plan-unset")
        runBlocking {
            c.plan.refresh(PlanRefresh.START)
            c.plan.refresh(PlanRefresh.REQUESTED)
        }
        c.planScreenOpened()
        c.refreshPlan()
        assertEquals(PlanState(), c.planState)
        assertEquals(PlanUi.Hidden, PlanUi.of(c.planState, emptyList(), today, 0L))
        c.updateSettings { it.copy(syncUrl = service.baseUrl) }
        c.updateSettings { it.copy(syncToken = SecretText("")) }
        await("the settings are read") { c.settings.value.syncUrl == service.baseUrl }
        runBlocking { c.plan.refresh(PlanRefresh.REQUESTED) }
        assertTrue("an address alone asks nothing", service.planRequests.isEmpty())
        assertEquals(PlanState(), c.planState)
    }

    @Test
    fun setTheAddressAndTokenAndTheFortnightIsAskedForAndShownWithNoRestart() {
        serve(planned("icu-1", today), planned("icu-2", "2026-11-20"))
        val c = container("plan-set")
        c.configure()
        await("the list is there") { c.planState.workouts.map { it.id } == listOf("icu-1", "icu-2") }
        val request = service.planRequests.single()
        assertEquals("from=2026-11-18&to=2026-12-01", request.query)
        assertEquals("Bearer ${service.token}", request.authorization)
        assertEquals("GET", request.method)
        assertEquals(0, request.body.size)
        assertTrue(c.planState.visible && c.planState.loaded)
        assertFalse(c.planState.stale)
        assertEquals("2026-11-18T06:30:00Z", c.planState.generatedAt)
        await("kept on disk") { cachedIds() == listOf("icu-1", "icu-2") }

        // The Ready screen opening within the minute asks nothing more; the rider's refresh does (it is dropped while one
        // is still finishing, so it is pressed until it has asked).
        runBlocking { c.plan.refresh(PlanRefresh.SCREEN_OPENED) }
        assertEquals(1, service.planRequests.size)
        await("the rider's refresh asked") {
            c.refreshPlan()
            service.planRequests.size >= 2
        }
    }

    @Test
    fun theListSurvivesARestartWithNoServiceAndSaysItMayBeOutOfDate() {
        serve(planned("icu-1", today))
        val first = container("plan-first")
        first.configure()
        await("the list is there") { first.planState.workouts.isNotEmpty() }
        // The list is shown as soon as the answer is in, and written to disk a moment after (on a slow disk, a long moment):
        // closing the container before then would cancel that write, and the restart would have nothing to read.
        await("the answer is on disk") { cachedIds() == listOf("icu-1") }
        await("the settings are on disk") { File(context.filesDir, "datastore/plan-first.preferences_pb").isFile }
        first.close()
        containers.remove(first)
        File(context.filesDir, "datastore/plan-first.preferences_pb")
            .copyTo(File(context.filesDir, "datastore/plan-second.preferences_pb"), overwrite = true)

        service.down = true
        val second = container("plan-second")
        await("the cached list is shown") {
            second.planState.visible && second.planState.workouts.map { it.id } == listOf("icu-1")
        }
        await(
            "and the failed request says so",
        ) { second.planState.problem == PlanProblem.Failed("Service error (503)") }
        assertTrue(second.planState.stale)
        assertEquals(listOf("icu-1"), second.planState.workouts.map { it.id })
        val noon = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
        val ui = PlanUi.of(second.planState, emptyList(), today, noon, ZoneOffset.UTC, Locale.ENGLISH)
        assertEquals("Out of date · updated 06:30", ui.status)
        assertEquals("Service error (503)", ui.detail)
        assertEquals(listOf("Workout icu-1"), ui.today.map { it.title })
    }

    @Test
    fun aDamagedCacheIsIgnoredAndTheNextAnswerMakesAGoodOne() {
        val file = File(extra, "plan/workouts").also { it.parentFile.mkdirs() }
        file.writeText("overdrive-plan 1 00000000 1700000000000 00000000\n{\"from\":")
        serve(planned("icu-1", today))
        val c = container("plan-damaged")
        c.configure()
        await("the list is there") { c.planState.workouts.map { it.id } == listOf("icu-1") }
        await("the cache is rewritten whole, so it reads again") { cachedIds() == listOf("icu-1") }
        assertFalse(c.planState.stale)
    }

    @Test
    fun aRejectedTokenIsSaidThenAskedAgainOnlyWhenTheRiderOrTheSettingsAsk() {
        serve(planned("icu-1", today))
        val c = container("plan-token")
        c.configure(token = "tok-wrong-0123456789abcdef0123456789abcdef")
        await("the token is rejected") { c.planState.problem == PlanProblem.TokenRejected }
        assertEquals(1, service.planRequests.size)
        assertEquals(PlanUi.TOKEN_REJECTED, PlanUi.of(c.planState, emptyList(), today, 0L).status)

        runBlocking { c.plan.refresh(PlanRefresh.SCREEN_OPENED) }
        runBlocking { c.plan.refresh(PlanRefresh.START) }
        assertEquals("not asked again by itself", 1, service.planRequests.size)
        await("the rider's refresh asked") {
            c.refreshPlan()
            service.planRequests.size >= 2
        }
        await(
            "and it is rejected again",
        ) { c.planState.problem == PlanProblem.TokenRejected && !c.planState.refreshing }

        c.configure()
        await("the right token, typed, gives the list") { c.planState.workouts.map { it.id } == listOf("icu-1") }
        assertEquals(null, c.planState.problem)
    }

    @Test
    fun aServiceWithNoPlanSourceShowsNothingAndNoError() {
        serve(configured = false)
        val c = container("plan-none")
        c.configure()
        await("it was asked") { service.planRequests.isNotEmpty() }
        await("and answered") { !c.planState.refreshing }
        assertEquals(PlanState(), c.planState)
    }

    @Test
    fun anOldServiceWithoutTheRouteIsAPlainProblemNotACrash() {
        service.planStatus = 404
        val c = container("plan-404")
        c.configure()
        await(
            "the problem is said",
        ) { c.planState.problem == PlanProblem.Failed("The service has no planned workouts") }
        assertFalse(c.planState.loaded)
        assertTrue(c.planState.visible)
    }

    @Test
    fun theTokenAndTheAddressReachNoLogAndNoPlanState() {
        val token = service.token
        ShadowLog.clear()
        serve(planned("icu-1", today))
        val c = container("plan-secret")
        service.down = true
        c.configure()
        await("it fails") { c.planState.problem is PlanProblem.Failed }
        service.down = false
        await("it works") {
            runBlocking { c.plan.refresh(PlanRefresh.REQUESTED) }
            c.planState.workouts.isNotEmpty()
        }
        await("and is kept") { cachedIds() == listOf("icu-1") }
        for (line in ShadowLog.getLogs()) {
            assertFalse("a log line holds the token: ${line.msg}", line.msg.contains(token))
            assertFalse("a log line holds the address: ${line.msg}", line.msg.contains(service.baseUrl))
            assertFalse(line.throwable?.stackTraceToString()?.contains(token) ?: false)
        }
        assertFalse(c.planState.toString().contains(token))
        assertFalse(File(extra, "plan/workouts").readText().contains(token))
        assertFalse(File(extra, "plan/workouts").readText().contains(service.baseUrl))
    }

    // ---- Riding a planned workout ---------------------------------------------------------------

    @Test
    fun aPlannedWorkoutRiddenToItsEndIsARideOfThePlansIdStoredSentAndMarkedDone() {
        val plannedWorkout = planned("icu-4242", today)
        serve(plannedWorkout)
        val c = container("plan-ride")
        runBlocking {
            c.settingsRepository.update { it.copy(demoSpeed = DemoSpeed.Faster, deviceSource = DeviceSource.Simulated) }
        }
        c.configure()
        await("the plan is there") { c.planState.workouts.isNotEmpty() }
        // The setting that chose the simulated devices rebuilds the ride after a pause; choose only once it has.
        await("the simulated devices are in") { c.host.devices.value.simulated }
        val before = c.host.ui.value.rideNumber

        // What a tap on the planned row does: the workout of the plan is chosen like any workout.
        val row = PlanUi.of(c.planState, emptyList(), today, 0L).today.single()
        assertEquals(PlanMark.None, row.mark)
        c.host.select(RideChoice.Planned(row.workout.workout))
        await("a simulated ride of it is ready") {
            c.host.ui.value.rideNumber > before && c.host.snapshots.value.state == RideState.READY
        }
        assertEquals("icu-4242", (c.host.choice.value as RideChoice.Planned).workout.id)
        c.host.actions.start()
        await("a few seconds of ride") { c.host.snapshots.value.elapsedSeconds >= 8 }
        c.host.actions.finish()
        await("the ride is saved") {
            c.host.ui.value.finished
                ?.save is SaveState.Saved
        }
        val saved = (checkNotNull(c.host.ui.value.finished).save as SaveState.Saved).ride

        // The ride is linked to the plan: in its record, in the stored ride, and in what was sent to the service.
        val record = RideRecordJson.decode(RideStore(directory).recordFile(saved.id).readText())
        assertEquals("icu-4242", record.ride.workoutId)
        assertEquals("icu-4242", saved.workoutId)
        await("the ride reached the service") { service.storedRecord(saved.id) != null }
        assertTrue(String(checkNotNull(service.storedRecord(saved.id))).contains("\"workoutId\":\"icu-4242\""))

        // And the Ready screen marks it done, with the time, and still lets it be ridden again.
        await("the ride counts as done") { c.rides.ridden.value.any { it.workoutId == "icu-4242" } }
        val done = PlanUi.of(c.planState, c.rides.ridden.value, today, System.currentTimeMillis()).today.single()
        assertEquals(PlanMark.Done, done.mark)
        assertTrue(done.subtitle, done.subtitle.startsWith("Done "))
        assertTrue(done.enabled)
    }

    @Test
    fun aRestartStillKnowsWhichPlannedWorkoutsWereRidden() {
        val plannedWorkout = planned("icu-4242", today)
        serve(plannedWorkout)
        val first = container("plan-ridden")
        runBlocking {
            first.settingsRepository.update {
                it.copy(
                    demoSpeed = DemoSpeed.Faster,
                    deviceSource = DeviceSource.Simulated,
                )
            }
        }
        await("the simulated devices are in") { first.host.devices.value.simulated }
        val before = first.host.ui.value.rideNumber
        first.host.select(RideChoice.Planned(plannedWorkout.workout))
        await("ready") {
            first.host.ui.value.rideNumber > before && first.host.snapshots.value.state == RideState.READY
        }
        first.host.actions.start()
        await("a few seconds of ride") { first.host.snapshots.value.elapsedSeconds >= 8 }
        first.host.actions.finish()
        await("saved") {
            first.host.ui.value.finished
                ?.save is SaveState.Saved
        }
        first.close()
        containers.remove(first)

        val second = container("plan-ridden-again")
        await("the rides on disk are read") { second.rides.ridden.value.any { it.workoutId == "icu-4242" } }
    }
}
