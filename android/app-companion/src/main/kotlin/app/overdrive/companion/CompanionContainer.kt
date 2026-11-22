package app.overdrive.companion

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.companion.model.Clock
import app.overdrive.companion.model.FitShown
import app.overdrive.companion.model.ProblemWords
import app.overdrive.companion.model.Words
import app.overdrive.companion.settings.CompanionSettings
import app.overdrive.companion.settings.CompanionSettingsRepository
import app.overdrive.companion.settings.OnboardingStep
import app.overdrive.companion.share.ContentResolverReader
import app.overdrive.companion.share.SharedFile
import app.overdrive.companion.share.SharedFileReader
import app.overdrive.companion.share.SharedRead
import app.overdrive.companion.ui.NavState
import app.overdrive.companion.work.BackgroundScheduler
import app.overdrive.companion.work.NoBackgroundWork
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.health.HealthSource
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.health.HealthSync
import app.overdrive.core.health.RetryPolicy
import app.overdrive.core.health.RunReason
import app.overdrive.core.health.RunResult
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.RideDetailResponse
import app.overdrive.core.notebook.NotebookResponse
import app.overdrive.core.outdoor.FitFlush
import app.overdrive.core.outdoor.FitFlushReason
import app.overdrive.core.outdoor.FitImporter
import app.overdrive.core.outdoor.FitOutcome
import app.overdrive.core.outdoor.FitStatus
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PowerProfileTable
import app.overdrive.core.profile.ProfileApiJson
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfileRefresh
import app.overdrive.core.profile.ProfileRepository
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.FtpSuggestionAnswers
import app.overdrive.core.remote.InsightsRepository
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.MetricsRepository
import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.PowerSkillsResources
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.Refresh
import app.overdrive.core.remote.RemoteResource
import app.overdrive.core.remote.RepeatDetails
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RideDetails
import app.overdrive.core.remote.RidesRepository
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.remote.WellnessRange
import app.overdrive.core.remote.WellnessResources
import app.overdrive.core.remote.WellnessView
import app.overdrive.core.remote.effortResource
import app.overdrive.core.remote.notebookResource
import app.overdrive.core.remote.recoveryResource
import app.overdrive.core.remote.repeatsResource
import app.overdrive.core.repeats.RepeatDetailResponse
import app.overdrive.core.repeats.RepeatsResponse
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpPostBytesTransport
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.data.health.FileBatchStore
import app.overdrive.data.health.HealthConnectSource
import app.overdrive.data.outdoor.FileFitOutbox
import app.overdrive.data.profile.FileProfileCache
import app.overdrive.data.remote.FileTextStore
import app.overdrive.data.remote.FileTextStores
import app.overdrive.data.settings.AddressCheck
import app.overdrive.data.settings.SecretText
import app.overdrive.data.settings.ServiceRules
import app.overdrive.data.sync.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** What the Connect step came to (design/companion.md §4.10): connected, or the field it concerns and the words under it. */
sealed interface ConnectResult {
    data object Connected : ConnectResult

    data class AddressProblem(
        val reason: String,
        val hint: String? = null,
    ) : ConnectResult

    data class TokenProblem(
        val reason: String,
    ) : ConnectResult
}

/** What Settings says of the last exchange (design/companion.md §4.9): when, what went wrong, what waits, and whether one runs. */
data class SyncOverview(
    val lastFetchedMillis: Long? = null,
    val problem: ServiceProblem? = null,
    val waiting: Int = 0,
    val syncing: Boolean = false,
)

/** The two views of the Rides tab (design/companion.md §4.12): the history of rides and activities, or the sessions ridden more than once. */
enum class RidesMode(
    val label: String,
) {
    History("History"),
    Repeats("Repeats"),
}

/** The screens' refresh targets (design/companion.md §3.3). */
sealed interface RefreshTarget {
    data object Today : RefreshTarget

    data object Fitness : RefreshTarget

    data object Power : RefreshTarget

    data object Rides : RefreshTarget

    data object Wellness : RefreshTarget

    data object Notes : RefreshTarget

    /** The list of repeated sessions in the Rides tab. */
    data object Repeats : RefreshTarget

    /** One repeated session, by its hash. */
    data class RepeatDetail(
        val hash: String,
    ) : RefreshTarget

    /** The coach notebook, from its page (design/companion.md §4.13). */
    data object Notebook : RefreshTarget

    /** The Power skills card for one window (design/companion.md §4.14, docs/adr/0042). */
    data class PowerSkills(
        val window: PowerSkillsWindow,
    ) : RefreshTarget

    /** The weekly Effort card (design/companion.md §4.15, docs/adr/0042). */
    data object Effort : RefreshTarget

