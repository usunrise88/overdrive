package app.overdrive.ride

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.ble.AndroidBle
import app.overdrive.ble.BleStack
import app.overdrive.ble.log.BleLogRole
import app.overdrive.ble.log.BleLogStore
import app.overdrive.ble.log.BleSessionLogger
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.link.ConnectStrategy
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.CoachControl
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.fit.FitRoutes
import app.overdrive.core.live.CuePlayer
import app.overdrive.core.live.LiveCoachClient
import app.overdrive.core.plan.PlanRefresh
import app.overdrive.core.plan.PlanRepository
import app.overdrive.core.profile.ProfileRefresh
import app.overdrive.core.profile.ProfileRepository
import app.overdrive.core.record.RideRecord
import app.overdrive.core.remote.CacheText
import app.overdrive.core.remote.Refresh
import app.overdrive.core.remote.RemoteResource
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.previousAttemptOf
import app.overdrive.core.remote.recoveryResource
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteLibrary
import app.overdrive.core.route.RoutesApiJson
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpSyncProvider
import app.overdrive.core.sync.HttpTransport
import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.SyncCoordinator
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.data.plan.FilePlanCache
import app.overdrive.data.profile.FileProfileCache
import app.overdrive.data.remote.FileTextStore
import app.overdrive.data.remote.FileTextStores
import app.overdrive.data.rides.RideStore
import app.overdrive.data.sync.FileOutbox
import app.overdrive.data.sync.LastUploadFile
import app.overdrive.data.sync.StoredRidePayloads
import app.overdrive.data.sync.UrlConnectionTransport
import app.overdrive.ride.devices.BleDevicesModel
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.DeviceStore
import app.overdrive.ride.devices.PairedDevices
import app.overdrive.ride.devices.PairingController
import app.overdrive.ride.handlebar.ButtonLearning
import app.overdrive.ride.handlebar.HandlebarService
import app.overdrive.ride.host.AudioManagerMediaController
import app.overdrive.ride.host.BleRideSessionFactory
import app.overdrive.ride.host.DeviceSourceFactory
import app.overdrive.ride.host.MediaController
import app.overdrive.ride.host.PreviousAttemptSource
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.live.AndroidCuePlayer
import app.overdrive.ride.live.LiveCoach
import app.overdrive.ride.overlay.AndroidOverlayController
import app.overdrive.ride.overlay.AppVisibility
import app.overdrive.ride.overlay.ContainerOverlayModel
import app.overdrive.ride.overlay.OverlayController
import app.overdrive.ride.overlay.OverlayModel
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.routes.RoutesView
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.SettingsRepository
import app.overdrive.ride.settings.effectiveSettingsOf
import app.overdrive.ride.settings.riderPatch
import app.overdrive.ride.settings.withProfile
import app.overdrive.ride.ui.AndroidBluetoothController
import app.overdrive.ride.ui.BleLogShare
import app.overdrive.ride.ui.BluetoothController
import app.overdrive.ride.workouts.WorkoutLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * What lives as long as the process (ADR 0005): the settings store, the workout library, the stored rides and the one
 * [RideHost]. Built by [OverdriveApp] on first use, so the activity can be destroyed and recreated (rotation, process
 * kept alive behind a video app) without touching the ride, and the overlay service reads the same instances through
 * [overlayModel]. No DI framework: this is the whole object graph.
 *
 * @param ridesDirectory where the rides are kept: the app's private `files/rides`, which nothing outside the app can read
 * except through the FileProvider (ADR 0008). A test passes a temporary folder, which is how a "restart" is simulated.
 * @param settingsName the settings' DataStore file; a second container in one process (a test) needs its own, because
 * DataStore allows one instance per file.
 * @param outboxFile the sync outbox's journal (ADR 0012); a test passes a temporary one.
 * @param lastUploadFile the one-line file that remembers when an upload last succeeded; beside the outbox by default.
 * @param syncTransport the network under the upload (`HttpURLConnection`); a test points it at a local server.
 * @param planTransport the network under the planned workouts' `GET` (ADR 0017): the same `HttpURLConnection` class.
 * @param planFile the planned workouts' cache (`filesDir/plan/workouts`); a test passes a temporary one.
 * @param profileTransport the network under the rider's profile `GET` (ADR 0021): the same `HttpURLConnection` class.
 * @param profilePatchTransport the network under the profile's `PATCH`. The same class again; a test on a JDK, whose
 * `HttpURLConnection` refuses that verb, passes one with a way round it.
 * @param previousTransport the network under the one request for the previous attempt of a session (docs/adr/0034): the same
 * `HttpURLConnection` class; a test points it at a local server.
 * @param profileFile the profile's cache (`filesDir/profile/profile`): the service's last answer and the unsent edit; a test
 * passes a temporary one.
 * @param routesTransport the network under the routes' and the recovery state's `GET` (ADR 0037): the same `HttpURLConnection` class.
 * @param routesDirectory where the routes are kept on disk (`filesDir/routes`: the list, and one file for the points of each route
 * the tablet has fetched); a test passes a temporary one.
 * @param recoveryFile the recovery state's cache (`filesDir/cache/recovery`); a test passes a temporary one.
 * @param today the tablet's local date, `YYYY-MM-DD`, for the range asked of the service; a test fixes it.
 * @param syncSettleMillis how long the service address and token must stay as typed before the upload is tried again with
 * them, so that typing a token does not send one request per letter.
 * @param mediaFactory the media keys of the ride bar and of the Click; a test records them.
 * @param elapsedMillis the monotonic clock of the gesture layer (a hold is 500 ms of it); Robolectric's is a fake one, so a
 * test of real holds passes a real clock.
 * @param liveTransport the network under the live coach (docs/adr/0039): its POSTs and its cue stream, the same `HttpURLConnection` class; a test points
 * it at a local server.
 * @param cuePlayer speaks one cue (`MediaPlayer` with audio focus); a test gives one that records and makes no sound.
 */
