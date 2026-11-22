package app.overdrive.companion

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ProblemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Power skills and the Effort through the real container (docs/adr/0042) against the loopback stand-in of the service: asked with the
 * phone's local days, one resource and one cache per window, kept on disk, shown with no network after a restart and said to be old, a
 * service that does not have the route, Sync now. The screens are a later stage: what is asserted is what they will read.
 * Every wait is for a condition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChartsContainerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val world = ContainerWorld(context)
    private val service get() = world.service

    @After
    fun cleanUp() = world.close()

    private fun CompanionContainer.configure() = with(world) { configure() }

    private fun disk(name: String) = File(world.cache, name)

    private fun diagnostics(c: CompanionContainer): () -> String =
        { "requests=${service.received.map { "${it.method} ${it.path}?${it.query}" }}\n" }

    private fun serveSkills() {
        service.powerSkills = { from, to -> ChartData.skills(from, to, rideCount = if (from == to) 1 else 3) }
    }

    @Test
    fun `the eight weeks are asked with the phone's local days, shown, and kept on disk in the service's words`() {
        serveSkills()
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.PowerSkills(PowerSkillsWindow.EightWeeks))
        val resource = c.powerSkillsFor(PowerSkillsWindow.EightWeeks)
        await("the answer is shown", diagnostics = diagnostics(c)) { resource.state.value.value != null }
        assertEquals(3, checkNotNull(resource.state.value.value).window.rideCount)
        // 18 Nov 2026 is today on the test clock: the last 56 days.
        assertEquals(
            "from=2026-09-24&to=2026-11-18",
            service.requests("GET", "/v1/power-skills").first().query,
        )
        await("it is on disk", diagnostics = diagnostics(c)) {
            disk("power-skills/power-skills-8w").takeIf { it.isFile }?.readText().orEmpty().contains("2026-11-18")
        }
    }

    @Test
    fun `the Power tab refreshes the window the rider chose, and each window has its own answer`() {
        serveSkills()
        val c = world.container()
        c.configure()
        val custom = PowerSkillsWindow.Custom("2026-11-02", "2026-11-02")
        c.setPowerSkillsWindow(custom)
        assertEquals(custom, c.powerSkillsWindow.value)
        c.screenOpened(RefreshTarget.Power)
        val chosen = c.powerSkillsFor(custom)
        await("the custom window is shown", diagnostics = diagnostics(c)) { chosen.state.value.value != null }
        assertEquals(1, checkNotNull(chosen.state.value.value).window.rideCount)
        assertTrue(
            "the chosen range was asked for",
            service.requests("GET", "/v1/power-skills").any { it.query == "from=2026-11-02&to=2026-11-02" },
        )
        // The eight weeks were not fetched for it: they are another resource, still empty.
        assertNull(c.powerSkillsFor(PowerSkillsWindow.EightWeeks).state.value.value)
        c.setPowerSkillsWindow(PowerSkillsWindow.EightWeeks)
        assertEquals(PowerSkillsWindow.EightWeeks, c.powerSkillsWindow.value)
    }

    @Test
    fun `after a restart with no service the windows fetched before are shown and said to be old`() {
        serveSkills()
        service.effort = ChartData.effort("2026-11-16")
        val custom = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
        val first = world.container("charts-shared")
        first.configure()
        first.screenOpened(RefreshTarget.PowerSkills(PowerSkillsWindow.EightWeeks))
        first.screenOpened(RefreshTarget.PowerSkills(custom))
        first.screenOpened(RefreshTarget.Effort)
        await("all three are shown", diagnostics = diagnostics(first)) {
            first.powerSkillsFor(PowerSkillsWindow.EightWeeks).state.value.value != null &&
                first.powerSkillsFor(custom).state.value.value != null &&
                first.effort.state.value.value != null
        }
        await("they are on disk") {
            disk("power-skills/power-skills-8w").isFile &&
                disk("power-skills/power-skills-c-2026-11-02-2026-11-15").isFile &&
                disk("effort").isFile
        }
        world.stop(first)

        service.down = true
        val second = world.container("charts-shared")
        await("the standing ones are shown from disk before anything is asked") {
            second.powerSkillsFor(PowerSkillsWindow.EightWeeks).state.value.value != null &&
                second.effort.state.value.value != null
        }
        // A custom range is read from its cache when first asked for.
        await("the custom range too") { second.powerSkillsFor(custom).state.value.value != null }
        second.refresh(RefreshTarget.PowerSkills(PowerSkillsWindow.EightWeeks))
        second.refresh(RefreshTarget.Effort)
        await("the failure is told next to what was kept", diagnostics = diagnostics(second)) {
            second.powerSkillsFor(PowerSkillsWindow.EightWeeks).state.value.isStale && second.effort.state.value.isStale
        }
        assertEquals(6, checkNotNull(second.effort.state.value.value).weeks.size)
    }

    @Test
    fun `the effort is asked with the Fitness tab and with Sync now, for a year of weeks`() {
        serveSkills()
        service.effort = ChartData.effort("2026-11-16")
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.Fitness)
        await("the effort is shown", diagnostics = diagnostics(c)) { c.effort.state.value.value != null }
        assertEquals("weeks=52", service.requests("GET", "/v1/effort").first().query)
        val before = service.requests("GET", "/v1/effort").size
        c.syncNow()
        await("Sync now asked again", diagnostics = diagnostics(c)) {
            service.requests("GET", "/v1/effort").size >
                before
        }
        await("and it asked the standing power skills windows", diagnostics = diagnostics(c)) {
            val queries = service.requests("GET", "/v1/power-skills").map { it.query }
            "from=2026-09-24&to=2026-11-18" in queries && "from=2023-11-19&to=2026-11-18" in queries
        }
    }

    @Test
    fun `a service that does not have the routes yet is an error with its cause, never an empty chart`() {
        service.powerSkills = null
        service.effort = null
        val c = world.container()
        c.configure()
        c.screenOpened(RefreshTarget.PowerSkills(PowerSkillsWindow.EightWeeks))
        c.screenOpened(RefreshTarget.Effort)
        await("the causes are known", diagnostics = diagnostics(c)) {
            c.powerSkillsFor(PowerSkillsWindow.EightWeeks).state.value.problem != null &&
                c.effort.state.value.problem != null
        }
        assertEquals(
            ProblemKind.NO_SUCH_ROUTE,
            c
                .powerSkillsFor(PowerSkillsWindow.EightWeeks)
                .state.value.problem
                ?.kind,
        )
        assertEquals(
            ProblemKind.NO_SUCH_ROUTE,
            c.effort.state.value.problem
                ?.kind,
        )
        assertNull(c.effort.state.value.value)
    }

    @Test
    fun `the power profile table goes on the profile and to the service, and the skills are asked again`() {
        serveSkills()
        val c = world.container()
        c.configure()
        await("the profile", diagnostics = diagnostics(c)) { c.profile.state.value.profile != null }
        assertNull(
            "men's is the default and is not written",
            c.profile.state.value.profile
                ?.powerProfileTable,
        )
        c.screenOpened(RefreshTarget.PowerSkills(PowerSkillsWindow.EightWeeks))
        await("the first answer", diagnostics = diagnostics(c)) {
            service.requests("GET", "/v1/power-skills").isNotEmpty()
        }
        val before = service.requests("GET", "/v1/power-skills").size
        c.setPowerProfileTable(PowerProfileTable.WOMEN)
        assertEquals(
            PowerProfileTable.WOMEN,
            c.profile.state.value.profile
                ?.powerProfileTable,
        )
        await("the service has it", diagnostics = diagnostics(c)) {
            service.profile?.powerProfileTable ==
                PowerProfileTable.WOMEN
        }
        await("the skills were asked again", diagnostics = diagnostics(c)) {
            service.requests("GET", "/v1/power-skills").size > before
        }
    }
}
