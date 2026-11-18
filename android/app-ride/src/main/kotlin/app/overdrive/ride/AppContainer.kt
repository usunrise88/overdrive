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
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.plan.PlanRefresh
import app.overdrive.core.plan.PlanRepository
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpSyncProvider
import app.overdrive.core.sync.HttpTransport
import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.SyncCoordinator
import app.overdrive.data.plan.FilePlanCache
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
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.overlay.AndroidOverlayController
import app.overdrive.ride.overlay.AppVisibility
import app.overdrive.ride.overlay.ContainerOverlayModel
import app.overdrive.ride.overlay.OverlayController
import app.overdrive.ride.overlay.OverlayModel
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.SettingsRepository
import app.overdrive.ride.ui.AndroidBluetoothController
import app.overdrive.ride.ui.BluetoothController
import app.overdrive.ride.workouts.WorkoutLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
 * @param today the tablet's local date, `YYYY-MM-DD`, for the range asked of the service; a test fixes it.
 * @param syncSettleMillis how long the service address and token must stay as typed before the upload is tried again with
 * them, so that typing a token does not send one request per letter.
 * @param mediaFactory the media keys of the ride bar and of the Click; a test records them.
 * @param elapsedMillis the monotonic clock of the gesture layer (a hold is 500 ms of it); Robolectric's is a fake one, so a
 * test of real holds passes a real clock.
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
    today: () -> String = { LocalDate.now().toString() },
    syncSettleMillis: Long = SYNC_SETTLE_MILLIS,
    mediaFactory: (Context) -> MediaController = { AudioManagerMediaController(it) },
    elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
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

    /**
     * The planned workouts (spec §5.7, ADR 0017): fetched from the same service, with the same address and token, at start,
     * when the Ready screen opens (at most once a minute) and when the rider asks; kept on disk so the list works offline.
     * With no address or token nothing is asked and nothing is shown. The settings are read at every request.
     */
    val plan: PlanRepository =
        PlanRepository(
            config = { settings.value.let { SyncConfig(it.syncUrl, it.syncToken.value) } },
            transport = planTransport,
            cache = FilePlanCache(planFile),
            today = today,
            wallMillis = System::currentTimeMillis,
            elapsedMillis = { System.nanoTime() / NANOS_PER_MILLI },
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
            settings = settingsRepository.settings,
            initialChoice = library.workouts.value.firstOrNull()?.let { RideChoice.Planned(it) } ?: RideChoice.Free,
            persistence = rides,
            rideTriggers = pairedDevices.changes,
        )

    /** The media keys of the ride bar and of the Click: system volume and a media key for whichever app plays. */
    val media: MediaController = mediaFactory(appContext)

    /**
     * **The one routing rule** (spec §4.7, ADR 0012): core's [ActionRouter] over the host's current session and the media.
     * The Click's gesture layer calls it, and so do the ride screen's buttons (`RideActions` delegates to it), so the two
     * cannot diverge.
     */
    val router: ActionRouter = ActionRouter({ host.currentSession() }, media)

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
        // The planned workouts: the cache first, so the list is there with no network, then a request once the settings have
        // been read from disk (before that the address is empty and there is nothing to ask). After that, a changed address or
        // token hides or shows the section at once and asks again once it has settled, like the upload above.
        scope.launch {
            plan.load()
            val read = settingsRepository.settings.first()
            settings.first { it == read }
            val key = { s: AppSettings -> s.syncUrl to s.syncToken.value }
            val atStart = key(read)
            launch { plan.refresh(PlanRefresh.START) }
            var first = true
            settings
                .map(key)
                .distinctUntilChanged()
                .collectLatest { changed ->
                    if (first) {
                        first = false
                        if (changed == atStart) return@collectLatest
                    }
                    plan.settingsChanged()
                    delay(syncSettleMillis)
                    plan.refresh(PlanRefresh.SETTINGS_CHANGED)
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

    /** The Ready screen came on screen: asks the service for the plan if it has not been asked in the last minute. */
    fun planScreenOpened() {
        scope.launch { plan.refresh(PlanRefresh.SCREEN_OPENED) }
    }

    /** The Planned section's refresh button: asks the service now, however recently it was asked. */
    fun refreshPlan() {
        scope.launch { plan.refresh(PlanRefresh.REQUESTED) }
    }

    /** Changes the settings from the UI; validation happens in the repository. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(change) }
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
        handlebar.close()
        learning.cancel()
        sync.close()
        host.close()
        pairing.stop()
        pairedDevices.close()
        ble.close()
        scope.cancel()
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
    }
}