    data class RideDetail(
        val id: String,
    ) : RefreshTarget
}

/**
 * What lives as long as the process (ADR 0024): the settings, the repositories with their caches on disk, and the navigation
 * state. Built by [CompanionApp] on first use, so the activity can be destroyed and recreated without losing a screen or an
 * answer. No DI framework: this is the whole object graph, like the ride app's `AppContainer` (ADR 0005).
 *
 * - **Refresh.** Each screen asks for its resources when it opens (each at most once a minute) and when the rider pulls down; the
 *   address or token changing in Settings is acted on once it has stayed as typed for [settleMillis]. Every request goes through
 *   one [ServiceClient], which reads the address and token **at every request**, so nothing needs a restart.
 * - **Offline.** Every answer is kept on disk by its repository; the screens read the cache first and say so when a refresh failed.
 *
 * @param cacheDirectory where the answers are kept (`filesDir/cache`); a test passes a temporary folder, which is how a "restart"
 * is simulated.
 * @param settingsName the settings' DataStore file; a second container in one process (a test) needs its own.
 * @param transport the network under the GETs and the POST; @param patchTransport the one under the profile's PATCH (a test on a JDK,
 * whose `HttpURLConnection` refuses that verb, passes one with a way round it).
 * @param settleMillis how long the address and token must stay as typed before they are tried.
 * @param healthSource Health Connect, or a fake.
 * @param scheduler the daily and the first read on the system's clock.
 * @param retryPolicy the wait before a failed upload of watch data is tried again while the app is alive.
 */
