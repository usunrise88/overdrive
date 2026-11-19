package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.companion.model.FitShown
import app.overdrive.companion.share.SharedRead
import app.overdrive.core.outdoor.ActivityImportReason
import app.overdrive.core.outdoor.FitOutcome
import app.overdrive.core.outdoor.FitRefusal
import app.overdrive.core.remote.ProblemKind
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
 * The outdoor ride through the real container (ADR 0028): a shared file goes to the loopback stand-in of the service with its bytes and the
 * FIT type, is kept on disk when the service cannot be reached and sent by Sync now, the retry, the app opening and a restart, each answer
 * of the service reaches the rider in its own words, and the token and the file stay private. Real threads, real files, real sockets.
 *
 * **Every wait is for a condition**; where a test then closes the container or reads a file it waits for the disk too, and what is asserted
 * is what the rider sees (the screen's state, the files, the service's holdings), not how many requests it took to get there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FitContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service
    private val files = FakeSharedFiles()
    private val network = SwitchableTransport()

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun container(
        name: String = "fit-settings",
        moving: Boolean = false,
        scheduler: CountingScheduler? = null,
    ): CompanionContainer =
        world.container(
            name,
            moving = moving,
            bytesTransport = network,
            sharedFiles = files,
            scheduler = scheduler ?: CountingScheduler(),
        )

    private fun diagnostics(c: CompanionContainer): () -> String =
        {
            "shown=${c.fitShown.value}\nstatus=${c.fitStatus.value}\n" +
                "service=${service.fitFiles.size} files, disk=${outbox().map { it.name }}\n"
        }

    private fun outbox(folder: String = "fit-outbox"): List<File> =
        File(world.cache, folder).listFiles { f -> f.isFile && f.name.endsWith(".fit") }.orEmpty().toList()

    private fun parked(): List<File> = outbox("fit-outbox/parked")

    private fun CompanionContainer.awaitDone(
        what: String,
        matches: (FitOutcome) -> Boolean,
    ): FitOutcome {
        await(
            what,
            diagnostics = diagnostics(this),
        ) { (fitShown.value as? FitShown.Done)?.outcome?.let(matches) == true }
        return (fitShown.value as FitShown.Done).outcome
    }

    // region the share

    @Test
    fun `a shared file reaches the service with its bytes and the FIT type, and the ride is in the list`() {
        val c = container()
        c.configure()
        val fit = SyntheticFit.bytes(1)
        c.shareFit(files.put("ride.fit", fit))
        val outcome = c.awaitDone("it is imported") { it is FitOutcome.Imported }
        assertTrue(outcome is FitOutcome.Imported)
        assertEquals(1, service.fitFiles.size)
        assertArrayEquals("the service has the file as it was shared", fit, service.fitFiles.values.single())
        val posts = service.requests("POST", "/v1/activities/fit")
        assertTrue(posts.all { it.contentType == "application/vnd.ant.fit" })
        assertTrue(posts.all { it.authorization == "Bearer ${service.token}" })
        await("nothing waits on the phone", diagnostics = diagnostics(c)) {
            c.fitStatus.value.waiting == 0 && outbox().isEmpty()
        }
        c.screenOpened(RefreshTarget.Rides)
        await("the ride is in the list", diagnostics = diagnostics(c)) {
            c.rides.view.value.list.items
                .any { it.sport == "cycling-outdoor" }
        }
    }

    @Test
    fun `the same file again is already imported and adds nothing`() {
        val c = container()
        c.configure()
        val file = files.put("ride.fit", SyntheticFit.bytes(2))
        c.shareFit(file)
        c.awaitDone("imported") { it is FitOutcome.Imported }
        c.shareFit(file)
        val second = c.awaitDone("told it is a duplicate") { it is FitOutcome.Duplicate }
        assertEquals(ActivityImportReason.SAME_FILE, (second as FitOutcome.Duplicate).reason)
        assertEquals(1, service.fitFiles.size)
        await("nothing waits", diagnostics = diagnostics(c)) { c.fitStatus.value.waiting == 0 && outbox().isEmpty() }
    }

    @Test
    fun `each answer of the service is told as what it is, and a refused file is set aside whole`() {
        val c = container()
        c.configure()
        val cases =
            listOf(
                Triple(422, "invalid_fit", FitRefusal.NOT_A_FIT_FILE),
                Triple(422, "unsupported_sport", FitRefusal.NOT_CYCLING),
                Triple(422, "empty_activity", FitRefusal.NO_RIDE),
                Triple(413, "payload_too_large", FitRefusal.TOO_LARGE),
                Triple(400, "invalid_query", FitRefusal.SERVICE_REFUSED),
            )
        for ((index, case) in cases.withIndex()) {
            val (status, code, expected) = case
            service.fitScript += status to """{"error":{"code":"$code","message":"No"}}"""
            c.shareFit(files.put("f$index.fit", SyntheticFit.bytes(10 + index)))
            val outcome = c.awaitDone("$code is told") { it is FitOutcome.Refused && it.refusal == expected }
            assertEquals(status, (outcome as FitOutcome.Refused).status)
            await("it is on disk, aside", diagnostics = diagnostics(c)) { parked().size == index + 1 }
        }
        assertTrue("nothing waits: a refusal is final", c.fitStatus.value.waiting == 0 && outbox().isEmpty())
        assertEquals(cases.map { it.third }, c.fitStatus.value.parked)
        assertTrue(service.fitFiles.isEmpty())
    }

    @Test
    fun `a file that is not FIT is refused on the phone and never reaches the service or the disk`() {
        val c = container()
        c.configure()
        c.shareFit(files.put("notes.fit", "a diary, not a ride".repeat(10).encodeToByteArray()))
        c.awaitDone("refused") { it == FitOutcome.Refused(FitRefusal.NOT_A_FIT_FILE) }
        c.shareFit(files.refuse("big.fit", SharedRead.Refused(FitRefusal.TOO_LARGE)))
        c.awaitDone("too large") { it == FitOutcome.Refused(FitRefusal.TOO_LARGE) }
        c.shareFit(files.refuse("gone.fit", SharedRead.Unreadable))
        await("unreadable", diagnostics = diagnostics(c)) { c.fitShown.value == FitShown.Unreadable }
        assertTrue(service.fitBodies.isEmpty())
        assertTrue(outbox().isEmpty() && parked().isEmpty())
    }

    // region offline

    @Test
    fun `with no network the file is kept, Sync now sends it when it is back, and the system's job is asked for`() {
        val scheduler = CountingScheduler()
        val c = container(scheduler = scheduler)
        c.configure()
        network.offline = true
        val fit = SyntheticFit.bytes(3)
        c.shareFit(files.put("ride.fit", fit))
        val outcome = c.awaitDone("it is kept") { it is FitOutcome.Waiting }
        assertEquals(ProblemKind.NO_NETWORK, (outcome as FitOutcome.Waiting).problem?.kind)
        await("it is on disk and counted", diagnostics = diagnostics(c)) {
            outbox().size == 1 && c.fitStatus.value.waiting == 1
        }
        await("the system is asked to send it when the network is back") { scheduler.fitFlushes.get() > 0 }
        assertTrue(service.fitFiles.isEmpty())

        network.offline = false
        c.syncNow()
        await("it is at the service", diagnostics = diagnostics(c)) { service.fitFiles.size == 1 }
        assertArrayEquals(fit, service.fitFiles.values.single())
        await("nothing waits", diagnostics = diagnostics(c)) { c.fitStatus.value.waiting == 0 && outbox().isEmpty() }
        await("the ride is in the list", diagnostics = diagnostics(c)) {
            c.rides.view.value.list.items
                .any { it.sport == "cycling-outdoor" }
        }
    }

    @Test
    fun `a file kept by one process is sent by the next when the app opens`() {
        val first = container("fit-shared")
        first.configure()
        network.offline = true
        first.shareFit(files.put("ride.fit", SyntheticFit.bytes(4)))
        first.awaitDone("kept") { it is FitOutcome.Waiting }
        await("it is on disk", diagnostics = diagnostics(first)) { outbox().size == 1 }
        world.stop(first)

        network.offline = false
        val second = container("fit-shared")
        second.appOpened()
        await("it is at the service", diagnostics = diagnostics(second)) { service.fitFiles.size == 1 }
        await("nothing waits", diagnostics = diagnostics(second)) {
            second.fitStatus.value.waiting == 0 &&
                outbox().isEmpty()
        }
    }

    @Test
    fun `a service that is down keeps the file, and the retry after the backoff sends it`() {
        val c = container(moving = true)
        c.configure()
        service.down = true
        c.shareFit(files.put("ride.fit", SyntheticFit.bytes(5)))
        val outcome = c.awaitDone("kept") { it is FitOutcome.Waiting }
        assertEquals(ProblemKind.SERVER_ERROR, (outcome as FitOutcome.Waiting).problem?.kind)
        await("it is on disk", diagnostics = diagnostics(c)) { outbox().size == 1 }
        service.down = false
        await("the retry sends it", diagnostics = diagnostics(c)) {
            service.fitFiles.size == 1 &&
                c.fitStatus.value.waiting == 0
        }
        await("the disk is empty", diagnostics = diagnostics(c)) { outbox().isEmpty() }
    }

    @Test
    fun `an answer that is lost keeps the file, and the second send is only a duplicate`() {
        val c = container(moving = true)
        c.configure()
        service.fitLoseAnswers = 1
        val fit = SyntheticFit.bytes(6)
        c.shareFit(files.put("ride.fit", fit))
        c.awaitDone("kept") { it is FitOutcome.Waiting }
        await("the file is at the service though the answer was lost", diagnostics = diagnostics(c)) {
            service.fitFiles.size ==
                1
        }
        c.syncNow()
        await("the second send is told apart and the file goes", diagnostics = diagnostics(c)) {
            c.fitStatus.value.waiting == 0 && outbox().isEmpty()
        }
        assertEquals("the ride is at the service once", 1, service.fitFiles.size)
        assertArrayEquals(fit, service.fitFiles.values.single())
    }

    @Test
    fun `a refused token keeps the file, says so, and Sync now sends it once the token is right`() {
        val c = container()
        c.configure()
        service.rejectToken = true
        c.shareFit(files.put("ride.fit", SyntheticFit.bytes(7)))
        val outcome = c.awaitDone("kept") { it is FitOutcome.Waiting }
        assertEquals(ProblemKind.TOKEN_REJECTED, (outcome as FitOutcome.Waiting).problem?.kind)
        await("it is on disk", diagnostics = diagnostics(c)) { outbox().size == 1 }
        assertTrue(service.fitFiles.isEmpty())
        service.rejectToken = false
        c.syncNow()
        await("it is at the service", diagnostics = diagnostics(c)) {
            service.fitFiles.size == 1 &&
                c.fitStatus.value.waiting == 0
        }
    }

    @Test
    fun `only so many files wait, and the next is told why`() {
        val c = container()
        c.configure()
        network.offline = true
        repeat(20) { c.shareFit(files.put("f$it.fit", SyntheticFit.bytes(100 + it))) }
        await("twenty files are kept", diagnostics = diagnostics(c)) {
            c.fitStatus.value.waiting == 20 &&
                outbox().size == 20
        }
        c.shareFit(files.put("one-more.fit", SyntheticFit.bytes(1)))
        c.awaitDone("refused") { it == FitOutcome.Refused(FitRefusal.QUEUE_FULL) }
        assertEquals(20, outbox().size)
    }

    // region privacy

    @Test
    fun `the token is in no file and no log line, and the ride's bytes are in no log line`() {
        val c = container()
        c.configure()
        val fit = SyntheticFit.bytes(8, size = 3000)
        service.fitScript += 422 to """{"error":{"code":"unsupported_sport","message":"Not cycling"}}"""
        c.shareFit(files.put("a.fit", fit))
        c.awaitDone("refused") { it is FitOutcome.Refused }
        network.offline = true
        c.shareFit(files.put("b.fit", SyntheticFit.bytes(9, size = 3000)))
        c.awaitDone("kept") { it is FitOutcome.Waiting }
        await("on disk", diagnostics = diagnostics(c)) { outbox().size == 1 && parked().size == 1 }
        val disk = world.diskText()
        assertFalse("the token is in the settings only, never in the cache", disk.contains(service.token))
        assertFalse(c.fitStatus.value.toString().contains(service.token))
        assertFalse(c.fitShown.value.toString().contains(service.token))
        val marker = String(fit, 12, 40, Charsets.ISO_8859_1)
        assertFalse(ShadowLog.getLogs().any { service.token in it.msg.orEmpty() || marker in it.msg.orEmpty() })
        // The kept file is whole, in the app's own folder.
        assertNotNull(outbox().single())
    }

    @Test
    fun `a kept file is in the app's private folder and in no shared or external place`() {
        val container =
            CompanionContainer(
                context,
                settingsName = "fit-private",
                bytesTransport = network,
                healthSource = FakeHealth(),
                permissionRequest = { emptySet() },
                sharedFiles = files,
                scheduler = CountingScheduler(),
            )
        try {
            container.updateService(service.baseUrl, service.token)
            await("settings stored") { container.settings.value?.isConfigured == true }
            network.offline = true
            container.shareFit(files.put("ride.fit", SyntheticFit.bytes(11)))
            await("kept", diagnostics = diagnostics(container)) { container.fitStatus.value.waiting == 1 }
            val own = File(context.filesDir, "cache/fit-outbox")
            await("it is on disk") { own.listFiles { f -> f.name.endsWith(".fit") }.orEmpty().size == 1 }
            val shared =
                listOfNotNull(context.cacheDir, context.getExternalFilesDir(null), context.externalCacheDir)
                    .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            assertTrue("nothing of the ride is outside the private files folder: $shared", shared.isEmpty())
        } finally {
            container.close()
            File(context.filesDir, "cache").deleteRecursively()
        }
    }
}