class AppContainer(
    context: Context,
    ridesDirectory: File = File(context.applicationContext.filesDir, "rides"),
    settingsName: String = "settings",
    devicesName: String = if (settingsName == "settings") "devices" else "$settingsName-devices",
    bleLogDirectory: File = File(context.applicationContext.filesDir, "ble-logs"),
    bleStack: (Context, CoroutineScope) -> BleStack = { appContext, scope -> AndroidBle(appContext, scope) },
    outboxFile: File = File(context.applicationContext.filesDir, "sync/outbox.log"),
    lastUploadFile: File = File(outboxFile.absoluteFile.parentFile, "last-upload"),
    syncTransport: HttpTransport = UrlConnectionTransport(),
    planTransport: HttpGetTransport = UrlConnectionTransport(),
    planFile: File = File(context.applicationContext.filesDir, "plan/workouts"),
    profileTransport: HttpGetTransport = UrlConnectionTransport(),
    profilePatchTransport: HttpPatchTransport = UrlConnectionTransport(),
    previousTransport: HttpGetTransport = UrlConnectionTransport(),
    profileFile: File = File(context.applicationContext.filesDir, "profile/profile"),
    routesTransport: HttpGetTransport = UrlConnectionTransport(),
    routesDirectory: File = File(context.applicationContext.filesDir, "routes"),
    recoveryFile: File = File(context.applicationContext.filesDir, "cache/recovery"),
    today: () -> String = { LocalDate.now().toString() },
    syncSettleMillis: Long = SYNC_SETTLE_MILLIS,
    mediaFactory: (Context) -> MediaController = { AudioManagerMediaController(it) },
    elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    liveTransport: UrlConnectionTransport = UrlConnectionTransport(),
    cuePlayer: (Context) -> CuePlayer = { AndroidCuePlayer(it) },
) {
    private val appContext = context.applicationContext
    private var liveScope: CoroutineScope? = null
    private val localToday = today
    private val routePointsDirectory = File(routesDirectory, "points")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One thread for everything that touches the rides folder: the log writes, the stores, the scan (ADR 0008). */
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = settingsScope) {
                appContext.preferencesDataStoreFile(settingsName)
            },
        )

    /** The current settings. Defaults until the first read from disk completes, a few milliseconds after start. */
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            scope,
            SharingStarted.Eagerly,
            AppSettings(),
        )

    val library: WorkoutLibrary = WorkoutLibrary.fromAssets(appContext.assets)

    /** The rides on disk: read by the library below and by the upload, which sends the stored files as they are. */
    private val rideStore = RideStore(ridesDirectory)

    /**
     * The sync outbox (spec §4.9, ADR 0012): finished rides waiting for an upload, on the tablet's disk, surviving the
     * app being killed. The coordinator drains it through the [HttpSyncProvider] (ADR 0014): the service's address and
     * token are read from the settings **at every upload**, so a change needs no restart, and with either empty nothing
     * is sent and every ride stays queued.
     */
    val outbox: FileOutbox = FileOutbox(outboxFile)

    private val syncProvider =
        HttpSyncProvider(
            config = { settings.value.let { SyncConfig(it.syncUrl, it.syncToken.value) } },
            rides = StoredRidePayloads(rideStore),
            transport = syncTransport,
        )

    /** When an upload last succeeded, kept next to the outbox so Settings still says so after a restart. */
    private val lastUpload = LastUploadFile(lastUploadFile)
    private val lastUploadAtStart: Long? = lastUpload.read()

    val sync: SyncCoordinator =
        SyncCoordinator(scope, outbox, { syncProvider }, lastSuccessMillis = lastUploadAtStart).also { it.start() }

    /** The service address and token as the settings file has them: null until the file has been read, so nothing is asked before. */
    private val serviceConfig = MutableStateFlow<SyncConfig?>(null)

    /**
     * The planned workouts (spec §5.7, ADR 0017): fetched from the same service, with the same address and token, at start,
     * when the Ready screen opens (at most once a minute) and when the rider asks; kept on disk so the list works offline.
     * With no address or token nothing is asked and nothing is shown. The settings are read at every request.
     */
    val plan: PlanRepository =
        PlanRepository(
            config = { serviceConfig.value ?: SyncConfig("", "") },
            transport = planTransport,
            cache = FilePlanCache(planFile),
            today = today,
            wallMillis = System::currentTimeMillis,
            elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
        )

    /**
     * The rider's profile (spec §8.3, ADR 0021): read from the same service at start, when the Ready screen opens (at most once a
     * minute), after a settled change of the address or token and when the rider asks (the same gesture as the plan's); the
     * rider's edits of FTP and physics are sent to it. The last good answer and the one unsent edit are kept on disk, so the
     * profile works offline and after a restart. With no address or token, or while the service was never reached and nothing is
     * cached, there is no profile in force and the tablet's own settings apply as they always did.
     */
    val profile: ProfileRepository =
        ProfileRepository(
            config = { serviceConfig.value ?: SyncConfig("", "") },
            reader = profileTransport,
            writer = profilePatchTransport,
            cache = FileProfileCache(profileFile),
            elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
        )

    /** The service the routes and the recovery state are asked from: the same address and token, read at every request. */
    private val serviceClient =
        ServiceClient(config = { serviceConfig.value ?: SyncConfig("", "") }, reader = routesTransport)

    /** One file for each route's points, at most [MAX_ROUTE_FILES]: a ride on a route never needs the network (spec §11.4, ADR 0037). */
    private val routePointFiles = FileTextStores(routePointsDirectory, maxFiles = MAX_ROUTE_FILES)

    /**
     * The routes on the tablet (spec §11.4, ADR 0037): the list of `GET /v1/routes`, cached on disk and shown at once and offline, and the
     * points of a route (`GET /v1/routes/{id}`), fetched when the rider chooses it and for the routes the next days' planned workouts
     * name, and kept on disk. With no address or token nothing is asked and nothing is shown.
     */
    val routeLibrary: RouteLibrary =
        RouteLibrary(
            client = serviceClient,
            config = { serviceConfig.value ?: SyncConfig("", "") },
            listStore = FileTextStore(File(routesDirectory, "list")),
            detailStore = routePointFiles::store,
            wallMillis = System::currentTimeMillis,
            elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
        )

    /** What the Ready screen reads of the routes: the list and which routes' points are on the tablet. */
    val routes: StateFlow<RoutesView> =
        combine(routeLibrary.list.state, routeLibrary.details) { list, details -> RoutesView.of(list, details) }
            .stateIn(scope, SharingStarted.Eagerly, RoutesView.None)

    /**
     * Today's recovery state (`GET /v1/recovery`, spec §11.5), read **only** to say "Recovery: caution" beside the easier version of a
     * planned workout: asked when a workout of today has one, at most once a minute, and kept on disk so that it shows offline too.
     */
    val recovery: RemoteResource<RecoveryResponse> =
        recoveryResource(
            client = serviceClient,
            config = { serviceConfig.value ?: SyncConfig("", "") },
            store = FileTextStore(recoveryFile),
            wallMillis = System::currentTimeMillis,
            elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
        )

    /**
     * The tablet's settings with the profile in force on top (ADR 0021, [withProfile]): FTP and the physics of the ride. A cold
     * flow that **waits until the profile is known** (the cache read and the settings looked at), so the ride built first uses the
     * right FTP and not the tablet's own for a moment, **for [PROFILE_WAIT_MILLIS] at most** (ADR 0029): a ride never depends on the
     * profile or its cache. The ride, the Ready screen and the Settings rows read this; the stored [settings] stay what the rider
     * set on the tablet and are never overwritten by the service's.
     */
    private val effectiveSource: Flow<AppSettings> = effectiveSettingsOf(settingsRepository.settings, profile.state)

    /** The latest of [effectiveSource]; the defaults until the settings have been read from disk, a few milliseconds after start. */
    val effectiveSettings: StateFlow<AppSettings> =
        effectiveSource.stateIn(
            scope,
            SharingStarted.Eagerly,
            AppSettings(),
        )

    /**
     * Wall-clock milliseconds for an entry's `enqueuedAtMillis`, **strictly increasing**: the coordinator queues each ride on
     * a coroutine of its own and the outbox orders by this time, so two rides announced in the same millisecond (the rides
     * on disk at a start, oldest first) would otherwise come out in whatever order the coroutines ran.
     */
    private val lastEnqueueTime = AtomicLong(0)

    private fun nextEnqueueTime(): Long =
        lastEnqueueTime.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis()) }

    /**
     * The rides on disk: records the ride as it happens, stores it when it ends, and on start offers the ones that were
     * cut off (ADR 0008). The scan runs in the background from the moment the container exists. Every stored ride is
     * queued in the outbox, a recovered one and the rides already on disk at start included.
     */
    val rides: RideLibrary =
        RideLibrary(
            store = rideStore,
            scope = diskScope,
            report = { note, cause -> Log.w(TAG, "$note: ${cause.javaClass.simpleName}: ${cause.message}") },
            onSaved = { stored ->
                sync.rideFinished(
                    OutboxEntry(
                        rideId = stored.id,
                        enqueuedAtMillis = nextEnqueueTime(),
                        startedAtMillis = stored.startEpochMillis,
                        durationSeconds = stored.durationSeconds,
                        title = stored.title,
                    ),
                )
            },
            routesOf = ::fitRoutesOf,
        ).also { it.start() }

    /**
     * Bluetooth: the radio and the permissions, the scanner and the GATT transports (ADR 0010). Everything above it sees
     * only `core`'s interfaces, so a test gives the container a fake one.
     */
    val ble: BleStack = bleStack(appContext, scope)

    /** What the Bluetooth session files are written to and shared from: `filesDir/ble-logs/` (ADR 0010). */
    val bleLogs: BleLogStore = BleLogStore(bleLogDirectory)

    /** The raw Bluetooth logs are written by one thread of their own, so a slow disk never reaches the radio or the ride. */
    private val bleLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val recordBle: () -> Boolean = { settings.value.recordBleSessions }

    // The zip of an earlier share of the Bluetooth logs is deleted at the next launch (ADR 0010).
    init {
        bleLogScope.launch { BleLogShare.clearArchives(appContext) }
    }

    private val trainerTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.TRAINER,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.trainer.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    private val strapTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.STRAP,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.strap.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    private val clickTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.CLICK,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.click.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    /**
     * The paired trainer, strap and Click: **long-lived**, owned by this container and not by any ride, connecting from
     * the moment the app starts and reconnecting whenever Bluetooth allows (ADR 0010, ADR 0012). The rides only use them.
     * A change of the Click's developer options replaces the Click's device (a short reconnect).
     */
    val pairedDevices: PairedDevices =
        PairedDevices(
            scope = scope,
            store =
                DeviceStore(
                    PreferenceDataStoreFactory.create(scope = settingsScope) {
                        appContext.preferencesDataStoreFile(devicesName)
                    },
                ),
            environment = ble.environment,
            transports = { role ->
                when (role) {
                    DeviceRole.Trainer -> trainerTransports
                    DeviceRole.Strap -> strapTransports
                    DeviceRole.Click -> clickTransports
                }
            },
            clickOptions = settingsRepository.settings.map { it.click }.distinctUntilChanged().map { it.toOptions() },
            connectStrategy = {
                if (settings.value.connectInBackground) ConnectStrategy.HYBRID else ConnectStrategy.LEGACY
            },
        )

    /** The Devices screen's scan, list and pairing. */
    val pairing: PairingController =
        PairingController(
            scope,
            ble.scanner,
            pairedDevices,
            canChange = { host.devices.value.canChange },
            clickOptions = { settings.value.click.toOptions() },
        )

    /** The Bluetooth notice's two fixes: the permission question and the system's Bluetooth settings. */
    val bluetooth: BluetoothController = AndroidBluetoothController(appContext, ble)

    private val bleRows = BleDevicesModel(pairedDevices, ble.environment)

    /**
     * The service for the one request a ride makes: the previous attempt of its session, for the overlay's line (docs/adr/0034). The
     * address and token are read at every request, like the plan's; with none set it asks nothing.
     */
    private val previousClient = ServiceClient({ serviceConfig.value ?: SyncConfig("", "") }, previousTransport)

    /**
     * The live coach (spec §12.1, docs/adr/0039): during a workout that asks for it, and with the rider's switch on, the service speaks short cues over the
     * video and the overlay shows them. Owned here, with the process, so it needs no activity (the video app is in front): the foreground service of a live
     * ride keeps the process alive. Its requests go through the same service client as everything else (the address and token read at every request, the
     * same `HttpURLConnection` stack), on a thread of its own that nothing else uses; it never speaks to anything but the service.
     */
    val liveCoach: LiveCoach =
        LiveCoach(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)).also { liveScope = it },
            client =
                LiveCoachClient(
                    ServiceClient(
                        { serviceConfig.value ?: SyncConfig("", "") },
                        liveTransport,
                        liveTransport,
                        streamer = liveTransport,
                    ),
                ),
            player = cuePlayer(appContext),
            enabled = { settings.value.liveCoach },
            nowMillis = { System.nanoTime() / NANOS_PER_MILLI },
        )

    /**
     * The one ride. The first bundled workout is selected at start. The developer setting chooses the real devices (the
     * default) or the simulated ones; a change of what is paired rebuilds a ride that has not started.
     */
    val host: RideHost =
        RideHost(
            factory =
                DeviceSourceFactory(
                    bluetooth = BleRideSessionFactory(pairedDevices, bleRows.ui),
                    simulated = SimulatedRideSessionFactory(),
                ),
            settings = effectiveSource,
            initialChoice = library.workouts.value.firstOrNull()?.let { RideChoice.Planned(it) } ?: RideChoice.Free,
            persistence = rides,
            // A route whose points arrive while the ride is ready is followed from the start: the ready ride is built again.
            rideTriggers =
                combine(pairedDevices.changes, routeLibrary.details) { changes, routes ->
                    changes to routes.downloaded.keys
                },
            downloadedRoutes = { routeLibrary.details.value.downloaded },
            previousAttempts = PreviousAttemptSource { hash -> previousAttemptOf(previousClient, hash) },
            liveCoach = liveCoach,
        )

    /** The media keys of the ride bar and of the Click: system volume and a media key for whichever app plays. */
    val media: MediaController = mediaFactory(appContext)

    /**
     * **The one routing rule** (spec §4.7, ADR 0012): core's [ActionRouter] over the host's current session and the media.
     * The Click's gesture layer calls it, and so do the ride screen's buttons (`RideActions` delegates to it), so the two
     * cannot diverge.
     */
    val router: ActionRouter = ActionRouter({ host.currentSession() }, media, CoachControl { liveCoach.mute() })

    /**
     * The *Learn buttons* flow: asks for the five roles in turn, from the Click's presses, and saves the mapping. Offered
     * only with real devices and no live ride; the handlebar says nothing while it runs.
     */
    val learning: ButtonLearning =
        ButtonLearning(
            scope = scope,
            buttons = pairedDevices.clickButtons,
            current = { settings.value.buttonMapping },
            save = { mapping -> settingsRepository.update { it.copy(buttonMapping = mapping) } },
            canLearn = { host.devices.value.let { it.canChange && !it.simulated } },
        )

    /**
     * The handlebar: the Click's presses, through the mapping and the gesture layer, into [router]. It runs **in this
     * container's scope, with no activity**, kept alive behind a video app by the overlay's foreground service, so a press
     * with the app in the background reaches the ride exactly as one with it on screen does (ADR 0012).
     */
    val handlebar: HandlebarService =
        HandlebarService(
            scope = scope,
            buttons = pairedDevices.clickButtons,
            mapping = { settings.value.buttonMapping },
            timing =
                settingsRepository.settings
                    .map { it.click.holdThresholdMillis to it.click.volumeRepeatMillis }
                    .distinctUntilChanged()
                    .map { (hold, repeat) -> GestureTiming(hold.toLong(), repeat.toLong()) },
            router = router,
            nowMillis = elapsedMillis,
            suspended = { learning.active },
        )

    init {
        // Connect what is remembered, with no user action. Started **here, after the properties above are assigned**, and
        // not where `pairedDevices` is declared: the first transport opens on another thread within milliseconds and its
        // log header reads `pairedDevices` (the model name), which would still be null if the container were being built.
        pairedDevices.start()
        handlebar.start()
        // A ride that goes live while the buttons are being learned ends the lesson: the handlebar is the ride's again.
        scope.launch {
            host.devices
                .map { it.canChange }
                .distinctUntilChanged()
                .collect { if (!it) learning.cancel() }
        }
        // A changed service address or token takes effect without a restart: once it has settled (typing a token is one
        // change per letter), the queue is tried again, refused rides and a rejected token included (ADR 0014 section 8).
        scope.launch {
            settings
                .map { it.syncUrl to it.syncToken.value }
                .distinctUntilChanged()
                .collectLatest {
                    delay(syncSettleMillis)
                    syncProvider.retryRejected()
                    sync.kick(retryRefused = true)
                }
        }
        // The planned workouts and the profile follow the settings file itself (not the copy in [settings], which starts as the
        // defaults and may be a step behind the file): the first value read from disk starts them, every later one is a change.
        // The profile is told at once, in the same step, so that what is in force is known before anything is built from it.
        scope.launch {
            settingsRepository.settings.collect {
                serviceConfig.value = SyncConfig(it.syncUrl, it.syncToken.value)
                profile.settingsChanged()
            }
        }
        // The cache first, so the list is there with no network, then a request as soon as the settings have been read. After
        // that, a changed address or token hides or shows the section at once and asks again once it has settled, like the
        // upload above. A change during a request abandons that request: it was made with the old address or token.
        scope.launch {
            plan.load()
            var first = true
            serviceConfig.filterNotNull().collectLatest {
                plan.settingsChanged()
                if (first) {
                    first = false
                    plan.refresh(PlanRefresh.START)
                } else {
                    delay(syncSettleMillis)
                    plan.refresh(PlanRefresh.SETTINGS_CHANGED)
                }
            }
        }
        // The profile the same way, on a coroutine of its own: a slow plan never holds up the FTP. Its cache is read first (the
        // ride waits for it, see [effectiveSettings]), then the PATCH of an edit that was never sent and the GET go out.
        scope.launch {
            profile.load()
            var first = true
            serviceConfig.filterNotNull().collectLatest {
                profile.settingsChanged()
                if (first) {
                    first = false
                    profile.refresh(ProfileRefresh.START)
                } else {
                    delay(syncSettleMillis)
                    profile.refresh(ProfileRefresh.SETTINGS_CHANGED)
                }
            }
        }
        // The routes the same way, on a coroutine of their own: the cached list first, then one request and a change of address or token.
        // What the next days' planned workouts name is fetched as soon as the plan is known and whenever it changes (ADR 0037).
        scope.launch {
            routeLibrary.load()
            recovery.load()
            var first = true
            serviceConfig.filterNotNull().collectLatest {
                routeLibrary.settingsChanged()
                recovery.settingsChanged()
                if (first) {
                    first = false
                    routeLibrary.refreshList(Refresh.START)
                } else {
                    delay(syncSettleMillis)
                    routeLibrary.refreshList(Refresh.SETTINGS_CHANGED)
                    if (hasVariantToday(plan.state.value.workouts)) recovery.refresh(Refresh.SETTINGS_CHANGED)
                }
            }
        }
        scope.launch {
            plan.state
                .map { it.workouts }
                .distinctUntilChanged()
                .collectLatest { workouts ->
                    routeLibrary.ensure(RouteLibrary.routeIdsOf(workouts, today()), Refresh.START)
                    if (hasVariantToday(workouts)) recovery.refresh(Refresh.START)
                }
        }
        // The last success survives a restart; a disk that refuses the one-line file costs nothing but that.
        scope.launch(Dispatchers.IO) {
            var written = lastUploadAtStart
            sync.report
                .map { it.lastSuccessMillis }
                .distinctUntilChanged()
                .collect { millis ->
                    if (millis != null && millis != written) {
                        written = millis
                        try {
                            lastUpload.write(millis)
                        } catch (e: IOException) {
                            Log.w(TAG, "The time of the last upload could not be kept: ${e.javaClass.simpleName}")
                        }
                    }
                }
        }
        // What the sync is doing, for adb logcat at the bench. Counts and plain reasons only: never an address, a token or a ride.
        scope.launch {
            sync.status.collect { Log.i(TAG, "Sync: $it") }
        }
    }

    /** Whether one of the activities is on screen: the overlay window shows only while the app is not. */
    val visibility: AppVisibility = AppVisibility().also { (appContext as? Application)?.let(it::register) }

    /** The activity's side of the overlay: permissions, the screens that grant them, starting the service. */
    val overlay: OverlayController = AndroidOverlayController(appContext)

    /** What the overlay service reads: this container's ride, settings and visibility (ADR 0006). */
    val overlayModel: OverlayModel = ContainerOverlayModel(this)

    /**
     * The Ready screen came on screen: asks the service for the plan and the profile, each if it has not been asked in the last
     * minute (and sends an edit of the profile that never got through).
     */
    fun planScreenOpened() {
        scope.launch { plan.refresh(PlanRefresh.SCREEN_OPENED) }
        scope.launch { profile.refresh(ProfileRefresh.SCREEN_OPENED) }
        scope.launch { routeLibrary.refreshList(Refresh.SCREEN_OPENED) }
        scope.launch {
            routeLibrary.ensure(RouteLibrary.routeIdsOf(plan.state.value.workouts, localToday()), Refresh.SCREEN_OPENED)
        }
        scope.launch { if (hasVariantToday(plan.state.value.workouts)) recovery.refresh(Refresh.SCREEN_OPENED) }
    }

    /** The Planned section's refresh button: asks the service now for the plan and the profile (one gesture, both), however recently. */
    fun refreshPlan() {
        scope.launch { plan.refresh(PlanRefresh.REQUESTED) }
        scope.launch { profile.refresh(ProfileRefresh.REQUESTED) }
        scope.launch { routeLibrary.refreshList(Refresh.REQUESTED) }
        scope.launch {
            routeLibrary.ensure(RouteLibrary.routeIdsOf(plan.state.value.workouts, localToday()), Refresh.REQUESTED)
        }
    }

    /**
     * The rider chose the route for a free ride, or the flat road for null (spec §11.4, ADR 0037): the ride is built on it at once and,
     * if its points are not on the tablet yet, they are fetched now. The ride never waits for them: until they are here it is flat.
     */
    fun chooseRoute(id: String?) {
        host.selectFreeRoute(id)
        if (id != null) scope.launch { routeLibrary.ensure(id, Refresh.REQUESTED) }
    }

    /** Whether a workout planned for today has an easier version: the only time the recovery state is worth asking for. */
    private fun hasVariantToday(workouts: List<app.overdrive.core.plan.PlannedWorkout>): Boolean {
        val day = localToday()
        return workouts.any { it.date == day && it.variant != null }
    }

    /**
     * The routes of a ride that is completed from its log or its stored record at a start (spec §11.4): a ride with no workout names its
     * route in its header, and the points are read from the tablet's disk. A ride of a workout has no way to say which step was on which
     * route, so its file has no positions.
     */
    private fun fitRoutesOf(record: RideRecord): FitRoutes {
        val id = record.ride.routeId.takeIf { record.ride.workoutId == null } ?: return FitRoutes.None
        val route = routeLibrary.details.value.downloaded[id] ?: routeOnDisk(id) ?: return FitRoutes.None
        return FitRoutes { step -> route.takeIf { step == null } }
    }

    /** The points of a route as the tablet kept them, read synchronously, or null when there are none or they are damaged. */
    private fun routeOnDisk(id: String): Route? =
        try {
            File(routePointsDirectory, id)
                .takeIf { it.isFile }
                ?.readText()
                ?.let { CacheText.decode("route", it) }
                ?.let { RoutesApiJson.decodeRoute(it.body) }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Changes the settings from the UI; validation happens in the repository. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(change) }
    }

    /**
     * The Settings screen changes FTP, mass, CdA or Crr (ADR 0021). [change] is given the settings **as the screen shows them**
     * (the profile on top, [effectiveSettings]) and returns them changed, so a step goes from the number the rider sees.
     *
     * - **With a profile in force** the values that differ are put on top of the profile at once and sent to the service
     *   (`PATCH /v1/profile`); if that cannot be done now the change stays, marked "Not sent yet", and is sent later. The tablet's
     *   own stored values are left alone.
     * - **With none** (no service set up, or never reached and nothing cached) it is an ordinary change of the tablet's settings,
     *   exactly as before there was a profile.
     */
    fun changeRider(change: (AppSettings) -> AppSettings) {
        val inForce = profile.state.value.profile
        if (inForce != null) {
            val shown = settings.value.withProfile(inForce)
            val patch = riderPatch(shown, change(shown))
            if (patch.isEmpty) return
            if (profile.edit(patch)) {
                scope.launch { profile.refresh(ProfileRefresh.EDITED) }
                return
            }
        }
        updateSettings(change)
    }

    fun resetOverlayPosition() {
        scope.launch { settingsRepository.resetOverlayPosition() }
    }

    /** The Devices screen's **Retry now** for the Click (it also starts over a Click that is connected but silent). */
    fun retryClick() {
        pairedDevices.controls(DeviceRole.Click)?.retryNow()
    }

    /** Stops the ride and everything else the container started. Only tests call it; the process owns it otherwise. */
    fun close() {
        liveCoach.close()
        handlebar.close()
        learning.cancel()
        sync.close()
        host.close()
        pairing.stop()
        pairedDevices.close()
        ble.close()
        scope.cancel()
        liveScope?.cancel()
        bleLogScope.cancel()
        diskScope.cancel()
        settingsScope.cancel()
    }

    /**
     * The Settings screen's **Send now**: tries the queue at once, and tries again what the service refused and a token it
     * rejected (the rider may have fixed the cause on the server). Harmless when nothing waits.
     */
    fun sendNow() {
        syncProvider.retryRejected()
        sync.kick(retryRefused = true)
    }

    companion object {
        /** An address or token that has not changed for this long is tried (ADR 0014 section 8). */
        const val SYNC_SETTLE_MILLIS = 1_500L

        private const val TAG = "OverdriveRides"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Routes kept on the tablet: 30 files of at most a few hundred kilobytes (5000 points), the oldest dropped first. */
        private const val MAX_ROUTE_FILES = 30
    }
}
