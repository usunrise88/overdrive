package app.overdrive.ride

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ride.RideState
import app.overdrive.core.sync.SyncStatus
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.rides.RecoveryState
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

/**
 * The persistent outbox in the real container (spec §4.9, ADR 0012): a ride that finishes, a ride that is recovered and the
 * rides already on disk at a start are all queued, nothing is lost or doubled by a restart, nothing is uploaded (the no-op
 * provider acknowledges nothing), and the sync settings reach no log, no ride file and no backup. Real threads, the
 * simulated bike at 60 times speed, real files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val extra = RideFixtures.directory()
    private val outboxFile = File(extra, "sync/outbox.log")
    private val containers = mutableListOf<AppContainer>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        worldScope.cancel()
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
            outboxFile = outboxFile,
        ).also { containers += it }

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
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun AppContainer.queued() = runBlocking { outbox.pending() }.map { it.rideId }

    /** Rides a short simulated ride to its end and returns its id. */
    private fun ride(c: AppContainer): String {
        runBlocking {
            c.settingsRepository.update { it.copy(demoSpeed = DemoSpeed.Faster, deviceSource = DeviceSource.Simulated) }
        }
        val before = c.host.ui.value.rideNumber
        c.host.select(RideChoice.Free)
        await("a simulated ride is ready") {
            c.host.ui.value.rideNumber > before && c.host.snapshots.value.state == RideState.READY
        }
        c.host.actions.start()
        await("a few seconds of ride") { c.host.snapshots.value.elapsedSeconds >= 8 }
        c.host.actions.finish()
        await("the ride is saved") {
            c.host.ui.value.finished
                ?.save is SaveState.Saved
        }
        return (checkNotNull(c.host.ui.value.finished).save as SaveState.Saved).ride.id
    }

    @Test
    fun aFinishedRideIsQueuedOnceAndStaysQueuedAcrossRestartsWithNothingUploaded() {
        val first = container("one")
        val id = ride(first)
        await("the ride is in the outbox") { first.queued() == listOf(id) }
        assertTrue("the journal is on disk", outboxFile.length() > 0)

        // Nothing is uploaded: the no-op provider says there is nowhere to go, and the ride stays.
        await("the coordinator knows it has nowhere to upload") {
            first.sync.status.value == SyncStatus.NotConfigured(1)
        }
        first.close()
        containers.remove(first)

        // The next start offers every stored ride again; the outbox has seen this one, so it is not doubled.
        val second = container("two")
        await("the rides are scanned") { second.rides.lastSaved.value?.id == id }
        Thread.sleep(500)
        assertEquals(listOf(id), second.queued())
        second.close()
        containers.remove(second)

        val third = container("three")
        Thread.sleep(500)
        assertEquals("still one, after another restart", listOf(id), third.queued())
    }

    @Test
    fun aRecoveredRideIsQueuedWhenTheRiderKeepsIt() {
        RideFixtures.writeLog(
            RideStore(directory),
            "cut",
            RideFixtures.START,
            400,
            finished = false,
            title = "Free ride",
        )
        val c = container("recover")
        await("the interrupted ride is found") { c.rides.recovery.value is RecoveryState.Found }
        assertEquals("not queued before it is stored", emptyList<String>(), c.queued())
        c.rides.saveRecovered("cut")
        await("it is queued") { c.queued() == listOf("cut") }
    }

    @Test
    fun aRideThatWasDiscardedIsNeverQueued() {
        RideFixtures.writeLog(
            RideStore(directory),
            "cut",
            RideFixtures.START,
            400,
            finished = false,
            title = "Free ride",
        )
        val c = container("discard")
        await("the interrupted ride is found") { c.rides.recovery.value is RecoveryState.Found }
        c.rides.discardRecovered("cut")
        await("it is gone") { c.rides.recovery.value == RecoveryState.None }
        Thread.sleep(300)
        assertEquals(emptyList<String>(), c.queued())
    }

    @Test
    fun ridesAlreadyOnDiskWhenTheAppStartsAreQueuedOldestFirstOnce() {
        // What an app updated from an earlier alpha finds: stored rides and an outbox that has never seen them.
        val store = RideStore(directory)
        for ((id, daysAgo) in listOf("new" to 1, "old" to 9, "mid" to 4)) {
            store.finalize(RideFixtures.record(id, RideFixtures.START - daysAgo * 86_400_000L, 90), "Ride $id", 3600)
        }
        val c = container("alpha")
        await("all three are queued") { c.queued().size == 3 }
        assertEquals(listOf("old", "mid", "new"), c.queued())
        c.close()
        containers.remove(c)
        val again = container("alpha-again")
        Thread.sleep(500)
        assertEquals(listOf("old", "mid", "new"), again.queued())
    }

    @Test
    fun theSyncSettingsReachNoLogNoRideFileAndNoOutbox() {
        val token = "TOKEN-b1a2c3d4e5f6a7b8c9d0"
        val address = "https://private-host.example.org/overdrive-api"
        ShadowLog.clear()
        val c = container("secret")
        c.updateSettings { it.copy(syncUrl = address, syncToken = SecretText(token)) }
        await("the settings are read back") { c.settings.value.syncToken.value == token }
        assertEquals(address, c.settings.value.syncUrl)

        // What a log line or an exception message made of the settings would be.
        Log.i("OverdriveTest", "settings: ${c.settings.value}")
        assertFalse(c.settings.value.toString().contains(token))

        val id = ride(c)
        await("queued") { c.queued() == listOf(id) }
        Thread.sleep(300)

        val leaks = mutableListOf<String>()

        fun scan(
            what: String,
            file: File,
        ) {
            if (!file.isFile) return
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.ISO_8859_1)
            if (text.contains(token)) leaks += "$what: ${file.name} holds the token"
            if (text.contains("private-host")) leaks += "$what: ${file.name} holds the address"
        }
        directory.walkTopDown().forEach { scan("rides", it) }
        scan("outbox", outboxFile)
        File(extra, "ble-secret").walkTopDown().forEach { scan("ble logs", it) }
        assertEquals(emptyList<String>(), leaks)
        for (line in ShadowLog.getLogs()) {
            assertFalse("a log line holds the token: ${line.msg}", line.msg.contains(token))
            assertFalse(line.throwable?.stackTraceToString()?.contains(token) ?: false)
        }
    }

    @Test
    fun theAppAsksForNoNetworkAndAllowsNoBackup() {
        val packageName = context.packageName
        val packages = context.packageManager
        val info = packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse("no INTERNET permission: $requested", Manifest.permission.INTERNET in requested)
        assertFalse(requested.toString(), "android.permission.ACCESS_NETWORK_STATE" in requested)
        val flags = packages.getApplicationInfo(packageName, 0).flags
        assertEquals(
            "backups are off, so the token is never copied off the tablet",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }
}
