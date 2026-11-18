package app.overdrive.ride

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.profile.CachedProfile
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileCacheFormat
import app.overdrive.core.profile.ProfilePhysics
import app.overdrive.core.profile.ProfileProblem
import app.overdrive.core.profile.ProfileRefresh
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.data.rides.RideStore
import app.overdrive.data.sync.UrlConnectionTransport
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.profile.ProfileUi
import app.overdrive.ride.profile.ValueSource
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.DemoSpeed
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.ride.settings.SecretText
import app.overdrive.ride.settings.SettingsRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

/**
 * The rider's profile in the real container (spec §8.3, ADR 0021), against a local stand-in of the service that holds a profile
 * and applies a `PATCH` as the real one does: the FTP of the ride is the service's, not the tablet's own, whatever the tablet has
 * stored (a planned "63% FTP" step is 110 W at FTP 175, not 126 W); it survives a restart with no service; the tablet's own values
 * come back when there is none; a change made on the Settings screen reaches the service, and one made while the service is down
 * is kept, shown as not sent, and sent later. Real threads, real files, real sockets on loopback.
 *
 * **Every wait is for a condition**, and where a test then closes the container or reads a file it waits for the **disk** as well:
 * the cache is written on a coroutine that `close()` cancels, so seeing a value in memory proves nothing about the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerProfileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val extra = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()
    private val names = mutableMapOf<AppContainer, String>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = LocalService()

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
            profilePatchTransport = UrlConnectionTransport(setPatchMethod = JdkPatch::allow),
            profileFile = File(extra, "profile/profile"),
            today = { "2026-11-18" },
            syncSettleMillis = 50,
        ).also {
            containers += it
            names[it] = name
        }

    private fun AppContainer.configure(
        url: String = service.baseUrl,
        token: String = service.token,
    ) {
        updateSettings { it.copy(syncUrl = url, syncToken = SecretText(token)) }
    }

    /** Simulated devices at 60x, set the way a tester sets them, and waited for: the ride is then built from the settings. */
    private fun AppContainer.simulate() {
        runBlocking {
            settingsRepository.update { it.copy(demoSpeed = DemoSpeed.Faster, deviceSource = DeviceSource.Simulated) }
        }
        await("the simulated devices are in") { host.devices.value.simulated }
    }

    private fun profileOf(
        ftp: Int?,
        massKg: Double = 82.0,
    ): Profile =
        Profile.default().copy(
            ftp = ftp,
            physics = ProfilePhysics.DEFAULT.copy(systemMassKg = massKg),
            updatedAt = "2026-11-18T09:30:00Z",
        )

    /** The warm-up the plan has in mind: ten minutes at 63% of FTP. */
    private val warmUp = Workout(1, "icu-warmup", "Warm-up", null, listOf(SteadyStep(duration = 600, target = 0.63)))

    // ---- Waiting ------------------------------------------------------------------------------

    private fun diagnostics(): String =
        buildString {
            appendLine()
            appendLine(
                "service: profile requests=${service.profileRequests.map { it.method }}, " +
                    "service profile ftp=${service.profile?.ftp}, down=${service.down}",
            )
            for (c in containers) {
                val state = c.profile.state.value
                appendLine(
                    "container ${names[c]}: ready=${state.ready} profile ftp=${state.profile?.ftp} " +
                        "pending=${state.pending != null} problem=${state.problem} refreshing=${state.refreshing} " +
                        "effective ftp=${c.effectiveSettings.value.ftp} stored ftp=${c.settings.value.ftp} " +
                        "ride=${c.host.snapshots.value.state} ride ftp=${c.host.snapshots.value.ftp}",
                )
            }
            val cached = cachedProfile()
            appendLine(
                "profile cache: exists=${File(extra, "profile/profile").isFile} ftp=${cached?.profile?.ftp} " +
                    "pending=${cached?.pending != null}",
            )
            val workers = Thread.getAllStackTraces().entries.filter { it.key.name.startsWith("DefaultDispatcher") }
            appendLine(
                "default dispatcher threads: ${workers.size}, " +
                    workers.groupingBy { it.key.state }.eachCount().entries.joinToString { "${it.key}=${it.value}" },
            )
        }

    private fun await(
        what: String,
        // A bound, not a pause: a condition that holds is seen within ten milliseconds. It is long because a two-core CI runner
        // that is busy takes many seconds for the first Robolectric test of a class.
        timeoutMs: Long = 60_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what" + diagnostics())
    }

    /** The profile cache as the file reads now, or null while it is missing or not whole. */
    private fun cachedProfile(): CachedProfile? =
        try {
            File(extra, "profile/profile")
                .takeIf { it.isFile }
                ?.readText()
                ?.let(ProfileCacheFormat::decode)
        } catch (e: IOException) {
            null
        }

    /** Whether the settings file of [name] on disk holds [text] (the address, stored as it was typed). */
    private fun settingsOnDisk(
        name: String,
        text: String,
    ): Boolean =
        File(context.filesDir, "datastore/$name.preferences_pb")
            .takeIf { it.isFile }
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?.contains(text) == true

    /** A ride of [expected] FTP is built and ready (a settings change rebuilds a ready ride a moment after it). */
    private fun AppContainer.awaitReadyRide(
        expected: Int,
        after: Int = 0,
    ) = await("a ride of FTP $expected is ready") {
        val snapshot = host.snapshots.value
        host.ui.value.rideNumber > after && snapshot.state == RideState.READY && snapshot.ftp == expected
    }

    private fun AppContainer.choose(workout: Workout) {
        val before = host.ui.value.rideNumber
        host.select(RideChoice.Planned(workout))
        await("the workout is chosen and a ride is built for it") {
            host.ui.value.rideNumber > before && (host.choice.value as? RideChoice.Planned)?.workout?.id == workout.id
        }
    }

    private val gets get() = service.profileRequests.count { it.method == "GET" }

    // ---- The ride uses the service's profile ---------------------------------------------------

    @Test
    fun aPlannedSixtyThreePercentIs110WattsAtTheServicesFtpOf175AndTheRideRecordSaysSo() {
        service.profile = profileOf(175)
        val c = container("profile-ride")
        c.simulate()
        c.configure()
        await("the service's FTP is in force") { c.effectiveSettings.value.ftp == 175 }
        assertEquals("the tablet's own value is not overwritten", 200, c.settings.value.ftp)
        assertEquals(82.0, c.effectiveSettings.value.massKg, 0.0)

        c.choose(warmUp)
        c.awaitReadyRide(175)
        c.host.actions.start()
        await("the first step's target") { c.host.snapshots.value.targetPower != null }
        assertEquals("63% of 175, not of 200", 110, c.host.snapshots.value.targetPower)
        assertEquals(175, c.host.snapshots.value.ftp)
        await("a few seconds of ride") { c.host.snapshots.value.elapsedSeconds >= 3 }
        c.host.actions.finish()
        await("the ride is saved") {
            c.host.ui.value.finished
                ?.save is SaveState.Saved
        }
        val saved = (checkNotNull(c.host.ui.value.finished).save as SaveState.Saved).ride
        val record = RideRecordJson.decode(RideStore(directory).recordFile(saved.id).readText())
        assertEquals("the ride record keeps the FTP it was ridden at", 175, record.ride.ftp)
        assertTrue(record.samples.any { it.targetPower == 110 })
        assertTrue(record.samples.none { it.targetPower == 126 })
    }

    @Test
    fun withNoServiceTheTabletsOwnFtpApplies_126WattsAt200() {
        val c = container("profile-local")
        c.simulate()
        c.choose(warmUp)
        c.awaitReadyRide(200)
        c.host.actions.start()
        await("the first step's target") { c.host.snapshots.value.targetPower != null }
        assertEquals(126, c.host.snapshots.value.targetPower)
        assertTrue("nothing was asked of a service that is not set up", service.profileRequests.isEmpty())
        assertNull(c.profile.state.value.profile)
    }

    @Test
    fun theTabletFollowsTheServiceThenGoesBackToItsOwnValuesWhenTheServiceIsTakenAway() {
        val c = container("profile-away")
        c.simulate()
        c.awaitReadyRide(200)
        service.profile = profileOf(175)
        c.configure()
        c.awaitReadyRide(175)
        assertEquals(175, c.effectiveSettings.value.ftp)

        c.updateSettings { it.copy(syncUrl = "") }
        await("the tablet's own FTP is back") { c.effectiveSettings.value.ftp == 200 }
        c.awaitReadyRide(200)
        assertNull(c.profile.state.value.profile)
        assertEquals(200, c.settings.value.ftp)

        // And with the service back, the profile that was kept is in force again with nothing asked first.
        c.configure()
        await("the service's FTP again") { c.effectiveSettings.value.ftp == 175 }
        c.awaitReadyRide(175)
    }

    @Test
    fun aProfileWithNoFtpLeavesTheTabletsOwnFtpAndStillBringsItsPhysics() {
        service.profile = profileOf(ftp = null, massKg = 82.0)
        val c = container("profile-noftp")
        c.simulate()
        c.configure()
        await("the service's physics are in force") { c.effectiveSettings.value.massKg == 82.0 }
        assertEquals(200, c.effectiveSettings.value.ftp)
        runBlocking { c.settingsRepository.update { it.copy(ftp = 220) } }
        await("the tablet's own FTP is the one in force") { c.effectiveSettings.value.ftp == 220 }
        c.awaitReadyRide(220)
        val ui = ProfileUi.of(c.profile.state.value)
        assertEquals(ValueSource.Tablet, ui.ftp)
        assertEquals(ValueSource.Service, ui.mass)
    }

    @Test
    fun theProfileIsThereAfterARestartWithNoService() {
        service.profile = profileOf(175)
        val first = container("profile-first")
        first.configure()
        await("the service's FTP is in force") { first.effectiveSettings.value.ftp == 175 }
        // In force is in memory; the cache is written a moment after on another thread, and close() would cancel that write.
        await("the profile is on disk") { cachedProfile()?.profile?.ftp == 175 }
        await("the settings are on disk") { settingsOnDisk("profile-first", service.baseUrl) }
        first.close()
        containers.remove(first)
        File(context.filesDir, "datastore/profile-first.preferences_pb")
            .copyTo(File(context.filesDir, "datastore/profile-second.preferences_pb"), overwrite = true)

        service.down = true
        val second = container("profile-second")
        await("the cached profile is in force") { second.effectiveSettings.value.ftp == 175 }
        await("and the failed request says so") {
            second.profile.state.value.problem == ProfileProblem.Failed("Service error (503)")
        }
        assertEquals(
            "the profile stays",
            175,
            second.profile.state.value.profile
                ?.ftp,
        )
        assertEquals(200, second.settings.value.ftp)
        second.simulate()
        second.awaitReadyRide(175)
        assertEquals(
            "Profile: Service error (503) · showing the last known profile",
            ProfileUi.of(second.profile.state.value).note,
        )
    }

    // ---- Following the service, and the one gesture --------------------------------------------

    @Test
    fun anFtpSetThroughAnAiClientIsFollowedWhenTheRiderRefreshes_andTheSameGestureRefreshesThePlan() {
        service.profile = profileOf(175)
        val c = container("profile-follow")
        c.simulate()
        c.configure()
        c.awaitReadyRide(175)
        await("the first read is done") { !c.profile.state.value.refreshing && gets >= 1 }
        val plans = service.planRequests.size

        service.profile = profileOf(190)
        await("the rider's refresh asked for both and the tablet follows") {
            c.refreshPlan()
            c.effectiveSettings.value.ftp == 190 && service.planRequests.size > plans
        }
        c.awaitReadyRide(190)
        await("the new profile is on disk") { cachedProfile()?.profile?.ftp == 190 }
        assertNull(c.profile.state.value.problem)
    }

    @Test
    fun theReadyScreenOpeningAsksOnceAMinuteAtMost() {
        service.profile = profileOf(175)
        val c = container("profile-minute")
        c.configure()
        await("the first read is in") { c.profile.state.value.profile != null && !c.profile.state.value.refreshing }
        val asked = gets
        c.planScreenOpened()
        runBlocking { c.profile.refresh(ProfileRefresh.SCREEN_OPENED) }
        runBlocking { c.profile.refresh(ProfileRefresh.SCREEN_OPENED) }
        assertEquals("within the minute: not asked again", asked, gets)
        await("the rider's refresh asks") {
            c.refreshPlan()
            gets > asked
        }
    }

    @Test
    fun nothingIsAskedAndNothingIsSaidUntilAnAddressAndATokenAreSet() {
        service.profile = profileOf(175)
        val c = container("profile-unset")
        runBlocking {
            c.profile.refresh(ProfileRefresh.START)
            c.profile.refresh(ProfileRefresh.REQUESTED)
        }
        c.planScreenOpened()
        c.refreshPlan()
        c.updateSettings { it.copy(syncUrl = service.baseUrl) }
        await("the settings are read") { c.settings.value.syncUrl == service.baseUrl }
        runBlocking { c.profile.refresh(ProfileRefresh.REQUESTED) }
        assertTrue("an address alone asks nothing", service.profileRequests.isEmpty())
        assertNull(c.profile.state.value.profile)
        assertNull(c.profile.state.value.problem)
        assertEquals(200, c.effectiveSettings.value.ftp)
    }

    // ---- Editing on the tablet -----------------------------------------------------------------

    @Test
    fun anFtpChangedOnTheSettingsScreenReachesTheServiceAndTheTabletsOwnValueIsLeftAlone() {
        service.profile = profileOf(175)
        val c = container("profile-edit")
        c.configure()
        await("the service's FTP is in force") { c.effectiveSettings.value.ftp == 175 }

        // What the + of the FTP stepper does: a step up from the number the screen shows (175), not from the stored 200.
        c.changeRider { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, +1)) }
        await("the service took it") { service.profile?.ftp == 180 }
        val patch = service.profileRequests.last { it.method == "PATCH" }
        assertEquals("""{"ftp":180}""", String(patch.body))
        assertEquals("Bearer ${service.token}", patch.authorization)
        await("it is in force and nothing is left unsent") {
            c.effectiveSettings.value.ftp == 180 && c.profile.state.value.pending == null
        }
        assertEquals("the tablet's own value is not overwritten", 200, c.settings.value.ftp)
        assertEquals(ValueSource.Service, ProfileUi.of(c.profile.state.value).ftp)
        await("the cache holds the answer to the patch, with nothing unsent") {
            cachedProfile()?.let { it.profile.ftp == 180 && it.pending == null } == true
        }

        c.changeRider { it.copy(massKg = SettingsRules.mass.stepped(it.massKg, +1), cdA = 0.30) }
        await("the physics reached the service") {
            service.profile?.physics?.let { it.systemMassKg == 83.0 && it.cdA == 0.30 } == true
        }
        assertEquals("""{"physics":{"systemMassKg":83.0,"cdA":0.3}}""", String(service.profileRequests.last().body))
        await("and are in force") { c.effectiveSettings.value.massKg == 83.0 && c.effectiveSettings.value.cdA == 0.30 }
        assertEquals(1.225, service.profile?.physics?.airDensity ?: 0.0, 0.0)
    }

    @Test
    fun anEditMadeWhileTheServiceIsDownStaysInForceIsShownAsNotSentAndIsSentByTheRidersRefresh() {
        service.profile = profileOf(175)
        val c = container("profile-offline-edit")
        c.simulate()
        c.configure()
        c.awaitReadyRide(175)
        await("the profile is on disk") { cachedProfile()?.profile?.ftp == 175 }

        service.down = true
        c.changeRider { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, +1)) }
        await("the rider's value is in force at once") { c.effectiveSettings.value.ftp == 180 }
        c.awaitReadyRide(180)
        await("it could not be sent, and says why") {
            c.profile.state.value.problem == ProfileProblem.Failed("Service error (503)") &&
                !c.profile.state.value.refreshing
        }
        assertEquals(175, service.profile?.ftp)
        assertEquals(ValueSource.NotSent, ProfileUi.of(c.profile.state.value).ftp)
        await("the unsent edit is on disk") { cachedProfile()?.pending != null }
        assertEquals("beside the service's last answer", 175, cachedProfile()?.profile?.ftp)

        service.down = false
        await("the rider's refresh sends it") {
            c.refreshPlan()
            service.profile?.ftp == 180
        }
        await(
            "nothing is left unsent",
        ) { c.profile.state.value.pending == null && c.profile.state.value.problem == null }
        await("and the disk says so") { cachedProfile()?.let { it.pending == null && it.profile.ftp == 180 } == true }
        assertEquals(180, c.effectiveSettings.value.ftp)
        assertEquals(200, c.settings.value.ftp)
    }

    @Test
    fun anEditThatWasNeverSentSurvivesARestartAndIsSentFirstAtTheNextStart() {
        service.profile = profileOf(175)
        val first = container("profile-unsent-first")
        first.configure()
        await("the service's FTP is in force") { first.effectiveSettings.value.ftp == 175 }
        await("the profile is on disk") { cachedProfile()?.profile?.ftp == 175 }

        service.down = true
        first.changeRider { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, +1)) }
        await("the edit is on disk, unsent") { cachedProfile()?.pending != null }
        await("the settings are on disk") { settingsOnDisk("profile-unsent-first", service.baseUrl) }
        first.close()
        containers.remove(first)
        File(context.filesDir, "datastore/profile-unsent-first.preferences_pb")
            .copyTo(File(context.filesDir, "datastore/profile-unsent-second.preferences_pb"), overwrite = true)

        // Still down: the restarted tablet shows the rider's value, not the service's.
        val offline = container("profile-unsent-second")
        await("the unsent edit is in force after a restart") { offline.effectiveSettings.value.ftp == 180 }
        assertEquals(ValueSource.NotSent, ProfileUi.of(offline.profile.state.value).ftp)
        await("and the failed attempt is done") { offline.profile.state.value.problem is ProfileProblem.Failed }
        offline.close()
        containers.remove(offline)

        // The service is back: the next start sends the edit first, and the answer to it is the profile (no GET follows).
        service.down = false
        service.profileRequests.clear()
        File(context.filesDir, "datastore/profile-unsent-first.preferences_pb")
            .copyTo(File(context.filesDir, "datastore/profile-unsent-third.preferences_pb"), overwrite = true)
        val third = container("profile-unsent-third")
        await("the edit reached the service") { service.profile?.ftp == 180 }
        await("nothing is left unsent") {
            third.profile.state.value.pending == null && !third.profile.state.value.refreshing &&
                third.effectiveSettings.value.ftp == 180
        }
        assertEquals(
            "sent first, and nothing read after it",
            listOf("PATCH"),
            service.profileRequests.map { it.method },
        )
        await("the disk says so") { cachedProfile()?.let { it.pending == null && it.profile.ftp == 180 } == true }
    }

    // ---- Secrets -------------------------------------------------------------------------------

    @Test
    fun theTokenAndTheAddressReachNoLogAndNoProfileStateAndNoCacheFile() {
        val token = service.token
        ShadowLog.clear()
        service.profile = profileOf(175)
        val c = container("profile-secret")
        service.down = true
        c.configure()
        await("it fails") { c.profile.state.value.problem is ProfileProblem.Failed }
        service.down = false
        await("it works, an edit goes out too") {
            runBlocking { c.profile.refresh(ProfileRefresh.REQUESTED) }
            c.profile.state.value.profile != null
        }
        c.changeRider { it.copy(ftp = SettingsRules.ftp.stepped(it.ftp, +1)) }
        await("the edit is taken") { service.profile?.ftp == 180 }
        await("and is kept") { cachedProfile()?.profile?.ftp == 180 }
        for (line in ShadowLog.getLogs()) {
            assertFalse("a log line holds the token: ${line.msg}", line.msg.contains(token))
            assertFalse("a log line holds the address: ${line.msg}", line.msg.contains(service.baseUrl))
            assertFalse(line.throwable?.stackTraceToString()?.contains(token) ?: false)
        }
        assertFalse(
            c.profile.state.value
                .toString()
                .contains(token),
        )
        val file = File(extra, "profile/profile").readText()
        assertFalse(file.contains(token))
        assertFalse(file.contains(service.baseUrl))
    }
}
