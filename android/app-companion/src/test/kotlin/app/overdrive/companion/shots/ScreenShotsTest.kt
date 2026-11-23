package app.overdrive.companion.shots

import app.overdrive.companion.ConnectResult
import app.overdrive.companion.Samples
import app.overdrive.companion.WatchSamples
import app.overdrive.companion.model.ActivityWords
import app.overdrive.companion.model.FitnessUi
import app.overdrive.companion.model.PowerUi
import app.overdrive.companion.model.RideDetailUi
import app.overdrive.companion.model.RidesUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.model.TodayUi
import app.overdrive.companion.ui.ActivityView
import app.overdrive.companion.ui.ConnectView
import app.overdrive.companion.ui.FitnessView
import app.overdrive.companion.ui.PowerActions
import app.overdrive.companion.ui.PowerView
import app.overdrive.companion.ui.RangeActions
import app.overdrive.companion.ui.RideDetailActions
import app.overdrive.companion.ui.RideDetailView
import app.overdrive.companion.ui.RidesActions
import app.overdrive.companion.ui.RidesView
import app.overdrive.companion.ui.SettingsActions
import app.overdrive.companion.ui.SettingsUiState
import app.overdrive.companion.ui.SettingsView
import app.overdrive.companion.ui.TodayActions
import app.overdrive.companion.ui.TodayView
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.insights.InsightsResponse
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.InsightsList
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesList
import app.overdrive.core.remote.RidesView
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.uikit.chart.ChartSelection
import org.junit.Test
import app.overdrive.core.wellness.RecoveryState as ServiceState

