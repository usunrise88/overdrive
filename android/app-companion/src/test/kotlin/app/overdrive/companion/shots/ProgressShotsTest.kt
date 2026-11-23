package app.overdrive.companion.shots

import app.overdrive.companion.ProgressSamples
import app.overdrive.companion.ProgressScreenSamples
import app.overdrive.companion.Samples
import app.overdrive.companion.model.FitnessUi
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.ProblemWords
import app.overdrive.companion.model.ProgressUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.ui.FitnessView
import app.overdrive.companion.ui.PowerActions
import app.overdrive.companion.ui.PowerView
import app.overdrive.companion.ui.ProgressActions
import app.overdrive.companion.ui.ProgressSelections
import app.overdrive.companion.ui.ProgressView
import app.overdrive.companion.ui.RangeActions
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ProgressRange
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Progress screen in every state of design/companion.md §4.16, rendered from the synthetic answers in the numbers of the mock-up
 * (`design/mockups/companion/png/progress--*.png`) into `build/shots/progress--*.png`. Looked at and compared with the mock-ups, never
 * committed, never compared to goldens.
 */
@Config(sdk = [35], qualifiers = "w412dp-h3000dp-xhdpi")
class ProgressShotsTest : ShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
    private val actions = ProgressActions({}, {}, {}, {}, {}, {})

    private fun day(date: String) = checkNotNull(PlanDates.epochDay(date)).toDouble()

    private fun shot(
        name: String,
        response: ProgressResponse?,
        range: ProgressRange = ProgressRange.SIX_MONTHS,
        problem: ServiceProblem? = null,
        othersCached: Boolean = false,
        selected: Map<String, Double> = emptyMap(),
        values: Boolean = false,
    ) {
        val state =
            if (response == null) {
                ResourceState<ProgressResponse>(loaded = true, asked = true, problem = problem)
            } else {
                fresh(response).copy(problem = problem)
            }
        val ui = ProgressUi.of(state, range, words, host, othersCached)
        val withStatus =
            if (problem != null && response != null) ui.copy(status = ProblemWords.of(problem, host, "06:10")) else ui
        val selections = ProgressSelections().also { all -> selected.forEach { (key, x) -> all.of(key).select(x) } }
        val table =
            if (values) {
                (
                    state.value
                        ?.let {
                            ProgressUi.content(it, range, words)
                        }?.groups
                        ?.get(1)
                        ?.cards
                        ?.first() as? app.overdrive.companion.model.ProgressChartUi
                )?.table
            } else {
                null
            }
        shot(name) { ProgressView(withStatus, range, actions, selections = selections, initialTable = table) }
    }

    private val full = ProgressScreenSamples.full()

    @Test
    fun sixMonths() = shot("progress--progress", full)

    @Test
    @Config(qualifiers = "w360dp-h3000dp-xhdpi")
    fun sixMonths360() = shot("progress--progress-360", full)

    @Test
    @Config(qualifiers = "w360dp-h3400dp-xhdpi")
    fun sixMonths360LargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        shot(
            "progress--progress-360-large",
            full,
            selected =
                mapOf(
                    "efficiency" to day("2026-09-07"),
                    "factor" to day("2026-06-01"),
                ),
        )
    }

    @Test
    fun selectedPoints() =
        shot(
            "progress--progress-selected",
            full,
            selected =
                mapOf(
                    "best" to day("2026-08-15"),
                    "efficiency" to day("2026-08-24"),
                    "factor" to day("2026-07-13"),
                    "weight" to day("2026-08-24"),
                ),
        )

    @Test
    fun oneYear() =
        shot("progress--progress-year", ProgressScreenSamples.full("2025-11-18", "2026-11-18"), ProgressRange.ONE_YEAR)

    @Test
    fun allTimeFiveYears() =
        shot("progress--progress-all", ProgressScreenSamples.full("2021-11-18", "2026-11-18"), ProgressRange.ALL_TIME)

    @Test
    fun allTimeShortHistory() =
        shot(
            "progress--progress-all-short",
            ProgressScreenSamples.full("2021-11-18", "2026-11-18", ProgressScreenSamples.Gaps(efficiencyWeeks = 40)),
            ProgressRange.ALL_TIME,
        )

    @Test
    fun values() = shot("progress--progress-values", full, values = true)

    @Test
    fun tooFewPoints() =
        shot("progress--few", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(efficiencyWeeks = 3)))

    @Test
    fun noWatchData() =
        shot("progress--no-watch", ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(resting = false)))

    @Test
    fun noWeight() =
        shot(
            "progress--no-weight",
            ProgressScreenSamples.full(gaps = ProgressScreenSamples.Gaps(weight = false, bestPowerWeightKnown = false)),
        )

    @Test
    fun offlineWithCache() = shot("progress--offline", full, problem = offline)

    @Test
    fun nothingAtAll() = shot("progress--nothing", ProgressSamples.empty())

    @Test
    fun loading() = shot("progress--loading", null)

    @Test
    fun notLoadedRange() = shot("progress--not-loaded", null, ProgressRange.ONE_YEAR, offline, othersCached = true)

    @Test
    fun errorNothingCached() = shot("progress--error", null, problem = offline)

    // region the entry row

    private fun metrics() = Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY), suggestion = null, ftp = 250)

    @Test
    fun entryOnFitness() {
        val ui =
            FitnessUi.of(
                fresh(Samples.metrics(MetricsRange.SIX_MONTHS.from(Samples.TODAY))),
                MetricsRange.SIX_MONTHS,
                words,
                host,
            )
        val entry = ProgressUi.entry(fresh(ProgressSamples.heartRateFalling()))
        shot("progress--progress-entry") {
            FitnessView(ui, MetricsRange.SIX_MONTHS, RangeActions({}, {}, {}, {}), progress = entry)
        }
    }

    @Test
    fun entryOnPowerWithoutHeadline() {
        val ui =
            PowerUi.of(
                fresh(metrics()),
                ProfileState(ready = true, profile = Samples.profile()),
                FtpAnswers(),
                MetricsRange.SIX_MONTHS,
                words,
                host,
            )
        val entry = ProgressUi.entry(ResourceState(loaded = true, asked = true))
        shot("progress--progress-entry-power") {
            PowerView(
                ui,
                MetricsRange.SIX_MONTHS,
                PowerActions(RangeActions({}, {}, {}, {}), {}, {}, {}),
                progress = entry,
            )
        }
    }
}
