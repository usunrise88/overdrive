package app.overdrive.companion.shots

import app.overdrive.companion.ChartScreenSamples
import app.overdrive.companion.Samples
import app.overdrive.companion.model.EffortUi
import app.overdrive.companion.model.FitnessUi
import app.overdrive.companion.model.PickerState
import app.overdrive.companion.model.PowerSkillsUi
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.ProblemWords
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.ui.FitnessView
import app.overdrive.companion.ui.PowerActions
import app.overdrive.companion.ui.PowerSheet
import app.overdrive.companion.ui.PowerSkillsHost
import app.overdrive.companion.ui.PowerView
import app.overdrive.companion.ui.RangeActions
import app.overdrive.companion.ui.SettingsActions
import app.overdrive.companion.ui.SettingsUiState
import app.overdrive.companion.ui.SettingsView
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.uikit.chart.ChartSelection
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Power skills and Effort cards in every state of design/companion.md §4.14 and §4.15, rendered from the synthetic answers in the
 * numbers of the mock-ups (`design/mockups/companion/png/skills--*.png`, `effort--*.png`) into `build/shots/charts--*.png`. Looked at and
 * compared with the mock-ups, never committed, never compared to goldens.
 */
class ChartsShotsTest : ShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val none = {}
    private val range = RangeActions(none, none, none, {})
    private val powerActions = PowerActions(range, {}, {}, {})
    private val today = LocalDate.parse(ChartScreenSamples.TODAY)
    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
    private val rideDays =
        listOf(
            "2026-11-02",
            "2026-11-03",
            "2026-11-05",
            "2026-11-07",
            "2026-11-09",
            "2026-11-10",
            "2026-11-12",
            "2026-11-14",
            "2026-11-16",
        ).map(LocalDate::parse)

    // region power skills

    private fun metrics(): MetricsResponse =
        Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY), suggestion = null, ftp = 250)

    private fun power(problem: ServiceProblem? = null) =
        PowerUi
            .of(
                fresh(metrics()).copy(problem = problem),
                ProfileState(ready = true, profile = Samples.profile()),
                FtpAnswers(),
                MetricsRange.SIX_MONTHS,
                words,
                host,
            )

    private fun host(
        response: PowerSkillsResponse?,
        window: PowerSkillsWindow = PowerSkillsWindow.EightWeeks,
        problem: ServiceProblem? = null,
    ): PowerSkillsHost {
        val state =
            if (response == null) {
                ResourceState<PowerSkillsResponse>(loaded = true, asked = true, problem = problem)
            } else {
                fresh(response).copy(problem = problem)
            }
        return PowerSkillsHost(PowerSkillsUi.of(state, window, words), rideDays, today, words) {}
    }

    private fun skillsShot(
        name: String,
        skills: PowerSkillsHost,
        sheet: PowerSheet? = null,
        table: app.overdrive.companion.model.ValuesTable? = null,
        ui: app.overdrive.companion.model.ScreenUi<app.overdrive.companion.model.PowerContent> = power(),
    ) = shot(name) {
        PowerView(
            ui,
            MetricsRange.SIX_MONTHS,
            powerActions,
            skills = skills,
            initialSheet = sheet,
            initialTable = table,
        )
    }

    @Test
    fun skillsEightWeeks() = skillsShot("charts--skills", host(ChartScreenSamples.skills()))

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun skills360() = skillsShot("charts--skills-360", host(ChartScreenSamples.skills()))

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun skills360LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        skillsShot("charts--skills-360-large", host(ChartScreenSamples.skills()))
    }

    @Test
    fun skillsLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        skillsShot("charts--skills-large", host(ChartScreenSamples.skills()))
    }

    @Test
    fun skillsAxisSheet() = skillsShot("charts--skills-axis", host(ChartScreenSamples.skills()), PowerSheet.Axis(5))

    @Test
    fun skillsSkillSheet() =
        skillsShot("charts--skills-skill", host(ChartScreenSamples.skills()), PowerSheet.Skill(PowerSkillId.CLIMB))

    @Test
    fun skillsNoWeight() = skillsShot("charts--skills-noweight", host(ChartScreenSamples.skills(weight = false)))

    @Test
    fun skillsCustomWithGaps() {
        val window = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
        skillsShot("charts--skills-custom", host(ChartScreenSamples.customSkills(), window))
    }

    @Test
    fun skillsAllTime() =
        skillsShot(
            "charts--skills-all-time",
            host(ChartScreenSamples.skills(window = ChartScreenSamples.bestEver), PowerSkillsWindow.AllTime),
        )

    @Test
    fun skillsPicker() {
        val window = host(ChartScreenSamples.skills())
        skillsShot(
            "charts--skills-picker",
            window,
            PowerSheet.Picker(
                PickerState(YearMonth.of(2026, 11), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 15)),
            ),
        )
    }

    @Test
    fun skillsValues() {
        val skills = host(ChartScreenSamples.skills())
        skillsShot("charts--skills-values", skills, table = skills.ui.table)
    }

    @Test
    fun skillsLoading() = skillsShot("charts--skills-loading", host(null))

    @Test
    fun skillsNotLoaded() = skillsShot("charts--skills-not-loaded", host(null, problem = offline))

    @Test
    fun skillsNoRides() = skillsShot("charts--skills-no-rides", host(ChartScreenSamples.noRidesEver()))

    @Test
    fun skillsFewDurations() = skillsShot("charts--skills-few", host(ChartScreenSamples.fewDurations()))

    @Test
    fun skillsEmptyCustomRange() {
        val window = PowerSkillsWindow.Custom("2026-08-02", "2026-08-09")
        skillsShot(
            "charts--skills-empty-range",
            host(ChartScreenSamples.emptyWindow("2026-08-02", "2026-08-09"), window),
        )
    }

    @Test
    fun skillsOffline() {
        val skills = host(ChartScreenSamples.skills(), problem = offline)
        val base = power()
        skillsShot(
            "charts--skills-offline",
            skills,
            ui = base.copy(status = ProblemWords.of(offline, host, "06:10")),
        )
    }

    // region effort

    private val fitnessMetrics: MetricsResponse = Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY))

    private fun fitnessShot(
        name: String,
        response: EffortResponse,
        selected: Int? = null,
        problem: ServiceProblem? = null,
        table: Boolean = false,
    ) {
        val ui = FitnessUi.of(fresh(fitnessMetrics), MetricsRange.SIX_MONTHS, words, host)
        val effort = EffortUi.of(fresh(response).copy(problem = problem), words)
        val selection = ChartSelection().also { if (selected != null) it.select(selected.toDouble()) }
        val content = (effort.body as? app.overdrive.companion.model.EffortBody.Content)?.content
        shot(name) {
            FitnessView(
                if (problem != null) ui.copy(status = ProblemWords.of(problem, host, "06:10")) else ui,
                MetricsRange.SIX_MONTHS,
                range,
                effort = effort,
                effortSelection = selection,
                initialSheet = if (table) content?.table else null,
            )
        }
    }

    @Test
    fun effortAtRest() = fitnessShot("charts--effort", ChartScreenSamples.effort())

    @Test
    fun effortSelectedWeek() = fitnessShot("charts--effort-selected", ChartScreenSamples.effort(), selected = 10)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun effort360LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        fitnessShot("charts--effort-360-large", ChartScreenSamples.effort(), selected = 10)
    }

    @Test
    fun effortLearning() = fitnessShot("charts--effort-learning", ChartScreenSamples.effort(learning = true))

    @Test
    fun effortNoEffort() = fitnessShot("charts--effort-none", ChartScreenSamples.noEffort())

    @Test
    fun effortNothingYetThisWeek() = fitnessShot("charts--effort-nothing-yet", ChartScreenSamples.nothingYetThisWeek())

    @Test
    fun effortActivityWithoutHeartRate() =
        fitnessShot("charts--effort-no-heart-rate", ChartScreenSamples.withoutHeartRate())

    @Test
    fun effortOffline() = fitnessShot("charts--effort-offline", ChartScreenSamples.effort(), problem = offline)

    @Test
    fun effortValues() = fitnessShot("charts--effort-values", ChartScreenSamples.effort(weeks = 30), table = true)

    @Test
    fun settingsPowerTable() =
        shot("charts--settings") {
            SettingsView(
                SettingsUiState(
                    address = "overdrive.example.net",
                    addressMistake = null,
                    notEncrypted = false,
                    token = "tok-3f9a7c1e5b2d4a6f",
                    tokenRefused = false,
                    lastSyncValue = "Today 07:40",
                    lastSyncNote = "Received your latest figures.",
                    syncing = false,
                    version = "0.4.1",
                    powerTable = PowerProfileTable.WOMEN,
                ),
                SettingsActions({}, {}, {}, {}, {}),
            )
        }
}
