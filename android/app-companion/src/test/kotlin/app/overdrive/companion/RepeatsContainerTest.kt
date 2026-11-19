package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.remote.ProblemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The repeated sessions through the real container (ADR 0028) against the loopback stand-in of the service: asked when their view opens,
 * kept on disk, shown with no network after a restart and said to be old, a service that does not have the route, a session that is gone.
 * Every wait is for a condition, and what is asserted is what the rider sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepeatsContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun diagnostics(c: CompanionContainer): () -> String =
        { "repeats=${c.repeats.state.value}\nrequests=${service.received.map { "${it.method} ${it.path}" }}\n" }

    private fun disk(name: String) = File(world.cache, name)

    private val detail = RepeatSamples.detail(listOf(150.0, 148.0, 149.0, 146.0, 144.0))

    @Test
    fun `the list is asked when the Repeats view opens, shown, and kept on disk in the service's words`() {
        service.repeats = RepeatSamples.twoSessions()
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Repeats)
        await("the list is shown", diagnostics = diagnostics(c)) { c.repeats.state.value.value != null }
        val list = checkNotNull(c.repeats.state.value.value)
        assertEquals(listOf(RepeatSamples.NAME, "Threshold 4×8"), list.repeats.map { it.name })
        assertNull(c.repeats.state.value.problem)
        await("it is on disk", diagnostics = diagnostics(c)) {
            disk("repeats").takeIf { it.isFile }?.readText().orEmpty().contains(RepeatSamples.HASH)
        }
    }

    @Test
    fun `a session opens, is kept by its hash, and a restart with no service shows it and says it is old`() {
        service.repeats = RepeatSamples.twoSessions()
        service.repeatDetails[RepeatSamples.HASH] = detail
        val first = world.container("repeats-shared")
        first.configure()
        first.screenOpened(RefreshTarget.RepeatDetail(RepeatSamples.HASH))
        await("the session is shown", diagnostics = diagnostics(first)) {
            first.repeatDetail(RepeatSamples.HASH).state.value.value != null
        }
        assertEquals(5, checkNotNull(first.repeatDetail(RepeatSamples.HASH).state.value.value).attempts.size)
        await("it is on disk under its hash") { disk("repeat-details/${RepeatSamples.HASH}").isFile }
        first.screenOpened(RefreshTarget.Repeats)
        await("the list is on disk too") { disk("repeats").isFile }
        world.stop(first)

        service.down = true
        val second = world.container("repeats-shared")
        await("both are shown from disk before anything is asked") {
            second.repeats.state.value.value != null &&
                second.repeatDetail(RepeatSamples.HASH).state.value.value != null
        }
        second.refresh(RefreshTarget.RepeatDetail(RepeatSamples.HASH))
        second.refresh(RefreshTarget.Repeats)
        await("the failure is told next to what was kept", diagnostics = diagnostics(second)) {
            second.repeatDetail(RepeatSamples.HASH).state.value.isStale && second.repeats.state.value.isStale
        }
        assertEquals(5, checkNotNull(second.repeatDetail(RepeatSamples.HASH).state.value.value).attempts.size)
    }

    @Test
    fun `a service that does not have the route yet is an error with its cause, never an empty list`() {
        service.repeats = null
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Repeats)
        await("the cause is known", diagnostics = diagnostics(c)) { c.repeats.state.value.problem != null }
        assertEquals(
            ProblemKind.NO_SUCH_ROUTE,
            c.repeats.state.value.problem
                ?.kind,
        )
        assertNull(c.repeats.state.value.value)
    }

    @Test
    fun `a session the service no longer has keeps what the phone had, with the cause`() {
        service.repeatDetails[RepeatSamples.HASH] = detail
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.RepeatDetail(RepeatSamples.HASH))
        await("shown", diagnostics = diagnostics(c)) { c.repeatDetail(RepeatSamples.HASH).state.value.value != null }
        service.repeatDetails.clear()
        c.refresh(RefreshTarget.RepeatDetail(RepeatSamples.HASH))
        await("the cause is told", diagnostics = diagnostics(c)) {
            c.repeatDetail(RepeatSamples.HASH).state.value.isStale
        }
        assertEquals(
            ProblemKind.NO_SUCH_ROUTE,
            c
                .repeatDetail(RepeatSamples.HASH)
                .state.value.problem
                ?.kind,
        )
    }

    @Test
    fun `Sync now asks for the list, and the cache holds no token`() {
        service.repeats = RepeatSamples.twoSessions()
        val c = world.container()
        c.configure()
        c.syncNow()
        await("the list arrives", diagnostics = diagnostics(c)) { c.repeats.state.value.value != null }
        await("it is on disk") { disk("repeats").isFile }
        assertFalse(world.diskText().contains(service.token))
        assertTrue(c.repeats.state.value.fetchedAtMillis != null)
    }
}