/** Every screen and state of design/companion.md at 412 dp wide, from the synthetic answers, into build/shots. */
class ScreenShotsTest : ShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val none = {}
    private val range = RangeActions(none, none, none, {})

    private fun metrics(
        r: MetricsRange = MetricsRange.SIX_MONTHS,
        suggestion: Boolean = true,
        ftp: Int? = 250,
        noLoad: Boolean = false,
        withDrift: Boolean = true,
    ): MetricsResponse =
        Samples.metrics(
            r.from(Samples.TODAY),
            suggestion = if (suggestion) Samples.suggestion() else null,
            ftp = ftp,
            noLoad = noLoad,
            withDrift = withDrift,
        )

    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
    private val dns =
        ServiceProblem(ProblemKind.NAME_NOT_FOUND, "Cannot find the service address (DNS)", "UnknownHostException")
    private val tokenRefused = ServiceProblem.TokenRejected
    private val profile = ProfileState(ready = true, profile = Samples.profile())

    // region today

    private val todayActions = TodayActions(none, none, none, {}, {}, {}, {}, none, {})

    private val connected =
        HealthStatus(
            checked = true,
            access = HealthAccess(HealthDataType.entries.toSet(), history = true, background = true),
        )

    private fun recovery(
        state: ServiceState,
        nights: Int = 31,
    ) = fresh(WatchSamples.recovery(state, nights = nights))

    private fun today(
        recovery: ResourceState<RecoveryResponse> = ResourceState(loaded = true, asked = true),
        metrics: ResourceState<MetricsResponse> = fresh(metrics()),
        workouts: ResourceState<WorkoutsResponse> = fresh(Samples.workouts()),
        answers: FtpAnswers = FtpAnswers(),
        justUsed: Int? = null,
        insights: InsightsView = InsightsView(),
        watch: HealthStatus = connected,
    ) = TodayUi.of(
        recovery,
        metrics,
        workouts,
        RidesView(),
        profile,
        answers,
        justUsed,
        Samples.TODAY,
        words,
        host,
        insights,
        watch,
    )

    private fun notes(response: InsightsResponse = WatchSamples.insights()) =
        InsightsView(fresh(response), InsightsList(response.insights))

    @Test
    fun todayReady() =
        shot("today--ready") {
            TodayView(
                today(recovery(ServiceState.READY), metrics = fresh(metrics(suggestion = false)), insights = notes()),
                todayActions,
            )
        }

    @Test
    fun todayCautionOfflineWithSuggestion() =
        shot("today--caution") {
            TodayView(
                today(
                    recovery(ServiceState.CAUTION),
                    metrics = fresh(metrics()).copy(problem = offline),
                    workouts = fresh(Samples.workouts()).copy(problem = offline),
                ),
                todayActions,
            )
        }

    @Test
    fun todayRest() = shot("today--rest") { TodayView(today(recovery(ServiceState.REST)), todayActions) }

    @Test
    fun todayLearning() =
        shot("today--learning") {
            TodayView(
                today(
                    recovery(ServiceState.LEARNING, nights = 9),
                    workouts = fresh(Samples.workouts(withToday = false)),
                    metrics = fresh(metrics(suggestion = false)),
                ),
                todayActions,
            )
        }

    @Test
    fun todayWithoutTheWatch() =
        shot("today--no-watch") { TodayView(today(watch = HealthStatus(checked = true)), todayActions) }

    @Test
    fun todayNoReading() = shot("today--no-reading") { TodayView(today(recovery(ServiceState.NO_DATA)), todayActions) }

    @Test
    fun todayNoteBeforeItIsWritten() =
        shot("today--note-waiting") {
            TodayView(
                today(recovery(ServiceState.READY), insights = notes(WatchSamples.insights(withToday = false))),
                todayActions,
            )
        }

    @Test
    fun todayAlertAndTopic() =
        shot("today--alert-note") {
            val response = WatchSamples.insights(withToday = false)
            TodayView(
                today(
                    recovery(ServiceState.CAUTION),
                    insights = notes(response.copy(insights = response.insights.drop(1))),
                    watch = HealthStatus(checked = true),
                ),
                todayActions,
            )
        }

    @Test
    fun todayWatchReadStopped() =
        shot("today--watch-stopped") {
            TodayView(
                today(
                    recovery(ServiceState.READY),
                    watch = connected.copy(firstReadDone = true, lastReadMillis = TestClock.now - 40 * 3_600_000),
                ),
                todayActions,
            )
        }

    @Test
    fun todayAfterUse() = shot("today--after-use") { TodayView(today(justUsed = 256), todayActions) }

    @Test
    fun todayKeepQueued() =
        shot("today--keep-queued") {
            TodayView(today(answers = FtpAnswers(setOf(7), waiting = 1)), todayActions)
        }

    @Test
    fun todayLoading() =
        shot("today--loading") {
            TodayView(
                today(
                    metrics = ResourceState(loaded = true, refreshing = true),
                    workouts = ResourceState(loaded = true, refreshing = true),
                ),
                todayActions,
            )
        }

    @Test
    fun todayError() =
        shot("today--error") {
            val failed = ResourceState<MetricsResponse>(loaded = true, problem = dns, asked = true)
            TodayView(
                today(metrics = failed, workouts = ResourceState(loaded = true, problem = dns, asked = true)),
                todayActions,
            )
        }

    @Test
    fun todayRefreshing() =
        shot("today--refreshing") { TodayView(today(metrics = fresh(metrics()).copy(refreshing = true)), todayActions) }

    // region fitness

    private fun fitness(
        r: MetricsRange = MetricsRange.SIX_MONTHS,
        state: ResourceState<MetricsResponse> = fresh(metrics(r)),
    ) = FitnessUi.of(state, r, words, host)

    @Test
    fun fitnessSixMonths() = shot("fitness--six-months") { FitnessView(fitness(), MetricsRange.SIX_MONTHS, range) }

    @Test
    fun fitnessYear() =
        shot("fitness--year") { FitnessView(fitness(MetricsRange.ONE_YEAR), MetricsRange.ONE_YEAR, range) }

    @Test
    fun fitnessSixWeeksSelected() {
        val selection = ChartSelection()
        val ui = fitness(MetricsRange.SIX_WEEKS)
        selection.select(
            ui.let { (it.body as app.overdrive.companion.model.Body.Content).value.fitness.spec.snapXs[9] },
        )
        shot("fitness--six-weeks-selected") { FitnessView(ui, MetricsRange.SIX_WEEKS, range, selection = selection) }
    }

    @Test
    fun fitnessValuesSheet() {
        val ui = fitness()
        val table = (ui.body as app.overdrive.companion.model.Body.Content).value.fitness.table
        shot("fitness--values") { FitnessView(ui, MetricsRange.SIX_MONTHS, range, initialSheet = table) }
    }

    @Test
    fun fitnessLoading() =
        shot("fitness--loading") {
            FitnessView(
                fitness(state = ResourceState(loaded = true, refreshing = true)),
                MetricsRange.SIX_MONTHS,
                range,
            )
        }

    @Test
    fun fitnessError() =
        shot("fitness--error") {
            FitnessView(
                fitness(state = ResourceState(loaded = true, problem = dns, asked = true)),
                MetricsRange.SIX_MONTHS,
                range,
            )
        }

    @Test
    fun fitnessStaleOffline() =
        shot("fitness--stale") {
            FitnessView(fitness(state = fresh(metrics()).copy(problem = offline)), MetricsRange.SIX_MONTHS, range)
        }

    @Test
    fun fitnessEmpty() =
        shot(
            "fitness--empty",
        ) { FitnessView(fitness(state = fresh(metrics(noLoad = true))), MetricsRange.SIX_MONTHS, range) }

    // region power

    private val powerActions = PowerActions(range, {}, {}, {})

    private fun power(
        state: ResourceState<MetricsResponse>,
        r: MetricsRange = MetricsRange.SIX_MONTHS,
        answers: FtpAnswers = FtpAnswers(),
    ) = PowerUi.of(state, profile, answers, r, words, host)

    @Test
    fun powerWithSuggestion() =
        shot("power--suggestion") {
            PowerView(power(fresh(metrics())), MetricsRange.SIX_MONTHS, powerActions)
        }

    @Test
    fun powerFiveMinutesSelected() {
        val selection = ChartSelection().also { it.select(300.0) }
        shot("power--selected") {
            PowerView(
                power(fresh(metrics(suggestion = false))),
                MetricsRange.SIX_MONTHS,
                powerActions,
                curve = selection,
            )
        }
    }

    @Test
    fun powerNoFtp() =
        shot("power--no-ftp") {
            PowerView(
                PowerUi.of(
                    fresh(metrics(suggestion = false, ftp = null)),
                    ProfileState(ready = true),
                    FtpAnswers(),
                    MetricsRange.SIX_MONTHS,
                    words,
                    host,
                ),
                MetricsRange.SIX_MONTHS,
                powerActions,
            )
        }

    @Test
    fun powerEmptyAndError() {
        shot("power--empty") {
            PowerView(
                power(fresh(metrics(noLoad = true, ftp = null, suggestion = false))),
                MetricsRange.SIX_MONTHS,
                powerActions,
            )
        }
    }

    @Test
    fun powerTokenRefusedStale() =
        shot("power--token-refused") {
            PowerView(power(fresh(metrics()).copy(problem = tokenRefused)), MetricsRange.SIX_MONTHS, powerActions)
        }

    // region rides

    private val ridesActions = RidesActions(none, none, none, {}, none)

    private fun rides(
        state: ResourceState<app.overdrive.core.metrics.RidesResponse> = fresh(Samples.ridesPage()),
        items: List<app.overdrive.core.metrics.RideItem> = Samples.rideItems(),
    ) = RidesUi.of(RidesView(state, RidesList(items)), words, host)

    @Test
    fun ridesList() = shot("rides--list") { RidesView(rides(), ridesActions) }

    @Test
    fun ridesStaleWithTokenRefused() =
        shot("rides--stale") {
            RidesView(
                rides(
                    fresh(Samples.ridesPage()).copy(problem = tokenRefused),
                    Samples
                        .rideItems()
                        .filter {
                            it.kind ==
                                app.overdrive.core.metrics.RideKind.RIDE
                        }.take(4),
                ),
                ridesActions,
            )
        }

    @Test
    fun ridesEmpty() =
        shot("rides--empty") {
            RidesView(rides(fresh(Samples.ridesPage(emptyList())), emptyList()), ridesActions)
        }

    @Test
    fun ridesLoading() =
        shot("rides--loading") {
            RidesView(rides(ResourceState(loaded = true, refreshing = true), emptyList()), ridesActions)
        }

    @Test
    fun ridesError() =
        shot("rides--error") {
            RidesView(rides(ResourceState(loaded = true, problem = dns, asked = true), emptyList()), ridesActions)
        }

    // region ride detail

    private val detailActions = RideDetailActions(none, none, none)

    @Test
    fun rideDetail() =
        shot("ride--detail") {
            RideDetailView(RideDetailUi.of(fresh(Samples.rideDetail()), words, host), detailActions)
        }

    @Test
    fun rideDetailSelected() {
        val selection = ChartSelection().also { it.select(1100.0) }
        shot("ride--selected") {
            RideDetailView(
                RideDetailUi.of(fresh(Samples.rideDetail()), words, host),
                detailActions,
                selection = selection,
            )
        }
    }

    @Test
    fun rideDetailWithoutPlanOrHeartRate() =
        shot("ride--no-plan") {
            RideDetailView(
                RideDetailUi.of(fresh(Samples.rideDetail(withPlan = false, withHeartRate = false)), words, host),
                detailActions,
            )
        }

    @Test
    fun rideDetailLoadingAndError() {
        shot("ride--loading") {
            RideDetailView(RideDetailUi.of(ResourceState(loaded = true, refreshing = true), words, host), detailActions)
        }
    }

    @Test
    fun activityDetail() =
        shot("activity--detail") { ActivityView(ActivityWords.of(Samples.rideItems()[2], words), none) }

    // region settings and first run

    private val settingsActions = SettingsActions(none, {}, {}, none, none)

    private val settingsOk =
        SettingsUiState(
            address = "overdrive.example.net",
            addressMistake = null,
            notEncrypted = false,
            token = "tok-3f9a7c1e5b2d4a6f",
            tokenRefused = false,
            lastSyncValue = "Today 07:40",
            lastSyncNote = "Received your latest figures.",
            syncing = false,
            version = "0.4.0",
        )

    @Test
    fun settings() = shot("settings--settings") { SettingsView(settingsOk, settingsActions) }

    @Test
    fun settingsProblems() =
        shot("settings--problems") {
            SettingsView(
                settingsOk.copy(
                    address = "http://192.168.1.20:3000",
                    notEncrypted = true,
                    tokenRefused = true,
                    lastSyncValue = "Yesterday 21:10",
                    lastSyncNote =
                        "The last attempt failed. The service did not accept the token. " +
                            "Check it in Settings. 1 answer is waiting to be sent.",
                ),
                settingsActions,
                tokenShown = true,
            )
        }

    @Test
    fun settingsSyncing() = shot("settings--syncing") { SettingsView(settingsOk.copy(syncing = true), settingsActions) }

    @Test
    fun connect() =
        shot("connect--empty") {
            ConnectView("overdrive.example.net", "tok-3f9a7c1e5b2d4a6f", null, false, {}, {}, {})
        }

    @Test
    fun connectFailsOnTheAddress() =
        shot("connect--dns") {
            ConnectView(
                "overdrive.example.net",
                "tok-3f9a7c1e5b2d4a6f",
                ConnectResult.AddressProblem(
                    "The phone could not find overdrive.example.net. " +
                        "Check the address in Settings, or try again on another network.",
                    "UnknownHostException · DNS",
                ),
                false,
                {},
                {},
                {},
            )
        }

    @Test
    fun connectFailsOnTheToken() =
        shot("connect--token") {
            ConnectView(
                "overdrive.example.net",
                "wrong",
                ConnectResult.TokenProblem(
                    "The service did not accept this token (401).",
                ),
                false,
                {
                },
                {},
                {},
            )
        }
}