class CompanionContainer(
    context: Context,
    settingsName: String = "settings",
    cacheDirectory: File = File(context.applicationContext.filesDir, "cache"),
    getTransport: HttpGetTransport = UrlConnectionTransport(),
    postTransport: HttpPostTransport = UrlConnectionTransport(),
    patchTransport: HttpPatchTransport = UrlConnectionTransport(),
    /** The network under the POST of a FIT file (ADR 0028). */
    bytesTransport: HttpPostBytesTransport = UrlConnectionTransport(),
    val clock: Clock = Clock(),
    private val elapsedMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val settleMillis: Long = SETTLE_MILLIS,
    /** The watch data: Health Connect behind one interface (ADR 0027); a test passes a fake. */
    private val healthSource: HealthSource = HealthConnectSource(context),
    /** The permissions one request asks for; the real set comes from Health Connect, which knows which of them it has. */
    private val permissionRequest: suspend () -> Set<String> = {
        (healthSource as? HealthConnectSource)?.permissionsToRequest().orEmpty()
    },
    /** The system's clock for the daily and the first read; a test passes [NoBackgroundWork] and the container reads in-process. */
    private val scheduler: BackgroundScheduler = NoBackgroundWork,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val openGapMillis: Long = APP_OPEN_GAP_MILLIS,
    /** Where a shared file's bytes come from: the content resolver, or a fake in a test (ADR 0028). */
    private val sharedFiles: SharedFileReader = ContentResolverReader(context),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val words: Words = Words(clock)

    private val settingsRepository =
        CompanionSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = settingsScope,
            ) { appContext.preferencesDataStoreFile(settingsName) },
        )

    /** The settings, or null until the file has been read (a few milliseconds after start): nothing is shown or asked before. */
    val settings: StateFlow<CompanionSettings?> =
        settingsRepository.settings
            .map<CompanionSettings, CompanionSettings?> {
                it
            }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun config(): SyncConfig = settings.value?.config ?: SyncConfig("", "")

    private val client = ServiceClient(::config, getTransport, postTransport, bytesTransport)
    private val wall: () -> Long = { clock.nowMillis() }

    private val cache = cacheDirectory

    private fun file(name: String) = FileTextStore(File(cache, name))

    val metrics: MetricsRepository =
        MetricsRepository(client, ::config, { clock.today() }, { file(it) }, wall, elapsedMillis)

    /** Today's planned workout: `GET /v1/workouts` for the day (spec §5.1). */
    val workouts: RemoteResource<WorkoutsResponse> =
        RemoteResource(
            kind = "workouts",
            client = client,
            config = ::config,
            path = { clock.today().let { "/v1/workouts?from=$it&to=$it" } },
            decode = PlanApiJson::decodeWorkoutsResponse,
            store = file("workouts"),
            wallMillis = wall,
            elapsedMillis = elapsedMillis,
        )

    val rides: RidesRepository = RidesRepository(scope, client, ::config, file("rides"), wall, elapsedMillis)

    private val rideStores = FileTextStores(File(cache, "ride-details"))
    private val rideDetails: RideDetails = RideDetails(client, ::config, { rideStores.store(it) }, wall, elapsedMillis)

    /** The repeated sessions (`GET /v1/repeats`, spec §11.7): the list of the Rides tab's second view. */
    val repeats: RemoteResource<RepeatsResponse> =
        repeatsResource(client, ::config, file("repeats"), wall, elapsedMillis)

    /** The coach notebook (`GET /v1/notebook`, spec §11.6): read-only in the companion, its own page (design/companion.md §4.13). */
    val notebook: RemoteResource<NotebookResponse> =
        notebookResource(client, ::config, file("notebook"), wall, elapsedMillis)

    private val skillStores = FileTextStores(File(cache, "power-skills"), SKILL_CACHE_FILES)

    /**
     * The Power skills radar (`GET /v1/power-skills`, spec §6.3, docs/adr/0042): one cached resource per window, the eight weeks and all
     * time at once, a custom range on first use. Call [powerSkillsFor] to read one; the screen only draws what it says.
     */
    private val powerSkills: PowerSkillsResources =
        PowerSkillsResources(client, ::config, { clock.today() }, { skillStores.store(it) }, wall, elapsedMillis)

    private val chosenWindow = MutableStateFlow<PowerSkillsWindow>(PowerSkillsWindow.EightWeeks)

    /** The window of the Power skills card, kept while the app lives and not across a restart (design/companion.md §4.14). */
    val powerSkillsWindow: StateFlow<PowerSkillsWindow> = chosenWindow

    fun setPowerSkillsWindow(window: PowerSkillsWindow) {
        chosenWindow.value = window
    }

    /** The weekly Effort chart (`GET /v1/effort?weeks=52`, spec §6.3, docs/adr/0042): the card draws the last 12 weeks, Values all of them. */
    val effort: RemoteResource<EffortResponse> = effortResource(client, ::config, file("effort"), wall, elapsedMillis)

    private val repeatStores = FileTextStores(File(cache, "repeat-details"))
    private val repeatDetails: RepeatDetails =
        RepeatDetails(client, ::config, { repeatStores.store(it) }, wall, elapsedMillis)

    /** The rider's profile (ADR 0021): the FTP in force, and the edits that go to the service. */
    val profile: ProfileRepository =
        ProfileRepository(
            ::config,
            getTransport,
            patchTransport,
            FileProfileCache(File(cache, "profile")),
            elapsedMillis,
        )

    /** **Keep 250 W**: the answers to FTP suggestions, queued while the service cannot be reached. */
    val ftpAnswers: FtpSuggestionAnswers = FtpSuggestionAnswers(client, ::config, file("ftp-answers"))

    /** The wellness figures of each range (`GET /v1/wellness`; a year is two requests, the service serves 186 days at most). */
    private val wellnessResources =
        WellnessResources(scope, client, ::config, { clock.today() }, { file(it) }, wall, elapsedMillis)

    /** This morning's recovery rating (`GET /v1/recovery`). */
    val recovery: RemoteResource<RecoveryResponse> =
        recoveryResource(client, ::config, file("recovery"), wall, elapsedMillis)

    /** The written notes and alerts (`GET /v1/insights`), 30 days at a time. */
    val insights: InsightsRepository =
        InsightsRepository(scope, client, ::config, { clock.today() }, file("insights"), wall, elapsedMillis)

    /**
     * The watch data's way to the service (ADR 0027): Health Connect read through [healthSource], queued on disk in `watch/outbox`
     * and sent through the same [client] as everything else. Everything it keeps is private to the app and holds no figure in a
     * file name or a log.
     */
    private val health: HealthSync =
        HealthSync(
            source = healthSource,
            client = client,
            stateStore = file("watch-state"),
            batches = FileBatchStore(File(cache, "watch-outbox")),
            wallMillis = wall,
            device = deviceName(),
            retry = retryPolicy,
        )

    /**
     * The outdoor ride's way to the service (ADR 0028): a FIT file shared from OnelapFit is kept in `fit-outbox` (private to the app,
     * on disk before anything is sent) and sent through the same [client] to `POST /v1/activities/fit`.
     */
    private val fits: FitImporter = FitImporter(client, FileFitOutbox(File(cache, "fit-outbox")), wall, retryPolicy)

    /** What Settings says of the FIT files that wait or were refused. */
    val fitStatus: StateFlow<FitStatus> = fits.status

    private val shown = MutableStateFlow<FitShown>(FitShown.Idle)

    /** What the import screen shows: the file being sent, or what came of it. */
    val fitShown: StateFlow<FitShown> = shown

    /** The segment of the Rides tab: its history, or the repeated sessions (design/companion.md §4.12). */
    private val mode = MutableStateFlow(RidesMode.History)
    val ridesMode: StateFlow<RidesMode> = mode

    fun setRidesMode(next: RidesMode) {
        mode.value = next
    }

    /** What Settings, Today and the first run's steps say of the watch data. */
    val healthStatus: StateFlow<HealthStatus> = health.status

    val nav = NavState()

    private val focus = MutableStateFlow<String?>(null)

    /** The metric Wellness should scroll to (the rider tapped a fact of the recovery card); the screen clears it when it has. */
    val wellnessFocus: StateFlow<String?> = focus

    fun focusWellness(key: String?) {
        focus.value = key
        if (key != null) nav.select(app.overdrive.companion.ui.Tab.Wellness)
    }

    private val used = MutableStateFlow<Int?>(null)

    /** The watts the rider just accepted: Today says "FTP is now 256 W" until the next visit to it. */
    val justUsed: StateFlow<Int?> = used

    private val syncingNow = MutableStateFlow(false)

    /** The state of one ride's detail. */
    fun rideDetail(id: String): RemoteResource<RideDetailResponse> =
        rideDetails.resource(id).also { resource ->
            // Its cache is read as soon as it is first asked for, so the screen has the ride at once and offline.
            if (!resource.state.value.loaded) scope.launch { resource.load() }
        }

    /** The state of one repeated session. */
    fun repeatDetail(hash: String): RemoteResource<RepeatDetailResponse> =
        repeatDetails.resource(hash).also { resource ->
            if (!resource.state.value.loaded) scope.launch { resource.load() }
        }

    /** The state of the Power skills for [window]: its cache is read as soon as it is first asked for, so a window seen before is there at once. */
    fun powerSkillsFor(window: PowerSkillsWindow): RemoteResource<PowerSkillsResponse> =
        powerSkills.resource(window).also { resource ->
            if (!resource.state.value.loaded) scope.launch { resource.load() }
        }

    /** The Wellness figures for [range]. */
    fun wellnessFor(range: WellnessRange): WellnessView = wellnessResources.view(range)

    /** The Fitness and Power metrics for [range]. */
    fun metricsFor(range: MetricsRange): RemoteResource<app.overdrive.core.metrics.MetricsResponse> =
        metrics.resource(range)

    /** What Settings says of the last exchange, as one flow. */
    val syncOverview: StateFlow<SyncOverview> =
        combine(
            listOf<kotlinx.coroutines.flow.Flow<Any?>>(
                metrics.resource(MetricsRange.SIX_WEEKS).state,
                metrics.resource(MetricsRange.SIX_MONTHS).state,
                metrics.resource(MetricsRange.ONE_YEAR).state,
                workouts.state,
                rides.newest.state,
                profile.state,
                ftpAnswers.state,
                syncingNow,
            ),
        ) { values -> overviewOf(values) }.stateIn(scope, SharingStarted.Eagerly, SyncOverview())

    private fun overviewOf(values: Array<Any?>): SyncOverview {
        val resources = values.take(RESOURCE_COUNT).filterIsInstance<ResourceState<*>>()
        val profileState = values[PROFILE_AT] as ProfileState
        val answers = values[ANSWERS_AT] as FtpAnswers
        return SyncOverview(
            lastFetchedMillis = resources.mapNotNull { it.fetchedAtMillis }.maxOrNull(),
            problem = resources.firstNotNullOfOrNull { it.problem },
            waiting = answers.waiting + if (profileState.pending != null) 1 else 0,
            syncing = values[SYNCING_AT] as Boolean,
        )
    }

    init {
        // The caches first, so every screen draws at once and offline; then, once the settings have been read, the first requests.
        scope.launch {
            metrics.load()
            workouts.load()
            rides.load()
            profile.load()
            ftpAnswers.load()
            wellnessResources.load()
            recovery.load()
            insights.load()
            repeats.load()
            notebook.load()
            powerSkills.load()
            effort.load()
            health.load()
            fits.load()
            var first = true
            settings.filterNotNull().map { it.config }.distinctUntilChanged().collectLatest {
                metrics.settingsChanged()
                workouts.settingsChanged()
                rides.settingsChanged()
                rideDetails.settingsChanged()
                profile.settingsChanged()
                ftpAnswers.settingsChanged()
                wellnessResources.settingsChanged()
                recovery.settingsChanged()
                insights.settingsChanged()
                repeats.settingsChanged()
                repeatDetails.settingsChanged()
                notebook.settingsChanged()
                powerSkills.settingsChanged()
                effort.settingsChanged()
                if (!first) delay(settleMillis)
                val why = if (first) Refresh.START else Refresh.SETTINGS_CHANGED
                first = false
                refreshToday(why, ProfileRefresh.START)
            }
        }
    }

    init {
        // What the service computes from the watch data is asked again when new data has reached it: the rating, the notes, the charts.
        scope.launch {
            health.status
                .map { it.lastSentMillis }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshWatchFigures(Refresh.REQUESTED) }
        }
        // A batch that could not be sent is tried again after its backoff while the app is alive; the daily job does the same later.
        scope.launch {
            health.status
                .map {
                    RetryAt(
                        it.nextRetryMillis,
                        it.waitingBatches > 0 && it.uploadProblem?.kind != ProblemKind.TOKEN_REJECTED,
                    )
                }.distinctUntilChanged()
                .collectLatest { retry ->
                    val at = retry.at
                    if (retry.waiting && at != null) {
                        delay((at - clock.nowMillis()).coerceAtLeast(0))
                        health.run(RunReason.RETRY)
                    }
                }
        }
    }

    init {
        // A ride that the service accepted from the queue (not from the share screen) is in the Rides list when the rider looks.
        scope.launch {
            fits.status
                .map { it.accepted }
                .distinctUntilChanged()
                .drop(1)
                .collect { rides.refresh(Refresh.REQUESTED) }
        }
        // A file that is kept is sent as soon as there is a network, even with the app closed; the system's job is made once per file.
        scope.launch {
            fits.status
                .map { it.waiting }
                .distinctUntilChanged()
                .collect { if (it > 0) scheduler.scheduleFitFlush() }
        }
        // A file that could not be sent is tried again after its backoff while the app is alive; the system's job does the same later.
        scope.launch {
            fits.status
                .map {
                    RetryAt(it.nextRetryMillis, it.waiting > 0 && it.problem?.kind != ProblemKind.TOKEN_REJECTED)
                }.distinctUntilChanged()
                .collectLatest { retry ->
                    val at = retry.at
                    if (retry.waiting && at != null) {
                        delay((at - clock.nowMillis()).coerceAtLeast(0))
                        fits.flush(FitFlushReason.RETRY)
                    }
                }
        }
    }

    private data class RetryAt(
        val at: Long?,
        val waiting: Boolean,
    )

    private suspend fun refreshWatchFigures(why: Refresh) =
        coroutineScope {
            launch { recovery.refresh(why) }
            launch { insights.refresh(why) }
            launch { wellnessResources.refresh(settings.value?.wellnessRange ?: WellnessRange.DEFAULT, why) }
        }

    private suspend fun refreshToday(
        why: Refresh,
        profileWhy: ProfileRefresh,
    ) = coroutineScope {
        launch { metrics.resource(MetricsRange.DEFAULT).refresh(why) }
        launch { workouts.refresh(why) }
        launch { profile.refresh(if (why == Refresh.SETTINGS_CHANGED) ProfileRefresh.SETTINGS_CHANGED else profileWhy) }
        launch { ftpAnswers.flush() }
        launch { recovery.refresh(why) }
        launch { insights.refresh(why) }
    }

    /** A screen came on screen: asks for what it shows, each resource at most once a minute. */
    fun screenOpened(target: RefreshTarget) {
        scope.launch { refresh(target, Refresh.SCREEN_OPENED) }
        if (target == RefreshTarget.Today) used.value = null
    }

    /** The rider pulled down or pressed **Try again**: asks again now, and tries a refused token once more. */
    fun refresh(target: RefreshTarget) {
        scope.launch { refresh(target, Refresh.REQUESTED) }
    }

    private suspend fun refresh(
        target: RefreshTarget,
        why: Refresh,
    ) = coroutineScope {
        when (target) {
            RefreshTarget.Today -> {
                launch { metrics.resource(MetricsRange.DEFAULT).refresh(why) }
                launch { workouts.refresh(why) }
                launch { rides.refresh(why) }
                launch {
                    profile.refresh(
                        if (why ==
                            Refresh.REQUESTED
                        ) {
                            ProfileRefresh.REQUESTED
                        } else {
                            ProfileRefresh.SCREEN_OPENED
                        },
                    )
                }
                launch { ftpAnswers.flush() }
                launch { recovery.refresh(why) }
                launch { insights.refresh(why) }
            }

            RefreshTarget.Wellness -> {
                launch { wellnessResources.refresh(settings.value?.wellnessRange ?: WellnessRange.DEFAULT, why) }
                launch { health.refreshAccess() }
            }

            RefreshTarget.Notes -> {
                launch { insights.refresh(why) }
            }

            RefreshTarget.Repeats -> {
                launch { repeats.refresh(why) }
            }

            is RefreshTarget.RepeatDetail -> {
                launch { repeatDetails.resource(target.hash).refresh(why) }
            }

            RefreshTarget.Notebook -> {
                launch { notebook.refresh(why) }
            }

            is RefreshTarget.PowerSkills -> {
                launch { powerSkillsFor(target.window).refresh(why) }
            }

            RefreshTarget.Effort -> {
                launch { effort.refresh(why) }
            }

            RefreshTarget.Fitness -> {
                launch { metrics.resource(settings.value?.fitnessRange ?: MetricsRange.DEFAULT).refresh(why) }
                launch { effort.refresh(why) }
            }

            RefreshTarget.Power -> {
                launch { metrics.resource(settings.value?.powerRange ?: MetricsRange.DEFAULT).refresh(why) }
                launch { powerSkillsFor(chosenWindow.value).refresh(why) }
                launch {
                    profile.refresh(
                        if (why ==
                            Refresh.REQUESTED
                        ) {
                            ProfileRefresh.REQUESTED
                        } else {
                            ProfileRefresh.SCREEN_OPENED
                        },
                    )
                }
            }

            RefreshTarget.Rides -> {
                launch { rides.refresh(why) }
            }

            is RefreshTarget.RideDetail -> {
                launch { rideDetails.resource(target.id).refresh(why) }
            }
        }
    }

    /** Asks the next 30 days of older notes. */
    fun loadMoreNotes() {
        scope.launch { insights.loadMore() }
    }

    /** Asks the next page of older rides. */
    fun loadMoreRides() {
        scope.launch { rides.loadMore() }
    }

    /**
     * Settings' **Sync now**: asks for everything at once, however recently, and sends the answers that wait. Off while it runs.
     */
    fun syncNow() {
        if (!syncingNow.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                coroutineScope {
                    for (range in MetricsRange.entries) launch { metrics.resource(range).refresh(Refresh.REQUESTED) }
                    launch { workouts.refresh(Refresh.REQUESTED) }
                    launch { rides.refresh(Refresh.REQUESTED) }
                    launch { profile.refresh(ProfileRefresh.REQUESTED) }
                    launch { ftpAnswers.flush() }
                    launch { recovery.refresh(Refresh.REQUESTED) }
                    launch { insights.refresh(Refresh.REQUESTED) }
                    launch { repeats.refresh(Refresh.REQUESTED) }
                    launch { notebook.refresh(Refresh.REQUESTED) }
                    launch { effort.refresh(Refresh.REQUESTED) }
                    launch { powerSkillsFor(PowerSkillsWindow.EightWeeks).refresh(Refresh.REQUESTED) }
                    launch { powerSkillsFor(PowerSkillsWindow.AllTime).refresh(Refresh.REQUESTED) }
                    // The window the rider chose, when it is a range of their own.
                    (chosenWindow.value as? PowerSkillsWindow.Custom)?.let {
                        launch {
                            powerSkillsFor(
                                it,
                            ).refresh(Refresh.REQUESTED)
                        }
                    }
                    // The files shared from OnelapFit that wait: the rider asked, so the backoff does not apply (ADR 0028).
                    launch { fits.flush(FitFlushReason.MANUAL) }
                    launch {
                        wellnessResources.refresh(
                            settings.value?.wellnessRange ?: WellnessRange.DEFAULT,
                            Refresh.REQUESTED,
                        )
                    }
                    // The watch data too: read what is new and send what waits (design/companion.md §3.3).
                    launch { health.run(RunReason.MANUAL) }
                }
            } finally {
                syncingNow.value = false
            }
        }
    }

    // region answers to the FTP suggestion

    /** **Use 256 W** (and Power's **Set FTP**): puts the FTP on the profile now and sends it, or keeps it to send when it can. */
    fun useFtp(watts: Int) {
        if (profile.edit(ProfilePatch(ftp = Change.Set(watts)))) {
            used.value = watts
            scope.launch {
                profile.refresh(ProfileRefresh.EDITED)
                metrics.resource(MetricsRange.DEFAULT).refresh(Refresh.REQUESTED)
            }
        } else {
            // No profile in force yet (the service was never reached): ask for it, the rider can answer when it is here.
            scope.launch { profile.refresh(ProfileRefresh.REQUESTED) }
        }
    }

    /** **Keep 250 W**: the suggestion is hidden at once and the service is told when it can be reached. */
    fun keepFtp(suggestionId: Int) {
        scope.launch { ftpAnswers.dismiss(suggestionId) }
    }

    // region the watch data

    /** The permissions one request asks for, for the system dialog (design/companion.md §4.10). */
    suspend fun permissionsToRequest(): Set<String> = permissionRequest()

    /** Re-reads what Health Connect is and allows now, for Settings and the first run's steps; reads no record. */
    fun refreshHealthAccess() {
        scope.launch { health.refreshAccess() }
    }

    /**
     * The rider answered the permission dialog, or came back from Health Connect's own screen: re-read what is allowed, and when
     * something is, put the daily read on the clock and read what is new.
     */
    fun healthAccessChanged() {
        scope.launch {
            health.refreshAccess()
            if (health.status.value.access.granted
                    .isNotEmpty()
            ) {
                scheduler.scheduleDaily()
                if (settings.value?.onboarding == OnboardingStep.Done) health.run(RunReason.APP_OPENED)
            }
        }
    }

    /** The first run moves to [step]. */
    fun setOnboarding(step: OnboardingStep) {
        scope.launch {
            settingsRepository.update { it.copy(onboarding = step) }
            if (step == OnboardingStep.Reading) startFirstRead()
        }
    }

    /** Starts the first read of the last 60 days where it keeps going when the rider leaves, or in this process when it cannot. */
    fun startFirstRead() {
        scheduler.scheduleDaily()
        if (!scheduler.startFirstRead()) scope.launch { runWatch(RunReason.FIRST_READ) }
    }

    /**
     * Reads, queues and sends the watch data, then asks again for the figures Today shows. What the background job calls, and Sync
     * now's and the app opening's way in; one at a time ([RunResult.outcome] says when another was running).
     */
    suspend fun runWatch(reason: RunReason): RunResult {
        val result = health.run(reason)
        if (settings.value?.isConfigured == true) refreshToday(Refresh.REQUESTED, ProfileRefresh.REQUESTED)
        return result
    }

    /** The app came on screen (design/companion.md §9): read what is new, at most once in [openGapMillis]. */
    fun appOpened() {
        val now = elapsedMillis()
        val last = lastOpenRun
        if (last != null && now - last < openGapMillis) return
        lastOpenRun = now
        scope.launch {
            val configured = settings.filterNotNull().first().isConfigured
            health.refreshAccess()
            if (!configured) return@launch
            if (health.status.value.access.granted
                    .isNotEmpty()
            ) {
                scheduler.scheduleDaily()
            }
            launch { fits.flush(FitFlushReason.APP_OPENED) }
            val step = settings.value?.onboarding
            // During the first run the steps decide when reading starts.
            if (step == OnboardingStep.Done || step == OnboardingStep.Reading) health.run(RunReason.APP_OPENED)
        }
    }

    private var lastOpenRun: Long? = null

    // region outdoor rides from a FIT file

    /** Sends the FIT files that wait; what the system's job calls, and what the retry and the app opening use. */
    suspend fun flushFits(reason: FitFlushReason): FitFlush = fits.flush(reason)

    private var shares = 0

    /**
     * The rider shared a file to the companion (design/companion.md §4.11): reads it (at most 16 MiB), keeps it, sends it now and says
     * what came of it on the import screen. It runs in the container's own scope, so leaving the screen does not stop the send, and a
     * file that cannot be sent stays on disk and goes later.
     */
    fun shareFit(file: SharedFile) {
        val mine = ++shares
        shown.value = FitShown.Importing
        scope.launch {
            val result =
                try {
                    when (val read = sharedFiles.read(file)) {
                        is SharedRead.Bytes -> FitShown.Done(fits.import(read.bytes))
                        is SharedRead.Refused -> FitShown.Done(FitOutcome.Refused(read.refusal))
                        SharedRead.Unreadable -> FitShown.Unreadable
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing another app does with the file may take the container down; what failed is not said (it could name the file).
                    FitShown.Unreadable
                }
            // A second share replaced this one while it ran: the screen shows the newer.
            if (mine == shares) shown.value = result
        }
    }

    /** The import screen was left: nothing is shown next time it opens without a file. */
    fun fitScreenClosed() {
        if (shown.value !is FitShown.Importing) shown.value = FitShown.Idle
    }

    // region heart rate

    private var sendEdit: Job? = null

    /** Settings' stepper: the maximum heart rate goes on the profile at once and to the service once the rider stops tapping. */
    fun setMaxHeartRate(bpm: Int) = editProfile(ProfilePatch(maxHeartRate = Change.Set(bpm)))

    fun setThresholdHeartRate(bpm: Int) = editProfile(ProfilePatch(thresholdHeartRate = Change.Set(bpm)))

    /**
     * Settings' **Power profile table**: which table of watts per kilogram the Power skills' levels are read against (ADR 0042). It goes on
     * the profile at once and to the service once the rider stops tapping; the levels are the service's, so the Power skills are asked
     * again afterwards.
     */
    fun setPowerProfileTable(table: PowerProfileTable) =
        editProfile(ProfilePatch(powerProfileTable = Change.Set(table)))

    private fun editProfile(patch: ProfilePatch) {
        if (!profile.edit(patch)) {
            // No profile in force yet (the service was never reached): ask for it, the rider can try again when it is here.
            scope.launch { profile.refresh(ProfileRefresh.REQUESTED) }
            return
        }
        sendEdit?.cancel()
        sendEdit =
            scope.launch {
                delay(EDIT_SETTLE_MILLIS)
                profile.refresh(ProfileRefresh.EDITED)
                if (patch.powerProfileTable != Change.Keep) refresh(RefreshTarget.Power)
            }
    }

    // region settings

    /** The Settings fields: a changed address or token (already in the valid form) is stored; the refresh follows once it settles. */
    fun updateService(
        url: String? = null,
        token: String? = null,
    ) {
        scope.launch {
            settingsRepository.update {
                it.copy(serviceUrl = url ?: it.serviceUrl, token = token?.let(::SecretText) ?: it.token)
            }
        }
    }

    fun setFitnessRange(range: MetricsRange) {
        scope.launch { settingsRepository.update { it.copy(fitnessRange = range) } }
    }

    fun setPowerRange(range: MetricsRange) {
        scope.launch { settingsRepository.update { it.copy(powerRange = range) } }
    }

    fun setWellnessRange(range: WellnessRange) {
        scope.launch { settingsRepository.update { it.copy(wellnessRange = range) } }
    }

    /**
     * The first run's Connect (design/companion.md §4.10): one `GET /v1/profile` with the typed address and token, which are stored
     * only if the service answers. A failure says which field it concerns.
     */
    suspend fun connect(
        rawUrl: String,
        rawToken: String,
    ): ConnectResult {
        val check = ServiceRules.checkAddress(rawUrl)
        val address =
            when (check) {
                AddressCheck.Empty -> return ConnectResult.AddressProblem("Enter the address of your service.")
                is AddressCheck.Invalid -> return ConnectResult.AddressProblem(check.reason)
                is AddressCheck.Valid -> check
            }
        val token = ServiceRules.cleanToken(rawToken)
        if (token.isEmpty()) return ConnectResult.TokenProblem("Enter the token made for this phone.")
        val typed = SyncConfig(address.url, token)
        val probe = ServiceClient({ typed }, probeTransport)
        return when (val outcome = probe.get("/v1/profile", PROBE_MAX_BYTES, ProfileApiJson::decodeProfile)) {
            is Outcome.Ok -> {
                settingsRepository.update { it.copy(serviceUrl = address.url, token = SecretText(token)) }
                ConnectResult.Connected
            }

            Outcome.NotSetUp -> {
                ConnectResult.AddressProblem("Enter the address of your service.")
            }

            is Outcome.Failed -> {
                if (outcome.problem.kind == ProblemKind.TOKEN_REJECTED) {
                    ConnectResult.TokenProblem("The service did not accept this token (401).")
                } else {
                    ConnectResult.AddressProblem(
                        ProblemWords.causeOf(outcome.problem, address.host),
                        ProblemWords.hintOf(outcome.problem),
                    )
                }
            }
        }
    }

    private val probeTransport: HttpGetTransport = getTransport

    private fun deviceName(): String =
        android.os.Build.MODEL
            .orEmpty()
            .trim()
            .ifEmpty { "Phone" }
            .take(DEVICE_CHARS)

    /** Stops what the container started. Only tests call it; the process owns it otherwise. */
    fun close() {
        scope.cancel()
        settingsScope.cancel()
    }

    companion object {
        /** An address or token that has not changed for this long is tried. */
        const val SETTLE_MILLIS = 1_500L

        /** Opening the app reads the watch data at most this often. */
        const val APP_OPEN_GAP_MILLIS = 5 * 60_000L

        /** A heart-rate stepper sends its change once it has been left alone this long. */
        private const val EDIT_SETTLE_MILLIS = 700L

        private const val NANOS_PER_MILLI = 1_000_000L

        /** The caches of the Power skills windows kept on disk: the two standing ones and the ranges the rider chose last. */
        private const val SKILL_CACHE_FILES = 10
        private const val DEVICE_CHARS = 64
        private const val PROBE_MAX_BYTES = 65_536
        private const val RESOURCE_COUNT = 5
        private const val PROFILE_AT = 5
        private const val ANSWERS_AT = 6
        private const val SYNCING_AT = 7
    }
}
