package app.overdrive.companion

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.companion.model.Clock
import app.overdrive.companion.model.ProblemWords
import app.overdrive.companion.model.Words
import app.overdrive.companion.settings.CompanionSettings
import app.overdrive.companion.settings.CompanionSettingsRepository
import app.overdrive.companion.ui.NavState
import app.overdrive.companion.wellness.UnavailableWellness
import app.overdrive.companion.wellness.WellnessRepository
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.RideDetailResponse
import app.overdrive.core.plan.PlanApiJson
import app.overdrive.core.plan.WorkoutsResponse
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.ProfileApiJson
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfileRefresh
import app.overdrive.core.profile.ProfileRepository
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.FtpSuggestionAnswers
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.MetricsRepository
import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.Refresh
import app.overdrive.core.remote.RemoteResource
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RideDetails
import app.overdrive.core.remote.RidesRepository
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.SyncConfig
import app.overdrive.data.profile.FileProfileCache
import app.overdrive.data.remote.FileTextStore
import app.overdrive.data.remote.FileTextStores
import app.overdrive.data.settings.AddressCheck
import app.overdrive.data.settings.SecretText
import app.overdrive.data.settings.ServiceRules
import app.overdrive.data.sync.UrlConnectionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.filterNotNull
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

/** The screens' refresh targets (design/companion.md §3.3). */
sealed interface RefreshTarget {
    data object Today : RefreshTarget

    data object Fitness : RefreshTarget

    data object Power : RefreshTarget

    data object Rides : RefreshTarget

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
 */
class CompanionContainer(
    context: Context,
    settingsName: String = "settings",
    cacheDirectory: File = File(context.applicationContext.filesDir, "cache"),
    getTransport: HttpGetTransport = UrlConnectionTransport(),
    postTransport: HttpPostTransport = UrlConnectionTransport(),
    patchTransport: HttpPatchTransport = UrlConnectionTransport(),
    val clock: Clock = Clock(),
    elapsedMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val settleMillis: Long = SETTLE_MILLIS,
    val wellness: WellnessRepository = UnavailableWellness(),
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

    private val client = ServiceClient(::config, getTransport, postTransport)
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

    val nav = NavState()

    private val used = MutableStateFlow<Int?>(null)

    /** The watts the rider just accepted: Today says "FTP is now 256 W" until the next visit to it. */
    val justUsed: StateFlow<Int?> = used

    private val syncingNow = MutableStateFlow(false)

    /** The state of one ride's detail. */
    fun rideDetail(id: String): RemoteResource<RideDetailResponse> = rideDetails.resource(id)

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
            var first = true
            settings.filterNotNull().map { it.config }.distinctUntilChanged().collectLatest {
                metrics.settingsChanged()
                workouts.settingsChanged()
                rides.settingsChanged()
                rideDetails.settingsChanged()
                profile.settingsChanged()
                ftpAnswers.settingsChanged()
                if (!first) delay(settleMillis)
                val why = if (first) Refresh.START else Refresh.SETTINGS_CHANGED
                first = false
                refreshToday(why, ProfileRefresh.START)
            }
        }
    }

    private suspend fun refreshToday(
        why: Refresh,
        profileWhy: ProfileRefresh,
    ) = coroutineScope {
        launch { metrics.resource(MetricsRange.DEFAULT).refresh(why) }
        launch { workouts.refresh(why) }
        launch { profile.refresh(if (why == Refresh.SETTINGS_CHANGED) ProfileRefresh.SETTINGS_CHANGED else profileWhy) }
        launch { ftpAnswers.flush() }
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
            }

            RefreshTarget.Fitness -> {
                launch { metrics.resource(settings.value?.fitnessRange ?: MetricsRange.DEFAULT).refresh(why) }
            }

            RefreshTarget.Power -> {
                launch { metrics.resource(settings.value?.powerRange ?: MetricsRange.DEFAULT).refresh(why) }
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

    /** Stops what the container started. Only tests call it; the process owns it otherwise. */
    fun close() {
        scope.cancel()
        settingsScope.cancel()
    }

    companion object {
        /** An address or token that has not changed for this long is tried. */
        const val SETTLE_MILLIS = 1_500L

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PROBE_MAX_BYTES = 65_536
        private const val RESOURCE_COUNT = 5
        private const val PROFILE_AT = 5
        private const val ANSWERS_AT = 6
        private const val SYNCING_AT = 7
    }
}
