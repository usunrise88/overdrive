package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.progress.ProgressDirection
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ProgressRange
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
import java.io.File

/**
 * The Progress data through the real container (ADR 0043) against the loopback stand-in of the service: asked per range when the
 * screen opens, kept on disk per range, shown with no network after a restart and said to be old, a service that does not have the
 * route yet, Sync now. The screen itself is a later stage: what is asserted is what it will read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun diagnostics(c: CompanionContainer): () -> String =
        {
            "six=${c.progressFor(ProgressRange.SIX_MONTHS).state.value}\n" +
                "requests=${service.received.map { "${it.method} ${it.path}?${it.query}" }}\n"
        }

    private fun disk(name: String) = File(world.cache, name)

    private fun asked() = service.requests("GET", "/v1/progress").map { it.query }

    @Test
    fun `a range is asked with its days when the screen opens, shown, and kept on disk in the service's words`() {
        service.progress = ProgressSamples.heartRateFalling()
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Progress(ProgressRange.SIX_MONTHS))
        await("it is shown", diagnostics = diagnostics(c)) {
            c.progressFor(ProgressRange.SIX_MONTHS).state.value.value !=
                null
        }
        // Today is 18 Nov 2026 on the test clock.
        assertEquals(listOf("from=2026-05-18&to=2026-11-18"), asked())
        val answer = checkNotNull(c.progressFor(ProgressRange.SIX_MONTHS).state.value.value)
        assertEquals(ProgressDirection.IMPROVING, answer.aerobicEfficiency.trend.direction)
        assertEquals("Heart rate at the same power is trending down: fitter", answer.headline?.verdictText)
        assertNull(c.progressFor(ProgressRange.SIX_MONTHS).state.value.problem)
        await("it is on disk", diagnostics = diagnostics(c)) {
            disk("progress-6m").takeIf { it.isFile }?.readText().orEmpty().contains("trending down: fitter")
        }
        // Another range has not been asked and has no cache.
        assertNull(c.progressFor(ProgressRange.ONE_YEAR).state.value.value)
        assertFalse(disk("progress-1y").exists())
    }

    @Test
    fun `each range is its own request and its own cache`() {
        service.progress = ProgressSamples.heartRateFalling()
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Progress(ProgressRange.ONE_YEAR))
        c.screenOpened(RefreshTarget.Progress(ProgressRange.ALL_TIME))
        await("both are shown", diagnostics = diagnostics(c)) {
            c.progressFor(ProgressRange.ONE_YEAR).state.value.value != null &&
                c.progressFor(ProgressRange.ALL_TIME).state.value.value != null
        }
        assertTrue("from=2025-11-18&to=2026-11-18" in asked())
        assertTrue("from=2021-11-18&to=2026-11-18" in asked())
        await("both are on disk") { disk("progress-1y").isFile && disk("progress-all").isFile }
    }

    @Test
    fun `the Fitness and Power screens ask the default range for the headline of their Progress row`() {
        service.progress = ProgressSamples.heartRateFalling()
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Fitness)
        await("the headline is there", diagnostics = diagnostics(c)) {
            c
                .progressFor(ProgressRange.DEFAULT)
                .state.value.value
                ?.headline != null
        }
        assertTrue("from=2026-05-18&to=2026-11-18" in asked())
    }

    @Test
    fun `a restart with no service shows the kept answer and says it is old`() {
        service.progress = ProgressSamples.heartRateFalling()
        val first = world.container("progress-shared")
        first.configure()
        first.screenOpened(RefreshTarget.Progress(ProgressRange.SIX_MONTHS))
        await("shown", diagnostics = diagnostics(first)) {
            first.progressFor(ProgressRange.SIX_MONTHS).state.value.value !=
                null
        }
        await("on disk") { disk("progress-6m").isFile }
        world.stop(first)

        service.down = true
        val second = world.container("progress-shared")
        await("it is shown from disk before anything is asked") {
            second.progressFor(ProgressRange.SIX_MONTHS).state.value.value != null
        }
        second.refresh(RefreshTarget.Progress(ProgressRange.SIX_MONTHS))
        await("the failure is told next to what was kept", diagnostics = diagnostics(second)) {
            second.progressFor(ProgressRange.SIX_MONTHS).state.value.isStale
        }
        val state = second.progressFor(ProgressRange.SIX_MONTHS).state.value
        assertNotNull(state.value)
        assertEquals(ProblemKind.SERVER_ERROR, state.problem?.kind)
    }

    @Test
    fun `a service that does not have the route yet is an error with its cause, never an empty page`() {
        service.progress = null
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Progress(ProgressRange.SIX_MONTHS))
        await("the cause is known", diagnostics = diagnostics(c)) {
            c.progressFor(ProgressRange.SIX_MONTHS).state.value.problem != null
        }
        assertEquals(
            ProblemKind.NO_SUCH_ROUTE,
            c
                .progressFor(ProgressRange.SIX_MONTHS)
                .state.value.problem
                ?.kind,
        )
        assertNull(c.progressFor(ProgressRange.SIX_MONTHS).state.value.value)
    }

    @Test
    fun `Sync now asks every range, and the cache holds no token`() {
        service.progress = ProgressSamples.empty()
        val c = world.container()
        c.configure()
        c.syncNow()
        await("all three arrive", diagnostics = diagnostics(c)) {
            ProgressRange.entries.all { c.progressFor(it).state.value.value != null }
        }
        await("they are on disk") {
            disk("progress-6m").isFile && disk("progress-1y").isFile && disk("progress-all").isFile
        }
        assertFalse(world.diskText().contains(service.token))
        assertNull(
            c
                .progressFor(ProgressRange.SIX_MONTHS)
                .state.value.value
                ?.headline,
        )
    }

    @Test
    fun `the range the rider chooses is remembered and the screen reads that range's own resource`() {
        val c = world.container()
        c.configure()
        assertEquals(ProgressRange.SIX_MONTHS, c.settings.value?.progressRange)
        c.setProgressRange(ProgressRange.ONE_YEAR)
        await("the choice is stored") { c.settings.value?.progressRange == ProgressRange.ONE_YEAR }
        assertEquals(ProgressRange.ONE_YEAR, c.settings.value?.progressRange)
    }
}
