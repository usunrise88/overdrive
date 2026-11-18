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
import org.junit.Assert.assertArrayEquals
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
 * The persistent outbox and the upload in the real container (spec §4.9, ADR 0012, ADR 0014): a ride that finishes, a ride
 * that is recovered and the rides already on disk at a start are all queued, nothing is lost or doubled by a restart,
 * nothing is sent while there is no address and token, a ride goes to a local stand-in of the service as soon as they are
 * set (no restart), a rejected token and **Send now** work, and the sync settings reach no log, no ride file and no backup.
 * Real threads, the simulated bike at 60 times speed, real files, real sockets on the loopback address.
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
            syncSettleMillis = 50,
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

        // Nothing is uploaded: no address and no token, so there is nowhere to go, and the ride stays.
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
    fun theAppAsksForInternetAndNothingElseOfTheNetworkAndAllowsNoBackup() {
        val packageName = context.packageName
        val packages = context.packageManager
        val info = packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue("INTERNET, to upload rides: $requested", Manifest.permission.INTERNET in requested)
        assertFalse(requested.toString(), "android.permission.ACCESS_NETWORK_STATE" in requested)
        val flags = packages.getApplicationInfo(packageName, 0).flags
        assertEquals(
            "backups are off, so the token is never copied off the tablet",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        assertTrue(
            "plain http:// is accepted for a bench (flagged in Settings), which Android 9 and later allow only with this",
            flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
    }

    // ---- The upload ---------------------------------------------------------------------------

    private val service = LocalService()

    @After
    fun stopService() {
        service.close()
    }

    private fun AppContainer.configure(
        url: String = service.baseUrl,
        token: String = service.token,
    ) {
        updateSettings { it.copy(syncUrl = url, syncToken = SecretText(token)) }
    }

    @Test
    fun aRideIsSentOnceAddressAndTokenAreSetWithNoRestartAndTheLastUploadSurvivesOne() {
        val c = container("upload")
        val id = ride(c)
        await("it waits: nothing to send to") { c.sync.status.value == SyncStatus.NotConfigured(1) }
        assertEquals(emptyList<LocalService.Received>(), service.received.toList())

        c.configure()
        await("the ride was sent and forgotten") { c.queued().isEmpty() && c.sync.status.value == SyncStatus.Idle(0) }
        assertEquals(
            listOf("/v1/rides/$id", "/v1/rides/$id/fit"),
            service.received.map { it.path },
        )
        assertTrue(service.received.all { it.authorization == "Bearer ${service.token}" })
        assertArrayEquals(RideStore(directory).recordFile(id).readBytes(), service.storedRecord(id))
        assertArrayEquals(RideStore(directory).fitFile(id).readBytes(), service.storedFit(id))
        val sentAt = checkNotNull(c.sync.report.value.lastSuccessMillis)
        assertTrue("the success is dated now: $sentAt", System.currentTimeMillis() - sentAt < 60_000)
        await("the time is kept on disk") { File(extra, "sync/last-upload").isFile }

        // A new process knows when the last upload was.
        c.close()
        containers.remove(c)
        val again = container("upload-again")
        assertEquals(sentAt, again.sync.report.value.lastSuccessMillis)
        Thread.sleep(300)
        assertEquals("nothing is sent twice", 2, service.received.size)
    }

    @Test
    fun aRejectedTokenIsSaidOnceAndAChangedTokenOrSendNowTriesAgain() {
        val c = container("rejected")
        val id = ride(c)
        // The ride's own kick (it is queued a moment after it is saved) is spent once the coordinator has found nowhere to upload.
        // Until then it could reach the wrong token and ask on its own, so the settings change below is the only thing that asks.
        await("it waits: nothing to send to") { c.sync.status.value == SyncStatus.NotConfigured(1) }
        c.configure(token = "tok-wrong-0123456789abcdef0123456789abcdef")
        await("the token is rejected") { c.sync.status.value == SyncStatus.TokenRejected(1) }
        assertEquals("one request, then silence", 1, service.received.size)
        c.sendNow()
        await("send now asked again") { service.received.size == 2 }
        await("and it is rejected again") { c.sync.status.value == SyncStatus.TokenRejected(1) }
        Thread.sleep(300)
        assertEquals("nothing else asks by itself", 2, service.received.size)

        // The right token, typed: tried again without a restart.
        c.configure()
        await("the ride went") { c.queued().isEmpty() }
        assertNotNull(service.storedFit(id))
    }

    @Test
    fun sendNowAfterTheServerCameBackSendsTheQueue() {
        val c = container("sendnow")
        val id = ride(c)
        service.down = true
        c.configure()
        await("it is waiting to retry") { c.sync.status.value is SyncStatus.WaitingToRetry }
        assertEquals(
            "Service error (503)",
            (c.sync.status.value as SyncStatus.WaitingToRetry).reason,
        )
        assertEquals(listOf(id), c.queued())
        service.down = false
        c.sendNow()
        await("the ride was sent at once, not after the backoff") { c.queued().isEmpty() }
    }

    @Test
    fun theTokenReachesNoLogNoStatusAndNoRideFileEvenWhileUploading() {
        val token = service.token
        ShadowLog.clear()
        val c = container("upload-secret")
        service.down = true
        c.configure()
        val id = ride(c)
        await("it fails and waits") { c.sync.status.value is SyncStatus.WaitingToRetry }
        service.down = false
        c.sendNow()
        await("sent") { c.queued().isEmpty() }
        assertTrue(
            "the sync's own log lines are there",
            ShadowLog.getLogs().any { it.msg.startsWith("Sync: WaitingToRetry") },
        )

        for (line in ShadowLog.getLogs()) {
            assertFalse("a log line holds the token: ${line.msg}", line.msg.contains(token))
            assertFalse(line.throwable?.stackTraceToString()?.contains(token) ?: false)
            assertFalse("a log line holds the address: ${line.msg}", line.msg.contains(service.baseUrl))
        }
        assertFalse(
            c.sync.status.value
                .toString()
                .contains(token),
        )
        assertFalse(
            c.sync.report.value
                .toString()
                .contains(token),
        )
        val stored = RideStore(directory)
        for (file in listOf(stored.recordFile(id), stored.fitFile(id), outboxFile, File(extra, "sync/last-upload"))) {
            assertFalse(file.name, String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        }
    }
}
